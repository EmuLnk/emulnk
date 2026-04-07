package com.emulnk.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.widget.FrameLayout
import com.emulnk.BuildConfig
import com.emulnk.bridge.OverlayBridge
import com.emulnk.core.model.GameData
import com.emulnk.model.ScreenTarget
import com.emulnk.model.WidgetConfig
import com.emulnk.model.WidgetLayoutState
import com.google.gson.Gson
import java.io.File

/** Secondary display overlay: WindowManager above ExternalPresentation, pass-through in normal mode. */
class OverlayPresentation(
    serviceContext: Context,
    private val display: Display,
    private val themeId: String,
    private val themesRootDir: File,
    private val devMode: Boolean = false,
    private val devUrl: String = "",
    private val widgetsBasePath: String? = null,
    private val devThemePath: String = themeId
) {

    companion object {
        private const val TAG = "OverlayPresentation"
        private val ALPHA_TAG_KEY = "alpha_tag".hashCode()
    }

    private val overlayContext: Context = serviceContext.createWindowContext(
        display,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        null
    )
    private val windowManager: WindowManager =
        overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val gson = Gson()
    private lateinit var rootLayout: FrameLayout
    private val widgetWebViews = mutableMapOf<String, WebView>()
    private val widgetContainers = mutableMapOf<String, WidgetContainer>()
    private val widgetConfigs = mutableMapOf<String, WidgetConfig>()

    private fun isBase(id: String) = widgetConfigs[id]?.isBaseLayer == true

    private var latestGameData: GameData? = null
    private var editMode = false
    private var selectedWidgetId: String? = null
    private var scrimView: View? = null
    private var onWidgetSelected: ((String) -> Unit)? = null
    private var builderSession: BuilderSession? = null

    fun setSession(session: BuilderSession?) {
        builderSession = session
    }

    fun show() {
        rootLayout = FrameLayout(overlayContext).apply {
            setBackgroundColor(0x00000000)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 1.0f
        }
        windowManager.addView(rootLayout, params)
    }

    fun hide() {
        if (::rootLayout.isInitialized) rootLayout.visibility = View.GONE
    }

    fun unhide() {
        if (::rootLayout.isInitialized) rootLayout.visibility = View.VISIBLE
    }

    fun dismiss() {
        if (::rootLayout.isInitialized) {
            try {
                windowManager.removeView(rootLayout)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "removeView failed during dismiss", e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun addWidget(config: WidgetConfig, layoutState: WidgetLayoutState?) {
        val ctx = overlayContext
        val density = ctx.resources.displayMetrics.density
        val isBase = config.isBaseLayer

        val enabled = if (isBase) true else (layoutState?.enabled ?: true)
        val alpha = if (isBase) 1.0f else (layoutState?.alpha ?: 1.0f)
        val bgColor = if (config.transparent) 0x00000000 else 0xFF000000.toInt()

        val interceptAssetsPath = config.assetsPath
        val webView = WebView(ctx).apply {
            settings.configureForOverlay()
            setBackgroundColor(bgColor)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (BuildConfig.DEBUG) {
                        consoleMessage?.let {
                            Log.d(TAG, "[Secondary:${config.id}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    latestGameData?.let { pushDataToSingleWidget(view, it) }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val baseDir = if (interceptAssetsPath != null) File(interceptAssetsPath)
                        else if (widgetsBasePath != null) File(widgetsBasePath)
                        else File(themesRootDir, themeId)
                    WebInterceptor.intercept(url, baseDir, devMode, devUrl, devThemePath)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl("https://app.emulink/${config.src}")
        }

        val container = WidgetContainer(ctx).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val params = if (isBase) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            val x = layoutState?.x ?: config.defaultX
            val y = layoutState?.y ?: config.defaultY
            val width = layoutState?.width ?: config.defaultWidth
            val height = layoutState?.height ?: config.defaultHeight
            FrameLayout.LayoutParams(
                (width * density).toInt(),
                (height * density).toInt()
            ).apply {
                leftMargin = (x * density).toInt()
                topMargin = (y * density).toInt()
            }
        }

        container.tag = enabled
        container.setTag(ALPHA_TAG_KEY, alpha)

        if (!enabled) {
            container.visibility = View.GONE
        } else {
            container.alpha = alpha
        }

        if (isBase) {
            rootLayout.addView(container, 0, params)
        } else {
            rootLayout.addView(container, params)
        }
        widgetWebViews[config.id] = webView
        widgetContainers[config.id] = container
        widgetConfigs[config.id] = config
    }

    fun addBridge(bridge: OverlayBridge) {
        for ((_, webView) in widgetWebViews) {
            webView.addJavascriptInterface(bridge, "emulink")
        }
    }

    fun removeWidget(id: String) {
        val webView = widgetWebViews.remove(id)
        val container = widgetContainers.remove(id)
        webView?.stopLoading()
        container?.let { rootLayout.removeView(it) }
        (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
        webView?.destroy()
        widgetConfigs.remove(id)
    }

    fun reloadAll() {
        for ((_, webView) in widgetWebViews) {
            webView.reload()
        }
    }

    fun pushDataToWidgets(js: String, gameData: GameData) {
        latestGameData = gameData
        for ((id, webView) in widgetWebViews) {
            val container = widgetContainers[id]
            if (container != null && container.visibility != View.GONE) {
                try {
                    webView.evaluateJavascript(js, null)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed to push data to secondary widget: ${e.message}")
                    }
                }
            }
        }
    }

    private fun pushDataToSingleWidget(view: WebView?, gameData: GameData) {
        view ?: return
        val js = buildDataInjectionJs(gameData, gson)
        try { view.evaluateJavascript(js, null) } catch (e: Exception) {
            Log.w(TAG, "Failed to push data to secondary widget: ${e.message}")
        }
    }

    // --- Edit mode ---

    fun enterEditMode(onSelected: (String) -> Unit) {
        if (editMode) return
        removeRecoveryPill()
        editMode = true
        onWidgetSelected = onSelected

        // Remove FLAG_NOT_TOUCHABLE so we can receive touches; add FLAG_NOT_TOUCH_MODAL
        val lp = rootLayout.layoutParams as WindowManager.LayoutParams
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.updateViewLayout(rootLayout, lp)

        // Add semi-transparent scrim behind widgets but above base layer
        val scrim = View(overlayContext).apply {
            setBackgroundColor(OverlayConstants.EDIT_SCRIM_COLOR.toInt())
        }
        val scrimIndex = widgetContainers.count { isBase(it.key) }.coerceAtMost(rootLayout.childCount)
        rootLayout.addView(scrim, scrimIndex, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        scrimView = scrim

        for ((id, container) in widgetContainers) {
            if (isBase(id)) continue

            container.interceptAllTouches = true

            // Dim disabled widgets in edit mode
            if (container.visibility == View.GONE) {
                container.visibility = View.VISIBLE
                val savedAlpha = container.getTag(ALPHA_TAG_KEY) as? Float ?: 1.0f
                container.alpha = savedAlpha * 0.5f
            }

            updateContainerEditVisual(id, id == selectedWidgetId)
            setupDragHandling(id, container)
        }
    }

    fun exitEditMode() {
        if (!editMode) return
        editMode = false
        onWidgetSelected = null
        selectedWidgetId = null

        // Restore FLAG_NOT_TOUCHABLE, remove FLAG_NOT_TOUCH_MODAL
        val lp = rootLayout.layoutParams as WindowManager.LayoutParams
        lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        windowManager.updateViewLayout(rootLayout, lp)

        // Remove scrim
        scrimView?.let { rootLayout.removeView(it) }
        scrimView = null

        // Disable touch interception and remove borders
        for ((id, container) in widgetContainers) {
            if (isBase(id)) continue

            container.interceptAllTouches = false
            container.background = null
            container.setOnTouchListener(null)

            // Restore visibility based on enabled state
            val isEnabled = container.tag as? Boolean ?: true
            if (!isEnabled) {
                container.visibility = View.GONE
            } else {
                val savedAlpha = container.getTag(ALPHA_TAG_KEY) as? Float ?: 1.0f
                container.alpha = savedAlpha
            }
        }
    }

    fun selectWidget(id: String) {
        val prevId = selectedWidgetId
        selectedWidgetId = if (id.isEmpty()) null else id
        if (prevId != null) updateContainerEditVisual(prevId, false)
        if (id.isNotEmpty()) updateContainerEditVisual(id, true)
    }

    fun toggleWidget(id: String) {
        if (isBase(id)) return
        val container = widgetContainers[id] ?: return
        val isEnabled = container.tag as? Boolean ?: true
        val newEnabled = !isEnabled
        container.tag = newEnabled

        if (newEnabled) {
            val savedAlpha = container.getTag(ALPHA_TAG_KEY) as? Float ?: 1.0f
            container.alpha = savedAlpha
        } else {
            container.alpha = 0.3f
        }
        updateContainerEditVisual(id, id == selectedWidgetId)
    }

    fun setWidgetEnabled(id: String, enabled: Boolean) {
        if (isBase(id)) return
        val container = widgetContainers[id] ?: return
        val isEnabled = container.tag as? Boolean ?: true
        if (isEnabled == enabled) return
        toggleWidget(id)
    }

    fun getWidgetAlpha(id: String): Float {
        val container = widgetContainers[id] ?: return 1.0f
        return container.getTag(ALPHA_TAG_KEY) as? Float ?: 1.0f
    }

    fun setWidgetAlpha(id: String, alpha: Float) {
        if (isBase(id)) return
        val container = widgetContainers[id] ?: return
        container.setTag(ALPHA_TAG_KEY, alpha)
        val isEnabled = container.tag as? Boolean ?: true
        if (isEnabled) container.alpha = alpha
    }

    fun setWidgetPosition(id: String, xPx: Int, yPx: Int) {
        if (isBase(id)) return
        val container = widgetContainers[id] ?: return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = xPx
        lp.topMargin = yPx
        container.layoutParams = lp
    }

    fun setWidgetSize(id: String, wPx: Int, hPx: Int) {
        if (isBase(id)) return
        val container = widgetContainers[id] ?: return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = wPx
        lp.height = hPx
        container.layoutParams = lp
    }

    fun getWidgetStates(density: Float): Map<String, WidgetLayoutState> {
        val states = mutableMapOf<String, WidgetLayoutState>()
        for ((id, container) in widgetContainers) {
            if (isBase(id)) continue
            val lp = container.layoutParams as? FrameLayout.LayoutParams ?: continue
            val isEnabled = container.tag as? Boolean ?: true
            val alpha = container.getTag(ALPHA_TAG_KEY) as? Float ?: 1.0f
            states[id] = WidgetLayoutState(
                x = (lp.leftMargin / density).toInt(),
                y = (lp.topMargin / density).toInt(),
                width = (lp.width / density).toInt(),
                height = (lp.height / density).toInt(),
                enabled = isEnabled,
                alpha = alpha
            )
        }
        return states
    }

    fun resetWidgets() {
        val density = overlayContext.resources.displayMetrics.density
        for ((id, container) in widgetContainers) {
            val config = widgetConfigs[id] ?: continue
            if (config.isBaseLayer) continue
            val lp = container.layoutParams as? FrameLayout.LayoutParams ?: continue
            lp.leftMargin = (config.defaultX * density).toInt()
            lp.topMargin = (config.defaultY * density).toInt()
            lp.width = (config.defaultWidth * density).toInt()
            lp.height = (config.defaultHeight * density).toInt()
            container.layoutParams = lp

            container.tag = true
            container.setTag(ALPHA_TAG_KEY, 1.0f)
            container.alpha = 1.0f
            container.visibility = View.VISIBLE
        }

        selectedWidgetId = widgetContainers.keys.firstOrNull { !isBase(it) }
        for ((id, _) in widgetContainers) {
            if (isBase(id)) continue
            updateContainerEditVisual(id, id == selectedWidgetId)
        }
    }

    // --- Internals ---

    private fun updateContainerEditVisual(id: String, isSelected: Boolean) {
        val container = widgetContainers[id] ?: return
        val isEnabled = container.tag as? Boolean ?: true
        container.background = OverlayConstants.createEditBorder(
            overlayContext.resources.displayMetrics.density, isEnabled, isSelected
        )
    }

    private fun setupDragHandling(id: String, container: WidgetContainer) {
        val session = builderSession ?: return
        val config = widgetConfigs[id] ?: return
        val density = overlayContext.resources.displayMetrics.density

        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val handler = OverlayGestureHandler(
            context = overlayContext,
            session = session,
            widgetId = id,
            screen = ScreenTarget.SECONDARY,
            config = config,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            getPosition = {
                val lp = container.layoutParams as FrameLayout.LayoutParams
                Pair(lp.leftMargin, lp.topMargin)
            },
            getSize = {
                val lp = container.layoutParams as FrameLayout.LayoutParams
                Pair(lp.width, lp.height)
            },
            onPositionChanged = { x, y ->
                val lp = container.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = x
                lp.topMargin = y
                container.layoutParams = lp
            },
            onSizeChanged = { w, h ->
                val lp = container.layoutParams as FrameLayout.LayoutParams
                lp.width = w
                lp.height = h
                lp.leftMargin = lp.leftMargin.coerceIn(0, maxOf(0, screenWidth - w))
                lp.topMargin = lp.topMargin.coerceIn(0, maxOf(0, screenHeight - h))
                container.layoutParams = lp
            },
            onSelected = {
                val prevId = selectedWidgetId
                selectedWidgetId = id
                if (prevId != null && prevId != id) {
                    updateContainerEditVisual(prevId, false)
                }
                updateContainerEditVisual(id, true)
                onWidgetSelected?.invoke(id)
            },
            onToggled = {
                val isEnabled = container.tag as? Boolean ?: true
                toggleWidget(id)
                session.executeAction(EditAction.ToggleEnabled(
                    id, ScreenTarget.SECONDARY, isEnabled, !isEnabled
                ))
            }
        )
        handler.attachTo(container)
    }

    // --- Recovery pill (separate window, touchable on secondary display) ---

    private var recoveryPill: View? = null

    fun showRecoveryPill(onEditMode: () -> Unit) {
        if (recoveryPill != null) return
        val density = overlayContext.resources.displayMetrics.density
        val (pill, params) = createRecoveryPill(overlayContext, density, onEditMode)
        try {
            windowManager.addView(pill, params)
            recoveryPill = pill
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show secondary recovery pill", e)
        }
    }

    fun removeRecoveryPill() {
        windowManager.safeRemoveView(recoveryPill)
        recoveryPill = null
    }

    private var destroyed = false

    fun destroyAll() {
        if (destroyed) return
        destroyed = true
        removeRecoveryPill()
        editMode = false
        onWidgetSelected = null
        selectedWidgetId = null
        scrimView = null
        builderSession = null
        // Stop, detach, and destroy WebViews. removeAllViews before destroy minimizes display mismatch warnings
        for ((id, webView) in widgetWebViews) {
            webView.stopLoading()
            widgetContainers[id]?.removeAllViews()
            webView.destroy()
        }
        rootLayout.removeAllViews()
        widgetWebViews.clear()
        widgetContainers.clear()
        widgetConfigs.clear()
    }

}
