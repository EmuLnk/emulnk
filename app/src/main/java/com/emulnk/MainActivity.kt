package com.emulnk

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emulnk.core.DisplayHelper
import com.emulnk.model.MatchConfidence
import com.emulnk.core.OverlayConstants
import com.emulnk.core.OverlayService
import com.emulnk.core.UiConstants
import com.emulnk.model.CustomOverlayConfig
import com.emulnk.model.SavedOverlayConfig
import com.emulnk.model.DisplayInfo
import com.emulnk.model.SafeArea
import com.emulnk.model.ScreenTarget
import com.emulnk.model.SystemInfo
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedType
import com.emulnk.ui.components.AppSettingsDialog
import com.emulnk.ui.components.SyncProgressDialog
import com.emulnk.ui.navigation.Screen
import com.emulnk.ui.screens.GalleryScreen
import com.emulnk.ui.screens.BuilderScreen
import com.emulnk.ui.screens.LauncherScreen
import com.emulnk.ui.screens.OnboardingScreen
import com.emulnk.ui.theme.EmuLinkTheme
import com.emulnk.ui.theme.SurfaceBase
import com.emulnk.ui.viewmodel.MainViewModel
import com.google.gson.Gson

class MainActivity : ComponentActivity() {
    private val gson = Gson()
    private val vm: MainViewModel by viewModels()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val docId = DocumentsContract.getTreeDocumentId(it)
            val split = docId.split(":")
            val type = split[0]
            val path = if (split.size > 1) {
                if ("primary".equals(type, ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                } else {
                    "/storage/$type/${split[1]}"
                }
            } else {
                it.path
            }

            if (path != null) {
                vm.updateRootDirectory(path)
                vm.addDebugLog("Folder verified: $path")
            }
        }
    }

    private val themeImporter = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importTheme(it) }
    }

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        OverlayService.onImagePicked(uri)
        window.decorView.post { moveTaskToBack(true) }
    }

    private var pendingAction: (() -> Unit)? = null
    private var deselectOnPermissionDenied = true
    private var onOverlayStarted: (() -> Unit)? = null

    private fun withOverlayPermission(deselectOnDenied: Boolean = true, block: () -> Unit) {
        if (!Settings.canDrawOverlays(this)) {
            pendingAction = block
            deselectOnPermissionDenied = deselectOnDenied
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            )
            return
        }
        block()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(OverlayService.EXTRA_CLEAR_OVERLAY, false)) {
            vm.selectTheme(null as ThemeConfig?)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_SHORT).show()
            if (deselectOnPermissionDenied) {
                vm.selectTheme(null as ThemeConfig?)
            }
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmuLinkTheme {
                val uiState by vm.uiState.collectAsState()
                val detectedGameId by vm.detectedGameId.collectAsState()
                val availableThemes by vm.availableThemes.collectAsState()
                val selectedTheme by vm.selectedTheme.collectAsState()
                val debugLogs by vm.debugLogs.collectAsState()
                val appConfig by vm.appConfig.collectAsState()
                val rootPath by vm.rootPath.collectAsState()
                val onboardingCompleted by vm.onboardingCompleted.collectAsState()
                val pairedOverlay by vm.pairedOverlay.collectAsState()
                val pairedSecondaryOverlay by vm.pairedSecondaryOverlay.collectAsState()
                val isDualScreen by vm.isDualScreen.collectAsState()
                val isSyncing by vm.isSyncing.collectAsState()
                val repoIndex by vm.repoIndex.collectAsState()
                val rawBaseUrl by vm.rawBaseUrl.collectAsState()
                val syncMessage by vm.syncMessage.collectAsState()
                val allInstalledThemes by vm.allInstalledThemes.collectAsState()
                val isRootPathSet by vm.isRootPathSet.collectAsState()
                val storeWidgets by vm.storeWidgets.collectAsState()

                val widgetUpdatesAvailable by vm.widgetUpdatesAvailable.collectAsState()
                val resolvedProfileId by vm.resolvedProfileId.collectAsState()
                val detectedConsole by vm.detectedConsole.collectAsState()
                val installedWidgetIds by vm.installedWidgetIds.collectAsState()
                val userOverlayThemes by vm.userOverlayThemes.collectAsState()
                val currentConfidence by vm.currentConfidence.collectAsState()
                val gameHash by vm.detectedGameHash.collectAsState()
                val detectedGameLabel by vm.detectedGameLabel.collectAsState()

                var currentScreen by remember { mutableStateOf<Screen>(Screen.Onboarding) }
                onOverlayStarted = { currentScreen = Screen.Overlay }
                launchBuilderScreen = { pid, con -> currentScreen = Screen.Builder(pid, con) }
                var showAppSettings by remember { mutableStateOf(false) }
                var galleryTargetProfileId by remember { mutableStateOf<String?>(null) }

                DisposableEffect(Unit) {
                    OverlayService.onSaveCompleted = { name, iconUri ->
                        val pid = vm.resolvedProfileId.value
                        val con = vm.detectedConsole.value
                        val builderConfig = pid?.let { vm.loadCustomOverlayConfig(it) }
                        if (pid != null && builderConfig != null) {
                            val savedId = vm.saveOverlayPreset(
                                name = name,
                                profileId = pid,
                                console = con,
                                selectedWidgetIds = builderConfig.selectedWidgetIds,
                                screenAssignments = builderConfig.screenAssignments
                            )
                            if (iconUri != null) {
                                vm.saveOverlayIcon(savedId, iconUri, this@MainActivity.contentResolver)
                            }
                            Toast.makeText(this@MainActivity, getString(R.string.save_overlay_success), Toast.LENGTH_SHORT).show()
                        }
                    }
                    OverlayService.onPickImage = { iconPicker.launch("image/*") }
                    onDispose {
                        OverlayService.onSaveCompleted = null
                        OverlayService.onPickImage = null
                    }
                }

                LaunchedEffect(onboardingCompleted, selectedTheme, pairedOverlay, pairedSecondaryOverlay) {
                    when {
                        !onboardingCompleted -> currentScreen = Screen.Onboarding
                        selectedTheme != null -> {
                            // Overlay bundle (dual-screen overlay-overlay pairing)
                            if (pairedSecondaryOverlay != null) {
                                if (OverlayService.isRunning()) {
                                    currentScreen = Screen.Overlay
                                } else {
                                    launchOverlayBundle(selectedTheme!!, pairedSecondaryOverlay)
                                }
                            } else when (selectedTheme!!.resolvedType) {
                                ThemeType.BUNDLE -> {
                                    // User-built overlays bypass the pairing sheet since they already have screen assignments
                                    val isOverlayBundle = selectedTheme!!.id.startsWith(SavedOverlayConfig.ID_PREFIX) || selectedTheme!!.id.startsWith(OverlayService.CUSTOM_THEME_ID_PREFIX)
                                    if (isOverlayBundle) {
                                        if (OverlayService.isRunning()) {
                                            currentScreen = Screen.Overlay
                                        } else {
                                            launchOverlay(selectedTheme!!)
                                        }
                                    } else {
                                        if (OverlayService.isRunning()) {
                                            currentScreen = Screen.Overlay
                                        } else {
                                            launchInteractiveTheme(selectedTheme!!, null)
                                        }
                                    }
                                }
                                ThemeType.OVERLAY -> {
                                    if (OverlayService.isRunning()) {
                                        currentScreen = Screen.Overlay
                                    } else {
                                        launchOverlay(selectedTheme!!)
                                    }
                                }
                                else -> {
                                    if (OverlayService.isRunning()) {
                                        currentScreen = Screen.Overlay
                                    } else {
                                        launchInteractiveTheme(selectedTheme!!, pairedOverlay)
                                    }
                                }
                            }
                        }
                        currentScreen == Screen.Onboarding -> currentScreen = Screen.Home
                        currentScreen == Screen.Overlay -> currentScreen = Screen.Home
                    }
                }

                val density = androidx.compose.ui.platform.LocalDensity.current
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val windowInsets = WindowInsets.systemBars

                val scale = density.density
                val topDp = (windowInsets.getTop(density) / scale).toInt()
                val bottomDp = (windowInsets.getBottom(density) / scale).toInt()
                val leftDp = (windowInsets.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr) / scale).toInt()
                val rightDp = (windowInsets.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr) / scale).toInt()
                val buttonSpaceDp = 56
                val orientation = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 90 else 0

                LaunchedEffect(topDp, bottomDp, leftDp, rightDp, orientation) {
                    val secDims = DisplayHelper.getSecondaryDimensions(this@MainActivity)
                    vm.updateSystemInfo(
                        SystemInfo(
                            safeArea = SafeArea(
                                top = topDp + buttonSpaceDp,
                                bottom = bottomDp,
                                left = leftDp,
                                right = rightDp
                            ),
                            display = DisplayInfo(
                                width = configuration.screenWidthDp,
                                height = configuration.screenHeightDp,
                                orientation = orientation,
                                isDualScreen = DisplayHelper.isDualScreen(this@MainActivity),
                                secondaryWidth = secDims?.widthDp ?: 0,
                                secondaryHeight = secDims?.heightDp ?: 0
                            )
                        )
                    )
                }

                Surface(modifier = Modifier.fillMaxSize(), color = SurfaceBase) {
                    when (currentScreen) {
                        is Screen.Onboarding -> OnboardingScreen(
                            rootPath = rootPath,
                            isRootPathSet = isRootPathSet,
                            appConfig = appConfig,
                            onGrantPermission = { requestStoragePermission() },
                            onSelectFolder = { folderPicker.launch(null) },
                            onGrantOverlayPermission = { requestOverlayPermission() },
                            onSetAutoBoot = { vm.setAutoBoot(it) },
                            onSetRepoUrl = { vm.setRepoUrl(it) },
                            onResetRepoUrl = { vm.resetRepoUrl() },
                            onCompleteOnboarding = { vm.completeOnboarding() }
                        )
                        is Screen.Home -> {
                            var backPressedOnce by remember { mutableStateOf(false) }

                            LaunchedEffect(backPressedOnce) {
                                if (backPressedOnce) {
                                    kotlinx.coroutines.delay(UiConstants.BACK_PRESS_EXIT_DELAY_MS)
                                    backPressedOnce = false
                                }
                            }

                            BackHandler {
                                if (backPressedOnce) {
                                    (this@MainActivity).finishAffinity()
                                } else {
                                    backPressedOnce = true
                                    Toast.makeText(this@MainActivity, getString(R.string.back_press_exit), Toast.LENGTH_SHORT).show()
                                }
                            }

                            val galleryExtras = remember(repoIndex, resolvedProfileId, allInstalledThemes) {
                                val idx = repoIndex; val pid = resolvedProfileId
                                if (pid == null || idx == null) null
                                else {
                                    val allGames = idx.consoles.flatMap { it.games }
                                    val parentId = vm.getParentProfileId(pid)
                                    val game = allGames.firstOrNull { it.profileId == pid }
                                        ?: parentId?.let { p -> allGames.firstOrNull { it.profileId == p } }
                                    if (game != null) {
                                        val installedIds = allInstalledThemes.map { it.id }.toSet()
                                        val uninstalled = game.themes.count { it.id !in installedIds }
                                        Triple(uninstalled, game.hasWidgets, game.name)
                                    } else null
                                }
                            }
                            val uninstalledThemeCount = galleryExtras?.first ?: 0
                            val hasGalleryWidgets = galleryExtras?.second ?: false
                            val detectedGameName = galleryExtras?.third

                            LauncherScreen(
                                detectedGameId = detectedGameId,
                                themes = availableThemes,
                                isSyncing = isSyncing,
                                appConfig = appConfig,
                                rootPath = rootPath,
                                isDualScreen = isDualScreen,
                                onSelectTheme = { theme -> vm.selectTheme(theme) },
                                onSelectOverlayBundle = { primary, secondary, setDefault ->
                                    val theme = listOfNotNull(primary, secondary).find { it.resolvedType == ThemeType.THEME }
                                    val overlays = listOfNotNull(primary, secondary).filter { it.resolvedType == ThemeType.OVERLAY }

                                    when {
                                        theme != null && overlays.isNotEmpty() -> {
                                            // Theme + overlay: route via selectPair → launchInteractiveTheme
                                            val overlay = overlays.first()
                                            val themeTarget = if (!isDualScreen || primary?.resolvedType == ThemeType.THEME) ScreenTarget.PRIMARY else ScreenTarget.SECONDARY
                                            val overlayTarget = if (!isDualScreen || primary?.resolvedType == ThemeType.OVERLAY) ScreenTarget.PRIMARY else ScreenTarget.SECONDARY
                                            vm.selectPair(
                                                theme.copy(screenTarget = themeTarget),
                                                overlay.copy(
                                                    screenTarget = overlayTarget,
                                                    widgets = overlay.widgets?.map { it.copy(screenTarget = null) }
                                                )
                                            )
                                        }
                                        theme != null -> {
                                            // Theme-only: stamp user's screen choice, then route via selectPair
                                            val targetedTheme = if (isDualScreen) {
                                                // User's pairing choice overrides author-defined screenTarget
                                                val target = if (primary != null) ScreenTarget.PRIMARY else ScreenTarget.SECONDARY
                                                theme.copy(screenTarget = target)
                                            } else {
                                                // Single screen: always primary
                                                theme.copy(screenTarget = ScreenTarget.PRIMARY)
                                            }
                                            vm.selectPair(targetedTheme, null)
                                        }
                                        else -> {
                                            val overriddenPrimary = primary?.let { p ->
                                                p.copy(
                                                    screenTarget = ScreenTarget.PRIMARY,
                                                    widgets = p.widgets?.map { it.copy(screenTarget = null) }
                                                )
                                            }
                                            val overriddenSecondary = secondary?.let { s ->
                                                s.copy(
                                                    screenTarget = ScreenTarget.SECONDARY,
                                                    widgets = s.widgets?.map { it.copy(screenTarget = null) }
                                                )
                                            }
                                            vm.selectOverlayBundle(overriddenPrimary, overriddenSecondary)
                                        }
                                    }

                                    if (setDefault && detectedGameId != null) {
                                        vm.setDefaultBundleForGame(detectedGameId!!, primary?.id, secondary?.id)
                                    } else if (!setDefault && detectedGameId != null) {
                                        val gid = detectedGameId!!
                                        val launchedIds = setOfNotNull(primary?.id, secondary?.id)

                                        // Clear bundle default if it overlaps with launched items
                                        val bundle = appConfig.defaultBundles[gid]
                                        val bundleIds = setOfNotNull(bundle?.primaryOverlayId, bundle?.secondaryOverlayId)
                                        if (launchedIds.intersect(bundleIds).isNotEmpty()) {
                                            vm.setDefaultBundleForGame(gid, null, null)
                                        }

                                        // Clear legacy defaults if they overlap
                                        val legacyIds = setOfNotNull(
                                            appConfig.defaultThemes[gid],
                                            (appConfig.defaultOverlays ?: emptyMap())[gid]
                                        )
                                        if (launchedIds.intersect(legacyIds).isNotEmpty()) {
                                            vm.setDefaultPairForGame(gid, null, null)
                                        }
                                    }
                                },
                                onSetDefaultTheme = { gameId, themeId -> vm.setDefaultThemeForGame(gameId, themeId) },
                                onOpenGallery = {
                                    galleryTargetProfileId = null
                                    vm.fetchGallery()
                                    currentScreen = Screen.Gallery
                                },
                                onOpenSettings = { showAppSettings = true },
                                onSync = { vm.syncRepository() },
                                uninstalledThemeCount = uninstalledThemeCount,
                                hasGalleryWidgets = hasGalleryWidgets,
                                onJumpToGallery = {
                                    val pid = resolvedProfileId
                                    val allGames = repoIndex?.consoles?.flatMap { it.games }
                                    val hasOwnEntry = allGames?.any { it.profileId == pid } == true
                                    galleryTargetProfileId = if (hasOwnEntry || pid == null) pid
                                        else vm.getParentProfileId(pid) ?: pid
                                    vm.fetchGallery()
                                    currentScreen = Screen.Gallery
                                },
                                detectedGameName = detectedGameLabel ?: detectedGameName,
                                userOverlays = userOverlayThemes,
                                onDeleteOverlay = { vm.deleteOverlayPreset(it) },
                                onEditOverlay = { overlay ->
                                    vm.loadSavedOverlayIntoBuilder(overlay.id)
                                    val pid = resolvedProfileId; val con = detectedConsole
                                    if (pid != null && con != null) launchBuilder(pid, con)
                                },
                                showBuilderButton = resolvedProfileId != null && installedWidgetIds.containsKey(resolvedProfileId),
                                onLaunchBuilder = {
                                    val pid = resolvedProfileId; val con = detectedConsole
                                    if (pid != null && con != null) launchBuilder(pid, con)
                                },
                                confidence = currentConfidence,
                                gameHash = gameHash,
                            )
                        }
                        is Screen.Gallery -> {
                            BackHandler { galleryTargetProfileId = null; currentScreen = Screen.Home }

                            GalleryScreen(
                                galleryIndex = repoIndex,
                                rawBaseUrl = rawBaseUrl,
                                isSyncing = isSyncing,
                                allInstalledThemes = allInstalledThemes,
                                appVersionCode = vm.getAppVersionCode(),
                                isDualScreen = isDualScreen,
                                storeWidgets = storeWidgets,
                                widgetUpdatesAvailable = widgetUpdatesAvailable,
                                initialProfileId = galleryTargetProfileId,
                                onBack = { galleryTargetProfileId = null; currentScreen = Screen.Home },
                                onImportTheme = { themeImporter.launch("application/zip") },
                                onSelectTheme = { vm.selectTheme(it) },
                                onDownloadTheme = { vm.downloadTheme(it) },
                                onDeleteTheme = { vm.deleteTheme(it) },
                                onFetchWidgets = { console, profileId -> vm.fetchWidgetsForGame(console, profileId) },
                                onDownloadWidgets = { console, profileId -> vm.downloadWidgets(console, profileId) },
                                onUpdateWidgets = { console, profileId -> vm.updateWidgets(console, profileId) },
                                onLaunchBuilder = { profileId, console -> launchBuilder(profileId, console) },
                                installedWidgetIds = installedWidgetIds,
                                onDownloadWidget = { c, p, w -> vm.downloadSingleWidget(c, p, w) },
                                onDeleteWidget = { p, w -> vm.deleteSingleWidget(p, w) },
                                onDeleteAllWidgets = { p -> vm.deleteAllWidgets(p) }
                            )
                        }
                        is Screen.Overlay -> {
                            BackHandler {
                                stopOverlayService()
                                vm.selectTheme(null as ThemeConfig?)
                            }

                            OverlayActiveScreen(
                                themeName = selectedTheme?.meta?.name ?: "Overlay",
                                onExit = {
                                    stopOverlayService()
                                    vm.selectTheme(null as ThemeConfig?)
                                }
                            )
                        }

                        is Screen.Builder -> {
                            val builderScreen = currentScreen as Screen.Builder
                            BackHandler { currentScreen = Screen.Home }

                            val builderWidgets = remember(builderScreen.profileId) {
                                vm.getInstalledStoreWidgets(builderScreen.profileId)
                            }
                            val savedBuilderConfig = remember(builderScreen.profileId) {
                                vm.loadCustomOverlayConfig(builderScreen.profileId)
                            }

                            BuilderScreen(
                                profileId = builderScreen.profileId,
                                console = builderScreen.console,
                                installedWidgets = builderWidgets,
                                savedConfig = savedBuilderConfig,
                                isDualScreen = isDualScreen,
                                previewBaseUrl = rawBaseUrl.ifBlank { null },
                                totalWidgetCount = storeWidgets[builderScreen.profileId]?.size ?: 0,
                                onBack = { currentScreen = Screen.Home },
                                onBrowseGallery = {
                                    galleryTargetProfileId = builderScreen.profileId
                                    vm.fetchGallery()
                                    currentScreen = Screen.Gallery
                                },
                                onLaunchPreview = { selectedIds, screenAssignments ->
                                    launchBuilderOverlay(
                                        builderScreen.profileId,
                                        builderScreen.console,
                                        selectedIds,
                                        screenAssignments
                                    )
                                }
                            )
                        }
                    }

                    if (isSyncing) {
                        SyncProgressDialog(message = syncMessage)
                    }

                    if (showAppSettings) {
                        AppSettingsDialog(
                            appConfig = appConfig,
                            rootPath = rootPath,
                            appVersionCode = vm.getAppVersionCode(),
                            onDismiss = { showAppSettings = false },
                            onSetAutoBoot = { vm.setAutoBoot(it) },
                            onSetRepoUrl = { vm.setRepoUrl(it) },
                            onResetRepoUrl = { vm.resetRepoUrl() },
                            onChangeRootFolder = {
                                showAppSettings = false
                                folderPicker.launch(null)
                            },
                            onSetDevMode = { vm.setDevMode(it) },
                            onSetDevUrl = { vm.setDevUrl(it) }
                        )
                    }

                }
            }
        }
    }

    private fun launchOverlay(theme: ThemeConfig) {
        withOverlayPermission { startOverlayService(theme) }
    }

    private fun startOverlayService(theme: ThemeConfig) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(theme))
        }
        startForegroundService(intent)
        onOverlayStarted?.invoke()
        moveTaskToBack(true)
    }

    private fun launchPairedOverlay(theme: ThemeConfig) {
        withOverlayPermission(deselectOnDenied = false) { startPairedOverlayService(theme) }
    }

    private fun startPairedOverlayService(theme: ThemeConfig) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(theme))
        }
        startForegroundService(intent)
        // Don't moveTaskToBack, stay on dashboard
    }

    private fun launchInteractiveTheme(theme: ThemeConfig, pairedOverlay: ThemeConfig?) {
        withOverlayPermission {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayConstants.EXTRA_INTERACTIVE_THEME, true)
                if (pairedOverlay != null) {
                    // Overlay is primary (its widgets float on top)
                    putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(pairedOverlay))
                    // Theme passed as secondary, service consumes it as base-layer source
                    putExtra(OverlayService.EXTRA_SECONDARY_THEME_JSON, gson.toJson(theme))
                } else {
                    // Theme-only: it IS the primary config
                    putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(theme))
                }
            }
            startForegroundService(intent)
            onOverlayStarted?.invoke()
            moveTaskToBack(true)
        }
    }

    private fun launchOverlayBundle(primary: ThemeConfig, secondary: ThemeConfig?) {
        withOverlayPermission { startOverlayBundleService(primary, secondary) }
    }

    private fun startOverlayBundleService(primary: ThemeConfig, secondary: ThemeConfig?) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(primary))
            if (secondary != null) {
                putExtra(OverlayService.EXTRA_SECONDARY_THEME_JSON, gson.toJson(secondary))
            }
        }
        startForegroundService(intent)
        onOverlayStarted?.invoke()
        moveTaskToBack(true)
    }

    private var launchBuilderScreen: ((String, String) -> Unit)? = null

    private fun launchBuilder(profileId: String, console: String) {
        launchBuilderScreen?.invoke(profileId, console)
    }

    private fun launchBuilderOverlay(
        profileId: String,
        console: String,
        selectedWidgetIds: List<String>,
        screenAssignments: Map<String, ScreenTarget>
    ) {
        // Save the custom overlay config
        vm.saveCustomOverlayConfig(
            CustomOverlayConfig(
                profileId = profileId,
                selectedWidgetIds = selectedWidgetIds,
                console = console,
                screenAssignments = screenAssignments
            )
        )

        val themeConfig = vm.buildCustomThemeConfigV2(profileId, console, selectedWidgetIds, screenAssignments) ?: return
        val allWidgets = vm.getInstalledStoreWidgets(profileId)

        // Activate theme so MemoryService starts polling data
        vm.selectTheme(themeConfig)

        withOverlayPermission(deselectOnDenied = false) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_THEME_JSON, gson.toJson(themeConfig))
                putExtra(OverlayService.EXTRA_BUILDER_MODE, true)
                putExtra(OverlayService.EXTRA_AVAILABLE_WIDGETS_JSON, gson.toJson(allWidgets))
            }
            startForegroundService(intent)
            onOverlayStarted?.invoke()
            moveTaskToBack(true)
        }
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }

    private fun requestStoragePermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(intent)
        }
    }
}

@Composable
private fun OverlayActiveScreen(
    themeName: String,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(com.emulnk.ui.theme.EmuLnkDimens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.overlay_active),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = com.emulnk.ui.theme.BrandCyan
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = themeName,
            fontSize = 16.sp,
            color = com.emulnk.ui.theme.TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.overlay_instructions),
            fontSize = 13.sp,
            color = com.emulnk.ui.theme.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onExit,
            colors = ButtonDefaults.buttonColors(containerColor = com.emulnk.ui.theme.BrandPurple)
        ) {
            Text(stringResource(R.string.overlay_stop), color = com.emulnk.ui.theme.TextPrimary)
        }
    }
}
