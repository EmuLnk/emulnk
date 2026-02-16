package com.emulnk.bridge

import android.content.Context
import android.os.VibrationEffect
import com.emulnk.BuildConfig
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import com.emulnk.core.BridgeConstants
import com.emulnk.core.NetworkConstants
import com.emulnk.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JavaScript interface for themes.
 */
class EmuLinkBridge(
    private val context: Context,
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
    private val themeId: String,
    private val themesRootDir: File,
    private val devMode: Boolean = false,
    private val devUrl: String = ""
) {
    private val vibrator: Vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private var lastVibrateTime = 0L
    private var lastSoundTime = 0L
    private var writeCount = 0
    private var writeWindowStart = 0L

    /**
     * Returns true if under rate limit, false if rate-limited.
     * Must be called from a @Synchronized method.
     */
    private fun checkWriteRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        if (now - writeWindowStart > 1000L) {
            writeCount = 0
            writeWindowStart = now
        }
        if (writeCount >= BridgeConstants.WRITE_MAX_PER_SECOND) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("EmuLinkBridge", "write rate limited ($writeCount calls in window)")
            }
            return false
        }
        writeCount++
        return true
    }

    @Synchronized
    @JavascriptInterface
    fun write(address: String, size: Int, value: Int) {
        if (!checkWriteRateLimit()) return

        if (size !in BridgeConstants.VALID_WRITE_SIZES) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("EmuLinkBridge", "write() rejected invalid size: $size (valid: ${BridgeConstants.VALID_WRITE_SIZES})")
            }
            viewModel.addDebugLog("Error: Invalid write size $size (valid: ${BridgeConstants.VALID_WRITE_SIZES})")
            return
        }

        val addr = try {
            address.removePrefix("0x").toLong(16)
        } catch (e: NumberFormatException) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("EmuLinkBridge", "Invalid hex address: $address", e)
            }
            viewModel.addDebugLog("Error: Invalid address format '$address'")
            return
        }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        when (size) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> buffer.putInt(value)
        }
        viewModel.writeMemory(addr, buffer.array())
    }

    @Synchronized
    @JavascriptInterface
    fun writeVar(varId: String, value: Int) {
        if (!checkWriteRateLimit()) return
        viewModel.writeVariable(varId, value)
    }

    @JavascriptInterface
    fun runMacro(macroId: String) = viewModel.runMacro(macroId)

    @Synchronized
    @JavascriptInterface
    fun vibrate(ms: Long) {
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < BridgeConstants.VIBRATE_MIN_INTERVAL_MS) return
        lastVibrateTime = now

        val clampedMs = ms.coerceIn(1, BridgeConstants.VIBRATE_MAX_DURATION_MS)
        vibrator.vibrate(VibrationEffect.createOneShot(clampedMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @JavascriptInterface
    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("EmuLinkBridge", "JS LOG: $message")
        }
        viewModel.addDebugLog(message)
    }

    @Synchronized
    @JavascriptInterface
    fun playSound(fileName: String) {
        val now = System.currentTimeMillis()
        if (now - lastSoundTime < BridgeConstants.SOUND_MIN_INTERVAL_MS) return
        lastSoundTime = now

        if (devMode) {
            cleanupOrphanedDevSounds()
        }

        val themeDir = File(themesRootDir, themeId)
        val file = File(themeDir, fileName)

        // Path traversal protection
        if (!file.canonicalPath.startsWith(themeDir.canonicalPath + File.separator) &&
            file.canonicalPath != themeDir.canonicalPath) {
            viewModel.addDebugLog("Error: Sound path traversal blocked: $fileName")
            return
        }

        if (file.exists()) {
            playLocalSound(file)
        } else if (devMode && devUrl.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val baseUrl = devUrl.removeSuffix("/")
                    val soundUrl = "$baseUrl/themes/$themeId/$fileName"
                    viewModel.addDebugLog("Dev: Fetching sound from $soundUrl")
                    val conn = java.net.URL(soundUrl).openConnection() as java.net.HttpURLConnection
                    var playbackStarted = false
                    var tempFile: File? = null
                    try {
                        conn.connectTimeout = NetworkConstants.CONNECT_TIMEOUT_MS
                        conn.readTimeout = NetworkConstants.READ_TIMEOUT_MS
                        if (conn.responseCode == 200) {
                            tempFile = File(context.cacheDir, "dev_sound_${System.currentTimeMillis()}")
                            conn.inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                            withContext(Dispatchers.Main) { playLocalSound(tempFile) }
                            playbackStarted = true
                        } else {
                            viewModel.addDebugLog("Dev: Sound not found at $soundUrl (HTTP ${conn.responseCode})")
                        }
                    } finally {
                        conn.disconnect()
                        if (!playbackStarted && tempFile != null) {
                            tempFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    viewModel.addDebugLog("Dev Sound Error: ${e.message}")
                }
            }
        } else {
            viewModel.addDebugLog("Sound file not found: $fileName")
        }
    }

    private fun playLocalSound(file: File) {
        val mediaPlayer = android.media.MediaPlayer()
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener {
                it.release()
                // Clean up temp dev mode sound files
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
        } catch (e: Exception) {
            viewModel.addDebugLog("Sound Error: ${e.message}")
            mediaPlayer.release()
            if (file.name.startsWith("dev_sound_")) {
                file.delete()
            }
        }
    }

    private fun cleanupOrphanedDevSounds() {
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("dev_sound_") && (now - it.lastModified() > BridgeConstants.DEV_SOUND_CLEANUP_AGE_MS)
        }?.forEach { it.delete() }
    }

    @JavascriptInterface
    fun save(key: String, value: String) = viewModel.updateThemeSetting(themeId, key, value)

    @JavascriptInterface
    fun exit() {
        viewModel.selectTheme(null)
    }

    @JavascriptInterface
    fun openSettings() {
        viewModel.requestSettings()
    }
}
