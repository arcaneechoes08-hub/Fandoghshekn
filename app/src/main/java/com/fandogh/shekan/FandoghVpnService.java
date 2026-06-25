package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "FandoghVpnChannel";

    // 💥 وصل کردن کدهای C به جاوا
    static {
        System.loadLibrary("native-lib");
    }
    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private String mVlessLink;

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mVlessLink = intent.getStringExtra("COMMAND_CONFIG"); 
        }

        if (mThread != null && mThread.isAlive()) stopVpn();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

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
            showStatus("🔍 بارگذاری هسته هوشمند...");
            
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            // مطمئن شو اسم فایل باینریت همینه (libxray.so یا libsingbox.so)
            File coreBin = new File(nativeDir, "libxray.so"); 
            if (!coreBin.exists()) throw new Exception("هسته سیستمی یافت نشد!");
            coreBin.setExecutable(true);

            File baseDir = getFilesDir();
            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");

            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("172.19.0.1", 24)
                    .addAddress("fd00:1:2:3::1", 126)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) throw new Exception("مجوز ایجاد تونل صادر نشد!");

            int fd = mInterface.getFd();
            generateSingBoxConfig(mVlessLink, baseDir, fd);
            
            // 💥 اجرای ایمن از طریق C برای حفظ آیکون و تونل
            int pid = startCoreNative(coreBin.getAbsolutePath(), new File(baseDir, "config.json").getAbsolutePath(), fd);
            if (pid < 0) throw new Exception("اجرای هسته با خطا مواجه شد!");
            
            showStatus("🚀 فندق‌شکن متصل شد.");

            // بیدار نگه داشتن ترد تا زمان قطع دستی
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }
            
        } catch (InterruptedException e) {
            // سرویس دستی قطع شد
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    // متد generateSingBoxConfig که دقیقاً همون کد مرحله قبله و درسته (برای کوتاه شدن پیام اینجا نذاشتمش، همون قبلی رو کپی کن داخل این فایل)
    
    // ... ادامه کدهای ساخت کانفیگ و نوتیفیکیشن ...

    private void stopVpn() {
        try {
            stopCoreNative(); // کشتن ایمن هسته از طریق لینوکس
            
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
            stopForeground(true);
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Fandogh VPN Service", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال ایمن برقرار است 🛡️")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(mainPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    // یادت نره اون متد generateSingBoxConfig رو از کد قبلی کپی کنی اینجا!
                }
