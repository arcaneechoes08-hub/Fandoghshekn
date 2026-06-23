package com.fandogh.shekan;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private EditText configInput;
    private Button connectButton;
    private Button disconnectButton;
    private TextView statusText;
    private SharedPreferences sharedPreferences;
    private static final String PREF_NAME = "FandoghPrefs";
    private static final String CONFIG_KEY = "vpn_config";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize views
        configInput = findViewById(R.id.configInput);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        statusText = findViewById(R.id.statusText);
        
        // Initialize SharedPreferences for secure storage
        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        
        // Set up button listeners
        connectButton.setOnClickListener(v -> connectVPN());
        disconnectButton.setOnClickListener(v -> disconnectVPN());
    }
    
    private void connectVPN() {
        String config = configInput.getText().toString().trim();
        
        if (config.isEmpty()) {
            Toast.makeText(this, "لطفاً کانفیگ را وارد کنید", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Store config securely (in real app, use encrypted storage)
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(CONFIG_KEY, config);
        editor.apply();
        
        // Start VPN Service
        Intent intent = new Intent(this, FandoghVpnService.class);
        intent.putExtra("config", config);
        startService(intent);
        
        statusText.setText("اتصال به VPN...");
        statusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        Toast.makeText(this, "VPN در حال اتصال است", Toast.LENGTH_SHORT).show();
    }
    
    private void disconnectVPN() {
        Intent intent = new Intent(this, FandoghVpnService.class);
        stopService(intent);
        
        statusText.setText("قطع شده");
        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        Toast.makeText(this, "VPN قطع شد", Toast.LENGTH_SHORT).show();
    }
}
