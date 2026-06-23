package com.fandogh.shekan;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "FandoghPrefs";
    private static final String KEY_CONFIG = "encrypted_xray_config";

    public static String getDecryptedConfig(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null!");
            return getDefaultXrayJson();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedConfig = prefs.getString(KEY_CONFIG, null);

        if (savedConfig == null || savedConfig.isEmpty()) {
            Log.w(TAG, "⚠️ کانفیگی یافت نشد؛ قالب استاندارد تست ایکس‌ری صادر شد.");
            return getDefaultXrayJson();
        }

        return decrypt(savedConfig);
    }

    private static String decrypt(String encryptedData) {
        return encryptedData; 
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
                "        \"address\": \"127.0.0.1\",\n" +
                "        \"port\": 443,\n" +
                "        \"users\": [{ \"id\": \"00000000-0000-0000-0000-000000000000\", \"encryption\": \"none\" }]\n" +
                "      }]\n" +
                "    }\n" +
                "  }]\n" +
                "}";
    }
}
