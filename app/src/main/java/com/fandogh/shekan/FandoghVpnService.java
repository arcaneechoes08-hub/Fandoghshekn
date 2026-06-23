// ... (کل محتویات قبلی را با این نسخه جایگزین کن) ...
// (فقط بخش json داخل متد generateXrayConfigManual را به این صورت تغییر می‌دهیم)

        String json = "{\n" +
                "  \"log\": {\"loglevel\": \"warning\"},\n" +
                "  \"dns\": {\"servers\": [\"8.8.8.8\", \"1.1.1.1\"]},\n" +
                "  \"inbounds\": [\n" +
                "    {\"port\": 10808, \"protocol\": \"socks\", \"settings\": {\"auth\": \"noauth\", \"udp\": true}}\n" +
                "  ],\n" +
                "  \"outbounds\": [{\n" +
                "    \"protocol\": \"vless\",\n" +
                "    \"settings\": {\"vnext\": [{\"address\": \"" + host + "\", \"port\": " + port + ", \"users\": [" + userSettings + "]}]},\n" +
                "    \"streamSettings\": " + streamStr.toString() + "\n" +
                "  }]\n" +
                "}";
// ... (بقیه کدهای فایل را مثل قبل نگه دار) ...
