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
import java.io.File;
import java.io.FileOutputStream;

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
        // ایجاد و فعال‌سازی فوری نوتیفیکیشن جهت جلوگیری از کرش سيستم
        startForegroundNotification();

        String action = intent != null ? intent.getAction() : "null";
        // ثبت لاگ اکشن ورودی به سرویس پس‌زمینه
        AppLog.add("FandoghVpnService", "سرویس با اکشن اجرا شد: " + action);

        if (intent != null) {
            if ("STOP".equals(intent.getAction())) {
                AppLog.add("FandoghVpnService", "دستور توقف صریح (STOP) از اکتیویتی دریافت شد.");
                stopVpn();
                return START_NOT_STICKY;
            }
            // هماهنگ‌سازی کلید متغیرها با اکتیوتی اصلی
            mVlessLink = intent.getStringExtra("COMMAND_CONFIG");
        }
        
        if (mThread != null && mThread.isAlive()) {
            AppLog.add("FandoghVpnService", "ترد قدیمی سرویس هنوز زنده است. در حال ری‌استارت تونل...");
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
            .setSmallIcon(android.R.drawable.ic_menu_share) // استفاده از آیکون پیش‌فرض سیستم برای هماهنگی فوری
            .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        AppLog.add("FandoghVpnService", "متد onDestroy سرویس فراخوانی شد. تخریب نهایی منابع ترافیکی.");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        try {
            AppLog.add("FandoghVpnService", "ترد اصلی هسته VPN آغاز به کار کرد.");
            showStatus("🔍 بارگذاری هسته هوشمند...");
            
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File coreBin = new File(nativeDir, "libxray.so");
            AppLog.add("FandoghVpnService", "در حال بررسی وجود باینری در مسیر سیستمی...");
            
            if (!coreBin.exists()) {
                throw new Exception("فایل هسته libxray.so در این مسیر یافت نشد!");
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

            // بخش‌های ساخت فایل json تنظیمات
            generateSingBoxConfig(mVlessLink, baseDir, mInterface.getFd());
            AppLog.add("FandoghVpnService", "فایل تنظیمات هسته تولید شد. دستور اجرای مستقیم باینری صادر می‌شود...");
            showStatus("🚀 فندق‌شکن متصل شد و ترافیک ایمن گردید.");
            
            String[] cmd = {
                coreBin.getAbsolutePath(), 
                "run", 
                "-config", 
                new File(baseDir, "config.json").getAbsolutePath()
            };
            
            // قبل از اجرای باینری هسته هسته
            AppLog.add("FandoghVpnService", "دستور فرستاده شد: " + coreBin.getAbsolutePath());
            mCoreProcess = Runtime.getRuntime().exec(cmd);
            
            AppLog.add("FandoghVpnService", "پروسس باینری با موفقیت لود شد و در حال پردازش پکت‌هاست.");
            
            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            AppLog.add("FandoghVpnService", "❌ وقوع خطای شدید در سرویس: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateSingBoxConfig(String link, File dir, int tunFd) throws Exception {
        String host = "YOUR_SERVER_ADDRESS";
        int port = 443;
        String uuid = "00000000-0000-0000-0000-000000000000";
        String json = "{\n" +
                "  \"log\": {\"level\": \"warn\"},\n" +
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
                "    \"server_port\": " + port + ",\n" +
                "    \"uuid\": \"" + uuid + "\",\n" +
                "    \"flow\": \"xtls-rprx-vision\",\n" +
                "    \"network\": \"tcp\",\n" +
                "    \"tls\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"server_name\": \"google.com\",\n" +
                "      \"utls\": {\"enabled\": true, \"fingerprint\": \"chrome\"}\n" +
                "    }\n" +
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
                AppLog.add("FandoghVpnService", "پروسس مادری باینری کلاً متوقف و تخریب شد.");
            }
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
                AppLog.add("FandoghVpnService", "رابط مجاری تونل لایه ۳ (Tun Fd) با موفقیت بسته شد.");
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
            AppLog.add("FandoghVpnService", "خطا در فرآیند متوقف‌سازی سرویس: " + e.getMessage());
        }
    }
                       }
