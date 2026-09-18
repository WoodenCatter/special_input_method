package com.example.input_ds.ui.chess

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
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
import com.example.input_ds.chess.ChineseChessInputBus
import com.example.input_ds.chess.ChineseChessSignalSink
import com.example.input_ds.model.ControlSignal
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val CHESS_ASSET_PREFIX = "chinese_chess/"
private const val CHESS_URL = "https://$ASSET_HOST/assets/chinese_chess/index.html"

/** Offline chess screen supporting both direct touch and EEG scanning control. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChineseChessScreen(scanIntervalMs: Long, onBack: () -> Unit) {
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var activeSignalSink by remember { mutableStateOf<ChineseChessWebSignalSink?>(null) }
    val currentOnBack by rememberUpdatedState(onBack)

    DisposableEffect(Unit) {
        onDispose {
            activeSignalSink?.let(ChineseChessInputBus::detach)
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

    // Keep the same stable parent structure used by the previously working screen.
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    activeWebView = this
                    val signalSink = ChineseChessWebSignalSink(this)
                    activeSignalSink = signalSink
                    ChineseChessInputBus.attach(signalSink)
                    addJavascriptInterface(
                        ChineseChessHostBridge(this) { currentOnBack() },
                        "InputDsChessHost"
                    )
                    setBackgroundColor(Color.rgb(245, 239, 229))
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
                    webViewClient = OfflineChessWebViewClient(context.assets) {
                        signalSink.onPageReady()
                    }
                    loadUrl(CHESS_URL)
                }
            },
            update = {
                activeSignalSink?.setScanInterval(scanIntervalMs)
            }
        )
    }
}

private class OfflineChessWebViewClient(
    private val assets: android.content.res.AssetManager,
    private val onPageReady: () -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isChessAssetUrl()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse {
        val url = request.url
        if (!url.isChessAssetUrl()) return blockedResponse()

        val assetPath = url.path.orEmpty().removePrefix("/assets/")
        if (!assetPath.startsWith(CHESS_ASSET_PREFIX) || assetPath.split('/').any { it == ".." }) {
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
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
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
        if (Uri.parse(url).isChessAssetUrl()) onPageReady()
    }
}

private class ChineseChessWebSignalSink(
    private val webView: WebView
) : ChineseChessSignalSink {
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
            webView.evaluateJavascript("window.inputDsChess?.handleAction('$action')", null)
        }
    }

    private fun sendScanInterval() {
        webView.post {
            webView.evaluateJavascript(
                "window.inputDsChess?.setScanInterval($scanIntervalMs)",
                null
            )
        }
    }
}

private class ChineseChessHostBridge(
    private val webView: WebView,
    private val onRequestExit: () -> Unit
) {
    @JavascriptInterface
    fun requestExit() {
        webView.post { onRequestExit() }
    }
}

private fun Uri.isChessAssetUrl(): Boolean =
    scheme == "https" && host == ASSET_HOST && path.orEmpty().startsWith("/assets/$CHESS_ASSET_PREFIX")

private fun mimeTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".svg") -> "image/svg+xml"
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
