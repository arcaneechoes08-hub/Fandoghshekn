package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mXrayProcess;
    private String mVlessLink;

    // تابع کمکی برای نمایش وضعیت به صورت زنده روی صفحه گوشی
    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show()
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
            showStatus("🔍 گام ۱: بررسی و استخراج هسته Xray...");
            File binDir = getFilesDir();
            File xrayBin = new File(binDir, "xray");
            
            if (!xrayBin.exists()) {
                try {
                    InputStream is = getAssets().open("xray");
                    OutputStream os = new FileOutputStream(xrayBin);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        os.write(buffer, 0, length);
                    }
                    os.flush(); os.close(); is.close();
                } catch (Exception assetError) {
                    throw new Exception("فایل باینری Xray در Assets یافت نشد! بیلد ناقص است.");
                }
            }
            xrayBin.setExecutable(true, false);

            showStatus("⚙️ گام ۲: تبدیل لینک به فایل کانفیگ...");
            if (mVlessLink != null && mVlessLink.startsWith("vless://")) {
                generateXrayConfigManual(mVlessLink, binDir);
            } else {
                throw new Exception("لینک VLESS خالی یا نامعتبر است!");
            }

            showStatus("🌐 گام ۳: در حال فعال‌سازی کلید VPN اندروید...");
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (mInterface == null) {
                throw new Exception("سیستم‌عامل اجازه ایجاد تونل مجازی را نداد!");
            }

            showStatus("🚀 فندق‌شکن با موفقیت متصل شد! کلید فعال شد.");

            String[] cmd = {
                xrayBin.getAbsolutePath(), 
                "run", 
                "-config", 
                new File(binDir, "config.json").getAbsolutePath()
            };
            
            mXrayProcess = Runtime.getRuntime().exec(cmd);

            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            showStatus("❌ خطا در اتصال: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateXrayConfigManual(String link, File dir) throws Exception {
        String current = link.substring(8);
        String[] hashSplit = current.split("#", 2);
        String mainPart = hashSplit[0];
        String[] querySplit = mainPart.split("\\?", 2);
        String credentialsAndServer = querySplit[0];
        String queryString = querySplit.length > 1 ? querySplit[1] : "";
        
        int atIdx = credentialsAndServer.lastIndexOf("@");
        if (atIdx == -1) throw new Exception("فرمت کانفیگ اشتباه است (@ ندارد)");
        String uuid = credentialsAndServer.substring(0, atIdx);
        String serverPart = credentialsAndServer.substring(atIdx + 1);
        
        int colonIdx = serverPart.lastIndexOf(":");
        if (colonIdx == -1) throw new Exception("پورت سرور در لینک پیدا نشد");
        String host = serverPart.substring(0, colonIdx).trim();
        int port = Integer.parseInt(serverPart.substring(colonIdx + 1).trim());
        
        Map<String, String> queryPairs = new HashMap<>();
        if (!queryString.isEmpty()) {
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx != -1) {
                    queryPairs.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
            }
        }

        String path = queryPairs.containsKey("path") ? java.net.URLDecoder.decode(queryPairs.get("path"), "UTF-8") : "/";
        String sni = queryPairs.containsKey("host") ? queryPairs.get("host") : host;

        String json = "{\n" +
                "  \"log\": {\"loglevel\": \"warning\"},\n" +
                "  \"inbounds\": [{\n" +
                "    \"port\": 10808,\n" +
                "    \"protocol\": \"socks\",\n" +
                "    \"settings\": {\"auth\": \"noauth\", \"udp\": true}\n" +
                "  }],\n" +
                "  \"outbounds\": [{\n" +
                "    \"protocol\": \"vless\",\n" +
                "    \"settings\": {\n" +
                "      \"vnext\": [{\n" +
                "        \"address\": \"" + host + "\",\n" +
                "        \"port\": " + port + ",\n" +
                "        \"users\": [{\"id\": \"" + uuid + "\", \"encryption\": \"none\"}]\n" +
                "      }]\n" +
                "    },\n" +
                "    \"streamSettings\": {\n" +
                "      \"network\": \"ws\",\n" +
                "      \"security\": \"none\",\n" +
                "      \"wsSettings\": {\n" +
                "        \"path\": \"" + path + "\",\n" +
                "        \"headers\": {\"Host\": \"" + sni + "\"}\n" +
                "      }\n" +
                "    }\n" +
                "  }]\n" +
                "}";

        File configFile = new File(dir, "config.json");
        FileOutputStream fos = new FileOutputStream(configFile);
        fos.write(json.getBytes());
        fos.flush(); fos.close();
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
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }
}
