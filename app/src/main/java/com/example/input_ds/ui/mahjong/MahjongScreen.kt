package com.example.input_ds.ui.mahjong

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.mahjong.MahjongInputBus
import com.example.input_ds.mahjong.MahjongSignalSink
import com.example.input_ds.model.ControlSignal
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val MAHJONG_ASSET_PREFIX = "mahjong/"
private const val MAHJONG_URL = "https://$ASSET_HOST/assets/mahjong/index.html"
private const val MAHJONG_LOG_TAG = "InputDsMahjong"

/** Fully offline four-player Mahjong table with touch and EEG/floating-ball controls. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MahjongScreen(scanIntervalMs: Long, onBack: () -> Unit) {
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var activeSignalSink by remember { mutableStateOf<MahjongWebSignalSink?>(null) }
    val currentOnBack by rememberUpdatedState(onBack)

    DisposableEffect(Unit) {
        onDispose {
            activeSignalSink?.let(MahjongInputBus::detach)
            activeSignalSink = null
            activeWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }

    // Keep the same stable parent/measurement structure as the working chess and
    // Dou Dizhu screens. Some tablet Compose layouts otherwise give AndroidView
    // no usable drawing height even though the surrounding page fills the screen.
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    activeWebView = this
                    val signalSink = MahjongWebSignalSink(this)
                    activeSignalSink = signalSink
                    MahjongInputBus.attach(signalSink)
                    addJavascriptInterface(
                        MahjongHostBridge(this) { currentOnBack() },
                        "InputDsMahjongHost"
                    )
                    setBackgroundColor(Color.rgb(8, 116, 62))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.e(
                                MAHJONG_LOG_TAG,
                                "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                            )
                            return true
                        }
                    }
                    webViewClient = OfflineMahjongWebViewClient(context.assets) {
                        signalSink.onPageReady()
                    }
                    loadUrl(MAHJONG_URL)
                }
            },
            update = {
                activeSignalSink?.setScanInterval(scanIntervalMs)
            }
        )
    }
}

private class OfflineMahjongWebViewClient(
    private val assets: android.content.res.AssetManager,
    private val onPageReady: () -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isMahjongAssetUrl()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse {
        val url = request.url
        if (!url.isMahjongAssetUrl()) return blockedResponse()

        val assetPath = url.path.orEmpty().removePrefix("/assets/")
        if (!assetPath.startsWith(MAHJONG_ASSET_PREFIX) ||
            assetPath.split('/').any { it == ".." }
        ) {
            return blockedResponse()
        }

        return try {
            WebResourceResponse(
                mimeTypeFor(assetPath),
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store",
                    "Content-Security-Policy" to
                        "default-src 'self'; script-src 'self'; style-src 'self'; " +
                        "img-src 'self' data:; media-src 'none'; connect-src 'none'"
                ),
                assets.open(assetPath)
            )
        } catch (_: Exception) {
            notFoundResponse()
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (Uri.parse(url).isMahjongAssetUrl()) onPageReady()
    }
}

private class MahjongWebSignalSink(
    private val webView: WebView
) : MahjongSignalSink {
    @Volatile
    private var pageReady = false
    private var scanIntervalMs = 1_500L

    fun onPageReady() {
        pageReady = true
        sendScanInterval()
    }

    fun setScanInterval(value: Long) {
        scanIntervalMs = value.coerceIn(1_100L, 3_000L)
        if (pageReady) sendScanInterval()
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
            webView.evaluateJavascript(
                "if (window.inputDsMahjong) window.inputDsMahjong.handleAction('$action')",
                null
            )
        }
    }

    private fun sendScanInterval() {
        webView.post {
            webView.evaluateJavascript(
                "if (window.inputDsMahjong) window.inputDsMahjong.setScanInterval($scanIntervalMs)",
                null
            )
        }
    }
}

private class MahjongHostBridge(
    private val webView: WebView,
    private val onRequestExit: () -> Unit
) {
    @JavascriptInterface
    fun requestExit() {
        webView.post { onRequestExit() }
    }
}

private fun Uri.isMahjongAssetUrl(): Boolean =
    scheme == "https" && host == ASSET_HOST &&
        path.orEmpty().startsWith("/assets/$MAHJONG_ASSET_PREFIX")

private fun mimeTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".svg") -> "image/svg+xml"
    path.endsWith(".png") -> "image/png"
    else -> "application/octet-stream"
}

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    404,
    "Not Found",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)
