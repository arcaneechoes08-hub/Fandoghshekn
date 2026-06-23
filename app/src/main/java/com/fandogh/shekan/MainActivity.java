package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.v2ray.ang.R;
// وارد کردن مدیریت سرویس‌های V2Ray
import com.v2ray.ang.utils.V2rayConfigUtil;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    
    // ⚠️ رفیق، بعداً کانفیگِ تست خودت (Vless یا Vmess) رو می‌ذاریم اینجا
    private String v2rayConfig = "vless://your_test_config_here";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startVandoghVpn();
            } else {
                stopFandoghVpn();
            }
        });
    }

    private void startVandoghVpn() {
        // ۱. ابتدا از کاربر اجازه دسترسی به VPN سیستم‌عامل را می‌گیریم
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
                // ۲. استارت زدن سرویس V2Ray با کانفیگ فندق‌شکن
                Intent v2rayIntent = new Intent(this, com.v2ray.ang.service.V2rayVPNService.class);
                v2rayIntent.setAction("com.v2ray.ang.action.start");
                // ارسال کانفیگ به سرویس پس‌زمینه
                v2rayIntent.putExtra("COMMAND_CONFIG", v2rayConfig);
                startService(v2rayIntent);

                isConnected = true;
                btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
                Toast.makeText(this, "فندق‌شکن آزاد شد!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "خطا در استارت موتور V2Ray", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopFandoghVpn() {
        // ۳. متوقف کردن سرویس و آزاد کردن ترافیک گوشی
        Intent v2rayIntent = new Intent(this, com.v2ray.ang.service.V2rayVPNService.class);
        v2rayIntent.setAction("com.v2ray.ang.action.stop");
        startService(v2rayIntent);

        isConnected = false;
        btnConnect.setText("اتصال به فندق‌شکن");
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
    }
}
