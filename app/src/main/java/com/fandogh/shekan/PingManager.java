package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PingManager {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PingCallback {
        void onResult(long latencyMs);
        void onError(String error);
    }

    public void checkTcpPing(String host, int port, PingCallback callback) {
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                long endTime = System.currentTimeMillis();
                long latency = endTime - startTime;
                mainHandler.post(() -> callback.onResult(latency));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Timeout"));
            }
        });
    }
}
