package com.emulnk.model

/**
 * Defines the Visual Theme.
 */
data class ThemeConfig(
    val id: String,              // Unique ID for the theme folder
    val meta: ThemeMeta,
    val targetProfileId: String, // Links to ProfileConfig.id (e.g., "GZL")
    val targetConsole: String? = null, // e.g., "GCN", "WII"
    val hideOverlay: Boolean = false, // Hides overlay buttons when active
    val assetsPath: String? = null,
    val settings: List<ThemeSettingSchema>? = emptyList()
)

data class ThemeMeta(
    val name: String,
    val author: String,
    val version: String? = "1.0.0", // Handle missing version in older theme.json
    val minAppVersion: Int? = 1, // Minimum EmuLink App Version (versionCode) required
    val description: String? = null,
    val links: Map<String, String>? = emptyMap()
)

/**
 * Defines a setting that the user can change in the App UI.
 */
data class ThemeSettingSchema(
    val id: String,          // e.g., "show_rupees"
    val label: String,       // e.g., "Show Rupee Counter"
    val type: String,        // "toggle", "color", "select"
    val default: String,     // Default value as string
    val options: List<String>? = null // For "select" type
)
