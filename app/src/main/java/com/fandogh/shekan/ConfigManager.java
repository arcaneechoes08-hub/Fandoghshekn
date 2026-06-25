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
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghSecurePrefs";
    private static final String KEY_CONFIG = "secure_config";
    
    private static final String GIST_RAW_URL = "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";
    private static final String SECRET_KEY = "FandoghSecretKey"; // ۱۶ بایت دقیق

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
                
                if (context != null && encryptedConfig != null) {
                    SharedPreferences prefs = getSecurePreferences();
                    prefs.edit().putString(KEY_CONFIG, encryptedConfig).apply();
                }
                mainHandler.post(() -> callback.onSuccess(decrypted));
            } catch (Exception e) {
                Log.w(TAG, "خطا در آنلاین، بازیابی از کش: " + e.getMessage());
                try {
                    String cached = getCachedEncrypted();
                    if (cached != null && !cached.isEmpty()) {
                        String decrypted = decrypt(cached);
                        mainHandler.post(() -> callback.onSuccess(decrypted));
                        return;
                    }
                } catch (Exception ignored) {}
                
                String errorMsg = e.getMessage();
                mainHandler.post(() -> callback.onError(errorMsg != null ? errorMsg : "خطای ناشناخته"));
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
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("کد خطای گیت‌هاب: " + responseCode);
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                String result = response.toString().trim();
                if (result.isEmpty()) throw new Exception("فایل خالی است");
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String getCachedEncrypted() throws Exception {
        if (context == null) return null;
        SharedPreferences prefs = getSecurePreferences();
        return prefs.getString(KEY_CONFIG, null);
    }

    private SharedPreferences getSecurePreferences() throws Exception {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(
                PREF_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    private static String decrypt(String encryptedData) throws Exception {
        byte[] decoded = Base64.decode(encryptedData, Base64.DEFAULT);
        
        int ivSize = 12;
        if (decoded.length <= ivSize) throw new Exception("دیتا نامعتبر است");
        
        // استخراج ۱۲ بایت اول به عنوان IV
        byte[] iv = new byte[ivSize];
        System.arraycopy(decoded, 0, iv, 0, ivSize);
        
        // استخراج مابقی داده‌ها به عنوان متن رمزنگاری شده
        int cipherTextSize = decoded.length - ivSize;
        byte[] cipherText = new byte[cipherTextSize];
        System.arraycopy(decoded, ivSize, cipherText, 0, cipherTextSize);

        SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
        byte[] decryptedBytes = cipher.doFinal(cipherText);
        return new String(decryptedBytes, "UTF-8");
    }
}
