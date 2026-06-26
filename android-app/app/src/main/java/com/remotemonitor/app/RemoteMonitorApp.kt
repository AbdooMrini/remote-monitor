package com.remotemonitor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.remotemonitor.app.data.SessionManager

/**
 * Application class — initialises global singletons.
 */
class RemoteMonitorApp : Application() {

    companion object {
        const val CHANNEL_MONITOR = "channel_monitor"
        const val CHANNEL_ALERTS  = "channel_alerts"

        lateinit var instance: RemoteMonitorApp
            private set
    }

    lateinit var sessionManager: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance       = this
        sessionManager = SessionManager(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    "Monitor Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active while remote monitoring is running"
                    setShowBadge(false)
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Important monitoring alerts"
                }
            )
        }
    }
}
