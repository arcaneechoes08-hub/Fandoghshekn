package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
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
            Log.i(TAG, "آماده‌سازی لایه‌های زیرین فندق‌شکن...");
            File binDir = getFilesDir();
            File xrayBin = new File(binDir, "xray");
            
            // ۱. استخراج باینری هسته از Assets
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
            xrayBin.setExecutable(true, false);

            // ۲. پارس کردن کاملاً دستی و ایمن لینک VLESS
            if (mVlessLink != null && mVlessLink.startsWith("vless://")) {
                generateXrayConfigManual(mVlessLink, binDir);
            } else {
                Log.e(TAG, "لینک کانفیگ خالی یا نامعتبر است!");
                return;
            }

            // ۳. ایجاد تونل مجازی اندروید با مهار لوپ ترافیکی
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    // مستثنی کردن خود اپلیکیشن برای جلوگیری از قفل شدن فرآیند Xray
                    .addDisallowedApplication(getPackageName())
                    .establish();

            Log.i(TAG, "چراغ سیستم‌عامل روشن شد. پرتاب موتور Xray...");

            // ۴. اجرای فرآیند باینری Xray
            String[] cmd = {
                xrayBin.getAbsolutePath(), 
                "run", 
                "-config", 
                new File(binDir, "config.json").getAbsolutePath()
            };
            
            mXrayProcess = Runtime.getRuntime().exec(cmd);
            Log.i(TAG, "🚀 موتور فندق‌شکن با موفقیت فعال شد!");

            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطای بحرانی در لایه سرویس: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateXrayConfigManual(String link, File dir) throws Exception {
        // حذف پروتکل ابتدایی
        String current = link.substring(8);
        
        // جدا کردن فرگمنت انتهایی (#)
        String[] hashSplit = current.split("#", 2);
        String mainPart = hashSplit[0];
        
        // جدا کردن بخش پارامترها (؟)
        String[] querySplit = mainPart.split("\\?", 2);
        String credentialsAndServer = querySplit[0];
        String queryString = querySplit.length > 1 ? querySplit[1] : "";
        
        // استخراج UUID و بخش سرور
        int atIdx = credentialsAndServer.lastIndexOf("@");
        if (atIdx == -1) throw new Exception("فرمت کاربری VLESS اشتباه است");
        String uuid = credentialsAndServer.substring(0, atIdx);
        String serverPart = credentialsAndServer.substring(atIdx + 1);
        
        // استخراج آی‌پي/هوست و پورت سرور
        int colonIdx = serverPart.lastIndexOf(":");
        if (colonIdx == -1) throw new Exception("پورت سرور یافت نشد");
        String host = serverPart.substring(0, colonIdx).trim();
        int port = Integer.parseInt(serverPart.substring(colonIdx + 1).trim());
        
        // استخراج اجزای تنظیمات شبکه
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

        // تزریق به بدنه ساختار استاندارد JSON
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
        Log.i(TAG, "فایل تراز شده‌ی config.json با موفقیت مستقر شد.");
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
            Log.i(TAG, "تونل با موفقیت تخلیه شد.");
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }
}
