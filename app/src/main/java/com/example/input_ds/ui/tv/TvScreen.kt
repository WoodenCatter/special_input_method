package com.example.input_ds.ui.tv

import com.example.input_ds.ui.design.*
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.tv.TvSignalSink
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.delay

internal data class TvChannel(
    val id: String,
    val name: String,
    val group: String,
    val officialPages: List<String>,
    val temporarilyUnavailable: Boolean = false
)

private fun cctvChannel(
    id: String,
    name: String,
    vararg officialPages: String
) = TvChannel(id, name, "央视", officialPages.toList())

private fun unavailableCctvChannel(
    id: String,
    name: String,
    officialPage: String
) = TvChannel(id, name, "央视", listOf(officialPage), temporarilyUnavailable = true)

internal val TV_CHANNELS = listOf(
    cctvChannel("cctv1", "CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"),
    cctvChannel("cctv2", "CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"),
    unavailableCctvChannel("cctv3", "CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"),
    cctvChannel("cctv4", "CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"),
    cctvChannel("cctv5", "CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"),
    cctvChannel("cctv5plus", "CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
    unavailableCctvChannel("cctv6", "CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"),
    cctvChannel("cctv7", "CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
    unavailableCctvChannel("cctv8", "CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"),
    unavailableCctvChannel("cctv9", "CCTV-9 纪录", "https://tv.cctv.com/live/cctv9/"),
    cctvChannel("cctv10", "CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"),
    cctvChannel("cctv11", "CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"),
    cctvChannel("cctv12", "CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
    cctvChannel("cctv13", "CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"),
    unavailableCctvChannel("cctv14", "CCTV-14 少儿", "https://tv.cctv.com/live/cctv14/"),
    cctvChannel("cctv15", "CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"),
    cctvChannel("cctv16", "CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
    cctvChannel("cctv17", "CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/")
)

private val CCTV_REQUEST_HEADERS = mapOf("Referer" to "https://tv.cctv.com/")

private val TV_OPERATION_LABELS = listOf("继续观看", "重新加载", "退出电视")

internal sealed interface TvPlaybackCommand {
    data class Tune(val index: Int) : TvPlaybackCommand
    data object OpenMenu : TvPlaybackCommand
    data object Ignore : TvPlaybackCommand
}

internal fun resolveTvPlaybackCommand(
    signal: ControlSignal,
    currentIndex: Int,
    channelCount: Int
): TvPlaybackCommand {
    if (channelCount <= 0) return TvPlaybackCommand.Ignore
    return when (signal) {
        ControlSignal.LEFT_LOOK -> TvPlaybackCommand.Tune((currentIndex - 1 + channelCount) % channelCount)
        ControlSignal.RIGHT_LOOK -> TvPlaybackCommand.Tune((currentIndex + 1) % channelCount)
        ControlSignal.BITE -> TvPlaybackCommand.OpenMenu
        ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> TvPlaybackCommand.Ignore
    }
}

internal data class TvControlState(
    val currentIndex: Int,
    val channelCount: Int,
    val fullscreen: Boolean,
    val operationVisible: Boolean,
    val operationIndex: Int,
    val operationDirection: Int,
    val operationCount: Int = 3
)

internal sealed interface TvControlEffect {
    data class Tune(val index: Int) : TvControlEffect
    data class OpenMenu(val exitFullscreen: Boolean) : TvControlEffect
    data class UpdateMenu(val index: Int, val direction: Int) : TvControlEffect
    data class ExecuteMenu(val index: Int) : TvControlEffect
    data object Ignore : TvControlEffect
}

internal fun dispatchTvSignal(
    state: TvControlState,
    signal: ControlSignal,
    scanMode: ScanMode
): TvControlEffect {
    if (state.operationVisible && !state.fullscreen) {
        return when (signal) {
            ControlSignal.LEFT_LOOK -> TvControlEffect.UpdateMenu(
                index = if (scanMode == ScanMode.MANUAL) {
                    (state.operationIndex - 1 + state.operationCount) % state.operationCount
                } else state.operationIndex,
                direction = -1
            )
            ControlSignal.RIGHT_LOOK -> TvControlEffect.UpdateMenu(
                index = if (scanMode == ScanMode.MANUAL) {
                    (state.operationIndex + 1) % state.operationCount
                } else state.operationIndex,
                direction = 1
            )
            ControlSignal.BITE -> TvControlEffect.ExecuteMenu(state.operationIndex)
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> TvControlEffect.Ignore
        }
    }
    return when (val cmd = resolveTvPlaybackCommand(signal, state.currentIndex, state.channelCount)) {
        is TvPlaybackCommand.Tune -> TvControlEffect.Tune(cmd.index)
        TvPlaybackCommand.OpenMenu -> TvControlEffect.OpenMenu(exitFullscreen = state.fullscreen)
        TvPlaybackCommand.Ignore -> TvControlEffect.Ignore
    }
}

@Composable
fun TvScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    onBack: () -> Unit,
    initialChannelId: String? = null,
    fromAssistant: Boolean = false,
    paused: Boolean = false,
    frozen: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onBoundary: () -> Unit = {}
) {
    TvScreenContent(
        scanIntervalMs = scanIntervalMs,
        scanMode = scanMode,
        onBack = onBack,
        initialChannelId = initialChannelId,
        fromAssistant = fromAssistant,
        paused = paused,
        frozen = frozen,
        onToast = onToast,
        onBoundary = onBoundary
    )
}

internal fun releaseTvWebView(webView: WebView) {
    webView.webChromeClient = null
    webView.webViewClient = WebViewClient()
    webView.onPause()
    webView.stopLoading()
    webView.loadUrl("about:blank")
    webView.clearHistory()
    webView.removeAllViews()
    webView.destroy()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TvScreenContent(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    onBack: () -> Unit,
    initialChannelId: String? = null,
    fromAssistant: Boolean = false,
    initialFullscreen: Pair<View, WebChromeClient.CustomViewCallback>? = null,
    enableWebView: Boolean = true,
    paused: Boolean = false,
    frozen: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onBoundary: () -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {},
    onWebViewReleased: (WebView) -> Unit = ::releaseTvWebView
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val returnLabel = if (fromAssistant) "返回助手" else "返回娱乐"
    val operationLabels = remember(returnLabel) { listOf("继续观看", "重新加载", returnLabel) }
    val initialIndex = TV_CHANNELS.indexOfFirst { it.id == initialChannelId }.coerceAtLeast(0)
    var currentIndex by remember(initialChannelId) { mutableIntStateOf(initialIndex) }
    val colors = LocalBciColors.current
    var showChannel by remember(currentIndex, frozen) { mutableStateOf(true) }
    var operationVisible by remember { mutableStateOf(false) }
    var menuCursor by remember(operationLabels.size) {
        mutableStateOf(ScanCursor(size = operationLabels.size, index = 0, direction = 1))
    }
    var dwellRevision by remember { mutableIntStateOf(0) }
    var reloadGeneration by remember { mutableIntStateOf(0) }
    var pageVariantIndex by remember { mutableIntStateOf(0) }
    val showLiveWebView = enableWebView && !frozen
    var loading by remember(initialChannelId, showLiveWebView) {
        mutableStateOf(showLiveWebView && !TV_CHANNELS[initialIndex].temporarilyUnavailable)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fullscreenView by remember(initialFullscreen) { mutableStateOf(initialFullscreen?.first) }
    var fullscreenCallback by remember(initialFullscreen) { mutableStateOf(initialFullscreen?.second) }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnToast by rememberUpdatedState(onToast)
    val currentOnBoundary by rememberUpdatedState(onBoundary)
    val currentOnWebViewCreated by rememberUpdatedState(onWebViewCreated)
    val currentOnWebViewReleased by rememberUpdatedState(onWebViewReleased)
    val channel = TV_CHANNELS[currentIndex]
    val officialPage = channel.officialPages[pageVariantIndex.coerceAtMost(channel.officialPages.lastIndex)]
    val safeIntervalMs = scanIntervalMs.coerceIn(1_100L, 3_000L)

    ReportActionHints(
        if (operationVisible && fullscreenView == null) {
            ActionHints.list(operationLabels[menuCursor.index], scanMode, menuCursor.direction)
        } else {
            ActionHints("上一台", "打开菜单", "下一台")
        }
    )

    fun exitFullscreen() {
        val callback = fullscreenCallback
        fullscreenCallback = null
        fullscreenView = null
        callback?.onCustomViewHidden()
    }

    fun closeMenu() {
        if (operationVisible) {
            operationVisible = false
            currentOnBoundary()
        }
    }

    BackHandler(enabled = !paused && fullscreenView != null, onBack = ::exitFullscreen)
    BackHandler(enabled = !paused && operationVisible && fullscreenView == null, onBack = ::closeMenu)

    fun selectChannel(index: Int) {
        val selectedIndex = (index + TV_CHANNELS.size) % TV_CHANNELS.size
        if (selectedIndex == currentIndex) return
        exitFullscreen()
        currentIndex = selectedIndex
        pageVariantIndex = 0
        loading = showLiveWebView && !TV_CHANNELS[selectedIndex].temporarilyUnavailable
        errorMessage = null
    }

    fun openMenu(exitFull: Boolean = false) {
        if (exitFull) exitFullscreen()
        menuCursor = ScanCursor(size = operationLabels.size, index = 0, direction = 1)
        dwellRevision++
        operationVisible = true
        currentOnBoundary()
    }

    fun executeOperation(index: Int) {
        when (index) {
            0 -> closeMenu()
            1 -> {
                closeMenu()
                pageVariantIndex = 0
                loading = showLiveWebView && !channel.temporarilyUnavailable
                errorMessage = null
                reloadGeneration += 1
            }
            2 -> {
                closeMenu()
                currentOnBack()
            }
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        val effect = dispatchTvSignal(
            state = TvControlState(
                currentIndex = currentIndex,
                channelCount = TV_CHANNELS.size,
                fullscreen = fullscreenView != null,
                operationVisible = operationVisible,
                operationIndex = menuCursor.index,
                operationDirection = menuCursor.direction,
                operationCount = operationLabels.size
            ),
            signal = signal,
            scanMode = scanMode
        )
        when (effect) {
            is TvControlEffect.Tune -> selectChannel(effect.index)
            is TvControlEffect.OpenMenu -> openMenu(exitFull = effect.exitFullscreen)
            is TvControlEffect.UpdateMenu -> {
                menuCursor = menuCursor.look(effect.direction, scanMode)
                dwellRevision++
            }
            is TvControlEffect.ExecuteMenu -> {
                val chosen = menuCursor.selected(
                    now = SystemClock.elapsedRealtime(),
                    graceMs = if (frozen) 0L else AppUiSettings.graceMs(context),
                    mode = scanMode
                )
                if (chosen != menuCursor.index && !frozen) {
                    currentOnToast("已按上一项执行（延迟补偿）", false)
                }
                executeOperation(chosen)
            }
            TvControlEffect.Ignore -> Unit
        }
    }
    val tickHandler = rememberUpdatedState<() -> Unit> {
        if (operationVisible && fullscreenView == null) {
            menuCursor = menuCursor.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
        }
    }
    val resetHandler = rememberUpdatedState<() -> Unit> {
        exitFullscreen()
        closeMenu()
        currentIndex = 0
        showChannel = true
        pageVariantIndex = 0
        errorMessage = null
        loading = showLiveWebView && !TV_CHANNELS[0].temporarilyUnavailable
        menuCursor = ScanCursor(size = operationLabels.size, index = 0, direction = 1)
        dwellRevision++
    }
    val signalSink = remember { TvSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        TvInputBus.attach(
            candidate = signalSink,
            onTick = { tickHandler.value() },
            onReset = { resetHandler.value() }
        )
        onDispose {
            TvInputBus.detach(signalSink)
            exitFullscreen()
        }
    }

    LaunchedEffect(paused) {
        menuCursor = menuCursor.reset()
    }

    LaunchedEffect(paused, frozen, scanMode, operationVisible, fullscreenView != null, dwellRevision, safeIntervalMs, lifecycleOwner) {
        if (!paused && !frozen && scanMode == ScanMode.AUTO && operationVisible && fullscreenView == null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                menuCursor = menuCursor.copy(previousIndex = -1, advancedAtMs = 0L)
                runScanTimer(
                    interval = { safeIntervalMs },
                    onFailure = { android.util.Log.e("ScanCursor", "TV menu tick failed", it) }
                ) {
                    menuCursor = menuCursor.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
                }
            }
        }
    }

    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(
        operationVisible,
        fullscreenView != null,
        menuCursor.index,
        menuCursor.direction,
        dwellRevision,
        safeIntervalMs,
        scanMode,
        paused,
        frozen,
        lifecycleOwner
    ) {
        when {
            !operationVisible || fullscreenView != null -> dwellProgress.snapTo(0f)
            frozen -> dwellProgress.snapTo(if (scanMode == ScanMode.AUTO) 0.5f else 0f)
            paused || scanMode != ScanMode.AUTO -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(safeIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    LaunchedEffect(currentIndex, frozen) {
        showChannel = true
        if (!frozen) {
            delay(3_000)
            showChannel = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .drawBehind {
                        if (size.width > 0f && size.height > 0f) {
                            val shader = AndroidRadialGradient(
                                0f,
                                0f,
                                1f,
                                intArrayOf(0xFF2C3A44.toInt(), 0xFF151B20.toInt(), 0xFF0B0E10.toInt()),
                                floatArrayOf(0f, 0.55f, 1f),
                                Shader.TileMode.CLAMP
                            )
                            val matrix = Matrix().apply {
                                setScale(size.width * 1.2f, size.height * 0.9f)
                                postTranslate(size.width * 0.3f, size.height * 0.2f)
                            }
                            shader.setLocalMatrix(matrix)
                            drawRect(ShaderBrush(shader))
                        }
                    }
            ) {
                if (showLiveWebView) {
                    DisposableEffect(Unit) {
                        onDispose { exitFullscreen() }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(AndroidColor.BLACK)
                                keepScreenOn = true
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
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
                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                        if (fullscreenView != null) {
                                            callback.onCustomViewHidden()
                                            return
                                        }
                                        (view.parent as? ViewGroup)?.removeView(view)
                                        fullscreenCallback = callback
                                        fullscreenView = view
                                    }

                                    override fun onHideCustomView() = exitFullscreen()
                                }
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
                                currentOnWebViewCreated(this)
                            }
                        },
                        update = { webView ->
                            val request = "${channel.id}:$pageVariantIndex:$reloadGeneration"
                            if (webView.tag != request) {
                                webView.tag = request
                                if (channel.temporarilyUnavailable) {
                                    webView.stopLoading()
                                    webView.loadUrl("about:blank")
                                } else {
                                    webView.loadUrl(officialPage, CCTV_REQUEST_HEADERS)
                                }
                            }
                        },
                        onRelease = { webView ->
                            currentOnWebViewReleased(webView)
                        }
                    )
                }

                if (channel.temporarilyUnavailable) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xEE202A31),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF647783))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 34.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("当前频道不可播放", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3F0E8))
                            Text(
                                "该频道将在后续版本继续处理",
                                color = Color(0xFFA9B0B8)
                            )
                        }
                    }
                }

                if (showLiveWebView && loading && !channel.temporarilyUnavailable) {
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
                                },
                                color = Color(0xFFF3F0E8)
                            )
                        }
                    }
                }

                if (showLiveWebView && !channel.temporarilyUnavailable) errorMessage?.let { message ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xEE2C2225), RoundedCornerShape(18.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("频道暂时无法打开", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3F0E8))
                        Text(message, color = Color(0xFFA9B0B8))
                        Button(
                            onClick = {
                                pageVariantIndex = 0
                                loading = true
                                errorMessage = null
                                reloadGeneration += 1
                            },
                            enabled = !paused
                        ) {
                            Text("重新加载")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 26.dp, top = 22.dp)
                        .then(
                            if (showLiveWebView) {
                                Modifier
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            } else Modifier
                        )
                        .clickable(enabled = !paused) { openMenu(exitFull = false) }
                ) {
                    BciLabel("● 直播 · 官方网页源", 18, Color(0xFFA9B0B8))
                }

                if (showChannel) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(30.dp)
                            .then(
                                if (showLiveWebView) {
                                    Modifier
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                } else Modifier
                            ),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = channel.name.substringBefore(' '),
                            color = Color(0xFFF3F0E8),
                            fontFamily = BciType.latin,
                            fontWeight = FontWeight(800),
                            fontSize = 64.sp,
                            lineHeight = 64.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            modifier = Modifier.alignByBaseline()
                        )
                        Text(
                            text = channel.name.substringAfter(' '),
                            color = Color(0xFFA9B0B8),
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Normal,
                            fontSize = 28.sp,
                            lineHeight = 28.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (offset in -2..2) {
                    val index = (currentIndex + offset).mod(TV_CHANNELS.size)
                    val selected = offset == 0
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(if (selected) colors.focusTint.compositeOver(colors.panel) else colors.panel)
                            .border(2.dp, if (selected) colors.focus else colors.line, shape)
                            .clickable(enabled = !paused) { selectChannel(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        BciLabel(
                            text = TV_CHANNELS[index].name.substringBefore(' '),
                            size = 22,
                            color = if (selected) colors.text else colors.muted,
                            weight = 700,
                            latin = true
                        )
                    }
                }
            }
        }

        fullscreenView?.let { view ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                Button(
                    onClick = ::exitFullscreen,
                    enabled = !paused,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Text("退出全屏")
                }
            }
        }

        val publishStageOverlay = LocalStageOverlay.current
        val showMenuOverlay = operationVisible && fullscreenView == null
        val currentOperationLabels = rememberUpdatedState(operationLabels)
        val currentScanMode = rememberUpdatedState(scanMode)
        val currentPaused = rememberUpdatedState(paused)
        val currentExecuteOperation = rememberUpdatedState(::executeOperation)

        if (publishStageOverlay != null) {
            DisposableEffect(publishStageOverlay, showMenuOverlay) {
                if (showMenuOverlay) {
                    publishStageOverlay {
                        val labels = currentOperationLabels.value
                        val mode = currentScanMode.value
                        val isPaused = currentPaused.value
                        TvOperationOverlay(
                            labels = labels,
                            selectedIndex = menuCursor.index,
                            direction = if (mode == ScanMode.MANUAL) 0 else menuCursor.direction,
                            progress = { if (mode == ScanMode.AUTO) dwellProgress.value else null },
                            paused = isPaused,
                            includePointerBlocker = false,
                            onSelect = { currentExecuteOperation.value(it) }
                        )
                    }
                } else {
                    publishStageOverlay(null)
                }
                onDispose { publishStageOverlay(null) }
            }
        } else if (showMenuOverlay) {
            TvOperationOverlay(
                labels = operationLabels,
                selectedIndex = menuCursor.index,
                direction = if (scanMode == ScanMode.MANUAL) 0 else menuCursor.direction,
                progress = { if (scanMode == ScanMode.AUTO) dwellProgress.value else null },
                paused = paused,
                includePointerBlocker = true,
                onSelect = ::executeOperation
            )
        }
    }
}

@Composable
private fun TvOperationOverlay(
    labels: List<String>,
    selectedIndex: Int,
    direction: Int,
    progress: () -> Float?,
    paused: Boolean,
    includePointerBlocker: Boolean,
    onSelect: (Int) -> Unit
) {
    val colors = LocalBciColors.current
    Box(Modifier.fillMaxSize().zIndex(100f)) {
        val backdropModifier = if (includePointerBlocker) {
            Modifier
                .fillMaxSize()
                .background(colors.ov)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
        } else {
            Modifier
                .fillMaxSize()
                .background(colors.ov)
        }
        Box(backdropModifier)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Text(
                text = "电视菜单",
                color = colors.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 40.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val focused = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = direction,
                                picked = false,
                                progress = if (focused) progress else null
                            )
                            .clickable(enabled = !paused) { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = colors.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
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

private fun Uri.isAllowedOfficialTvPage(): Boolean {
    if (scheme != "https") return false
    val currentHost = host?.lowercase() ?: return false
    return listOf("cctv.com", "cctv.cn", "cntv.cn")
        .any { currentHost == it || currentHost.endsWith(".$it") }
}
