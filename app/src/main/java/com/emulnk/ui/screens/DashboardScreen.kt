package com.emulnk.ui.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.emulnk.BuildConfig
import com.emulnk.R
import com.emulnk.bridge.EmuLinkBridge
import com.emulnk.core.UiConstants
import com.emulnk.model.GameData
import com.emulnk.model.ThemeConfig
import com.emulnk.ui.components.DebugOverlay
import com.emulnk.ui.components.ThemeSettingsDialog
import com.emulnk.ui.theme.BrandPurple
import com.emulnk.ui.viewmodel.MainViewModel
import com.google.gson.Gson
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.io.FileInputStream

private const val DEV_MODE_ERROR_HTML = """<html><body style="background:#121212;color:white;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
                        <div style="text-align:center;padding:32px;">
                            <h2 style="color:#6A5ACD;">Dev Server Not Configured</h2>
                            <p style="color:#888;">Set your Dev Server URL in the launcher screen.<br>Example: <code>http://192.168.1.100:5500</code></p>
                            <p style="color:#666;font-size:12px;">Start a server on your PC:<br><code>python -m http.server 5500</code></p>
                        </div></body></html>"""

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    gson: Gson,
    theme: ThemeConfig,
    uiState: GameData,
    debugLogs: List<String>,
    onExitTheme: () -> Unit
) {
    var showDebugConsole by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastTouchTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        vm.showSettingsEvent.collect {
            showSettingsDialog = true
        }
    }

    // WebView lifecycle management and cleanup
    // Key only on lifecycleOwner — NOT webViewInstance, since changing that key
    // would dispose (destroy) the WebView the moment it's assigned.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewInstance?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewInstance?.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val wv = webViewInstance
            webViewInstance = null
            wv?.let {
                it.stopLoading()
                it.removeJavascriptInterface("emulink")
                it.destroy()
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.gameClosingEvent.collect {
            webViewInstance?.evaluateJavascript(
                "if(typeof onGameClosed !== 'undefined') onGameClosed()", null
            )
        }
    }

    // Auto-hide overlay using snapshotFlow to avoid race conditions
    LaunchedEffect(isMenuExpanded) {
        if (isMenuExpanded) {
            overlayVisible = true
        } else {
            snapshotFlow { lastTouchTime }.collectLatest {
                overlayVisible = true
                kotlinx.coroutines.delay(UiConstants.AUTO_HIDE_OVERLAY_DELAY_MS)
                overlayVisible = false
            }
        }
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(400)
    )

    var lastPointerEventTime by remember { mutableLongStateOf(0L) }

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    val now = System.currentTimeMillis()
                    if (now - lastPointerEventTime >= 100) { // max 10 updates/sec
                        lastTouchTime = now
                        lastPointerEventTime = now
                    }
                }
            }
        }
    ) {
        DashboardWebView(
            vm = vm,
            gson = gson,
            themeId = theme.id,
            uiState = uiState,
            modifier = Modifier.fillMaxSize(),
            onWebViewCreated = { webViewInstance = it }
        )

        if (theme.hideOverlay != true && overlayAlpha > 0f) {
            IconButton(
                onClick = onExitTheme,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .alpha(overlayAlpha)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.exit), tint = Color.White)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .alpha(overlayAlpha),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { isMenuExpanded = !isMenuExpanded },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(if (isMenuExpanded) R.drawable.ic_close else R.drawable.ic_menu),
                        contentDescription = null,
                        tint = Color.White
                    )
                }

                AnimatedVisibility(
                    visible = isMenuExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(onClick = { webViewInstance?.reload() }, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                            Icon(painter = painterResource(R.drawable.ic_sync), contentDescription = stringResource(R.string.reload), tint = Color.White)
                        }
                        IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                            Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings), tint = Color.White)
                        }
                        IconButton(onClick = { showDebugConsole = !showDebugConsole }, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                            Icon(painter = painterResource(R.drawable.ic_terminal), contentDescription = stringResource(R.string.debug), tint = if (showDebugConsole) BrandPurple else Color.White)
                        }
                    }
                }
            }
        }

        if (showDebugConsole) {
            DebugOverlay(logs = debugLogs, modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp))
        }

        if (showSettingsDialog) {
            ThemeSettingsDialog(
                theme = theme,
                currentSettings = uiState.settings,
                onDismiss = { showSettingsDialog = false },
                onUpdate = { key, value -> vm.updateThemeSetting(theme.id, key, value) }
            )
        }

        if (!uiState.isConnected) {
            Text(stringResource(R.string.waiting_for_data), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardWebView(
    vm: MainViewModel,
    gson: Gson,
    themeId: String,
    uiState: GameData,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit
) {
    val appConfig by vm.appConfig.collectAsState()
    val rootPath by vm.rootPath.collectAsState()
    var internalWebView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(themeId) {
        internalWebView?.let { webView ->
            if (appConfig.devMode) {
                val baseUrl = appConfig.devUrl.removeSuffix("/")
                if (baseUrl.isBlank()) {
                    webView.loadDataWithBaseURL(null, DEV_MODE_ERROR_HTML, "text/html", "UTF-8", null)
                } else {
                    webView.loadUrl("$baseUrl/themes/$themeId/index.html")
                }
            } else {
                webView.loadUrl("https://app.emulink/index.html")
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = if (BuildConfig.DEBUG) {
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                // Enable Remote Debugging (debug builds only)
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            val msg = "[JS] ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                            vm.addDebugLog(msg)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("EmuLinkJS", msg)
                            }
                        }
                        return true
                    }
                }

                addJavascriptInterface(EmuLinkBridge(context, vm, vm.viewModelScope, themeId, File(rootPath, "themes"), appConfig.devMode, appConfig.devUrl), "emulink")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("EmuLink", "Page finished loading: $url")
                        }
                        val initialJson = gson.toJson(uiState)
                        val encodedData = android.util.Base64.encodeToString(initialJson.toByteArray(), android.util.Base64.NO_WRAP)
                        val js = "if(typeof updateData !== 'undefined') { updateData('$encodedData', true); 'OK'; } else { 'MISSING'; }"
                        view?.evaluateJavascript(js) { result ->
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("EmuLink", "Initial data injection result: $result")
                            }
                            if (result == "\"MISSING\"") {
                                view?.postDelayed({
                                    view.evaluateJavascript(js) { retryResult ->
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d("EmuLink", "Retry data injection result: $retryResult")
                                        }
                                        if (retryResult == "\"MISSING\"") {
                                            vm.addDebugLog("Warning: updateData function missing in theme after retry")
                                        }
                                    }
                                }, UiConstants.THEME_INJECT_RETRY_DELAY_MS)
                            }
                        }

                        // Hot reload in dev mode
                        if (appConfig.devMode) {
                            val hotReloadJs = """
                                (function() {
                                    if (window.__emulink_hr) return;
                                    window.__emulink_hr = true;
                                    var last = null;
                                    setInterval(function() {
                                        fetch(window.location.href, { method: 'HEAD', cache: 'no-store' })
                                            .then(function(r) {
                                                var sig = r.headers.get('Last-Modified') || r.headers.get('Content-Length') || '';
                                                if (last !== null && sig !== last) window.location.reload();
                                                last = sig;
                                            }).catch(function() {});
                                    }, 1000);
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(hotReloadJs, null)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val msg = if (appConfig.devMode) {
                                "Dev Server Error: ${error?.description} at ${request.url}\n" +
                                "Troubleshooting:\n" +
                                "1. Is the server running? (python -m http.server 5500)\n" +
                                "2. Are both devices on the same Wi-Fi network?\n" +
                                "3. Is the PC firewall allowing port access?\n" +
                                "4. Use your PC's LAN IP (e.g. 192.168.x.x), not localhost"
                            } else {
                                "Load Error: ${error?.description} (${error?.errorCode}) at ${request.url}"
                            }
                            vm.addDebugLog(msg)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.e("EmuLink", msg)
                            }
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (appConfig.devMode) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.v("EmuLink", "DevMode: Skipping interception for ${request?.url}")
                            }
                            return null
                        }

                        val url = request?.url?.toString() ?: return null
                        if (url.startsWith("https://app.emulink/")) {
                            val fileName = url.replace("https://app.emulink/", "")
                            val currentThemesRoot = File(vm.rootPath.value, "themes")
                            val themeDir = File(currentThemesRoot, themeId)
                            val requestedFile = if (fileName.isEmpty() || fileName == "/") "index.html" else fileName
                            val file = File(themeDir, requestedFile).canonicalFile

                            if (!file.canonicalPath.startsWith(themeDir.canonicalPath + File.separator) &&
                                file.canonicalPath != themeDir.canonicalPath) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.w("EmuLink", "Path traversal attempt blocked: $fileName")
                                }
                                return null
                            }

                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("EmuLink", "Intercepting: $url -> ${file.absolutePath}")
                            }

                            if (file.exists()) {
                                val mimeType = when (file.extension.lowercase()) {
                                    "html" -> "text/html"; "css" -> "text/css"; "js" -> "application/javascript"
                                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
                                    "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; else -> "application/octet-stream"
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
                                    android.util.Log.e("EmuLink", "Interception failed for $url", e)
                                    null
                                }
                            } else {
                                android.util.Log.e("EmuLink", "File not found for interception: ${file.absolutePath}")
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                setBackgroundColor(0x00000000)

                if (appConfig.devMode) {
                    val baseUrl = appConfig.devUrl.removeSuffix("/")
                    if (baseUrl.isBlank()) {
                        loadDataWithBaseURL(null, DEV_MODE_ERROR_HTML, "text/html", "UTF-8", null)
                        vm.addDebugLog("Dev Mode: No server URL configured. Set it in the launcher.")
                    } else {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i("EmuLink", "Loading Dev URL: $baseUrl/themes/$themeId/index.html")
                        }
                        loadUrl("$baseUrl/themes/$themeId/index.html")
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.i("EmuLink", "Loading Local URL: https://app.emulink/index.html")
                    }
                    loadUrl("https://app.emulink/index.html")
                }
                internalWebView = this
                onWebViewCreated(this)
            }
        }
    )
    LaunchedEffect(uiState, internalWebView) {
        internalWebView?.let { webView ->
            try {
                if (webView.url != null) {
                    val jsonData = gson.toJson(uiState)
                    val encodedData = android.util.Base64.encodeToString(jsonData.toByteArray(), android.util.Base64.NO_WRAP)
                    webView.evaluateJavascript("if(typeof updateData !== 'undefined') updateData('$encodedData', true)", null)
                }
            } catch (_: Exception) {
                // WebView may have been destroyed between null-check and use
            }
        }
    }
}
