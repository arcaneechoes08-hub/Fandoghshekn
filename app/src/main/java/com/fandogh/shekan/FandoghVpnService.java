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

    static { System.loadLibrary("native-lib"); }

    private native int execWithFd(String[] cmd, int tunFd, String logPath);

    private void showStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("STOP".equals(intent.getAction())) { stopVpn(); return START_NOT_STICKY; }
            mVlessLink = intent.getStringExtra("VLESS_LINK");
        }
        if (mThread != null && mThread.isAlive()) stopVpn();
        mThread = new Thread(this, "FandoghVpnThread");
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() { stopVpn(); super.onDestroy(); }

    @Override
    public void run() {
        try {
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File xrayBin = new File(nativeDir, "libxray.so");
            File tun2socksBin = new File(nativeDir, "libtun2socks.so");
            File baseDir = getFilesDir();
            
            generateXrayConfigManual(mVlessLink, baseDir);

            Builder builder = new Builder();
            builder.setSession("FandoghShekan").addAddress("10.0.0.2", 24).addRoute("0.0.0.0", 0).addDnsServer("8.8.8.8").addDisallowedApplication(getPackageName());

            mInterface = builder.establish();
            int tunFd = mInterface.getFd();
            
            String[] xrayCmd = {xrayBin.getAbsolutePath(), "run", "-config", new File(baseDir, "config.json").getAbsolutePath()};
            mXrayProcess = new ProcessBuilder(xrayCmd).start();

            String[] t2sCmd = {tun2socksBin.getAbsolutePath(), "-device", "fd://" + tunFd, "-proxy", "socks5://127.0.0.1:10808"};
            mTun2SocksPid = execWithFd(t2sCmd, tunFd, new File(baseDir, "tun2socks.log").getAbsolutePath());

            while (mThread != null && !mThread.isInterrupted()) Thread.sleep(1000);
        } catch (Exception e) { showStatus("❌ خطا: " + e.getMessage()); } finally { stopVpn(); }
    }

    private void generateXrayConfigManual(String link, File dir) throws Exception {
        String current = link.substring(8);
        String[] hashSplit = current.split("#", 2);
        String[] querySplit = hashSplit[0].split("\\?", 2);
        int atIdx = querySplit[0].lastIndexOf("@");
        String uuid = querySplit[0].substring(0, atIdx);
        String host = querySplit[0].substring(atIdx + 1).split(":")[0];
        int port = Integer.parseInt(querySplit[0].substring(atIdx + 1).split(":")[1]);

        String json = "{\n" +
                "  \"log\": {\"loglevel\": \"warning\"},\n" +
                "  \"dns\": {\"servers\": [\"8.8.8.8\", \"1.1.1.1\"]},\n" +
                "  \"inbounds\": [{\"port\": 10808, \"protocol\": \"socks\", \"settings\": {\"auth\": \"noauth\", \"udp\": true}}],\n" +
                "  \"outbounds\": [{\n" +
                "    \"protocol\": \"vless\",\n" +
                "    \"settings\": {\"vnext\": [{\"address\": \"" + host + "\", \"port\": " + port + ", \"users\": [{\"id\": \"" + uuid + "\", \"encryption\": \"none\"}]}]},\n" +
                "    \"streamSettings\": {\"network\": \"ws\", \"security\": \"none\", \"wsSettings\": {\"path\": \"/\"}}\n" +
                "  }]\n" +
                "}";

        FileOutputStream fos = new FileOutputStream(new File(dir, "config.json"));
        fos.write(json.getBytes());
        fos.flush(); fos.close();
    }

    private void stopVpn() {
        if (mTun2SocksPid > 0) android.os.Process.sendSignal(mTun2SocksPid, 9);
        if (mXrayProcess != null) mXrayProcess.destroy();
        if (mInterface != null) try { mInterface.close(); } catch(Exception e){}
    }
}
