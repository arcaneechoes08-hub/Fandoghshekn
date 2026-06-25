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
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghPrefs";
    private static final String KEY_CONFIG = "encrypted_config";
    private static final String SECRET_KEY = "FandoghSecretKey";
    private static final String GIST_RAW_URL = "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";

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
                } catch (Exception ex) {
                    // ignore
                }
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private String fetchFromGist() throws Exception {
        URL url = new URL(GIST_RAW_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString().trim();
        } else {
            throw new Exception("کد سرور: " + responseCode);
        }
    }

    private String getCachedEncrypted() {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CONFIG, null);
    }

    private String decrypt(String encryptedText) throws Exception {
        if (encryptedText == null || encryptedText.isEmpty()) {
            throw new Exception("متن رمزگذاری شده خالی است");
        }
        byte[] combined = Base64.decode(encryptedText, Base64.DEFAULT);
        if (combined.length < 12) {
            throw new Exception("دیتا نامعتبر یا خیلی کوتاه است");
        }

        // تفکیک مقدار IV تصادفی (۱۲ بایت اول)
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, 12);

        // تفکیک سایفرتکست اصلی
        int cipherTextLength = combined.length - 12;
        byte[] cipherText = new byte[cipherTextLength];
        System.arraycopy(combined, 12, cipherText, 0, cipherTextLength);

        // رمزگشایی پیشرفته لایه امنیتی GCM
        SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        byte[] decryptedBytes = cipher.doFinal(cipherText);
        return new String(decryptedBytes, "UTF-8");
    }
        }
                        
