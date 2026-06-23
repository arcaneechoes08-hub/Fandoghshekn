package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PingManager {
    private static final String TAG = "PingManager";
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT_MS = 5000;

    public interface PingCallback {
        void onResult(long latencyMs);
        void onError(String error);
    }

    public void checkTcpPing(String host, int port, PingCallback callback) {
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            Socket socket = new Socket();

            try {
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                long latency = System.currentTimeMillis() - startTime;
                mainHandler.post(() -> callback.onResult(latency));
                Log.d(TAG, "TCP Ping to " + host + ":" + port + " = " + latency + "ms");
            } catch (IOException e) {
                Log.e(TAG, "Ping failed: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Socket close error: " + e.getMessage());
                }
            }
        });
    }

    public void checkDnsResolution(String host, PingCallback callback) {
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                java.net.InetAddress.getByName(host);
                long latency = System.currentTimeMillis() - startTime;
                mainHandler.post(() -> callback.onResult(latency));
                Log.d(TAG, "DNS Resolution for " + host + " = " + latency + "ms");
            } catch (IOException e) {
                Log.e(TAG, "DNS Resolution failed: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
