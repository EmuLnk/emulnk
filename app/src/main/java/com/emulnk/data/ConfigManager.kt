package com.emulnk.data

import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.emulnk.BuildConfig
import com.emulnk.model.AppConfig
import com.emulnk.core.model.ConsoleConfig
import com.emulnk.model.CustomOverlayConfig
import com.emulnk.core.model.HashEntry
import com.emulnk.core.model.ProfileConfig
import com.emulnk.core.resolver.ProfileMerger
import com.emulnk.core.resolver.ProfileResolver
import com.emulnk.model.OverlayLayout
import com.emulnk.model.SavedOverlayConfig
import com.emulnk.model.StoreWidget
import com.emulnk.model.ThemeConfig
import com.google.gson.Gson
import com.emulnk.core.ConfigConstants
import com.emulnk.core.constants.ConsoleConfigDefaults
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Loads JSON configurations from the external storage.
 */
class ConfigManager(private val context: android.content.Context) {
    companion object {
        private const val TAG = "ConfigManager"
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("emulink_prefs", android.content.Context.MODE_PRIVATE)
    
    private val configLock = Any()

    private fun validateId(id: String, label: String): Boolean {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) {
            Log.e(TAG, "Invalid $label rejected (path traversal attempt): $id")
            return false
        }
        return true
    }

    private fun atomicWrite(file: File, content: String) {
        val tmpFile = File(file.parent, "${file.name}.tmp")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(file)) {
            // renameTo can fail on some filesystems; fall back to copy + delete
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun validateCanonicalPath(file: File, parentDir: File, label: String): Boolean {
        if (!file.canonicalPath.startsWith(parentDir.canonicalPath + File.separator)) {
            Log.e(TAG, "$label escapes directory: ${file.canonicalPath}")
            return false
        }
        return true
    }

    private lateinit var rootDir: File
    private lateinit var themesDir: File
    private lateinit var profilesDir: File
    private lateinit var savesDir: File
    private lateinit var userOverlaysDir: File
    private lateinit var consolesFile: File
    private lateinit var appConfigFile: File

    init {
        val savedRoot = prefs.getString("root_uri", null)
        rootDir = if (savedRoot != null) File(savedRoot)
                  else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "EmuLink")
        updateDerivedPaths()
        ensureDirs()
    }

    fun setRootDirectory(path: String) {
        prefs.edit { putString("root_uri", path) }
        rootDir = File(path)
        updateDerivedPaths()
        ensureDirs()
    }

    private fun updateDerivedPaths() {
        themesDir = File(rootDir, "themes")
        profilesDir = File(rootDir, "profiles")
        savesDir = File(rootDir, "saves")
        userOverlaysDir = File(rootDir, "user_overlays")
        consolesFile = File(rootDir, "consoles.json")
        appConfigFile = File(rootDir, "app_settings.json")
    }

    /** Cached hash registry: hash -> HashEntry. Loaded lazily, refreshed on sync. */
    @Volatile
    private var hashRegistry: Map<String, HashEntry> = emptyMap()

    fun getRootDir(): File = rootDir
    fun getThemesDir(): File = themesDir
    fun getSavesDir(): File = savesDir
    fun getUserOverlaysDir(): File = userOverlaysDir
    fun getWidgetsDir(): File = File(rootDir, "widgets")
    fun isRootPathSet(): Boolean = prefs.contains("root_uri")

    fun getAppVersionCode(): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.and(0xFFFFFFFFL).toInt()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to read app version code: ${e.message}")
            }
            1
        }
    }

    private fun ensureDirs() {
        themesDir.mkdirs()
        profilesDir.mkdirs()
        savesDir.mkdirs()
        userOverlaysDir.mkdirs()
    }

    // --- Hash Registry ---

    /** Load hashes.json from root directory into memory. Called on startup and after sync. */
    fun loadHashRegistry() {
        val file = File(rootDir, "hashes.json")
        if (!file.exists() || file.length() > ConfigConstants.MAX_CONFIG_FILE_SIZE) {
            hashRegistry = emptyMap()
            return
        }
        val parsed = try {
            val type = object : TypeToken<Map<String, HashEntry>>() {}.type
            gson.fromJson<Map<String, HashEntry>>(file.readText(), type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hashes.json", e)
            emptyMap()
        }
        hashRegistry = parsed
    }

    fun getHashRegistry(): Map<String, HashEntry> = hashRegistry

    /**
     * Create a ProfileResolver that uses the hash registry and this ConfigManager's
     * profile loading/resolution capabilities.
     */
    fun createProfileResolver(): ProfileResolver {
        return ProfileResolver(
            hashRegistry = hashRegistry,
            profileLoader = object : ProfileResolver.ProfileLoader {
                override fun loadProfile(profileId: String): ProfileConfig? {
                    return this@ConfigManager.loadProfileResolved(profileId)
                }
                override fun resolveProfileId(gameId: String): String? {
                    return this@ConfigManager.resolveProfileId(gameId)
                }
            }
        )
    }

    /**
     * Load a profile by ID, resolving inheritance via ProfileMerger.
     * Returns the fully-merged profile ready for use.
     */
    fun loadProfileResolved(profileId: String): ProfileConfig? {
        val raw = loadProfileRaw(profileId) ?: return null
        if (raw.extends == null) return raw
        return ProfileMerger.resolve(raw) { loadProfileRaw(it) }
    }

    /** Return the direct parent profile ID (the `extends` field) for a given profile, or null. */
    fun getParentProfileId(profileId: String): String? {
        return loadProfileRaw(profileId)?.extends
    }

    /** Load a profile without inheritance resolution (raw JSON parse). */
    private fun loadProfileRaw(profileId: String): ProfileConfig? {
        if (!validateId(profileId, "profileId")) return null
        // Search in platform subdirectories first, then flat profiles dir
        val platformDirs = profilesDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in platformDirs) {
            val file = File(dir, "$profileId.json")
            if (file.exists()) return parseProfile(file)
        }
        // Fallback to flat structure
        val file = File(profilesDir, "$profileId.json")
        if (file.exists()) return parseProfile(file)
        return null
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)
    
    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit { putBoolean("onboarding_completed", completed) }
    }

    fun getAppConfig(): AppConfig = synchronized(configLock) {
        val config = if (appConfigFile.exists()) {
            try {
                gson.fromJson(appConfigFile.readText(), AppConfig::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse app_settings.json", e)
                // Preserve corrupted file for debugging
                try {
                    val backupFile = File(appConfigFile.parent, "app_settings.json.bak")
                    appConfigFile.copyTo(backupFile, overwrite = true)
                    Log.w(TAG, "Corrupted app_settings.json backed up to ${backupFile.name}")
                } catch (backupEx: Exception) {
                    Log.w(TAG, "Failed to backup corrupted app_settings.json: ${backupEx.message}")
                }
                AppConfig()
            }
        } else AppConfig()

        // Migrate repo URL from old default URLs to release URL
        val oldUrls = setOf(
            "https://github.com/EmuLnk/emulnk-repo/archive/refs/heads/feature/overlay.zip",
            "https://github.com/EmuLnk/emulnk-repo/archive/refs/heads/main.zip"
        )
        if (config.repoUrl in oldUrls) {
            val migrated = config.copy(repoUrl = AppConfig().repoUrl)
            saveAppConfig(migrated)
            return@synchronized migrated
        }
        config
    }

    fun saveAppConfig(config: AppConfig) {
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            Log.w(TAG, "Failed to create root directory: ${rootDir.absolutePath}")
            return
        }
        synchronized(configLock) {
            try {
                atomicWrite(appConfigFile, gson.toJson(config))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save app_settings.json: ${e.message}")
            }
        }
    }

    fun getConsoleConfigs(): List<ConsoleConfig> {
        return if (consolesFile.exists()) {
            try {
                val type = object : TypeToken<List<ConsoleConfig>>() {}.type
                gson.fromJson(consolesFile.readText(), type)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse consoles.json", e)
                createDefaultConsoles(save = false)
            }
        } else {
            // Only persist defaults if root dir is set
            createDefaultConsoles(save = rootDir.exists() && prefs.contains("root_uri"))
        }
    }

    private fun createDefaultConsoles(save: Boolean = true): List<ConsoleConfig> {
        val defaults = ConsoleConfigDefaults
        if (save) {
            try {
                consolesFile.writeText(gson.toJson(defaults))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save default consoles.json: ${e.message}")
            }
        }
        return defaults
    }

    fun getAvailableThemes(): List<ThemeConfig> {
        if (!themesDir.exists()) {
            Log.e(TAG, "Themes directory does not exist: ${themesDir.absolutePath}")
            return emptyList()
        }
        
        val folders = themesDir.listFiles { file -> file.isDirectory }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Scanning themesDir: ${themesDir.absolutePath}. Found ${folders?.size ?: 0} folders.")
        }

        return folders?.mapNotNull { folder ->
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "Checking folder: ${folder.name}")
            }
            loadThemeConfig(folder)
        } ?: emptyList()
    }

    fun deleteTheme(themeId: String): Boolean {
        if (!validateId(themeId, "themeId")) return false
        val themeFolder = File(themesDir, themeId)
        if (!validateCanonicalPath(themeFolder, themesDir, "Theme folder")) return false
        return themeFolder.deleteRecursively()
    }

    private fun loadThemeConfig(folder: File): ThemeConfig? {
        val configFile = File(folder, "theme.json")
        if (!configFile.exists()) {
            Log.w(TAG, "theme.json missing in ${folder.name}")
            return null
        }
        if (configFile.length() > ConfigConstants.MAX_CONFIG_FILE_SIZE) {
            Log.e(TAG, "theme.json too large, skipping: ${folder.name} (${configFile.length()} bytes)")
            return null
        }

        return try {
            val json = configFile.readText()
            val config = gson.fromJson(json, ThemeConfig::class.java).copy(id = folder.name)
            // Gson bypasses Kotlin defaults, so normalize null type to "theme"
            config.copy(type = config.type ?: "theme")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing theme.json in ${folder.name}", e)
            null
        }
    }

    /** Collect all profile JSON files from both flat and platform subdirectory layouts. */
    private fun allProfileFiles(): List<File> {
        if (!profilesDir.exists()) return emptyList()
        val flat = profilesDir.listFiles { f -> f.isFile && f.extension == "json" }?.toList() ?: emptyList()
        val nested = profilesDir.listFiles { f -> f.isDirectory }
            ?.flatMap { dir -> dir.listFiles { f -> f.extension == "json" }?.toList() ?: emptyList() }
            ?: emptyList()
        return flat + nested
    }

    /**
     * Resolves a raw game ID to a profile ID using a 3-tier strategy:
     * 1. Exact match: gameIds list in profile JSON matches the gameId case-insensitively
     * 2. Prefix match: for truncated IDs (e.g. SNES serials), matches stripped-space prefixes
     * 3. Filename match: for GameCube/Wii style IDs, matches profile filename prefixes
     */
    fun resolveProfileId(gameId: String): String? {
        val files = allProfileFiles()
        if (files.isEmpty()) return null
        val profiles = files.mapNotNull { parseProfile(it) }

        profiles.find { p ->
            p.gameIds?.any { it.equals(gameId, ignoreCase = true) } == true
        }?.let { return it.id }

        // Prefix match for truncated IDs (e.g. SNES serials)
        if (gameId.length >= 6) {
            profiles.find { p ->
                p.gameIds?.any {
                    it.replace(" ", "").startsWith(gameId, ignoreCase = true)
                } == true
            }?.let { return it.id }
        }

        // Filename prefix matching (GameCube/Wii style)
        if (gameId.length >= 3) {
            files.asSequence().map { it.nameWithoutExtension }
                .find { gameId.startsWith(it, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    /**
     * Load a profile by ID with inheritance resolution and filename-based fallback.
     * This is the primary entry point for the UI layer.
     */
    fun loadProfile(profileId: String): ProfileConfig? {
        if (!validateId(profileId, "profileId")) return null
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loading profile for: $profileId")
        }

        // Try resolved (inheritance-aware) load first
        loadProfileResolved(profileId)?.let { return it }

        // Fallback: filename-based matching (short + series)
        if (profileId.length >= 4) {
            val shortId = profileId.take(4)
            loadProfileResolved(shortId)?.let {
                if (BuildConfig.DEBUG) Log.d(TAG, "Found profile via 4-char match: $shortId")
                return it
            }
        }
        if (profileId.length >= 3) {
            val seriesId = profileId.take(3)
            loadProfileResolved(seriesId)?.let {
                if (BuildConfig.DEBUG) Log.d(TAG, "Found profile via series match: $seriesId")
                return it
            }
        }

        Log.e(TAG, "No profile found for $profileId in ${profilesDir.absolutePath}")
        return null
    }

    fun saveOverlayLayout(overlayId: String, layout: OverlayLayout, screenId: String? = null) {
        if (!validateId(overlayId, "overlayId")) return
        if (screenId != null && !validateId(screenId, "screenId")) return
        synchronized(configLock) {
            try {
                savesDir.mkdirs()
                val fileName = if (screenId != null) "${overlayId}_layout_${screenId}.json" else "${overlayId}_layout.json"
                val file = File(savesDir, fileName)
                atomicWrite(file, gson.toJson(layout))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save overlay layout for $overlayId (screen=$screenId): ${e.message}")
            }
        }
    }

    fun loadOverlayLayout(overlayId: String, screenId: String? = null): OverlayLayout? {
        if (!validateId(overlayId, "overlayId")) return null
        if (screenId != null && !validateId(screenId, "screenId")) return null
        if (screenId != null) {
            val screenFile = File(savesDir, "${overlayId}_layout_${screenId}.json")
            if (screenFile.exists()) {
                return try {
                    gson.fromJson(screenFile.readText(), OverlayLayout::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load screen-specific layout for $overlayId/$screenId: ${e.message}")
                    null
                }
            }
        }
        val file = File(savesDir, "${overlayId}_layout.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), OverlayLayout::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load overlay layout for $overlayId: ${e.message}")
            null
        }
    }

    private fun parseProfile(file: File): ProfileConfig? {
        if (file.length() > ConfigConstants.MAX_CONFIG_FILE_SIZE) {
            Log.e(TAG, "Profile too large, skipping: ${file.name} (${file.length()} bytes)")
            return null
        }
        return try {
            val profile = gson.fromJson(file.readText(), ProfileConfig::class.java)
            // Gson bypasses Kotlin defaults; normalize null lists
            profile?.copy(
                gameIds = profile.gameIds ?: emptyList(),
                dataPoints = profile.dataPoints ?: emptyList(),
                macros = profile.macros ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing profile ${file.name}", e)
            null
        }
    }

    fun loadStoreWidgets(profileId: String): List<StoreWidget> {
        if (!validateId(profileId, "profileId")) return emptyList()
        val file = File(getWidgetsDir(), "$profileId/widgets.json")
        if (!file.exists() || file.length() > ConfigConstants.MAX_CONFIG_FILE_SIZE) return emptyList()
        return try {
            gson.fromJson<List<StoreWidget>>(file.readText(), StoreWidget.LIST_TYPE)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing widgets.json for $profileId", e)
            emptyList()
        }
    }

    fun hasInstalledWidgets(profileId: String): Boolean {
        if (!validateId(profileId, "profileId")) return false
        return File(getWidgetsDir(), "$profileId/widgets.json").exists()
    }

    fun isWidgetFileInstalled(profileId: String, src: String): Boolean {
        if (!validateId(profileId, "profileId")) return false
        return File(getWidgetsDir(), "$profileId/$src").exists()
    }

    fun deleteWidgetFile(profileId: String, src: String): Boolean {
        if (!validateId(profileId, "profileId")) return false
        val file = File(getWidgetsDir(), "$profileId/$src")
        if (!validateCanonicalPath(file, getWidgetsDir(), "Widget file")) return false
        return file.delete()
    }

    fun saveCustomOverlayConfig(config: CustomOverlayConfig) {
        if (!validateId(config.profileId, "profileId")) return
        synchronized(configLock) {
            try {
                savesDir.mkdirs()
                val fileName = "custom_${config.profileId}.json"
                val file = File(savesDir, fileName)
                atomicWrite(file, gson.toJson(config))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save custom overlay config for ${config.profileId}: ${e.message}")
            }
        }
    }

    fun loadCustomOverlayConfig(profileId: String): CustomOverlayConfig? {
        if (!validateId(profileId, "profileId")) return null
        val file = File(savesDir, "custom_$profileId.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), CustomOverlayConfig::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load custom overlay config for $profileId: ${e.message}")
            null
        }
    }

    fun deleteWidgets(profileId: String): Boolean {
        if (!validateId(profileId, "profileId")) return false
        val widgetDir = File(getWidgetsDir(), profileId)
        if (!validateCanonicalPath(widgetDir, getWidgetsDir(), "Widget folder")) return false
        return widgetDir.deleteRecursively()
    }

    // --- User Overlays (saved presets) ---

    fun saveUserOverlay(config: SavedOverlayConfig) {
        if (!validateId(config.id, "userOverlayId")) return
        synchronized(configLock) {
            try {
                userOverlaysDir.mkdirs()
                val file = File(userOverlaysDir, "${config.id}.json")
                atomicWrite(file, gson.toJson(config))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save user overlay ${config.id}: ${e.message}")
            }
        }
    }

    fun loadUserOverlays(): List<SavedOverlayConfig> {
        if (!userOverlaysDir.exists()) return emptyList()
        val files = userOverlaysDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            if (file.length() > ConfigConstants.MAX_CONFIG_FILE_SIZE) return@mapNotNull null
            try {
                gson.fromJson(file.readText(), SavedOverlayConfig::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse user overlay ${file.name}: ${e.message}")
                null
            }
        }
    }

    fun deleteUserOverlay(id: String): Boolean {
        if (!validateId(id, "userOverlayId")) return false
        synchronized(configLock) {
            val configFile = File(userOverlaysDir, "$id.json")
            val deleted = configFile.delete()
            // Clean up associated icon (any format) and layout files
            SavedOverlayConfig.ICON_EXTENSIONS.forEach { ext ->
                File(userOverlaysDir, "${id}_icon.$ext").delete()
            }
            savesDir.listFiles { f -> f.name.startsWith("${id}_layout") }?.forEach { it.delete() }
            return deleted
        }
    }

    fun copyOverlayLayout(sourceId: String, targetId: String, screenId: String?) {
        if (!validateId(sourceId, "sourceId") || !validateId(targetId, "targetId")) return
        if (screenId != null && !validateId(screenId, "screenId")) return
        synchronized(configLock) {
            val suffix = if (screenId != null) "_layout_${screenId}.json" else "_layout.json"
            val sourceFile = File(savesDir, "${sourceId}${suffix}")
            if (sourceFile.exists()) {
                try {
                    val targetFile = File(savesDir, "${targetId}${suffix}")
                    sourceFile.copyTo(targetFile, overwrite = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy layout $sourceId -> $targetId (screen=$screenId): ${e.message}")
                }
            }
        }
    }
}
