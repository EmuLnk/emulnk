package com.emulnk.model

/**
 * Configuration for a specific console/emulator mode.
 */
data class ConsoleConfig(
    val id: String,
    val name: String,
    val packageNames: List<String> = emptyList(), // Support multiple packages for forks
    val console: String, // GCN, WII, PSP, etc.
    val port: Int,
    val idAddress: String
)
