package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

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

    private native int startNativeCore(String config);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                v2rayConfig = intent.getStringExtra("COMMAND_CONFIG");

                // 🔔 بیدار کردن فورگراند سرویس برای نمایش قطعی آیکون کلید در استاتوس‌بار
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
        // ساخت کانال نوتیفیکیشن برای اندرویدهای ۸ به بالا
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

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // تنظیمات ظاهری نوتیفیکیشن اتصال
        Notification notification = builder
                .setContentTitle("فندق‌شکن 🌰")
                .setContentText("اتصال امن برقرار است و ترافیک هدایت می‌شود.")
                .setSmallIcon(android.R.drawable.sym_def_app_icon) // استفاده از آیکون سیستم جهت امنیت در کامپایل
                .build();

        // اعلام رسمی به اندروید: من یک VPN فعال و زنده هستم!
        startForeground(1, notification);
    }

    @Override
    public void run() {
        try {
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
                    .addDisallowedApplication(getPackageName()) // شکستن حلقه لوپ روتینگ
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
            ProcessBuilder pb = new ProcessBuilder(
                    tun2socksPath,
                    "-device", "fd://" + vpnFd,
                    "-proxy", "socks5://127.0.0.1:10808"
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
        } catch (Throwable t) {
            Log.e(TAG, "❌ [Throwable] خطای پیش‌بینی نشده: " + t.getMessage());
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

        // پاک کردن نوتیفیکیشن و خروج از حالت فورگراند
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
