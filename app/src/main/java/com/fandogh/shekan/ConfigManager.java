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

    private static final String SECRET_KEY = "FandoghSecretKey"; 

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public void fetchAndDecryptConfig(ConfigCallback callback) {
        executor.execute(() -> {
            // استفاده از لینک مستقیم گیت‌هک که بدون فیلتر دیتا را رد می‌کند
            String[] urlsToTest = {
                "https://gl.githack.com/gist/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt",
                "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt"
            };

            String rawData = "";
            for (String baseUrl : urlsToTest) {
                try {
                    String freshUrl = baseUrl + "?nocache=" + System.currentTimeMillis();
                    URL url = new URL(freshUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setUseCaches(false);

                    if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        rawData = response.toString().trim();
                        if (!rawData.isEmpty()) {
                            break; 
                        }
                    }
                } catch (Exception e) {
                    // تست لینک بعدی در صورت خطا
                }
            }

            if (rawData == null || rawData.isEmpty()) {
                mainHandler.post(() -> callback.onError("خطا در شبکه یا اینترنت گوشی"));
                return;
            }

            try {
                String decryptedConfig = decryptAES(rawData, SECRET_KEY);
                mainHandler.post(() -> callback.onSuccess(decryptedConfig));
            } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException ae) {
                mainHandler.post(() -> callback.onError("خطا در رمزگشایی: دیتای جیست با کلید همخوانی ندارد"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("خطا در پردازش نهایی دیتای شبکه: " + e.getMessage()));
            }
        });
    }

    private String decryptAES(String encryptedText, String key) throws Exception {
        // حذف هرگونه کاراکتر مخفی، اسپیس، تب یا اینتر از ابتدا، انتها و میان متن دانلود شده
        String cleanText = encryptedText.replaceAll("[\\n\\r\\s\\t]+", "").trim();
        
        // استفاده از NO_WRAP برای نادیده گرفتن شکستگی‌های خط در بیس۶۴
        byte[] encryptedBytes = Base64.decode(cleanText, Base64.NO_WRAP);
        
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
}
