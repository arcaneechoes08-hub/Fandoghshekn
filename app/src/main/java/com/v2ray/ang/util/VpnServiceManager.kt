package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.util.ServerSelector
import libv2ray.Libv2ray

class VpnServiceManager(private val context: Context) {
    private var isConnected = false
    private val serverSelector = ServerSelector(context)

    suspend fun startVPN() {
        try {
            // Fetch and select the best server
            val config = serverSelector.selectBestServer()
            if (config != null) {
                // Import config into V2Ray
                Libv2ray.startV2Ray()
                isConnected = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isConnected = false
        }
    }

    suspend fun stopVPN() {
        try {
            Libv2ray.stopV2Ray()
            isConnected = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getVPNStatus(): String {
        return if (isConnected) "connected" else "disconnected"
    }
}
