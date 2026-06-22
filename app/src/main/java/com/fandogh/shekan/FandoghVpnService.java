package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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
            // دریافت لینک کانفیگ از اکتیویتی اصلی
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

            // ۲. پارس کردن لینک VLESS و ساخت فایل config.json
            if (mVlessLink != null && mVlessLink.startsWith("vless://")) {
                generateXrayConfig(mVlessLink, binDir);
            } else {
                Log.e(TAG, "لینک کانفیگ نامعتبر است یا دریافت نشد!");
                return;
            }

            // ۳. ایجاد تونل مجازی اینترنت (TUN Interface)
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .establish();

            Log.i(TAG, "تونل هدایت ترافیک باز شد. پرتاب موتور Xray...");

            // ۴. اجرای باینری Xray در پس‌زمینه با کانفیگ ساخته شده
            String[] cmd = {
                xrayBin.getAbsolutePath(), 
                "run", 
                "-config", 
                new File(binDir, "config.json").getAbsolutePath()
            };
            
            mXrayProcess = Runtime.getRuntime().exec(cmd);
            Log.i(TAG, "🚀 موتور فندق‌شکن با موفقیت در لایه لینوکس روشن شد!");

            // زنده نگه داشتن تِرد سرویس
            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطای بحرانی در لایه سرویس: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateXrayConfig(String link, File dir) throws Exception {
        // یک پارسر دستی و سریع برای استخراج اجزای VLESS
        URI uri = new URI(link.replace("#", "?hash="));
        String uuid = uri.getUserInfo();
        String host = uri.getHost();
        int port = uri.getPort();
        
        Map<String, String> queryPairs = new HashMap<>();
        String query = uri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            queryPairs.put(pair.substring(0, idx), pair.substring(idx + 1));
        }

        String path = queryPairs.containsKey("path") ? java.net.URLDecoder.decode(queryPairs.get("path"), "UTF-8") : "/";
        String sni = queryPairs.containsKey("host") ? queryPairs.get("host") : host;

        // ساخت تمپلیت ساختاریافته‌ی JSON برای هسته Xray
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
        Log.i(TAG, "فایل تنظیمات هسته (config.json) با موفقیت پخته شد.");
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
            Log.i(TAG, "سیستم به حالت عادی برگشت.");
        } catch (Exception e) {
            Log.e(TAG, "خطا در تخلیه متغیرها: " + e.getMessage());
        }
    }
}
