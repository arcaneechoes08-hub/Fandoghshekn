package com.v2ray.ang

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.util.VpnServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var vpnServiceManager: VpnServiceManager
    private var hasShownTelegramDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        vpnServiceManager = VpnServiceManager(this)

        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(JsInterface(), "Android")
    }

    private fun showTelegramSupportDialog() {
        if (hasShownTelegramDialog) return
        hasShownTelegramDialog = true

        AlertDialog.Builder(this)
            .setTitle("Support Us")
            .setMessage("Please join our Telegram channel to support the project and get updates.")
            .setPositiveButton("Join Channel") { _, _ ->
                openTelegramChannel()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun openTelegramChannel() {
        val telegramUrl = "https://t.me/fandoghsheknfree"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class JsInterface {
        @JavascriptInterface
        fun connectVPN() {
            showTelegramSupportDialog()
            CoroutineScope(Dispatchers.Main).launch {
                vpnServiceManager.startVPN()
            }
        }

        @JavascriptInterface
        fun disconnectVPN() {
            CoroutineScope(Dispatchers.Main).launch {
                vpnServiceManager.stopVPN()
            }
        }

        @JavascriptInterface
        fun getVPNStatus(): String {
            return vpnServiceManager.getVPNStatus()
        }
    }
}
