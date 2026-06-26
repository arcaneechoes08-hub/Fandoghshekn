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

    public static volatile boolean isRunning = false;
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
        AppLog.add(TAG, "📥 [onStartCommand] سرویس فندق‌شکن فراخوانی شد.");
        if (intent != null) {
            String action = intent.getAction();
            AppLog.add(TAG, "📡 اکشن دریافتی از اینتنت: " + (action != null ? action : "NULL"));

            if ("STOP".equals(action)) {
                AppLog.add(TAG, "🛑 سیگنال توقف دستی دریافت شد. در حال بستن تونل...");
                stopVpn();
                stopSelf();
                return START_NOT_STICKY;
            }

            if (intent.hasExtra("VLESS_LINK")) {
                mVlessLink = intent.getStringExtra("VLESS_LINK");
                AppLog.add(TAG, "🔗 لینک VLESS جدید به طول " + mVlessLink.length() + " کاراکتر با موفقیت کش شد.");
            }
        }

        synchronized (mLock) {
            if (mThread != null && mThread.isAlive()) {
                AppLog.add(TAG, "🔄 هشدار: ترد قدیمی هنوز زنده است؛ ریستارت امن ترد آغاز شد...");
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
        AppLog.add(TAG, "♻️ [onDestroy] سرویس به طور کامل توسط سیستم‌عامل متوقف و حافظه پاکسازی شد.");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        File baseDir = getFilesDir();
        AppLog.add(TAG, "📂 دایرکتوری اختصاصی فایل‌های اپلیکیشن: " + baseDir.getAbsolutePath());
        try {
            showStatus("🔍 بارگذاری هسته هوشمند...");
            AppLog.add(TAG, "🎬 آغاز بکار لایه تونل‌زنی فندق‌شکن...");

            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File coreBin = new File(nativeDir, "libxray.so");
            AppLog.add(TAG, "🛠️ چک کردن وجود هسته نیتیو در مسیر: " + coreBin.getAbsolutePath());
            if (!coreBin.exists()) {
                throw new Exception("هسته سیستمی لایه ۲ (libxray.so) یافت نشد!");
            }
            AppLog.add(TAG, "✅ هسته نیتیو با موفقیت رویت شد. حجم فایل: " + coreBin.length() + " بایت");

            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");
            AppLog.add(TAG, "🌐 در حال پیکربندی اینترفیس TUN اندروید...");

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
                throw new Exception("سیستم‌عامل اندروید مجوز VpnService را صادر نکرد (VpnBuilder برگشت نخورد).");
            }

            int fd = mInterface.getFd();
            AppLog.add(TAG, "✅ [TUN Interface] با موفقیت ایجاد شد. FileDescriptor اختصاص یافته: " + fd);

            // 🛠️ ایجاد کانفیگ هوشمند با فرمت استاندارد هسته و لاگ فوق پیشرفته
            generateSingBoxConfig(mVlessLink, baseDir, fd);

            AppLog.add(TAG, "⚡ در حال فراخوانی متد نیتیو startCoreNative...");
            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) {
                throw new Exception("هسته نیتیو با کد خطای " + pid + " متوقف شد و بالا نیامد.");
            }
            
            AppLog.add(TAG, "🚀 هسته نیتیو با موفقیت به سیستم تزریق شد. PID جاری = " + pid);
            showStatus("🚀 فندق‌شکن متصل شد.");
            
            isRunning = true;

            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            AppLog.add(TAG, "ℹ️ ترد لایه ۳ به صورت امن و با سیگنال Interrupt متوقف شد.");
        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ERROR: " + e.getMessage());
            AppLog.add(TAG, "❌ خطا در چرخه ران‌تایم فندق‌شکن: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateSingBoxConfig(String link, File baseDir, int tunFd) throws Exception {
        AppLog.add(TAG, "📝 آغاز فرآیند پارس کردن لینک اتصال...");
        if (link == null || !link.toLowerCase().startsWith("vless://")) {
            throw new Exception("لینک ارسالی خالی یا فاقد پروتکل استاندارد vless:// است.");
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
                throw new Exception("اجزای اجباری لینک (User/Host/Port) نامعتبر یا ناقص هستند.");
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
            throw new Exception("خطای پارسر اندروید روی رشته کامپوننت: " + e.getMessage());
        }

        // 🔥 لاگ فوق‌العاده قدرتمند از ریز پارامترهای دیکود شده لینک
        AppLog.add(TAG, "🗺️ [مشخصات کامل ساختار لینک ورودی]");
        AppLog.add(TAG, "├── 🔑 شناسه کاربر (UUID): " + uuid);
        AppLog.add(TAG, "├── 📡 آدرس سرور (Host): " + host);
        AppLog.add(TAG, "├── 🔌 پورت اتصال (Port): " + port);
        AppLog.add(TAG, "├── 🛡️ رمزنگاری (Security): " + security);
        AppLog.add(TAG, "├── 📦 نوع ترافیک (Type): " + type.toUpperCase());
        AppLog.add(TAG, "├── 🎯 شناسه SNI سرور: " + (sni.isEmpty() ? "تعریف نشده (استفاده از Host)" : sni));
        AppLog.add(TAG, "├── 🕸️ مسیر وب‌سوکت (Path): " + path);
        AppLog.add(TAG, "├── 👥 هاست وب‌سوکت (WS-Host): " + (wsHost.isEmpty() ? "ندارد" : wsHost));
        AppLog.add(TAG, "└── 👣 اثر انگشت (Fingerprint): " + (fp.isEmpty() ? "پیش‌فرض" : fp));

        JSONObject root = new JSONObject();

        // ۱. تنظیمات لاگ داخلی هسته
        JSONObject log = new JSONObject();
        log.put("level", "info");
        log.put("timestamp", true);
        root.put("log", log);

        // ۲. ساختار DNS دوکاناله
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();

        JSONObject remoteDns = new JSONObject();
        remoteDns.put("tag", "remote-dns");
        remoteDns.put("address", "8.8.8.8");
        remoteDns.put("detour", "proxy"); 
        dnsServers.put(remoteDns);

        JSONObject directDns = new JSONObject();
        directDns.put("tag", "direct-dns");
        directDns.put("address", "1.1.1.1"); 
        directDns.put("detour", "direct"); 
        dnsServers.put(directDns);

        dns.put("servers", dnsServers);

        JSONArray dnsRules = new JSONArray();
        JSONObject bypassRule = new JSONObject();
        JSONArray bypassDomains = new JSONArray();
        bypassDomains.put(host); 
        if (!sni.isEmpty()) {
            bypassDomains.put(sni);
        }
        bypassRule.put("domain", bypassDomains);
        bypassRule.put("server", "direct-dns"); 
        dnsRules.put(bypassRule);

        dns.put("rules", dnsRules);
        dns.put("final", "remote-dns");
        dns.put("strategy", "prefer_ipv4");
        root.put("dns", dns);

        // ۳. تنظیمات ورودی اینترفیس لایه ۳ (TUN Inbound)
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("file_descriptor", tunFd); // هماهنگ با فرمت استاندارد نیتیو
        tunIn.put("mtu", 1500);
        tunIn.put("auto_route", false);   
        tunIn.put("strict_route", false);
        tunIn.put("stack", "mixed");       
        tunIn.put("sniff", true);         
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

        JSONObject dnsOut = new JSONObject();
        dnsOut.put("type", "dns");
        dnsOut.put("tag", "dns-out");
        outbounds.put(dnsOut);

        root.put("outbounds", outbounds);

        // ۵. قوانین روتینگ هسته برای پکت‌های ورودی دستگاه
        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();

        JSONObject dnsRule = new JSONObject();
        JSONArray protoArray = new JSONArray();
        protoArray.put("dns");
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
        route.put("auto_detect_interface", false); 
        root.put("route", route);

        // 🔥 بمب لاگ‌گیری: چاپ زنده و کامل کل بدنه ساختار فایل جی‌سان نهایی قبل از رایت فیزیکی!
        AppLog.add(TAG, "⚙️ [محتوای خروجی نهایی ساختار config.json]:\n" + root.toString(2));

        // ذخیره فایل فیزیکی کانفیگ
        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
        AppLog.add(TAG, "💾 فایل تنظیمات هسته با موفقیت در دایرکتوری لوکال بازنویسی و سنک شد.");
    }

    private void stopVpn() {
        synchronized (mLock) {
            isRunning = false;
            try {
                stopCoreNative();
                AppLog.add(TAG, "📉 دستور قطع اتصال هسته صادر شد [stopCoreNative].");
            } catch (Exception e) {
                Log.e(TAG, "خطا در بستن هسته: " + e.getMessage());
            }

            if (mInterface != null) {
                try {
                    mInterface.close();
                    AppLog.add(TAG, "🔒 اینترفیس لایه ۳ (TUN) با موفقیت ریلیز شد. ترافیک به شبکه محلی بازگشت.");
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
                   
