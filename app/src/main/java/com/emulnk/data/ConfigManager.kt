package com.emulnk.data

import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.emulnk.BuildConfig
import com.emulnk.model.AppConfig
import com.emulnk.model.ConsoleConfig
import com.emulnk.model.ProfileConfig
import com.emulnk.model.ThemeConfig
import com.google.gson.Gson
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

    private var rootDir: File
    private var themesDir: File
    private var profilesDir: File
    private var savesDir: File
    private var consolesFile: File
    private var appConfigFile: File

    init {
        val savedRoot = prefs.getString("root_uri", null)
        rootDir = if (savedRoot != null) File(savedRoot) 
                  else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "EmuLink")
        
        themesDir = File(rootDir, "themes")
        profilesDir = File(rootDir, "profiles")
        savesDir = File(rootDir, "saves")
        consolesFile = File(rootDir, "consoles.json")
        appConfigFile = File(rootDir, "app_settings.json")
        
        if (savedRoot != null) ensureDirs()
    }

    fun setRootDirectory(path: String) {
        prefs.edit { putString("root_uri", path) }
        rootDir = File(path)
        themesDir = File(rootDir, "themes")
        profilesDir = File(rootDir, "profiles")
        savesDir = File(rootDir, "saves")
        consolesFile = File(rootDir, "consoles.json")
        appConfigFile = File(rootDir, "app_settings.json")
        ensureDirs()
    }

    fun getRootDir(): File = rootDir
    fun getThemesDir(): File = themesDir
    fun getSavesDir(): File = savesDir
    fun isRootPathSet(): Boolean = prefs.contains("root_uri")

    fun getAppVersionCode(): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to read app version code: ${e.message}")
            }
            1
        }
    }

    private fun ensureDirs() {
        if (!themesDir.exists()) themesDir.mkdirs()
        if (!profilesDir.exists()) profilesDir.mkdirs()
        if (!savesDir.exists()) savesDir.mkdirs()
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)
    
    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit { putBoolean("onboarding_completed", completed) }
    }

    fun getAppConfig(): AppConfig {
        return if (appConfigFile.exists()) {
            try {
                gson.fromJson(appConfigFile.readText(), AppConfig::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse app_settings.json", e)
                // Backup corrupted file before returning defaults
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
    }

    fun saveAppConfig(config: AppConfig) {
        if (!rootDir.exists()) return // Don't save to non-existent default root
        synchronized(configLock) {
            try {
                val tmpFile = File(appConfigFile.parent, "app_settings.json.tmp")
                tmpFile.writeText(gson.toJson(config))
                if (!tmpFile.renameTo(appConfigFile)) {
                    // renameTo can fail on some filesystems; fall back to copy + delete
                    tmpFile.copyTo(appConfigFile, overwrite = true)
                    tmpFile.delete()
                }
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
        val defaults = listOf(
            ConsoleConfig(
                id = "dolphin_gcn",
                name = "Dolphin (GameCube)",
                packageNames = listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinmmjr"),
                console = "GCN",
                port = 55355,
                idAddress = "0x80000000"
            ),
            ConsoleConfig(
                id = "dolphin_wii",
                name = "Dolphin (Wii)",
                packageNames = listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinmmjr"),
                console = "WII",
                port = 55356,
                idAddress = "0x80000000"
            )
        )
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
        // Reject path traversal characters
        if (themeId.contains('/') || themeId.contains('\\') || themeId.contains("..")) {
            Log.e(TAG, "Invalid themeId rejected (path traversal attempt): $themeId")
            return false
        }

        val themeFolder = File(themesDir, themeId)

        // Validate canonical path stays within themesDir
        val canonicalThemesDir = themesDir.canonicalPath
        val canonicalThemeFolder = themeFolder.canonicalPath
        if (!canonicalThemeFolder.startsWith(canonicalThemesDir + File.separator)) {
            Log.e(TAG, "Theme folder escapes themes directory: $canonicalThemeFolder")
            return false
        }

        return if (themeFolder.exists()) {
            themeFolder.deleteRecursively()
        } else false
    }

    private fun loadThemeConfig(folder: File): ThemeConfig? {
        val configFile = File(folder, "theme.json")
        if (!configFile.exists()) {
            Log.w(TAG, "theme.json missing in ${folder.name}")
            return null
        }
        
        return try {
            val json = configFile.readText()
            gson.fromJson(json, ThemeConfig::class.java).copy(id = folder.name)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing theme.json in ${folder.name}", e)
            null
        }
    }

    fun loadProfile(profileId: String): ProfileConfig? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loading profile for: $profileId")
        }

        // 1. Try exact match (e.g., GZLE01.json)
        var file = File(profilesDir, "$profileId.json")
        if (file.exists()) return parseProfile(file)

        // 2. Try short match (e.g., GZLE.json)
        if (profileId.length >= 4) {
            file = File(profilesDir, "${profileId.take(4)}.json")
            if (file.exists()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Found profile via 4-char match: ${file.name}")
                }
                return parseProfile(file)
            }
        }

        // 3. Try series match (e.g., GZL.json)
        if (profileId.length >= 3) {
            file = File(profilesDir, "${profileId.take(3)}.json")
            if (file.exists()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Found profile via series match: ${file.name}")
                }
                return parseProfile(file)
            }
        }

        Log.e(TAG, "No profile found for $profileId in ${profilesDir.absolutePath}")
        return null
    }

    private fun parseProfile(file: File): ProfileConfig? {
        return try {
            gson.fromJson(file.readText(), ProfileConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing profile ${file.name}", e)
            null
        }
    }
}
