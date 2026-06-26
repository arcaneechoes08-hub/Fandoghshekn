package com.fandogh.shekan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "FandoghVpnChannel";

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
            String action = intent.getAction();
            if ("STOP".equals(action)) {
                stopVpn();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent.hasExtra("VLESS_LINK")) {
                mVlessLink = intent.getStringExtra("VLESS_LINK");
            }
        }

        if (mThread != null && mThread.isAlive()) {
            stopVpn();
        }

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
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
        File baseDir = getFilesDir();
        try {
            showStatus("ðŸ” Ø¨Ø§Ø±Ú¯Ø°Ø§Ø±ÛŒ Ù‡Ø³ØªÙ‡ Ù‡ÙˆØ´Ù…Ù†Ø¯...");
            String nativeDir = getApplicationInfo().nativeLibraryDir;

            File coreBin = new File(nativeDir, "libxray.so");
            if (!coreBin.exists()) throw new Exception("Ù‡Ø³ØªÙ‡ Ø³ÛŒØ³ØªÙ…ÛŒ ÛŒØ§ÙØª Ù†Ø´Ø¯!");
            coreBin.setExecutable(true);

            showStatus("âš™ï¸ ØªÙ†Ø¸ÛŒÙ… Ù…Ø³ÛŒØ±ÛŒØ§Ø¨ÛŒ ØªØ±Ø§ÙÛŒÚ©...");

            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fd00:1:2:3::1", 126)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) throw new Exception("Ù…Ø¬ÙˆØ² Ø§ÛŒØ¬Ø§Ø¯ ØªÙˆÙ†Ù„ ØµØ§Ø¯Ø± Ù†Ø´Ø¯!");

            int fd = mInterface.getFd();
            AppLog.add(TAG, "TUN fd Ø³Ø§Ø®ØªÙ‡ Ø´Ø¯: " + fd);

            generateCoreConfig(mVlessLink, baseDir, fd);
            AppLog.add(TAG, "config.json Ù†ÙˆØ´ØªÙ‡ Ø´Ø¯.");

            showStatus("ðŸš€ ÙÙ†Ø¯Ù‚â€ŒØ´Ú©Ù† Ù…ØªØµÙ„ Ø´Ø¯.");

            int pid = startCoreNative(
                    coreBin.getAbsolutePath(),
                    new File(baseDir, "config.json").getAbsolutePath(),
                    fd
            );

            if (pid < 0) throw new Exception("Ø§Ø¬Ø±Ø§ÛŒ Ù‡Ø³ØªÙ‡ Ø¨Ø§ Ø®Ø·Ø§ Ù…ÙˆØ§Ø¬Ù‡ Ø´Ø¯!");
            AppLog.add(TAG, "Ù‡Ø³ØªÙ‡ Ø¨Ø§ PID=" + pid + " Ø§Ø¬Ø±Ø§ Ø´Ø¯.");

            // ØµØ¨Ø± Ú©Ù† Ù‡Ø³ØªÙ‡ start Ø¨Ø´Ù‡ Ø¨Ø¹Ø¯ Ù„Ø§Ú¯Ø´ Ø±Ùˆ Ø¨Ø®ÙˆÙ†
            Thread.sleep(2000);
            readAndLogCoreOutput(baseDir);

            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            // Ø®Ø±ÙˆØ¬ Ø§Ù…Ù† Ø§Ø² Ø­Ù„Ù‚Ù‡
        } catch (Exception e) {
            Log.e(TAG, "Ø®Ø·Ø§: " + e.getMessage());
            AppLog.add(TAG, "âŒ Ø®Ø·Ø§: " + e.getMessage());
            showStatus("âŒ Ø®Ø·Ø§: " + e.getMessage());
            // Ø­ØªÛŒ Ø§Ú¯Ù‡ Ø®Ø·Ø§ Ø¨ÙˆØ¯ Ù„Ø§Ú¯ Ù‡Ø³ØªÙ‡ Ø±Ùˆ Ø¨Ø®ÙˆÙ†
            readAndLogCoreOutput(baseDir);
        } finally {
            stopVpn();
        }
    }

    private void readAndLogCoreOutput(File baseDir) {
        File logFile = new File(baseDir, "core_output.log");
        if (!logFile.exists()) {
            AppLog.add(TAG, "âš ï¸ ÙØ§ÛŒÙ„ Ù„Ø§Ú¯ Ù‡Ø³ØªÙ‡ ÙˆØ¬ÙˆØ¯ Ù†Ø¯Ø§Ø±Ø¯ - Ù‡Ø³ØªÙ‡ Ø§ØµÙ„Ø§Ù‹ Ø§Ø¬Ø±Ø§ Ù†Ø´Ø¯Ù‡!");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 50) {
                sb.append(line).append("\n");
                count++;
            }
            String output = sb.toString().trim();
            if (output.isEmpty()) {
                AppLog.add(TAG, "âš ï¸ Ù„Ø§Ú¯ Ù‡Ø³ØªÙ‡ Ø®Ø§Ù„ÛŒÙ‡ - Ø§Ø­ØªÙ…Ø§Ù„Ø§Ù‹ config.json Ù…Ø´Ú©Ù„ Ø¯Ø§Ø±Ø¯");
            } else {
                AppLog.add(TAG, "ðŸ“‹ Ø®Ø±ÙˆØ¬ÛŒ Ù‡Ø³ØªÙ‡:\n" + output);
            }
        } catch (Exception e) {
            AppLog.add(TAG, "Ø®Ø·Ø§ Ø¯Ø± Ø®ÙˆØ§Ù†Ø¯Ù† Ù„Ø§Ú¯ Ù‡Ø³ØªÙ‡: " + e.getMessage());
        }
    }

    private void generateCoreConfig(String link, File baseDir, int tunFd) throws Exception {
        if (link == null || !link.startsWith("vless://")) {
            throw new Exception("Ù„ÛŒÙ†Ú© Ú©Ø§Ù†ÙÛŒÚ¯ Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª (ÙÙ‚Ø· vless Ù¾Ø´ØªÛŒØ¨Ø§Ù†ÛŒ Ù…ÛŒâ€ŒØ´ÙˆØ¯).");
        }

        String host = "", uuid = "", security = "none", type = "tcp",
               path = "/", sni = "", wsHost = "", fp = "";
        int port = 443;

        try {
            String uriBody = link.substring(8);
            int hashIdx = uriBody.indexOf("#");
            if (hashIdx != -1) uriBody = uriBody.substring(0, hashIdx);

            String[] querySplit = uriBody.split("\\?", 2);
            String credentialsAndServer = querySplit[0];
            String queryParams = querySplit.length > 1 ? querySplit[1] : "";

            int atIdx = credentialsAndServer.lastIndexOf("@");
            uuid = credentialsAndServer.substring(0, atIdx);
            String serverPart = credentialsAndServer.substring(atIdx + 1);

            int colonIdx = serverPart.lastIndexOf(":");
            host = serverPart.substring(0, colonIdx);
            port = Integer.parseInt(serverPart.substring(colonIdx + 1));

            if (!queryParams.isEmpty()) {
                for (String param : queryParams.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length < 2) continue;
                    String key = kv[0];
                    String val = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    switch (key) {
                        case "type":     type     = val; break;
                        case "security": security = val; break;
                        case "sni":      sni      = val; break;
                        case "host":     wsHost   = val; break;
                        case "path":     path     = val; break;
                        case "fp":       fp       = val; break;
                    }
                }
            }
        } catch (Exception e) {
            throw new Exception("Ø®Ø·Ø§ Ø¯Ø± Ù¾Ø§Ø±Ø³ Ú©Ø±Ø¯Ù† Ù„ÛŒÙ†Ú© vless");
        }

        AppLog.add(TAG, "Ù¾Ø§Ø±Ø³ Ù„ÛŒÙ†Ú©: host=" + host + " port=" + port + " type=" + type + " security=" + security);

        JSONObject root = new JSONObject();

        JSONObject log = new JSONObject();
        log.put("level", "warn");
        root.put("log", log);

        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        JSONObject dnsServer = new JSONObject();
        dnsServer.put("tag", "google");
        dnsServer.put("address", "8.8.8.8");
        dnsServers.put(dnsServer);
        dns.put("servers", dnsServers);
        root.put("dns", dns);

        // âœ… Ø§ØµÙ„Ø§Ø­ Ø§ØµÙ„ÛŒ: Ø¨Ù‡ Ø¬Ø§ÛŒ fd Ù…Ø³ØªÙ‚ÛŒÙ…ØŒ Ø§Ø² /proc/self/fd/N Ø§Ø³ØªÙØ§Ø¯Ù‡ Ù…ÛŒâ€ŒÚ©Ù†ÛŒÙ…
        // sing-box Ù†Ø³Ø®Ù‡â€ŒÙ‡Ø§ÛŒ Ø¬Ø¯ÛŒØ¯ fd Ø±Ø§ Ø¯Ø± inbound Ù‚Ø¨ÙˆÙ„ Ù†Ù…ÛŒâ€ŒÚ©Ù†Ø¯
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("device", "/proc/self/fd/" + tunFd); // âœ… fd Ø§Ø² Ø·Ø±ÛŒÙ‚ Ù…Ø³ÛŒØ± /proc
        tunIn.put("mtu", 1500);
        tunIn.put("inet4_address", "172.19.0.1/30");
        tunIn.put("inet6_address", "fd00:1:2:3::1/126");
        tunIn.put("auto_route", false);                 // âœ… Android Ø®ÙˆØ¯Ø´ routing Ø³Ø§Ø®ØªÙ‡
        tunIn.put("strict_route", false);
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        JSONArray outbounds = new JSONArray();
        JSONObject vlessOut = new JSONObject();
        vlessOut.put("type", "vless");
        vlessOut.put("tag", "proxy");
        vlessOut.put("server", host);
        vlessOut.put("server_port", port);
        vlessOut.put("uuid", uuid);

        if ("tls".equals(security) || "reality".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            tls.put("server_name", sni.isEmpty() ? host : sni);
            if (!fp.isEmpty()) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
            vlessOut.put("tls", tls);
        }

        if ("ws".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            transport.put("path", path);
            if (!wsHost.isEmpty()) {
                JSONObject headers = new JSONObject();
                headers.put("Host", wsHost);
                transport.put("headers", headers);
            }
            vlessOut.put("transport", transport);
        }

        outbounds.put(vlessOut);
        root.put("outbounds", outbounds);

        JSONObject route = new JSONObject();
        JSONArray rules = new JSONArray();
        JSONObject rule = new JSONObject();
        rule.put("inbound", new JSONArray().put("tun-in"));
        rule.put("outbound", "proxy");
        rules.put(rule);
        route.put("rules", rules);
        route.put("final", "proxy");
        root.put("route", route);

        AppLog.add(TAG, "JSON Config:\n" + root.toString(2));

        File configFile = new File(baseDir, "config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(root.toString(2).getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void stopVpn() {
        try {
            stopCoreNative();
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
            Log.e(TAG, "Ø®Ø·Ø§ Ø¯Ø± ØªÙˆÙ‚Ù: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fandogh VPN Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, FandoghVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ÙÙ†Ø¯Ù‚â€ŒØ´Ú©Ù†")
                .setContentText("Ø§ØªØµØ§Ù„ Ø§ÛŒÙ…Ù† Ø¨Ø±Ù‚Ø±Ø§Ø± Ø§Ø³Øª ðŸ›¡ï¸")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ù‚Ø·Ø¹ Ø§ØªØµØ§Ù„", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
    }
