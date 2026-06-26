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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.security.MessageDigest;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    private String v2rayConfig = "";
    private ConfigManager configManager;
    private PingManager pingManager;

    private TextView txtLogs;
    private ScrollView logScrollView;
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

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
        txtLogs = findViewById(R.id.txtLogs);
        logScrollView = findViewById(R.id.logScrollView);

        configManager = new ConfigManager(this);
        pingManager = new PingManager();

        registerVpnPermissionLauncher();

        if (txtLogs != null) txtLogs.setText(AppLog.getAllLogs());

        AppLog.setListener(newLog -> {
            if (txtLogs != null && logScrollView != null) {
                runOnUiThread(() -> {
                    txtLogs.append(newLog + "\n");
                    logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                });
            }
        });

        AppLog.add("MainActivity", "رابط کاربری برنامه فندق‌شکن لود شد.");

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) startFetchingConfig();
            else stopFandoghVpn();
        });
    }

    private void startFetchingConfig() {
        btnConnect.setEnabled(false);
        btnConnect.setText("در حال رمزگشایی و اتصال...");
        AppLog.add("MainActivity", "شروع فرآیند اتصال...");

        configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
            @Override
            public void onSuccess(String decryptedConfig) {
                if (decryptedConfig == null || !decryptedConfig.startsWith("vless://")) {
                    AppLog.add("MainActivity", "خطا: کانفیگ دریافتی نامعتبر است.");
                    runOnUiThread(() -> {
                        btnConnect.setEnabled(true);
                        btnConnect.setText("اتصال هوشمند");
                        Toast.makeText(MainActivity.this,
                            "❌ کانفیگ دریافتی نامعتبر است", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                v2rayConfig = decryptedConfig;
                AppLog.add("MainActivity", "کانفیگ با موفقیت رمزگشایی شد.");
                runOnUiThread(() -> btnConnect.setText("در حال تست پینگ..."));
                parseAndPing(decryptedConfig);
            }

            @Override
            public void onError(String error) {
                AppLog.add("MainActivity", "خطا: " + error);
                runOnUiThread(() -> {
                    btnConnect.setEnabled(true);
                    btnConnect.setText("اتصال هوشمند");
                    Toast.makeText(MainActivity.this, "❌ " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void parseAndPing(String config) {
        if (config == null || !config.startsWith("vless://")) {
            AppLog.add("MainActivity", "خطا در بررسی پینگ: کانفیگ نامعتبر");
            runOnUiThread(this::startFandoghVpn);
            return;
        }
        try {
            String uriBody = config.substring(8);
            int atIndex = uriBody.lastIndexOf("@");
            if (atIndex < 0) throw new Exception("فرمت لینک نامعتبر");
            String serverPart = uriBody.substring(atIndex + 1);
            String hostAndPort = serverPart.split("[?#]")[0];
            int colonIndex = hostAndPort.lastIndexOf(":");
            if (colonIndex < 0) throw new Exception("پورت یافت نشد");
            String host = hostAndPort.substring(0, colonIndex).trim();
            int port = Integer.parseInt(hostAndPort.substring(colonIndex + 1).trim());

            pingManager.checkTcpPing(host, port, new PingManager.PingCallback() {
                @Override
                public void onResult(long latencyMs) {
                    AppLog.add("MainActivity", "پینگ سرور: " + latencyMs + "ms");
                    runOnUiThread(() -> startFandoghVpn());
                }
                @Override
                public void onError(String error) {
                    AppLog.add("MainActivity", "سرور قطع است (Timeout)");
                    runOnUiThread(() -> {
                        btnConnect.setEnabled(true);
                        btnConnect.setText("سرور قطع است");
                    });
                }
            });
        } catch (Exception e) {
            AppLog.add("MainActivity", "خطا در بررسی پینگ، اتصال مستقیم...");
            runOnUiThread(this::startFandoghVpn);
        }
    }

    private void registerVpnPermissionLauncher() {
        vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    AppLog.add("MainActivity", "مجوز VpnService تایید شد.");
                    startVpnServiceForeground();
                } else {
                    AppLog.add("MainActivity", "کاربر مجوز VPN را رد کرد.");
                    btnConnect.setText("اتصال هوشمند");
                    btnConnect.setEnabled(true);
                }
            });
    }

    private void startFandoghVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            AppLog.add("MainActivity", "درخواست مجوز سیستم عامل...");
            vpnPermissionLauncher.launch(intent);
        } else {
            startVpnServiceForeground();
        }
    }

    private void startVpnServiceForeground() {
        try {
            Intent vpnIntent = new Intent(this, FandoghVpnService.class);
            vpnIntent.setAction("START");
            vpnIntent.putExtra("VLESS_LINK", v2rayConfig);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(vpnIntent);
            else
                startService(vpnIntent);

            isConnected = true;
            btnConnect.setEnabled(true);
            btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
            btnConnect.setBackgroundColor(0xFF4CAF50);
            AppLog.add("MainActivity", "سرویس فندق‌شکن فعال شد.");
        } catch (Exception e) {
            AppLog.add("MainActivity", "خطا در استارت سرویس: " + e.getMessage());
            btnConnect.setText("اتصال هوشمند");
            btnConnect.setEnabled(true);
            isConnected = false;
        }
    }

    private void stopFandoghVpn() {
        AppLog.add("MainActivity", "سیگنال توقف به سرویس ارسال شد.");
        Intent vpnIntent = new Intent(this, FandoghVpnService.class);
        vpnIntent.setAction("STOP");
        startService(vpnIntent);
        isConnected = false;
        btnConnect.setText("اتصال هوشمند");
        btnConnect.setBackgroundColor(0xFFFF9800);
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
    }

    private boolean checkAppSignature() {
        try {
            if ("YOUR_REAL_SIGNATURE_HASH_HERE".equals(EXPECTED_SIGNATURE)) return true;
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                for (Signature sig : packageInfo.signingInfo.getApkContentsSigners())
                    if (verifyHash(sig)) return true;
            } else {
                packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNATURES);
                for (Signature sig : packageInfo.signatures)
                    if (verifyHash(sig)) return true;
            }
        } catch (Exception e) {
            Log.e("Security", "خطا در ارزیابی امضا", e);
        }
        return false;
    }

    private boolean verifyHash(Signature signature) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(signature.toByteArray());
        String current = Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        return current.equals(EXPECTED_SIGNATURE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
        if (configManager != null) configManager.shutdown();
        if (pingManager != null) pingManager.shutdown();
    }
                }
