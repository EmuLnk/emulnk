package com.emulnk.ui.viewmodel

import android.app.Application
import com.emulnk.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emulnk.core.MemoryService
import com.emulnk.core.SyncService
import com.emulnk.core.TelemetryConstants
import com.emulnk.core.TelemetryService
import com.emulnk.data.ConfigManager
import com.emulnk.data.MemoryRepository
import com.emulnk.model.AppConfig
import com.emulnk.model.BatteryInfo
import com.emulnk.model.GameData
import com.emulnk.model.RepoIndex
import com.emulnk.model.RepoTheme
import com.emulnk.model.SystemInfo
import com.emulnk.model.ThermalInfo
import com.emulnk.model.ThemeConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_DEBUG_LOGS = 50

        fun generateMockGameData(settings: Map<String, String> = emptyMap()): GameData {
            return GameData(
                isConnected = true,
                values = mapOf(
                    "health" to 12.0,
                    "max_health" to 20,
                    "magic_current" to 24,
                    "magic_max" to 32,
                    "rupees" to 247,
                    "bombs" to 10,
                    "arrows" to 30,
                    "pos_x" to 50000.0f,
                    "pos_y" to 200.0f,
                    "pos_z" to -100000.0f,
                    "rotation_y" to 16384,
                    "wind_direction" to 0,
                    "stage_id_raw" to 1936024832L
                ),
                raw = mapOf(
                    "health" to 48,
                    "max_health" to 80,
                    "magic_current" to 24,
                    "magic_max" to 32,
                    "rupees" to 247,
                    "bombs" to 10,
                    "arrows" to 30,
                    "pos_x" to 50000.0f,
                    "pos_y" to 200.0f,
                    "pos_z" to -100000.0f,
                    "rotation_y" to 16384,
                    "wind_direction" to 0,
                    "stage_id_raw" to 1936024832L
                ),
                settings = settings,
                system = SystemInfo(
                    battery = BatteryInfo(level = 85, isCharging = false),
                    thermal = ThermalInfo(cpuTemp = 45.5f, isThrottling = false)
                )
            )
        }
    }

    private val configManager = ConfigManager(application)
    private val memoryService = MemoryService(MemoryRepository())
    private val telemetryService = TelemetryService(application)
    private val syncService = SyncService(configManager.getRootDir())
    private val gson = Gson()
    
    val uiState: StateFlow<GameData> = memoryService.uiState
    val detectedGameId: StateFlow<String?> = memoryService.detectedGameId
    val detectedConsole: StateFlow<String?> = memoryService.detectedConsole

    private val _availableThemes = MutableStateFlow<List<ThemeConfig>>(emptyList())
    val availableThemes: StateFlow<List<ThemeConfig>> = _availableThemes

    private val _allInstalledThemes = MutableStateFlow<List<ThemeConfig>>(emptyList())
    val allInstalledThemes: StateFlow<List<ThemeConfig>> = _allInstalledThemes

    private val _selectedTheme = MutableStateFlow<ThemeConfig?>(null)
    val selectedTheme: StateFlow<ThemeConfig?> = _selectedTheme

    private val _showSettingsEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showSettingsEvent: SharedFlow<Unit> = _showSettingsEvent

    private val _gameClosingEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gameClosingEvent: SharedFlow<Unit> = _gameClosingEvent

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _repoIndex = MutableStateFlow<RepoIndex?>(null)
    val repoIndex: StateFlow<RepoIndex?> = _repoIndex

    private val _appConfig = MutableStateFlow(configManager.getAppConfig())
    val appConfig: StateFlow<AppConfig> = _appConfig

    private val _rootPath = MutableStateFlow(configManager.getRootDir().absolutePath)
    val rootPath: StateFlow<String> = _rootPath

    private val _onboardingCompleted = MutableStateFlow(configManager.isOnboardingCompleted())
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted

    private val _isRootPathSet = MutableStateFlow(configManager.isRootPathSet())
    val isRootPathSet: StateFlow<Boolean> = _isRootPathSet

    init {
        refreshAllInstalledThemes()
        
        memoryService.start(configManager.getConsoleConfigs())

        viewModelScope.launch {
            var consecutiveTelemetryFailures = 0
            while (true) {
                try {
                    updateTelemetry()
                    consecutiveTelemetryFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveTelemetryFailures++
                    if (BuildConfig.DEBUG) {
                        android.util.Log.w(TAG, "Telemetry failure #$consecutiveTelemetryFailures: ${e.message}")
                    }
                    if (consecutiveTelemetryFailures >= 5) {
                        addDebugLog("Telemetry disabled after 5 consecutive failures")
                        break
                    }
                }
                delay(TelemetryConstants.UPDATE_INTERVAL_MS)
            }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(detectedGameId, detectedConsole, rootPath) { gameId, console, path ->
                Triple(gameId, console, path)
            }.debounce(300L).collectLatest { (gameId, console, path) ->
                if (gameId != null && console != null) {
                    refreshThemesForGame(gameId, console)
                    if (_appConfig.value.autoBoot) {
                        autoSelectTheme(gameId)
                    }
                } else {
                    if (_selectedTheme.value != null) {
                        _gameClosingEvent.tryEmit(Unit)
                        delay(2000) // Give theme time to show disconnection state
                    }
                    if (_appConfig.value.devMode) {
                        refreshThemesForDevMode()
                    } else {
                        _availableThemes.value = emptyList()
                    }
                    _selectedTheme.value = null
                }
            }
        }
    }

    fun refreshAllInstalledThemes() {
        _allInstalledThemes.value = configManager.getAvailableThemes()
    }

    private fun filterThemes(allThemes: List<ThemeConfig>, gameId: String?, console: String?): List<ThemeConfig> {
        val currentAppVersion = configManager.getAppVersionCode()
        val resolvedId = gameId?.let { configManager.resolveProfileId(it) }

        return allThemes.filter { theme ->
            val minVersion = theme.meta.minAppVersion ?: 1
            if (minVersion > currentAppVersion) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w(TAG, "Theme ${theme.id} skipped: Requires app v$minVersion")
                }
                return@filter false
            }

            if (gameId != null) {
                val themeTarget = theme.targetProfileId
                val matchesGame = resolvedId != null && resolvedId.equals(themeTarget, ignoreCase = true)

                val matchesConsole = theme.targetConsole == null || theme.targetConsole == console

                val matches = matchesGame && matchesConsole
                if (matches && BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "Match Found: ${theme.id} targets $themeTarget (resolved $gameId -> $resolvedId)")
                }
                return@filter matches
            }

            // Dev mode: all version-compatible themes
            true
        }
    }

    private fun refreshThemesForGame(gameId: String, console: String) {
        val allThemes = configManager.getAvailableThemes()
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Refreshing themes for $gameId ($console). Found ${allThemes.size} total themes.")
        }
        _availableThemes.value = filterThemes(allThemes, gameId, console)
    }

    private fun refreshThemesForDevMode() {
        val allThemes = configManager.getAvailableThemes()
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Dev Mode: Loading all ${allThemes.size} themes")
        }
        _availableThemes.value = filterThemes(allThemes, null, null)
    }

    private fun autoSelectTheme(gameId: String) {
        // Only auto-select if we haven't manually picked one or if the game changed
        val currentTheme = _selectedTheme.value
        val resolvedId = configManager.resolveProfileId(gameId)
        if (currentTheme == null || (resolvedId != null && !resolvedId.equals(currentTheme.targetProfileId, ignoreCase = true))) {
            val themes = _availableThemes.value
            if (themes.isNotEmpty()) {
                val defaultId = _appConfig.value.defaultThemes[gameId]
                val themeToSelect = themes.find { it.id == defaultId } ?: themes.first()
                selectTheme(themeToSelect)
            }
        }
    }

    fun setAutoBoot(enabled: Boolean) {
        val newConfig = _appConfig.value.copy(autoBoot = enabled)
        _appConfig.value = newConfig
        configManager.saveAppConfig(newConfig)
    }

    fun setRepoUrl(url: String) {
        val newConfig = _appConfig.value.copy(repoUrl = url)
        _appConfig.value = newConfig
        configManager.saveAppConfig(newConfig)
    }

    fun resetRepoUrl() {
        val defaultUrl = AppConfig().repoUrl
        setRepoUrl(defaultUrl)
    }

    fun setDevMode(enabled: Boolean) {
        val newConfig = _appConfig.value.copy(devMode = enabled)
        _appConfig.value = newConfig
        configManager.saveAppConfig(newConfig)
        val gameId = detectedGameId.value
        val console = detectedConsole.value
        if (gameId != null && console != null) {
            if (enabled) refreshThemesForDevMode() else refreshThemesForGame(gameId, console)
        } else if (enabled) {
            refreshThemesForDevMode()
        } else {
            _availableThemes.value = emptyList()
        }
    }

    fun setDevUrl(url: String) {
        val newConfig = _appConfig.value.copy(devUrl = url)
        _appConfig.value = newConfig
        configManager.saveAppConfig(newConfig)
    }

    fun completeOnboarding() {
        configManager.setOnboardingCompleted(true)
        _onboardingCompleted.value = true
        syncRepository()
    }

    fun getAppVersionCode() = configManager.getAppVersionCode()

    fun updateRootDirectory(path: String) {
        configManager.setRootDirectory(path)
        _rootPath.value = path
        _isRootPathSet.value = true
        syncService.updateRootDir(configManager.getRootDir())
        
        refreshAllInstalledThemes()
        
        // Refresh consoles and current game detection
        memoryService.stop()
        memoryService.start(configManager.getConsoleConfigs())
        
        // Reload AppConfig from the new location if it exists
        _appConfig.value = configManager.getAppConfig()
    }

    fun setDefaultThemeForGame(gameId: String, themeId: String) {
        val newDefaults = _appConfig.value.defaultThemes.toMutableMap()
        newDefaults[gameId] = themeId
        val newConfig = _appConfig.value.copy(defaultThemes = newDefaults)
        _appConfig.value = newConfig
        configManager.saveAppConfig(newConfig)
    }

    fun selectTheme(theme: ThemeConfig?) {
        _selectedTheme.value = theme
        
        if (theme == null) {
            memoryService.stopPolling()
            return
        }
        
        val saveFile = File(configManager.getSavesDir(), "${theme.id}.json")
        val currentSettings = if (saveFile.exists()) {
            try { 
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(saveFile.readText(), type) 
            } catch(_: Exception) { emptyMap<String, String>() }
        } else {
            theme.settings?.associate { it.id to it.default } ?: emptyMap()
        }

        memoryService.updateState { it.copy(settings = currentSettings) }

        // Mock data only when no game is detected (devMode controls WHERE themes load from, not WHAT data)
        val shouldInjectMock = detectedGameId.value == null

        if (shouldInjectMock) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(TAG, "Injecting mock data for preview (no game detected)")
            }
            memoryService.updateState { generateMockGameData(currentSettings) }
        } else {
            configManager.loadProfile(theme.targetProfileId)?.let {
                memoryService.setProfile(it)
            }
        }
    }

    fun updateThemeSetting(themeId: String, key: String, value: String) {
        val theme = _selectedTheme.value
        if (theme == null) {
            if (BuildConfig.DEBUG) {
                addDebugLog("Cannot update setting: no theme selected")
            }
            return
        }

        val settingSchema = theme.settings?.find { it.id == key }
        if (settingSchema == null) {
            if (BuildConfig.DEBUG) {
                addDebugLog("Unknown setting key: $key")
            }
            return
        }

        val newSettings = uiState.value.settings.toMutableMap()
        newSettings[key] = value
        memoryService.updateState { it.copy(settings = newSettings) }

        viewModelScope.launch(Dispatchers.IO) {
            val saveFile = File(configManager.getSavesDir(), "${themeId}.json")
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(newSettings))
        }
    }

    fun updateSystemInfo(info: SystemInfo) {
        memoryService.updateState { it.copy(system = info) }
    }

    private fun updateTelemetry() {
        try {
            val battery = telemetryService.getBatteryInfo()
            val thermal = telemetryService.getThermalInfo()
            memoryService.updateState { 
                it.copy(system = it.system.copy(battery = battery, thermal = thermal))
            }
        } catch (e: Exception) {
            viewModelScope.launch { addDebugLog("Telemetry Error: ${e.message}") }
        }
    }

    fun fetchGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            val index = syncService.fetchRepoIndex(_appConfig.value.repoUrl)
            _repoIndex.value = index
        }
    }

    fun downloadTheme(repoTheme: RepoTheme) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "Downloading ${repoTheme.name}..."

        val themePrefix = "themes/${repoTheme.id}/"
        viewModelScope.launch(Dispatchers.IO) {
            val success = syncService.downloadAndExtract(
                url = _appConfig.value.repoUrl,
                stripRoot = true,
                pathFilter = { path -> path.startsWith(themePrefix) }
            ) { message ->
                _syncMessage.value = message
                viewModelScope.launch { addDebugLog(message) }
            }

            _isSyncing.value = false

            if (success) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG, "Theme download successful: ${repoTheme.id}")
                }
                refreshAllInstalledThemes()

                val gameId = detectedGameId.value
                val console = detectedConsole.value
                if (gameId != null && console != null) {
                    refreshThemesForGame(gameId, console)
                }
            }
        }
    }

    fun deleteTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (configManager.deleteTheme(themeId)) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG, "Theme deleted: $themeId")
                }

                refreshAllInstalledThemes()

                val gameId = detectedGameId.value
                val console = detectedConsole.value
                if (gameId != null && console != null) {
                    refreshThemesForGame(gameId, console)
                }
            }
        }
    }

    fun importTheme(uri: android.net.Uri) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "Importing theme..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    val success = syncService.unzipStream(
                        inputStream = stream,
                        targetDir = configManager.getThemesDir(),
                        stripRoot = false,
                        onProgress = { message ->
                            _syncMessage.value = message
                        }
                    )

                    if (success) {
                        refreshAllInstalledThemes()
                        val gameId = detectedGameId.value
                        val console = detectedConsole.value
                        if (gameId != null && console != null) {
                            refreshThemesForGame(gameId, console)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import Error", e)
                _syncMessage.value = "Import Failed: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun syncRepository() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "Syncing configs..."

        val currentRootDir = configManager.getRootDir()
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Initiating sync. RootDir: ${currentRootDir.absolutePath}")
        }

        viewModelScope.launch(Dispatchers.IO) {
            val success = syncService.downloadAndExtract(
                url = _appConfig.value.repoUrl,
                stripRoot = true,
                pathFilter = { path ->
                    path == "index.json" ||
                    path == "consoles.json" ||
                    path.startsWith("profiles/")
                }
            ) { message ->
                _syncMessage.value = message
                viewModelScope.launch { addDebugLog(message) }
            }
            
            _isSyncing.value = false

            if (success) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG, "Sync successful, refreshing configs.")
                }
                refreshAllInstalledThemes()
                
                viewModelScope.launch {
                    memoryService.stop()
                    memoryService.start(configManager.getConsoleConfigs())
                    
                    val gameId = detectedGameId.value
                    val console = detectedConsole.value
                    if (gameId != null && console != null) {
                        refreshThemesForGame(gameId, console)
                    }
                }
            } else {
                android.util.Log.e(TAG, "Sync failed.")
            }
        }
    }

    fun addDebugLog(message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "DEBUG: $message")
        }
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = _debugLogs.value.toMutableList()
        current.add(0, "$timestamp: $message")

        if (current.size > MAX_DEBUG_LOGS) {
            current.subList(MAX_DEBUG_LOGS, current.size).clear()
        }

        _debugLogs.value = current
    }

    fun requestSettings() {
        viewModelScope.launch { _showSettingsEvent.emit(Unit) }
    }

    fun writeMemory(address: Long, data: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryService.writeMemory(address, data)
        }
    }
    
    fun writeVariable(varId: String, value: Int) = memoryService.writeVariable(varId, value)
    
    fun runMacro(macroId: String) = memoryService.runMacro(macroId) { addDebugLog(it) }

    override fun onCleared() {
        super.onCleared()
        memoryService.close()
        syncService.close()
    }
}
