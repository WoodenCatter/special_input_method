package com.example.input_ds.ui.doudizhu

import android.annotation.SuppressLint
import android.graphics.Color
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.doudizhu.DoudizhuInputBus
import com.example.input_ds.doudizhu.DoudizhuSignalSink
import com.example.input_ds.model.ControlSignal
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val DOUDIZHU_ASSET_PREFIX = "doudizhu/"
private const val DOUDIZHU_URL = "https://$ASSET_HOST/assets/doudizhu/index.html"

/** Fully offline classic three-player Dou Dizhu screen with touch and EEG controls. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DoudizhuScreen(scanIntervalMs: Long, onBack: () -> Unit) {
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var activeSignalSink by remember { mutableStateOf<DoudizhuWebSignalSink?>(null) }
    val currentOnBack by rememberUpdatedState(onBack)

    DisposableEffect(Unit) {
        onDispose {
            activeSignalSink?.let(DoudizhuInputBus::detach)
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

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    activeWebView = this
                    val signalSink = DoudizhuWebSignalSink(this)
                    activeSignalSink = signalSink
                    DoudizhuInputBus.attach(signalSink)
                    addJavascriptInterface(
                        DoudizhuHostBridge(this) { currentOnBack() },
                        "InputDsDoudizhuHost"
                    )
                    setBackgroundColor(Color.rgb(18, 63, 39))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
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
                    webViewClient = OfflineDoudizhuWebViewClient(context.assets) {
                        signalSink.onPageReady()
                    }
                    loadUrl(DOUDIZHU_URL)
                }
            },
            update = {
                activeSignalSink?.setScanInterval(scanIntervalMs)
            }
        )
    }
}

private class OfflineDoudizhuWebViewClient(
    private val assets: android.content.res.AssetManager,
    private val onPageReady: () -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isDoudizhuAssetUrl()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse {
        val url = request.url
        if (!url.isDoudizhuAssetUrl()) return blockedResponse()

        val assetPath = url.path.orEmpty().removePrefix("/assets/")
        if (!assetPath.startsWith(DOUDIZHU_ASSET_PREFIX) || assetPath.split('/').any { it == ".." }) {
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
        if (Uri.parse(url).isDoudizhuAssetUrl()) onPageReady()
    }
}

private class DoudizhuWebSignalSink(
    private val webView: WebView
) : DoudizhuSignalSink {
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
            webView.evaluateJavascript("window.inputDsDoudizhu?.handleAction('$action')", null)
        }
    }

    private fun sendScanInterval() {
        webView.post {
            webView.evaluateJavascript(
                "window.inputDsDoudizhu?.setScanInterval($scanIntervalMs)",
                null
            )
        }
    }
}

private class DoudizhuHostBridge(
    private val webView: WebView,
    private val onRequestExit: () -> Unit
) {
    @JavascriptInterface
    fun requestExit() {
        webView.post { onRequestExit() }
    }
}

private fun Uri.isDoudizhuAssetUrl(): Boolean =
    scheme == "https" && host == ASSET_HOST &&
        path.orEmpty().startsWith("/assets/$DOUDIZHU_ASSET_PREFIX")

private fun mimeTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".png") -> "image/png"
    path.endsWith(".webmanifest") -> "application/manifest+json"
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
