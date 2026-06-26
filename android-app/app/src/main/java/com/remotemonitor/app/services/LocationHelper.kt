package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class LocationHelper(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null

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

        try {
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                "fused" // LocationManager.FUSED_PROVIDER (API 31+)
            )

            var anyProviderStarted = false

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
                    // Ignore if a specific provider fails (e.g. SecurityException or IllegalArgumentException)
                }
            }

            if (!anyProviderStarted) {
                locationListener = null
            }
        } catch (e: Exception) {
            locationListener = null
        }
    }

    fun stopUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
    }
}
