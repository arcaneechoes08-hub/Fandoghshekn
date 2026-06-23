package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class FandoghVpnService extends VpnService implements Runnable {

    private static final String TAG = "FandoghCore";
    private static final String LOG_TAG_TUN = "FandoghTun2Socks";
    private static final String CHANNEL_ID = "FandoghVpnChannel";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mTun2SocksProcess;
    private String v2rayConfig = "";

    static {
        System.loadLibrary("native-lib");
    }

        @androidx.annotation.Keep
    private native int startNativeCore(String config);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                v2rayConfig = intent.getStringExtra("COMMAND_CONFIG");

                startServiceForeground();

                if (mThread == null || !mThread.isAlive()) {
                    mThread = new Thread(this, "FandoghVPNThread");
                    mThread.start();
                }
            } else if ("com.fandogh.shekan.STOP".equals(action)) {
                stopVpn();
            }
        }
        return START_STICKY;
    }

    private void startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fandogh VPN Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
                ? new Notification.Builder(this, CHANNEL_ID) 
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("فندق‌شکن 🌰")
                .setContentText("اتصال امن برقرار است و ترافیک هدایت می‌شود.")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();

        startForeground(1, notification);
    }

    // 📂 متد استخراج منابع مسیریابی از Assets به محیط محلی قابل خواندن توسط کامپایلر لینوکس
    private void extractAssetsIfNeeded() {
        String[] assetFiles = {"geoip.dat", "geosite.dat"};
        File cacheDir = getFilesDir();
        for (String fileName : assetFiles) {
            File targetFile = new File(cacheDir, fileName);
            if (!targetFile.exists()) {
                try (InputStream in = getAssets().open(fileName);
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[1024 * 4];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    Log.i(TAG, "📦 فایل با موفقیت استخراج شد: " + fileName);
                } catch (IOException e) {
                    Log.e(TAG, "خطا در استخراج فایل اسست: " + fileName, e);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            // ۱. حل ارور ۱ و ۲: استخراج کامپوننت‌های روتینگ و تزریق کانکشن به متغیرهای محیطی سیستم‌عامل
            extractAssetsIfNeeded();
            String assetPath = getFilesDir().getAbsolutePath();
            try {
                Os.setenv("XRAY_LOCATION_ASSET", assetPath, true);
                Os.setenv("V2RAY_LOCATION_ASSET", assetPath, true);
            } catch (Exception e) {
                Log.e(TAG, "موفق به ست کردن محیط آست‌ها نشدیم", e);
            }

            Log.i(TAG, "🚀 [Engine] در حال استارت هسته نیتیو V2Ray در ترد پس‌زمینه...");
            if (v2rayConfig != null && !v2rayConfig.isEmpty()) {
                new Thread(() -> {
                    try {
                        startNativeCore(v2rayConfig);
                    } catch (Exception e) {
                        Log.e(TAG, "خطا در اجرای داخلی هسته نیتیو: " + e.getMessage());
                    }
                }, "FandoghV2RayCore").start();
            }

            Log.i(TAG, "🔧 [System] در حال پیکربندی کارت شبکه مجازی فندق...");

            VpnService.Builder builder = new VpnService.Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1500)
                    .addAddress("10.0.0.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) {
                Log.e(TAG, "❌ [Fatal] موفق به ایجاد اینترفیس VpnService نشدیم.");
                return;
            }

            Log.i(TAG, "✅ [System] کارت شبکه با موفقیت ایجاد شد. FD: " + mInterface.getFd());

            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String tun2socksPath = nativeLibDir + "/libtun2socks.so";

            if (!new File(tun2socksPath).exists()) {
                Log.e(TAG, "❌ [Fatal] خطا: فایل هسته لایه شبکه پیدا نشد!");
                return;
            }

            int vpnFd = mInterface.getFd();
            
            // 🎯 حل ارور ۳: پاس دادن مستقیم حجم بسته (-mtu 1500) برای جلوگیری از تداخل امنیتی
            ProcessBuilder pb = new ProcessBuilder(
                    tun2socksPath,
                    "-device", "fd://" + vpnFd,
                    "-proxy", "socks5://127.0.0.1:10808",
                    "-mtu", "1500"
            );

            pb.redirectErrorStream(true);
            mTun2SocksProcess = pb.start();
            Log.i(TAG, "🚀 [Engine] موتور باینری Tun2Socks در پس‌زمینه بیدار شد.");

            BufferedReader reader = new BufferedReader(new InputStreamReader(mTun2SocksProcess.getInputStream()));
            String logLine;
            while ((logLine = reader.readLine()) != null) {
                Log.d(LOG_TAG_TUN, "📋 " + logLine);
            }

            mTun2SocksProcess.waitFor();

        } catch (Exception e) {
            Log.e(TAG, "❌ [Exception] خطا در چرخه حیات تانلینگ: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void stopVpn() {
        Log.i(TAG, "🛑 [System] در حال متوقف‌سازی فندق‌شکن و پاکسازی ترافیک...");

        if (mTun2SocksProcess != null) {
            mTun2SocksProcess.destroy();
            mTun2SocksProcess = null;
        }

        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "خطا در بستن اینترفیس", e);
            }
            mInterface = null;
        }

        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
