package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*

/**
 * Wraps FusedLocationProviderClient with a clean callback interface.
 * Supports configurable intervals, priorities, and single-shot requests.
 */
class LocationHelper(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private var currentIntervalMs: Long = 10_000L
    private var currentPriority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY

    @SuppressLint("MissingPermission")
    fun startUpdates(
        intervalMs: Long = 10_000L,
        priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        onLocation: (lat: Double, lng: Double, acc: Float, alt: Double, speed: Float, provider: String) -> Unit,
    ) {
        currentIntervalMs = intervalMs
        currentPriority = priority

        val request = LocationRequest.Builder(priority, intervalMs)
            .setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        // Remove any existing callback before starting new updates
        callback?.let { fusedClient.removeLocationUpdates(it) }

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

    /**
     * Restart location updates with a new interval and priority.
     * Used for adaptive location frequency (e.g., high-frequency mode).
     */
    @SuppressLint("MissingPermission")
    fun restartWithInterval(
        newIntervalMs: Long,
        priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        onLocation: (lat: Double, lng: Double, acc: Float, alt: Double, speed: Float, provider: String) -> Unit,
    ) {
        stopUpdates()
        startUpdates(
            intervalMs = newIntervalMs,
            priority = priority,
            onLocation = onLocation
        )
    }

    fun stopUpdates() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }
}