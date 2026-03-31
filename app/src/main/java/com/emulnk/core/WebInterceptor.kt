package com.emulnk.core

import android.util.Base64
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.emulnk.BuildConfig
import com.emulnk.model.GameData
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared WebView request interception for overlay widgets.
 * Used by both OverlayService (primary) and OverlayPresentation (secondary).
 */
object WebInterceptor {
    private const val TAG = "WebInterceptor"
    private const val SCHEME = "https://app.emulink/"

    private val MIME_MAP = mapOf(
        "html" to "text/html", "css" to "text/css", "js" to "application/javascript",
        "json" to "application/json",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp",
        "svg" to "image/svg+xml", "mp3" to "audio/mpeg", "wav" to "audio/wav",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf"
    )

    /** Cache canonicalPath per baseDir to avoid repeated symlink-resolving I/O on every request. */
    private val canonicalPathCache = ConcurrentHashMap<File, String>()

    private fun cachedCanonicalPath(dir: File): String =
        canonicalPathCache.getOrPut(dir) { dir.canonicalPath }

    fun intercept(url: String, baseDir: File, devMode: Boolean, devUrl: String, devThemePath: String): WebResourceResponse? {
        if (devMode && devUrl.isNotBlank() && url.startsWith(SCHEME)) {
            try {
                val fileName = url.removePrefix(SCHEME)
                val relativePath = if (fileName.isEmpty() || fileName == "/") "index.html" else fileName
                if (relativePath.contains("..")) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Path traversal blocked in dev URL: $relativePath")
                    return null
                }
                val remoteUrl = "${devUrl.removeSuffix("/")}/themes/$devThemePath/$relativePath"
                val conn = URL(remoteUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = NetworkConstants.CONNECT_TIMEOUT_MS
                conn.readTimeout = NetworkConstants.READ_TIMEOUT_MS
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val ext = relativePath.substringAfterLast('.', "").lowercase()
                    val mimeType = MIME_MAP[ext] ?: "application/octet-stream"
                    // Stream passed to WebView - connection closes when stream is consumed
                    return WebResourceResponse(mimeType, "UTF-8", conn.inputStream)
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Dev server fetch failed, falling back to local: ${e.message}")
            }
        }
        return intercept(url, baseDir)
    }

    fun intercept(url: String, baseDir: File): WebResourceResponse? {
        if (!url.startsWith(SCHEME)) return null

        val fileName = url.removePrefix(SCHEME)
        val requestedFile = if (fileName.isEmpty() || fileName == "/") "index.html" else fileName
        val file = File(baseDir, requestedFile).canonicalFile
        val baseDirCanonical = cachedCanonicalPath(baseDir)

        // Path traversal protection: the + File.separator suffix ensures "/tmp/a" won't
        // match "/tmp/abc/foo", only true children of baseDir pass.
        if (!file.canonicalPath.startsWith(baseDirCanonical + File.separator) &&
            file.canonicalPath != baseDirCanonical
        ) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Path traversal blocked: $fileName")
            }
            return null
        }

        if (!file.exists()) return null

        val mimeType = MIME_MAP[file.extension.lowercase()] ?: "application/octet-stream"
        return try {
            WebResourceResponse(mimeType, "UTF-8", file.inputStream())
        } catch (e: Exception) {
            Log.e(TAG, "Interception failed for $url", e)
            null
        }
    }
}

/** Configure WebSettings for overlay WebViews (shared by OverlayService and OverlayPresentation). */
@Suppress("SetJavaScriptEnabled")
fun WebSettings.configureForOverlay() {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    cacheMode = WebSettings.LOAD_NO_CACHE
    // false = viewport matches WebView's layout width (container), not screen width
    useWideViewPort = false
    loadWithOverviewMode = false
    setSupportZoom(false)
    builtInZoomControls = false
    displayZoomControls = false
}

/** Build the JS injection string for pushing GameData to a widget's updateData function. */
fun buildDataInjectionJs(gameData: GameData, gson: Gson): String {
    val encoded = Base64.encodeToString(gson.toJson(gameData).toByteArray(), Base64.NO_WRAP)
    return "if(typeof updateData !== 'undefined') updateData('$encoded', true)"
}
