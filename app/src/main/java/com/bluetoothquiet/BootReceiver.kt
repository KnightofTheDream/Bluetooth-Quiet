package com.bluetoothquiet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.wasRunning(context)) {
            val service = Intent(context, VolumeService::class.java).apply {
                action = VolumeService.ACTION_START
            }
            ContextCompat.startForegroundService(context, service)
        }
    }
}
