package com.fandogh.shekan;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConfigManager {
    private static final String GIST_URL = "https://gist.githubusercontent.com/YOUR_USERNAME/YOUR_GIST_ID/raw";
    private static final String ENCRYPTION_KEY = "FandoghSecretKey"; // 16 bytes key
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public interface ConfigsListCallback {
        void onSuccess(List<ConfigModel> configs);
        void onError(String error);
    }

    // Fetch single config and decrypt it
    public void fetchAndDecryptConfig(ConfigCallback callback) {
        executor.execute(() -> {
            try {
                String encryptedData = fetchFromGist();
                String decrypted = decrypt(encryptedData);
                mainHandler.post(() -> callback.onSuccess(decrypted));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // Fetch and decrypt multiple configs
    public void fetchAndDecryptConfigs(ConfigsListCallback callback) {
        executor.execute(() -> {
            try {
                JSONArray configsJson = fetchConfigsFromGist();
                List<ConfigModel> configs = new ArrayList<>();

                for (int i = 0; i < configsJson.length(); i++) {
                    JSONObject obj = configsJson.getJSONObject(i);
                    int id = obj.getInt("id");
                    String name = obj.getString("name");
                    String encryptedData = obj.getString("data");

                    ConfigModel config = new ConfigModel(id, name, encryptedData);
                    try {
                        String decrypted = decrypt(encryptedData);
                        config.setDecryptedData(decrypted);
                    } catch (Exception e) {
                        config.setStatus("failed");
                    }
                    configs.add(config);
                }

                mainHandler.post(() -> callback.onSuccess(configs));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private String fetchFromGist() throws Exception {
        URL url = new URL(GIST_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("خطا در دریافت از Gist: " + responseCode);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        return response.toString();
    }

    private JSONArray fetchConfigsFromGist() throws Exception {
        String jsonContent = fetchFromGist();
        JSONObject root = new JSONObject(jsonContent);
        return root.getJSONArray("configs");
    }

    public String decrypt(String encryptedText) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(ENCRYPTION_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);

        byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    public String encrypt(String plainText) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(ENCRYPTION_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
