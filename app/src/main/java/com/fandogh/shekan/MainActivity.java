package com.fandogh.shekan;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    private String v2rayConfig = "";
    private ConfigManager configManager;
    
    // المان‌های مربوط به بخش مانیتورینگ و لاگ موقت
    private TextView txtLogs;
    private ScrollView logScrollView;

    // راه‌انداز مدرن برای جایگزینی onActivityResult (جهت دریافت مجوز VPN)
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        configManager = new ConfigManager(this);
        
        txtLogs = findViewById(R.id.txtLogs);
        logScrollView = findViewById(R.id.logScrollView);

        // ثبت راه‌انداز (Launcher) برای دریافت نتیجه مجوز از سیستم‌عامل
        registerVpnPermissionLauncher();

        // بازیابی لاگ‌های قبلی حافظه و تنظیم لیسنر زنده
        if (txtLogs != null) {
            txtLogs.setText(AppLog.getAllLogs());
        }
        
        AppLog.setListener(new AppLog.LogListener() {
            @Override
            public void onLogAdded(String newLog) {
                if (txtLogs != null && logScrollView != null) {
                    runOnUiThread(() -> {
                        txtLogs.append(newLog + "\n");
                        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                    });
                }
            }
        });

        AppLog.add("MainActivity", "رابط کاربری برنامه فندق‌شکن لود شد.");

        btnConnect.setOnClickListener(v -> {
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

    /**
     * ثبت و مقداردهی مدیریت مجوز سیستم برای ساخت تونل
     */
    private void registerVpnPermissionLauncher() {
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        AppLog.add("MainActivity", "تایید مجوز VpnService توسط کاربر انجام شد.");
                        startVpnServiceForeground();
                    } else {
                        // رفع باگ: بازگرداندن وضعیت دکمه در صورت رد شدن مجوز
                        AppLog.add("MainActivity", "خطا: کاربر مجوز ساخت تونل VPN را رد کرد.");
                        btnConnect.setText("اتصال به فندق‌شکن");
                        btnConnect.setEnabled(true);
                    }
                });
    }

    private void startFandoghVpn() {
        AppLog.add("MainActivity", "در حال بررسی و آماده‌سازی سیستم عامل برای مجوز تونل VPN...");
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            AppLog.add("MainActivity", "درخواست مجوز سیستم عامل برای ساخت تونل نمایش داده شد.");
            // استفاده از لانچر مدرن به جای startActivityForResult
            vpnPermissionLauncher.launch(intent);
        } else {
            AppLog.add("MainActivity", "مجوز سیستم عامل از قبل صادر شده است. عبور مستقیم به اجرای سرویس.");
            startVpnServiceForeground();
        }
    }

    /**
     * استارت کردن سرویس پس از اطمینان از وجود مجوز
     */
    private void startVpnServiceForeground() {
        try {
            Intent vpnIntent = new Intent(this, FandoghVpnService.class);
            vpnIntent.setAction("START");
            vpnIntent.putExtra("VLESS_LINK", v2rayConfig);
            
            AppLog.add("MainActivity", "در حال استارت سرویس پیش‌زمینه (Foreground Service)...");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent);
            } else {
                startService(vpnIntent);
            }

            isConnected = true;
            btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
            Toast.makeText(this, "فندق‌شکن فعال شد!", Toast.LENGTH_SHORT).show();
            AppLog.add("MainActivity", "سرویس فندق‌شکن با موفقیت در سیستم ثبت و فعال گردید.");
            
        } catch (Exception e) {
            // هندل کردن ارورهای محدودیت سرویس پس‌زمینه در اندروید ۱۲ به بالا
            AppLog.add("MainActivity", "خطای شدید در متد استارت سرویس: " + e.getMessage());
            Toast.makeText(this, "خطا در استارت سرویس فندق‌شکن", Toast.LENGTH_LONG).show();
            
            // ریست کردن وضعیت دکمه
            btnConnect.setText("اتصال به فندق‌شکن");
            btnConnect.setEnabled(true);
            isConnected = false;
        }
    }

    private void stopFandoghVpn() {
        AppLog.add("MainActivity", "شروع فرآیند توقف سرویس فندق‌شکن...");
        try {
            Intent vpnIntent = new Intent(this, FandoghVpnService.class);
            vpnIntent.setAction("STOP");
            // رفع باگ کرش اندروید 8+: برای متوقف کردن سرویس فقط از startService استفاده می‌کنیم
            startService(vpnIntent);
            AppLog.add("MainActivity", "سیگنال توقف (STOP) به سرویس ارسال شد.");
        } catch (Exception e) {
            AppLog.add("MainActivity", "خطا در متد توقف سرویس: " + e.getMessage());
        }
        
        isConnected = false;
        btnConnect.setText("اتصال به فندق‌شکن");
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
        AppLog.add("MainActivity", "وضعیت اپلیکیشن به حالت قطع کامل تغییر یافت.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // رفع مشکل نشت حافظه (Memory Leak)
        AppLog.setListener(null); 
    }
    }
                           
