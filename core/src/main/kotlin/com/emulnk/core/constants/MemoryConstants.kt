package com.emulnk.core.constants

/**
 * Application-wide constants for timeouts, intervals, and thresholds.
 * Centralizes magic numbers for maintainability.
 */

object MemoryConstants {
    /** UDP socket timeout in milliseconds */
    const val SOCKET_TIMEOUT_MS = 500

    /** Memory polling interval in milliseconds */
    const val POLLING_INTERVAL_MS = 200L

    /** Minimum allowed polling interval in milliseconds */
    const val MIN_POLLING_INTERVAL_MS = 50L

    /** Maximum allowed polling interval in milliseconds */
    const val MAX_POLLING_INTERVAL_MS = 5000L

    /** Delay between detection retry attempts in milliseconds */
    const val DETECTION_RETRY_DELAY_MS = 1000L

    /** Delay after successful detection before starting polling in milliseconds */
    const val DETECTION_SUCCESS_DELAY_MS = 3000L

    /** Maximum number of consecutive detection failures before clearing game state */
    const val MAX_DETECTION_FAILURES = 3

    /** Number of consecutive failures before re-identifying the emulator on each port.
     *  Handles emulator switches on the same port (e.g. Dolphin → RetroArch). */
    const val IDENTITY_REFRESH_FAILURES = 10

    /** Maximum single read size in bytes */
    const val MAX_READ_SIZE = 4096

    /** Maximum entries in a batch read request */
    const val BATCH_MAX_ENTRIES = 256

    /** Response buffer size for batch reads (64 KB, near UDP max) */
    const val BATCH_RESPONSE_BUFFER = 65000

    /** Safe budget, leaves 5 KB headroom below the UDP receive buffer ceiling */
    const val BATCH_RESPONSE_BUDGET = BATCH_RESPONSE_BUFFER - 5000

    /** Poll tier intervals */
    const val POLL_TIER_HIGH_MS = 50L
    const val POLL_TIER_LOW_MS = 1000L

    /** Maximum valid 32-bit address */
    const val MAX_ADDRESS = 0xFFFFFFFFL

    /** Maximum depth for multi-level pointer chains */
    const val MAX_POINTER_CHAIN_DEPTH = 10

    /** Discovery handshake magic - expects JSON response with hash + game ID */
    val IDENTIFY_V2_MAGIC = byteArrayOf(0x45, 0x4D, 0x4C, 0x4B, 0x56, 0x32) // "EMLKV2"

    /** UDP timeout for V2 identify (includes hash computation of zipped ROMs) */
    const val IDENTIFY_V2_TIMEOUT_MS = 2000

}