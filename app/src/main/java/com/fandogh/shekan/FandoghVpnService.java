package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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

    // 💥 وصل کردن کدهای C به جاوا
    static {
        System.loadLibrary("native-lib");
    }
    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private String mVlessLink;

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            // پردازش دستور توقف از سمت MainActivity
            if ("STOP".equals(action)) {
                stopVpn();
                stopSelf(); // نابودی کامل سرویس
                return START_NOT_STICKY;
            }

            // دریافت لینک با کلید صحیح (همگام با MainActivity)
            if (intent.hasExtra("VLESS_LINK")) {
                mVlessLink = intent.getStringExtra("VLESS_LINK");
            }
        }

        if (mThread != null && mThread.isAlive()) stopVpn();

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
            if (!coreBin.exists()) throw new Exception("هسته سیستمی یافت نشد!");
            coreBin.setExecutable(true);

            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");

            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1500) // 👈 اضافه شدن MTU برای جلوگیری از دراپ پکت
                    .addAddress("172.19.0.1", 24)
                    .addAddress("fd00:1:2:3::1", 126)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) throw new Exception("مجوز ایجاد تونل صادر نشد!");

            int fd = mInterface.getFd();
            AppLog.add(TAG, "TUN fd ساخته شد: " + fd);

            // 🚦 بخش عبور ترافیک: تولید config.json برای هسته sing-box
            generateSingBoxConfig(mVlessLink, baseDir, fd);
            AppLog.add(TAG, "config.json با موفقیت نوشته شد.");

            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) throw new Exception("اجرای هسته با خطا مواجه شد!");
            AppLog.add(TAG, "هسته با PID=" + pid + " اجرا شد.");

            showStatus("🚀 فندق‌شکن متصل شد.");

            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            // خروج امن از حلقه
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            AppLog.add(TAG, "❌ خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    /**
     * 🚦 بخش عبور ترافیک
     * این متد لینک vless را پارس کرده و یک config.json استاندارد برای هسته sing-box
     * می‌سازد: ورودی تونل (tun-in) از روی fd ساخته‌شده توسط VpnService، خروجی پروکسی (vless)،
     * و قوانین مسیریابی که مشخص می‌کنند چه ترافیکی از کجا عبور کند.
     */
    private void generateSingBoxConfig(String link, File baseDir, int tunFd) throws Exception {
        if (link == null || !link.startsWith("vless://")) {
            throw new Exception("لینک کانفیگ نامعتبر است (فقط vless پشتیبانی می‌شود).");
        }

        String host = "", uuid = "", security = "none", type = "tcp",
                path = "/", sni = "", wsHost = "", fp = "";
        int port = 443;

        try {
            String uriBody = link.substring(8); // حذف "vless://"
            int hashIdx = uriBody.indexOf("#");
            if (hashIdx != -1) uriBody = uriBody.substring(0, hashIdx);

            String[] querySplit = uriBody.split("\\?", 2);
            String credentialsAndServer = querySplit[0];
            String queryParams = querySplit.length > 1 ? querySplit[1] : "";

            int atIdx = credentialsAndServer.lastIndexOf("@");
            uuid = credentialsAndServer.substring(0, atIdx);
            String serverPart = credentialsAndServer.substring(atIdx + 1);

            int colonIdx = serverPart.lastIndexOf(":");
            host = serverPart.substring(0, colonIdx);
            port = Integer.parseInt(serverPart.substring(colonIdx + 1));

            if (!queryParams.isEmpty()) {
                for (String param : queryParams.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length < 2) continue;
                    String key = kv[0];
                    String val = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    switch (key) {
                        case "type":     type     = val; break;
                        case "security": security = val; break;
                        case "sni":      sni      = val; break;
                        case "host":     wsHost   = val; break;
                        case "path":     path     = val; break;
                        case "fp":       fp       = val; break;
                    }
                }
            }
        } catch (Exception e) {
            throw new Exception("خطا در پارس کردن لینک vless");
        }

        AppLog.add(TAG, "پارس لینک: host=" + host + " port=" + port + " type=" + type + " security=" + security);

        JSONObject root = new JSONObject();

        // --- لاگ هسته ---
        JSONObject log = new JSONObject();
        log.put("level", "warn");
        log.put("timestamp", true);
        root.put("log", log);

        // --- DNS: همه‌ی کوئری‌های DNS از داخل تونل پروکسی عبور می‌کنند تا لو نروند ---
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

        // --- ورودی: همان TUN ساخته‌شده توسط VpnService (هیچ روتینگ سیستمی اضافه‌ای لازم نیست) ---
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("fd", tunFd);
        tunIn.put("mtu", 1500);
        tunIn.put("inet4_address", "172.19.0.1/24");
        tunIn.put("inet6_address", "fd00:1:2:3::1/126");
        tunIn.put("auto_route", false);   // مسیریابی توسط خود Android VpnService انجام شده
        tunIn.put("strict_route", false);
        tunIn.put("stack", "gvisor");
        tunIn.put("sniff", true);
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // --- خروجی‌ها: پروکسی vless + مسیر مستقیم برای آی‌پی‌های داخلی/خصوصی ---
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

        // --- قوانین مسیریابی: آی‌پی‌های خصوصی/داخلی مستقیم، بقیه از پروکسی ---
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

        AppLog.add(TAG, "JSON Config:\n" + root.toString(2));

        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void stopVpn() {
        try {
            stopCoreNative();

            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
            stopForeground(true);
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Fandogh VPN Service", NotificationManager.IMPORTANCE_LOW
            );
            // حذف ویبره و صدا از نوتیفیکیشن سرویس دائمی
            channel.setSound(null, null);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال ایمن برقرار است 🛡️")
                .setSmallIcon(android.R.drawable.ic_secure) // مطمئن شوید این آیکون وجود دارد
                .setContentIntent(mainPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 👈 جلوگیری از کشیده شدن نوتیفیکیشن توسط کاربر
                .build();
    }
}
