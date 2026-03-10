package com.emulnk.core.model

/**
 * Defines the memory map and logic for a specific game series/version.
 */
data class ProfileConfig(
    val id: String,
    val name: String,
    val platform: String,
    // Nullable for Gson (bypasses Kotlin defaults); normalized to emptyList() by ConfigManager.parseProfile()
    val gameIds: List<String>? = emptyList(),
    val extends: String? = null,
    val bundles: Map<String, BundleConfig>? = null,
    val dataPoints: List<DataPoint>? = emptyList(),
    val macros: List<MacroConfig>? = emptyList()
)

data class DataPoint(
    val id: String,
    val type: String,
    val size: Int,
    val formula: String? = null,
    val stable: Boolean = false,

    // Bundle-based addressing (preferred for new profiles)
    val bundle: String? = null,
    val offset: String? = null,

    // Legacy addressing (backward compatible)
    val addresses: Map<String, String> = emptyMap(),

    // Pointer chain addressing (for dynamic data without bundles)
    val pointer: Map<String, String>? = null,
    val offsets: List<String>? = null
)

/**
 * Bundle configuration: a named group of memory addresses that share a common base.
 * Supports static bases (fixed address or regional map) and pointer-resolved bases.
 */
data class BundleConfig(
    // Static base: either a single hex address string or a map of gameId -> address.
    // Gson deserializes as String or LinkedTreeMap; resolver checks type at runtime.
    val base: Any? = null,

    // Pointer-resolved base: follow this chain to find the base at runtime
    val pointer: String? = null,
    val chain: List<String>? = null
)
