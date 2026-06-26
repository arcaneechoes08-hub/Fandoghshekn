package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PingManager {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PingCallback {
        void onResult(long latencyMs);
        void onError(String error);
    }

    public void checkTcpPing(String host, int port, PingCallback callback) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            mainHandler.post(() -> callback.onError("آدرس سرور نامعتبر است"));
            return;
        }
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                long latency = System.currentTimeMillis() - start;
                mainHandler.post(() -> callback.onResult(latency));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Timeout"));
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
