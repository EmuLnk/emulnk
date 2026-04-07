package com.emulnk.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emulnk.BuildConfig
import com.emulnk.EmuLnkApplication
import com.emulnk.core.DisplayHelper
import com.emulnk.core.OverlayService
import com.emulnk.core.SyncService
import com.emulnk.core.TelemetryConstants
import com.emulnk.core.TelemetryService
import com.emulnk.core.constants.MemoryConstants
import com.emulnk.core.model.BatteryInfo
import com.emulnk.core.model.GameData
import com.emulnk.core.model.MatchConfidence
import com.emulnk.core.model.SystemInfo
import com.emulnk.core.model.ThermalInfo
import com.emulnk.data.ConfigManager
import com.emulnk.data.MigrationManager
import com.emulnk.model.AppConfig
import com.emulnk.model.CustomOverlayConfig
import com.emulnk.model.GalleryIndex
import com.emulnk.model.GalleryTheme
import com.emulnk.model.OverlayBundle
import com.emulnk.model.SavedOverlayConfig
import com.emulnk.model.ScreenTarget
import com.emulnk.model.StoreWidget
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeMeta
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedScreenTarget
import com.emulnk.model.resolvedType
import com.emulnk.model.toWidgetConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

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

    private fun isValidId(id: String): Boolean {
        return id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")
    }
    private val memoryService = getApplication<EmuLnkApplication>().memoryService
    private val telemetryService = TelemetryService(application)
    private val syncService = SyncService(configManager.getRootDir())
    private val gson = Gson()

    private val _isDualScreen = MutableStateFlow(DisplayHelper.isDualScreen(application))
    val isDualScreen: StateFlow<Boolean> = _isDualScreen
    
    val uiState: StateFlow<GameData> = memoryService.uiState
    val detectedGameId: StateFlow<String?> = memoryService.detectedGameId
    val detectedConsole: StateFlow<String?> = memoryService.detectedConsole
    val detectedGameHash: StateFlow<String?> = memoryService.detectedGameHash

    private val _availableThemes = MutableStateFlow<List<ThemeConfig>>(emptyList())
    val availableThemes: StateFlow<List<ThemeConfig>> = _availableThemes

    private val _userOverlayThemes = MutableStateFlow<List<ThemeConfig>>(emptyList())
    val userOverlayThemes: StateFlow<List<ThemeConfig>> = _userOverlayThemes

    private val _allInstalledThemes = MutableStateFlow<List<ThemeConfig>>(emptyList())
    val allInstalledThemes: StateFlow<List<ThemeConfig>> = _allInstalledThemes

    private val _selectedTheme = MutableStateFlow<ThemeConfig?>(null)
    val selectedTheme: StateFlow<ThemeConfig?> = _selectedTheme

    private val _pairedOverlay = MutableStateFlow<ThemeConfig?>(null)
    val pairedOverlay: StateFlow<ThemeConfig?> = _pairedOverlay

    private val _pairedSecondaryOverlay = MutableStateFlow<ThemeConfig?>(null)
    val pairedSecondaryOverlay: StateFlow<ThemeConfig?> = _pairedSecondaryOverlay

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _repoIndex = MutableStateFlow<GalleryIndex?>(null)
    val repoIndex: StateFlow<GalleryIndex?> = _repoIndex

    private val _storeWidgets = MutableStateFlow<Map<String, List<StoreWidget>>>(emptyMap())
    val storeWidgets: StateFlow<Map<String, List<StoreWidget>>> = _storeWidgets

    private val _installedWidgetProfiles = MutableStateFlow<Set<String>>(emptySet())
    val installedWidgetProfiles: StateFlow<Set<String>> = _installedWidgetProfiles

    private val _widgetUpdatesAvailable = MutableStateFlow<Set<String>>(emptySet())
    val widgetUpdatesAvailable: StateFlow<Set<String>> = _widgetUpdatesAvailable

    private val _installedWidgetIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val installedWidgetIds: StateFlow<Map<String, Set<String>>> = _installedWidgetIds

    private val _resolvedProfileId = MutableStateFlow<String?>(null)
    val resolvedProfileId: StateFlow<String?> = _resolvedProfileId

    private var _lastDetectedHash: String? = null

    private val _savedOverlays = MutableStateFlow<List<SavedOverlayConfig>>(emptyList())
    val savedOverlays: StateFlow<List<SavedOverlayConfig>> = _savedOverlays

    private val _checkedWidgetProfiles: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var settingsSaveJob: Job? = null
    private val _currentConfidence = MutableStateFlow(MatchConfidence.MATCHED)
    val currentConfidence: StateFlow<MatchConfidence> = _currentConfidence

    private val _detectedGameLabel = MutableStateFlow<String?>(null)
    val detectedGameLabel: StateFlow<String?> = _detectedGameLabel

    private val _rawBaseUrl = MutableStateFlow("")
    val rawBaseUrl: StateFlow<String> = _rawBaseUrl

    private val _appConfig = MutableStateFlow(configManager.getAppConfig())
    val appConfig: StateFlow<AppConfig> = _appConfig

    /** True when device can display secondary-screen content (physical dual-screen OR remote host). */
    val canShowSecondaryContent: StateFlow<Boolean> = combine(_isDualScreen, _appConfig) { dual, config ->
        dual || config.isRemoteHost
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _isDualScreen.value)

    private val _rootPath = MutableStateFlow(configManager.getRootDir().absolutePath)
    val rootPath: StateFlow<String> = _rootPath

    private val _onboardingCompleted = MutableStateFlow(configManager.isOnboardingCompleted())
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted

    private val _isRootPathSet = MutableStateFlow(configManager.isRootPathSet())
    val isRootPathSet: StateFlow<Boolean> = _isRootPathSet

    init {
        MigrationManager(application, configManager).runIfNeeded()
        configManager.loadHashRegistry()
        refreshAllInstalledThemes()
        refreshSavedOverlays()

        memoryService.start(configManager.getConsoleConfigs())

        if (configManager.isOnboardingCompleted()) {
            viewModelScope.launch(Dispatchers.IO) {
                syncRepository()
            }
        }

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
            kotlinx.coroutines.flow.combine(detectedGameId, detectedConsole, rootPath, memoryService.detectedGameHash) { gameId, console, path, hash ->
                arrayOf(gameId, console, path, hash)
            }.debounce(300L).collectLatest { args ->
                val gameId = args[0] as? String
                val console = args[1] as? String
                val hash = args[3] as? String
                if (gameId != null && console != null) {
                    var romSwapped = false
                    // ROM swap: hash changed while theme is active → stop overlay + deselect
                    if (hash != null && _lastDetectedHash != null && hash != _lastDetectedHash && _selectedTheme.value != null) {
                        stopOverlayService()
                        _selectedTheme.value = null
                        _pairedOverlay.value = null
                        _pairedSecondaryOverlay.value = null
                        romSwapped = true
                    }
                    if (hash != null) _lastDetectedHash = hash

                    val resolver = configManager.createProfileResolver()
                    val result = resolver.resolve(hash, gameId)
                    _resolvedProfileId.value = result?.profile?.id
                    _currentConfidence.value = result?.confidence ?: MatchConfidence.UNKNOWN
                    val entry = result?.hashEntry
                    _detectedGameLabel.value = when {
                        entry?.label != null && entry.version != null -> "${entry.label} (${entry.version})"
                        entry?.label != null -> entry.label
                        else -> null
                    }
                    refreshThemesForGame(gameId, console)
                    if (_appConfig.value.autoBoot) {
                        if (romSwapped) delay(500) // let overlay service stop before reselection
                        autoSelectPair(gameId)
                    }
                } else {
                    _resolvedProfileId.value = null
                    // _lastDetectedHash intentionally kept. collectLatest may cancel this branch
                    // during a ROM swap, and the if-branch needs the old hash for comparison.
                    _detectedGameLabel.value = null
                    if (_selectedTheme.value != null) {
                        delay(2000) // Give theme time to show disconnection state
                    }
                    if (_appConfig.value.devMode) {
                        refreshThemesForDevMode()
                    } else {
                        _availableThemes.value = emptyList()
                        _userOverlayThemes.value = emptyList()
                    }
                    _selectedTheme.value = null
                    _pairedOverlay.value = null
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
        val parentId = resolvedId?.let { configManager.getParentProfileId(it) }
        val canShowSecondary = canShowSecondaryContent.value

        return allThemes.filter { theme ->
            val minVersion = theme.meta.minAppVersion ?: 1
            if (minVersion > currentAppVersion) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w(TAG, "Theme ${theme.id} skipped: Requires app v$minVersion")
                }
                return@filter false
            }

            if (!canShowSecondary && theme.resolvedType != ThemeType.OVERLAY) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "Theme ${theme.id} skipped: single-screen only shows overlays")
                }
                return@filter false
            }

            if (gameId != null) {
                val themeTarget = theme.targetProfileId
                val matchesGame = resolvedId != null && (
                    resolvedId.equals(themeTarget, ignoreCase = true) ||
                    parentId?.equals(themeTarget, ignoreCase = true) == true
                )

                val matchesConsole = theme.targetConsole == null || theme.targetConsole == console

                return@filter matchesGame && matchesConsole
            }

            // Dev mode passes gameId=null → show all version-compatible themes
            true
        }
    }

    private fun refreshThemesForGame(gameId: String, console: String) {
        val allThemes = _allInstalledThemes.value
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Refreshing themes for $gameId ($console). Found ${allThemes.size} total themes.")
        }
        val filtered = filterThemes(allThemes, gameId, console).toMutableList()

        val resolvedId = configManager.resolveProfileId(gameId)
        if (resolvedId != null && configManager.hasInstalledWidgets(resolvedId)) {
            if (!_storeWidgets.value.containsKey(resolvedId)) {
                val local = configManager.loadStoreWidgets(resolvedId)
                if (local.isNotEmpty()) {
                    _storeWidgets.value = _storeWidgets.value + (resolvedId to local)
                }
            }
            _installedWidgetProfiles.value = _installedWidgetProfiles.value + resolvedId
            refreshInstalledWidgetIds(resolvedId)
        }

        // Build user overlay themes separately
        val userOverlays = mutableListOf<ThemeConfig>()
        for (saved in _savedOverlays.value) {
            if (saved.profileId == resolvedId && (saved.console == null || saved.console == console)) {
                buildSavedOverlayThemeConfig(saved, console)?.let { userOverlays.add(it) }
            }
        }

        _availableThemes.value = filtered
        _userOverlayThemes.value = userOverlays
    }

    private fun refreshThemesForDevMode() {
        val allThemes = _allInstalledThemes.value
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Dev Mode: Loading all ${allThemes.size} themes")
        }
        _availableThemes.value = filterThemes(allThemes, null, null)

        val userOverlays = mutableListOf<ThemeConfig>()
        for (saved in _savedOverlays.value) {
            buildSavedOverlayThemeConfig(saved, saved.console ?: "")?.let { userOverlays.add(it) }
        }
        _userOverlayThemes.value = userOverlays
    }

    private fun stopOverlayService() {
        val app = getApplication<Application>()
        val stop = Intent(app, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        app.startService(stop)
        val bringBack = Intent(app, com.emulnk.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        app.startActivity(bringBack)
    }

    private fun autoSelectPair(gameId: String) {
        val currentTheme = _selectedTheme.value
        val resolvedId = configManager.resolveProfileId(gameId)
        if (currentTheme == null || (resolvedId != null &&
            !resolvedId.equals(currentTheme.targetProfileId, ignoreCase = true))) {
            val themes = _availableThemes.value + _userOverlayThemes.value
            if (themes.isEmpty()) return

            // Check for saved overlay bundle first (dual-screen pairing)
            val defaultBundle = _appConfig.value.defaultBundles[gameId]
            if (defaultBundle != null && canShowSecondaryContent.value) {
                val primary = defaultBundle.primaryOverlayId?.let { id -> themes.find { it.id == id } }
                val secondary = defaultBundle.secondaryOverlayId?.let { id -> themes.find { it.id == id } }
                if (primary != null || secondary != null) {
                    val theme = listOfNotNull(primary, secondary).find { it.resolvedType == ThemeType.THEME }
                    val overlays = listOfNotNull(primary, secondary).filter { it.resolvedType == ThemeType.OVERLAY }
                    // Dispatch based on bundle composition: theme+overlay, theme-only, or overlay-bundle
                    when {
                        theme != null && overlays.isNotEmpty() -> {
                            val overlay = overlays.first()
                            val overlayTarget = if (defaultBundle.primaryOverlayId == overlay.id)
                                ScreenTarget.PRIMARY else ScreenTarget.SECONDARY
                            selectPair(theme, overlay.copy(screenTarget = overlayTarget))
                        }
                        theme != null -> selectPair(theme, null)
                        else -> selectOverlayBundle(primary, secondary)
                    }
                    return
                }
            }

            val defaultThemeId = _appConfig.value.defaultThemes[gameId]
            val defaultOverlayId = (_appConfig.value.defaultOverlays ?: emptyMap())[gameId]
            val themeToSelect = themes.find { it.id == defaultThemeId } ?: themes.first()

            when (themeToSelect.resolvedType) {
                ThemeType.BUNDLE -> selectPair(themeToSelect, null)
                ThemeType.OVERLAY -> {
                    if (canShowSecondaryContent.value) {
                        if (themeToSelect.resolvedScreenTarget == ScreenTarget.SECONDARY) {
                            selectOverlayBundle(null, themeToSelect)
                        } else {
                            selectOverlayBundle(themeToSelect, null)
                        }
                    } else {
                        selectPair(null, themeToSelect)
                    }
                }
                else -> {
                    val overlayToSelect = defaultOverlayId?.let { id ->
                        themes.find { it.id == id && it.resolvedType == ThemeType.OVERLAY }
                    }
                    selectPair(themeToSelect, overlayToSelect)
                }
            }
        }
    }

    fun setAutoBoot(enabled: Boolean) {
        _appConfig.update { it.copy(autoBoot = enabled) }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun setRepoUrl(url: String) {
        _appConfig.update { it.copy(repoUrl = url) }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun resetRepoUrl() {
        val defaultUrl = AppConfig().repoUrlShim
        setRepoUrl(defaultUrl)
    }

    fun setDevMode(enabled: Boolean) {
        _appConfig.update { it.copy(devMode = enabled) }
        configManager.saveAppConfig(_appConfig.value)
        val gameId = detectedGameId.value
        val console = detectedConsole.value
        if (gameId != null && console != null) {
            refreshThemesForGame(gameId, console)
        } else if (enabled) {
            refreshThemesForDevMode()
        } else {
            _availableThemes.value = emptyList()
            _userOverlayThemes.value = emptyList()
        }
    }

    fun setDevUrl(url: String) {
        _appConfig.update { it.copy(devUrl = url) }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun setEmulatorHost(host: String) {
        _appConfig.update { it.copy(emulatorHost = host) }
        configManager.saveAppConfig(_appConfig.value)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryService.setHost(host)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Failed to apply emulator host: ${e.message}")
            }
        }
        val gameId = detectedGameId.value
        val console = detectedConsole.value
        if (gameId != null && console != null) refreshThemesForGame(gameId, console)
    }

    fun resetEmulatorHost() {
        setEmulatorHost(AppConfig().emulatorHost)
    }

    fun completeOnboarding() {
        configManager.setOnboardingCompleted(true)
        _onboardingCompleted.value = true
        syncRepository()
    }

    fun getAppVersionCode() = configManager.getAppVersionCode()
    fun getParentProfileId(profileId: String): String? = configManager.getParentProfileId(profileId)

    fun getThemeById(id: String): ThemeConfig? {
        return _allInstalledThemes.value.find { it.id == id }
    }

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
        _appConfig.update { current ->
            val newDefaults = current.defaultThemes.toMutableMap()
            newDefaults[gameId] = themeId
            current.copy(defaultThemes = newDefaults)
        }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun setDefaultPairForGame(gameId: String, themeId: String?, overlayId: String?) {
        _appConfig.update { current ->
            val newThemes = current.defaultThemes.toMutableMap()
            val newOverlays = (current.defaultOverlays ?: emptyMap()).toMutableMap()

            if (themeId != null) newThemes[gameId] = themeId else newThemes.remove(gameId)

            // Bundles are exclusive, clear overlay default
            if (themeId != null && getThemeById(themeId)?.resolvedType == ThemeType.BUNDLE) {
                newOverlays.remove(gameId)
            } else {
                if (overlayId != null) newOverlays[gameId] = overlayId else newOverlays.remove(gameId)
            }

            current.copy(defaultThemes = newThemes, defaultOverlays = newOverlays)
        }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun setDefaultBundleForGame(gameId: String, primaryId: String?, secondaryId: String?) {
        _appConfig.update { current ->
            val newBundles = current.defaultBundles.toMutableMap()
            if (primaryId != null || secondaryId != null) {
                newBundles[gameId] = OverlayBundle(
                    primaryOverlayId = primaryId,
                    secondaryOverlayId = secondaryId
                )
            } else {
                newBundles.remove(gameId)
            }
            current.copy(defaultBundles = newBundles)
        }
        configManager.saveAppConfig(_appConfig.value)
    }

    fun selectTheme(theme: ThemeConfig?) {
        _selectedTheme.value = theme
        _pairedOverlay.value = null
        _pairedSecondaryOverlay.value = null
        if (theme == null) { memoryService.stopPolling(); return }
        loadAndApplyThemeSettings(theme)
    }

    fun selectPair(theme: ThemeConfig?, overlay: ThemeConfig?) {
        if (theme == null && overlay != null) {
            // Overlay-only, treat overlay as the selected theme
            _selectedTheme.value = overlay
            _pairedOverlay.value = null
        } else {
            _selectedTheme.value = theme
            _pairedOverlay.value = overlay
        }
        _pairedSecondaryOverlay.value = null
        if (theme == null && overlay == null) { memoryService.stopPolling(); return }
        (theme ?: overlay)?.let { loadAndApplyThemeSettings(it) }
    }

    fun selectOverlayBundle(primary: ThemeConfig?, secondary: ThemeConfig?) {
        if (primary == null && secondary == null) {
            _selectedTheme.value = null
            _pairedOverlay.value = null
            _pairedSecondaryOverlay.value = null
            memoryService.stopPolling()
            return
        }
        if (primary == null && secondary != null) {
            // Solo overlay (no companion), launch as single overlay
            _selectedTheme.value = secondary
            _pairedOverlay.value = null
            _pairedSecondaryOverlay.value = null
        } else {
            _selectedTheme.value = primary
            _pairedOverlay.value = null
            _pairedSecondaryOverlay.value = secondary
        }
        (primary ?: secondary)?.let { loadAndApplyThemeSettings(it) }
    }

    private fun loadAndApplyThemeSettings(theme: ThemeConfig) {
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

        // devMode affects theme listing, not data. Mock data is injected when no game is detected
        val shouldInjectMock = detectedGameId.value == null

        if (shouldInjectMock) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(TAG, "Injecting mock data for preview (no game detected)")
            }
            memoryService.updateState { generateMockGameData(currentSettings) }
        } else {
            // Prefer hash-resolved profile (may differ from theme target, e.g. ROM hack)
            val hash = memoryService.detectedGameHash.value
            val gameId = detectedGameId.value
            val resolver = configManager.createProfileResolver()
            val resolved = resolver.resolve(hash, gameId)
            val profile = resolved?.profile ?: configManager.loadProfile(theme.targetProfileId)
            val confidence = resolved?.confidence ?: _currentConfidence.value

            if (profile != null) {
                val consoleInterval = configManager.getConsoleConfigs()
                    .firstOrNull { it.console == detectedConsole.value }
                    ?.minPollingInterval
                val effectiveInterval = maxOf(
                    consoleInterval ?: MemoryConstants.MIN_POLLING_INTERVAL_MS,
                    theme.pollingInterval ?: MemoryConstants.POLLING_INTERVAL_MS
                ).coerceIn(MemoryConstants.MIN_POLLING_INTERVAL_MS, MemoryConstants.MAX_POLLING_INTERVAL_MS)
                memoryService.setProfile(
                    profile, effectiveInterval,
                    confidence = confidence,
                    uses = theme.uses?.toSet()
                )
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

        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            try {
                val saveFile = File(configManager.getSavesDir(), "${themeId}.json")
                saveFile.parentFile?.mkdirs()
                saveFile.writeText(gson.toJson(newSettings))
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Failed to save theme settings: ${e.message}")
                }
            }
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
            val repoUrl = _appConfig.value.repoUrlShim
            _rawBaseUrl.value = syncService.deriveRawBaseUrl(repoUrl)
            val index = syncService.fetchRepoIndex(repoUrl)
            _repoIndex.value = index
        }
    }

    fun downloadTheme(theme: GalleryTheme) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "Downloading ${theme.name}..."

        // v2 repo path: themes/{console}/{profileId}/{themeId}/
        val themePrefix = "themes/${theme.console}/${theme.profileId}/${theme.id}/"
        // Composite local path: themes/{profileId}_{themeId}/
        val localPrefix = "themes/${theme.profileId}_${theme.id}/"
        viewModelScope.launch(Dispatchers.IO) {
            val success = syncService.downloadAndExtract(
                url = getSyncUrl(),
                stripRoot = false,
                pathFilter = { path -> path.startsWith(themePrefix) },
                pathRewriter = { path -> localPrefix + path.removePrefix(themePrefix) }
            ) { message ->
                _syncMessage.value = message
                viewModelScope.launch { addDebugLog(message) }
            }

            _isSyncing.value = false

            if (success) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG, "Theme download successful: ${theme.id}")
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

    fun fetchWidgetsForGame(console: String, profileId: String) {
        if (_storeWidgets.value.containsKey(profileId)) {
            if (profileId in _installedWidgetProfiles.value) {
                checkForWidgetUpdates(console, profileId)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Try local cache first
            val local = configManager.loadStoreWidgets(profileId)
            if (local.isNotEmpty()) {
                _storeWidgets.value = _storeWidgets.value + (profileId to local)
                _installedWidgetProfiles.value = _installedWidgetProfiles.value + profileId
                refreshInstalledWidgetIds(profileId)
                checkForWidgetUpdates(console, profileId)
                return@launch
            }
            // Fetch from remote
            val rawUrl = _rawBaseUrl.value
            if (rawUrl.isBlank()) return@launch
            val widgets = syncService.fetchWidgetsJson(rawUrl, console, profileId)
            if (widgets != null) {
                _storeWidgets.value = _storeWidgets.value + (profileId to widgets)
            }
        }
    }

    private fun checkForWidgetUpdates(console: String, profileId: String) {
        if (!_checkedWidgetProfiles.add(profileId)) return
        viewModelScope.launch(Dispatchers.IO) {
            val rawUrl = _rawBaseUrl.value
            if (rawUrl.isBlank()) return@launch
            val remote = syncService.fetchWidgetsJson(rawUrl, console, profileId) ?: return@launch
            val local = _storeWidgets.value[profileId] ?: return@launch
            if (remote != local) {
                _widgetUpdatesAvailable.value = _widgetUpdatesAvailable.value + profileId
            }
        }
    }

    fun downloadWidgets(console: String, profileId: String) {
        if (!isValidId(profileId)) return
        downloadWidgetsInternal(console, profileId, "Downloading widgets...")
    }

    fun updateWidgets(console: String, profileId: String) {
        if (!isValidId(profileId)) return
        downloadWidgetsInternal(console, profileId, "Updating widgets...") {
            _widgetUpdatesAvailable.value = _widgetUpdatesAvailable.value - profileId
        }
    }

    private fun downloadWidgetsInternal(
        console: String,
        profileId: String,
        statusMessage: String,
        onSuccess: (() -> Unit)? = null
    ) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = statusMessage

        val widgetPrefix = "themes/$console/$profileId/widgets/"
        val localPrefix = "widgets/$profileId/"
        viewModelScope.launch(Dispatchers.IO) {
            val success = syncService.downloadAndExtract(
                url = getSyncUrl(),
                stripRoot = false,
                pathFilter = { path -> path.startsWith(widgetPrefix) },
                pathRewriter = { path -> localPrefix + path.removePrefix(widgetPrefix) }
            ) { message ->
                _syncMessage.value = message
                viewModelScope.launch { addDebugLog(message) }
            }

            _isSyncing.value = false

            if (success) {
                _installedWidgetProfiles.value = _installedWidgetProfiles.value + profileId
                val widgets = configManager.loadStoreWidgets(profileId)
                if (widgets.isNotEmpty()) {
                    _storeWidgets.value = _storeWidgets.value + (profileId to widgets)
                }
                refreshInstalledWidgetIds(profileId)
                onSuccess?.invoke()
            }
        }
    }

    fun getInstalledStoreWidgets(profileId: String): List<StoreWidget> {
        val all = _storeWidgets.value[profileId] ?: return emptyList()
        val installed = _installedWidgetIds.value[profileId] ?: return emptyList()
        return all.filter { it.id in installed }
    }

    fun refreshInstalledWidgetIds(profileId: String) {
        val widgets = _storeWidgets.value[profileId] ?: return
        val installed = widgets.filter { configManager.isWidgetFileInstalled(profileId, it.src) }.map { it.id }.toSet()
        _installedWidgetIds.value = _installedWidgetIds.value + (profileId to installed)
    }

    fun downloadSingleWidget(console: String, profileId: String, widget: StoreWidget) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "Downloading ${widget.label}..."
        viewModelScope.launch(Dispatchers.IO) {
            val rawUrl = _rawBaseUrl.value
            if (rawUrl.isNotBlank()) {
                val remoteUrl = "$rawUrl/themes/$console/$profileId/widgets/${widget.src}"
                val localFile = File(configManager.getWidgetsDir(), "$profileId/${widget.src}")
                val success = syncService.downloadSingleFile(remoteUrl, localFile)
                if (success) {
                    if (!configManager.hasInstalledWidgets(profileId)) {
                        val jsonUrl = "$rawUrl/themes/$console/$profileId/widgets/widgets.json"
                        val jsonFile = File(configManager.getWidgetsDir(), "$profileId/widgets.json")
                        syncService.downloadSingleFile(jsonUrl, jsonFile)
                        _installedWidgetProfiles.value = _installedWidgetProfiles.value + profileId
                    }
                    refreshInstalledWidgetIds(profileId)
                }
            }
            _isSyncing.value = false
        }
    }

    fun deleteSingleWidget(profileId: String, widget: StoreWidget) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.deleteWidgetFile(profileId, widget.src)
            refreshInstalledWidgetIds(profileId)
            val remaining = _installedWidgetIds.value[profileId] ?: emptySet()
            if (remaining.isEmpty()) {
                configManager.deleteWidgets(profileId)
                _installedWidgetProfiles.value = _installedWidgetProfiles.value - profileId
                _installedWidgetIds.value = _installedWidgetIds.value - profileId
            }
        }
    }

    fun deleteAllWidgets(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.deleteWidgets(profileId)
            _installedWidgetProfiles.value = _installedWidgetProfiles.value - profileId
            _installedWidgetIds.value = _installedWidgetIds.value - profileId
        }
    }

    fun buildCustomThemeConfigV2(
        profileId: String,
        console: String,
        selectedWidgetIds: List<String>,
        screenAssignments: Map<String, ScreenTarget>
    ): ThemeConfig? {
        if (!isValidId(profileId)) return null
        val allWidgets = getInstalledStoreWidgets(profileId)
        if (allWidgets.isEmpty()) return null

        val selectedWidgets = allWidgets.filter { it.id in selectedWidgetIds }
        if (selectedWidgets.isEmpty()) return null

        // Apply screen assignments, override widget's default screenTarget
        val assignedWidgets = selectedWidgets.map { widget ->
            val target = screenAssignments[widget.id] ?: widget.screenTarget ?: ScreenTarget.PRIMARY
            widget.toWidgetConfig().copy(screenTarget = target)
        }

        return ThemeConfig(
            id = "${OverlayService.CUSTOM_THEME_ID_PREFIX}$profileId",
            meta = ThemeMeta(name = "Custom Overlay", author = "User"),
            targetProfileId = profileId,
            targetConsole = console,
            type = ThemeType.OVERLAY,
            assetsPath = File(configManager.getRootDir(), "widgets/$profileId").absolutePath,
            widgets = assignedWidgets
        )
    }

    fun loadCustomOverlayConfig(profileId: String): CustomOverlayConfig? {
        return configManager.loadCustomOverlayConfig(profileId)
    }

    fun saveCustomOverlayConfig(config: CustomOverlayConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.saveCustomOverlayConfig(config)
        }
    }

    fun loadSavedOverlayIntoBuilder(overlayId: String) {
        val saved = _savedOverlays.value.find { it.id == overlayId } ?: return
        saveCustomOverlayConfig(CustomOverlayConfig(
            profileId = saved.profileId,
            selectedWidgetIds = saved.selectedWidgetIds,
            console = saved.console,
            screenAssignments = saved.screenAssignments
        ))
    }

    // --- Saved Overlays (user presets) ---

    fun refreshSavedOverlays() {
        _savedOverlays.value = configManager.loadUserOverlays()
    }

    fun buildSavedOverlayThemeConfig(saved: SavedOverlayConfig, console: String): ThemeConfig? {
        if (!isValidId(saved.profileId)) return null
        val allWidgets = getInstalledStoreWidgets(saved.profileId)
        if (allWidgets.isEmpty()) return null

        val selectedWidgets = allWidgets.filter { it.id in saved.selectedWidgetIds }
        if (selectedWidgets.isEmpty()) return null

        val dualScreen = _isDualScreen.value
        val assignedWidgets = selectedWidgets.map { widget ->
            val target = if (!dualScreen) ScreenTarget.PRIMARY
                         else saved.screenAssignments[widget.id] ?: widget.screenTarget ?: ScreenTarget.PRIMARY
            widget.toWidgetConfig().copy(screenTarget = target)
        }

        val hasSecondary = assignedWidgets.any { it.screenTarget == ScreenTarget.SECONDARY }
        val type = if (hasSecondary && dualScreen) ThemeType.BUNDLE else ThemeType.OVERLAY

        return ThemeConfig(
            id = saved.id,
            meta = ThemeMeta(name = saved.name, author = "User"),
            targetProfileId = saved.profileId,
            targetConsole = console,
            type = type,
            assetsPath = File(configManager.getRootDir(), "widgets/${saved.profileId}").absolutePath,
            widgets = assignedWidgets
        )
    }

    fun saveOverlayPreset(
        name: String,
        profileId: String,
        console: String?,
        selectedWidgetIds: List<String>,
        screenAssignments: Map<String, ScreenTarget>
    ): String {
        val id = "uo_${UUID.randomUUID().toString().take(8)}"
        val config = SavedOverlayConfig(
            id = id,
            name = name,
            profileId = profileId,
            console = console,
            selectedWidgetIds = selectedWidgetIds,
            screenAssignments = screenAssignments
        )
        configManager.saveUserOverlay(config)

        // Copy layout files from the custom builder overlay
        val sourceId = "${OverlayService.CUSTOM_THEME_ID_PREFIX}$profileId"
        val isDual = _isDualScreen.value
        if (isDual) {
            configManager.copyOverlayLayout(sourceId, id, "primary")
            configManager.copyOverlayLayout(sourceId, id, "secondary")
        } else {
            configManager.copyOverlayLayout(sourceId, id, null)
        }

        refreshSavedOverlays()
        val gameId = detectedGameId.value
        val det = detectedConsole.value
        if (gameId != null && det != null) {
            refreshThemesForGame(gameId, det)
        }
        return id
    }

    fun saveOverlayIcon(overlayId: String, uri: android.net.Uri, contentResolver: android.content.ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mime = contentResolver.getType(uri)
                val ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime)
                    .takeIf { it in SavedOverlayConfig.ICON_EXTENSIONS }
                    ?: "png"
                // Remove any previous icon in other formats
                SavedOverlayConfig.ICON_EXTENSIONS.forEach { e ->
                    java.io.File(configManager.getUserOverlaysDir(), "${overlayId}_icon.$e").delete()
                }
                val iconFile = java.io.File(configManager.getUserOverlaysDir(), "${overlayId}_icon.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    iconFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save overlay icon: ${e.message}")
            }
        }
    }

    fun deleteOverlayPreset(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.deleteUserOverlay(id)
            refreshSavedOverlays()
            val gameId = detectedGameId.value
            val console = detectedConsole.value
            if (gameId != null && console != null) {
                refreshThemesForGame(gameId, console)
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

    private fun getSyncUrl(): String {
        val config = _appConfig.value
        return if (config.devMode && config.devUrl.isNotBlank()) {
            "${config.devUrl.removeSuffix("/")}/__dev_sync"
        } else {
            config.repoUrl
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

        val isDevSync = _appConfig.value.devMode && _appConfig.value.devUrl.isNotBlank()

        viewModelScope.launch(Dispatchers.IO) {
            val success = syncService.downloadAndExtract(
                url = getSyncUrl(),
                stripRoot = false,
                pathFilter = if (isDevSync) null else { path ->
                    path == "index.json" ||
                    path == "consoles.json" ||
                    path == "hashes.json" ||
                    path.startsWith("profiles/")
                },
                pathRewriter = if (isDevSync) { path ->
                    if (path.startsWith("themes/")) {
                        // themes/GBA/BPE/widgets/... → widgets/BPE/...
                        // themes/GBA/BPE/ThemeId/...  → themes/ThemeId/...
                        val parts = path.removePrefix("themes/").split("/", limit = 4)
                        if (parts.size >= 4) {
                            val profileId = parts[1]
                            val segment = parts[2]
                            val rest = parts[3]
                            if (segment == "widgets") "widgets/$profileId/$rest"
                            else "themes/${profileId}_$segment/$rest"
                        } else path
                    } else path
                } else null
            ) { message ->
                _syncMessage.value = message
                viewModelScope.launch { addDebugLog(message) }
            }
            
            _isSyncing.value = false

            if (success) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(TAG, "Sync successful, refreshing configs.")
                }
                configManager.loadHashRegistry()
                refreshAllInstalledThemes()

                // Re-resolve game profile with updated hash registry
                val hash = memoryService.detectedGameHash.value
                val gameId = detectedGameId.value
                val console = detectedConsole.value
                if (hash != null && gameId != null) {
                    val resolver = configManager.createProfileResolver()
                    val result = resolver.resolve(hash, gameId)
                    _resolvedProfileId.value = result?.profile?.id
                    _currentConfidence.value = result?.confidence ?: MatchConfidence.UNKNOWN
                    val entry = result?.hashEntry
                    _detectedGameLabel.value = when {
                        entry?.label != null && entry.version != null -> "${entry.label} (${entry.version})"
                        entry?.label != null -> entry.label
                        else -> null
                    }
                }

                try {
                    val repoUrl = _appConfig.value.repoUrlShim
                    _rawBaseUrl.value = syncService.deriveRawBaseUrl(repoUrl)
                    _repoIndex.value = syncService.fetchRepoIndex(repoUrl)
                } catch (_: Exception) { /* index fetch failure shouldn't break sync */ }

                viewModelScope.launch {
                    memoryService.stop()
                    memoryService.start(configManager.getConsoleConfigs())

                    val gid = detectedGameId.value
                    val con = detectedConsole.value
                    if (_appConfig.value.devMode && gid == null) {
                        refreshThemesForDevMode()
                    } else if (gid != null && con != null) {
                        refreshThemesForGame(gid, con)
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
        val entry = "$timestamp: $message"
        _debugLogs.value = listOf(entry) + _debugLogs.value.take(MAX_DEBUG_LOGS - 1)
    }

    override fun onCleared() {
        super.onCleared()
        syncService.close()
    }
}
