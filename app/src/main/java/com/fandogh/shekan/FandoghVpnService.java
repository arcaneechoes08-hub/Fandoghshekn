package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class FandoghVpnService extends VpnService implements Runnable {

    private static final String TAG = "FandoghCore";
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
                
                // استارت زدن تانل شبکه در یک ترد جداگانه برای جلوگیری از فریز شدن برنامه
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
            Log.i(TAG, "🔧 در حال پیکربندی کارت شبکه مجازی فندق...");
            
            // ۱. ساخت و کانفیگ اینترفیس TUN اندروید
            VpnService.Builder builder = new VpnService.Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1500)
                    .addAddress("10.0.0.1", 24) // آی‌پی داخلی تونل
                    .addRoute("0.0.0.0", 0)     // هدایت کل ترافیک IPv4 گوشی به فندق‌شکن
                    .addDnsServer("1.1.1.1")    // دی‌ان‌اس ابری کلودفلر برای سرعت بیشتر
                    .establish();

            Log.i(TAG, "✅ کارت شبکه tun0 با موفقیت ایجاد شد.");

            // ۲. پیدا کردن مسیر فایل باینری نیتیو tun2socks
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String tun2socksPath = nativeLibDir + "/libtun2socks.so";

            if (!new File(tun2socksPath).exists()) {
                Log.e(TAG, "❌ خطا: فایل libtun2socks.so پیدا نشد!");
                return;
            }

            // ۳. اجرای موتور تانلینگ با پروسس بیلدر (اتصال ترافیک تونل به پروکسی محلی وی‌توری روی پورت 10808)
            // فرض می‌کنیم در آینده هسته v2ray ما روی پورت جوراب (Socks5) 10808 بالا می‌آید
            ProcessBuilder pb = new ProcessBuilder(
                    tun2socksPath,
                    "-device", "tun0",
                    "-proxy", "socks5://127.0.0.1:10808"
            );
            
            pb.redirectErrorStream(true);
            mTun2SocksProcess = pb.start();
            Log.i(TAG, "🚀 موتور باینری Tun2Socks با موفقیت در پس‌زمینه بیدار شد.");

            // زنده نگه داشتن ترد تا زمانی که پروسس در حال اجراست
            mTun2SocksProcess.waitFor();

        } catch (Exception e) {
            Log.e(TAG, "❌ خطا در چرخه حیات تانلینگ: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void stopVpn() {
        Log.i(TAG, "🛑 در حال متوقف‌سازی فندق‌شکن و آزاد کردن شبکه...");
        
        // متوقف کردن پروسس باینری
        if (mTun2SocksProcess != null) {
            mTun2SocksProcess.destroy();
            mTun2SocksProcess = null;
        }
        
        // بستن کارت شبکه مجازی برای برگشتن اینترنت گوشی به حالت عادی
        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "خطا در بستن اینترفیس تونل", e);
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
