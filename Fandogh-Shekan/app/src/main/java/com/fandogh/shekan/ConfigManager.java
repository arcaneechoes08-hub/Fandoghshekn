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
    
    // لینک اختصاصی گیست پروژه شما
    [span_9](start_span)private static final String GIST_RAW_URL = "https://gist.githubusercontent.com/arcaneechoes08-hub/97ed58c7ee1f162a7a0ae8608f08b25c/raw/70d01d72a367542d276e385b6fb6d914e504875a/%25D9%2584";[span_9](end_span)
    
    // کلید رمزنگاری ۱۶ بایتی هماهنگ با سیستم جدید رمزگذاری GCM
    [span_10](start_span)private static final String SECRET_KEY = "FandoghSecretKey";[span_10](end_span)

    [span_11](start_span)private final ExecutorService executor = Executors.newSingleThreadExecutor();[span_11](end_span)
    [span_12](start_span)private final Handler mainHandler = new Handler(Looper.getMainLooper());[span_12](end_span)
    private Context context;

    [span_13](start_span)public ConfigManager() {}[span_13](end_span)

    public ConfigManager(Context context) {
        [span_14](start_span)this.context = context;[span_14](end_span)
    }

    [span_15](start_span)public interface ConfigCallback {[span_15](end_span)
        [span_16](start_span)void onSuccess(String decryptedConfig);[span_16](end_span)
        [span_17](start_span)void onError(String error);[span_17](end_span)
    }

    [span_18](start_span)public void fetchAndDecryptConfig(ConfigCallback callback) {[span_18](end_span)
        [span_19](start_span)if (callback == null) return;[span_19](end_span)

        [span_20](start_span)executor.execute(() -> {[span_20](end_span)
            try {
                String encryptedConfig = fetchFromGist();
                String decrypted = decrypt(encryptedConfig);
                
                if (context != null && encryptedConfig != null) {
                    SharedPreferences prefs = getSecurePreferences();
                    prefs.edit().putString(KEY_CONFIG, encryptedConfig).apply();
                }
                [span_21](start_span)mainHandler.post(() -> callback.onSuccess(decrypted));[span_21](end_span)
            } catch (Exception e) {
                Log.w(TAG, "خطا در دریافت دیتای آنلاین، تلاش برای بازیابی از کش امن سخت‌افزاری: " + e.getMessage());
                try {
                    String cached = getCachedEncrypted();
                    if (cached != null && !cached.isEmpty()) {
                        String decrypted = decrypt(cached);
                        mainHandler.post(() -> callback.onSuccess(decrypted));
                        return;
                    }
                } catch (Exception ignored) {}
                String errorMsg = e.getMessage();
                [span_22](start_span)mainHandler.post(() -> callback.onError(errorMsg != null ? errorMsg : "خطای ناشناخته"));[span_22](end_span)
            }
        });
    }

    [span_23](start_span)private String fetchFromGist() throws Exception {[span_23](end_span)
        [span_24](start_span)URL url = new URL(GIST_RAW_URL);[span_24](end_span)
        [span_25](start_span)HttpURLConnection connection = (HttpURLConnection) url.openConnection();[span_25](end_span)
        try {
            [span_26](start_span)connection.setRequestMethod("GET");[span_26](end_span)
            [span_27](start_span)connection.setConnectTimeout(10000);[span_27](end_span)
            [span_28](start_span)connection.setReadTimeout(10000);[span_28](end_span)
            [span_29](start_span)connection.setInstanceFollowRedirects(true);[span_29](end_span)
            
            [span_30](start_span)int responseCode = connection.getResponseCode();[span_30](end_span)
            [span_31](start_span)if (responseCode != HttpURLConnection.HTTP_OK) {[span_31](end_span)
                throw new Exception("پاسخ نامعتبر از سرور گیت‌هاب: " + responseCode);
            }
            
            [span_32](start_span)try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {[span_32](end_span)
                [span_33](start_span)StringBuilder response = new StringBuilder();[span_33](end_span)
                String line;
                [span_34](start_span)while ((line = reader.readLine()) != null) {[span_34](end_span)
                    [span_35](start_span)response.append(line);[span_35](end_span)
                }
                [span_36](start_span)String result = response.toString().trim();[span_36](end_span)
                [span_37](start_span)if (result.isEmpty()) {[span_37](end_span)
                    throw new Exception("فایل دریافتی از سرور کاملا خالی است");
                }
                return result;
            }
        } finally {
            [span_38](start_span)connection.disconnect();[span_38](end_span)
        }
    }

    private String getCachedEncrypted() throws Exception {
        [span_39](start_span)if (context == null) return null;[span_39](end_span)
        SharedPreferences prefs = getSecurePreferences();
        [span_40](start_span)return prefs.getString(KEY_CONFIG, null);[span_40](end_span)
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
        [span_41](start_span)byte[] decoded = Base64.decode(encryptedData, Base64.DEFAULT);[span_41](end_span)
        
        int ivSize = 12; // اندازه بردار تصادفی IV الگوریتم ایمن AES-GCM
        if (decoded.length <= ivSize) {
            throw new Exception("دیتای رمزنگاری شده کوتاه یا ساختار شکن است");
        }
        
        byte[] iv = new byte[ivSize];
        System.arraycopy(decoded, 0, iv, 0, ivSize);
        
        int cipherTextSize = decoded.length - ivSize;
        byte[] cipherText = new byte[cipherTextSize];
        System.arraycopy(decoded, ivSize, cipherText, 0, cipherTextSize);

        [span_42](start_span)SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes("UTF-8"), "AES");[span_42](end_span)
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
        byte[] decryptedBytes = cipher.doFinal(cipherText);
        [span_43](start_span)return new String(decryptedBytes, "UTF-8");[span_43](end_span)
    }
            }
              
