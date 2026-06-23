package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.net.Socket;

public class FandoghVpnService extends VpnService {

    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final String CHANNEL_NAME = "VPN Service";

    private ParcelFileDescriptor vpnInterface = null;
    private volatile boolean isRunning = false;
    private Thread xrayThread = null;

    // ✅ نام لایبرری با خروجی CMakeLists.txt هماهنگ شد
    static {
        System.loadLibrary("native-lib");
    }

    public static native void startXray(String configJson, int tunFd);
    public static native void stopXray();

    public boolean protectSocket(int socket) {
        boolean result = protect(socket);
        Log.d(TAG, "protectSocket(" + socket + ") = " + result);
        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            new Thread(this::stopVpn).start();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        try {
            createNotificationChannel();
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "Foreground service started.");

            VpnService.Builder builder = new VpnService.Builder();
            builder.setMtu(1350);
            builder.addAddress("10.0.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.setSession("FandoghShekn");
            builder.setBlocking(false);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.");
                new Thread(this::stopVpn).start();
                return;
            }

            final int tunFd = vpnInterface.getFd();
            Log.d(TAG, "VPN interface established with FD: " + tunFd);

            try {
                Socket testSocket = new Socket();
                protect(testSocket);
                testSocket.close();
                Log.d(TAG, "Test socket protected successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to protect test socket", e);
            }

            final String configJson = ConfigManager.getDecryptedConfig(getApplicationContext());
            if (configJson == null || configJson.isEmpty()) {
                Log.e(TAG, "Config is null or empty.");
                new Thread(this::stopVpn).start();
                return;
            }

            isRunning = true;
            xrayThread = new Thread(() -> {
                try {
                    Log.d(TAG, "Starting Xray core via JNI...");
                    startXray(configJson, tunFd);
                    Log.d(TAG, "Xray core execution finished.");
                } catch (Exception e) {
                    Log.e(TAG, "Native Xray crash: " + e.getMessage(), e);
                } finally {
                    isRunning = false;
                }
            }, "XrayCoreThread");
            xrayThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN: " + e.getMessage(), e);
            new Thread(this::stopVpn).start();
        }
    }

    private void stopVpn() {
        if (isRunning) {
            try {
                stopXray();
                Thread.sleep(300);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping Xray: " + e.getMessage(), e);
            }
            isRunning = false;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing tunFd: " + e.getMessage());
            } finally {
                vpnInterface = null;
            }
        }

        stopForeground(true);

        if (xrayThread != null && xrayThread.isAlive()) {
            try { xrayThread.join(1000); } catch (InterruptedException ignored) {}
            xrayThread = null;
        }

        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, FandoghVpnService.class).setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fandogh Shekan")
                .setContentText("VPN فعال است.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
