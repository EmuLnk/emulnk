package com.emulnk.model

/**
 * Global application preferences.
 */
data class AppConfig(
    val autoBoot: Boolean = true,
    val repoUrl: String = "https://github.com/EmuLnk/emulnk-repo/archive/refs/heads/main.zip",
    val defaultThemes: Map<String, String> = emptyMap(), // GameID -> ThemeID
    val devMode: Boolean = false,
    val devUrl: String = ""
)
