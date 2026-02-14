package com.emulnk.model

/**
 * Defines the Memory Map and Logic for a specific Game series/version.
 */
data class ProfileConfig(
    val id: String,
    val name: String,
    val platform: String,
    val dataPoints: List<DataPoint>,
    val macros: List<MacroConfig> = emptyList()
)

data class DataPoint(
    val id: String,
    val type: String,
    val size: Int,
    val formula: String? = null,
    val addresses: Map<String, String> = emptyMap(),
    val pointer: Map<String, String>? = null,
    val offset: String? = null
)

data class MacroConfig(
    val id: String,          // e.g., "full_heal"
    val steps: List<MacroStep>
)

data class MacroStep(
    val varId: String? = null,   // The variable to write to
    val value: String? = null,   // The value to write (Number or another varId)
    val delay: Long? = null      // Optional delay in ms before next step
)
