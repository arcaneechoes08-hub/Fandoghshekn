package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mCoreProcess;
    private String mVlessLink;

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        String action = intent != null ? intent.getAction() : "null";
        AppLog.add("FandoghVpnService", "سرویس با اکشن اجرا شد: " + action);

        if (intent != null) {
            if ("STOP".equals(intent.getAction())) {
                AppLog.add("FandoghVpnService", "دستور توقف صریح (STOP) دریافت شد.");
                stopVpn();
                return START_NOT_STICKY;
            }
            mVlessLink = intent.getStringExtra("COMMAND_CONFIG");
        }
        
        if (mThread != null && mThread.isAlive()) {
            AppLog.add("FandoghVpnService", "در حال ری‌استارت تونل هسته...");
            stopVpn();
        }
        
        mThread = new Thread(this, "FandoghVpnThread");
        mThread.start();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "fandogh_vpn_channel";
        Notification.Builder builder;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId, "Fandogh VPN", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("فندق‌شکن")
            .setContentText("اتصال هوشمند برقرار است")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        AppLog.add("FandoghVpnService", "سرویس کلاً تخریب و بسته شد.");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        try {
            AppLog.add("FandoghVpnService", "ترد اصلی هسته Sing-box آغاز به کار کرد.");
            showStatus("🔍 بارگذاری هسته هوشمند...");
            
            // بررسی مسیر فایل باینری جدید سینگ باکس
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File coreBin = new File(nativeDir, "libsingbox.so");
            AppLog.add("FandoghVpnService", "در حال بررسی وجود باینری در مسیر سیستمی: " + coreBin.getAbsolutePath());
            
            if (!coreBin.exists()) {
                throw new Exception("فایل هسته libsingbox.so یافت نشد! مطمئن شوید در گیت‌هاب اکشنز فایل درست دانلود می‌شود.");
            }

            File baseDir = getFilesDir();
            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");
            AppLog.add("FandoghVpnService", "باینری تایید شد. در حال مقداردهی کانفیگ پکت‌های لایه ۳ (Tun)...");
            
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("172.19.0.1", 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) {
                throw new Exception("سیستم عامل مجوز ساخت VpnInterface را صادر نکرد.");
            }
            AppLog.add("FandoghVpnService", "تونل لایه ۳ با موفقیت باز شد. شماره Fd: " + mInterface.getFd());

            generateSingBoxConfig(mVlessLink, baseDir, mInterface.getFd());
            AppLog.add("FandoghVpnService", "فایل تنظیمات هسته تولید شد. دستور اجرای مستقیم باینری صادر می‌شود...");
            showStatus("🚀 فندق‌شکن متصل شد و ترافیک ایمن گردید.");
            
            // اصلاح فلگ اجرای سینگ باکس به فرمت استاندارد c-
            String[] cmd = {
                coreBin.getAbsolutePath(), 
                "run", 
                "-c", 
                new File(baseDir, "config.json").getAbsolutePath()
            };
            
            AppLog.add("FandoghVpnService", "دستور فرستاده شد: " + coreBin.getAbsolutePath());
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            mCoreProcess = pb.start();
            
            AppLog.add("FandoghVpnService", "پروسس باینری لود شد. سیستم پایش هوشمند ترافیک فعال گردید.");
            
            // سیستم مانیتورینگ زنده و پایش لاگ‌ها
            BufferedReader reader = new BufferedReader(new InputStreamReader(mCoreProcess.getInputStream(), "UTF-8"));
            String line;
            while (mThread != null && !mThread.isInterrupted() && (line = reader.readLine()) != null) {
                String smartLog = analyzeLogSmartly(line);
                AppLog.add("FandoghVpnService", smartLog);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            AppLog.add("FandoghVpnService", "❌ وقوع خطای شدید در سرویس: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    public String analyzeLogSmartly(String rawLog) {
        String lowerLog = rawLog.toLowerCase();
        
        if (lowerLog.contains("context deadline exceeded") || lowerLog.contains("connection refused") || lowerLog.contains("dial tcp")) {
            return "❌ [خطای شبکه]: ارتباط با سرور برقرار نشد! احتمالاً IP سرور فیلتر شده یا اینترنت شما بشدت ضعیف است.";
        }
        if (lowerLog.contains("bad handshake") || lowerLog.contains("handshake failed") || lowerLog.contains("authentication failed")) {
            return "🔑 [خطای اعتبار سنجی]: مشخصات کانفیگ یا کلید Reality اشتباه است. سرور دسترسی را رد کرد.";
        }
        if (lowerLog.contains("tun interface") && (lowerLog.contains("broken pipe") || lowerLog.contains("failed to open"))) {
            return "🔌 [خطای سیستم‌عامل]: کارت شبکه مجازی (TUN) اندروید مسدود یا قطع شد.";
        }
        if (lowerLog.contains("proxy connection opened") || lowerLog.contains("tcp:connect") || lowerLog.contains("inbound/tun")) {
            return "✅ [ترافیک زنده]: دیتای درخواستی با موفقیت رمزگشایی و از تونل عبور کرد.";
        }
        if (lowerLog.contains("rejected") || lowerLog.contains("blocked")) {
            return "🚫 [مسدود شده]: ترافیک این وب‌سایت طبق قوانین کانفیگ مسدود شده است.";
        }
        return rawLog;
    }

    private void generateSingBoxConfig(String link, File dir, int tunFd) throws Exception {
        String host = "127.0.0.1";
        int port = 443;
        String uuid = "00000000-0000-0000-0000-000000000000";
        String networkType = "tcp";
        String path = "/";
        String sni = "google.com";
        String hostHeader = "";
        boolean isTls = true;

        try {
            if (link != null && link.startsWith("vless://")) {
                String clean = link.substring(8);
                if (clean.contains("#")) {
                    clean = clean.split("#")[0];
                }
                String[] parts = clean.split("@");
                if (parts.length == 2) {
                    uuid = parts[0];
                    String remainder = parts[1];
                    String[] mainAndQuery = remainder.split("\\?");
                    String main = mainAndQuery[0];
                    
                    String[] hostPort = main.split(":");
                    host = hostPort[0];
                    if (hostPort.length == 2) {
                        port = Integer.parseInt(hostPort[1].trim());
                    }
                    
                    if (mainAndQuery.length == 2) {
                        String query = mainAndQuery[1];
                        String[] params = query.split("&");
                        for (String param : params) {
                            String[] kv = param.split("=");
                            if (kv.length == 2) {
                                String k = kv[0].trim();
                                String v = android.net.Uri.decode(kv[1].trim());
                                if ("type".equals(k)) networkType = v;
                                if ("path".equals(k)) path = v;
                                if ("sni".equals(k)) sni = v;
                                if ("host".equals(k)) hostHeader = v;
                                if ("security".equals(k) && "none".equals(v)) isTls = false;
                            }
                        }
                    }
                }
            }
            AppLog.add("FandoghVpnService", "لینک VLESS با موفقیت پارس شد. میزبان: " + host);
        } catch (Exception e) {
            AppLog.add("FandoghVpnService", "⚠️ خطا در پارس خودکار لینک: " + e.getMessage());
        }

        String transportBlock = "";
        if ("ws".equals(networkType)) {
            transportBlock = ",\n    \"transport\": {\n" +
                    "      \"type\": \"ws\",\n" +
                    "      \"path\": \"" + path + "\",\n" +
                    "      \"headers\": {\n" +
                    "        \"Host\": \"" + (hostHeader.isEmpty() ? host : hostHeader) + "\"\n" +
                    "      }\n" +
                    "    }";
        }

        String tlsBlock = "{\n" +
                "      \"enabled\": " + isTls + ",\n" +
                "      \"server_name\": \"" + (hostHeader.isEmpty() ? sni : hostHeader) + "\",\n" +
                "      \"utls\": {\"enabled\": true, \"fingerprint\": \"chrome\"}\n" +
                "    }";

        String json = "{\n" +
                "  \"log\": {\"level\": \"info\"},\n" +
                "  \"inbounds\": [{\n" +
                "    \"type\": \"tun\",\n" +
                "    \"tag\": \"tun-in\",\n" +
                "    \"interface_name\": \"tun0\",\n" +
                "    \"fd\": " + tunFd + ",\n" +
                "    \"accept_proxy_protocol\": false\n" +
                "  }],\n" +
                "  \"outbounds\": [{\n" +
                "    \"type\": \"vless\",\n" +
                "    \"tag\": \"proxy\",\n" +
                "    \"server\": \"" + host + "\",\n" +
                "    \"server_port\": " + port + "\n," +
                "    \"uuid\": \"" + uuid + "\",\n" +
                "    \"network\": \"tcp\",\n" +
                "    \"tls\": " + tlsBlock +
                transportBlock + "\n" +
                "  }]\n" +
                "}";

        File configFile = new File(dir, "config.json");
        FileOutputStream fos = new FileOutputStream(configFile);
        fos.write(json.getBytes());
        fos.flush(); fos.close();
    }

    private void stopVpn() {
        AppLog.add("FandoghVpnService", "درخواست توقف سرویس و تخریب پروسس باینری هسته دریافت شد.");
        try {
            if (mCoreProcess != null) {
                mCoreProcess.destroy();
                mCoreProcess = null;
                AppLog.add("FandoghVpnService", "پروسس باینری کلاً متوقف شد.");
            }
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
                AppLog.add("FandoghVpnService", "رابط مجازی تونل لایه ۳ با موفقیت بسته شد.");
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }
    }
