package com.v2ray.ang.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class ServerSelector(private val context: Context) {
    private val gson = Gson()
    private val httpClient = OkHttpClient()

    // Configuration
    private val GIST_URL = "https://gist.githubusercontent.com/your-gist-url/raw"
    private val SECRET_SALT = "your-secret-salt-here"
    private val DEVICE_ID_KEY = "device_id"

    suspend fun selectBestServer(): String? = withContext(Dispatchers.IO) {
        try {
            // Fetch encrypted configs from Gist
            val encryptedConfigs = fetchEncryptedConfigs()
            if (encryptedConfigs.isEmpty()) return@withContext null

            // Decrypt all configs
            val decryptedConfigs = mutableListOf<Pair<String, String>>() // config, serverAddress
            for (encryptedConfig in encryptedConfigs) {
                val decrypted = decryptConfig(encryptedConfig)
                if (decrypted != null) {
                    decryptedConfigs.add(Pair(decryptedConfig, extractServerAddress(decryptedConfig)))
                }
            }

            if (decryptedConfigs.isEmpty()) return@withContext null

            // Perform TCP ping on each server and select the one with lowest ping
            var bestConfig: String? = null
            var lowestPing = Long.MAX_VALUE

            for ((config, serverAddress) in decryptedConfigs) {
                val ping = tcpPing(serverAddress, 443)
                if (ping >= 0 && ping < lowestPing) {
                    lowestPing = ping
                    bestConfig = config
                }
            }

            bestConfig
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun fetchEncryptedConfigs(): List<String> {
        return try {
            val request = Request.Builder().url(GIST_URL).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return emptyList()
                val jsonArray = gson.fromJson(body, JsonArray::class.java)
                jsonArray.map { it.asString }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun decryptConfig(encryptedConfig: String): String? {
        return try {
            val deviceId = getDeviceId()
            val key = deriveKey(SECRET_SALT, deviceId)
            val iv = deriveIV(SECRET_SALT, deviceId)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, key.size, "AES"), IvParameterSpec(iv))

            val encryptedBytes = android.util.Base64.decode(encryptedConfig, android.util.Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun deriveKey(salt: String, deviceId: String): ByteArray {
        val input = salt + deviceId
        val messageDigest = MessageDigest.getInstance("SHA-256")
        return messageDigest.digest(input.toByteArray())
    }

    private fun deriveIV(salt: String, deviceId: String): ByteArray {
        val input = salt + deviceId + "iv"
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val hash = messageDigest.digest(input.toByteArray())
        return hash.copyOfRange(0, 16) // AES IV is 16 bytes
    }

    private fun extractServerAddress(config: String): String {
        return try {
            val jsonObject = gson.fromJson(config, JsonObject::class.java)
            jsonObject.get("server")?.asString ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun tcpPing(host: String, port: Int): Long {
        return try {
            val startTime = System.currentTimeMillis()
            val socket = Socket(host, port)
            socket.close()
            System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            -1 // Connection failed
        }
    }

    private fun getDeviceId(): String {
        val sharedPref = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        var deviceId = sharedPref.getString(DEVICE_ID_KEY, null)
        if (deviceId == null) {
            deviceId = android.os.Build.ID + android.os.Build.FINGERPRINT
            sharedPref.edit().putString(DEVICE_ID_KEY, deviceId).apply()
        }
        return deviceId
    }
}
