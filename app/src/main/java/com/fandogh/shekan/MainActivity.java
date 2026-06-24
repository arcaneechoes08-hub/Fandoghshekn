package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
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
        configManager = new ConfigManager(getApplicationContext());

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                btnConnect.setEnabled(false);
                btnConnect.setText("در حال دریافت کانفیگ...");

                configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
                    @Override
                    public void onSuccess(String decryptedConfig) {
                        v2rayConfig = decryptedConfig;
                        runOnUiThread(() -> {
                            btnConnect.setEnabled(true);
                            startFandoghVpn();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            btnConnect.setEnabled(true);
                            btnConnect.setText("اتصال به فندق‌شکن");
                            Toast.makeText(MainActivity.this, "خطا: " + error, Toast.LENGTH_LONG).show();
                        });
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
                vpnIntent.setAction("START");
                vpnIntent.putExtra("COMMAND_CONFIG", v2rayConfig);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent);
                } else {
                    startService(vpnIntent);
                }

                isConnected = true;
                btnConnect.setText("متصل شد (قطع اتصال)");
                Toast.makeText(this, "فندق‌شکن فعال شد!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "خطا در استارت سرویس فندق‌شکن", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopFandoghVpn() {
        try {
            Intent vpnIntent = new Intent(this, FandoghVpnService.class);
            vpnIntent.setAction("STOP");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent);
            } else {
                startService(vpnIntent);
            }
        } catch (Exception e) {
            // ignore
        }
        isConnected = false;
        btnConnect.setText("اتصال به فندق‌شکن");
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
    }
}
