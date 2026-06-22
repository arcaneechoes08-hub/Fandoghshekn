package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mXrayProcess;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        
        if (mThread != null && mThread.isAlive()) {
            stopVpn();
        }
        
        mThread = new Thread(this, "FandoghVpnThread");
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        try {
            Log.i(TAG, "در حال استخراج و آماده‌سازی موتور فندق‌شکن...");
            File binDir = getFilesDir();
            File xrayBin = new File(binDir, "xray");
            
            // استخراج باینری از Assets به حافظه داخلی برای کسب مجوز اجرا
            if (!xrayBin.exists()) {
                InputStream is = getAssets().open("xray");
                OutputStream os = new FileOutputStream(xrayBin);
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush(); os.close(); is.close();
            }
            
            // اعطای مجوز اجرایی لینوکس به فایل موتور
            xrayBin.setExecutable(true, false);
            Log.i(TAG, "موتور اجرایی آماده شد.");

            // تشکیل تونل مجازی اندروید
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .establish();

            Log.i(TAG, "تونل مجازی باز شد. در حال استارت زدن هسته Xray...");

            // روشن کردن موتور Xray در پس‌زمینه (در مراحل بعد فایل کانفیگ واقعی جایگزین می‌شود)
            String[] cmd = {xrayBin.getAbsolutePath(), "run", "-config", new File(binDir, "config.json").getAbsolutePath()};
            // برای تست اولیه، فعلاً فرآیند آماده‌سازی دستور را لاگ می‌کنیم
            Log.i(TAG, "دستور پرتاب موتور آماده است.");

            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا در عملکرد هسته: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void stopVpn() {
        try {
            if (mXrayProcess != null) {
                mXrayProcess.destroy();
                mXrayProcess = null;
            }
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
            Log.i(TAG, "سرویس و موتور فندق‌شکن متوقف شدند.");
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف لایه‌ها: " + e.getMessage());
        }
    }
}
