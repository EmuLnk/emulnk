package com.emulnk.core

/**
 * Application-wide constants for timeouts, intervals, and thresholds.
 * Centralizes magic numbers for maintainability.
 */

object MemoryConstants {
    /** UDP socket timeout in milliseconds */
    const val SOCKET_TIMEOUT_MS = 500

    /** Memory polling interval in milliseconds */
    const val POLLING_INTERVAL_MS = 200L

    /** Delay between detection retry attempts in milliseconds */
    const val DETECTION_RETRY_DELAY_MS = 1000L

    /** Delay after successful detection before starting polling in milliseconds */
    const val DETECTION_SUCCESS_DELAY_MS = 3000L

    /** Maximum number of consecutive detection failures before giving up */
    const val MAX_DETECTION_FAILURES = 3

    /** Maximum single read size in bytes */
    const val MAX_READ_SIZE = 1024

    /** Maximum valid 32-bit address */
    const val MAX_ADDRESS = 0xFFFFFFFFL

    /** Maximum depth for multi-level pointer chains */
    const val MAX_POINTER_CHAIN_DEPTH = 10
}

object UiConstants {
    /** Time window to press back twice to exit app in milliseconds */
    const val BACK_PRESS_EXIT_DELAY_MS = 2000L

    /** Auto-hide overlay delay in milliseconds */
    const val AUTO_HIDE_OVERLAY_DELAY_MS = 4000L
}

object TelemetryConstants {
    /** Telemetry update interval in milliseconds */
    const val UPDATE_INTERVAL_MS = 5000L

    /** Threshold for converting millidegrees to degrees Celsius */
    const val THERMAL_MILLIDEGREE_THRESHOLD = 1000f
}

object NetworkConstants {
    /** HTTP connection timeout in milliseconds */
    const val CONNECT_TIMEOUT_MS = 2000

    /** HTTP read timeout in milliseconds */
    const val READ_TIMEOUT_MS = 3000
}

object SyncConstants {
    /** Maximum total extracted size in bytes (100 MB) */
    const val MAX_EXTRACT_SIZE_BYTES = 100L * 1024 * 1024

    /** Maximum number of ZIP entries */
    const val MAX_ZIP_ENTRIES = 500

    /** Maximum download size in bytes (50 MB) */
    const val MAX_DOWNLOAD_SIZE_BYTES = 50L * 1024 * 1024

    /** Maximum retry attempts for network requests */
    const val MAX_RETRIES = 3

    /** Initial retry delay in milliseconds */
    const val INITIAL_RETRY_DELAY_MS = 1000L
}

object BridgeConstants {
    /** Minimum interval between vibrate calls in milliseconds */
    const val VIBRATE_MIN_INTERVAL_MS = 50L

    /** Maximum vibrate duration in milliseconds */
    const val VIBRATE_MAX_DURATION_MS = 5000L

    /** Minimum interval between playSound calls in milliseconds */
    const val SOUND_MIN_INTERVAL_MS = 200L

    /** Maximum write calls per second */
    const val WRITE_MAX_PER_SECOND = 30

    /** Valid write sizes */
    val VALID_WRITE_SIZES = setOf(1, 2, 4)

    /** Age threshold for cleaning up orphaned dev sound files in milliseconds */
    const val DEV_SOUND_CLEANUP_AGE_MS = 60_000L
}

object MathConstants {
    /** Maximum formula expression length */
    const val MAX_EXPRESSION_LENGTH = 256

    /** Maximum nesting depth for parentheses */
    const val MAX_NESTING_DEPTH = 20
}
