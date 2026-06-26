package com.remotemonitor.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.remotemonitor.app.services.MonitorService
import com.remotemonitor.app.data.SessionManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Received boot/update intent: ${intent.action}")
            
            // Only start if we have a registered device token
            val sessionManager = SessionManager(context)
            if (sessionManager.deviceToken != null && sessionManager.serverUrl != null) {
                val serviceIntent = Intent(context, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_START_BACKGROUND
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("BootReceiver", "Started MonitorService in background")
            } else {
                Log.i("BootReceiver", "Not starting MonitorService: not registered")
            }
        }
    }
}
