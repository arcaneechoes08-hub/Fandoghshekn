package com.fandogh.shekan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghPrefs";
    private static final String KEY_CONFIG = "encrypted_config";
    private static final String SECRET_KEY = "FandoghSecretKey";
    private static final String GIST_RAW_URL =
            "https://gist.githubusercontent.com/arcaneechoes08-hub/" +
            "360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context context;

    public ConfigManager() {}

    public ConfigManager(Context context) {
        this.context = context;
    }

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public void fetchAndDecryptConfig(ConfigCallback callback) {
        if (callback == null) return;
        executor.execute(() -> {
            try {
                String encryptedConfig = fetchFromGist();
                String decrypted = decrypt(encryptedConfig);

                if (context != null && encryptedConfig != null) {
                    SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putString(KEY_CONFIG, encryptedConfig).apply();
                }

                mainHandler.post(() -> callback.onSuccess(decrypted));
            } catch (Exception e) {
                Log.w(TAG, "Gist fetch failed, trying cached config: " + e.getMessage());
                try {
                    String cached = getCachedEncrypted();
                    if (cached != null && !cached.isEmpty()) {
                        String decrypted = decrypt(cached);
                        mainHandler.post(() -> callback.onSuccess(decrypted));
                        return;
                    }
                } catch (Exception ignored) {}

                String errorMsg = e.getMessage();
                mainHandler.post(() -> callback.onError(errorMsg != null ? errorMsg : "Unknown error"));
            }
        });
    }

    private String fetchFromGist() throws Exception {
        URL url = new URL(GIST_RAW_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("Server returned: " + responseCode);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                String result = response.toString().trim();
                if (result.isEmpty()) {
                    throw new Exception("Empty response from server");
                }
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String getCachedEncrypted() {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CONFIG, null);
    }

    public static String getDecryptedConfig(Context ctx) {
        if (ctx == null) return null;
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_CONFIG, null);
        if (saved == null || saved.isEmpty()) return null;
        try {
            return decrypt(saved);
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed: " + e.getMessage());
            return null;
        }
    }

    private static String decrypt(String encryptedData) throws Exception {
        byte[] keyBytes = SECRET_KEY.getBytes("UTF-8");
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decoded = Base64.decode(encryptedData, Base64.DEFAULT);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted, "UTF-8");
    }
}
