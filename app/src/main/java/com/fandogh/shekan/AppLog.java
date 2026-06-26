package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AppLog {
    private static final int MAX_LOGS = 500;
    private static final List<String> logs = new ArrayList<>();
    private static LogListener listener;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat dateFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public interface LogListener {
        void onLogAdded(String newLog);
    }

    public static synchronized void add(String tag, String message) {
        String fullLog = "[" + dateFormat.format(new Date()) + "][" + tag + "] " + message;
        if (logs.size() >= MAX_LOGS) logs.remove(0);
        logs.add(fullLog);
        if (listener != null) mainHandler.post(() -> listener.onLogAdded(fullLog));
    }

    public static synchronized void setListener(LogListener logListener) {
        listener = logListener;
    }

    public static synchronized String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        for (String log : logs) sb.append(log).append("\n");
        return sb.toString();
    }

    public static synchronized void clear() { logs.clear(); }
}
