package com.emulnk.core

import android.util.Log
import com.emulnk.BuildConfig
import com.emulnk.model.GalleryConsole
import com.emulnk.model.GalleryGame
import com.emulnk.model.GalleryIndex
import com.emulnk.model.GalleryTheme
import com.emulnk.model.RepoIndexV2
import com.emulnk.model.StoreWidget
import com.emulnk.model.ThemeType
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Handles syncing community content from a remote GitHub repository.
 */
class SyncService(private var rootDir: File) {

    companion object {
        private const val TAG = "SyncService"
        private val client = OkHttpClient.Builder()
            .connectTimeout(NetworkConstants.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(NetworkConstants.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        /** Longer timeouts for dev server (zip may be built on-demand). */
        private val devClientLazy = lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        private val devClient by devClientLazy
        private val gson = Gson()
    }

    fun updateRootDir(newDir: File) {
        this.rootDir = newDir
    }

    fun close() {
        Thread {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            if (devClientLazy.isInitialized()) {
                devClient.dispatcher.executorService.shutdown()
                devClient.connectionPool.evictAll()
            }
        }.start()
    }

    fun deriveRawBaseUrl(archiveUrl: String): String {
        // releases URL: https://github.com/{owner}/{repo}/releases/latest/download/{file}.zip
        val releasesMatch = Regex("github\\.com/([^/]+/[^/]+)/releases/").find(archiveUrl)
        if (releasesMatch != null) {
            return "https://raw.githubusercontent.com/${releasesMatch.groupValues[1]}/main"
        }
        // archive URL: https://github.com/{owner}/{repo}/archive/refs/heads/{branch}.zip
        return archiveUrl
            .replace(Regex("archive/refs/heads/(.+)\\.zip"), "raw/$1")
            .trimEnd('/')
    }

    private suspend fun <T> fetchJsonWithRetry(url: String, label: String, parse: (String) -> T?): T? {
        var lastException: Exception? = null

        for (attempt in 0 until SyncConstants.MAX_RETRIES) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return parse(response.body?.string() ?: return null)
                    } else {
                        Log.e(TAG, "Fetch $label Failed: ${response.code} (attempt ${attempt + 1})")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Fetch $label Exception (attempt ${attempt + 1})", e)
            }

            if (attempt < SyncConstants.MAX_RETRIES - 1) {
                val delayMs = SyncConstants.INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                delay(delayMs + (0..delayMs / 4).random())
            }
        }

        if (lastException != null) {
            Log.e(TAG, "Fetch $label failed after ${SyncConstants.MAX_RETRIES} attempts", lastException)
        }
        return null
    }

    suspend fun fetchRepoIndex(baseUrl: String): GalleryIndex? {
        val url = deriveRawBaseUrl(baseUrl) + "/index.json"
        return fetchJsonWithRetry(url, "Index") { json ->
            mapToGalleryIndex(gson.fromJson(json, RepoIndexV2::class.java))
        }
    }

    private fun mapToGalleryIndex(v2: RepoIndexV2): GalleryIndex {
        val grouped = v2.games.groupBy { it.console }
        val consoles = grouped.map { (consoleId, games) ->
            GalleryConsole(
                id = consoleId,
                games = games.map { game ->
                    GalleryGame(
                        profileId = game.profileId,
                        name = game.name,
                        console = game.console,
                        hasWidgets = game.hasWidgets,
                        themes = game.themes.map { theme ->
                            GalleryTheme(
                                id = theme.id,
                                name = theme.name,
                                author = theme.author,
                                version = theme.version,
                                description = theme.description,
                                type = theme.type ?: ThemeType.THEME,
                                tags = theme.tags,
                                minAppVersion = theme.minAppVersion ?: 1,
                                previewUrl = theme.previewUrl,
                                console = game.console,
                                profileId = game.profileId,
                                gameName = game.name
                            )
                        }
                    )
                }
            )
        }
        return GalleryIndex(consoles = consoles)
    }

    suspend fun fetchWidgetsJson(baseUrl: String, console: String, profileId: String): List<StoreWidget>? {
        val url = "$baseUrl/themes/$console/$profileId/widgets/widgets.json"
        return fetchJsonWithRetry(url, "Widgets") { json ->
            gson.fromJson(json, StoreWidget.LIST_TYPE)
        }
    }

    suspend fun downloadSingleFile(url: String, targetFile: File): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $url", e)
            false
        }
    }

    /**
     * Downloads and extracts a zip from a URL.
     */
    fun downloadAndExtract(
        url: String,
        stripRoot: Boolean,
        targetSubDir: String? = null,
        pathFilter: ((String) -> Boolean)? = null,
        pathRewriter: ((String) -> String)? = null,
        onProgress: (String) -> Unit
    ): Boolean {
        return try {
            onProgress("Connecting...")
            val request = Request.Builder().url(url).build()
            val isDev = url.contains("__dev_sync")
            val httpClient = if (isDev) devClient else client

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress("Failed: HTTP ${response.code}")
                    return false
                }

                val body = response.body ?: return false

                val contentLength = body.contentLength()
                if (contentLength > SyncConstants.MAX_DOWNLOAD_SIZE_BYTES) {
                    Log.w(TAG, "Download size ($contentLength bytes) exceeds limit (${SyncConstants.MAX_DOWNLOAD_SIZE_BYTES} bytes)")
                    onProgress("Error: File too large (${contentLength / 1024 / 1024} MB)")
                    return false
                }

                val baseDir = if (targetSubDir != null) File(rootDir, targetSubDir) else rootDir

                // Wrap stream with a bounded input stream to handle missing Content-Length
                val boundedStream = BoundedInputStream(body.byteStream(), SyncConstants.MAX_DOWNLOAD_SIZE_BYTES)
                unzipStream(boundedStream, baseDir, stripRoot, onProgress, pathFilter, pathRewriter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download Error", e)
            onProgress("Error: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Extracts a Zip stream to a target directory.
     */
    fun unzipStream(
        inputStream: InputStream,
        targetDir: File,
        stripRoot: Boolean,
        onProgress: (String) -> Unit,
        pathFilter: ((String) -> Boolean)? = null,
        pathRewriter: ((String) -> String)? = null
    ): Boolean {
        var extractionSuccessful = false
        return try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Extracting to: ${targetDir.absolutePath}")
            }
            targetDir.mkdirs()
            val canonicalTargetDir = targetDir.canonicalPath

            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                if (entry == null) {
                    onProgress("Error: Invalid Zip")
                    targetDir.deleteRecursively()
                    return false
                }

                var fileCount = 0
                var entryCount = 0
                var totalBytesWritten = 0L
                val extractedFiles = mutableListOf<File>()
                var failedFile: String? = null
                val buffer = ByteArray(8192)

                while (entry != null) {
                    entryCount++
                    // ZIP bomb: entry count check (counts all entries including directories)
                    if (entryCount >= SyncConstants.MAX_ZIP_ENTRIES) {
                        Log.w(TAG, "ZIP entry limit exceeded (${SyncConstants.MAX_ZIP_ENTRIES})")
                        onProgress("Error: Too many files in archive")
                        failedFile = "entry_limit"
                        break
                    }

                    val nameParts = entry.name.split("/")
                    val relativePath = if (stripRoot) {
                        if (nameParts.size > 1) nameParts.drop(1).joinToString("/") else ""
                    } else {
                        entry.name
                    }

                    if (relativePath.isNotEmpty() && (pathFilter == null || pathFilter(relativePath))) {
                        val outputPath = pathRewriter?.invoke(relativePath) ?: relativePath
                        if (outputPath.isEmpty()) {
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                            continue
                        }
                        val targetFile = File(targetDir, outputPath)

                        // Path traversal protection: the + File.separator suffix ensures
                        // "/tmp/a" won't match "/tmp/abc/foo", only true children pass.
                        if (!targetFile.canonicalPath.startsWith(canonicalTargetDir + File.separator) &&
                            targetFile.canonicalPath != canonicalTargetDir) {
                            Log.w(TAG, "Path traversal blocked: ${entry.name}")
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            try {
                                FileOutputStream(targetFile).use { output ->
                                    var bytesRead: Int
                                    while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                        totalBytesWritten += bytesRead
                                        if (totalBytesWritten > SyncConstants.MAX_EXTRACT_SIZE_BYTES) {
                                            Log.w(TAG, "ZIP bomb detected: extracted size exceeds ${SyncConstants.MAX_EXTRACT_SIZE_BYTES} bytes")
                                            onProgress("Error: Archive too large (possible ZIP bomb)")
                                            failedFile = "size_limit"
                                            break
                                        }
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                                if (failedFile != null) break
                                extractedFiles.add(targetFile)
                                fileCount++
                            } catch (e: Exception) {
                                Log.e(TAG, "File Write Error: ${targetFile.name}", e)
                                failedFile = targetFile.name
                                break
                            }
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }

                if (failedFile != null) {
                    Log.w(TAG, "Extraction failed at: $failedFile, cleaning up extracted files")
                    extractedFiles.forEach { file ->
                        try {
                            file.delete()
                        } catch (_: Exception) {
                            Log.w(TAG, "Failed to delete extracted file during cleanup: ${file.name}")
                        }
                    }
                    onProgress("Error: Failed to extract $failedFile")
                    return false
                }

                extractionSuccessful = true
                onProgress("Extraction Complete ($fileCount files)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unzip Error", e)
            onProgress("Error: ${e.localizedMessage}")
            if (!extractionSuccessful) {
                targetDir.deleteRecursively()
            }
            false
        }
    }

    /**
     * InputStream wrapper that throws after a maximum number of bytes read.
     */
    private class BoundedInputStream(
        stream: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(stream) {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= maxBytes) throw java.io.IOException("Download size limit exceeded ($maxBytes bytes)")
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) throw java.io.IOException("Download size limit exceeded ($maxBytes bytes)")
            val result = super.read(b, off, len)
            if (result > 0) bytesRead += result
            return result
        }
    }
}
