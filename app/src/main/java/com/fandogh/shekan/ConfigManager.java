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

    // استفاده از پروکسی CDN رایگان و بدون فیلتر jsDelivr برای دور زدن فیلترینگ گیت‌هاب در ایران
    private static final String BYPASS_URL = "https://cdn.jsdelivr.net/gh/arcaneechoes08-hub/Fandoghshekn@master/app/src/main/java/com/fandogh/shekan/ConfigManager.java"; 
    // اما چون دیتای شما روی جیست است، از یک لینک میرور (Mirror) مستقیم استفاده می‌کنیم:
    private static final String GIST_RAW_URL = "https://io.fastgit.org/gist/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";
    
    // اگر میرور بالا هم کند بود، مستقیم از خود این دامنه استفاده می‌کنیم که فیلتر نیست:
    private static final String ALTERNATIVE_URL = "https://raw.fastgit.org/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt";

    private static final String SECRET_KEY = "FandoghSecretKey"; 

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public void fetchAndDecryptConfig(ConfigCallback callback) {
        executor.execute(() -> {
            // لیست لینک‌های جایگزین برای تست نوبتی در صورت فیلتر بودن
            String[] urlsToTest = {
                "https://gl.githack.com/gist/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt",
                "https://gist.githubusercontent.com/arcaneechoes08-hub/360f898ab276cb083f0901cbabd4aa6a/raw/configs.txt"
            };

            String rawData = "";
            int responseCode = -1;

            for (String baseUrl : urlsToTest) {
                try {
                    String freshUrl = baseUrl + "?nocache=" + System.currentTimeMillis();
                    URL url = new URL(freshUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Cache-Control", "no-cache");

                    responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        rawData = response.toString().trim();
                        if (!rawData.isEmpty()) {
                            break; // دیتا با موفقیت دانلود شد، از حلقه خارج شو
                        }
                    }
                } catch (Exception e) {
                    // اگر این لینک خطا داد، برو سراغ لینک بعدی
                }
            }

            try {
                if (rawData != null && !rawData.isEmpty()) {
                    String decryptedConfig = decryptAES(rawData, SECRET_KEY);
                    mainHandler.post(() -> callback.onSuccess(decryptedConfig));
                } else {
                    mainHandler.post(() -> callback.onError("خطا در شبکه: سرورهای کمکی پاسخ ندادند."));
                }
            } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException ae) {
                mainHandler.post(() -> callback.onError("خطا در رمزگشایی: کلید نامعتبر یا دیتای جیست اشتباه است"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("خطا در پردازش نهایی دیتای شبکه"));
            }
        });
    }

    private String decryptAES(String encryptedText, String key) throws Exception {
        String cleanText = encryptedText.replaceAll("[\\n\\r\\s]+", "");
        byte[] encryptedBytes = Base64.decode(cleanText, Base64.DEFAULT);
        
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
}
