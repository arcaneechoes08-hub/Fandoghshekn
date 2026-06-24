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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FandoghVpnService extends VpnService {
    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final String CHANNEL_NAME = "VPN Service";

    private ParcelFileDescriptor vpnInterface = null;
    private volatile boolean isRunning = false;
    private Thread coreThread = null;

    static {
        System.loadLibrary("native-lib");
    }

    public static native int startCoreNative(String corePath, String configPath, int tunFd);
    public static native void stopCoreNative();

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            VpnService.Builder builder = new VpnService.Builder();
            builder.setMtu(1500);
            builder.addAddress("172.19.0.1", 30);
            builder.addDnsServer("1.1.1.1");
            builder.addDnsServer("8.8.8.8");
            builder.addRoute("0.0.0.0", 0);
            builder.addRoute("::", 0);
            builder.setSession("FandoghShekan");
            builder.addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.");
                stopVpn();
                return;
            }

            final int tunFd = vpnInterface.getFd();
            Log.d(TAG, "TUN interface established. FD: " + tunFd);

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

            if (rawConfig == null || rawConfig.isEmpty()) {
                Log.e(TAG, "No VPN config available.");
                stopVpn();
                return;
            }

            final String singBoxJson = convertToSingBoxJson(rawConfig, tunFd);
            Log.d(TAG, "Generated sing-box config length: " + singBoxJson.length());

            File configFile = new File(getFilesDir(), "config.json");
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                fos.write(singBoxJson.getBytes("UTF-8"));
            }

            String abi = Build.SUPPORTED_ABIS[0];
            String assetName = "singbox-arm64-v8a";
            if (abi.toLowerCase().contains("v7") || abi.toLowerCase().contains("armeabi")) {
                assetName = "singbox-armeabi-v7a";
            }

            File coreBin = new File(getFilesDir(), "singbox_core");
            try (InputStream is = getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(coreBin)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            coreBin.setExecutable(true, true);

            isRunning = true;
            coreThread = new Thread(() -> {
                try {
                    Log.d(TAG, "Launching Sing-box core...");
                    int exitCode = startCoreNative(
                            coreBin.getAbsolutePath(),
                            configFile.getAbsolutePath(),
                            tunFd);
                    Log.d(TAG, "Sing-box core exited with code: " + exitCode);
                } catch (Exception e) {
                    Log.e(TAG, "Native core error: " + e.getMessage(), e);
                } finally {
                    isRunning = false;
                }
            }, "FandoghCoreThread");
            coreThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN: " + e.getMessage(), e);
            stopVpn();
        }
    }

    private String convertToSingBoxJson(String input, int tunFd) {
        String host = "";
        int port = 443;
        String uuid = "";
        String type = "tcp";
        String security = "tls";
        String sni = "";
        String fp = "chrome";
        String path = "/";
        String wsHost = "";
        String serviceName = "";
        String headerType = "";
        String alpn = "";
        String flow = "";
        String pbk = "";
        String sid = "";

        if (input != null && input.startsWith("vless://")) {
            try {
                String current = input.substring(8);
                String[] hashSplit = current.split("#", 2);
                String mainPart = hashSplit[0];
                String[] querySplit = mainPart.split("\\?", 2);
                String credentials = querySplit[0];
                String query = querySplit.length > 1 ? querySplit[1] : "";

                int atIdx = credentials.lastIndexOf("@");
                if (atIdx != -1) {
                    uuid = credentials.substring(0, atIdx);
                    String serverPart = credentials.substring(atIdx + 1);
                    if (serverPart.startsWith("[")) {
                        int bEnd = serverPart.indexOf("]");
                        if (bEnd != -1) {
                            host = serverPart.substring(1, bEnd);
                            if (bEnd + 2 < serverPart.length()) {
                                port = Integer.parseInt(serverPart.substring(bEnd + 2).trim());
                            }
                        }
                    } else {
                        int cIdx = serverPart.lastIndexOf(":");
                        if (cIdx != -1) {
                            host = serverPart.substring(0, cIdx).trim();
                            port = Integer.parseInt(serverPart.substring(cIdx + 1).trim());
                        }
                    }
                }

                if (!query.isEmpty()) {
                    for (String pair : query.split("&")) {
                        int idx = pair.indexOf("=");
                        if (idx == -1) continue;
                        String key = pair.substring(0, idx);
                        String val = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        switch (key) {
                            case "type": type = val; break;
                            case "security": security = val; break;
                            case "sni": sni = val; break;
                            case "fp": fp = val; break;
                            case "path": path = val; break;
                            case "host": wsHost = val; break;
                            case "serviceName": serviceName = val; break;
                            case "headerType": headerType = val; break;
                            case "alpn": alpn = val; break;
                            case "flow": flow = val; break;
                            case "pbk": pbk = val; break;
                            case "sid": sid = val; break;
                        }
                    }
                }

                if (sni.isEmpty()) {
                    sni = !wsHost.isEmpty() ? wsHost : host;
                }
            } catch (Exception e) {
                Log.e(TAG, "VLESS parse error: " + e.getMessage());
            }
        }

        // flow is only valid for direct TCP (no additional transport layer)
        if (!"tcp".equals(type) || "http".equals(headerType)) {
            flow = "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // Log
        sb.append("  \"log\": {\"level\": \"info\"},\n");

        // DNS
        sb.append("  \"dns\": {\n");
        sb.append("    \"servers\": [\n");
        sb.append("      {\"tag\": \"proxy-dns\", \"address\": \"https://1.1.1.1/dns-query\", \"detour\": \"proxy\"},\n");
        sb.append("      {\"tag\": \"direct-dns\", \"address\": \"local\", \"detour\": \"direct\"}\n");
        sb.append("    ],\n");
        sb.append("    \"rules\": [{\"outbound\": \"any\", \"server\": \"direct-dns\"}],\n");
        sb.append("    \"strategy\": \"ipv4_only\"\n");
        sb.append("  },\n");

        // Inbounds (TUN with sniffing)
        sb.append("  \"inbounds\": [{\n");
        sb.append("    \"type\": \"tun\",\n");
        sb.append("    \"tag\": \"tun-in\",\n");
        sb.append("    \"fd\": ").append(tunFd).append(",\n");
        sb.append("    \"stack\": \"gvisor\",\n");
        sb.append("    \"sniff\": true,\n");
        sb.append("    \"sniff_override_destination\": true\n");
        sb.append("  }],\n");

        // Outbounds
        sb.append("  \"outbounds\": [{\n");
        sb.append("    \"type\": \"vless\",\n");
        sb.append("    \"tag\": \"proxy\",\n");
        sb.append("    \"server\": \"").append(host).append("\",\n");
        sb.append("    \"server_port\": ").append(port).append(",\n");
        sb.append("    \"uuid\": \"").append(uuid).append("\"");

        if (!flow.isEmpty()) {
            sb.append(",\n    \"flow\": \"").append(flow).append("\"");
        }

        // TLS block
        if ("tls".equals(security)) {
            sb.append(",\n    \"tls\": {\n");
            sb.append("      \"enabled\": true,\n");
            sb.append("      \"server_name\": \"").append(sni).append("\",\n");
            sb.append("      \"utls\": {\"enabled\": true, \"fingerprint\": \"").append(fp).append("\"}");
            if (!alpn.isEmpty()) {
                sb.append(",\n      \"alpn\": [");
                String[] parts = alpn.split(",");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(parts[i].trim()).append("\"");
                }
                sb.append("]");
            }
            sb.append("\n    }");
        } else if ("reality".equals(security)) {
            sb.append(",\n    \"tls\": {\n");
            sb.append("      \"enabled\": true,\n");
            sb.append("      \"server_name\": \"").append(sni).append("\",\n");
            sb.append("      \"utls\": {\"enabled\": true, \"fingerprint\": \"").append(fp).append("\"},\n");
            sb.append("      \"reality\": {\n");
            sb.append("        \"enabled\": true,\n");
            sb.append("        \"public_key\": \"").append(pbk).append("\",\n");
            sb.append("        \"short_id\": \"").append(sid).append("\"\n");
            sb.append("      }");
            if (!alpn.isEmpty()) {
                sb.append(",\n      \"alpn\": [");
                String[] parts = alpn.split(",");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(parts[i].trim()).append("\"");
                }
                sb.append("]");
            }
            sb.append("\n    }");
        }

        // Transport block
        if ("ws".equals(type)) {
            sb.append(",\n    \"transport\": {\n");
            sb.append("      \"type\": \"ws\",\n");
            sb.append("      \"path\": \"").append(path).append("\"");
            if (!wsHost.isEmpty()) {
                sb.append(",\n      \"headers\": {\"Host\": \"").append(wsHost).append("\"}");
            }
            sb.append("\n    }");
        } else if ("grpc".equals(type)) {
            sb.append(",\n    \"transport\": {\n");
            sb.append("      \"type\": \"grpc\",\n");
            sb.append("      \"service_name\": \"").append(serviceName).append("\"\n");
            sb.append("    }");
        } else if ("httpupgrade".equals(type)) {
            sb.append(",\n    \"transport\": {\n");
            sb.append("      \"type\": \"httpupgrade\",\n");
            sb.append("      \"path\": \"").append(path).append("\"");
            if (!wsHost.isEmpty()) {
                sb.append(",\n      \"headers\": {\"Host\": \"").append(wsHost).append("\"}");
            }
            sb.append("\n    }");
        } else if ("http".equals(type) || ("tcp".equals(type) && "http".equals(headerType))) {
            sb.append(",\n    \"transport\": {\n");
            sb.append("      \"type\": \"http\",\n");
            sb.append("      \"path\": \"").append(path).append("\"");
            if (!wsHost.isEmpty()) {
                sb.append(",\n      \"host\": [\"").append(wsHost).append("\"]");
            }
            sb.append("\n    }");
        }

        sb.append("\n  }, {\n");
        sb.append("    \"type\": \"direct\",\n");
        sb.append("    \"tag\": \"direct\"\n");
        sb.append("  }, {\n");
        sb.append("    \"type\": \"dns\",\n");
        sb.append("    \"tag\": \"dns-out\"\n");
        sb.append("  }],\n");

        // Route
        sb.append("  \"route\": {\n");
        sb.append("    \"rules\": [\n");
        sb.append("      {\"protocol\": \"dns\", \"outbound\": \"dns-out\"},\n");
        sb.append("      {\"ip_is_private\": true, \"outbound\": \"direct\"}\n");
        sb.append("    ],\n");
        sb.append("    \"final\": \"proxy\",\n");
        sb.append("    \"auto_detect_interface\": true\n");
        sb.append("  }\n");

        sb.append("}");
        return sb.toString();
    }

    private void stopVpn() {
        if (isRunning) {
            try {
                stopCoreNative();
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
