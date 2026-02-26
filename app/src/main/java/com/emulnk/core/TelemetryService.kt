package com.emulnk.core

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.emulnk.BuildConfig
import com.emulnk.model.BatteryInfo
import com.emulnk.model.ThermalInfo
import java.io.File

/**
 * Collects system telemetry like battery and thermals.
 */
class TelemetryService(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryService"
    }

    fun getBatteryInfo(): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batteryManager == null) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "BatteryManager service unavailable")
            }
            return BatteryInfo(level = 0, isCharging = false)
        }

        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryInfo(level = level, isCharging = isCharging)
    }

    fun getThermalInfo(): ThermalInfo {
        // HardwarePropertiesManager requires system-level permissions (DEVICE_POWER).
        // We rely on sysfs paths which are generally accessible on many Android devices
        // and specifically useful for handheld gaming devices.
        val cpuTemp = readCpuTempFromSysfs()
        return ThermalInfo(cpuTemp = cpuTemp, isThrottling = false)
    }

    private fun readCpuTempFromSysfs(): Float {
        val thermalPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone7/temp", // Common for some Qualcomm chips
            "/sys/class/thermal/thermal_zone10/temp",
            "/sys/class/thermal/thermal_zone11/temp",
            "/sys/class/thermal/thermal_zone12/temp"
        )
        
        for (path in thermalPaths) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val temp = file.readText().trim().toFloat()
                    // Some devices return millidegrees
                    return if (temp > TelemetryConstants.THERMAL_MILLIDEGREE_THRESHOLD) temp / TelemetryConstants.THERMAL_MILLIDEGREE_THRESHOLD else temp
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed to read thermal info from $path: ${e.message}")
                    }
                }
            }
        }
        return 0f
    }
}
