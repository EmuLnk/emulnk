package com.emulnk.model

/**
 * Theme type constants.
 */
object ThemeType {
    const val THEME = "theme"
    const val OVERLAY = "overlay"
    const val BUNDLE = "bundle"
}

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
    val settings: List<ThemeSettingSchema>? = emptyList(),
    val type: String? = null,    // "theme", "overlay", or "bundle"; null defaults to "theme" (Gson bypasses Kotlin defaults)
    val widgets: List<WidgetConfig>? = null, // Widget definitions for overlay-type themes
    val pollingInterval: Long? = null, // Preferred polling rate in ms (null = default 200ms)
    val screenTarget: ScreenTarget? = null // Preferred screen for dual-screen (null = primary)
)

/** Resolves the effective type, defaulting null to "theme". */
val ThemeConfig.resolvedType: String
    get() = type ?: ThemeType.THEME

/**
 * Synthesizes a base-layer widget from a theme config (full-screen opaque theme widget).
 * Assumes all themes use `index.html` as entry point. This is a repo-level contract.
 */
fun ThemeConfig.toBaseLayerWidget(): WidgetConfig = WidgetConfig(
    id = "__base_$id",
    label = meta.name,
    src = "index.html",
    defaultWidth = 0,
    defaultHeight = 0,
    defaultX = 0,
    defaultY = 0,
    resizable = false,
    transparent = false,
    minWidth = 0,
    minHeight = 0,
    screenTarget = null,
    isBaseLayer = true,
    assetsPath = assetsPath
)

/** Resolves the preferred screen target: themes default to SECONDARY, overlays to PRIMARY. */
val ThemeConfig.resolvedScreenTarget: ScreenTarget
    get() = screenTarget ?: if (resolvedType == ThemeType.THEME) ScreenTarget.SECONDARY else ScreenTarget.PRIMARY

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
    val label: String? = null, // e.g., "Show Rupee Counter" (null for hidden settings like nuzlocke-data)
    val type: String,        // "toggle", "color", "select"
    val default: String,     // Default value as string
    val options: List<String>? = null, // For "select" type
    val category: String? = null, // e.g., "Party", "Battle" — groups settings in UI
    val hidden: Boolean? = null // Hidden settings are not shown in the settings dialog
)
