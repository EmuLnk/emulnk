package com.emulnk.bridge

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import com.emulnk.BuildConfig
import com.emulnk.core.BridgeConstants
import com.emulnk.core.NetworkConstants
import com.emulnk.core.memory.MemoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JS interface for overlay WebViews.
 * Provides write, writeVar, runMacro, vibrate, playSound, and log.
 * Excludes save, exit, openSettings which aren't applicable in the overlay context.
 */
class OverlayBridge(
    private val context: Context,
    private val memoryService: MemoryService,
    private val scope: CoroutineScope,
    private val themeId: String,
    private val themesRootDir: File,
    private val devMode: Boolean = false,
    private val devUrl: String = "",
    private val devThemePath: String = themeId,
    private val assetsDir: File? = null,
    private val onSave: ((String, String) -> Unit)? = null,
    private val onExit: (() -> Unit)? = null,
    private val onOpenSettings: (() -> Unit)? = null,
    private val debugLogCallback: ((String) -> Unit)? = null
) {
    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    init {
        if (devMode) cleanupOrphanedDevSounds()
    }

    @Synchronized
    @JavascriptInterface
    fun write(address: String, size: Int, value: Int) {
        if (!BridgeRateLimiter.checkWriteLimit()) return
        if (size !in BridgeConstants.VALID_WRITE_SIZES) return

        val addr = try {
            address.removePrefix("0x").toLong(16)
        } catch (_: NumberFormatException) {
            return
        }

        val buffer = ByteBuffer.allocate(size).order(memoryService.platformByteOrder)
        when (size) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> buffer.putInt(value)
        }
        memoryService.writeMemory(addr, buffer.array())
    }

    @Synchronized
    @JavascriptInterface
    fun writeVar(varId: String, value: Int) {
        if (!BridgeRateLimiter.checkWriteLimit()) return
        memoryService.writeVariable(varId, value)
    }

    @JavascriptInterface
    fun runMacro(macroId: String) {
        memoryService.runMacro(macroId) { log(it) }
    }

    @Synchronized
    @JavascriptInterface
    fun vibrate(ms: Long) {
        if (!BridgeRateLimiter.checkVibrateLimit()) return

        val clampedMs = ms.coerceIn(1, BridgeConstants.VIBRATE_MAX_DURATION_MS)
        vibrator.vibrate(VibrationEffect.createOneShot(clampedMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @Synchronized
    @JavascriptInterface
    fun playSound(fileName: String) {
        if (!BridgeRateLimiter.checkSoundLimit()) return

        val themeDir = assetsDir ?: File(themesRootDir, themeId)
        val file = File(themeDir, fileName)

        // Path traversal protection
        if (!file.canonicalPath.startsWith(themeDir.canonicalPath + File.separator) &&
            file.canonicalPath != themeDir.canonicalPath) {
            log("Error: Sound path traversal blocked: $fileName")
            return
        }

        if (file.exists()) {
            playLocalSound(file)
        } else if (devMode && devUrl.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val baseUrl = devUrl.removeSuffix("/")
                    val soundUrl = "$baseUrl/themes/$devThemePath/$fileName"
                    log("Dev: Fetching sound from $soundUrl")
                    val conn = java.net.URL(soundUrl).openConnection() as? java.net.HttpURLConnection ?: return@launch
                    var playbackStarted = false
                    var tempFile: File? = null
                    try {
                        conn.connectTimeout = NetworkConstants.CONNECT_TIMEOUT_MS
                        conn.readTimeout = NetworkConstants.READ_TIMEOUT_MS
                        if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                            tempFile = File(context.cacheDir, "dev_sound_${System.currentTimeMillis()}")
                            conn.inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                            withContext(Dispatchers.Main) { playLocalSound(tempFile) }
                            playbackStarted = true
                        } else {
                            log("Dev: Sound not found at $soundUrl (HTTP ${conn.responseCode})")
                        }
                    } finally {
                        conn.disconnect()
                        if (!playbackStarted && tempFile != null) {
                            tempFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    log("Dev Sound Error: ${e.message}")
                }
            }
        } else {
            log("Sound file not found: $fileName")
        }
    }

    private fun playLocalSound(file: File) {
        val mediaPlayer = android.media.MediaPlayer()
        var started = false
        try {
            mediaPlayer.setOnCompletionListener {
                it.release()
                if (file.name.startsWith("dev_sound_")) {
                    file.delete()
                }
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (file.name.startsWith("dev_sound_")) {
                    file.delete()
                }
                true
            }
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            started = true
        } catch (e: Exception) {
            log("Sound Error: ${e.message}")
            if (file.name.startsWith("dev_sound_")) {
                file.delete()
            }
        } finally {
            if (!started) mediaPlayer.release()
        }
    }

    private fun cleanupOrphanedDevSounds() {
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("dev_sound_") && (now - it.lastModified() > BridgeConstants.DEV_SOUND_CLEANUP_AGE_MS)
        }?.forEach { it.delete() }
    }

    @JavascriptInterface
    fun save(key: String, value: String) { onSave?.invoke(key, value) }

    @JavascriptInterface
    fun exit() { onExit?.invoke() }

    @JavascriptInterface
    fun openSettings() { onOpenSettings?.invoke() }

    @JavascriptInterface
    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("OverlayBridge", "JS: $message")
        }
        debugLogCallback?.invoke(message)
    }
}
