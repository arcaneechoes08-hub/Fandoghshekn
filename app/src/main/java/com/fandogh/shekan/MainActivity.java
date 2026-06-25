package com.fandogh.shekan;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    private boolean isConnected = false;
    private Button btnConnect;
    private ConfigManager configManager;
    private PingManager pingManager;
    private String mDecryptedConfig;

    // هش امضای نهایی شما (SHA-256)
    private static final String EXPECTED_SIGNATURE = "YOUR_REAL_SIGNATURE_HASH_HERE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!checkAppSignature()) {
            Toast.makeText(this, "خطای امنیتی: برنامه دستکاری شده است!", Toast.LENGTH_LONG).show();
            finishAffinity();
            System.exit(0);
            return;
        }

        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btnConnect);
        configManager = new ConfigManager(getApplicationContext());
        pingManager = new PingManager();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startFetchingConfig();
            } else {
                stopFandoghShekan();
            }
        });
    }

    private boolean checkAppSignature() {
        try {
            if ("YOUR_REAL_SIGNATURE_HASH_HERE".equals(EXPECTED_SIGNATURE)) {
                return true; 
            }
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] signatures = packageInfo.signingInfo.getApkContentsSigners();
                for (Signature signature : signatures) {
                    if (verifyHash(signature)) return true;
                }
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                for (Signature signature : packageInfo.signatures) {
                    if (verifyHash(signature)) return true;
                }
            }
        } catch (Exception e) {
            Log.e("Security", "خطا در ارزیابی امضا سیستم", e);
        }
        return false;
    }

    private boolean verifyHash(Signature signature) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(signature.toByteArray());
        String currentSignature = Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        return currentSignature.equals(EXPECTED_SIGNATURE);
    }

    private void startFetchingConfig() {
        btnConnect.setText("در حال دریافت کانفیگ...");
        btnConnect.setBackgroundColor(0xFF2196F3);
        btnConnect.setEnabled(false);

        configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
            @Override
            public void onSuccess(String decryptedConfig) {
                btnConnect.setText("در حال تست پینگ سرور...");
                parseAndPing(decryptedConfig);
            }

            @Override
            public void onError(String error) {
                resetButton(error, 0xFFF44336);
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

            this.mDecryptedConfig = config;
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
            intent.putExtra("VLESS_LINK", mDecryptedConfig);
            
            // 🚨 حل مشکل کرش اندروید ۸ به بالا برای استارت سرویس 🚨
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            
            btnConnect.setEnabled(true);
            btnConnect.setText("فندق‌شکن فعال است 🛡️");
            btnConnect.setBackgroundColor(0xFF4CAF50);
            isConnected = true;
        } else {
            resetButton("عدم تایید مجوز VPN", 0xFFF44336);
        }
    }

    private void stopFandoghShekan() {
        Intent intent = new Intent(this, FandoghVpnService.class);
        intent.setAction("STOP");
        startService(intent); // برای استاپ استثنائا همین startService صحیح است
        resetButton("اتصال هوشمند", 0xFFFF9800);
    }

    private void resetButton(String text, int color) {
        btnConnect.setEnabled(true);
        btnConnect.setText(text);
        btnConnect.setBackgroundColor(color);
        isConnected = false;
    }
    }
                        
