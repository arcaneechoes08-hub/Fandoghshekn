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

<<<<<<< HEAD
    // لینکی که مستقیماً به متن خام (Raw) در Gist اشاره می‌کند
=======
    // ۱. لینک Raw گیت‌هاب خودت را اینجا بگذار
>>>>>>> f995db2 (Fix AES key length to exact 16 bytes)
    private static final String GIST_RAW_URL = "https://gist.github.com/arcaneechoes08-hub/97ed58c7ee1f162a7a0ae8608f08b25c.js";
    
    // ۲. کلید اختصاصی تو (حتماً باید ۱۶ کاراکتر/بایت باشد)
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
                URL url = new URL(GIST_RAW_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // رمزگشایی پیشرفته AES
                    String decryptedConfig = decryptAES(response.toString().trim(), SECRET_KEY);

                    mainHandler.post(() -> callback.onSuccess(decryptedConfig));
                } else {
                    mainHandler.post(() -> callback.onError("خطا در ارتباط: " + responseCode));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("خطا در رمزگشایی یا شبکه: " + e.getMessage()));
            }
        });
    }

    private String decryptAES(String encryptedText, String key) throws Exception {
        // ابتدا متن بیس۶۴ را به بایت تبدیل میکنیم
        byte[] encryptedBytes = Base64.decode(encryptedText, Base64.DEFAULT);
        
        // تنظیم الگوریتم AES
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        // رمزگشایی نهایی
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
}
