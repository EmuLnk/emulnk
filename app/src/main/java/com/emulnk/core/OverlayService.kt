package com.emulnk.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Display
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.emulnk.BuildConfig
import com.emulnk.MainActivity
import com.emulnk.R
import com.emulnk.bridge.OverlayBridge
import com.emulnk.data.ConfigManager
import com.emulnk.EmuLnkApplication
import com.emulnk.core.memory.MemoryService
import com.emulnk.model.AppConfig
import com.emulnk.model.CustomOverlayConfig
import com.emulnk.core.model.GameData
import com.emulnk.model.OverlayLayout
import com.emulnk.model.ScreenTarget
import com.emulnk.model.resolvedScreenTarget
import com.emulnk.model.StoreWidget
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedType
import com.emulnk.model.WidgetConfig
import com.emulnk.model.WidgetLayoutState
import com.emulnk.model.toBaseLayerWidget
import com.emulnk.model.toWidgetConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import kotlin.math.abs

/**
 * Foreground service that manages floating WebView overlay widgets
 * using SYSTEM_ALERT_WINDOW (TYPE_APPLICATION_OVERLAY).
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val EXTRA_THEME_JSON = "theme_json"
        const val EXTRA_SECONDARY_THEME_JSON = "secondary_theme_json"
        const val EXTRA_BUILDER_MODE = "builder_mode"
        const val EXTRA_AVAILABLE_WIDGETS_JSON = "available_widgets_json"
        const val ACTION_STOP = "com.emulnk.STOP_OVERLAY"
        const val ACTION_EDIT_MODE = "com.emulnk.EDIT_MODE"
        const val ACTION_SWAP_SCREENS = "com.emulnk.SWAP_SCREENS"
        const val EXTRA_CLEAR_OVERLAY = "CLEAR_OVERLAY"
        const val CUSTOM_THEME_ID_PREFIX = "custom_"

        private var instance: OverlayService? = null
        fun isRunning(): Boolean = instance != null

        var onSaveCompleted: ((name: String, iconUri: android.net.Uri?) -> Unit)? = null
        var onPickImage: (() -> Unit)? = null
        fun onImagePicked(uri: android.net.Uri?) {
            instance?.onIconPicked(uri)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()
    private lateinit var windowManager: WindowManager
    private lateinit var configManager: ConfigManager

    private var memoryService: MemoryService? = null
    private var overlayBridge: OverlayBridge? = null
    private var dataCollectionJob: Job? = null
    private var settingSaveJob: Job? = null
    private var devReloadJob: Job? = null
    private var lastDevMtime: String? = null

    private var themeConfig: ThemeConfig? = null
    private var baseLayerSourceConfig: ThemeConfig? = null
    private var secondaryThemeConfig: ThemeConfig? = null
    private val widgetViews = mutableMapOf<String, WidgetWindow>()
    private var savedLayout: OverlayLayout? = null

    private var overlayPresentation: OverlayPresentation? = null
    private var secondaryWidgets: List<WidgetConfig> = emptyList()
    private var isDualScreenActive = false

    private var isEditMode = false
    private var scrimView: View? = null
    private var controlsBar: View? = null
    private var controlsLabel: TextView? = null
    private var selectedWidget: WidgetWindow? = null
    private var selectedSecondaryWidgetId: String? = null
    private var recoveryPill: View? = null

    // Cached JS injection string, recomputed only when GameData changes
    private var lastGameData: GameData? = null
    private var cachedDataJs: String? = null

    private var isBuilderMode = false
    private var availableStoreWidgets: List<StoreWidget> = emptyList()

    private var builderSession: BuilderSession? = null
    private var sessionObserverJob: Job? = null
    private var undoButton: TextView? = null
    private var redoButton: TextView? = null
    private var alphaSeekBar: SeekBar? = null
    private var alphaLabel: TextView? = null
    private var alphaSliderRow: View? = null

    private var saveDialogView: View? = null
    private var saveDialogScrim: View? = null
    private var pickedIconUri: android.net.Uri? = null
    private var iconPreviewView: android.widget.ImageView? = null

    private var themeChrome: ThemeOverlayChrome? = null
    private var isInteractiveTheme: Boolean = false
    private var lastConnectedState: Boolean = false
    private var baseLayerWM: WindowManager? = null

    /** Build the dev-mode theme path: `{console}/{profileId}/{themeId}` or flat `{themeId}`. */
    private fun ThemeConfig.devThemePath(): String =
        if (targetConsole != null) "$targetConsole/$targetProfileId/$id" else id

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (DisplayHelper.isDualScreen(this)) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val primaryDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
            if (primaryDisplay != null) {
                val primaryContext = createWindowContext(
                    primaryDisplay,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
                windowManager = primaryContext.getSystemService(WINDOW_SERVICE) as WindowManager
            }
        }

        configManager = ConfigManager(this)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_EDIT_MODE) {
            if (isEditMode) exitEditMode() else enterEditMode()
            return START_STICKY
        }

        if (intent?.action == ACTION_SWAP_SCREENS) {
            swapScreens()
            return START_STICKY
        }

        val themeJson = intent?.getStringExtra(EXTRA_THEME_JSON) ?: run {
            Log.e(TAG, "No theme config provided")
            stopSelf()
            return START_NOT_STICKY
        }

        removeAllWidgets()
        dataCollectionJob?.cancel()

        startForeground(OverlayConstants.NOTIFICATION_ID, createNotification())

        try {
            themeConfig = gson.fromJson(themeJson, ThemeConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse theme config", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Parse interactive theme mode
        isInteractiveTheme = intent?.getBooleanExtra(OverlayConstants.EXTRA_INTERACTIVE_THEME, false) ?: false
        lastConnectedState = false

        // Parse builder mode
        isBuilderMode = intent?.getBooleanExtra(EXTRA_BUILDER_MODE, false) ?: false
        builderSession = null
        if (isBuilderMode) {
            val widgetsJson = intent?.getStringExtra(EXTRA_AVAILABLE_WIDGETS_JSON)
            if (widgetsJson != null) {
                try {
                    availableStoreWidgets = gson.fromJson(widgetsJson, StoreWidget.LIST_TYPE)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse available widgets JSON", e)
                }
            }
        }

        val config = themeConfig ?: run { stopSelf(); return START_NOT_STICKY }
        val allWidgets = config.widgets ?: emptyList()

        // Parse secondary theme BEFORE empty-widgets check (theme configs have no widgets)
        val secondaryJson = intent?.getStringExtra(EXTRA_SECONDARY_THEME_JSON)
        if (secondaryJson != null) {
            try {
                secondaryThemeConfig = gson.fromJson(secondaryJson, ThemeConfig::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse secondary theme config", e)
            }
        }

        // Only stop if no widgets AND no secondary config AND not an interactive theme (i.e., truly nothing to show)
        if (allWidgets.isEmpty() && !isBuilderMode && secondaryThemeConfig == null
            && config.resolvedType != ThemeType.THEME && !isInteractiveTheme) {
            Log.e(TAG, "No widgets defined in overlay theme: ${config.id}")
            stopSelf()
            return START_NOT_STICKY
        }

        val appConfig = configManager.getAppConfig()
        val hasSecondaryDisplay = DisplayHelper.getSecondaryDisplay(this) != null

        // Partition widgets: author-defined screenTarget or user-paired bundle
        val primaryWidgets: List<WidgetConfig>
        if (secondaryThemeConfig != null && !isInteractiveTheme) {
            // Two separate overlays paired by user (bundle path)
            primaryWidgets = allWidgets
            secondaryWidgets = secondaryThemeConfig?.widgets ?: emptyList()
        } else if (appConfig.isRemoteHost && !hasSecondaryDisplay) {
            // Remote host mode: phone IS the second screen, show everything here
            primaryWidgets = allWidgets
            secondaryWidgets = emptyList()
        } else {
            // Single theme with per-widget screenTarget
            val themeDefault = config.resolvedScreenTarget
            primaryWidgets = allWidgets.filter { (it.screenTarget ?: themeDefault) != ScreenTarget.SECONDARY }
            secondaryWidgets = allWidgets.filter { (it.screenTarget ?: themeDefault) == ScreenTarget.SECONDARY }
        }

        // Determine base-layer theme source
        baseLayerSourceConfig = null
        val baseLayerThemeConfig: ThemeConfig? = when {
            // Paired: overlay in EXTRA_THEME_JSON, theme in EXTRA_SECONDARY_THEME_JSON
            isInteractiveTheme && secondaryThemeConfig != null -> {
                val tc = secondaryThemeConfig!!
                secondaryThemeConfig = null  // consume, not for secondary display
                tc
            }
            // Theme-only: primary IS the theme
            isInteractiveTheme && config.resolvedType == ThemeType.THEME -> config
            // Non-interactive theme (bundle backdrop)
            !isInteractiveTheme && allWidgets.isEmpty() && config.resolvedType == ThemeType.THEME -> config
            else -> null
        }

        if (baseLayerThemeConfig != null) {
            baseLayerSourceConfig = baseLayerThemeConfig
        }

        // Synthesize base-layer widget and prepend to widget list
        val baseLayerWidget = baseLayerThemeConfig?.toBaseLayerWidget()

        // Create base-layer FIRST (z-order: behind everything)
        // Route to the correct display based on user's screen choice
        baseLayerWM = null
        if (baseLayerWidget != null) {
            val targetWM = if (baseLayerThemeConfig!!.resolvedScreenTarget == ScreenTarget.SECONDARY) {
                val secDisplay = DisplayHelper.getSecondaryDisplay(this)
                if (secDisplay != null) {
                    val secCtx = createWindowContext(
                        secDisplay,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                    val secWM = secCtx.getSystemService(WINDOW_SERVICE) as WindowManager
                    baseLayerWM = secWM
                    secWM
                } else {
                    windowManager
                }
            } else {
                windowManager
            }
            createWidgetWindow(baseLayerThemeConfig!!, baseLayerWidget, null, interactive = isInteractiveTheme, wm = targetWM, appConfig = appConfig)
        }

        // Mark dual-screen active when base-layer is on secondary display
        if (baseLayerWM != null) {
            isDualScreenActive = true
        }

        // Create regular widgets on top
        savedLayout = configManager.loadOverlayLayout(config.id, OverlayConstants.SCREEN_PRIMARY)

        for (widget in primaryWidgets) {
            val layoutState = savedLayout?.widgets?.get(widget.id)
            createWidgetWindow(config, widget, layoutState, appConfig = appConfig)
        }

        // ThemeOverlayChrome creation, after base-layer widget
        if (baseLayerWidget != null && isInteractiveTheme) {
            val baseWW = widgetViews[baseLayerWidget.id]
            if (baseWW != null) {
                themeChrome = ThemeOverlayChrome(
                    context = this,
                    windowManager = baseLayerWM ?: windowManager,
                    themeConfig = baseLayerThemeConfig!!,
                    currentSettings = { memoryService?.uiState?.value?.settings ?: emptyMap() },
                    onExit = { exitInteractiveTheme() },
                    onReload = { widgetViews[baseLayerWidget.id]?.webView?.reload() },
                    onSettingChanged = { key, value -> handleThemeSettingChanged(key, value) }
                )
                themeChrome?.show()
            }
        }

        // Show recovery pill if saved layout has all non-base-layer widgets disabled
        val editableWidgets = widgetViews.values.filter { !it.widget.isBaseLayer }
        if (editableWidgets.isNotEmpty() && editableWidgets.all { !it.enabled }) {
            showRecoveryPill()
        }

        // Create secondary display presentation if we have secondary widgets or a secondary theme
        // In builder mode, always create it so the user can add widgets to the secondary screen
        val secondaryDisplay = DisplayHelper.getSecondaryDisplay(this)
        if (secondaryDisplay != null && (secondaryWidgets.isNotEmpty() || isBuilderMode || secondaryThemeConfig != null)) {
            val secThemeConfig = secondaryThemeConfig ?: config
            val secThemeId = secThemeConfig.id
            val secAssetsPath = secThemeConfig.assetsPath
            val presentation = OverlayPresentation(
                serviceContext = this,
                display = secondaryDisplay,
                themeId = secThemeId,
                themesRootDir = File(configManager.getRootDir(), "themes"),
                devMode = appConfig.devMode,
                devUrl = appConfig.devUrl,
                widgetsBasePath = secAssetsPath,
                devThemePath = secThemeConfig.devThemePath()
            )
            presentation.show()

            val secLayout = configManager.loadOverlayLayout(secThemeId, OverlayConstants.SCREEN_SECONDARY)
            for (widget in secondaryWidgets) {
                val layoutState = secLayout?.widgets?.get(widget.id)
                presentation.addWidget(widget, layoutState)
            }

            // If secondary config is a theme (no widgets), show base-layer widget on secondary
            if (secondaryWidgets.isEmpty() && secondaryThemeConfig != null) {
                val secBaseWidget = secondaryThemeConfig!!.toBaseLayerWidget()
                presentation.addWidget(secBaseWidget, null)
            }

            overlayPresentation = presentation
            isDualScreenActive = true
        }

        // Show secondary recovery pill when there are no primary widgets
        // (user can't long-press primary to enter edit mode)
        if (editableWidgets.isEmpty() && secondaryWidgets.isNotEmpty()) {
            overlayPresentation?.showRecoveryPill { enterEditMode() }
        }

        startDataCollection()

        if (appConfig.devMode && appConfig.devUrl.isNotBlank()) {
            startDevReloadPolling(appConfig.devUrl, config.devThemePath())
        }

        // Wire JS bridge to all widget WebViews now that MemoryService exists
        memoryService?.let { ms ->
            val bridge = createBridge(config, ms, appConfig)
            overlayBridge = bridge
            for ((_, ww) in widgetViews) {
                ww.webView.addJavascriptInterface(bridge, "emulink")
            }
            overlayPresentation?.addBridge(bridge)
        }

        // Update notification with swap action if dual-screen
        if (isDualScreenActive) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(OverlayConstants.NOTIFICATION_ID, createNotification())
        }

        if (isBuilderMode) {
            serviceScope.launch {
                delay(OverlayConstants.BUILDER_EDIT_MODE_DELAY_MS)
                enterEditMode()
            }
        }

        return START_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWidgetWindow(
        themeConfig: ThemeConfig,
        widget: WidgetConfig,
        layoutState: WidgetLayoutState?,
        interactive: Boolean = false,
        wm: WindowManager = windowManager,
        appConfig: AppConfig = configManager.getAppConfig(),
        devThemePath: String = themeConfig.devThemePath()
    ) {
        val isBase = widget.isBaseLayer

        val density = resources.displayMetrics.density

        val params = if (isBase) {
            val flags = if (interactive) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.OPAQUE
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                    setFitInsetsSides(0)
                }
            }
        } else {
            val x = layoutState?.x ?: widget.defaultX
            val y = layoutState?.y ?: widget.defaultY
            val width = layoutState?.width ?: widget.defaultWidth
            val height = layoutState?.height ?: widget.defaultHeight
            val widthPx = (width * density).toInt()
            val heightPx = (height * density).toInt()
            val xPx = (x * density).toInt()
            val yPx = (y * density).toInt()
            WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = xPx
                this.y = yPx
            }
        }

        val enabled = if (isBase) true else (layoutState?.enabled ?: true)
        val alpha = if (isBase) 1.0f else (layoutState?.alpha ?: 1.0f)
        val bgColor = if (widget.transparent) 0x00000000 else 0xFF000000.toInt()

        val container = if (isBase && interactive) {
            object : WidgetContainer(this) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        themeChrome?.onDoubleTap()
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
        } else {
            WidgetContainer(this)
        }

        val interceptAssetsPath = widget.assetsPath
        val interceptDevMode = appConfig.devMode
        val interceptDevUrl = appConfig.devUrl
        val interceptDevThemePath = devThemePath
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.configureForOverlay()
            setBackgroundColor(bgColor)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (BuildConfig.DEBUG) {
                        consoleMessage?.let {
                            Log.d(TAG, "[Widget:${widget.id}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                    }
                    if (isBase) {
                        consoleMessage?.let {
                            themeChrome?.addDebugLog("${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject current data immediately so widget doesn't start blank
                    val js = cachedDataJs
                        ?: memoryService?.uiState?.value?.let { buildDataInjectionJs(it, gson) }
                    if (js != null) {
                        try {
                            view?.evaluateJavascript(js, null)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "Failed to push initial data to widget: ${e.message}")
                            }
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Widget ${widget.id} page finished: $url")
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val baseDir = if (interceptAssetsPath != null) {
                        File(interceptAssetsPath)
                    } else if (themeConfig.assetsPath != null) {
                        File(themeConfig.assetsPath)
                    } else {
                        File(configManager.getRootDir(), "themes/${themeConfig.id}")
                    }
                    WebInterceptor.intercept(url, baseDir, interceptDevMode, interceptDevUrl, interceptDevThemePath)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl("https://app.emulink/${widget.src}")
        }

        container.addView(webView)

        if (!enabled) {
            container.visibility = View.GONE
        } else {
            container.alpha = alpha
        }

        if (!isBase) {
            setupLongPressDetection(webView)
        }

        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add widget view: ${widget.id}", e)
            webView.destroy()
            return
        }

        widgetViews[widget.id] = WidgetWindow(
            widget = widget,
            container = container,
            webView = webView,
            params = params,
            enabled = enabled,
            alpha = alpha
        )
    }

    private fun createBridge(config: ThemeConfig, ms: MemoryService, appConfig: AppConfig): OverlayBridge =
        OverlayBridge(
            context = this,
            memoryService = ms,
            scope = serviceScope,
            themeId = config.id,
            themesRootDir = File(configManager.getRootDir(), "themes"),
            devMode = appConfig.devMode,
            devUrl = appConfig.devUrl,
            devThemePath = config.devThemePath(),
            assetsDir = config.assetsPath?.let { File(it) },
            onSave = if (isInteractiveTheme) { k, v -> handleThemeSettingChanged(k, v) } else null,
            onExit = if (isInteractiveTheme) { { exitInteractiveTheme() } } else null,
            onOpenSettings = if (isInteractiveTheme) { { themeChrome?.showSettingsDialog() } } else null,
            debugLogCallback = if (isInteractiveTheme) { { themeChrome?.addDebugLog(it) } } else null
        )

    private fun startDataCollection() {
        val ms = (application as EmuLnkApplication).memoryService
        memoryService = ms

        dataCollectionJob = serviceScope.launch {
            ms.uiState.collectLatest { gameData ->
                pushDataToWidgets(gameData)

                // Detect game disconnection for interactive themes
                if (isInteractiveTheme) {
                    val nowConnected = gameData.isConnected
                    if (lastConnectedState && !nowConnected) {
                        val baseWV = widgetViews.values.firstOrNull { it.widget.isBaseLayer }?.webView
                        baseWV?.evaluateJavascript(
                            "if(typeof onGameClosed !== 'undefined') onGameClosed()", null
                        )
                    }
                    lastConnectedState = nowConnected
                }
            }
        }
    }

    private fun startDevReloadPolling(devUrl: String, devThemePath: String) {
        devReloadJob?.cancel()
        lastDevMtime = null
        devReloadJob = serviceScope.launch(Dispatchers.IO) {
            val pollUrl = "${devUrl.removeSuffix("/")}/__dev_reload?path=themes/$devThemePath"
            while (true) {
                delay(OverlayConstants.DEV_RELOAD_POLL_INTERVAL_MS)
                try {
                    val conn = URL(pollUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = NetworkConstants.CONNECT_TIMEOUT_MS
                    conn.readTimeout = NetworkConstants.READ_TIMEOUT_MS
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val prev = lastDevMtime
                        lastDevMtime = body
                        // Skip first tick (prev == null) - only reload when mtime actually changes
                        if (prev != null && body != prev) {
                            withContext(Dispatchers.Main) {
                                for ((_, ww) in widgetViews) {
                                    ww.webView.reload()
                                }
                                overlayPresentation?.reloadAll()
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Dev reload poll failed: ${e.message}")
                }
            }
        }
    }

    private fun handleThemeSettingChanged(key: String, value: String) {
        val ms = memoryService ?: return
        ms.updateState { state ->
            state.copy(settings = state.settings.toMutableMap().apply { put(key, value) })
        }
        val themeId = (baseLayerSourceConfig ?: themeConfig)?.id ?: return
        settingSaveJob?.cancel()
        settingSaveJob = serviceScope.launch(Dispatchers.IO) {
            delay(500)
            try {
                val saveFile = File(configManager.getSavesDir(), "$themeId.json")
                saveFile.parentFile?.mkdirs()
                val allSettings = ms.uiState.value.settings
                saveFile.writeText(gson.toJson(allSettings))
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Failed to save theme setting", e)
                }
            }
        }
    }

    private fun pushDataToWidgets(gameData: GameData) {
        val js = if (gameData != lastGameData) {
            val built = buildDataInjectionJs(gameData, gson)
            lastGameData = gameData
            cachedDataJs = built
            built
        } else {
            cachedDataJs ?: return
        }
        for ((_, ww) in widgetViews) {
            if (ww.enabled) {
                try {
                    ww.webView.evaluateJavascript(js, null)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed to push data to widget: ${e.message}")
                    }
                }
            }
        }
        overlayPresentation?.pushDataToWidgets(js, gameData)
    }

    /**
     * Sets up long-press detection on the WebView itself.
     * Must be on the WebView (not the container) because WebView consumes
     * all touch events, so a parent FrameLayout's OnTouchListener never fires.
     * Returns false so the WebView still handles touch normally.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressDetection(webView: WebView) {
        var longPressJob: Job? = null
        var startX = 0f
        var startY = 0f
        val threshold = OverlayConstants.DRAG_THRESHOLD_PX

        webView.setOnTouchListener { _, event ->
            if (isEditMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    longPressJob = serviceScope.launch {
                        delay(OverlayConstants.LONG_PRESS_THRESHOLD_MS)
                        enterEditMode()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dx * dx + dy * dy > threshold * threshold) {
                        longPressJob?.cancel()
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    false
                }
                else -> false
            }
        }
    }

    fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        removeRecoveryPill()
        selectedWidget = widgetViews.values.firstOrNull { !it.widget.isBaseLayer }

        // Create BuilderSession for undo/redo tracking
        val session = BuilderSession(isBuilderMode = isBuilderMode)
        session.setEditMode(true)
        builderSession = session
        sessionObserverJob = serviceScope.launch {
            session.state.collectLatest { state ->
                undoButton?.apply { alpha = if (state.canUndo) 1.0f else 0.3f; isEnabled = state.canUndo }
                redoButton?.apply { alpha = if (state.canRedo) 1.0f else 0.3f; isEnabled = state.canRedo }
            }
        }

        // Add scrim backdrop
        val scrim = View(this).apply {
            setBackgroundColor(OverlayConstants.EDIT_SCRIM_COLOR.toInt())
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            getRealScreenHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(scrim, scrimParams)
            scrimView = scrim
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add scrim", e)
        }

        for ((_, ww) in widgetViews) {
            if (ww.widget.isBaseLayer) continue

            try {
                windowManager.removeView(ww.container)
            } catch (e: Exception) {
                Log.w(TAG, "enterEditMode: removeView failed", e)
            }

            ww.params.flags = ww.params.flags and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            ww.container.interceptAllTouches = true

            // Make disabled widgets visible but dimmed, preserve actual alpha
            if (!ww.enabled) {
                ww.container.visibility = View.VISIBLE
                ww.container.alpha = ww.alpha * 0.5f
            }

            updateWidgetEditVisual(ww, ww == selectedWidget)

            try {
                windowManager.addView(ww.container, ww.params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-add widget for edit mode", e)
            }

            setupDragHandling(ww)
        }

        // Activate edit mode on secondary display, pass session for gesture handling
        overlayPresentation?.setSession(session)
        overlayPresentation?.enterEditMode { widgetId ->
            // Secondary widget selected, deselect primary
            selectedWidget?.let { updateWidgetEditVisual(it, false) }
            selectedWidget = null
            selectedSecondaryWidgetId = widgetId
            overlayPresentation?.selectWidget(widgetId)
            session.selectWidget(widgetId, ScreenTarget.SECONDARY)
            updateControlsLabel()
        }

        showEditModeControls()
        updateControlsLabel()
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        selectedWidget = null
        selectedSecondaryWidgetId = null
        undoButton = null
        redoButton = null
        alphaSeekBar = null
        alphaLabel = null
        alphaSliderRow = null

        // Clean up session
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        builderSession?.setEditMode(false)
        builderSession = null

        // Exit edit mode on secondary display
        overlayPresentation?.setSession(null)
        overlayPresentation?.exitEditMode()

        // Remove controls bar
        windowManager.safeRemoveView(controlsBar)
        controlsBar = null
        controlsLabel = null

        // Re-add widget containers (restores normal flags, removes from above scrim)
        for ((_, ww) in widgetViews) {
            if (ww.widget.isBaseLayer) continue

            windowManager.safeRemoveView(ww.container)

            ww.params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            ww.container.background = null
            ww.container.interceptAllTouches = false
            setupLongPressDetection(ww.webView)

            // Restore visibility: disabled → GONE, enabled → saved alpha
            if (!ww.enabled) {
                ww.container.visibility = View.GONE
            } else {
                ww.container.alpha = ww.alpha
            }

            try {
                windowManager.addView(ww.container, ww.params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-add widget after edit mode", e)
            }
        }

        windowManager.safeRemoveView(scrimView)
        scrimView = null

        saveCurrentLayout()

        // Save custom overlay config in builder mode
        if (isBuilderMode) {
            val config = themeConfig
            if (config != null) {
                val primaryIds = widgetViews.keys.filter { !widgetViews[it]!!.widget.isBaseLayer }
                val secondaryIds = secondaryWidgets.map { it.id }
                val allIds = primaryIds + secondaryIds
                val assignments = mutableMapOf<String, ScreenTarget>()
                for (id in primaryIds) assignments[id] = ScreenTarget.PRIMARY
                for (id in secondaryIds) assignments[id] = ScreenTarget.SECONDARY
                val profileId = config.targetProfileId
                configManager.saveCustomOverlayConfig(
                    CustomOverlayConfig(
                        profileId = profileId,
                        selectedWidgetIds = allIds,
                        console = config.targetConsole,
                        screenAssignments = assignments
                    )
                )
            }
        }

        val editableAfter = widgetViews.values.filter { !it.widget.isBaseLayer }
        if (editableAfter.isNotEmpty() && editableAfter.all { !it.enabled }) {
            showRecoveryPill()
        }

        // Re-show secondary recovery pill if no primary widgets exist
        if (editableAfter.isEmpty() && overlayPresentation != null) {
            overlayPresentation?.showRecoveryPill { enterEditMode() }
        }
    }

    private fun updateWidgetEditVisual(ww: WidgetWindow, isSelected: Boolean) {
        ww.container.background = OverlayConstants.createEditBorder(
            resources.displayMetrics.density, ww.enabled, isSelected
        )
    }

    private fun updateControlsLabel() {
        val label = selectedWidget?.widget?.label
            ?: selectedSecondaryWidgetId?.let { id ->
                secondaryWidgets.find { it.id == id }?.label
            } ?: ""
        controlsLabel?.text = label

        // Update alpha slider visibility and value
        val sw = selectedWidget
        val secId = selectedSecondaryWidgetId
        val isBaseLayer = sw?.widget?.isBaseLayer == true
        val hasSelection = sw != null || secId != null

        if (!hasSelection || isBaseLayer) {
            alphaSliderRow?.visibility = View.GONE
        } else {
            alphaSliderRow?.visibility = View.VISIBLE
            val alpha = when {
                sw != null -> sw.alpha
                secId != null -> overlayPresentation?.getWidgetAlpha(secId) ?: 1.0f
                else -> 1.0f
            }
            val pct = (alpha * 100).toInt().coerceIn(
                OverlayConstants.ALPHA_SLIDER_MIN_PERCENT,
                OverlayConstants.ALPHA_SLIDER_MAX_PERCENT
            )
            alphaSeekBar?.progress = pct - OverlayConstants.ALPHA_SLIDER_MIN_PERCENT
            alphaLabel?.text = "$pct%"
        }
    }

    private fun applyAlphaToSelected(alpha: Float) {
        val sw = selectedWidget
        val secId = selectedSecondaryWidgetId
        if (sw != null) {
            sw.alpha = alpha
            if (sw.enabled) sw.container.alpha = alpha
        } else if (secId != null) {
            overlayPresentation?.setWidgetAlpha(secId, alpha)
        }
    }

    private fun setupDragHandling(ww: WidgetWindow) {
        val session = builderSession ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = getRealScreenHeight()
        val density = resources.displayMetrics.density

        val handler = OverlayGestureHandler(
            context = this,
            session = session,
            widgetId = ww.widget.id,
            screen = ScreenTarget.PRIMARY,
            config = ww.widget,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            getPosition = { Pair(ww.params.x, ww.params.y) },
            getSize = { Pair(ww.params.width, ww.params.height) },
            onPositionChanged = { x, y ->
                ww.params.x = x
                ww.params.y = y
                try { windowManager.updateViewLayout(ww.container, ww.params) } catch (_: Exception) {}
            },
            onSizeChanged = { w, h ->
                ww.params.width = w
                ww.params.height = h
                clampToBounds(ww)
                try { windowManager.updateViewLayout(ww.container, ww.params) } catch (_: Exception) {}
            },
            onSelected = {
                // Deselect secondary widget if one was selected
                selectedSecondaryWidgetId?.let {
                    overlayPresentation?.selectWidget("")
                    selectedSecondaryWidgetId = null
                }
                val prevSelected = selectedWidget
                selectedWidget = ww
                if (prevSelected != null && prevSelected != ww) {
                    updateWidgetEditVisual(prevSelected, false)
                }
                updateWidgetEditVisual(ww, true)
                session.selectWidget(ww.widget.id, ScreenTarget.PRIMARY)
                updateControlsLabel()
            },
            onToggled = {
                val oldEnabled = ww.enabled
                toggleWidgetEnabled(ww)
                session.executeAction(EditAction.ToggleEnabled(
                    ww.widget.id, ScreenTarget.PRIMARY, oldEnabled, ww.enabled
                ))
            }
        )
        handler.attachTo(ww.container)
    }

    private fun getRealScreenHeight(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            size.y
        }
    }

    private fun clampToBounds(ww: WidgetWindow) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = getRealScreenHeight()
        ww.params.x = ww.params.x.coerceIn(0, maxOf(0, screenWidth - ww.params.width))
        ww.params.y = ww.params.y.coerceIn(0, maxOf(0, screenHeight - ww.params.height))
    }

    private fun exitInteractiveTheme() {
        val bringBack = Intent(this, MainActivity::class.java)
        bringBack.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        bringBack.putExtra(EXTRA_CLEAR_OVERLAY, true)
        startActivity(bringBack)
        stopSelf()
    }

    private fun toggleWidgetEnabled(ww: WidgetWindow) {
        ww.enabled = !ww.enabled
        if (ww.enabled) {
            ww.container.alpha = ww.alpha
        } else {
            ww.container.alpha = ww.alpha * 0.5f
        }
        updateWidgetEditVisual(ww, ww == selectedWidget)
    }

    private fun createIconButton(iconRes: Int, tintColor: Int, iconSizeDp: Int = 18, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        val iconSize = (iconSizeDp * density).toInt()
        val btnPad = (6 * density).toInt()
        val icon = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply {
            setBounds(0, 0, iconSize, iconSize)
            setTint(tintColor)
        }
        return TextView(this).apply {
            setCompoundDrawables(icon, null, null, null)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showEditModeControls() {
        val density = resources.displayMetrics.density

        // Pill background
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_ELEVATED)
            cornerRadius = 24 * density
        }

        val dp = density.toInt()

        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp)
            background = pillBg
            minimumWidth = (OverlayConstants.CONTROLS_MIN_WIDTH_DP * density).toInt()
        }

        // Widget label (also serves as drag handle for the controls bar)
        val label = TextView(this).apply {
            text = selectedWidget?.widget?.label ?: ""
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        controlsLabel = label

        // Drag handling for controls bar via the label
        var controlsInitialX = 0
        var controlsInitialY = 0
        var controlsInitialTouchX = 0f
        var controlsInitialTouchY = 0f
        var controlsDragging = false

        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = controlsBar?.layoutParams as? WindowManager.LayoutParams
                        ?: return@setOnTouchListener false
                    controlsInitialX = lp.x
                    controlsInitialY = lp.y
                    controlsInitialTouchX = event.rawX
                    controlsInitialTouchY = event.rawY
                    controlsDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - controlsInitialTouchX
                    val dy = event.rawY - controlsInitialTouchY
                    if (!controlsDragging &&
                        (abs(dx) > OverlayConstants.DRAG_THRESHOLD_PX ||
                         abs(dy) > OverlayConstants.DRAG_THRESHOLD_PX)) {
                        controlsDragging = true
                    }
                    if (controlsDragging) {
                        val lp = controlsBar?.layoutParams as? WindowManager.LayoutParams
                            ?: return@setOnTouchListener true
                        lp.x = controlsInitialX + dx.toInt()
                        lp.y = controlsInitialY - dy.toInt() // BOTTOM gravity: positive y = up
                        try {
                            windowManager.updateViewLayout(controlsBar, lp)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Controls bar drag: updateViewLayout failed", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        // Done button
        val doneBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.BRAND_PURPLE)
            cornerRadius = 16 * density
        }
        val doneButton = TextView(this).apply {
            text = getString(R.string.overlay_done)
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 13f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            background = doneBg
            setOnClickListener { exitEditMode() }
        }

        // Icon row: undo, redo, swap (if dual), save (builder only)
        val iconPad = (8 * density).toInt()
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val undoBtnView = createIconButton(R.drawable.ic_undo, UiColors.TEXT_SECONDARY) { undoLastAction() }.apply {
            alpha = 0.3f
            isEnabled = false
            setPadding(iconPad, iconPad, iconPad, iconPad)
        }
        undoButton = undoBtnView
        iconRow.addView(undoBtnView)

        val redoBtnView = createIconButton(R.drawable.ic_redo, UiColors.TEXT_SECONDARY) { redoLastAction() }.apply {
            alpha = 0.3f
            isEnabled = false
            setPadding(iconPad, iconPad, iconPad, iconPad)
        }
        redoButton = redoBtnView
        iconRow.addView(redoBtnView)

        if (isDualScreenActive) {
            iconRow.addView(createIconButton(R.drawable.ic_swap_vert, UiColors.TEXT_SECONDARY, iconSizeDp = 20) {
                exitEditMode()
                swapScreens()
                if (isBuilderMode) {
                    serviceScope.launch {
                        delay(OverlayConstants.BUILDER_EDIT_MODE_DELAY_MS)
                        enterEditMode()
                    }
                }
            }.apply {
                setPadding(iconPad, iconPad, iconPad, iconPad)
            })
        }

        if (isBuilderMode) {
            iconRow.addView(createIconButton(R.drawable.ic_save, UiColors.TEXT_SECONDARY, iconSizeDp = 20) {
                saveCurrentLayout()
                showSaveDialog()
            }.apply {
                setPadding(iconPad, iconPad, iconPad, iconPad)
            })
        }

        controlsLayout.addView(iconRow)

        // Divider
        controlsLayout.addView(View(this).apply {
            setBackgroundColor(UiColors.DIVIDER)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
        ).apply {
            topMargin = (3 * density).toInt()
            bottomMargin = (3 * density).toInt()
        })

        // Alpha slider row
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }

        val alphaIcon = TextView(this).apply {
            text = "\u03B1" // α
            setTextColor(UiColors.BRAND_PURPLE)
            textSize = 13f
            setPadding(0, 0, (6 * density).toInt(), 0)
        }
        sliderRow.addView(alphaIcon)

        val sliderRange = OverlayConstants.ALPHA_SLIDER_MAX_PERCENT - OverlayConstants.ALPHA_SLIDER_MIN_PERCENT
        val seekBar = SeekBar(this).apply {
            max = sliderRange
            progress = sliderRange // default 100%
            progressTintList = android.content.res.ColorStateList.valueOf(UiColors.BRAND_PURPLE)
            thumbTintList = android.content.res.ColorStateList.valueOf(UiColors.BRAND_PURPLE)
        }
        sliderRow.addView(seekBar, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        val pctLabel = TextView(this).apply {
            text = "100%"
            setTextColor(UiColors.TEXT_SECONDARY)
            textSize = 11f
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        sliderRow.addView(pctLabel)

        alphaSeekBar = seekBar
        alphaLabel = pctLabel
        alphaSliderRow = sliderRow

        var oldAlpha = 1.0f
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                val sw = selectedWidget
                val secId = selectedSecondaryWidgetId
                oldAlpha = when {
                    sw != null -> sw.alpha
                    secId != null -> overlayPresentation?.getWidgetAlpha(secId) ?: 1.0f
                    else -> 1.0f
                }
            }

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pct = progress + OverlayConstants.ALPHA_SLIDER_MIN_PERCENT
                pctLabel.text = "$pct%"
                applyAlphaToSelected(pct / 100f)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                val pct = bar.progress + OverlayConstants.ALPHA_SLIDER_MIN_PERCENT
                val newAlpha = pct / 100f
                if (newAlpha == oldAlpha) return
                val sw = selectedWidget
                val secId = selectedSecondaryWidgetId
                val widgetId = sw?.widget?.id ?: secId ?: return
                val screen = if (sw != null) ScreenTarget.PRIMARY else ScreenTarget.SECONDARY
                builderSession?.executeAction(
                    EditAction.ChangeAlpha(widgetId, screen, oldAlpha, newAlpha)
                )
            }
        })

        controlsLayout.addView(sliderRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Divider below slider
        controlsLayout.addView(View(this).apply {
            setBackgroundColor(UiColors.DIVIDER)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
        ).apply {
            topMargin = (2 * density).toInt()
            bottomMargin = (3 * density).toInt()
        })

        // Action row: label + done
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(label)
        actionRow.addView(doneButton)
        controlsLayout.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (OverlayConstants.GESTURE_NAV_INSET_DP * density).toInt()
        }

        try {
            windowManager.addView(controlsLayout, params)
            controlsBar = controlsLayout
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show edit controls", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addWidgetFromStore(storeWidget: StoreWidget, screenTarget: ScreenTarget, recordAction: Boolean = true) {
        val config = themeConfig ?: return
        // Check if widget already exists on this screen
        if (screenTarget == ScreenTarget.PRIMARY && widgetViews.containsKey(storeWidget.id)) return
        if (screenTarget == ScreenTarget.SECONDARY && secondaryWidgets.any { it.id == storeWidget.id }) return

        val widgetConfig = storeWidget.toWidgetConfig().copy(screenTarget = screenTarget)

        if (screenTarget == ScreenTarget.SECONDARY) {
            overlayPresentation?.addWidget(widgetConfig, null)
            secondaryWidgets = secondaryWidgets + widgetConfig
            overlayPresentation?.let { pres ->
                overlayBridge?.let { bridge -> pres.addBridge(bridge) }
                if (isEditMode) {
                    pres.enterEditMode { widgetId ->
                        selectedWidget?.let { updateWidgetEditVisual(it, false) }
                        selectedWidget = null
                        selectedSecondaryWidgetId = widgetId
                        pres.selectWidget(widgetId)
                        updateControlsLabel()
                    }
                }
            }
        } else {
            createWidgetWindow(config, widgetConfig, null)
            val ww = widgetViews[storeWidget.id] ?: return
            overlayBridge?.let { ww.webView.addJavascriptInterface(it, "emulink") }
            if (isEditMode) {
                // Re-setup for edit mode
                windowManager.safeRemoveView(ww.container)
                ww.params.flags = ww.params.flags and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                ww.container.interceptAllTouches = true
                updateWidgetEditVisual(ww, false)
                try { windowManager.addView(ww.container, ww.params) } catch (_: Exception) {}
                setupDragHandling(ww)
            }
        }
        if (recordAction) {
            builderSession?.executeAction(EditAction.AddWidget(storeWidget, screenTarget))
        }

    }

    private fun removeWidgetFromBuilder(widgetId: String, screenTarget: ScreenTarget, recordAction: Boolean = true) {
        val storeWidget = if (recordAction) availableStoreWidgets.find { it.id == widgetId } else null
        if (screenTarget == ScreenTarget.SECONDARY) {
            overlayPresentation?.removeWidget(widgetId)
            secondaryWidgets = secondaryWidgets.filter { it.id != widgetId }
            if (selectedSecondaryWidgetId == widgetId) {
                selectedSecondaryWidgetId = null
                updateControlsLabel()
            }
        } else {
            val ww = widgetViews.remove(widgetId) ?: return
            try {
                ww.webView.stopLoading()
                windowManager.removeView(ww.container)
                ww.webView.destroy()
            } catch (_: Exception) {}
            if (selectedWidget == ww) {
                selectedWidget = widgetViews.values.firstOrNull()
                selectedWidget?.let { updateWidgetEditVisual(it, true) }
                updateControlsLabel()
            }
        }
        if (recordAction && storeWidget != null) {
            builderSession?.executeAction(EditAction.RemoveWidget(storeWidget, screenTarget))
        }

    }

    private fun undoLastAction() {
        val action = builderSession?.undo() ?: return
        applyUndoRedoAction(action, isUndo = true)
    }

    private fun redoLastAction() {
        val action = builderSession?.redo() ?: return
        applyUndoRedoAction(action, isUndo = false)
    }

    private fun applyUndoRedoAction(action: EditAction, isUndo: Boolean) {
        val density = resources.displayMetrics.density
        when (action) {
            is EditAction.AddWidget -> {
                if (isUndo) removeWidgetFromBuilder(action.widget.id, action.screen, recordAction = false)
                else addWidgetFromStore(action.widget, action.screen, recordAction = false)
            }
            is EditAction.RemoveWidget -> {
                if (isUndo) addWidgetFromStore(action.widget, action.screen, recordAction = false)
                else removeWidgetFromBuilder(action.widget.id, action.screen, recordAction = false)
            }
            is EditAction.Move -> {
                val (x, y) = if (isUndo) Pair(action.oldX, action.oldY) else Pair(action.newX, action.newY)
                if (action.screen == ScreenTarget.PRIMARY) {
                    val ww = widgetViews[action.widgetId] ?: return
                    ww.params.x = (x * density).toInt()
                    ww.params.y = (y * density).toInt()
                    try { windowManager.updateViewLayout(ww.container, ww.params) } catch (_: Exception) {}
                } else {
                    overlayPresentation?.setWidgetPosition(action.widgetId, (x * density).toInt(), (y * density).toInt())
                }
            }
            is EditAction.Resize -> {
                val (w, h) = if (isUndo) Pair(action.oldW, action.oldH) else Pair(action.newW, action.newH)
                if (action.screen == ScreenTarget.PRIMARY) {
                    val ww = widgetViews[action.widgetId] ?: return
                    ww.params.width = (w * density).toInt()
                    ww.params.height = (h * density).toInt()
                    clampToBounds(ww)
                    try { windowManager.updateViewLayout(ww.container, ww.params) } catch (_: Exception) {}
                } else {
                    overlayPresentation?.setWidgetSize(action.widgetId, (w * density).toInt(), (h * density).toInt())
                }
            }
            is EditAction.ToggleEnabled -> {
                val newEnabled = if (isUndo) action.oldEnabled else action.newEnabled
                if (action.screen == ScreenTarget.PRIMARY) {
                    val ww = widgetViews[action.widgetId] ?: return
                    ww.enabled = newEnabled
                    ww.container.alpha = if (newEnabled) ww.alpha else ww.alpha * 0.5f
                    updateWidgetEditVisual(ww, ww == selectedWidget)
                } else {
                    overlayPresentation?.setWidgetEnabled(action.widgetId, newEnabled)
                }
            }
            is EditAction.ChangeAlpha -> {
                val alpha = if (isUndo) action.oldAlpha else action.newAlpha
                if (action.screen == ScreenTarget.PRIMARY) {
                    val ww = widgetViews[action.widgetId] ?: return
                    ww.alpha = alpha
                    if (ww.enabled) ww.container.alpha = alpha
                } else {
                    overlayPresentation?.setWidgetAlpha(action.widgetId, alpha)
                }
                updateControlsLabel()
            }
        }
    }


    /** Saves synchronously. Safe for onDestroy where the coroutine scope is about to cancel. */
    private fun saveCurrentLayout() {
        val config = themeConfig ?: return
        val density = resources.displayMetrics.density
        val states = mutableMapOf<String, WidgetLayoutState>()

        for ((id, ww) in widgetViews) {
            if (ww.widget.isBaseLayer) continue
            states[id] = WidgetLayoutState(
                x = (ww.params.x / density).toInt(),
                y = (ww.params.y / density).toInt(),
                width = (ww.params.width / density).toInt(),
                height = (ww.params.height / density).toInt(),
                enabled = ww.enabled,
                alpha = ww.alpha
            )
        }

        val screenId = if (isDualScreenActive) OverlayConstants.SCREEN_PRIMARY else null
        configManager.saveOverlayLayout(config.id, OverlayLayout(widgets = states), screenId)

        // Save secondary layout
        if (isDualScreenActive) {
            val secConfig = secondaryThemeConfig ?: config
            val secDisplay = DisplayHelper.getSecondaryDisplay(this)
            if (secDisplay != null) {
                val secDensity = DisplayHelper.getDisplayDensity(this, secDisplay)
                val secStates = overlayPresentation?.getWidgetStates(secDensity)
                if (secStates != null) {
                    configManager.saveOverlayLayout(
                        secConfig.id,
                        OverlayLayout(widgets = secStates),
                        OverlayConstants.SCREEN_SECONDARY
                    )
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            OverlayConstants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.overlay_active),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, OverlayConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(
                if (isDualScreenActive) getString(R.string.overlay_bundle_active)
                else getString(R.string.overlay_notification_text)
            )
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.overlay_edit_layout),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, OverlayService::class.java).apply { action = ACTION_EDIT_MODE },
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build())

        if (isDualScreenActive) {
            builder.addAction(Notification.Action.Builder(
                null, getString(R.string.swap_screens),
                PendingIntent.getService(
                    this, 2,
                    Intent(this, OverlayService::class.java).apply { action = ACTION_SWAP_SCREENS },
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build())
        }

        builder.addAction(Notification.Action.Builder(
            null, getString(R.string.exit),
            stopIntent
        ).build())
            .setOngoing(true)

        return builder.build()
    }

    private fun showRecoveryPill() {
        if (recoveryPill != null) return
        val density = resources.displayMetrics.density
        val (pill, params) = createRecoveryPill(this, density) { enterEditMode() }
        try {
            windowManager.addView(pill, params)
            recoveryPill = pill
            Toast.makeText(this, R.string.overlay_all_hidden, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show recovery pill", e)
        }
    }

    private fun removeRecoveryPill() {
        windowManager.safeRemoveView(recoveryPill)
        recoveryPill = null
    }

    private fun swapScreens() {
        if (!isDualScreenActive) return
        val config = themeConfig ?: return
        val secConfig = secondaryThemeConfig ?: config

        // Save current layouts before swap
        saveCurrentLayout()

        val secondaryDisplay = DisplayHelper.getSecondaryDisplay(this) ?: return
        val primaryDensity = resources.displayMetrics.density
        val primaryWidthDp = (resources.displayMetrics.widthPixels / primaryDensity).toInt()
        val primaryHeightDp = (getRealScreenHeight() / primaryDensity).toInt()
        val secDims = DisplayHelper.getSecondaryDimensions(this) ?: return

        // Capture current primary widget configs (exclude base-layer, it stays on primary)
        val currentPrimaryWidgets = widgetViews.values
            .filter { !it.widget.isBaseLayer }
            .map { it.widget }.toList()
        val currentSecondaryWidgets = secondaryWidgets.toList()
        val baseLayerWasOnSecondary = baseLayerWM != null

        // Tear down ThemeOverlayChrome before destroying views
        themeChrome?.dismiss()
        themeChrome = null

        // Destroy all: dismiss first so window detaches before WebViews are destroyed
        overlayPresentation?.let {
            it.dismiss()
            it.destroyAll()
            overlayPresentation = null
        }
        for ((_, ww) in widgetViews) {
            if (ww.widget.isBaseLayer) continue
            try {
                ww.webView.stopLoading()
                windowManager.removeView(ww.container)
                ww.webView.destroy()
            } catch (_: Exception) {}
        }
        // Remove base-layer
        val baseWW = widgetViews.values.firstOrNull { it.widget.isBaseLayer }
        if (baseWW != null) {
            try {
                val bwm = baseLayerWM ?: windowManager
                baseWW.webView.stopLoading()
                bwm.removeView(baseWW.container)
                baseWW.webView.destroy()
            } catch (_: Exception) {}
            baseLayerWM = null
        }
        widgetViews.clear()
        removeRecoveryPill()

        // Swap: old secondary -> new primary, old primary -> new secondary
        secondaryWidgets = currentPrimaryWidgets.map { it.copy(screenTarget = ScreenTarget.SECONDARY) }

        // Re-create base-layer widget, swap to opposite screen
        val swapAppConfig = configManager.getAppConfig()
        val baseLayerTheme = baseLayerSourceConfig
        val baseLayerWidget = baseLayerTheme?.toBaseLayerWidget()
        if (baseLayerWidget != null) {
            if (baseLayerWasOnSecondary) {
                // Was on secondary → now goes to primary
                createWidgetWindow(baseLayerTheme!!, baseLayerWidget, null, interactive = isInteractiveTheme, appConfig = swapAppConfig)
            } else {
                // Was on primary → now goes to secondary
                val secCtx = createWindowContext(
                    secondaryDisplay,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
                val secWM = secCtx.getSystemService(WINDOW_SERVICE) as WindowManager
                baseLayerWM = secWM
                createWidgetWindow(baseLayerTheme!!, baseLayerWidget, null, interactive = isInteractiveTheme, wm = secWM, appConfig = swapAppConfig)
            }
        }

        // Re-create primary widgets (from old secondary) with scaling
        val primaryLayoutForSec = configManager.loadOverlayLayout(secConfig.id, OverlayConstants.SCREEN_PRIMARY)
        val secLayoutForSec = configManager.loadOverlayLayout(secConfig.id, OverlayConstants.SCREEN_SECONDARY)
        for (widget in currentSecondaryWidgets) {
            val swappedWidget = widget.copy(screenTarget = ScreenTarget.PRIMARY)
            val sourceState = primaryLayoutForSec?.widgets?.get(widget.id)
                ?: secLayoutForSec?.widgets?.get(widget.id)
            val scaledState = sourceState?.let {
                scaleLayoutState(it, secDims.widthDp, secDims.heightDp, primaryWidthDp, primaryHeightDp, swappedWidget)
            }
            createWidgetWindow(secConfig, swappedWidget, scaledState, appConfig = swapAppConfig)
        }

        // Recreate ThemeOverlayChrome pointing to new base-layer WebView
        @Suppress("KotlinConstantConditions")
        if (baseLayerWidget != null && isInteractiveTheme && baseLayerTheme != null) {
            val newBaseWW = widgetViews[baseLayerWidget.id]
            if (newBaseWW != null) {
                themeChrome = ThemeOverlayChrome(
                    context = this,
                    windowManager = baseLayerWM ?: windowManager,
                    themeConfig = baseLayerTheme,
                    currentSettings = { memoryService?.uiState?.value?.settings ?: emptyMap() },
                    onExit = { exitInteractiveTheme() },
                    onReload = { newBaseWW.webView.reload() },
                    onSettingChanged = { key, value -> handleThemeSettingChanged(key, value) }
                )
                themeChrome?.show()
            }
        }

        // Re-create secondary presentation (from old primary) with scaling
        val presentation = OverlayPresentation(
            serviceContext = this,
            display = secondaryDisplay,
            themeId = config.id,
            themesRootDir = File(configManager.getRootDir(), "themes"),
            devMode = swapAppConfig.devMode,
            devUrl = swapAppConfig.devUrl,
            widgetsBasePath = config.assetsPath,
            devThemePath = config.devThemePath()
        )
        presentation.show()

        val secSavedLayout = configManager.loadOverlayLayout(config.id, OverlayConstants.SCREEN_SECONDARY)
        val priLayoutForPri = configManager.loadOverlayLayout(config.id, OverlayConstants.SCREEN_PRIMARY)
        for (widget in currentPrimaryWidgets) {
            val swappedWidget = widget.copy(screenTarget = ScreenTarget.SECONDARY)
            val sourceState = secSavedLayout?.widgets?.get(widget.id)
                ?: priLayoutForPri?.widgets?.get(widget.id)
            val scaledState = sourceState?.let {
                scaleLayoutState(it, primaryWidthDp, primaryHeightDp, secDims.widthDp, secDims.heightDp, swappedWidget)
            }
            presentation.addWidget(swappedWidget, scaledState)
        }

        // If new secondary has no widgets and the secondary config is a theme, add base-layer
        if (secondaryWidgets.isEmpty() && secondaryThemeConfig != null) {
            val secBaseWidget = secondaryThemeConfig!!.toBaseLayerWidget()
            presentation.addWidget(secBaseWidget, null)
        }

        overlayPresentation = presentation

        // Swap theme configs if user-paired bundle
        if (secondaryThemeConfig != null) {
            val tmp = themeConfig
            themeConfig = secondaryThemeConfig
            secondaryThemeConfig = tmp
        }

        // Recreate bridge with correct post-swap themeIds
        val swappedPrimaryConfig = themeConfig ?: return
        val swappedSecConfig = secondaryThemeConfig
        memoryService?.let { ms ->
            val appConfig = configManager.getAppConfig()
            val newBridge = createBridge(swappedPrimaryConfig, ms, appConfig)
            overlayBridge = newBridge
            for ((_, ww) in widgetViews) {
                ww.webView.addJavascriptInterface(newBridge, "emulink")
            }
            val secBridgeConfig = swappedSecConfig ?: swappedPrimaryConfig
            val secBridge = createBridge(secBridgeConfig, ms, appConfig)
            presentation.addBridge(secBridge)
        }

        // Push current data
        memoryService?.uiState?.value?.let { pushDataToWidgets(it) }

        // Re-evaluate recovery pills after swap
        val editableAfterSwap = widgetViews.values.filter { !it.widget.isBaseLayer }
        if (editableAfterSwap.isNotEmpty() && editableAfterSwap.all { !it.enabled }) {
            showRecoveryPill()
        }
        if (editableAfterSwap.isEmpty() && secondaryWidgets.isNotEmpty()) {
            overlayPresentation?.showRecoveryPill { enterEditMode() }
        }
    }

    private fun scaleLayoutState(
        source: WidgetLayoutState,
        srcWidth: Int, srcHeight: Int,
        tgtWidth: Int, tgtHeight: Int,
        widget: WidgetConfig
    ): WidgetLayoutState? {
        if (widget.isBaseLayer) return null
        if (srcWidth <= 0 || srcHeight <= 0) return source
        val newX = (source.x.toFloat() / srcWidth * tgtWidth).toInt()
        val newY = (source.y.toFloat() / srcHeight * tgtHeight).toInt()
        val newW = (source.width.toFloat() / srcWidth * tgtWidth).toInt().coerceAtLeast(widget.minWidth)
        val newH = (source.height.toFloat() / srcHeight * tgtHeight).toInt().coerceAtLeast(widget.minHeight)
        return source.copy(x = newX, y = newY, width = newW, height = newH)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSaveDialog() {
        if (saveDialogView != null) return
        controlsBar?.visibility = View.GONE
        overlayPresentation?.hide()
        val density = resources.displayMetrics.density
        val dp = density.toInt()

        val config = themeConfig ?: return
        val builderConfig = configManager.loadCustomOverlayConfig(config.targetProfileId)
        val hasDualWidgets = builderConfig?.screenAssignments?.values?.any { it == ScreenTarget.SECONDARY } == true

        // Scrim
        val scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dismissSaveDialog() }
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(scrim, scrimParams)
            saveDialogScrim = scrim
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add save dialog scrim", e)
            return
        }

        // Dialog card
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_ELEVATED)
            cornerRadius = 16 * density
        }

        val card = object : FrameLayout(this@OverlayService) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    dismissSaveDialog()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            background = cardBg
            setPadding(24 * dp, 20 * dp, 24 * dp, 16 * dp)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Title
        content.addView(TextView(this).apply {
            text = getString(R.string.save_overlay_title)
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 18f
        })

        // EditText
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.save_overlay_name_hint)
            setHintTextColor(UiColors.TEXT_SECONDARY)
            setTextColor(UiColors.TEXT_PRIMARY)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val editBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(UiColors.INPUT_BACKGROUND)
                setStroke((1 * density).toInt(), UiColors.TEXT_SECONDARY)
                cornerRadius = 8 * density
            }
            background = editBg
            setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp)
        }
        content.addView(editText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 * dp })

        // Icon picker row
        pickedIconUri = null
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconPreview = android.widget.ImageView(this).apply {
            val size = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            val previewBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(UiColors.INPUT_BACKGROUND)
                setStroke((1 * density).toInt(), UiColors.TEXT_SECONDARY)
                cornerRadius = 8 * density
            }
            background = previewBg
            visibility = View.GONE
        }
        iconPreviewView = iconPreview
        iconRow.addView(iconPreview)

        val iconLabel = TextView(this).apply {
            text = getString(R.string.save_overlay_add_icon)
            setTextColor(UiColors.BRAND_PURPLE)
            textSize = 13f
            setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp)
            setOnClickListener { onPickImage?.invoke() }
        }
        iconRow.addView(iconLabel)

        content.addView(iconRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 * dp })

        // Type label for dual-screen
        if (isDualScreenActive) {
            content.addView(TextView(this).apply {
                text = if (hasDualWidgets) getString(R.string.save_overlay_type_bundle)
                       else getString(R.string.save_overlay_type_overlay)
                setTextColor(UiColors.TEXT_SECONDARY)
                textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 * dp })
        }

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = TextView(this).apply {
            text = getString(R.string.back)
            setTextColor(UiColors.TEXT_SECONDARY)
            textSize = 14f
            setPadding(16 * dp, 10 * dp, 16 * dp, 10 * dp)
            setOnClickListener { dismissSaveDialog() }
        }
        buttonRow.addView(cancelBtn)

        val saveBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.BRAND_PURPLE)
            cornerRadius = 16 * density
        }
        val saveBtn = TextView(this).apply {
            text = getString(R.string.save_overlay)
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 14f
            setPadding(16 * dp, 10 * dp, 16 * dp, 10 * dp)
            background = saveBtnBg
            alpha = 0.4f
            isEnabled = false
            setOnClickListener {
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    onSaveCompleted?.invoke(name, pickedIconUri)
                    dismissSaveDialog()
                }
            }
        }
        buttonRow.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 8 * dp })

        // Enable/disable save button based on text
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                saveBtn.isEnabled = hasText
                saveBtn.alpha = if (hasText) 1.0f else 0.4f
            }
        })

        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 * dp })

        card.addView(content, FrameLayout.LayoutParams(
            (280 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Dialog window must NOT use FLAG_NOT_FOCUSABLE so EditText can receive keyboard input
        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            windowManager.addView(card, dialogParams)
            saveDialogView = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add save dialog", e)
            dismissSaveDialog()
            return
        }

        // Request focus and show keyboard
        editText.requestFocus()
        editText.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun onIconPicked(uri: android.net.Uri?) {
        pickedIconUri = uri
        iconPreviewView?.let { preview ->
            if (uri != null) {
                preview.setImageURI(uri)
                preview.visibility = View.VISIBLE
            } else {
                preview.setImageDrawable(null)
                preview.visibility = View.GONE
            }
        }
    }

    private fun dismissSaveDialog() {
        saveDialogView?.let { view ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
        windowManager.safeRemoveView(saveDialogView)
        saveDialogView = null
        windowManager.safeRemoveView(saveDialogScrim)
        saveDialogScrim = null
        pickedIconUri = null
        iconPreviewView = null
        controlsBar?.visibility = View.VISIBLE
        overlayPresentation?.unhide()
    }

    private fun removeAllWidgets() {
        devReloadJob?.cancel()
        devReloadJob = null
        lastDevMtime = null
        removeRecoveryPill()
        themeChrome?.dismiss()
        themeChrome = null

        windowManager.safeRemoveView(controlsBar)
        controlsBar = null
        controlsLabel = null

        windowManager.safeRemoveView(scrimView)
        scrimView = null

        // Dismiss secondary display presentation. Dismiss first, then destroy
        overlayPresentation?.let {
            it.dismiss()
            it.destroyAll()
            overlayPresentation = null
        }
        secondaryWidgets = emptyList()
        isDualScreenActive = false

        for ((_, ww) in widgetViews) {
            try {
                val wm = if (ww.widget.isBaseLayer) baseLayerWM ?: windowManager else windowManager
                ww.webView.stopLoading()
                wm.removeView(ww.container)
                ww.webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error removing widget: ${e.message}")
            }
        }
        widgetViews.clear()
        baseLayerWM = null
    }

    override fun onDestroy() {
        instance = null
        isEditMode = false

        dismissSaveDialog()
        saveCurrentLayout()
        removeAllWidgets()

        dataCollectionJob?.cancel()
        memoryService = null
        secondaryThemeConfig = null

        serviceScope.cancel()
        super.onDestroy()
    }

    private class WidgetWindow(
        val widget: WidgetConfig,
        val container: WidgetContainer,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        var enabled: Boolean,
        var alpha: Float
    )
}

/**
 * Custom FrameLayout that can intercept all child touch events.
 * Needed because WebView consumes touches. Without interception,
 * OnTouchListeners set on the container never fire.
 */
internal open class WidgetContainer(context: android.content.Context) : FrameLayout(context) {
    var interceptAllTouches = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return interceptAllTouches || super.onInterceptTouchEvent(ev)
    }
}
