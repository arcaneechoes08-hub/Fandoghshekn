package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "FandoghVpnChannel";

    // قفل اختصاصی برای همگام‌سازی تردها و جلوگیری از Race Condition
    private final Object mLock = new Object();

    static {
        System.loadLibrary("native-lib");
    }
    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private volatile String mVlessLink; // استفاده از volatile برای هماهنگی بین تردها

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if ("STOP".equals(action)) {
                stopVpn();
                stopSelf();
                return START_NOT_STICKY;
            }

            if (intent.hasExtra("VLESS_LINK")) {
                mVlessLink = intent.getStringExtra("VLESS_LINK");
            }
        }

        synchronized (mLock) {
            // منتظر ماندن و نابود کردن ترد قبلی در صورت وجود، برای جلوگیری از تداخل هسته‌ها
            if (mThread != null && mThread.isAlive()) {
                mThread.interrupt();
                try {
                    mThread.join(500); // ۵۰۰ میلی‌ثانیه فرصت برای خروج امن ترد قبلی
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            stopVpn(); // پاکسازی کامل منابع قبلی
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        mThread = new Thread(this, "FandoghVpnThread");
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        File baseDir = getFilesDir();
        try {
            showStatus("🔍 بارگذاری هسته هوشمند...");

            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File coreBin = new File(nativeDir, "libxray.so");
            if (!coreBin.exists()) {
                throw new Exception("هسته سیستمی یافت نشد!");
            }

            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");

            // ساخت ایمن اینترفیس تونل با ساختار درخواستی اندروید
            Builder builder = new Builder();
            synchronized (mLock) {
                mInterface = builder.setSession("FandoghShekan")
                        .setMtu(1500)
                        .addAddress("172.19.0.1", 24)
                        .addAddress("fd00:1:2:3::1", 126)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .addDisallowedApplication(getPackageName()) // معافیت برنامه از تونل جهت جلوگیری از لوپ ترافیکی
                        .establish();
            }

            if (mInterface == null) {
                throw new Exception("مجوز ایجاد تونل صادر نشد!");
            }

            int fd = mInterface.getFd();
            AppLog.add(TAG, "TUN fd ساخته شد: " + fd);

            // تولید کانفیگ با مکانیزم پارس جدید و ضد کرش
            generateSingBoxConfig(mVlessLink, baseDir, fd);
            AppLog.add(TAG, "config.json با موفقیت نوشته شد.");

            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) {
                throw new Exception("اجرای هسته با خطا مواجه شد!");
            }
            AppLog.add(TAG, "هسته با PID=" + pid + " اجرا شد.");

            showStatus("🚀 فندق‌شکن متصل شد.");

            // حلقه گوش به زنگ برای زنده نگه داشتن ترد سرویس
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            AppLog.add(TAG, "ترد سرویس متوقف شد.");
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            AppLog.add(TAG, "❌ خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    /**
     * 🚦 بخش عبور ترافیک (بهینه‌سازی شده با الگوریتم ساختاریافته کاملاً امن)
     */
    private void generateSingBoxConfig(String link, File baseDir, int tunFd) throws Exception {
        if (link == null || !link.toLowerCase().startsWith("vless://")) {
            throw new Exception("لینک کانفیگ نامعتبر است (فقط vless پشتیبانی می‌شود).");
        }

        String host, uuid;
        int port;
        String type = "tcp", security = "none", path = "/", sni = "", wsHost = "", fp = "";

        try {
            // استفاده از کلاس کمکی Uri برای پارس بی‌نقص بدون نیاز به ساب‌استرینگ‌های خطرناک
            Uri uri = Uri.parse(link);
            
            uuid = uri.getUserInfo();
            host = uri.getHost();
            port = uri.getPort();

            if (uuid == null || host == null || port == -1) {
                throw new Exception("اطلاعات حیاتی لینک (UUID, Host, Port) ناقص است.");
            }

            // استخراج پارامترهای اختیاری کوئری به صورت امن
            if (uri.getQuery() != null) {
                type = uri.getQueryParameter("type") != null ? uri.getQueryParameter("type") : "tcp";
                security = uri.getQueryParameter("security") != null ? uri.getQueryParameter("security") : "none";
                sni = uri.getQueryParameter("sni") != null ? uri.getQueryParameter("sni") : "";
                wsHost = uri.getQueryParameter("host") != null ? uri.getQueryParameter("host") : "";
                path = uri.getQueryParameter("path") != null ? uri.getQueryParameter("path") : "/";
                fp = uri.getQueryParameter("fp") != null ? uri.getQueryParameter("fp") : "";
            }

        } catch (Exception e) {
            throw new Exception("خطای ساختاری در ساختار لینک vless: " + e.getMessage());
        }

        AppLog.add(TAG, "پارس لینک موفق: host=" + host + " port=" + port);

        JSONObject root = new JSONObject();

        // --- لاگ هسته ---
        JSONObject log = new JSONObject();
        log.put("level", "warn");
        log.put("timestamp", true);
        root.put("log", log);

        // --- DNS ---
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        JSONObject remoteDns = new JSONObject();
        remoteDns.put("tag", "remote-dns");
        remoteDns.put("address", "8.8.8.8");
        remoteDns.put("detour", "proxy");
        dnsServers.put(remoteDns);
        dns.put("servers", dnsServers);
        dns.put("final", "remote-dns");
        dns.put("strategy", "prefer_ipv4");
        root.put("dns", dns);

        // --- ورودی (TUN) ---
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("fd", tunFd);
        tunIn.put("mtu", 1500);
        tunIn.put("inet4_address", "172.19.0.1/24");
        tunIn.put("inet6_address", "fd00:1:2:3::1/126");
        tunIn.put("auto_route", false);
        tunIn.put("strict_route", false);
        tunIn.put("stack", "gvisor");
        tunIn.put("sniff", true);
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // --- خروجی‌ها ---
        JSONArray outbounds = new JSONArray();

        JSONObject vlessOut = new JSONObject();
        vlessOut.put("type", "vless");
        vlessOut.put("tag", "proxy");
        vlessOut.put("server", host);
        vlessOut.put("server_port", port);
        vlessOut.put("uuid", uuid);

        if ("tls".equals(security) || "reality".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            tls.put("server_name", sni.isEmpty() ? host : sni);
            if (!fp.isEmpty()) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
            vlessOut.put("tls", tls);
        }

        if ("ws".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            transport.put("path", path);
            if (!wsHost.isEmpty()) {
                JSONObject headers = new JSONObject();
                headers.put("Host", wsHost);
                transport.put("headers", headers);
            }
            vlessOut.put("transport", transport);
        }

        outbounds.put(vlessOut);

        JSONObject directOut = new JSONObject();
        directOut.put("type", "direct");
        directOut.put("tag", "direct");
        outbounds.put(directOut);

        root.put("outbounds", outbounds);

        // --- قوانین مسیریابی ---
        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();

        JSONObject privateRule = new JSONObject();
        privateRule.put("ip_is_private", true);
        privateRule.put("outbound", "direct");
        rules.put(privateRule);

        route.put("rules", rules);
        route.put("final", "proxy");
        route.put("auto_detect_interface", true);
        root.put("route", route);

        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void stopVpn() {
        // همگام‌سازی کامل متد توقف برای جلوگیری از کرش‌های همزمانی چرخه‌حیات اندروید
        synchronized (mLock) {
            try {
                stopCoreNative();
            } catch (Exception e) {
                Log.e(TAG, "خطا در توقف هسته نیتیو: " + e.getMessage());
            }

            if (mInterface != null) {
                try {
                    mInterface.close();
                } catch (Exception e) {
                    Log.e(TAG, "خطا در بستن اینترفیس تونل: " + e.getMessage());
                }
                mInterface = null;
            }

            stopForeground(true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Fandogh VPN Service", NotificationManager.IMPORTANCE_LOW
            );
            channel.setSound(null, null);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        // استفاده ایمن از پرچم تایید شده در اندروید ۱۴ به بالا
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال ایمن برقرار است 🛡️")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(mainPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
