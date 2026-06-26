package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DeviceStatus(
    val batteryLevel:   Int,
    val isCharging:     Boolean,
    val networkType:    String,
    val wifiSsid:       String,
    val signalStrength: Int,
    val publicIp:       String,
    val isScreenOn:     Boolean,
)

class DeviceStatusHelper(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @SuppressLint("HardwareIds", "MissingPermission")
    suspend fun collect(): DeviceStatus = withContext(Dispatchers.IO) {
        // ── Battery ───────────────────────────────────────────
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging    = bm.isCharging

        // ── Network ───────────────────────────────────────────
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val networkType = when {
            caps == null                                                     -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)            -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)        -> "mobile"
            else                                                             -> "unknown"
        }

        // ── Wi-Fi SSID ────────────────────────────────────────
        val wm  = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiSsid = if (networkType == "wifi") {
            wm.connectionInfo.ssid.removePrefix("\"").removeSuffix("\"")
        } else ""

        // ── Signal strength (approximate) ─────────────────────
        val signalStrength = if (networkType == "wifi") {
            WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5)
        } else -1

        // ── Public IP ─────────────────────────────────────────
        val publicIp = try {
            val req = Request.Builder().url("https://api.ipify.org").build()
            httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
        } catch (e: Exception) { "" }

        // ── Screen on? ────────────────────────────────────────
        val wm2 = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.state == android.view.Display.STATE_ON
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        }

        DeviceStatus(batteryLevel, isCharging, networkType, wifiSsid, signalStrength, publicIp, isScreenOn)
    }
}
