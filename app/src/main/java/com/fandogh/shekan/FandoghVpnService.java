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
    private volatile boolean isRunning = false;
    private Thread coreThread = null;

    static {
        // لود کردن کتابخانه نیتیو پروژه
        System.loadLibrary("native-lib");
    }

    // حفظ ساختار متد JNI قبلی جهت هماهنگی کامل و عدم نیاز به تغییر در کدهای C/Go شما
    public static native void startXray(String configJson, int tunFd);
    public static native void stopXray();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn(intent);
        return START_STICKY;
    }

    private void startVpn(Intent intent) {
        if (isRunning) return;
        try {
            createNotificationChannel();
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);

            // ایجاد کانال تونل امن و پایدار لایه ۳ شبکه
            VpnService.Builder builder = new VpnService.Builder();
            builder.setMtu(1400);
            builder.addAddress("172.19.0.1", 30); // رنج آی‌پی محلی و استاندارد موتور gVisor
            builder.addDnsServer("1.1.1.1");
            builder.addDnsServer("8.8.8.8");
            builder.addRoute("0.0.0.0", 0); // هدایت کل ترافیک دستگاه به داخل فندق‌شکن
            builder.setSession("FandoghShekan");
            builder.addDisallowedApplication(getPackageName()); // جلوگیری از لوپ شدن دیتای خود اپلیکیشن

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "ساخت اینترفیس VPN با خطا مواجه شد.");
                stopVpn();
                return;
            }

            final int tunFd = vpnInterface.getFd();
            Log.d(TAG, "تونل امن با موفقیت باز شد. شناسه فایل: " + tunFd);

            // استخراج هوشمند لینک یا کانفیگ ارسال شده از اکتیویتی یا حافظه
            String rawConfig = null;
            if (intent != null) {
                rawConfig = intent.getStringExtra("COMMAND_CONFIG");
                if (rawConfig == null) {
                    rawConfig = intent.getStringExtra("VLESS_LINK");
                }
            }
            if (rawConfig == null || rawConfig.isEmpty()) {
                rawConfig = ConfigManager.getDecryptedConfig(getApplicationContext());
            }

            // تبدیل هوشمند دیتا به کانفیگ سازگار با اینباند TUN در Sing-box
            final String singBoxJson = convertToSingBoxJson(rawConfig, tunFd);

            isRunning = true;
            coreThread = new Thread(() -> {
                try {
                    Log.d(TAG, "در حال ارسال کانفیگ Sing-box به لایه نیتیو...");
                    startXray(singBoxJson, tunFd);
                } catch (Exception e) {
                    Log.e(TAG, "خطای کرش در هسته نیتیو: " + e.getMessage(), e);
                } finally {
                    isRunning = false;
                }
            }, "FandoghCoreThread");
            coreThread.start();

        } catch (Exception e) {
            Log.e(TAG, "خطا در راه‌اندازی سرویس: " + e.getMessage(), e);
            stopVpn();
        }
    }

    private String convertToSingBoxJson(String input, int tunFd) {
        // مقادیر پیش‌فرض پایدار در صورت بروز خطا در پارس کردن لینک
        String host = "YOUR_SERVER_ADDRESS";
        int port = 443;
        String uuid = "00000000-0000-0000-0000-000000000000";
        String path = "/";
        String sni = "google.com";

        if (input != null && input.startsWith("vless://")) {
            try {
                String current = input.substring(8);
                String[] hashSplit = current.split("#", 2);
                String mainPart = hashSplit[0];
                String[] querySplit = mainPart.split("\\?", 2);
                String credentialsAndServer = querySplit[0];
                String queryString = querySplit.length > 1 ? querySplit[1] : "";

                int atIdx = credentialsAndServer.lastIndexOf("@");
                if (atIdx != -1) {
                    uuid = credentialsAndServer.substring(0, atIdx);
                    String serverPart = credentialsAndServer.substring(atIdx + 1);
                    int colonIdx = serverPart.lastIndexOf(":");
                    if (colonIdx != -1) {
                        host = serverPart.substring(0, colonIdx).trim();
                        port = Integer.parseInt(serverPart.substring(colonIdx + 1).trim());
                    }
                }

                if (!queryString.isEmpty()) {
                    String[] pairs = queryString.split("&");
                    for (String pair : pairs) {
                        int idx = pair.indexOf("=");
                        if (idx != -1) {
                            String key = pair.substring(0, idx);
                            String val = pair.substring(idx + 1);
                            if ("path".equals(key)) path = java.net.URLDecoder.decode(val, "UTF-8");
                            if ("host".equals(key) || "sni".equals(key)) sni = val;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "خطا در پردازش لینک VLESS، استفاده از مقادیر پیش‌فرض: " + e.getMessage());
            }
        }

        // تولید قالب فوق‌العاده تمیز Sing-box که ترافیک tunFd را به طور مستقیم و بدون واسطه عبور می‌دهد
        return "{\n" +
                "  \"log\": {\"level\": \"warn\"},\n" +
                "  \"inbounds\": [{\n" +
                "    \"type\": \"tun\",\n" +
                "    \"tag\": \"tun-in\",\n" +
                "    \"fd\": " + tunFd + ",\n" +
                "    \"strict_route\": true,\n" +
                "    \"stack\": \"gvisor\"\n" +
                "  }],\n" +
                "  \"outbounds\": [{\n" +
                "    \"type\": \"vless\",\n" +
                "    \"tag\": \"proxy\",\n" +
                "    \"server\": \"" + host + "\",\n" +
                "    \"server_port\": " + port + ",\n" +
                "    \"uuid\": \"" + uuid + "\",\n" +
                "    \"flow\": \"xtls-rprx-vision\",\n" +
                "    \"packet_encoding\": \"xray\",\n" +
                "    \"tls\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"server_name\": \"" + sni + "\",\n" +
                "      \"utls\": {\"enabled\": true, \"fingerprint\": \"chrome\"}\n" +
                "    }\n" +
                "  }, {\n" +
                "    \"type\": \"direct\",\n" +
                "    \"tag\": \"direct\"\n" +
                "  }]\n" +
                "}";
    }

    private void stopVpn() {
        if (isRunning) {
            try {
                stopXray();
                Thread.sleep(100);
            } catch (Exception ignored) {}
            isRunning = false;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException ignored) {}
            vpnInterface = null;
        }
        stopForeground(true);
        if (coreThread != null && coreThread.isAlive()) {
            coreThread.interrupt();
            coreThread = null;
        }
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
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
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال برقرار است و ترافیک دستگاه شما ایمن گردید.")
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
