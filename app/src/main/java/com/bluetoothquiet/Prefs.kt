package com.bluetoothquiet

import android.content.Context

object Prefs {

    private const val NAME = "bluetoothquiet"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXTRA_PERCENT = "extra_percent"
    private const val KEY_WAS_RUNNING = "was_running"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun extraPercent(context: Context): Int = prefs(context).getInt(KEY_EXTRA_PERCENT, 60)

    fun setExtraPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_EXTRA_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    fun wasRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_WAS_RUNNING, false)

    fun setWasRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAS_RUNNING, running).apply()
    }
}
