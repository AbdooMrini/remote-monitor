package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocationHelper(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null
    private var fallbackJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @SuppressLint("MissingPermission")
    fun startUpdates(
        intervalMs: Long = 10_000L,
        onLocation: (lat: Double, lng: Double, acc: Float, alt: Double, speed: Float, provider: String) -> Unit,
    ) {
        stopUpdates()

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                onLocation(
                    loc.latitude,
                    loc.longitude,
                    loc.accuracy,
                    loc.altitude,
                    loc.speed,
                    loc.provider ?: "native"
                )
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        var anyProviderStarted = false
        try {
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                "fused" // LocationManager.FUSED_PROVIDER (API 31+)
            )

            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.getLastKnownLocation(provider)?.let { loc ->
                            locationListener?.onLocationChanged(loc)
                        }
                        locationManager.requestLocationUpdates(
                            provider,
                            intervalMs,
                            0f,
                            locationListener!!,
                            Looper.getMainLooper()
                        )
                        anyProviderStarted = true
                    }
                } catch (e: Exception) {
                    // Ignore per-provider failures
                }
            }
        } catch (e: Exception) {
            // Master try-catch
        }

        // ── Gmail-style IP Fallback ──────────────────────────────
        // If the Android system completely blocked GPS/Network location, 
        // fallback to an IP-based location API just like Gmail.
        if (!anyProviderStarted) {
            locationListener = null
            startIpFallback(intervalMs, onLocation)
        }
    }

    private fun startIpFallback(
        intervalMs: Long,
        onLocation: (lat: Double, lng: Double, acc: Float, alt: Double, speed: Float, provider: String) -> Unit
    ) {
        fallbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val req = Request.Builder().url("http://ip-api.com/json/").build()
                    val res = httpClient.newCall(req).execute().use { it.body?.string() }
                    if (res != null) {
                        val json = JSONObject(res)
                        if (json.optString("status") == "success") {
                            onLocation(
                                json.optDouble("lat"),
                                json.optDouble("lon"),
                                5000f, // Approx 5km accuracy
                                0.0,
                                0f,
                                "ip-api (fallback)"
                            )
                        }
                    }
                } catch (e: Exception) {
                    // IP location failed
                }
                delay(intervalMs)
            }
        }
    }

    fun stopUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
        fallbackJob?.cancel()
        fallbackJob = null
    }
}
