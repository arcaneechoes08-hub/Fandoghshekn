package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class FandoghVpnService extends VpnService {
    
    private static final String TAG = "FandoghVpn";
    private Handler handler;
    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler = new Handler(Looper.getMainLooper());
        
        if (intent != null && intent.hasExtra("config")) {
            String config = intent.getStringExtra("config");
            startVPN(config);
        }
        
        return START_STICKY;
    }
    
    private void startVPN(String config) {
        vpnThread = new Thread(() -> {
            try {
                // Create VPN interface
                Builder builder = new Builder();
                builder.setSession("Fandogh-Shekan VPN")
                       .addAddress("10.0.0.2", 24)
                       .addRoute("0.0.0.0", 0);
                
                vpnInterface = builder.establish();
                
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface");
                    return;
                }
                
                // Execute VPN with native library
                executeVPN(config);
                
            } catch (Exception e) {
                Log.e(TAG, "VPN Error: " + e.getMessage(), e);
            }
        });
        
        vpnThread.start();
    }
    
    public native int execWithFd(String[] cmd, int tun_fd, String log_path);
    
    private void executeVPN(String config) {
        try {
            File logFile = new File(getCacheDir(), "vpn.log");
            
            String[] cmd = {"/system/bin/sh", "-c", config};
            int fd = vpnInterface.getFd();
            
            int pid = execWithFd(cmd, fd, logFile.getAbsolutePath());
            Log.d(TAG, "VPN Process started with PID: " + pid);
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing VPN: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface: " + e.getMessage());
            }
        }
        
        if (vpnThread != null && vpnThread.isAlive()) {
            vpnThread.interrupt();
        }
    }
    
    static {
        System.loadLibrary("native-lib");
    }
}
