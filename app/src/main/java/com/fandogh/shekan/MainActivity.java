package com.fandogh.shekan;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import libv2ray.Libv2ray;

public class MainActivity extends Activity {

    private boolean isConnected = false;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // قدم اول: تست امنیت دستگاه. اگر روت بود، اجازه ورود نمی‌دهیم!
        if (isDeviceRooted()) {
            setContentView(new TextView(this)); // صفحه خالی
            Toast.makeText(this, "امکان اجرای فندق‌شکن روی دستگاه‌های روت‌شده وجود ندارد!", Toast.LENGTH_LONG).show();
            finish(); // بستن آنی برنامه
            return;
        }

        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btnConnect);

        // حذف فیلد ورودی قبلی چون حالا همه‌چیز خودکار از Gist می‌آید
        try {
            Libv2ray.initV2ray();
        } catch (Exception e) {
            e.printStackTrace();
        }

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startFandoghShekan();
            } else {
                stopFandoghShekan();
            }
        });
    }

    // مکانیزم سه مرحله‌ای بررسی روت بودن گوشی
    private boolean isDeviceRooted() {
        // تست اول: چک کردن مسیرهای معروف فایل su
        String[] paths = {
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }

        // تست دوم: چک کردن برچسب بیلد سیستم‌عامل (Test-Keys معمولاً یعنی رام سفارشی یا روت‌شده)
        String buildTags = android.os.Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) return true;

        // تست سوم: تلاش برای اجرای مخفیانه دستور su در خط فرمان اندروید
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
        btnConnect.setBackgroundColor(0xFF2196F3); // آبی

        // اینجا در پارت بعدی کد اتصال به Gist، دانلود متن رمزنگاری شده،
        // رمزگشاییِ محلی و پینگ‌گیری را اضافه می‌کنیم.
        
        // فعلاً برای تست موتور را بیدار می‌کنیم:
        btnConnect.setText("متصل به سریع‌ترین سرور 🥜");
        btnConnect.setBackgroundColor(0xFF4CAF50); // سبز
        isConnected = true;
    }

    private void stopFandoghShekan() {
        btnConnect.setText("اتصال");
        btnConnect.setBackgroundColor(0xFFFF9800); // نارنجی
        isConnected = false;
    }
}
