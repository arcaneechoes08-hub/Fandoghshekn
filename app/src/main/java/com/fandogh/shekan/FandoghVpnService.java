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

public class FandoghVpnService extends VpnService {

    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final String CHANNEL_NAME = "VPN Service";

    private ParcelFileDescriptor vpnInterface = null;
    private boolean isRunning = false;
    private Thread xrayThread = null;

    static {
        System.loadLibrary("xray");
    }

    public static native void startXray(String configJson, int tunFd);
    public static native void stopXray();

    public boolean protectSocket(int socket) {
        return protect(socket);
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

            // ✅ اصلاح خط ۵۴: حذف آرگومان extra از بیلدر اصلی سیستم‌عامل
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

            // ✅ ارجاع کانتکست صحیح به منیجر کانفیگ‌ها
            final String configJson = ConfigManager.getDecryptedConfig(getApplicationContext());
            if (configJson == null || configJson.isEmpty()) {
                Log.e(TAG, "Config is null or empty.");
                new Thread(this::stopVpn).start();
                return;
            }

            isRunning = true;
            xrayThread = new Thread(() -> {
                try {
                    startXray(configJson, tunFd);
                    Log.d(TAG, "Xray core execution finished.");
                } catch (Exception e) {
                    Log.e(TAG, "Native Xray crash: " + e.getMessage());
                }
            }, "XrayCoreThread");
            xrayThread.start();

            Log.d(TAG, "Xray core thread spawned successfully.");

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN: " + e.getMessage(), e);
            new Thread(this::stopVpn).start();
        }
    }

    private void stopVpn() {
        Log.d(TAG, "Stopping VPN in background thread...");

        if (isRunning) {
            try {
                stopXray();
                Thread.sleep(150);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping Xray: " + e.getMessage(), e);
            }
            isRunning = false;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                Log.d(TAG, "VPN interface closed.");
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface: " + e.getMessage(), e);
            } finally {
                vpnInterface = null;
            }
        }

        stopForeground(true);
        
        if (xrayThread != null) {
            xrayThread.interrupt();
            xrayThread = null;
        }

        stopSelf();
        Log.d(TAG, "Service completely stopped.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, FandoghVpnService.class).setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fandogh Shekan")
                .setContentText("VPN فعال است و از اتصال شما محافظت می‌کند.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called.");
        stopVpn();
        super.onDestroy();
    }
}
