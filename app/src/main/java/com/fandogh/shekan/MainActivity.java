package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    private String v2rayConfig = "";
    private ConfigManager configManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        configManager = new ConfigManager();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                btnConnect.setEnabled(false);
                btnConnect.setText("در حال رمزگشایی و اتصال...");
                
                // 🎯 فراخوانی شاهکار خودت: دانلود مخفی، دور زدن فیلترینگ و رمزگشایی AES
                configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
                    @Override
                    public void onSuccess(String decryptedConfig) {
                        v2rayConfig = decryptedConfig;
                        btnConnect.setEnabled(true);
                        
                        // ورود به مرحله فعال‌سازی تانل شبکه با کانفیگ کاملاً رمزگشایی شده
                        startFandoghVpn();
                    }

                    @Override
                    public void onError(String error) {
                        btnConnect.setEnabled(true);
                        btnConnect.setText("اتصال به فندق‌شکن");
                        Toast.makeText(MainActivity.this, "❌ " + error, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                stopFandoghVpn();
            }
        });
    }

    private void startFandoghVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 512);
        } else {
            onActivityResult(512, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 512 && resultCode == RESULT_OK) {
            try {
                Intent vpnIntent = new Intent(this, FandoghVpnService.class);
                vpnIntent.setAction("com.fandogh.shekan.START");
                vpnIntent.putExtra("COMMAND_CONFIG", v2rayConfig);
                startService(vpnIntent);

                isConnected = true;
                btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
                Toast.makeText(this, "فندق‌شکن فعال شد!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "خطا در استارت سرویس فندق", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopFandoghVpn() {
        Intent vpnIntent = new Intent(this, FandoghVpnService.class);
        vpnIntent.setAction("com.fandogh.shekan.STOP");
        startService(vpnIntent);

        isConnected = false;
        btnConnect.setText("اتصال به فندق‌شکن");
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
    }
}
