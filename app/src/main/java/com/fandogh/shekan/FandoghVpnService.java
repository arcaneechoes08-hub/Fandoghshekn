package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.util.Log;
import android.widget.Toast;

public class FandoghVpnService extends VpnService {
    
    // بارگذاری کتابخانه نیتیو ساخته شده توسط CMake
    static {
        System.loadLibrary("native-lib");
    }

    // معرفی متد لایه C به جاوا
    private native int startNativeCore(String config);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                String config = intent.getStringExtra("COMMAND_CONFIG");
                
                Log.i("FandoghService", "Sending configuration to native core...");
                // صدا زدن هسته C
                int result = startNativeCore(config);
                
                if (result == 0) {
                    Log.i("FandoghService", "Native core started successfully.");
                } else {
                    Log.e("FandoghService", "Failed to start native core.");
                }
            } else if ("com.fandogh.shekan.STOP".equals(action)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
