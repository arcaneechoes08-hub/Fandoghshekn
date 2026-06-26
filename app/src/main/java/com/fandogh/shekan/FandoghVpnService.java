package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
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

    // هماهنگ‌کننده وضعیت برای رسیور MainActivity
    public static volatile boolean isRunning = false;
    
    // قفل اختصاصی برای همگام‌سازی تردها و جلوگیری از نشت ترافیک
    private final Object mLock = new Object();

    static {
        System.loadLibrary("native-lib");
    }
    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private volatile String mVlessLink;

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
                AppLog.add(TAG, "🛑 سیگنال توقف دستی دریافت شد. در حال بستن تونل...");
                stopVpn();
                stopSelf();
                return START_NOT_STICKY;
            }

            if (intent.hasExtra("VLESS_LINK")) {
                mVlessLink = intent.getStringExtra("VLESS_LINK");
            }
        }

        synchronized (mLock) {
            if (mThread != null && mThread.isAlive()) {
                AppLog.add(TAG, "🔄 درخواست اتصال جدید؛ در حال نابود کردن ترد فعال قبلی...");
                mThread.interrupt();
                try {
                    mThread.join(500); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            stopVpn(); 
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        mThread = new Thread(this, "FandoghVpnThread");
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.add(TAG, "♻️ onDestroy سرویس فراخوانی شد. پاکسازی نهایی...");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        File baseDir = getFilesDir();
        try {
            showStatus("🔍 بارگذاری هسته هوشمند...");
            AppLog.add(TAG, "🎬 آغاز بکار لایه تونل‌زنی فندق‌شکن...");

            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File coreBin = new File(nativeDir, "libxray.so");
            if (!coreBin.exists()) {
                throw new Exception("هسته سیستمی لایه ۲ (libxray.so) یافت نشد!");
            }

            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");

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
                        .addDisallowedApplication(getPackageName()) 
                        .establish();
            }

            if (mInterface == null) {
                throw new Exception("سیستم‌عامل اندروید مجوز VpnService را صادر نکرد.");
            }

            int fd = mInterface.getFd();
            AppLog.add(TAG, "✅ [TUN Interface] با موفقیت ایجاد شد. FileDescriptor: " + fd);

            // ایجاد کانفیگ با سیستم لاگ‌گیری دقیق مسیر ترافیک
            generateSingBoxConfig(mVlessLink, baseDir, fd);

            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) {
                throw new Exception("هسته نیتیو با کد خطای " + pid + " متوقف شد.");
            }
            
            AppLog.add(TAG, "🚀 هسته نیتیو با موفقیت تزریق شد. PID = " + pid);
            showStatus("🚀 فندق‌شکن متصل شد.");
            isRunning = true;

            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            AppLog.add(TAG, "ℹ️ ترد لایه ۳ به صورت امن متوقف شد.");
        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ERROR: " + e.getMessage());
            AppLog.add(TAG, "❌ خطا در چرخه ران‌تایم: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    /**
     * 🚦 متد تولید کانفیگ همراه با مستندسازی و لاگ‌گیری دقیق نقشه عبور ترافیک
     */
    private void generateSingBoxConfig(String link, File baseDir, int tunFd) throws Exception {
        if (link == null || !link.toLowerCase().startsWith("vless://")) {
            throw new Exception("لینک ارسالی خالی یا فاقد پروتکل vless:// است.");
        }

        String host, uuid;
        int port;
        String type = "tcp", security = "none", path = "/", sni = "", wsHost = "", fp = "";

        try {
            Uri uri = Uri.parse(link);
            uuid = uri.getUserInfo();
            host = uri.getHost();
            port = uri.getPort();

            if (uuid == null || host == null || port == -1) {
                throw new Exception("اجزای ساختاری لینک (User/Host/Port) نامعتبر است.");
            }

            if (uri.getQuery() != null) {
                type = uri.getQueryParameter("type") != null ? uri.getQueryParameter("type") : "tcp";
                security = uri.getQueryParameter("security") != null ? uri.getQueryParameter("security") : "none";
                sni = uri.getQueryParameter("sni") != null ? uri.getQueryParameter("sni") : "";
                wsHost = uri.getQueryParameter("host") != null ? uri.getQueryParameter("host") : "";
                path = uri.getQueryParameter("path") != null ? uri.getQueryParameter("path") : "/";
                fp = uri.getQueryParameter("fp") != null ? uri.getQueryParameter("fp") : "";
            }
        } catch (Exception e) {
            throw new Exception("خطای پارسر اندروید روی کانفیگ: " + e.getMessage());
        }

        // 📝 لاگ‌گیری جزئیات مهندسی ترافیک (Traffic Routing Blueprint)
        AppLog.add(TAG, "🗺️ [نقشه مسیریابی ترافیک فندق‌شکن]");
        AppLog.add(TAG, "🔗 مشخصات سرور خروجی: " + host + ":" + port + " via " + type.toUpperCase());
        AppLog.add(TAG, "📥 [مسیر ورودی]: کل ترافیک دستگاه اندروید -> VpnService TUN (fd:" + tunFd + ") -> هسته کارگذاری شده");
        AppLog.add(TAG, "📍 [قانون ۱ - امنیتی دی‌ان‌اس]: تمام درخواست‌های UDP:53 -> هدایت به outbound:dns-out -> رمزنگاری و عبور از داخل تونل پروکسی جهت جلوگیری از نشت (DNS Leak)");
        AppLog.add(TAG, "📍 [قانون ۲ - ترافیک داخلی]: آی‌پ‌ی‌های رنج Private/شبکه داخلی -> هدایت به outbound:direct -> بدون فیلترشکن و مستقیم");
        AppLog.add(TAG, "📍 [قانون ۳ - ترافیک عمومی]: ترافیک بین‌الملل (باقی‌مانده) -> هدایت به outbound:proxy -> پروتکل VLESS (" + security + ")");

        JSONObject root = new JSONObject();

        // ۱. لاگ هسته (تغییر لایه لاگ به info برای دریافت ردپای پکت‌ها در لایه نیتیو)
        JSONObject log = new JSONObject();
        log.put("level", "info");
        log.put("timestamp", true);
        root.put("log", log);

        // ۲. سرویس هوشمند DNS هسته
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

        // ۳. تنظیمات ورودی اینترفیس لایه ۳
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("fd", tunFd); 
        tunIn.put("mtu", 1500);
        tunIn.put("auto_route", false);   // بر عهده VpnService اندروید است
        tunIn.put("strict_route", false);
        tunIn.put("stack", "gvisor");     // پشته شبکه gVisor برای پایداری پکت‌ها
        tunIn.put("sniff", true);         
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // ۴. خروجی‌ها (Outbounds)
        JSONArray outbounds = new JSONArray();

        // خروجی اصلی VLESS
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

        // خروجی دایرکت
        JSONObject directOut = new JSONObject();
        directOut.put("type", "direct");
        directOut.put("tag", "direct");
        outbounds.put(directOut);

        // خروجی مجزای دی‌ان‌اس (حل مشکل فریز شدن اینترنت)
        JSONObject dnsOut = new JSONObject();
        dnsOut.put("type", "dns");
        dnsOut.put("tag", "dns-out");
        outbounds.put(dnsOut);

        root.put("outbounds", outbounds);

        // ۵. قوانین مسیریابی نهایی هسته
        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();

        // قانون پکت‌های پروتکل DNS
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("protocol", "dns");
        dnsRule.put("outbound", "dns-out");
        rules.put(dnsRule);

        // قانون آی‌پی‌های خصوصی شبکه‌های محلی
        JSONObject privateRule = new JSONObject();
        privateRule.put("ip_is_private", true);
        privateRule.put("outbound", "direct");
        rules.put(privateRule);

        route.put("rules", rules);
        route.put("final", "proxy");
        route.put("auto_detect_interface", false); // عدم تداخل با لایه روت اندروید
        root.put("route", route);

        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
        AppLog.add(TAG, "💾 فایل کامپایل شده نهایی config.json با موفقیت در دایرکتوری خصوصی ذخیره شد.");
    }

    private void stopVpn() {
        synchronized (mLock) {
            isRunning = false;
            try {
                stopCoreNative();
                AppLog.add(TAG, "📉 هسته نیتیو متوقف شد.");
            } catch (Exception e) {
                Log.e(TAG, "خطا در بستن هسته: " + e.getMessage());
            }

            if (mInterface != null) {
                try {
                    mInterface.close();
                    AppLog.add(TAG, "🔒 اینترفیس TUN اندروید بسته شد و ترافیک به حالت عادی برگشت.");
                } catch (Exception e) {
                    Log.e(TAG, "خطا در بستن فایل دیسکریپتور: " + e.getMessage());
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
