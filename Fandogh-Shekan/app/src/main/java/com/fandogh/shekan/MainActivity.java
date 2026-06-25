package com.fandogh.shekan;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    [span_45](start_span)private boolean isConnected = false;[span_45](end_span)
    [span_46](start_span)private Button btnConnect;[span_46](end_span)
    [span_47](start_span)private ConfigManager configManager;[span_47](end_span)
    [span_48](start_span)private PingManager pingManager;[span_48](end_span)
    [span_49](start_span)private String mDecryptedConfig;[span_49](end_span)

    // هش امضای نهایی شما (SHA-256) پس از خروجی Sign شده رسمی اینجا قرار می‌گیرد.
    // تا زمانی که مقدار زیر پیش‌فرض باشد، چک کردن امضا در حالت توسعه غیرفعال است تا بتوانید تست کنید.
    private static final String EXPECTED_SIGNATURE = "YOUR_REAL_SIGNATURE_HASH_HERE";

    @Override
    [span_50](start_span)protected void onCreate(Bundle savedInstanceState) {[span_50](end_span)
        [span_51](start_span)super.onCreate(savedInstanceState);[span_51](end_span)

        // بررسی یکپارچگی برنامه و جلوگیری از بازنشر کلون‌های غیررسمی
        if (!checkAppSignature()) {
            Toast.makeText(this, "خطای امنیتی: برنامه دستکاری شده است!", Toast.LENGTH_LONG).show();
            finishAffinity();
            System.exit(0);
            return;
        }

        [span_52](start_span)setContentView(R.layout.activity_main);[span_52](end_span)

        [span_53](start_span)btnConnect = findViewById(R.id.btnConnect);[span_53](end_span)
        configManager = new ConfigManager(getApplicationContext());
        [span_54](start_span)pingManager = new PingManager();[span_54](end_span)

        [span_55](start_span)btnConnect.setOnClickListener(v -> {[span_55](end_span)
            [span_56](start_span)if (!isConnected) {[span_56](end_span)
                [span_57](start_span)startFetchingConfig();[span_57](end_span)
            [span_58](start_span)} else {[span_58](end_span)
                [span_59](start_span)stopFandoghShekan();[span_59](end_span)
            }
        });
    }

    private boolean checkAppSignature() {
        try {
            if ("YOUR_REAL_SIGNATURE_HASH_HERE".equals(EXPECTED_SIGNATURE)) {
                return true; // نادیده گرفتن موقت برای اجرای تست در محیط اندروید استودیو
            }
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] signatures = packageInfo.signingInfo.getApkContentsSigners();
                for (Signature signature : signatures) {
                    if (verifyHash(signature)) return true;
                }
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                for (Signature signature : packageInfo.signatures) {
                    if (verifyHash(signature)) return true;
                }
            }
        } catch (Exception e) {
            Log.e("Security", "خطا در ارزیابی امضا سیستم", e);
        }
        return false;
    }

    private boolean verifyHash(Signature signature) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(signature.toByteArray());
        String currentSignature = Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        return currentSignature.equals(EXPECTED_SIGNATURE);
    }

    [span_60](start_span)private void startFetchingConfig() {[span_60](end_span)
        [span_61](start_span)btnConnect.setText("در حال دریافت کانفیگ...");[span_61](end_span)
        [span_62](start_span)btnConnect.setBackgroundColor(0xFF2196F3);[span_62](end_span)
        [span_63](start_span)btnConnect.setEnabled(false);[span_63](end_span)

        [span_64](start_span)configManager.fetchAndDecryptConfig(new ConfigManager.ConfigCallback() {[span_64](end_span)
            [span_65](start_span)@Override[span_65](end_span)
            [span_66](start_span)public void onSuccess(String decryptedConfig) {[span_66](end_span)
                [span_67](start_span)btnConnect.setText("در حال تست پینگ سرور...");[span_67](end_span)
                [span_68](start_span)parseAndPing(decryptedConfig);[span_68](end_span)
            [span_69](start_span)}

            @Override[span_69](end_span)
            [span_70](start_span)public void onError(String error) {[span_70](end_span)
                [span_71](start_span)resetButton(error, 0xFFF44336);[span_71](end_span)
            [span_72](start_span)}
        });
    }[span_72](end_span)

    [span_73](start_span)private void parseAndPing(String config) {[span_73](end_span)
        [span_74](start_span)try {[span_74](end_span)
            [span_75](start_span)if (config == null) throw new Exception("کانفیگ خالی است");[span_75](end_span)
            [span_76](start_span)config = config.trim();[span_76](end_span)

            [span_77](start_span)if (!config.startsWith("vless://")) {[span_77](end_span)
                [span_78](start_span)throw new Exception("لینک vless معتبر نیست");[span_78](end_span)
            [span_79](start_span)}

            this.mDecryptedConfig = config;[span_79](end_span)
            [span_80](start_span)String uriBody = config.substring(8);[span_80](end_span)
            [span_81](start_span)int atIndex = uriBody.lastIndexOf("@");[span_81](end_span)
            [span_82](start_span)if (atIndex == -1) throw new Exception("علامت @ پیدا نشد");[span_82](end_span)
            [span_83](start_span)String serverPart = uriBody.substring(atIndex + 1);[span_83](end_span)
            [span_84](start_span)String[] mainParts = serverPart.split("[?#]");[span_84](end_span)
            [span_85](start_span)String hostAndPort = mainParts[0];[span_85](end_span)

            [span_86](start_span)int colonIndex = hostAndPort.lastIndexOf(":");[span_86](end_span)
            [span_87](start_span)if (colonIndex == -1) throw new Exception("پورت سرور پیدا نشد");[span_87](end_span)

            [span_88](start_span)String host = hostAndPort.substring(0, colonIndex).trim();[span_88](end_span)
            [span_89](start_span)String portStr = hostAndPort.substring(colonIndex + 1).trim();[span_89](end_span)
            [span_90](start_span)int port = Integer.parseInt(portStr);[span_90](end_span)

            [span_91](start_span)pingManager.checkTcpPing(host, port, new PingManager.PingCallback() {[span_91](end_span)
                [span_92](start_span)@Override[span_92](end_span)
                [span_93](start_span)public void onResult(long latencyMs) {[span_93](end_span)
                    [span_94](start_span)Toast.makeText(MainActivity.this, "پینگ سرور: " + latencyMs + "ms", Toast.LENGTH_SHORT).show();[span_94](end_span)
                    [span_95](start_span)Intent intent = VpnService.prepare(MainActivity.this);[span_95](end_span)
                    [span_96](start_span)if (intent != null) {[span_96](end_span)
                        [span_97](start_span)startActivityForResult(intent, 0);[span_97](end_span)
                    [span_98](start_span)} else {[span_98](end_span)
                        [span_99](start_span)onActivityResult(0, RESULT_OK, null);[span_99](end_span)
                    [span_100](start_span)}
                }[span_100](end_span)

                [span_101](start_span)@Override[span_101](end_span)
                [span_102](start_span)public void onError(String error) {[span_102](end_span)
                    [span_103](start_span)resetButton("سرور قطع است (Timeout)", 0xFFE91E63);[span_103](end_span)
                [span_104](start_span)}
            });
        } catch (Exception e) {[span_104](end_span)
            [span_105](start_span)String preview = "";[span_105](end_span)
            [span_106](start_span)if (config != null) {[span_106](end_span)
                [span_107](start_span)int end = Math.min(config.length(), 25);[span_107](end_span)
                [span_108](start_span)preview = " -> [" + config.substring(0, end) + "]";[span_108](end_span)
            [span_109](start_span)}
            resetButton("خطا: " + e.getMessage() + preview, 0xFFF44336);[span_109](end_span)
        [span_110](start_span)}
    }[span_110](end_span)

    [span_111](start_span)@Override[span_111](end_span)
    [span_112](start_span)protected void onActivityResult(int requestCode, int resultCode, Intent data) {[span_112](end_span)
        [span_113](start_span)super.onActivityResult(requestCode, resultCode, data);[span_113](end_span)
        [span_114](start_span)if (resultCode == RESULT_OK) {[span_114](end_span)
            [span_115](start_span)Intent intent = new Intent(this, FandoghVpnService.class);[span_115](end_span)
            [span_116](start_span)intent.putExtra("VLESS_LINK", mDecryptedConfig);[span_116](end_span)
            [span_117](start_span)startService(intent);[span_117](end_span)
            
            [span_118](start_span)btnConnect.setEnabled(true);[span_118](end_span)
            [span_119](start_span)btnConnect.setText("فندق‌شکن فعال است 🛡️");[span_119](end_span)
            [span_120](start_span)btnConnect.setBackgroundColor(0xFF4CAF50);[span_120](end_span)
            [span_121](start_span)isConnected = true;[span_121](end_span)
        [span_122](start_span)} else {[span_122](end_span)
            [span_123](start_span)resetButton("عدم تایید مجوز VPN", 0xFFF44336);[span_123](end_span)
        [span_124](start_span)}
    }[span_124](end_span)

    [span_125](start_span)private void stopFandoghShekan() {[span_125](end_span)
        [span_126](start_span)Intent intent = new Intent(this, FandoghVpnService.class);[span_126](end_span)
        [span_127](start_span)intent.setAction("STOP");[span_127](end_span)
        [span_128](start_span)startService(intent);[span_128](end_span)
        [span_129](start_span)resetButton("اتصال هوشمند", 0xFFFF9800);[span_129](end_span)
    [span_130](start_span)}

    private void resetButton(String text, int color) {[span_130](end_span)
        [span_131](start_span)btnConnect.setEnabled(true);[span_131](end_span)
        [span_132](start_span)btnConnect.setText(text);[span_132](end_span)
        [span_133](start_span)btnConnect.setBackgroundColor(color);[span_133](end_span)
        [span_134](start_span)isConnected = false;[span_134](end_span)
    [span_135](start_span)}[span_135](end_span)
        }
                                                                       
