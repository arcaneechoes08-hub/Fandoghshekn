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
import android.system.Os;
import android.system.OsConstants;
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

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mCoreProcess;
    private String mVlessLink;

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("STOP".equals(intent.getAction())) {
                stopVpn();
                return START_NOT_STICKY;
            }
            mVlessLink = intent.getStringExtra("VLESS_LINK");
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
            File coreBin = new File(nativeDir, "libxray.so");
            if (!coreBin.exists()) throw new Exception("هسته سیستمی یافت نشد!");
            
            // 🛠️ حل مشکل عدم اجرای فایل در اندروید ۱۰ به بالا
            coreBin.setExecutable(true);

            File baseDir = getFilesDir();
            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");

            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("172.19.0.1", 30) // IPv4
                    .addAddress("fd00:1:2:3::1", 126) // IPv6
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0) // هدایت کامل IPv4
                    .addRoute("::", 0) // 🛠️ حل نشتی (Leak) ترافیک IPv6
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) throw new Exception("مجوز ایجاد تونل صادر نشد!");

            // 🛠️ حل مشکل دسترسی هسته نیتیو به کارت شبکه (حذف فلگ O_CLOEXEC) در جاوا
            int fd = mInterface.getFd();
            int currentFlags = Os.fcntlInt(mInterface.getFileDescriptor(), OsConstants.F_GETFD, 0);
            Os.fcntlInt(mInterface.getFileDescriptor(), OsConstants.F_SETFD, currentFlags & ~OsConstants.O_CLOEXEC);

            generateSingBoxConfig(mVlessLink, baseDir, fd);
            showStatus("🚀 فندق‌شکن متصل شد.");

            ProcessBuilder pb = new ProcessBuilder(
                    coreBin.getAbsolutePath(),
                    "run",
                    "-c", // پرچم استاندارد سینگ‌باکس برای کانفیگ
                    new File(baseDir, "config.json").getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            mCoreProcess = pb.start();

            // 🛠️ حل مشکل مصرف باتری: منتظر ماندن ترد به جای حلقه While
            mCoreProcess.waitFor();
            
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateSingBoxConfig(String link, File dir, int tunFd) throws Exception {
        String host = "127.0.0.1";
        int port = 443;
        String uuid = "00000000-0000-0000-0000-000000000000";
        String type = "tcp";
        String security = "tls";
        String sni = "google.com";
        String fp = "chrome";
        String path = "/";
        String wsHost = "";

        // استخراج دیتا از لینک Vless
        if (link != null && link.startsWith("vless://")) {
            try {
                String current = link.substring(8);
                String[] hashSplit = current.split("#", 2);
                String[] querySplit = hashSplit[0].split("\\?", 2);
                String credentials = querySplit[0];
                String query = querySplit.length > 1 ? querySplit[1] : "";

                int atIdx = credentials.lastIndexOf("@");
                if (atIdx != -1) {
                    uuid = credentials.substring(0, atIdx);
                    String serverPart = credentials.substring(atIdx + 1);
                    int cIdx = serverPart.lastIndexOf(":");
                    if (cIdx != -1) {
                        host = serverPart.substring(0, cIdx).trim();
                        port = Integer.parseInt(serverPart.substring(cIdx + 1).trim());
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
                        }
                    }
                }
                if (sni.isEmpty() && !wsHost.isEmpty()) sni = wsHost;
                if (sni.isEmpty()) sni = host;
            } catch (Exception e) {
                Log.e(TAG, "خطا در پردازش لینک: " + e.getMessage());
            }
        }

        // ساخت JSON استاندارد سینگ‌باکس
        JSONObject root = new JSONObject();
        JSONObject log = new JSONObject();
        log.put("level", "warn");
        root.put("log", log);

        // Inbounds
        JSONArray inbounds = new JSONArray();
        JSONObject tunIn = new JSONObject();
        tunIn.put("type", "tun");
        tunIn.put("tag", "tun-in");
        tunIn.put("interface_name", "tun0");
        tunIn.put("fd", tunFd);
        tunIn.put("stack", "system"); // الزامی برای پایداری در اندروید
        tunIn.put("auto_route", true);
        inbounds.put(tunIn);
        root.put("inbounds", inbounds);

        // Outbounds
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
            tls.put("server_name", sni);
            JSONObject utls = new JSONObject();
            utls.put("enabled", true);
            utls.put("fingerprint", fp);
            tls.put("utls", utls);
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

        // ذخیره فایل
        File configFile = new File(dir, "config.json");
        FileOutputStream fos = new FileOutputStream(configFile);
        fos.write(root.toString(2).getBytes("UTF-8"));
        fos.flush();
        fos.close();
    }

    private void stopVpn() {
        try {
            if (mCoreProcess != null) {
                mCoreProcess.destroy(); // متوقف کردن پروسس باینری
                mCoreProcess = null;
            }
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
            stopForeground(true);
            stopSelf();
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
        Intent stopIntent = new Intent(this, FandoghVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("فندق‌شکن")
                .setContentText("اتصال ایمن برقرار است 🛡️")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
