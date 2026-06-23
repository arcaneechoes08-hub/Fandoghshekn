package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
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
    private static final String CHANNEL_ID = "vpn_channel"; // هماهنگ با چنل رسمی تحلیل جدید
    private Thread mThread;
    private ParcelFileDescriptor mInterface = null; // مقداردهی اولیه امن برای جلوگیری از کرش
    private Process mTun2SocksProcess;
    private String v2rayConfig = "";

    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isNetworkChanging = false;

    static {
        System.loadLibrary("native-lib");
    }

    @androidx.annotation.Keep
    private native int startNativeCore(String config);

    @androidx.annotation.Keep
    public boolean protectSocket(int fd) {
        boolean result = protect(fd);
        if (result) {
            Log.d(TAG, "✅ سوکت با موفقیت از تونل مستثنی شد (Protect). FD: " + fd);
        } else {
            Log.e(TAG, "❌ خطا در پروتکت سوکت سیستم‌عامل! FD: " + fd);
        }
        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                v2rayConfig = intent.getStringExtra("COMMAND_CONFIG");
                
                // 🚨 گام اورژانسی ۱: انتقال فوری سرویس به Foreground قبل از تشکیل تانل برای زنده کردن آیکون کلید
                startServiceForeground();
                acquireWakeLock();
                registerNetworkTracker();

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
                    CHANNEL_ID, "Fandogh VPN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
                ? new Notification.Builder(this, CHANNEL_ID) 
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Fandogh Shekan")
                .setContentText("در حال اتصال و برقراری تونل امن هسته...")
                .setSmallIcon(android.R.drawable.ic_lock_lock) // استفاده از آیکون پیش‌فرض سیستم جهت پایداری سورس
                .build();

        startForeground(1, notification);
        Log.i(TAG, "🛡️ سرویس به صورت Foreground معرفی شد. تخصیص کلید سیستم عامل در صف اجراست.");
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fandogh::VpnWakeLock");
            wakeLock.acquire(10*60*1000L);
            Log.i(TAG, "🔋 لایه WakeLock فعال شد.");
        }
    }

    private void registerNetworkTracker() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (isNetworkChanging) {
                    Log.i(TAG, "🔄 شبکه تغییر کرد. ترافیک بازسازی می‌شود.");
                    isNetworkChanging = false;
                }
            }

            @Override
            public void onLost(Network network) {
                isNetworkChanging = true;
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void extractAssetsIfNeeded() {
        String[] assetFiles = {"geoip.dat", "geosite.dat"};
        File cacheDir = getFilesDir();
        for (String fileName : assetFiles) {
            File targetFile = new File(cacheDir, fileName);
            if (!targetFile.exists()) {
                try (InputStream in = getAssets().open(fileName);
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "خطا در استخراج فایل کمکی", e);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            extractAssetsIfNeeded();
            String assetPath = getFilesDir().getAbsolutePath();
            Os.setenv("XRAY_LOCATION_ASSET", assetPath, true);
            Os.setenv("V2RAY_LOCATION_ASSET", assetPath, true);

            if (v2rayConfig != null && !v2rayConfig.isEmpty()) {
                new Thread(() -> {
                    try {
                        startNativeCore(v2rayConfig);
                    } catch (Exception e) {
                        Log.e(TAG, "خطای درایور هسته: " + e.getMessage());
                    }
                }, "FandoghV2RayCore").start();
            }

            // 🚨 گام اورژانسی ۲: استقرار امن اینترفیس سیستمی بعد از معرفی موتیفیکیشن پیش‌زمینه
            VpnService.Builder builder = new VpnService.Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1350)
                    .addAddress("10.0.0.1", 24)
                    .addRoute("0.0.0.0", 0) // اکنون به دلیل تایید هویت سرویس، کرنل اندروید این روت را فعال می‌کند
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) {
                Log.e(TAG, "❌ خطا! سیستم‌عامل اجازه ایجاد دسکریپتور تانل را صادر نکرد.");
                return;
            }

            Log.i(TAG, "✅ تونل سیستمی مستقر شد و ترافیک به سمت لایه لینوکس هدایت می‌شود.");

            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String tun2socksPath = nativeLibDir + "/libtun2socks.so";

            ProcessBuilder pb = new ProcessBuilder(
                    tun2socksPath,
                    "-device", "fd://" + mInterface.getFd(),
                    "-proxy", "socks5://127.0.0.1:10808",
                    "-mtu", "1350"
            );

            pb.redirectErrorStream(true);
            mTun2SocksProcess = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(mTun2SocksProcess.getInputStream()));
            String logLine;
            while ((logLine = reader.readLine()) != null) {
                Log.d(LOG_TAG_TUN, logLine);
            }
            mTun2SocksProcess.waitFor();

        } catch (Exception e) {
            Log.e(TAG, "خطای چرخه شبکه: " + e.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "خطای غیرمنتظره لایه سخت‌افزار", t);
        } finally {
            stopVpn();
        }
    }

    // 🚨 گام اورژانسی ۳: پروتکل خروج ایمن (Teardown) برای ممانعت کامل از خطاهای NullPointer و Bad File Descriptor
    private void stopVpn() {
        Log.i(TAG, "🛑 شروع پاک‌سازی لایه‌های حافظه و بستن امن تانل...");
        
        if (mTun2SocksProcess != null) {
            mTun2SocksProcess.destroy();
            mTun2SocksProcess = null;
        }

        // بررسی شرط حیاتی برای جلوگیری از کرش هنگام قطع اتصال
        if (mInterface != null) {
            try { 
                mInterface.close(); 
                Log.d(TAG, "✅ فایل دسکریپتور تانل با موفقیت بسته شد.");
            } catch (IOException e) {
                Log.e(TAG, "خطای خاموش در بستن دسکریپتور سیستم‌عامل", e);
            }
            mInterface = null; // نال کردن مرجع جهت تخلیه کامل حافظه رم
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
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
