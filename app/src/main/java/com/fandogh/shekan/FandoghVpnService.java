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

    static {
        System.loadLibrary("native-lib");
    }

    private native int startNativeCore(String config);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                String config = intent.getStringExtra("COMMAND_CONFIG");
                startNativeCore(config);
                
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
            Log.i(TAG, "🔧 [System] در حال پیکربندی کارت شبکه مجازی فندق...");
            
            VpnService.Builder builder = new VpnService.Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1500)
                    .addAddress("10.0.0.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .establish();

            Log.i(TAG, "✅ [System] کارت شبکه tun0 با موفقیت ایجاد و فعال شد.");

            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String tun2socksPath = nativeLibDir + "/libtun2socks.so";

            if (!new File(tun2socksPath).exists()) {
                Log.e(TAG, "❌ [Fatal] خطا: فایل هسته لایه شبکه پیدا نشد!");
                return;
            }

            // آماده‌سازی دستور با تفکیک دقیق پارامترها
            ProcessBuilder pb = new ProcessBuilder(
                    tun2socksPath,
                    "-device", "tun0",
                    "-proxy", "socks5://127.0.0.1:10808"
            );
            
            // ادغام کانال ارور و خروجی عادی برای مدیریت یکپارچه لاگ‌ها
            pb.redirectErrorStream(true);
            mTun2SocksProcess = pb.start();
            Log.i(TAG, "🚀 [Engine] موتور باینری Tun2Socks در پس‌زمینه بیدار شد.");

            // 🎯 مرتب‌سازی و خواندن زنده لاگ‌های هسته نیتیو
            BufferedReader reader = new BufferedReader(new InputStreamReader(mTun2SocksProcess.getInputStream()));
            String logLine;
            while ((logLine = reader.readLine()) != null) {
                // ارسال مستقیم تمام رویدادهای شبکه به سیستم لاگ‌کت اندروید با تگ اختصاصی
                Log.d(LOG_TAG_TUN, "📋 " + logLine);
            }

            // انتظار برای پایان پروسس در صورت توقف خودخواسته
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
