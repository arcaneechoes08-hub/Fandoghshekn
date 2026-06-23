package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class FandoghVpnService extends VpnService implements Runnable {

    private static final String TAG = "FandoghCore";
    private static final String LOG_TAG_TUN = "FandoghTun2Socks";
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

    @Override
    public void run() {
        try {
            // 🚀 ۱. انتقال استارت هسته به ترد مستقل پس‌زمینه جهت جلوگیری از فریز شدن UI
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

            // 🎯 ۲. حل مشکل بن‌بست ترافیک با دور زدن نام دیوایس و ارسال مستقیم File Descriptor
            int vpnFd = mInterface.getFd();

            // توجه: اکثر نسخه‌های اندرویدی tun2socks فرمت fd:// را می‌پذیرند.
            // اگر بعداً در لاگ ارور Invalid argument دیدی، می‌توانی این بخش را به "-tunFd", String.valueOf(vpnFd) تغییر دهی.
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

        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
