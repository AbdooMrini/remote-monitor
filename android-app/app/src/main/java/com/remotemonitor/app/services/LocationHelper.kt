package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*

/**
 * Wraps FusedLocationProviderClient with a clean callback interface.
 */
class LocationHelper(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun startUpdates(
        intervalMs: Long = 10_000L,
        onLocation: (lat: Double, lng: Double, acc: Float, alt: Double, speed: Float, provider: String) -> Unit,
    ) {
        // Use balanced power accuracy so it works indoors via Wi-Fi/Cell towers
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    onLocation(
                        loc.latitude,
                        loc.longitude,
                        loc.accuracy,
                        loc.altitude,
                        loc.speed,
                        loc.provider ?: "fused"
                    )
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Location permission revoked
            callback = null
        }
    }

    fun stopUpdates() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }
}
