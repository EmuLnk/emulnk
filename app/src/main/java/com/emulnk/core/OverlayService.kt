package com.emulnk.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.emulnk.BuildConfig
import com.emulnk.MainActivity
import com.emulnk.R
import com.emulnk.bridge.OverlayBridge
import com.emulnk.data.ConfigManager
import com.emulnk.EmuLnkApplication
import com.emulnk.model.GameData
import com.emulnk.model.OverlayLayout
import com.emulnk.model.ThemeConfig
import com.emulnk.model.WidgetConfig
import com.emulnk.model.WidgetLayoutState
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that manages floating WebView overlay widgets
 * using SYSTEM_ALERT_WINDOW (TYPE_APPLICATION_OVERLAY).
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val EXTRA_THEME_JSON = "theme_json"
        const val ACTION_STOP = "com.emulnk.STOP_OVERLAY"
        const val ACTION_EDIT_MODE = "com.emulnk.EDIT_MODE"

        private var instance: OverlayService? = null
        fun isRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()
    private lateinit var windowManager: WindowManager
    private lateinit var configManager: ConfigManager

    private var memoryService: MemoryService? = null
    private var overlayBridge: OverlayBridge? = null
    private var dataCollectionJob: Job? = null

    private var themeConfig: ThemeConfig? = null
    private val widgetViews = mutableMapOf<String, WidgetWindow>()
    private var savedLayout: OverlayLayout? = null

    private var isEditMode = false
    private var scrimView: View? = null
    private var controlsBar: View? = null
    private var controlsLabel: TextView? = null
    private var selectedWidget: WidgetWindow? = null
    private var recoveryPill: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configManager = ConfigManager(this)
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

        val themeJson = intent?.getStringExtra(EXTRA_THEME_JSON) ?: run {
            Log.e(TAG, "No theme config provided")
            stopSelf()
            return START_NOT_STICKY
        }

        // Clean up existing widgets if service is restarted with a new theme
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

        val config = themeConfig ?: run { stopSelf(); return START_NOT_STICKY }
        val widgets = config.widgets

        if (widgets.isNullOrEmpty()) {
            Log.e(TAG, "No widgets defined in overlay theme: ${config.id}")
            stopSelf()
            return START_NOT_STICKY
        }

        savedLayout = configManager.loadOverlayLayout(config.id)

        for (widget in widgets) {
            val layoutState = savedLayout?.widgets?.get(widget.id)
            createWidgetWindow(config, widget, layoutState)
        }

        // Show recovery pill if saved layout has all widgets disabled
        if (widgetViews.values.all { !it.enabled }) {
            showRecoveryPill()
        }

        startDataCollection()

        // Wire JS bridge to all widget WebViews now that MemoryService exists
        memoryService?.let { ms ->
            val appConfig = configManager.getAppConfig()
            val bridge = OverlayBridge(
                context = this,
                memoryService = ms,
                scope = serviceScope,
                themeId = config.id,
                themesRootDir = File(configManager.getRootDir(), "themes"),
                devMode = appConfig.devMode,
                devUrl = appConfig.devUrl
            )
            overlayBridge = bridge
            for ((_, ww) in widgetViews) {
                ww.webView.addJavascriptInterface(bridge, "emulink")
            }
        }

        return START_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWidgetWindow(
        themeConfig: ThemeConfig,
        widget: WidgetConfig,
        layoutState: WidgetLayoutState?
    ) {
        val x = layoutState?.x ?: widget.defaultX
        val y = layoutState?.y ?: widget.defaultY
        val width = layoutState?.width ?: widget.defaultWidth
        val height = layoutState?.height ?: widget.defaultHeight
        val enabled = layoutState?.enabled ?: true
        val alpha = layoutState?.alpha ?: 1.0f

        val density = resources.displayMetrics.density
        val widthPx = (width * density).toInt()
        val heightPx = (height * density).toInt()
        val xPx = (x * density).toInt()
        val yPx = (y * density).toInt()

        val params = WindowManager.LayoutParams(
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

        val container = WidgetContainer(this)

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            setBackgroundColor(0x00000000)

            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (BuildConfig.DEBUG) {
                        consoleMessage?.let {
                            Log.d(TAG, "[Widget:${widget.id}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject current data immediately so widget doesn't start blank
                    memoryService?.uiState?.value?.let { pushDataToWidget(view, it) }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Widget ${widget.id} page finished: $url")
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("https://app.emulink/")) {
                        val fileName = url.replace("https://app.emulink/", "")
                        val themesRoot = File(configManager.getRootDir(), "themes")
                        val themeDir = File(themesRoot, themeConfig.id)
                        val requestedFile = if (fileName.isEmpty() || fileName == "/") "index.html" else fileName
                        val file = File(themeDir, requestedFile).canonicalFile

                        // Path traversal protection
                        if (!file.canonicalPath.startsWith(themeDir.canonicalPath + File.separator) &&
                            file.canonicalPath != themeDir.canonicalPath
                        ) {
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "Path traversal blocked: $fileName")
                            }
                            return null
                        }

                        if (file.exists()) {
                            val mimeType = when (file.extension.lowercase()) {
                                "html" -> "text/html"; "css" -> "text/css"; "js" -> "application/javascript"
                                "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
                                "svg" -> "image/svg+xml"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
                                else -> "application/octet-stream"
                            }
                            return try {
                                val inputStream = FileInputStream(file)
                                try {
                                    WebResourceResponse(mimeType, "UTF-8", inputStream)
                                } catch (e: Exception) {
                                    inputStream.close()
                                    throw e
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Interception failed for $url", e)
                                null
                            }
                        }
                    }
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

        setupLongPressDetection(webView)

        try {
            windowManager.addView(container, params)
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

    private fun startDataCollection() {
        val ms = (application as EmuLnkApplication).memoryService
        memoryService = ms

        dataCollectionJob = serviceScope.launch {
            ms.uiState.collectLatest { gameData ->
                pushDataToWidgets(gameData)
            }
        }
    }

    private fun pushDataToWidget(view: WebView?, gameData: GameData) {
        view ?: return
        val jsonData = gson.toJson(gameData)
        val encodedData = android.util.Base64.encodeToString(
            jsonData.toByteArray(),
            android.util.Base64.NO_WRAP
        )
        val js = "if(typeof updateData !== 'undefined') updateData('$encodedData', true)"
        try {
            view.evaluateJavascript(js, null)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to push data to widget: ${e.message}")
            }
        }
    }

    private fun pushDataToWidgets(gameData: GameData) {
        for ((_, ww) in widgetViews) {
            if (ww.enabled) {
                pushDataToWidget(ww.webView, gameData)
            }
        }
    }

    /**
     * Sets up long-press detection on the WebView itself.
     * Must be on the WebView (not the container) because WebView consumes
     * all touch events — a parent FrameLayout's OnTouchListener never fires.
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
        selectedWidget = widgetViews.values.firstOrNull()

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

        // Re-add widget containers above scrim (WindowManager z-order = insertion order)
        for ((_, ww) in widgetViews) {
            try {
                windowManager.removeView(ww.container)
            } catch (_: Exception) {}

            ww.params.flags = ww.params.flags and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            ww.container.interceptAllTouches = true

            // Make disabled widgets visible but dimmed in edit mode
            if (!ww.enabled) {
                ww.container.visibility = View.VISIBLE
                ww.container.alpha = 0.3f
            }

            updateWidgetEditVisual(ww, ww == selectedWidget)

            try {
                windowManager.addView(ww.container, ww.params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-add widget for edit mode", e)
            }

            setupDragHandling(ww)
        }

        showEditModeControls()
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        selectedWidget = null

        // Remove controls bar
        controlsBar?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            controlsBar = null
        }
        controlsLabel = null

        // Re-add widget containers (restores normal flags, removes from above scrim)
        for ((_, ww) in widgetViews) {
            try {
                windowManager.removeView(ww.container)
            } catch (_: Exception) {}

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

        // Remove scrim
        scrimView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            scrimView = null
        }

        saveCurrentLayout()

        // If all widgets are disabled, show recovery pill so user can re-enter edit mode
        if (widgetViews.values.all { !it.enabled }) {
            showRecoveryPill()
        }
    }

    private fun updateWidgetEditVisual(ww: WidgetWindow, isSelected: Boolean) {
        val density = resources.displayMetrics.density
        val radiusPx = OverlayConstants.EDIT_BORDER_RADIUS_DP * density

        val border = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000) // transparent fill
            cornerRadius = radiusPx

            if (!ww.enabled) {
                // Disabled: red border
                setStroke((OverlayConstants.EDIT_BORDER_NORMAL_WIDTH_DP * density).toInt(), 0xFFFF5252.toInt())
            } else if (isSelected) {
                // Selected + enabled: cyan border
                setStroke((OverlayConstants.EDIT_BORDER_SELECTED_WIDTH_DP * density).toInt(), 0xFF00E5FF.toInt())
            } else {
                // Unselected + enabled: divider border
                setStroke((OverlayConstants.EDIT_BORDER_NORMAL_WIDTH_DP * density).toInt(), 0xFF2A2650.toInt())
            }
        }
        ww.container.background = border
    }

    private fun updateControlsLabel() {
        controlsLabel?.text = selectedWidget?.widget?.label ?: ""
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandling(ww: WidgetWindow) {
        val density = resources.displayMetrics.density
        val minWidthPx = (ww.widget.minWidth * density).toInt()
        val minHeightPx = (ww.widget.minHeight * density).toInt()

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!ww.widget.resizable) return false
                val factor = detector.scaleFactor
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = getRealScreenHeight()
                ww.params.width = (ww.params.width * factor).toInt().coerceIn(minWidthPx, screenWidth)
                ww.params.height = (ww.params.height * factor).toInt().coerceIn(minHeightPx, screenHeight)
                clampToBounds(ww)
                try {
                    windowManager.updateViewLayout(ww.container, ww.params)
                } catch (_: Exception) {}
                return true
            }
        })

        ww.container.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ww.params.x
                    initialY = ww.params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) return@setOnTouchListener true
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > OverlayConstants.DRAG_THRESHOLD_PX || kotlin.math.abs(dy) > OverlayConstants.DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        ww.params.x = initialX + dx.toInt()
                        ww.params.y = initialY + dy.toInt()
                        clampToBounds(ww)
                        snapToEdges(ww)
                        try {
                            windowManager.updateViewLayout(ww.container, ww.params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasAlreadySelected = (selectedWidget == ww)

                    // Update selection
                    val prevSelected = selectedWidget
                    selectedWidget = ww
                    if (prevSelected != null && prevSelected != ww) {
                        updateWidgetEditVisual(prevSelected, false)
                    }
                    updateWidgetEditVisual(ww, true)
                    updateControlsLabel()

                    // Only toggle if tapped (not dragged) on already-selected widget
                    if (!isDragging && wasAlreadySelected) {
                        toggleWidgetEnabled(ww)
                    }
                    true
                }
                else -> false
            }
        }
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

    private fun snapToEdges(ww: WidgetWindow) {
        val density = resources.displayMetrics.density
        val snapPx = (OverlayConstants.SNAP_THRESHOLD_DP * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = getRealScreenHeight()

        if (ww.params.x in 1..snapPx) ww.params.x = 0
        if (ww.params.y in 1..snapPx) ww.params.y = 0

        val rightEdge = maxOf(0, screenWidth - ww.params.width)
        if (rightEdge > 0 && ww.params.x in (rightEdge - snapPx) until rightEdge) ww.params.x = rightEdge

        val bottomEdge = maxOf(0, screenHeight - ww.params.height)
        if (bottomEdge > 0 && ww.params.y in (bottomEdge - snapPx) until bottomEdge) ww.params.y = bottomEdge
    }

    private fun toggleWidgetEnabled(ww: WidgetWindow) {
        ww.enabled = !ww.enabled
        if (ww.enabled) {
            ww.container.alpha = ww.alpha
        } else {
            ww.container.alpha = 0.3f
        }
        updateWidgetEditVisual(ww, ww == selectedWidget)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showEditModeControls() {
        val density = resources.displayMetrics.density

        // Pill background
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1E1A3A.toInt()) // SurfaceElevated
            cornerRadius = 24 * density
        }

        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = pillBg
        }

        // Widget label (also serves as drag handle for the controls bar)
        val label = TextView(this).apply {
            text = selectedWidget?.widget?.label ?: ""
            setTextColor(0xFFEDE9FC.toInt()) // TextPrimary
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
                        (kotlin.math.abs(dx) > OverlayConstants.DRAG_THRESHOLD_PX ||
                         kotlin.math.abs(dy) > OverlayConstants.DRAG_THRESHOLD_PX)) {
                        controlsDragging = true
                    }
                    if (controlsDragging) {
                        val lp = controlsBar?.layoutParams as? WindowManager.LayoutParams
                            ?: return@setOnTouchListener true
                        lp.x = controlsInitialX + dx.toInt()
                        lp.y = controlsInitialY - dy.toInt() // BOTTOM gravity: positive y = up
                        try {
                            windowManager.updateViewLayout(controlsBar, lp)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        // Reset button
        val resetButton = TextView(this).apply {
            text = getString(R.string.overlay_reset)
            setTextColor(0xFF9E96B8.toInt()) // TextSecondary
            textSize = 13f
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setOnClickListener { resetLayout() }
        }

        // Done button
        val doneBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFB47CFF.toInt()) // BrandPurple
            cornerRadius = 16 * density
        }
        val doneButton = TextView(this).apply {
            text = getString(R.string.overlay_done)
            setTextColor(0xFFEDE9FC.toInt()) // TextPrimary
            textSize = 13f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            background = doneBg
            setOnClickListener { exitEditMode() }
        }

        controlsLayout.addView(label)
        controlsLayout.addView(resetButton)
        controlsLayout.addView(doneButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }

        try {
            windowManager.addView(controlsLayout, params)
            controlsBar = controlsLayout
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show edit controls", e)
        }
    }

    private fun resetLayout() {
        val density = resources.displayMetrics.density
        val config = themeConfig ?: return

        for ((_, ww) in widgetViews) {
            ww.params.x = (ww.widget.defaultX * density).toInt()
            ww.params.y = (ww.widget.defaultY * density).toInt()
            ww.params.width = (ww.widget.defaultWidth * density).toInt()
            ww.params.height = (ww.widget.defaultHeight * density).toInt()
            ww.enabled = true
            ww.alpha = 1.0f
            ww.container.alpha = 1.0f

            try {
                windowManager.updateViewLayout(ww.container, ww.params)
            } catch (_: Exception) {}
        }

        configManager.saveOverlayLayout(config.id, OverlayLayout())

        selectedWidget = widgetViews.values.firstOrNull()
        updateControlsLabel()
        for ((_, ww) in widgetViews) {
            updateWidgetEditVisual(ww, ww == selectedWidget)
        }
    }

    /** Saves synchronously — safe for onDestroy where the coroutine scope is about to cancel. */
    private fun saveCurrentLayout() {
        val config = themeConfig ?: return
        val density = resources.displayMetrics.density
        val states = mutableMapOf<String, WidgetLayoutState>()

        for ((id, ww) in widgetViews) {
            states[id] = WidgetLayoutState(
                x = (ww.params.x / density).toInt(),
                y = (ww.params.y / density).toInt(),
                width = (ww.params.width / density).toInt(),
                height = (ww.params.height / density).toInt(),
                enabled = ww.enabled,
                alpha = ww.alpha
            )
        }

        configManager.saveOverlayLayout(config.id, OverlayLayout(widgets = states))
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

        return Notification.Builder(this, OverlayConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.overlay_edit_layout),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, OverlayService::class.java).apply { action = ACTION_EDIT_MODE },
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build())
            .addAction(Notification.Action.Builder(
                null, getString(R.string.exit),
                stopIntent
            ).build())
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRecoveryPill() {
        if (recoveryPill != null) return
        val density = resources.displayMetrics.density
        val widthPx = (40 * density).toInt()
        val heightPx = (24 * density).toInt()

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setColor(0xFF1E1A3A.toInt())
            setStroke((1 * density).toInt(), 0xFF9E96B8.toInt())
        }

        val pill = TextView(this).apply {
            text = "\u22EF" // midline horizontal ellipsis
            setTextColor(0xFFEDE9FC.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            background = pillBg
            alpha = 0.6f
            setOnClickListener { enterEditMode() }
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (8 * density).toInt()
        }

        try {
            windowManager.addView(pill, params)
            recoveryPill = pill
            Toast.makeText(this, R.string.overlay_all_hidden, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show recovery pill", e)
        }
    }

    private fun removeRecoveryPill() {
        recoveryPill?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            recoveryPill = null
        }
    }

    private fun removeAllWidgets() {
        removeRecoveryPill()

        controlsBar?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            controlsBar = null
        }
        controlsLabel = null

        scrimView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            scrimView = null
        }

        for ((_, ww) in widgetViews) {
            try {
                ww.webView.stopLoading()
                windowManager.removeView(ww.container)
                ww.webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error removing widget: ${e.message}")
            }
        }
        widgetViews.clear()
    }

    override fun onDestroy() {
        instance = null
        isEditMode = false

        saveCurrentLayout()
        removeAllWidgets()

        dataCollectionJob?.cancel()
        memoryService = null

        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Custom FrameLayout that can intercept all child touch events.
     * Needed because WebView consumes touches — without interception,
     * OnTouchListeners set on the container never fire.
     */
    private class WidgetContainer(context: android.content.Context) : FrameLayout(context) {
        var interceptAllTouches = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return interceptAllTouches || super.onInterceptTouchEvent(ev)
        }
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
