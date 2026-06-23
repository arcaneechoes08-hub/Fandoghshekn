package com.fandogh.shekan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private boolean isConnected = false;
    private String v2rayConfig = "";

    // 🎯 آدرس گیت‌هاب گیست خودت را اینجا جایگزین کن (حتماً لینک Raw باشد)
    private static final String GIST_URL = "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                btnConnect.setEnabled(false);
                btnConnect.setText("در حال آماده‌سازی...");
                
                // دریافت کانفیگ در پس‌زمینه بدون درگیر کردن UI اصلی
                fetchConfigAndConnect();
            } else {
                stopFandoghVpn();
            }
        });
    }

    private void fetchConfigAndConnect() {
        new Thread(() -> {
            try {
                URL url = new URL(GIST_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line).append("\n");
                    }
                    reader.close();

                    v2rayConfig = result.toString().trim();

                    // برگشت به ترد اصلی برای استارت زدن وی‌پی‌ان
                    runOnUiThread(() -> {
                        btnConnect.setEnabled(true);
                        if (!v2rayConfig.isEmpty()) {
                            startFandoghVpn();
                        } else {
                            btnConnect.setText("اتصال به فندق‌شکن");
                            Toast.makeText(MainActivity.this, "❌ کانفیگ دریافت شده خالی بود!", Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    throw new Exception("خطای سرور: " + connection.getResponseCode());
                }
                connection.disconnect();

            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnConnect.setEnabled(true);
                    btnConnect.setText("اتصال به فندق‌شکن");
                    Toast.makeText(MainActivity.this, "❌ خطا در اتصال به سرور پشتیبان!", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startFandoghVpn() {
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
                Intent vpnIntent = new Intent(this, FandoghVpnService.class);
                vpnIntent.setAction("com.fandogh.shekan.START");
                vpnIntent.putExtra("COMMAND_CONFIG", v2rayConfig);
                startService(vpnIntent);

                isConnected = true;
                btnConnect.setText("متصل شد 🌰 (قطع اتصال)");
                Toast.makeText(this, "فندق‌شکن فعال شد!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "خطا در استارت سرویس فندق", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopFandoghVpn() {
        Intent vpnIntent = new Intent(this, FandoghVpnService.class);
        vpnIntent.setAction("com.fandogh.shekan.STOP");
        startService(vpnIntent);

        isConnected = false;
        btnConnect.setText("اتصال به فندق‌شکن");
        Toast.makeText(this, "فندق‌شکن متوقف شد.", Toast.LENGTH_SHORT).show();
    }
}
