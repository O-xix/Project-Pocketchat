package com.pocketchat.app.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class ThrottleReason { BATTERY_LOW, THERMAL_HIGH }

/** [detail] is a short human-readable specific (e.g. "12%", "severe") for the status line. */
data class ThrottleStatus(val reason: ThrottleReason, val detail: String)

/**
 * Point-in-time battery/thermal check (NFR-011) — cheap enough to call
 * synchronously right before starting a generation. Thermal takes priority
 * over battery since it's the more urgent condition (can lead to a forced
 * shutdown); returns null when neither condition is present.
 */
object DeviceStressMonitor {
    private const val LOW_BATTERY_PCT = 15

    fun current(context: Context): ThrottleStatus? {
        thermalStatus(context)?.let { return it }
        return batteryStatus(context)
    }

    // PowerManager.getCurrentThermalStatus() is API 29+; below that, this
    // device class predates the API entirely, so thermal throttling is
    // simply not available there and this always returns null.
    private fun thermalStatus(context: Context): ThrottleStatus? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val label = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown imminent"
            else -> return null // NONE or LIGHT: not worth interrupting generation for
        }
        return ThrottleStatus(ThrottleReason.THERMAL_HIGH, label)
    }

    // registerReceiver(null, filter) is the standard trick for reading the
    // last-broadcast sticky ACTION_BATTERY_CHANGED intent synchronously,
    // without registering a live receiver that would need unregistering.
    private fun batteryStatus(context: Context): ThrottleStatus? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged) return null // charging: no need to conserve power

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val pct = level * 100 / scale
        if (pct > LOW_BATTERY_PCT) return null
        return ThrottleStatus(ThrottleReason.BATTERY_LOW, "$pct%")
    }
}
