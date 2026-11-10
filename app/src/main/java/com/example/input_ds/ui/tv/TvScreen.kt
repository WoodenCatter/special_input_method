package com.example.input_ds.ui.tv

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.tv.TvSignalSink
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.auroraBackground
import kotlinx.coroutines.delay

private data class TvChannel(
    val id: String,
    val name: String,
    val officialPages: List<String>
)

private fun cctvChannel(
    id: String,
    name: String,
    vararg officialPages: String
) = TvChannel(id, name, officialPages.toList())

private val TV_CHANNELS = listOf(
    cctvChannel("cctv1", "CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"),
    cctvChannel("cctv2", "CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"),
    cctvChannel("cctv4", "CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"),
    cctvChannel("cctv5", "CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"),
    cctvChannel("cctv5plus", "CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
    cctvChannel("cctv7", "CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
    cctvChannel("cctv10", "CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"),
    cctvChannel("cctv11", "CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"),
    cctvChannel("cctv12", "CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
    cctvChannel("cctv13", "CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"),
    cctvChannel("cctv15", "CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"),
    cctvChannel("cctv16", "CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
    cctvChannel("cctv17", "CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/")
)

private val CCTV_REQUEST_HEADERS = mapOf("Referer" to "https://tv.cctv.com/")

private val TV_OPERATION_LABELS = listOf("继续观看", "重新加载", "退出电视")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvScreen(scanIntervalMs: Long, onBack: () -> Unit) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var operationVisible by remember { mutableStateOf(false) }
    var operationIndex by remember { mutableIntStateOf(0) }
    var operationDirection by remember { mutableIntStateOf(1) }
    var reloadGeneration by remember { mutableIntStateOf(0) }
    var pageVariantIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    val currentOnBack by rememberUpdatedState(onBack)
    val listState = rememberLazyListState()
    val channel = TV_CHANNELS[currentIndex]
    val officialPage = channel.officialPages[pageVariantIndex.coerceAtMost(channel.officialPages.lastIndex)]

    fun selectChannel(index: Int) {
        val selectedIndex = (index + TV_CHANNELS.size) % TV_CHANNELS.size
        if (selectedIndex == currentIndex) return
        currentIndex = selectedIndex
        pageVariantIndex = 0
        loading = true
        errorMessage = null
    }

    fun executeOperation(index: Int) {
        when (index) {
            0 -> operationVisible = false
            1 -> {
                operationVisible = false
                pageVariantIndex = 0
                loading = true
                errorMessage = null
                reloadGeneration += 1
            }
            2 -> currentOnBack()
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        if (operationVisible) {
            when (signal) {
                ControlSignal.LEFT_LOOK -> {
                    operationDirection = -1
                    operationIndex = (operationIndex - 1 + TV_OPERATION_LABELS.size) % TV_OPERATION_LABELS.size
                }
                ControlSignal.RIGHT_LOOK -> {
                    operationDirection = 1
                    operationIndex = (operationIndex + 1) % TV_OPERATION_LABELS.size
                }
                ControlSignal.BITE -> executeOperation(operationIndex)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
        } else {
            when (signal) {
                ControlSignal.LEFT_LOOK -> selectChannel(currentIndex - 1)
                ControlSignal.RIGHT_LOOK -> selectChannel(currentIndex + 1)
                ControlSignal.BITE -> {
                    operationIndex = 0
                    operationDirection = 1
                    operationVisible = true
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
        }
    }
    val signalSink = remember { TvSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        TvInputBus.attach(signalSink)
        onDispose {
            TvInputBus.detach(signalSink)
            activeWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeJavascriptInterface(TV_FULLSCREEN_BRIDGE_NAME)
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }

    LaunchedEffect(operationVisible, operationIndex, operationDirection, scanIntervalMs) {
        if (operationVisible) {
            delay(scanIntervalMs.coerceIn(1_100L, 3_000L))
            operationIndex = (operationIndex + operationDirection + TV_OPERATION_LABELS.size) %
                TV_OPERATION_LABELS.size
        }
    }

    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(currentIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .auroraBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("看电视", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "左看、右看切换频道 · 咬牙打开电视操作 · 当前：${channel.name}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { operationVisible = true }) { Text("电视操作") }
                TextButton(onClick = currentOnBack) { Text("返回首页") }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(18.dp))
                    .border(2.dp, Color(0xFF364B5A), RoundedCornerShape(18.dp))
            ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        factory = { context ->
                            WebView(context).apply {
                                activeWebView = this
                                setBackgroundColor(AndroidColor.BLACK)
                                keepScreenOn = true
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    javaScriptCanOpenWindowsAutomatically = false
                                    setSupportMultipleWindows(false)
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    loadsImagesAutomatically = true
                                    blockNetworkImage = false
                                    userAgentString = userAgentString.replace("; wv", "")
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                addJavascriptInterface(
                                    TvFullscreenTapBridge(this),
                                    TV_FULLSCREEN_BRIDGE_NAME
                                )
                                webChromeClient = WebChromeClient()
                                webViewClient = OfficialTvWebViewClient(
                                    onStarted = {
                                        loading = true
                                        errorMessage = null
                                    },
                                    onFinished = { loading = false },
                                    onError = { message ->
                                        val alternatives = TV_CHANNELS[currentIndex].officialPages
                                        if (pageVariantIndex < alternatives.lastIndex) {
                                            pageVariantIndex += 1
                                            loading = true
                                            errorMessage = null
                                        } else {
                                            loading = false
                                            errorMessage = message
                                        }
                                    }
                                )
                            }
                        },
                        update = { webView ->
                            val request = "${channel.id}:$pageVariantIndex:$reloadGeneration"
                            if (webView.tag != request) {
                                webView.tag = request
                                webView.loadUrl(officialPage, CCTV_REQUEST_HEADERS)
                            }
                        }
                    )

                    if (loading) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            color = Color(0xD9223039),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 3.dp)
                                Text(
                                    if (pageVariantIndex == 0) {
                                        "正在打开 ${channel.name} 官方直播"
                                    } else {
                                        "正在尝试 ${channel.name} 备用官方入口"
                                    }
                                )
                            }
                        }
                    }

                    errorMessage?.let { message ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color(0xEE2C2225), RoundedCornerShape(18.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("频道暂时无法打开", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(
                                onClick = {
                                    pageVariantIndex = 0
                                    loading = true
                                    errorMessage = null
                                    reloadGeneration += 1
                                }
                            ) {
                                Text("重新加载")
                            }
                        }
                    }
            }

            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(TV_CHANNELS, key = { _, item -> item.id }) { index, item ->
                        TvChannelButton(
                            channel = item,
                            selected = index == currentIndex,
                            onClick = { selectChannel(index) }
                        )
                    }
                }
            }
        }

        if (operationVisible) {
            TvOperationOverlay(
                selectedIndex = operationIndex,
                onSelect = ::executeOperation,
                onDismiss = { operationVisible = false }
            )
        }
    }
}

@Composable
private fun TvChannelButton(channel: TvChannel, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(176.dp),
        color = if (selected) Color(0xFF384D68) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) AuroraVioletBright else MaterialTheme.colorScheme.outline
        )
    ) {
        Text(
            channel.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TvOperationOverlay(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .border(2.dp, AuroraVioletBright, RoundedCornerShape(24.dp))
                .clickable { }
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("电视操作", style = MaterialTheme.typography.headlineSmall)
            TV_OPERATION_LABELS.forEachIndexed { index, label ->
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (index == selectedIndex) {
                            AuroraVioletBright
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (index == selectedIndex) Color(0xFF1C1730) else MaterialTheme.colorScheme.onSurface
                    ),
                    border = if (index == selectedIndex) {
                        androidx.compose.foundation.BorderStroke(3.dp, Color.White)
                    } else {
                        null
                    }
                ) {
                    Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private class OfficialTvWebViewClient(
    private val onStarted: () -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit
) : WebViewClient() {
    private var failedMainFrameUrl: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isAllowedOfficialTvPage()

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (failedMainFrameUrl != url) failedMainFrameUrl = null
        onStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        view.evaluateJavascript(
            """
            (function() {
              var text = document.body ? document.body.innerText : '';
              return text.indexOf('对不起，可能是网络原因或无此页面') >= 0 ||
                     text.indexOf('403 Forbidden') >= 0;
            })();
            """.trimIndent()
        ) { isCctvErrorPage ->
            if (view.url != url) return@evaluateJavascript
            if (isCctvErrorPage == "true") {
                reportPageError(url, "该央视入口暂时不可用")
            } else {
                view.evaluateJavascript(TV_AUTO_FULLSCREEN_SCRIPT, null)
                onFinished()
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        val description = error.description?.toString().orEmpty()
        val wasCancelledByChannelChange = description.contains("ERR_ABORTED", ignoreCase = true)
        if (request.isForMainFrame && !wasCancelledByChannelChange) {
            reportPageError(
                request.url.toString(),
                description.ifBlank { "网络连接失败" }
            )
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            reportPageError(
                request.url.toString(),
                "官方页面返回错误：${errorResponse.statusCode}"
            )
        }
    }

    private fun reportPageError(url: String, message: String) {
        if (failedMainFrameUrl == url) return
        failedMainFrameUrl = url
        onError(message)
    }
}

private const val TV_FULLSCREEN_BRIDGE_NAME = "InputDsTvFullscreen"

/**
 * A JavaScript click is not considered a user gesture by Chromium, so it cannot
 * reliably activate HTML5 fullscreen. This bridge receives only the player
 * rectangle and dispatches native touchscreen events into the WebView: first at
 * the centre to reveal controls, then at the bottom-right fullscreen control.
 */
private class TvFullscreenTapBridge(private val webView: WebView) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun open(
        pageUrl: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        viewportWidth: Double,
        viewportHeight: Double
    ) {
        mainHandler.post {
            if (webView.url != pageUrl || webView.width <= 0 || webView.height <= 0) return@post
            if (!listOf(left, top, right, bottom, viewportWidth, viewportHeight).all { it.isFinite() }) {
                return@post
            }
            if (viewportWidth <= 0.0 || viewportHeight <= 0.0 || right <= left || bottom <= top) {
                return@post
            }

            val scaleX = webView.width / viewportWidth
            val scaleY = webView.height / viewportHeight
            val centreX = ((left + right) / 2.0 * scaleX).toFloat()
            val centreY = ((top + bottom) / 2.0 * scaleY).toFloat()
            val controlInsetX = minOf(34.0, (right - left) * 0.045)
            val controlInsetY = minOf(30.0, (bottom - top) * 0.065)
            val fullscreenX = ((right - controlInsetX) * scaleX).toFloat()
            val fullscreenY = ((bottom - controlInsetY) * scaleY).toFloat()

            dispatchTap(centreX, centreY)
            mainHandler.postDelayed({
                if (webView.url == pageUrl) dispatchTap(fullscreenX, fullscreenY)
            }, 650L)
        }
    }

    private fun dispatchTap(x: Float, y: Float) {
        val safeX = x.coerceIn(1f, (webView.width - 2).coerceAtLeast(1).toFloat())
        val safeY = y.coerceIn(1f, (webView.height - 2).coerceAtLeast(1).toFloat())
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, safeX, safeY, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        down.recycle()

        mainHandler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, safeX, safeY, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 80L)
    }
}

/** Finds the visible CCTV player and asks the native bridge to tap its controls. */
private const val TV_AUTO_FULLSCREEN_SCRIPT = """
(function () {
  if (window.__inputDsAutoFullscreenStarted) return;
  window.__inputDsAutoFullscreenStarted = true;

  function largestVisible(candidates) {
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < candidates.length; i += 1) {
      var rect = candidates[i].getBoundingClientRect();
      var visibleWidth = Math.max(0, Math.min(rect.right, window.innerWidth) - Math.max(rect.left, 0));
      var visibleHeight = Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
      var area = visibleWidth * visibleHeight;
      if (area > bestArea) {
        best = candidates[i];
        bestArea = area;
      }
    }
    return best;
  }

  function findPlayer() {
    var selectors = [
      '#player', '#videoPlayer', '.video-player', '.video_player', '.player-container',
      '.player_wrap', '.video-wrap', '[class*="player-wrap"]', '[class*="video-player"]',
      'video', 'canvas', 'iframe[src*="player"]', 'iframe[src*="live"]'
    ];
    return largestVisible(Array.prototype.slice.call(document.querySelectorAll(selectors.join(','))));
  }

  var attempts = 0;
  var timer = window.setInterval(function () {
    attempts += 1;
    var player = findPlayer();
    if (player) {
      var rect = player.getBoundingClientRect();
      var minimumArea = window.innerWidth * window.innerHeight * 0.12;
      if (rect.width * rect.height >= minimumArea) {
        window.clearInterval(timer);
        InputDsTvFullscreen.open(
          window.location.href,
          rect.left,
          rect.top,
          rect.right,
          rect.bottom,
          window.innerWidth,
          window.innerHeight
        );
      }
    }
    if (attempts >= 60) window.clearInterval(timer);
  }, 500);
})();
"""

private fun Uri.isAllowedOfficialTvPage(): Boolean {
    if (scheme != "https") return false
    val currentHost = host?.lowercase() ?: return false
    return listOf("cctv.com", "cctv.cn", "cntv.cn")
        .any { currentHost == it || currentHost.endsWith(".$it") }
}
