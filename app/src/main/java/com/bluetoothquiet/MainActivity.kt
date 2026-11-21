package com.bluetoothquiet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var switchEffect: MaterialSwitch
    private lateinit var seekExtra: SeekBar
    private lateinit var textStatus: TextView
    private lateinit var textValue: TextView

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchEffect = findViewById(R.id.switchEffect)
        seekExtra = findViewById(R.id.seekExtra)
        textStatus = findViewById(R.id.textStatus)
        textValue = findViewById(R.id.textValue)

        switchEffect.isChecked = Prefs.isEnabled(this)
        seekExtra.progress = Prefs.extraPercent(this)
        updateValueLabel()

        switchEffect.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            if (checked) {
                startEffect()
            } else {
                val stop = Intent(this, VolumeService::class.java).apply {
                    action = VolumeService.ACTION_STOP
                }
                ContextCompat.startForegroundService(this, stop)
            }
        }

        seekExtra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.setExtraPercent(this@MainActivity, progress)
                updateValueLabel()
                if (Prefs.isEnabled(this@MainActivity)) applyVolume()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        switchEffect.isChecked = Prefs.isEnabled(this)
        if (Prefs.isEnabled(this)) {
            startEffect()
        }
        updateStatus()
    }

    private fun updateValueLabel() {
        textValue.text = getString(R.string.volume_value, Prefs.extraPercent(this))
    }

    private fun updateStatus() {
        val connected = hasBluetoothOutput()
        val enabled = Prefs.isEnabled(this)
        textStatus.text = when {
            enabled && connected -> getString(R.string.status_active)
            enabled -> getString(R.string.status_waiting)
            else -> getString(R.string.status_off)
        }
    }

    private fun hasBluetoothOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun startEffect() {
        val intent = Intent(this, VolumeService::class.java).apply {
            action = VolumeService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun applyVolume() {
        val intent = Intent(this, VolumeService::class.java).apply {
            action = VolumeService.ACTION_APPLY
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNeededPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }
}
