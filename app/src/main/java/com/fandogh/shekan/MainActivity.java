package com.fandogh.shekan;

import android.app.Activity;
import android.os.Bundle;
import import android.widget.Button;
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
            // یک پارسر ساده و سریع برای استخراج Host و Port از لینک vless
            String cleanConfig = config.replace("vless://", "");
            String[] partsAfterAt = cleanConfig.split("@");
            String[] hostAndPortParts = partsAfterAt[1].split(":");
            
            String host = hostAndPortParts[0];
            String portWithQuery = hostAndPortParts[1];
            
            // جدا کردن پورت از باقی پارامترها (مثل ?type=ws)
            int port = Integer.parseInt(portWithQuery.split("\\?")[0].split("#")[0]);

            // شروع عملیات پینگ
            pingManager.checkTcpPing(host, port, new PingManager.PingCallback() {
                @Override
                public void onResult(long latencyMs) {
                    btnConnect.setEnabled(true);
                    btnConnect.setText("متصل شد (Ping: " + latencyMs + "ms) ⚡");
                    btnConnect.setBackgroundColor(0xFF4CAF50); // سبز
                    isConnected = true;
                    Toast.makeText(MainActivity.this, "سرور زنده است!", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String error) {
                    resetButton("سرور قطع است (Timeout)", 0xFFE91E63); // صورتی/قرمز مایل به ارور پینگ
                }
            });

        } catch (Exception e) {
            resetButton("خطا در ساختار کانفیگ", 0xFFF44336);
        }
    }

    private void stopFandoghShekan() {
        resetButton("اتصال هوشمند", 0xFFFF9800); // نارنجی
    }

    private void resetButton(String text, int color) {
        btnConnect.setEnabled(true);
        btnConnect.setText(text);
        btnConnect.setBackgroundColor(color);
        isConnected = false;
    }
}
