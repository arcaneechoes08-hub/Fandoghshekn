package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {

    // این دو مقدار را در قدم بعدی با نانو ویرایش می‌کنی:
    private static final String GIST_RAW_URL = "https://gist.githubusercontent.com/arcaneechoes08-hub/97ed58c7ee1f162a7a0ae8608f08b25c/raw/70d01d72a367542d276e385b6fb6d914e504875a/%25D9%2584";
    private static final String SECRET_KEY = "FandoghSecretKey"; 

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public void fetchAndDecryptConfig(ConfigCallback callback) {
        executor.execute(() -> {
            try {
                if (GIST_RAW_URL.contains("لینک_RAW_گیت‌هاب")) {
                    throw new Exception("تنظیمات ست نشده است");
                }
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

                    String decryptedConfig = decryptAES(response.toString().trim(), SECRET_KEY);
                    mainHandler.post(() -> callback.onSuccess(decryptedConfig));
                } else {
                    mainHandler.post(() -> callback.onError("کد سرور: " + responseCode));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("ارور توابع: " + e.getMessage()));
            }
        });
    }

    private String decryptAES(String encryptedText, String key) throws Exception {
        byte[] encryptedBytes = Base64.decode(encryptedText, Base64.DEFAULT);
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
}
