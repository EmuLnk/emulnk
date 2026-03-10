package com.emulnk.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Safe removal: silently catches IllegalArgumentException from already-removed views. */
fun WindowManager.safeRemoveView(view: View?) {
    view ?: return
    try { removeView(view) } catch (_: Exception) {}
}

/** Creates the standard recovery pill (⋯) view + layout params for edit mode entry. */
fun createRecoveryPill(
    context: Context,
    density: Float,
    onClick: () -> Unit
): Pair<View, WindowManager.LayoutParams> {
    val widthPx = (40 * density).toInt()
    val heightPx = (24 * density).toInt()

    val pillBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12 * density
        setColor(UiColors.SURFACE_ELEVATED)
        setStroke((1 * density).toInt(), UiColors.TEXT_SECONDARY)
    }

    val pill = TextView(context).apply {
        text = "\u22EF"
        setTextColor(UiColors.TEXT_PRIMARY)
        textSize = 14f
        gravity = Gravity.CENTER
        background = pillBg
        alpha = 0.6f
        setOnClickListener { onClick() }
    }

    val params = WindowManager.LayoutParams(
        widthPx, heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (8 * density).toInt()
        y = (8 * density).toInt()
    }

    return Pair(pill, params)
}

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
    const val MAX_READ_SIZE = 2048

    /** Maximum valid 32-bit address */
    const val MAX_ADDRESS = 0xFFFFFFFFL

    /** Maximum depth for multi-level pointer chains */
    const val MAX_POINTER_CHAIN_DEPTH = 10

    /** Discovery handshake magic — expects JSON response with hash + game ID */
    val IDENTIFY_V2_MAGIC = byteArrayOf(0x45, 0x4D, 0x4C, 0x4B, 0x56, 0x32) // "EMLKV2"

    /** UDP timeout for V2 identify (includes hash computation of zipped ROMs) */
    const val IDENTIFY_V2_TIMEOUT_MS = 2000

}

object UiConstants {
    /** Time window to press back twice to exit app in milliseconds */
    const val BACK_PRESS_EXIT_DELAY_MS = 2000L

    /** Auto-hide overlay delay in milliseconds */
    const val AUTO_HIDE_OVERLAY_DELAY_MS = 4000L

    /** Delay before retrying theme updateData injection in milliseconds */
    const val THEME_INJECT_RETRY_DELAY_MS = 300L
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

object ConfigConstants {
    /** Maximum config file size in bytes (1 MB) */
    const val MAX_CONFIG_FILE_SIZE = 1_000_000L
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

object OverlayConstants {
    /** Long-press threshold to enter edit mode in milliseconds */
    const val LONG_PRESS_THRESHOLD_MS = 800L

    /** Notification channel ID for overlay service */
    const val NOTIFICATION_CHANNEL_ID = "emulnk_overlay"

    /** Notification ID for overlay service foreground notification */
    const val NOTIFICATION_ID = 1001

    /** Minimum drag distance to consider a move (pixels) */
    const val DRAG_THRESHOLD_PX = 10

    /** Snap-to-edge threshold in dp */
    const val SNAP_THRESHOLD_DP = 8

    /** Semi-transparent scrim color, ARGB: 80% alpha + SurfaceBase (#0E0C1C) */
    const val EDIT_SCRIM_COLOR = 0xCC0E0C1C

    /** Border corner radius in dp */
    const val EDIT_BORDER_RADIUS_DP = 8

    /** Selected widget border width in dp */
    const val EDIT_BORDER_SELECTED_WIDTH_DP = 2

    /** Unselected widget border width in dp */
    const val EDIT_BORDER_NORMAL_WIDTH_DP = 1

    /** Delay before auto-entering edit mode in builder, allows WebViews to finish loading */
    const val BUILDER_EDIT_MODE_DELAY_MS = 500L

    /** Screen target identifiers for dual-screen overlays */
    const val SCREEN_PRIMARY = "primary"
    const val SCREEN_SECONDARY = "secondary"

    /** Drawer panel height as fraction of screen height */
    const val DRAWER_HEIGHT_RATIO = 0.45f

    /** Drawer panel slide animation duration in milliseconds */
    const val DRAWER_ANIM_DURATION_MS = 250L

    /** Bottom inset to clear gesture navigation area (dp) */
    const val GESTURE_NAV_INSET_DP = 60

    /** Minimum controls bar width (dp) */
    const val CONTROLS_MIN_WIDTH_DP = 220

    /** Maximum undo/redo stack depth */
    const val MAX_UNDO_STACK = 50

    /** Double-tap timeout for toggle in milliseconds */
    const val DOUBLE_TAP_TIMEOUT_MS = 300L

    /** Theme chrome auto-hide delay in milliseconds */
    const val THEME_CHROME_AUTO_HIDE_MS = 4000L

    /** Theme chrome button size in dp */
    const val THEME_CHROME_BUTTON_SIZE_DP = 48

    /** Minimum alpha slider value as percentage (prevents fully invisible widgets) */
    const val ALPHA_SLIDER_MIN_PERCENT = 10

    /** Maximum alpha slider value as percentage */
    const val ALPHA_SLIDER_MAX_PERCENT = 100

    /** Intent extra flag for interactive theme mode */
    const val EXTRA_INTERACTIVE_THEME = "interactive_theme"

    /** Edit mode border color: visible but not selected (brand purple) */
    const val EDIT_BORDER_COLOR_ENABLED = 0xFFB47CFF

    /** Edit mode border color: currently selected (cyan) */
    const val EDIT_BORDER_COLOR_SELECTED = 0xFF00E5FF

    /** Edit mode border color: disabled (red) */
    const val EDIT_BORDER_COLOR_DISABLED = 0xFFFF5252

    /** Create the standard edit-mode border drawable for widgets. */
    fun createEditBorder(density: Float, enabled: Boolean, selected: Boolean): android.graphics.drawable.GradientDrawable {
        val radiusPx = EDIT_BORDER_RADIUS_DP * density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000)
            cornerRadius = radiusPx
            if (!enabled) {
                setStroke((EDIT_BORDER_NORMAL_WIDTH_DP * density).toInt(), EDIT_BORDER_COLOR_DISABLED.toInt())
            } else if (selected) {
                setStroke((EDIT_BORDER_SELECTED_WIDTH_DP * density).toInt(), EDIT_BORDER_COLOR_SELECTED.toInt())
            } else {
                setStroke((EDIT_BORDER_NORMAL_WIDTH_DP * density).toInt(), EDIT_BORDER_COLOR_ENABLED.toInt())
            }
        }
    }
}

/** ARGB int colors for android.view (non-Compose) overlay UI. Mirrors ui.theme.Color. */
object UiColors {
    const val SURFACE_BASE = 0xFF0E0C1C.toInt()
    const val SURFACE_ELEVATED = 0xFF1E1A3A.toInt()
    const val SURFACE_OVERLAY = 0xFF252142.toInt()
    const val BRAND_PURPLE = 0xFFB47CFF.toInt()
    const val BRAND_CYAN = 0xFF00E5FF.toInt()
    const val TEXT_PRIMARY = 0xFFEDE9FC.toInt()
    const val TEXT_SECONDARY = 0xFF9E96B8.toInt()
    const val DIVIDER = 0xFF2A2650.toInt()
    const val INPUT_BACKGROUND = 0xFF151226.toInt()
}
