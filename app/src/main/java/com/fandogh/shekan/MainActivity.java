package com.fandogh.shekan;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

public class MainActivity extends Activity {
    private boolean isConnected = false;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            isConnected = !isConnected;
            btnConnect.setText(isConnected ? "متصل" : "اتصال هوشمند");
        });
    }
}
