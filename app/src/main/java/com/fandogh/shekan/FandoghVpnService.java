package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class FandoghVpnService extends VpnService {
    private static final String TAG = "FandoghVpnService";
    private ParcelFileDescriptor vpnInterface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        Log.d(TAG, "Starting Fandogh VPN Tunnel...");
        try {
            Builder builder = new Builder();
            // ایجاد یک تونل مجازی داخلی
            vpnInterface = builder.setSession("FandoghShekan")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0) // هدایت کل ترافیک گوشی به اپلیکیشن
                    .addDnsServer("8.8.8.8")
                    .establish();
            
            Log.d(TAG, "VPN Interface established successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN Interface", e);
        }
    }

    private void stopVpn() {
        Log.d(TAG, "Stopping Fandogh VPN Tunnel...");
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN interface", e);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
