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
    private static final String CHANNEL_ID = "FandoghVpnChannel";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
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

    // 🔑 حل مشکل شماره ۱ (حیاتی): کالبک JNI جهت اجرای پروتکت روی سوکت خروجی هسته و ریشه‌کنی لوپ ترافیک
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
                .setContentTitle("فندق‌شکن 🌰")
                .setContentText("اتصال امن لایه هسته برقرار است.")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();

        startForeground(1, notification);
    }

    // 🔋 حل مشکل شماره ۶: گرفتن قفل پردازنده جهت ممانعت از فریز شدن در خواب عمیق گوشی (Doze Mode)
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fandogh::VpnWakeLock");
            wakeLock.acquire(10*60*1000L); // تمدید امن ده دقیقه‌ای
            Log.i(TAG, "🔋 لایه WakeLock فعال شد.");
        }
    }

    // 📡 حل مشکل شماره ۵: ردیابی و مدیریت هوشمند ترافیک موقع سوییچ بین Wi-Fi و اینترنت همراه
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

            VpnService.Builder builder = new VpnService.Builder();
            // 📐 حل مشکل شماره ۳ و ۴: کاهش MTU به ۱۳۵۰ برای پایداری پکت‌ها + ست کردن DNS کلودفلر
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1350)
                    .addAddress("10.0.0.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) return;

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
        } finally {
            stopVpn();
        }
    }

    private void stopVpn() {
        if (mTun2SocksProcess != null) {
            mTun2SocksProcess.destroy();
            mTun2SocksProcess = null;
        }
        if (mInterface != null) {
            try { mInterface.close(); } catch (IOException e) {}
            mInterface = null;
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
