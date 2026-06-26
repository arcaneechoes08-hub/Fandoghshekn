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

    // 🟢 متغیر استاتیک برای هماهنگی با MainActivity (حل مشکل ریست شدن دکمه)
    public static volatile boolean isRunning = false;
    
    // قفل همگام‌سازی برای جلوگیری از تداخل تردها
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
                AppLog.add(TAG, "🔄 ترد قدیمی هنوز زنده است؛ ریستارت امن ترد...");
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
        AppLog.add(TAG, "♻️ سرویس به طور کامل متوقف و حافظه پاکسازی شد.");
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
                        .addDisallowedApplication(getPackageName()) // دور زدن خود اپلیکیشن برای جلوگیری از لوپ
                        .establish();
            }

            if (mInterface == null) {
                throw new Exception("سیستم‌عامل اندروید مجوز VpnService را صادر نکرد.");
            }

            int fd = mInterface.getFd();
            AppLog.add(TAG, "✅ [TUN Interface] با موفقیت ایجاد شد. FileDescriptor: " + fd);

            // 🛠️ ایجاد کانفیگ هوشمند ضد بن‌بست DNS
            generateSingBoxConfig(mVlessLink, baseDir, fd);

            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) {
                throw new Exception("هسته نیتیو با کد خطای " + pid + " متوقف شد.");
            }
            
            AppLog.add(TAG, "🚀 هسته نیتیو با موفقیت تزریق شد. PID = " + pid);
            showStatus("🚀 فندق‌شکن متصل شد.");
            
            // 🟢 سرویس با موفقیت فعال شد
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
     * 🚦 متد اصلاح‌شده تولید کانفیگ با معماری تفکیک DNS برای عبور قطعی ترافیک
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

        // 📝 پرینت ساختار مهندسی شده شبکه در کنسول برنامه شما
        AppLog.add(TAG, "🗺️ [نقشه مسیریابی ترافیک فندق‌شکن]");
        AppLog.add(TAG, "🔗 مشخصات سرور خروجی: " + host + ":" + port + " via " + type.toUpperCase());
        AppLog.add(TAG, "🔓 [شکستن لوپ DNS]: دامنه " + host + " مستقیم (Direct) حل می‌شود تا اینترنت فریز نشود.");

        JSONObject root = new JSONObject();

        // ۱. تنظیمات لاگ داخلی هسته
        JSONObject log = new JSONObject();
        log.put("level", "info");
        log.put("timestamp", true);
        root.put("log", log);

        // ۲. ساختار DNS دوکاناله (حل‌کننده بن‌بست اینترنت)
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();

        // کانال اول: مخصوص سایت‌های فیلتر شده (عبور از داخل پروکسی)
        JSONObject remoteDns = new JSONObject();
        remoteDns.put("tag", "remote-dns");
        remoteDns.put("address", "8.8.8.8");
        remoteDns.put("detour", "proxy"); 
        dnsServers.put(remoteDns);

        // کانال دوم: مخصوص آدرس خود سرور (عبور مستقیم از اینترنت واقعی گوشی برای باز شدن تونل)
        JSONObject directDns = new JSONObject();
        directDns.put("tag", "direct-dns");
        directDns.put("address", "1.1.1.1"); 
        directDns.put("detour", "direct"); 
        dnsServers.put(directDns);

        dns.put("servers", dnsServers);

        // 🌟 قانون کلیدی: آدرس دامنه سرور نباید وارد پروکسی شود، وگرنه اینترنت کور می‌شود!
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
        tunIn.put("fd", tunFd); 
        tunIn.put("mtu", 1500);
        tunIn.put("auto_route", false);   // مدیریت مسیر بر عهده اندروید است
        tunIn.put("strict_route", false);
        tunIn.put("stack", "mixed");       // استفاده از پشته ترکیبی برای پایداری پکت‌ها روی تمامی پردازنده‌ها
        tunIn.put("sniff", true);         
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // ۴. خروجی‌ها (Outbounds)
        JSONArray outbounds = new JSONArray();

        // خروجی تونل اصلی (VLESS)
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

        // خروجی عبور مستقیم (Direct)
        JSONObject directOut = new JSONObject();
        directOut.put("type", "direct");
        directOut.put("tag", "direct");
        outbounds.put(directOut);

        // خروجی پکت‌های سیستم مدیریت DNS هسته
        JSONObject dnsOut = new JSONObject();
        dnsOut.put("type", "dns");
        dnsOut.put("tag", "dns-out");
        outbounds.put(dnsOut);

        root.put("outbounds", outbounds);

        // ۵. قوانین روتینگ هسته برای پکت‌های ورودی دستگاه
        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();

        // هدایت پکت‌های استاندارد لایه شبکه DNS به موتور پردازش داخلی
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("protocol", "dns");
        dnsRule.put("outbound", "dns-out");
        rules.put(dnsRule);

        // هد
    
