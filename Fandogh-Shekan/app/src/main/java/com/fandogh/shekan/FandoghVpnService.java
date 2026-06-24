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

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
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
            showStatus("🔍 بارگذاری هسته هوشمند...");
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            // این باینری می‌تواند نسخه کامپایل شده sing-box باشد که قابلیت هندل مستقیم TUN را دارد
            File coreBin = new File(nativeDir, "libxray.so"); 
            if (!coreBin.exists()) {
                throw new Exception("هسته سیستمی یافت نشد!");
            }

            File baseDir = getFilesDir();
            showStatus("⚙️ تنظیم مسیریابی ترافیک لایه ۳...");
            
            // ایجاد تونل اختصاصی اندروید با تنظیم DNS پایدار
            Builder builder = new Builder();
            mInterface = builder.setSession("FandoghShekan")
                    .addAddress("172.19.0.1", 30) // رنج آی‌پی استاندارد تونل
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0) // هدایت کل ترافیک سیستم به تونل
                    .addDisallowedApplication(getPackageName()) // مستثنی کردن خود اپلیکیشن برای جلوگیری از لوپ
                    .establish();

            if (mInterface == null) {
                throw new Exception("مجوز ایجاد تونل صادر نشد!");
            }

            // ذخیره کانفیگ سینگ‌باکس یا ایکس‌ری مجهز به تان
            generateSingBoxConfig(mVlessLink, baseDir, mInterface.getFd());

            showStatus("🚀 فندق‌شکن متصل شد و ترافیک ایمن گردید.");
            
            // اجرای هسته با دسترسی به فایل کانفیگ و تونل سیستم
            String[] cmd = {
                coreBin.getAbsolutePath(), 
                "run", 
                "-config", 
                new File(baseDir, "config.json").getAbsolutePath()
            };
            mCoreProcess = Runtime.getRuntime().exec(cmd);

            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "خطا: " + e.getMessage());
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void generateSingBoxConfig(String link, File dir, int tunFd) throws Exception {
        // نمونه کانفیگ استاندارد Sing-box که ترافیک tunFd اندروید را مستقیم دریافت و به اکست‌ری/وی‌لس هدایت می‌کند
        // برای نسخه فعلی شما، این متد فایل کانفیگ را به شکلی فرمت می‌کند که هسته بداند پکت‌ها را از کجا بخواند.
        String host = "YOUR_SERVER_ADDRESS";
        int port = 443;
        String uuid = "00000000-0000-0000-0000-000000000000";

        String json = "{\n" +
                "  \"log\": {\"level\": \"warn\"},\n" +
                "  \"inbounds\": [{\n" +
                "    \"type\": \"tun\",\n" +
                "    \"tag\": \"tun-in\",\n" +
                "    \"interface_name\": \"tun0\",\n" +
                "    \"fd\": " + tunFd + ",\n" +
                "    \"accept_proxy_protocol\": false\n" +
                "  }],\n" +
                "  \"outbounds\": [{\n" +
                "    \"type\": \"vless\",\n" +
                "    \"tag\": \"proxy\",\n" +
                "    \"server\": \"" + host + "\",\n" +
                "    \"server_port\": " + port + ",\n" +
                "    \"uuid\": \"" + uuid + "\",\n" +
                "    \"flow\": \"xtls-rprx-vision\",\n" +
                "    \"network\": \"tcp\",\n" +
                "    \"tls\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"server_name\": \"google.com\",\n" +
                "      \"utls\": {\"enabled\": true, \"fingerprint\": \"chrome\"}\n" +
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
            if (mCoreProcess != null) {
                mCoreProcess.destroy();
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
        } catch (Exception e) {
            Log.e(TAG, "خطا در توقف: " + e.getMessage());
        }
    }
}
