package com.example.input_ds.ui.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.media.MediaNetwork
import com.example.input_ds.media.TvChannel
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class CctvWebStatus {
    LOADING,
    PLAYING,
    ERROR
}

/** Hosts CCTV's own protected-video player while preserving the app's BCI-focused chrome. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CctvOfficialPlayer(
    channel: TvChannel,
    onStatus: (CctvWebStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestStatusCallback = rememberUpdatedState(onStatus)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val bridge = CctvPlayerBridge { latestStatusCallback.value(it) }
            WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.userAgentString = DESKTOP_USER_AGENT
                webChromeClient = WebChromeClient()
                webViewClient = CctvWebViewClient(
                    httpClient = MediaNetwork.createClient(),
                    onStatus = { bridge.reportFromNative(it) }
                )
                addJavascriptInterface(bridge, BRIDGE_NAME)
            }
        },
        update = { webView ->
            if (webView.tag != channel.pageUrl) {
                webView.tag = channel.pageUrl
                latestStatusCallback.value(CctvWebStatus.LOADING)
                webView.loadUrl(channel.pageUrl)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.destroy()
        }
    )
}

private class CctvPlayerBridge(
    private val onStatus: (CctvWebStatus) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun report(status: String) {
        val mapped = when (status) {
            "playing" -> CctvWebStatus.PLAYING
            "error" -> CctvWebStatus.ERROR
            else -> CctvWebStatus.LOADING
        }
        mainHandler.post { onStatus(mapped) }
    }

    fun reportFromNative(status: CctvWebStatus) {
        mainHandler.post { onStatus(status) }
    }
}

private class CctvWebViewClient(
    private val httpClient: OkHttpClient,
    private val onStatus: (CctvWebStatus) -> Unit
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        onStatus(CctvWebStatus.LOADING)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(PLAYER_FOCUS_SCRIPT, null)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        if (request?.isForMainFrame == true) onStatus(CctvWebStatus.ERROR)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        webRequest: WebResourceRequest
    ): WebResourceResponse? {
        val url = webRequest.url.toString()
        if (webRequest.method != "GET" || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            return null
        }
        return runCatching {
            val requestBuilder = Request.Builder().url(url)
            webRequest.requestHeaders.forEach { (name, value) ->
                if (!name.equals("Host", ignoreCase = true) &&
                    !name.equals("Connection", ignoreCase = true) &&
                    !name.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    requestBuilder.header(name, value)
                }
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.toWebResourceResponse()
        }.getOrElse {
            if (webRequest.isForMainFrame) onStatus(CctvWebStatus.ERROR)
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream("CCTV resource unavailable".toByteArray())
            )
        }
    }
}

private fun Response.toWebResourceResponse(): WebResourceResponse {
    val responseBody = body
    val contentType = responseBody.contentType()
    val mimeType = contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
    val encoding = contentType?.charset()?.name() ?: "UTF-8"
    val responseHeaders = headers.toMultimap()
        .filterKeys {
            !it.equals("content-length", ignoreCase = true) &&
                !it.equals("content-encoding", ignoreCase = true) &&
                !it.equals("connection", ignoreCase = true)
        }
        .mapValues { (_, values) -> values.joinToString(",") }
    return WebResourceResponse(
        mimeType,
        encoding,
        code,
        message.ifBlank { "OK" },
        responseHeaders,
        ResponseClosingInputStream(responseBody.byteStream(), this)
    )
}

private class ResponseClosingInputStream(
    input: InputStream,
    private val response: Response
) : FilterInputStream(input) {
    override fun close() {
        try {
            super.close()
        } finally {
            response.close()
        }
    }
}

private const val BRIDGE_NAME = "InputDsCctvBridge"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val PLAYER_FOCUS_SCRIPT = """
(function () {
  var style = document.createElement('style');
  style.textContent = `
    html, body { margin: 0 !important; width: 100% !important; height: 100% !important;
      overflow: hidden !important; background: #000 !important; }
    body * { visibility: hidden !important; }
    #player, #player * { visibility: visible !important; }
    #player { position: fixed !important; inset: 0 !important; width: 100vw !important;
      height: 100vh !important; margin: 0 !important; background: #000 !important; }
    #player video, #player canvas, #player iframe, #player object { width: 100% !important;
      height: 100% !important; object-fit: contain !important; }
  `;
  document.head.appendChild(style);
  var attempts = 0;
  window.setInterval(function () {
    attempts += 1;
    var video = document.querySelector('#player video');
    var canvas = document.querySelector('#player canvas');
    if (video) {
      video.setAttribute('playsinline', '');
      video.controls = false;
      if (video.paused) video.play().catch(function () {});
      if (video.error) InputDsCctvBridge.report('error');
      else if (video.readyState >= 2 && !video.paused) InputDsCctvBridge.report('playing');
      else InputDsCctvBridge.report('loading');
    } else if (canvas && canvas.width > 0) {
      InputDsCctvBridge.report('playing');
    } else if (attempts > 30) {
      InputDsCctvBridge.report('error');
    }
  }, 1000);
})();
"""
