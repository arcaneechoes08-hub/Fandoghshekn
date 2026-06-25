package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    private String v2rayConfig = "";
    private ConfigManager configManager;
    
    // المان‌های مربوط به بخش مانیتورینگ و لاگ موقت
    private TextView txtLogs;
    private ScrollView logScrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        configManager = new ConfigManager();
        
        // مقداردهی کامپوننت‌های رابط کاربری مانیتورینگ
        txtLogs = findViewById(R.id.txtLogs);
        logScrollView = findViewById(R.id.logScrollView);

        // بازیابی لاگ‌های قبلی حافظه و تنظیم لیسنر زنده برای بروزرسانی خودکار اسکرول
        if (txtLogs != null) {
            txtLogs.setText(AppLog.getAllLogs());
        }
        
        AppLog.setListener(new AppLog.LogListener() {
            @Override
            public void onLogAdded(String newLog) {
                if (txtLogs != null && logScrollView != null) {
                    txtLogs.append(newLog + "\n");
                    // اسکرول هوشمند به انتهای خطوط لاگ به محض دریافت رویداد جدید
                    logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            }
        });

        // ثبت لاگ اولیه ورود کاربر به اپلیکیشن
        AppLog.add("MainActivity", "رابط کاربری برنامه فندق‌شکن لود شد.");

        btnConnect.setOnClickListener(v -> {
            // 🛑 دقیقا اولین اکشن پس از کلیک: ثبت آنی وضعیت فعلی فلگ اتصال
            AppLog.add("MainActivity", "دکمه اتصال فشرده شد. وضعیت فعلی اتصال: " + isConnected);

            if (!isConnected) {
                btnConnect.setEnabled(false);
                btnConnect.setText("در حال رمزگشایی و اتصال...");
                AppLog.add("MainActivity", "شروع فرآیند اتصال و درخواست کانفیگ از سرویس مدیریت...");

                configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {
                    @Override
                    public void onSuccess(String decryptedConfig) {
                        v2rayConfig = decryptedConfig;
                        AppLog.add("MainActivity", "موفقیت: کانفیگ دریافت و رمزگشایی شد.");
                        runOnUiThread(() -> {
                            btnConnect.setEnabled(true);
                            startFandoghVpn();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        AppLog.add("MainActivity", "خطا در فرآیند کانفیگ: " + error);
                        runOnUiThread(() -> {
                            btnConnect.setEnabled(true);
                            btnConnect.setText("اتصال به فندق‌شکن");
                            Toast.makeText(MainActivity.this, "❌ " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } else {
                AppLog.add("MainActivity", "درخواست قطع اتصال توسط کاربر صادر شد.");
                stopFandoghVpn();
            }
        });
    }

    private void startFandoghVpn() {
        AppLog.add("MainActivity", "در حال بررسی و آماده‌سازی سیستم عامل برای مجوز تونل VPN...");
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            AppLog.add("MainActivity", "درخواست مجوز سیستم عامل برای ساخت تونل نمایش داده شد.");
            startActivityForResult(intent, 512);
        } else {
            AppLog.add("MainActivity", "مجوز سیستم عامل از قبل صادر شده است. عبور مستقیم به اجرای سرویس.");
            onActivityResult(512, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 512) {
            if (resultCode == RESULT_OK) {
                AppLog.add("MainActivity", "تایید مجوز VpnService توسط کاربر انجام شد.");
                try {
                    Intent vpnIntent = new Intent(this, FandoghVpnService.class);
                    vpnIntent.setAction("START");
                    vpnIntent.putExtra("COMMAND_CONFIG", v2rayConfig);
                    
                    AppLog.add("MainActivity", "در حال استارت سرویس پیش‌زمینه (Foreground Service)...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(vpnIntent);
                    } else {
                        startService(vpnIntent);
                    }

                    isConnected = true;
                    btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
                    Toast.makeText(this, "فندق‌شکن فعال شد!", Toast.LENGTH_SHORT).show();
                    AppLog.add("MainActivity", "س
                        
