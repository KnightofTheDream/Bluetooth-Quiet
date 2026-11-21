package com.bluetoothquiet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class VolumeService : Service() {

    companion object {
        const val ACTION_START = "com.bluetoothquiet.action.START"
        const val ACTION_STOP = "com.bluetoothquiet.action.STOP"
        const val ACTION_APPLY = "com.bluetoothquiet.action.APPLY"

        const val DB_PER_PERCENT = 0.3f
        const val CHANNEL_ID = "volume_service"
        const val NOTIFICATION_ID = 1

        const val TAG = "VolumeService"
    }

    private var a2dpProxy: BluetoothProfile? = null
    private var proxyConnected = false
    private var enhancer: LoudnessEnhancer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val applyLoop = object : Runnable {
        override fun run() {
            tryApply("loop")
            handler.postDelayed(this, 1500L)
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                tryApply("volume_changed")
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            tryApply("devices_added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            tryApply("devices_removed")
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy
                proxyConnected = true
                tryApply("a2dp_proxy")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                proxyConnected = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundInternal()

        bluetoothAdapter()?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)

        ContextCompat.registerReceiver(
            this,
            volumeReceiver,
            IntentFilter().apply { addAction("android.media.VOLUME_CHANGED_ACTION") },
            ContextCompat.RECEIVER_EXPORTED
        )

        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startForegroundInternal()
            stopEffect()
            Prefs.setEnabled(this, false)
            Prefs.setWasRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        Prefs.setWasRunning(this, true)
        startForegroundInternal()
        handler.removeCallbacks(applyLoop)
        handler.post(applyLoop)
        tryApply("intent")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(applyLoop)
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        a2dpProxy?.let { bluetoothAdapter()?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dpProxy = null
        proxyConnected = false
        enhancer?.release()
        enhancer = null
        super.onDestroy()
    }

    private fun hasBluetoothConnect(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        if (!hasBluetoothConnect()) return null
        return (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private fun connectedA2dpDevice(): BluetoothDevice? {
        if (!hasBluetoothConnect() || !proxyConnected) return null
        return try {
            a2dpProxy?.connectedDevices?.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun tryApply(reason: String) {
        if (!Prefs.isEnabled(this) || !hasBluetoothConnect()) return

        val device = connectedA2dpDevice()
        updateNotification(device != null)
        if (device == null) {
            setAttenuationDb(0f)
            return
        }

        val percent = Prefs.extraPercent(this)
        enforceStreamTarget(audible = percent > 0)
        val db = -(100 - percent) * DB_PER_PERCENT
        setAttenuationDb(db)
        Log.d(TAG, "apply ($reason): atenuación=%.1f dB".format(db))
    }

    private fun enforceStreamTarget(audible: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val target = if (audible) maxOf(min, 1) else min
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun setAttenuationDb(db: Float) {
        val e = enhancer ?: try {
            LoudnessEnhancer(0)
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer no disponible: ${t.message}")
            null
        } ?: return
        enhancer = e
        try {
            e.setTargetGain((db * 100f).roundToInt())
        } catch (t: Throwable) {
            Log.w(TAG, "setTarget falló: ${t.message}")
        }
    }

    private fun stopEffect() {
        setAttenuationDb(0f)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, min + (max - min) / 4, 0)
        } catch (_: SecurityException) {
        }
    }

    private fun startForegroundInternal() {
        val channelManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channelManager.createNotificationChannel(channel)
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(connectedA2dpDevice() != null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun updateNotification(hasDevice: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(hasDevice))
    }

    private fun buildNotification(hasDevice: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VolumeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val status = if (hasDevice) {
            getString(R.string.notif_active)
        } else {
            getString(R.string.notif_waiting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
