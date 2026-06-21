package com.fandogh.shekan;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import com.fandogh.shekan.R;

public class MainActivity extends Activity {

    private boolean isConnected = false;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                startFandoghShekan();
            } else {
                stopFandoghShekan();
            }
        });
    }

    private void startFandoghShekan() {
        btnConnect.setText("در حال دریافت کانفیگ‌های امن...");
        btnConnect.setBackgroundColor(0xFF2196F3); 
        btnConnect.setText("متصل به سریع‌ترین سرور 🥜");
        btnConnect.setBackgroundColor(0xFF4CAF50); 
        isConnected = true;
    }

    private void stopFandoghShekan() {
        btnConnect.setText("اتصال هوشمند");
        btnConnect.setBackgroundColor(0xFFFF9800); 
        isConnected = false;
    }
}
