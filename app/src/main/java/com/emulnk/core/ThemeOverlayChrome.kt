package com.emulnk.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.emulnk.R
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeSettingSchema

/**
 * Floating UI chrome for interactive theme mode.
 * Manages back button, menu, settings dialog, and debug console
 * as TYPE_APPLICATION_OVERLAY windows.
 *
 * Lifecycle: [show] creates the buttons (hidden), double-tap via [onDoubleTap] reveals them,
 * auto-hide timer fades them after [OverlayConstants.THEME_CHROME_AUTO_HIDE_MS].
 * [dismiss] removes all views and releases the handler.
 * If [ThemeConfig.hideOverlay] is true, chrome is fully suppressed.
 */
class ThemeOverlayChrome(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themeConfig: ThemeConfig,
    private val currentSettings: () -> Map<String, String>,
    private val onExit: () -> Unit,
    private val onReload: () -> Unit,
    private val onSettingChanged: (key: String, value: String) -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private var handler: Handler? = Handler(Looper.getMainLooper())
    private val btnSizePx = (OverlayConstants.THEME_CHROME_BUTTON_SIZE_DP * density).toInt()
    private val marginPx = (8 * density).toInt()

    private var backButton: View? = null
    private var menuButton: ImageView? = null
    private var menuPanel: View? = null
    private var settingsScrim: View? = null
    private var settingsDialog: View? = null
    private var debugConsole: View? = null
    private var debugTextView: TextView? = null

    private var isMenuOpen = false
    private var isDebugVisible = false
    private var isChromeVisible = true
    private var lastTapTime = 0L

    private val autoHideRunnable = Runnable { hideChromeButtons() }

    fun show() {
        if (themeConfig.hideOverlay) return
        createBackButton()
        createMenuButton()
        // Start hidden, double-tap to reveal
        isChromeVisible = false
        backButton?.alpha = 0f
        menuButton?.alpha = 0f
        setButtonsTouchable(false)
    }

    fun dismiss() {
        handler?.removeCallbacks(autoHideRunnable)
        handler = null
        removeView(backButton); backButton = null
        removeView(menuButton); menuButton = null
        dismissMenu()
        dismissSettingsDialog()
        dismissDebugConsole()
    }

    fun onDoubleTap() {
        if (themeConfig.hideOverlay) return
        val now = System.currentTimeMillis()
        if (!isChromeVisible) {
            // Require double-tap to show chrome
            if (now - lastTapTime < OverlayConstants.DOUBLE_TAP_TIMEOUT_MS) {
                showChromeButtons()
                resetAutoHideTimer()
                lastTapTime = now
            } else {
                lastTapTime = now
            }
            return
        }
        // Don't reset auto-hide while menu, settings, or debug are open
        if (!isMenuOpen && settingsDialog == null && !isDebugVisible) {
            resetAutoHideTimer()
        }
    }

    fun addDebugLog(message: String) {
        handler?.post {
            debugTextView?.let { tv ->
                tv.append("$message\n")
                // TextView is inside HorizontalScrollView inside ScrollView
                (tv.parent as? HorizontalScrollView)?.let { hScroll ->
                    (hScroll.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    fun showSettingsDialog() {
        if (settingsDialog != null) return
        val settings = themeConfig.settings?.filter { it.hidden != true }
        if (settings.isNullOrEmpty()) return

        // Scrim
        val scrim = View(context).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dismissSettingsDialog() }
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
            settingsScrim = scrim
        } catch (_: Exception) { return }

        // Card
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_ELEVATED)
            cornerRadius = 16 * density
        }
        val dp = density.toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(24 * dp, 20 * dp, 24 * dp, 16 * dp)
        }

        // Title
        card.addView(TextView(context).apply {
            text = context.getString(R.string.theme_settings_title)
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 18f
        })

        val currentVals = currentSettings().toMutableMap()

        // Settings container inside a ScrollView
        val settingsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val categories = settings.groupBy { it.category ?: "General" }
        val hasMultipleCategories = categories.size > 1
        val expandedCategories = categories.keys.toMutableSet() // all expanded by default

        // Cap scroll area at 60% of screen to leave room for title, button, and padding
        val displayMetrics = getDisplayMetrics()
        val maxScrollHeight = (displayMetrics.heightPixels * 0.6f).toInt()

        val scrollView = ScrollView(context).apply {
            addView(settingsContainer)
        }

        rebuildSettingsContent(settingsContainer, settings, currentVals, expandedCategories, hasMultipleCategories, scrollView, maxScrollHeight)

        card.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Close button
        val closeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.BRAND_PURPLE)
            cornerRadius = 16 * density
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.overlay_done)
            setTextColor(UiColors.TEXT_PRIMARY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(16 * dp, 8 * dp, 16 * dp, 8 * dp)
            background = closeBg
            setOnClickListener { dismissSettingsDialog() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 * dp })

        capScrollViewHeight(scrollView, maxScrollHeight)

        val dialogParams = WindowManager.LayoutParams(
            (280 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(card, dialogParams)
            settingsDialog = card
        } catch (_: Exception) {
            dismissSettingsDialog()
        }
    }

    // --- Private helpers ---

    private fun createBackButton() {
        val btn = createChromeButton(R.drawable.ic_back, Gravity.TOP or Gravity.START) { onExit() }
        backButton = btn
    }

    private fun createMenuButton() {
        val btn = createChromeButton(R.drawable.ic_menu, Gravity.TOP or Gravity.END) { toggleMenu() }
        menuButton = btn as? ImageView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createChromeButton(iconRes: Int, gravity: Int, onClick: () -> Unit): View? {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(UiColors.SURFACE_ELEVATED)
            setStroke((1 * density).toInt(), UiColors.BRAND_PURPLE)
        }
        val btn = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(UiColors.TEXT_PRIMARY)
            })
            background = bg
            scaleType = ImageView.ScaleType.CENTER
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }

        val params = WindowManager.LayoutParams(
            btnSizePx, btnSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = marginPx
            y = marginPx
        }

        return try {
            windowManager.addView(btn, params)
            btn
        } catch (_: Exception) { null }
    }

    private fun toggleMenu() {
        if (isMenuOpen) {
            dismissMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        if (menuPanel != null) return
        isMenuOpen = true
        menuButton?.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()?.apply {
            setTint(UiColors.TEXT_PRIMARY)
        })

        val dp = density.toInt()
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xE61E1A3A.toInt()) // SurfaceElevated 90% alpha, unique alpha variant
            cornerRadius = 12 * density
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(4 * dp, 4 * dp, 4 * dp, 4 * dp)
        }

        // Build menu items list
        val menuItems = mutableListOf<View>()

        menuItems.add(createMenuItemButton(R.drawable.ic_sync, context.getString(R.string.theme_chrome_reload)) {
            onReload()
            dismissMenu()
        })

        if (themeConfig.settings?.any { it.hidden != true } == true) {
            menuItems.add(createMenuItemButton(R.drawable.ic_settings, context.getString(R.string.theme_chrome_settings)) {
                dismissMenu()
                showSettingsDialog()
            })
        }

        menuItems.add(createMenuItemButton(R.drawable.ic_terminal, context.getString(R.string.theme_chrome_debug)) {
            dismissMenu()
            toggleDebugConsole()
        })

        for ((index, item) in menuItems.withIndex()) {
            panel.addView(item)
            if (index < menuItems.lastIndex) {
                panel.addView(View(context).apply {
                    setBackgroundColor(UiColors.DIVIDER)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                    ).apply {
                        marginStart = (12 * density).toInt()
                        marginEnd = (12 * density).toInt()
                    }
                })
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginPx
            y = marginPx + btnSizePx + (4 * density).toInt()
        }

        try {
            windowManager.addView(panel, params)
            menuPanel = panel
        } catch (_: Exception) {}
    }

    private fun dismissMenu() {
        removeView(menuPanel); menuPanel = null
        isMenuOpen = false
        menuButton?.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_menu)?.mutate()?.apply {
            setTint(UiColors.TEXT_PRIMARY)
        })
        resetAutoHideTimer()
    }

    private fun createMenuItemButton(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val dp = density.toInt()
        val iconSizePx = (18 * density).toInt()
        val cornerRadius = 8 * density

        val pressedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_OVERLAY)
            this.cornerRadius = cornerRadius
        }
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_ELEVATED)
            this.cornerRadius = cornerRadius
        }
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(), normalBg)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12 * dp, 12 * dp, 16 * dp, 12 * dp)
            background = stateList
            isClickable = true
            setOnClickListener { onClick() }

            addView(ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                    setTint(UiColors.TEXT_PRIMARY)
                })
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            })

            addView(TextView(context).apply {
                text = label
                setTextColor(UiColors.TEXT_PRIMARY)
                textSize = 13f
                setPadding(8 * dp, 0, 0, 0)
            })
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun createSettingRow(
        setting: ThemeSettingSchema,
        currentValue: String,
        currentVals: MutableMap<String, String>
    ): LinearLayout {
        val dp = density.toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = setting.label ?: setting.id
                setTextColor(UiColors.TEXT_PRIMARY)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            when (setting.type) {
                "toggle" -> {
                    addView(Switch(context).apply {
                        isChecked = currentValue.toBooleanStrictOrNull() ?: (currentValue == "1")
                        trackTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(UiColors.BRAND_PURPLE, UiColors.SURFACE_OVERLAY)
                        )
                        thumbTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(UiColors.TEXT_PRIMARY, UiColors.TEXT_SECONDARY)
                        )
                        setOnCheckedChangeListener { _, checked ->
                            val value = checked.toString()
                            currentVals[setting.id] = value
                            onSettingChanged(setting.id, value)
                        }
                    })
                }
                "select" -> {
                    val options = setting.options ?: emptyList()
                    val tv = TextView(context).apply {
                        text = currentValue
                        setTextColor(UiColors.BRAND_PURPLE)
                        textSize = 14f
                        setPadding(8 * dp, 4 * dp, 8 * dp, 4 * dp)
                    }
                    tv.setOnClickListener { anchor ->
                        if (options.isEmpty()) return@setOnClickListener
                        val popup = android.widget.PopupMenu(context, anchor)
                        options.forEachIndexed { idx, opt ->
                            popup.menu.add(0, idx, idx, opt)
                        }
                        popup.setOnMenuItemClickListener { item ->
                            val value = options[item.itemId]
                            tv.text = value
                            currentVals[setting.id] = value
                            onSettingChanged(setting.id, value)
                            true
                        }
                        popup.show()
                    }
                    addView(tv)
                }
                else -> {
                    addView(TextView(context).apply {
                        text = currentValue
                        setTextColor(UiColors.TEXT_SECONDARY)
                        textSize = 13f
                    })
                }
            }
        }
    }

    private fun rebuildSettingsContent(
        container: LinearLayout,
        settings: List<ThemeSettingSchema>,
        currentVals: MutableMap<String, String>,
        expandedCategories: MutableSet<String>,
        hasMultipleCategories: Boolean,
        scrollView: ScrollView,
        maxScrollHeight: Int
    ) {
        container.removeAllViews()
        val dp = density.toInt()
        val categories = settings.groupBy { it.category ?: "General" }

        for ((category, catSettings) in categories) {
            if (hasMultipleCategories) {
                val isExpanded = category in expandedCategories
                container.addView(
                    createCategoryHeader(category, isExpanded) {
                        if (category in expandedCategories) expandedCategories.remove(category)
                        else expandedCategories.add(category)
                        rebuildSettingsContent(container, settings, currentVals, expandedCategories, hasMultipleCategories, scrollView, maxScrollHeight)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12 * dp }
                )
                if (!isExpanded) continue
            }

            for (setting in catSettings) {
                container.addView(
                    createSettingRow(setting, currentVals[setting.id] ?: setting.default, currentVals),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12 * dp }
                )
            }
        }
        capScrollViewHeight(scrollView, maxScrollHeight)
    }

    private fun createCategoryHeader(
        category: String,
        isExpanded: Boolean,
        onToggle: () -> Unit
    ): LinearLayout {
        val dp = density.toInt()
        val cornerRadius = 6 * density
        val pressedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiColors.SURFACE_OVERLAY)
            this.cornerRadius = cornerRadius
        }
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.TRANSPARENT)
            this.cornerRadius = cornerRadius
        }
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(), normalBg)
        }
        val triangle = if (isExpanded) "\u25BC " else "\u25B6 " // ▼ or ▶
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4 * dp, 4 * dp, 4 * dp, 4 * dp)
            background = stateList
            isClickable = true
            setOnClickListener { onToggle() }

            addView(TextView(context).apply {
                text = "$triangle${category.uppercase()}"
                setTextColor(UiColors.TEXT_SECONDARY)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun dismissSettingsDialog() {
        removeView(settingsDialog); settingsDialog = null
        removeView(settingsScrim); settingsScrim = null
        resetAutoHideTimer()
    }

    private fun toggleDebugConsole() {
        if (isDebugVisible) {
            dismissDebugConsole()
        } else {
            showDebugConsole()
        }
    }

    private fun showDebugConsole() {
        if (debugConsole != null) return
        isDebugVisible = true

        val dp = density.toInt()
        // Use windowManager.defaultDisplay for correct screen width (may be secondary display)
        val displayMetrics = getDisplayMetrics()
        val screenWidth = displayMetrics.widthPixels
        val consoleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xE60E0C1C.toInt()) // SurfaceBase 90% alpha, unique alpha variant
            setStroke((1 * density).toInt(), UiColors.BRAND_PURPLE)
            cornerRadius = 8 * density
        }

        val tv = TextView(context).apply {
            setTextColor(UiColors.BRAND_CYAN)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(8 * dp, 8 * dp, 8 * dp, 8 * dp)
            setHorizontallyScrolling(true)
        }
        debugTextView = tv

        val hScroll = HorizontalScrollView(context).apply {
            addView(tv)
        }

        val scrollView = ScrollView(context).apply {
            background = consoleBg
            addView(hScroll)
        }

        val params = WindowManager.LayoutParams(
            (screenWidth * 0.8f).toInt(),
            (150 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = marginPx + btnSizePx + (4 * density).toInt()
        }

        try {
            windowManager.addView(scrollView, params)
            debugConsole = scrollView
        } catch (_: Exception) {}
    }

    private fun dismissDebugConsole() {
        removeView(debugConsole); debugConsole = null
        debugTextView = null
        isDebugVisible = false
        resetAutoHideTimer()
    }

    private fun resetAutoHideTimer() {
        handler?.removeCallbacks(autoHideRunnable)
        handler?.postDelayed(autoHideRunnable, OverlayConstants.THEME_CHROME_AUTO_HIDE_MS)
    }

    private fun hideChromeButtons() {
        if (!isChromeVisible) return
        if (isMenuOpen || settingsDialog != null || isDebugVisible) return
        isChromeVisible = false
        backButton?.animate()?.alpha(0f)?.setDuration(200)?.start()
        menuButton?.animate()?.alpha(0f)?.setDuration(200)?.start()
        setButtonsTouchable(false)
    }

    private fun showChromeButtons() {
        isChromeVisible = true
        setButtonsTouchable(true)
        backButton?.animate()?.alpha(1f)?.setDuration(200)?.start()
        menuButton?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun setButtonsTouchable(touchable: Boolean) {
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        listOf(backButton, menuButton).forEach { btn ->
            btn?.let {
                (it.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                    params.flags = flags
                    try { windowManager.updateViewLayout(it, params) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun capScrollViewHeight(scrollView: ScrollView, maxHeight: Int) {
        scrollView.post {
            val lp = scrollView.layoutParams
            if (scrollView.height > maxHeight) {
                lp.height = maxHeight
            } else {
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            }
            scrollView.requestLayout()
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(): android.util.DisplayMetrics =
        android.util.DisplayMetrics().also {
            windowManager.defaultDisplay.getRealMetrics(it)
        }

    private fun removeView(view: View?) {
        windowManager.safeRemoveView(view)
    }
}
