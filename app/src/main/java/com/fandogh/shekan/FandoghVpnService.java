package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class FandoghVpnService extends VpnService implements Runnable {
    private static final String TAG = "FandoghVpnService";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private Process mXrayProcess;
    private int mTun2SocksPid = -1;
    private String mVlessLink;

    static {
        System.loadLibrary("native-lib");
    }

    private native int execWithFd(String[] cmd, int tunFd, String logPath);

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
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File xrayBin = new File(nativeDir, "libxray.so");
            File tun2socksBin = new File(nativeDir, "libtun2socks.so");

            if (!xrayBin.exists() || !tun2socksBin.exists()) {
                throw new Exception("هسته‌های شبکه یافت نشدند!");
            }

            File baseDir = getFilesDir();
            if (mVlessLink != null && mVlessLink.startsWith("vless://")) {
                generateXrayConfigManual(mVlessLink, baseDir);
            } else {
                throw new Exception("لینک VLESS نامعتبر است!");
            }

            Builder builder = new Builder();
            builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDisallowedApplication(getPackageName());

            mInterface = builder.establish();
            if (mInterface == null) throw new Exception("تونل VPN ایجاد نشد!");
            int tunFd = mInterface.getFd();

            // آماده‌سازی فایل‌های گزارش
            File xrayLog = new File(baseDir, "xray.log");
            File t2sLog = new File(baseDir, "tun2socks.log");
            if (xrayLog.exists()) xrayLog.delete();
            if (t2sLog.exists()) t2sLog.delete();

            // 🚀 ۱. روشن کردن Xray و هدایت هوشمند لوگ‌ها به فایل
            String[] xrayCmd = {xrayBin.getAbsolutePath(), "run", "-config", new File(baseDir, "config.json").getAbsolutePath()};
            ProcessBuilder xrayPb = new ProcessBuilder(xrayCmd);
            xrayPb.redirectOutput(xrayLog);
            xrayPb.redirectError(xrayLog);
            mXrayProcess = xrayPb.start();

            // 🚀 ۲. روشن کردن Tun2Socks از طریق لایه نیتیو
            String[] t2sCmd = {
                tun2socksBin.getAbsolutePath(),
                "-device", "fd://" + tunFd,
                "-proxy", "socks5://127.0.0.1:10808"
            };
            mTun2SocksPid = execWithFd(t2sCmd, tunFd, t2sLog.getAbsolutePath());

            showStatus("⏳ در حال تحلیل پایداری هسته‌ها...");
            
            // ۲ ثانیه صبر می‌کنیم تا هسته‌ها بالا بیایند و اگر اروری دارند بنویسند
            Thread.sleep(2000);
            checkCoarseLogs(baseDir);

            while (mThread != null && !mThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            showStatus("🛑 فندق‌شکن قطع شد.");
        } catch (Exception e) {
            showStatus("❌ خطا: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    // خواندن فایل‌های جعبه سیاه و نمایش مستقیم روی صفحه گوشی
    private void checkCoarseLogs(File baseDir) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                File xrayLog = new File(baseDir, "xray.log");
                File t2sLog = new File(baseDir, "tun2socks.log");
                StringBuilder report = new StringBuilder();

                if (xrayLog.exists()) {
                    report.append("🔹 Xray:\n");
                    BufferedReader br = new BufferedReader(new java.io.FileReader(xrayLog));
                    String line; int c = 0;
                    while ((line = br.readLine()) != null && c < 2) {
                        report.append(line).append("\n"); c++;
                    }
                    br.close();
                }

                if (t2sLog.exists()) {
                    report.append("\n🔸 Tun2Socks:\n");
                    BufferedReader br = new BufferedReader(new java.io.FileReader(t2sLog));
                    String line; int c = 0;
                    while ((line = br.readLine()) != null && c < 2) {
                        report.append(line).append("\n"); c++;
                    }
                    br.close();
                }

                if (report.length() > 0) {
                    Toast.makeText(getApplicationContext(), report.toString(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), "🚀 هسته‌ها فعال و سکوت برقرار است!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "خطا در بررسی جعبه سیاه", e);
            }
        });
    }

    private void generateXrayConfigManual(String link, File dir) throws Exception {
        String current = link.substring(8);
        String[] hashSplit = current.split("#", 2);
        String mainPart = hashSplit[0];
        String[] querySplit = mainPart.split("\\?", 2);
        String credentialsAndServer = querySplit[0];
        String queryString = querySplit.length > 1 ? querySplit[1] : "";
        
        int atIdx = credentialsAndServer.lastIndexOf("@");
        if (atIdx == -1) throw new Exception("فرمت کانفیگ اشتباه است");
        String uuid = credentialsAndServer.substring(0, atIdx);
        String serverPart = credentialsAndServer.substring(atIdx + 1);
        
        int colonIdx = serverPart.lastIndexOf(":");
        if (colonIdx == -1) throw new Exception("پورت سرور یافت نشد");
        String host = serverPart.substring(0, colonIdx).trim();
        int port = Integer.parseInt(serverPart.substring(colonIdx + 1).trim());
        
        Map<String, String> params = new HashMap<>();
        if (!queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                int idx = pair.indexOf("=");
                if (idx != -1) {
                    params.put(pair.substring(0, idx), java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                }
            }
        }
        
        String security = params.getOrDefault("security", "none");
        String network = params.getOrDefault("type", "tcp");
        String sni = params.getOrDefault("sni", params.getOrDefault("host", host));
        String path = params.getOrDefault("path", "/");
        String pbk = params.getOrDefault("pbk", "");
        String sid = params.getOrDefault("sid", "");
        String fp = params.getOrDefault("fp", "chrome");
        String flow = params.getOrDefault("flow", ""); 

        StringBuilder streamStr = new StringBuilder();
        streamStr.append("{\n")
                 .append("  \"network\": \"").append(network).append("\",\n")
                 .append("  \"security\": \"").append(security).append("\"");

        if ("reality".equals(security)) {
            streamStr.append(",\n  \"realitySettings\": {\n")
                     .append("    \"show\": false,\n")
                     .append("    \"fingerprint\": \"").append(fp).append("\",\n")
                     .append("    \"serverName\": \"").append(sni).append("\",\n")
                     .append("    \"publicKey\": \"").append(pbk).append("\",\n")
                     .append("    \"shortId\": \"").append(sid).append("\"\n")
                     .append("  }");
        } else if ("tls".equals(security)) {
            streamStr.append(",\n  \"tlsSettings\": {\n")
                     .append("    \"serverName\": \"").append(sni).append("\",\n")
                     .append("    \"fingerprint\": \"").append(fp).append("\"\n")
                     .append("  }");
        }

        if ("ws".equals(network)) {
            streamStr.append(",\n  \"wsSettings\": {\n")
                     .append("    \"path\": \"").append(path).append("\",\n")
                     .append("    \"headers\": {\"Host\": \"").append(sni).append("\"}\n")
                     .append("  }");
        } else if ("grpc".equals(network)) {
            String serviceName = params.getOrDefault("serviceName", "");
            streamStr.append(",\n  \"grpcSettings\": {\n")
                     .append("    \"serviceName\": \"").append(serviceName).append("\"\n")
                     .append("  }");
        }
        streamStr.append("\n}");

        String userSettings = "{\"id\": \"" + uuid + "\", \"encryption\": \"none\"" + 
                (!flow.isEmpty() ? ", \"flow\": \"" + flow + "\"" : "") + "}";

        String json = "{\n" +
                "  \"log\": {\"loglevel\": \"warning\"},\n" +
                "  \"inbounds\": [\n" +
                "    {\"port\": 10808, \"protocol\": \"socks\", \"settings\": {\"auth\": \"noauth\", \"udp\": true}}\n" +
                "  ],\n" +
                "  \"outbounds\": [{\n" +
                "    \"protocol\": \"vless\",\n" +
                "    \"settings\": {\"vnext\": [{\"address\": \"" + host + "\", \"port\": " + port + ", \"users\": [" + userSettings + "]}]},\n" +
                "    \"streamSettings\": " + streamStr.toString() + "\n" +
                "  }]\n" +
                "}";

        FileOutputStream fos = new FileOutputStream(new File(dir, "config.json"));
        fos.write(json.getBytes());
        fos.flush(); fos.close();
    }

    private void stopVpn() {
        try {
            if (mTun2SocksPid > 0) {
                android.os.Process.sendSignal(mTun2SocksPid, 9);
                mTun2SocksPid = -1;
            }
            if (mXrayProcess != null) { mXrayProcess.destroy(); mXrayProcess = null; }
            if (mThread != null) { mThread.interrupt(); mThread = null; }
            if (mInterface != null) { mInterface.close(); mInterface = null; }
        } catch (Exception e) { Log.e(TAG, "خطا در توقف: " + e.getMessage()); }
    }
}
