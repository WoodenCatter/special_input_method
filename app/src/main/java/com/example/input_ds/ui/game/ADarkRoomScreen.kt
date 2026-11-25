package com.example.input_ds.ui.game

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.input_ds.adarkroom.ADarkRoomInputBus
import com.example.input_ds.adarkroom.ADarkRoomSignalSink
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.theme.LocalBciColors
import java.io.ByteArrayInputStream

private const val ADR_ASSET_HOST = "appassets.androidplatform.net"
private const val ADR_ASSET_PREFIX = "adarkroom/"
private const val ADR_URL = "https://$ADR_ASSET_HOST/assets/adarkroom/index.html?lang=zh_cn&ignorebrowser=true"

/** Offline WebView host for《小黑屋 / A Dark Room》(MPL-2.0) with BCI 3-key + touch support. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ADarkRoomScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    paused: Boolean,
    onFeedback: (String) -> Unit,
    onBoundary: () -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalBciColors.current
    val isDark = colors.bg.luminance() < 0.5f
    val bgArgb = colors.bg.toArgb()

    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var activeSignalSink by remember { mutableStateOf<ADarkRoomWebSignalSink?>(null) }
    var lifecycleResumed by remember { mutableStateOf(true) }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnFeedback by rememberUpdatedState(onFeedback)
    val currentOnBoundary by rememberUpdatedState(onBoundary)

    var actionHints by remember {
        mutableStateOf(ActionHints("", "正在进入小黑屋…", ""))
    }
    ReportActionHints(actionHints)

    val effectivePaused = paused || !lifecycleResumed

    LifecycleResumeEffect(Unit) {
        lifecycleResumed = true
        onPauseOrDispose {
            lifecycleResumed = false
            activeSignalSink?.saveAndPause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSignalSink?.saveAndPause()
            activeSignalSink?.let(ADarkRoomInputBus::detach)
            activeSignalSink = null
            activeWebView?.apply {
                removeJavascriptInterface("InputDsAdrHost")
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    activeWebView = this
                    val signalSink = ADarkRoomWebSignalSink(this)
                    activeSignalSink = signalSink
                    ADarkRoomInputBus.attach(signalSink)
                    addJavascriptInterface(
                        ADarkRoomHostBridge(
                            webView = this,
                            onScanState = { left, bite, right, dir ->
                                actionHints = ActionHints(left, bite, right, dir)
                            },
                            onBoundary = { currentOnBoundary() },
                            onFeedback = { currentOnFeedback(it) },
                            onRequestExit = { currentOnBack() }
                        ),
                        "InputDsAdrHost"
                    )
                    setBackgroundColor(bgArgb)
                    settings.apply {
                        javaScriptEnabled = true
                        // Required for A Dark Room's offline localStorage save game on the local asset origin.
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = OfflineADarkRoomWebViewClient(context.assets) {
                        signalSink.onPageReady()
                    }
                    loadUrl(ADR_URL)
                }
            },
            update = { webView ->
                webView.setBackgroundColor(bgArgb)
                activeSignalSink?.configure(
                    intervalMs = scanIntervalMs,
                    manual = scanMode == ScanMode.MANUAL,
                    paused = effectivePaused,
                    graceMs = AppUiSettings.graceMs(webView.context),
                    isDark = isDark
                )
            }
        )
    }
}

private class OfflineADarkRoomWebViewClient(
    private val assets: android.content.res.AssetManager,
    private val onPageReady: () -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isAdrAssetUrl()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse {
        val url = request.url
        if (!url.isAdrAssetUrl()) return adrBlockedResponse()

        val assetPath = url.path.orEmpty().removePrefix("/assets/")
        if (!assetPath.startsWith(ADR_ASSET_PREFIX) || assetPath.split('/').any { it == ".." }) {
            return adrBlockedResponse()
        }

        return try {
            val isBinaryAudio = assetPath.endsWith(".flac") || assetPath.endsWith(".mp3") || assetPath.endsWith(".ogg")
            WebResourceResponse(
                adrMimeTypeFor(assetPath),
                if (isBinaryAudio) null else "UTF-8",
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store",
                    "Content-Security-Policy" to
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data:; media-src 'self'; connect-src 'self'"
                ),
                assets.open(assetPath)
            )
        } catch (_: Exception) {
            adrNotFoundResponse()
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (Uri.parse(url).isAdrAssetUrl()) onPageReady()
    }
}

private class ADarkRoomWebSignalSink(
    private val webView: WebView
) : ADarkRoomSignalSink {
    @Volatile
    private var pageReady = false
    private var scanIntervalMs = 1_500L
    private var manual = false
    private var paused = false
    private var graceMs = 400L
    private var isDark = false
    private var configuredOnce = false

    fun onPageReady() {
        pageReady = true
        pushConfig()
    }

    fun configure(intervalMs: Long, manual: Boolean, paused: Boolean, graceMs: Long, isDark: Boolean) {
        val clamped = intervalMs.coerceIn(1_100L, 3_000L)
        if (configuredOnce &&
            clamped == scanIntervalMs &&
            this.manual == manual &&
            this.paused == paused &&
            this.graceMs == graceMs &&
            this.isDark == isDark
        ) {
            return
        }
        configuredOnce = true
        scanIntervalMs = clamped
        this.manual = manual
        this.paused = paused
        this.graceMs = graceMs
        this.isDark = isDark
        if (pageReady) pushConfig()
    }

    fun saveAndPause() {
        if (!pageReady) return
        webView.post {
            webView.evaluateJavascript("window.inputDsAdr?.saveAndPause()", null)
        }
    }

    override fun onSignal(signal: ControlSignal) {
        val action = when (signal) {
            ControlSignal.LEFT_LOOK -> "look_left"
            ControlSignal.RIGHT_LOOK -> "look_right"
            ControlSignal.BITE -> "bite"
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> return
        }
        if (!pageReady) return
        webView.post {
            webView.evaluateJavascript("window.inputDsAdr?.handleAction('$action')", null)
        }
    }

    override fun onDebugTick() {
        if (!pageReady) return
        webView.post {
            webView.evaluateJavascript("window.inputDsAdr?.debugTick()", null)
        }
    }

    private fun pushConfig() {
        webView.post {
            webView.evaluateJavascript(
                "window.inputDsAdr?.configure($scanIntervalMs, $manual, $paused, $graceMs, $isDark)",
                null
            )
        }
    }
}

private class ADarkRoomHostBridge(
    private val webView: WebView,
    private val onScanState: (String, String, String, Int) -> Unit,
    private val onBoundary: () -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onRequestExit: () -> Unit
) {
    @JavascriptInterface
    fun scanState(left: String, bite: String, right: String, direction: Int) {
        webView.post { onScanState(left, bite, right, direction) }
    }

    @JavascriptInterface
    fun onContextBoundary() {
        webView.post { onBoundary() }
    }

    @JavascriptInterface
    fun notifyFeedback(message: String) {
        webView.post { onFeedback(message) }
    }

    @JavascriptInterface
    fun requestExit() {
        webView.post { onRequestExit() }
    }
}

private fun Uri.isAdrAssetUrl(): Boolean =
    scheme == "https" && host == ADR_ASSET_HOST && path.orEmpty().startsWith("/assets/$ADR_ASSET_PREFIX")

private fun adrMimeTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".svg") -> "image/svg+xml"
    path.endsWith(".png") -> "image/png"
    path.endsWith(".flac") -> "audio/flac"
    path.endsWith(".mp3") -> "audio/mpeg"
    path.endsWith(".ogg") -> "audio/ogg"
    else -> "application/octet-stream"
}

private fun adrBlockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

private fun adrNotFoundResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    404,
    "Not Found",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)
