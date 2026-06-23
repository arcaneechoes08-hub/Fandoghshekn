package com.fandogh.shekan;

import java.net.URI;
import java.net.URLDecoder;

public class ConfigManager {

    public interface ConfigCallback {
        void onSuccess(String decryptedConfig);
        void onError(String error);
    }

    public void fetchAndDecryptConfig(ConfigCallback callback) {
        // نمونه پایه فرضی؛ این بخش را با متد فچینگ اندپوینت خودت سینک نگه دار
        String sampleConfig = "vless://example-server.com:443?encryption=none#فندق_🌰";
        String fixedHost = parseVlessLinkSecure(sampleConfig);
        
        if (fixedHost != null) {
            String jsonConfig = "{\"inbounds\":[{\"listen\":\"127.0.0.1\",\"port\":10808,\"protocol\":\"socks\",\"settings\":{\"auth\":\"noauth\",\"udp\":true}}],\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"" + fixedHost + "\",\"port\":443}]}}]}";
            callback.onSuccess(jsonConfig);
        } else {
            callback.onError("خطا در پردازش لینک کانفیگ");
        }
    }

    // 🎯 حل مشکل شماره ۷: ایزوله‌سازی تگ ریمارک (#) قبل از اعمال دیکودر برای ممانعت از خرابی متغیر Host
    public static String parseVlessLinkSecure(String vlessLink) {
        try {
            String cleanLink = vlessLink;
            if (vlessLink.contains("#")) {
                int hashIndex = vlessLink.indexOf("#");
                cleanLink = vlessLink.substring(0, hashIndex);
                // رمزگشایی ایمن نام کانفیگ جدا از URL اصلی
                URLDecoder.decode(vlessLink.substring(hashIndex + 1), "UTF-8");
            }
            URI uri = new URI(cleanLink);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
