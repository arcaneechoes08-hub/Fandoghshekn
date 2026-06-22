package com.fandogh.shekan;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    private boolean isConnected = false;
    private Button btnConnect;
    private ConfigManager configManager;
    private PingManager pingManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnConnect = findViewById(R.id.btnConnect);
        configManager = new ConfigManager();
        pingManager = new PingManager();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startFetchingConfig();
            } else {
                stopFandoghShekan();
            }
        });
    }

    private void startFetchingConfig() {
        btnConnect.setText("در حال دریافت کانفیگ...");
        btnConnect.setBackgroundColor(0xFF2196F3); // آبی
        btnConnect.setEnabled(false);

        configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
            @Override
            public void onSuccess(String decryptedConfig) {
                btnConnect.setText("در حال تست پینگ سرور...");
                parseAndPing(decryptedConfig);
            }

            @Override
            public void onError(String error) {
                resetButton("خطا در شبکه!", 0xFFF44336);
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void parseAndPing(String config) {
        try {
            if (config == null) throw new Exception("کانفیگ خالی است");
            config = config.trim();
            
            if (!config.startsWith("vless://")) {
                throw new Exception("لینک vless معتبر نیست");
            }
            
            String uriBody = config.substring(8); 
            int atIndex = uriBody.lastIndexOf("@");
            if (atIndex == -1) throw new Exception("علامت @ پیدا نشد");
            
            String serverPart = uriBody.substring(atIndex + 1);
            String[] mainParts = serverPart.split("[?#]");
            String hostAndPort = mainParts[0];
            
            int colonIndex = hostAndPort.lastIndexOf(":");
            if (colonIndex == -1) throw new Exception("پورت سرور پیدا نشد");
            
            String host = hostAndPort.substring(0, colonIndex).trim();
            String portStr = hostAndPort.substring(colonIndex + 1).trim();
            
            int port = Integer.parseInt(portStr);

            pingManager.checkTcpPing(host, port, new PingManager.PingCallback() {
                @Override
                public void onResult(long latencyMs) {
                    Toast.makeText(MainActivity.this, "پینگ سرور: " + latencyMs + "ms", Toast.LENGTH_SHORT).show();
                    Intent intent = VpnService.prepare(MainActivity.this);
                    if (intent != null) {
                        startActivityForResult(intent, 0);
                    } else {
                        onActivityResult(0, RESULT_OK, null);
                    }
                }

                @Override
                public void onError(String error) {
                    resetButton("سرور قطع است (Timeout)", 0xFFE91E63);
                }
            });

        } catch (Exception e) {
            // سیستم رادیولوژی: نمایش دلیل خطا به همراه ۲۵ کاراکتر اول متن دریافتی
            String preview = "";
            if (config != null) {
                int end = Math.min(config.length(), 25);
                preview = " -> [" + config.substring(0, end) + "]";
            }
            resetButton("خطا: " + e.getMessage() + preview, 0xFFF44336);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            Intent intent = new Intent(this, FandoghVpnService.class);
            startService(intent);
            
            btnConnect.setEnabled(true);
            btnConnect.setText("فندق‌شکن فعال است 🛡️");
            btnConnect.setBackgroundColor(0xFF4CAF50); // سبز
            isConnected = true;
        } else {
            resetButton("عدم تایید مجوز VPN", 0xFFF44336);
        }
    }

    private void stopFandoghShekan() {
        Intent intent = new Intent(this, FandoghVpnService.class);
        intent.setAction("STOP");
        startService(intent);
        resetButton("اتصال هوشمند", 0xFFFF9800); // نارنجی
    }

    private void resetButton(String text, int color) {
        btnConnect.setEnabled(true);
        btnConnect.setText(text);
        btnConnect.setBackgroundColor(color);
        isConnected = false;
    }
}
