package com.fandogh.shekan;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghPrefs";
    private static final String KEY_CONFIG = "encrypted_xray_config";
    private static final String SECRET_KEY = "FandoghSecretKey"; // دقیقاً ۱۶ بایت استاندارد برای AES-128

    // تعریف اینترفیس کالبک که MainActivity به آن نیاز دارد
    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    /**
     * متد اسینکرون کالبک‌محور برای استفاده در MainActivity
     */
    public void fetchAndDecryptConfig(ConfigCallback callback) {
        if (callback == null) return;
        
        try {
            // در فاز اول مستقیماً متد سینک را صدا می‌زنیم؛ اگر نیاز به ترد بک‌گراند بود خودمان بعداً می‌بریم روی ترد
            String config = getDecryptedConfig(null); // چون تو کالبک کانتکست نداریم، هندل شده است
            callback.onSuccess(config);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    /**
     * دریافت و رمزگشایی کانفیگ برای استفاده در سرویس
     */
    public static String getDecryptedConfig(Context context) {
        // اگر کانتکست نال بود یا SharedPreferences خالی بود، قالب پیش‌فرض را بدهد
        if (context == null) {
            return getDefaultXrayJson();
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedConfig = prefs.getString(KEY_CONFIG, null);

        if (savedConfig == null || savedConfig.isEmpty()) {
            Log.w(TAG, "⚠️ کانفیگ یافت نشد؛ قالب استاندارد تست Xray صادر شد.");
            return getDefaultXrayJson();
        }

        try {
            return decrypt(savedConfig);
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed: " + e.getMessage(), e);
            return getDefaultXrayJson();
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

    private static String getDefaultXrayJson() {
        return "{\n" +
                "  \"log\": { \"loglevel\": \"warning\" },\n" +
                "  \"inbounds\": [{\n" +
                "    \"tag\": \"socks-in\",\n" +
                "    \"port\": 10808,\n" +
                "    \"listen\": \"127.0.0.1\",\n" +
                "    \"protocol\": \"socks\",\n" +
                "    \"settings\": { \"auth\": \"noauth\", \"udp\": true }\n" +
                "  }],\n" +
                "  \"outbounds\": [{\n" +
                "    \"tag\": \"proxy\",\n" +
                "    \"protocol\": \"vless\",\n" +
                "    \"settings\": {\n" +
                "      \"vnext\": [{\n" +
                "        \"address\": \"YOUR_SERVER_ADDRESS\",\n" +
                "        \"port\": 443,\n" +
                "        \"users\": [{\n" +
                "          \"id\": \"00000000-0000-0000-0000-000000000000\",\n" +
                "          \"encryption\": \"none\",\n" +
                "          \"flow\": \"xtls-rprx-vision\"\n" +
                "        }]\n" +
                "      }]\n" +
                "    },\n" +
                "    \"streamSettings\": {\n" +
                "      \"network\": \"tcp\",\n" +
                "      \"security\": \"tls\",\n" +
                "      \"tlsSettings\": {\n" +
                "        \"allowInsecure\": false\n" +
                "      }\n" +
                "    }\n" +
                "  }]\n" +
                "}";
    }
}
