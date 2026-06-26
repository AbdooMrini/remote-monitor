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
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            
            // Get last known location immediately
            if (isNetworkEnabled) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { loc ->
                    locationListener?.onLocationChanged(loc)
                }
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs,
                    0f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
            
            if (isGpsEnabled) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                    locationListener?.onLocationChanged(loc)
                }
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    0f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            locationListener = null
        } catch (e: Exception) {
            locationListener = null
        }
    }

    fun stopUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
    }
}
