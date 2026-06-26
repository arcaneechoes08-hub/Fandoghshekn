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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "FandoghVpnChannel";

    public static volatile boolean isRunning = false;
    private final Object mLock = new Object();

    static {
        System.loadLibrary("native-lib");
    }
    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

    private Thread mThread;
    private Thread mLogThread; 
    private ParcelFileDescriptor mInterface;
    private volatile String mVlessLink;

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.add(TAG, "📥 [onStartCommand] سرویس فندق‌شکن فراخوانی شد.");
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
                AppLog.add(TAG, "🔗 لینک VLESS جدید با موفقیت ذخیره شد.");
            }
        }

        synchronized (mLock) {
            if (mThread != null && mThread.isAlive()) {
                AppLog.add(TAG, "🔄 ریستارت امن ترد قبلی...");
                mThread.interrupt();
                try { mThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
        AppLog.add(TAG, "♻️ سرویس متوقف و حافظه پاکسازی شد.");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        File baseDir = getFilesDir();
        File logFile = new File(baseDir, "core.log");
        
        if (logFile.exists()) {
            logFile.delete(); // پاکسازی لاگ‌های حجیم قبلی برای شروع تازه
        }

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
            AppLog.add(TAG, "✅ [TUN Interface] ایجاد شد. FD: " + fd);

            generateSingBoxConfig(mVlessLink, baseDir, fd);

            isRunning = true;
            
            // 📡 فعال‌سازی رادار مانیتورینگ بلادرنگ فایل لاگ هسته
            startCoreLogStreamer(logFile);

            AppLog.add(TAG, "⚡ در حال فراخوانی متد نیتیو startCoreNative...");
            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) {
                throw new Exception("هسته نیتیو با کد خطای " + pid + " متوقف شد.");
            }
            
            AppLog.add(TAG, "🚀 هسته نیتیو با موفقیت تزریق شد. PID = " + pid);
            showStatus("🚀 فندق‌شکن متصل شد.");

            while (!Thread.currentThread().isInterrupted() && isRunning) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            AppLog.add(TAG, "ℹ️ ترد لایه ۳ متوقف شد.");
        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ERROR: " + e.getMessage());
            AppLog.add(TAG, "❌ خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateSingBoxConfig(String link, File baseDir, int tunFd) throws Exception {
        if (link == null || !link.toLowerCase().startsWith("vless://")) {
            throw new Exception("لینک ارسالی معتبر نیست.");
        }

        String host, uuid;
        int port;
        String type = "tcp", security = "none", path = "/", sni = "", wsHost = "", fp = "";
        String pbk = "", sid = "", serviceName = "";

        try {
            Uri uri = Uri.parse(link);
            uuid = uri.getUserInfo();
            host = uri.getHost();
            port = uri.getPort();

            if (uri.getQuery() != null) {
                type = uri.getQueryParameter("type") != null ? uri.getQueryParameter("type") : "tcp";
                security = uri.getQueryParameter("security") != null ? uri.getQueryParameter("security") : "none";
                sni = uri.getQueryParameter("sni") != null ? uri.getQueryParameter("sni") : "";
                wsHost = uri.getQueryParameter("host") != null ? uri.getQueryParameter("host") : "";
                path = uri.getQueryParameter("path") != null ? uri.getQueryParameter("path") : "/";
                fp = uri.getQueryParameter("fp") != null ? uri.getQueryParameter("fp") : "";
                pbk = uri.getQueryParameter("pbk") != null ? uri.getQueryParameter("pbk") : "";
                sid = uri.getQueryParameter("sid") != null ? uri.getQueryParameter("sid") : "";
                serviceName = uri.getQueryParameter("serviceName") != null ? uri.getQueryParameter("serviceName") : 
                             (uri.getQueryParameter("path") != null ? uri.getQueryParameter("path") : "");
            }
            
            AppLog.add(TAG, "🛠️ [Debug Parser] Host: " + host + " | Port: " + port + " | Type: " + type + " | Security: " + security);
            
        } catch (Exception e) {
            throw new Exception("خطا در پارس لینک: " + e.getMessage());
        }

        JSONObject root = new JSONObject();

        // ۱. تنظیمات حداکثری لاگ (TRACE) به همراه خروجی مستقیم روی دیسک
        JSONObject log = new JSONObject();
        log.put("level", "trace");
        log.put("output", new File(baseDir, "core.log").getAbsolutePath());
        log.put("timestamp", true);
        root.put("log", log);

        // ۲. ساختار هوشمند مانیتورینگ DNS هسته
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();

        JSONObject remoteDns = new JSONObject();
        remoteDns.put("tag", "remote-dns");
        remoteDns.put("address", "1.1.1.1");
        remoteDns.put("detour", "proxy"); 
        dnsServers.put(remoteDns);

        JSONObject directDns = new JSONObject();
        directDns.put("tag", "direct-dns");
        directDns.put("address", "8.8.8.8"); 
        directDns.put("detour", "direct"); 
        dnsServers.put(directDns);

        dns.put("servers", dnsServers);

        JSONArray dnsRules = new JSONArray();
        JSONObject bypassRule = new JSONObject();
        JSONArray bypassDomains = new JSONArray();
        bypassDomains.put(host); 
        if (!sni.isEmpty()) bypassDomains.put(sni);
        bypassRule.put("domain", bypassDomains);
        bypassRule.put("server", "direct-dns"); 
        dnsRules.put(bypassRule);

        dns.put("rules", dnsRules);
        dns.put("final", "remote-dns");
        dns.put("strategy", "prefer_ipv4");
        root.put("dns", dns);

        // ۳. تنظیمات اینترفیس TUN (✨ اصلاح طلایی: تغییر کلید به fd)
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("fd", tunFd); // تغییر حیاتی از file_descriptor به fd برای ممانعت از کرش پنهان هسته
        tunIn.put("inet4_address", "172.19.0.1/24");
        tunIn.put("inet6_address", "fd00:1:2:3::1/126");
        tunIn.put("mtu", 1500);
        tunIn.put("auto_route", false);   
        tunIn.put("strict_route", false);
        tunIn.put("stack", "mixed");       
        tunIn.put("sniff", true);         
        tunIn.put("sniff_override_destination", true);
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // ۴. خروجی‌ها (Outbounds)
        JSONArray outbounds = new JSONArray();

        JSONObject vlessOut = new JSONObject();
        vlessOut.put("type", "vless");
        vlessOut.put("tag", "proxy");
        vlessOut.put("server", host);
        vlessOut.put("server_port", port);
        vlessOut.put("uuid", uuid);
        vlessOut.put("packet_encoding", "xudp"); 

        if ("tls".equals(security) || "reality".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            tls.put("server_name", sni.isEmpty() ? host : sni);
            
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                reality.put("enabled", true);
                reality.put("public_key", pbk);
                reality.put("short_id", sid);
                tls.put("reality", reality);
            }

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
        } else if ("grpc".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "grpc");
            transport.put("service_name", serviceName);
            vlessOut.put("transport", transport);
        }
        
        outbounds.put(vlessOut);

        JSONObject directOut = new JSONObject();
        directOut.put("type", "direct");
        directOut.put("tag", "direct");
        outbounds.put(directOut);

        JSONObject dnsOut = new JSONObject();
        dnsOut.put("type", "dns");
        dnsOut.put("tag", "dns-out");
        outbounds.put(dnsOut);

        root.put("outbounds", outbounds);

        // ۵. قوانین روتینگ هوشمند (شکار همزمان بر اساس پروتکل و پورت ۵۳ سیستم‌عامل)
        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();

        JSONObject dnsRule = new JSONObject();
        JSONArray portArray = new JSONArray(); portArray.put(53);
        JSONArray protoArray = new JSONArray(); protoArray.put("dns");
        dnsRule.put("port", portArray);
        dnsRule.put("protocol", protoArray);
        dnsRule.put("outbound", "dns-out");
        rules.put(dnsRule);

        JSONObject privateRule = new JSONObject();
        JSONArray privateCidrs = new JSONArray();
        privateCidrs.put("10.0.0.0/8");
        privateCidrs.put("172.16.0.0/12");
        privateCidrs.put("192.168.0.0/16");
        privateCidrs.put("127.0.0.0/8");
        privateRule.put("ip_cidr", privateCidrs);
        privateRule.put("outbound", "direct");
        rules.put(privateRule);

        route.put("rules", rules);
        route.put("final", "proxy"); 
        route.put("auto_detect_interface", true); 
        root.put("route", route);

        AppLog.add(TAG, "⚙️ [Config Generated Flawlessly]");

        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void startCoreLogStreamer(final File logFile) {
        mLogThread = new Thread(() -> {
            AppLog.add(TAG, "📡 رادار مانیتورینگ فایل داخلی لاگ هسته فعال شد.");
            try {
                int timeout = 0;
                while (!logFile.exists() && isRunning && timeout < 20) {
                    Thread.sleep(200);
                    timeout++;
                }

                if (!isRunning || !logFile.exists()) {
                    AppLog.add(TAG, "⚠️ فایل لوگر هسته یافت نشد. منتظر شروع فرآیند نیتیو...");
                    return;
                }

                try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                    while (isRunning && !Thread.currentThread().isInterrupted()) {
                        String line = br.readLine();
                        if (line != null) {
                            if (!line.trim().isEmpty()) {
                                AppLog.add("CORE_INTERNAL", "⚙️ " + line.trim());
                            }
                        } else {
                            Thread.sleep(100); // تاخیر بهینه برای خواندن خطوط جدید فلاش شده روی دیسک
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "ترد لاگ هسته متوقف شد.");
            } catch (Exception e) {
                Log.e(TAG, "خطا در استریم لاگ هسته: " + e.getMessage());
            }
        }, "FandoghCoreLogThread");
        mLogThread.start();
    }

    private void stopVpn() {
        synchronized (mLock) {
            isRunning = false;
            
            if (mLogThread != null && mLogThread.isAlive()) {
                mLogThread.interrupt();
                mLogThread = null;
            }

            try {
                stopCoreNative();
                AppLog.add(TAG, "📉 دستور قطع اتصال هسته صادر شد.");
            } catch (Exception e) {
                Log.e(TAG, "خطا در بستن هسته: " + e.getMessage());
            }

            if (mInterface != null) {
                try {
                    mInterface.close();
                    AppLog.add(TAG, "🔒 اینترفیس TUN بسته شد.");
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال ایمن برقرار است 🛡️")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
                                    }
                
