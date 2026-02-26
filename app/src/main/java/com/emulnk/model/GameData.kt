package com.emulnk.model

data class GameData(
    val isConnected: Boolean = false,
    val values: Map<String, Any> = emptyMap(),
    val raw: Map<String, Any> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
    val system: SystemInfo = SystemInfo()
)

data class SystemInfo(
    val safeArea: SafeArea = SafeArea(),
    val display: DisplayInfo = DisplayInfo(),
    val battery: BatteryInfo = BatteryInfo(),
    val thermal: ThermalInfo = ThermalInfo()
)

data class BatteryInfo(
    val level: Int = 0,
    val isCharging: Boolean = false
)

data class ThermalInfo(
    val cpuTemp: Float = 0f,
    val isThrottling: Boolean = false
)

data class SafeArea(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
)

data class DisplayInfo(
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0, // 0, 90, 180, 270
    val isDualScreen: Boolean = false
)
