package com.fandogh.shekan;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;

// این خط تیر خلاص است: ایمپورت مستقیم منابع
import com.fandogh.shekan.R;

public class MainActivity extends Activity {

    private boolean isConnected = false;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (isDeviceRooted()) {
            setContentView(new TextView(this)); 
            Toast.makeText(this, "امکان اجرای فندق‌شکن روی دستگاه‌های روت‌شده وجود ندارد!", Toast.LENGTH_LONG).show();
            finish(); 
            return;
        }

        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startFandoghShekan();
            } else {
                stopFandoghShekan();
            }
        });
    }

    private boolean isDeviceRooted() {
        String[] paths = {
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }

        String buildTags = android.os.Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) return true;

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{ "/system/xbin/which", "su" });
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            if (in.readLine() != null) return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void startFandoghShekan() {
        btnConnect.setText("در حال دریافت کانفیگ‌های امن...");
        btnConnect.setBackgroundColor(0xFF2196F3); 
        
        // تغییر وضعیت برای تست
        btnConnect.setText("متصل به سریع‌ترین سرور 🥜");
        btnConnect.setBackgroundColor(0xFF4CAF50); 
        isConnected = true;
    }

    private void stopFandoghShekan() {
        btnConnect.setText("اتصال هوشمند");
        btnConnect.setBackgroundColor(0xFFFF9800); 
        isConnected = false;
    }
}
