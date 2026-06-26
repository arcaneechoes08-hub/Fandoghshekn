package com.fandogh.shekan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghSecurePrefs";
    private static final String KEY_CONFIG = "secure_config";

    private static final String GIST_RAW_URL =
            "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";
    private static final String SECRET_KEY = "FandoghSecretKey";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context context;

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
                if (context != null) {
                    getSecurePreferences().edit().putString(KEY_CONFIG, encryptedConfig).apply();
                }
                mainHandler.post(() -> callback.onSuccess(decrypted));
            } catch (Exception e) {
                Log.w(TAG, "خطا در آنلاین، بازیابی از کش: " + e.getMessage());
                try {
                    String cached = getCachedEncrypted();
                    if (cached != null && !cached.isEmpty()) {
                        mainHandler.post(() -> {
                            try { callback.onSuccess(decrypt(cached)); }
                            catch (Exception ex) { callback.onError(ex.getMessage()); }
                        });
                        return;
                    }
                } catch (Exception ignored) {}
                String msg = e.getMessage();
                mainHandler.post(() -> callback.onError(msg != null ? msg : "خطای ناشناخته"));
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
        context = null;
    }

    private String fetchFromGist() throws Exception {
        URL url = new URL(GIST_RAW_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new Exception("کد خطای گیت‌هاب: " + connection.getResponseCode());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String result = sb.toString().trim();
                if (result.isEmpty()) throw new Exception("فایل کانفیگ خالی است");
                return result;
            }
        } finally { connection.disconnect(); }
    }

    private String getCachedEncrypted() throws Exception {
        if (context == null) return null;
        return getSecurePreferences().getString(KEY_CONFIG, null);
    }

    private SharedPreferences getSecurePreferences() throws Exception {
        String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(PREF_NAME, alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private static String decrypt(String encryptedData) throws Exception {
        if (encryptedData == null || encryptedData.isEmpty())
            throw new Exception("دیتای رمزنگاری‌شده خالی است");
        byte[] decoded = Base64.decode(encryptedData, Base64.DEFAULT);
        final int ivSize = 12;
        if (decoded.length <= ivSize + 16) throw new Exception("دیتا نامعتبر یا ناقص است");
        byte[] iv = new byte[ivSize];
        System.arraycopy(decoded, 0, iv, 0, ivSize);
        byte[] cipherText = new byte[decoded.length - ivSize];
        System.arraycopy(decoded, ivSize, cipherText, 0, cipherText.length);
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), "UTF-8");
    }
    }
