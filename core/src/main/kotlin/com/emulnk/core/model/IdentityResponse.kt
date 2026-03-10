package com.emulnk.core.model

/**
 * V2 handshake response from the emulator.
 */
data class IdentityResponse(
    val emulator: String,
    val game_id: String? = null,
    val game_hash: String? = null,
    val platform: String? = null
)
