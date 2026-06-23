package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.widget.Toast;

public class FandoghVpnService extends VpnService {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.fandogh.shekan.START".equals(action)) {
                String config = intent.getStringExtra("COMMAND_CONFIG");
                // 🎯 امیرعلی جان، اینجا بعداً متد JNI تو برای وصل شدن به کدهای CMake (C++) قرار می‌گیره
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
