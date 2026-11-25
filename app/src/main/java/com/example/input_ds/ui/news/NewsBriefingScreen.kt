package com.example.input_ds.ui.news

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.news.CachedNewsFeed
import com.example.input_ds.news.NewsArticle
import com.example.input_ds.news.NewsArticleDetail
import com.example.input_ds.news.NewsArticleDetailFetcher
import com.example.input_ds.news.NewsArticleExtractor
import com.example.input_ds.news.NewsArticlePaginator
import com.example.input_ds.news.NewsBriefingStore
import com.example.input_ds.news.NewsCatalog
import com.example.input_ds.news.NewsContentBlock
import com.example.input_ds.news.NewsFeedFetcher
import com.example.input_ds.news.NewsImageLoader
import com.example.input_ds.news.NewsInputBus
import com.example.input_ds.news.NewsReadingPage
import com.example.input_ds.news.NewsSignalSink
import com.example.input_ds.news.NewsSource
import com.example.input_ds.news.OkHttpNewsFeedFetcher
import com.example.input_ds.news.RssFeedParser
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BROWSE_MENU_DEFINITIONS = listOf(
    NewsScanState.BROWSE_READ_FULL to "阅读全文",
    NewsScanState.BROWSE_CONTINUE to "继续浏览",
    NewsScanState.BROWSE_SOURCES to "换新闻源",
    NewsScanState.BROWSE_DIRECTORY to "新闻目录",
    NewsScanState.BROWSE_REFRESH to "刷新",
    NewsScanState.BROWSE_BACK to "返回娱乐"
)

private val READING_MENU_DEFINITIONS = listOf(
    NewsScanState.READING_CONTINUE to "继续阅读",
    NewsScanState.READING_SPEAK to "朗读本页",
    NewsScanState.READING_NEXT_ARTICLE to "下一篇",
    NewsScanState.READING_EXIT to "返回浏览"
)

internal fun effectiveNewsScanDelayMs(focusMode: NewsFocusMode, baseIntervalMs: Long): Long {
    val base = baseIntervalMs.coerceIn(1_100L, 3_000L)
    return when (focusMode) {
        // Headlines are 20-35 Chinese characters; give comfortable reading time per row.
        NewsFocusMode.ARTICLES -> (base * 2.6).toLong().coerceIn(4_000L, 6_500L)
        NewsFocusMode.SOURCES -> (base * 1.4).toLong().coerceIn(2_200L, 4_000L)
        NewsFocusMode.BROWSE,
        NewsFocusMode.BROWSE_MENU,
        NewsFocusMode.READING,
        NewsFocusMode.READING_MENU -> base
    }
}

@Composable
fun NewsBriefingScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    paused: Boolean,
    frozen: Boolean = false,
    onSpeak: (text: String, onDone: () -> Unit) -> Unit,
    onStopSpeak: () -> Unit,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onBoundary: () -> Unit = {},
    onBack: () -> Unit,
    sources: List<NewsSource> = NewsCatalog.sources,
    fetcher: NewsFeedFetcher = OkHttpNewsFeedFetcher,
    detailFetcher: NewsArticleDetailFetcher = NewsArticleExtractor
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(appContext) { NewsBriefingStore(appContext) }

    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnSpeak by rememberUpdatedState(onSpeak)
    val currentOnStopSpeak by rememberUpdatedState(onStopSpeak)
    val currentOnToast by rememberUpdatedState(onToast)
    val currentOnBoundary by rememberUpdatedState(onBoundary)
    val currentFetcher by rememberUpdatedState(fetcher)
    val currentDetailFetcher by rememberUpdatedState(detailFetcher)

    val safeSources = remember(sources) { sources.ifEmpty { NewsCatalog.sources } }
    var activeSource by remember(safeSources) {
        val savedId = store.selectedSourceId()
        mutableStateOf(safeSources.firstOrNull { it.id == savedId } ?: safeSources.first())
    }
    var articles by remember { mutableStateOf<List<NewsArticle>>(emptyList()) }
    var articleIndex by remember { mutableIntStateOf(0) }
    var readingPageIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var detailLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var usingCache by remember { mutableStateOf(false) }
    var fetchedAtMs by remember { mutableStateOf<Long?>(null) }
    var isSpeaking by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    val articleDetails = remember { mutableStateMapOf<String, NewsArticleDetail>() }

    var scanState by remember {
        mutableStateOf(
            NewsScanState().syncCounts(newArticleCount = 0, newSourceCount = safeSources.size)
        )
    }

    val safeArticleIndex = articleIndex.coerceIn(0, (articles.size - 1).coerceAtLeast(0))
    val currentArticle = articles.getOrNull(safeArticleIndex)
    val nextArticle = if (articles.size > 1) articles[(safeArticleIndex + 1) % articles.size] else null

    val currentDetail = remember(currentArticle, articleDetails[currentArticle?.id]) {
        currentArticle?.let { article ->
            articleDetails[article.id] ?: NewsArticleExtractor.detailFromCachedArticle(article)
        }
    }
    val readingPages = remember(currentDetail) {
        currentDetail?.let { NewsArticlePaginator.paginate(it.blocks) }.orEmpty()
    }
    val safePageIndex = readingPageIndex.coerceIn(0, (readingPages.size - 1).coerceAtLeast(0))

    val effectiveDelayMs = effectiveNewsScanDelayMs(scanState.focusMode, scanIntervalMs)
    val activeCursor = scanState.activeCursor()
    val activeTargetIndex = scanState.activeTargetIndex()

    fun stopSpeaking() {
        if (isSpeaking) isSpeaking = false
        currentOnStopSpeak()
    }

    fun speakCurrentReadingPage() {
        val article = currentArticle ?: return
        val page = readingPages.getOrNull(safePageIndex)
        val spoken = buildString {
            if (safePageIndex == 0) {
                append(article.title)
                append("。")
            }
            val pageText = page?.spokenText.orEmpty()
            if (pageText.isNotBlank()) {
                append(pageText)
            } else if (safePageIndex > 0) {
                append(article.title)
            }
        }
        if (spoken.isBlank()) return
        isSpeaking = true
        currentOnSpeak(spoken) {
            isSpeaking = false
        }
    }

    fun ensureArticleDetailLoaded(article: NewsArticle) {
        if (articleDetails.containsKey(article.id)) {
            detailLoading = false
            return
        }
        if (article.fullContentLoaded) {
            articleDetails[article.id] = NewsArticleExtractor.detailFromCachedArticle(article)
            detailLoading = false
            return
        }
        detailJob?.cancel()
        detailLoading = true
        detailJob = scope.launch {
            try {
                val detail = currentDetailFetcher.fetchDetail(article)
                if (detail != null && detail.blocks.isNotEmpty()) {
                    articleDetails[article.id] = detail
                    val mergedImages = (detail.imageUrls + article.imageUrls).distinct()
                    val enrichedSummary = RssFeedParser.formatSummary(detail.plainText, article.title)
                    val updatedList = articles.map { item ->
                        if (item.id == article.id) {
                            item.copy(
                                summary = enrichedSummary,
                                fullText = detail.plainText.ifBlank { item.fullText },
                                imageUrls = mergedImages,
                                fullContentLoaded = true
                            )
                        } else {
                            item
                        }
                    }
                    articles = updatedList
                    fetchedAtMs?.let { ts ->
                        store.saveCachedFeed(CachedNewsFeed(activeSource.id, updatedList, ts))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep cached summary fallback seamlessly.
            } finally {
                detailLoading = false
            }
        }
    }

    fun loadSource(source: NewsSource, forceRefresh: Boolean) {
        fetchJob?.cancel()
        detailJob?.cancel()
        stopSpeaking()
        activeSource = source
        store.saveSelectedSourceId(source.id)
        errorMessage = null

        val cached = store.loadCachedFeed(source.id)
        val now = System.currentTimeMillis()
        if (cached != null && cached.articles.isNotEmpty()) {
            articles = cached.articles
            articleIndex = 0
            readingPageIndex = 0
            fetchedAtMs = cached.fetchedAtMs
            usingCache = true
            scanState = scanState.syncCounts(cached.articles.size, safeSources.size)
            val cacheFresh = !forceRefresh && (now - cached.fetchedAtMs) < NewsBriefingStore.CACHE_TTL_MS
            if (cacheFresh) {
                loading = false
                return
            }
        } else {
            articles = emptyList()
            articleIndex = 0
            readingPageIndex = 0
            fetchedAtMs = null
            usingCache = false
            scanState = scanState.syncCounts(0, safeSources.size)
        }

        loading = true
        fetchJob = scope.launch {
            try {
                val fresh = currentFetcher.fetch(source)
                val updatedAt = System.currentTimeMillis()
                store.saveCachedFeed(CachedNewsFeed(source.id, fresh, updatedAt))
                articles = fresh
                articleIndex = 0
                readingPageIndex = 0
                fetchedAtMs = updatedAt
                usingCache = false
                errorMessage = null
                loading = false
                scanState = scanState.syncCounts(fresh.size, safeSources.size)
                if (forceRefresh) {
                    currentOnToast("已更新「${source.name}」${fresh.size} 条新闻", true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                loading = false
                val msg = failure.message ?: "获取新闻失败"
                if (articles.isNotEmpty()) {
                    usingCache = true
                    errorMessage = null
                    if (forceRefresh) {
                        currentOnToast("网络暂不可用，继续显示缓存", false)
                    }
                } else {
                    errorMessage = msg
                    // Automatically open browse menu so the user can immediately switch source or refresh.
                    scanState = scanState.syncCounts(0, safeSources.size)
                        .enterBrowseMenu(NewsScanState.BROWSE_SOURCES)
                    if (forceRefresh) {
                        currentOnToast(msg, false)
                    }
                }
            }
        }
    }

    fun moveArticle(delta: Int) {
        if (articles.size <= 1) return
        stopSpeaking()
        articleIndex = (safeArticleIndex + delta + articles.size) % articles.size
        readingPageIndex = 0
    }

    fun openReadingForCurrent() {
        val article = articles.getOrNull(safeArticleIndex) ?: return
        stopSpeaking()
        readingPageIndex = 0
        ensureArticleDetailLoaded(article)
        scanState = scanState.enterReading()
        currentOnBoundary()
    }

    fun turnReadingPage(delta: Int) {
        if (readingPages.isEmpty()) return
        val next = safePageIndex + delta
        when {
            next < 0 -> currentOnToast("已是第一页", false)
            next >= readingPages.size -> {
                if (articles.size > 1) {
                    val nextIndex = (safeArticleIndex + 1) % articles.size
                    stopSpeaking()
                    articleIndex = nextIndex
                    readingPageIndex = 0
                    articles.getOrNull(nextIndex)?.let(::ensureArticleDetailLoaded)
                    currentOnToast("已进入下一篇", true)
                } else {
                    currentOnToast("已是最后一页，咬牙可打开菜单", false)
                }
            }
            else -> {
                stopSpeaking()
                readingPageIndex = next
            }
        }
    }

    fun selectSource(source: NewsSource) {
        val switching = source.id != activeSource.id
        scanState = scanState.enterBrowse()
        currentOnBoundary()
        if (switching) {
            loadSource(source, forceRefresh = false)
            currentOnToast("已切换到「${source.name}」", true)
        }
    }

    fun selectArticleFromDirectory(index: Int) {
        if (index !in articles.indices) return
        stopSpeaking()
        articleIndex = index
        readingPageIndex = 0
        ensureArticleDetailLoaded(articles[index])
        scanState = scanState.enterReading()
        currentOnBoundary()
    }

    fun returnFromSubpanel() {
        when (scanState.focusMode) {
            NewsFocusMode.BROWSE -> {
                stopSpeaking()
                currentOnBoundary()
                currentOnBack()
            }
            NewsFocusMode.BROWSE_MENU -> {
                if (articles.isEmpty()) {
                    stopSpeaking()
                    currentOnBoundary()
                    currentOnBack()
                } else {
                    scanState = scanState.enterBrowse()
                    currentOnBoundary()
                }
            }
            NewsFocusMode.SOURCES -> {
                scanState = if (articles.isEmpty()) {
                    scanState.enterBrowseMenu(NewsScanState.BROWSE_SOURCES)
                } else {
                    scanState.enterBrowse()
                }
                currentOnBoundary()
            }
            NewsFocusMode.ARTICLES -> {
                scanState = scanState.enterBrowse()
                currentOnBoundary()
            }
            NewsFocusMode.READING -> {
                stopSpeaking()
                scanState = scanState.enterBrowse()
                currentOnBoundary()
            }
            NewsFocusMode.READING_MENU -> {
                scanState = scanState.enterReading()
                currentOnBoundary()
            }
        }
    }

    fun executeBrowseMenu(target: Int) {
        when (target) {
            NewsScanState.BROWSE_READ_FULL -> openReadingForCurrent()
            NewsScanState.BROWSE_CONTINUE -> {
                scanState = scanState.enterBrowse()
                currentOnBoundary()
            }
            NewsScanState.BROWSE_SOURCES -> {
                stopSpeaking()
                val idx = safeSources.indexOfFirst { it.id == activeSource.id }.coerceAtLeast(0)
                scanState = scanState.enterSources(idx)
                currentOnBoundary()
            }
            NewsScanState.BROWSE_DIRECTORY -> {
                if (articles.isEmpty()) {
                    currentOnToast("当前暂无新闻目录", false)
                    return
                }
                stopSpeaking()
                scanState = scanState.enterArticles(safeArticleIndex)
                currentOnBoundary()
            }
            NewsScanState.BROWSE_REFRESH -> {
                scanState = if (articles.isNotEmpty()) scanState.enterBrowse() else scanState
                loadSource(activeSource, forceRefresh = true)
                currentOnBoundary()
            }
            NewsScanState.BROWSE_BACK -> {
                stopSpeaking()
                currentOnBoundary()
                currentOnBack()
            }
        }
    }

    fun executeReadingMenu(target: Int) {
        when (target) {
            NewsScanState.READING_CONTINUE -> {
                scanState = scanState.enterReading()
                currentOnBoundary()
            }
            NewsScanState.READING_SPEAK -> {
                if (isSpeaking) stopSpeaking() else speakCurrentReadingPage()
                scanState = scanState.enterReading()
                currentOnBoundary()
            }
            NewsScanState.READING_NEXT_ARTICLE -> {
                if (articles.size > 1) {
                    val nextIndex = (safeArticleIndex + 1) % articles.size
                    stopSpeaking()
                    articleIndex = nextIndex
                    readingPageIndex = 0
                    articles.getOrNull(nextIndex)?.let(::ensureArticleDetailLoaded)
                }
                scanState = scanState.enterReading()
                currentOnBoundary()
            }
            NewsScanState.READING_EXIT -> {
                stopSpeaking()
                scanState = scanState.enterBrowse()
                currentOnBoundary()
            }
        }
    }

    val hints = when (scanState.focusMode) {
        NewsFocusMode.BROWSE -> ActionHints(
            left = if (articles.size > 1) "上一条" else "打开菜单",
            bite = if (articles.isNotEmpty()) "阅读 / 菜单" else "打开菜单",
            right = if (articles.size > 1) "下一条" else "打开菜单"
        )
        NewsFocusMode.BROWSE_MENU -> {
            val label = BROWSE_MENU_DEFINITIONS.firstOrNull { it.first == activeTargetIndex }?.second.orEmpty()
            ActionHints.list(label, scanMode, activeCursor.direction)
        }
        NewsFocusMode.READING -> ActionHints(
            left = if (safePageIndex > 0) "上一页" else "已是首页",
            bite = "打开菜单",
            right = when {
                safePageIndex < readingPages.lastIndex -> "下一页"
                articles.size > 1 -> "下一篇"
                else -> "已到末页"
            }
        )
        NewsFocusMode.READING_MENU -> {
            val label = if (activeTargetIndex == NewsScanState.READING_SPEAK && isSpeaking) {
                "停止朗读"
            } else {
                READING_MENU_DEFINITIONS.firstOrNull { it.first == activeTargetIndex }?.second.orEmpty()
            }
            ActionHints.list(label, scanMode, activeCursor.direction)
        }
        NewsFocusMode.SOURCES -> {
            val label = if (activeTargetIndex == 0) "返回新闻"
            else safeSources.getOrNull(activeTargetIndex - 1)?.name.orEmpty()
            ActionHints.list(label, scanMode, activeCursor.direction)
        }
        NewsFocusMode.ARTICLES -> {
            val label = if (activeTargetIndex == 0) "返回新闻"
            else articles.getOrNull(activeTargetIndex - 1)?.title?.take(12).orEmpty()
            ActionHints.list(label, scanMode, activeCursor.direction)
        }
    }
    ReportActionHints(hints)

    BackHandler(enabled = !paused) {
        returnFromSubpanel()
    }

    LaunchedEffect(Unit) {
        loadSource(activeSource, forceRefresh = false)
    }

    // Automatically pre-fetch full article text + lead image for the currently displayed article.
    LaunchedEffect(currentArticle?.id) {
        val article = currentArticle ?: return@LaunchedEffect
        ensureArticleDetailLoaded(article)
    }

    LaunchedEffect(paused) {
        if (paused) stopSpeaking()
        scanState = scanState.resetForPauseBoundary()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val autoScanningActive = scanMode == ScanMode.AUTO &&
        !scanState.isDirectNavigationMode &&
        activeCursor.size > 1

    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(
        scanState.focusMode,
        activeCursor.index,
        activeCursor.ticks,
        activeCursor.direction,
        scanState.dwellRevision,
        effectiveDelayMs,
        autoScanningActive,
        paused,
        frozen,
        lifecycleOwner
    ) {
        when {
            frozen -> dwellProgress.snapTo(if (autoScanningActive) 0.5f else 0f)
            paused || !autoScanningActive -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(effectiveDelayMs.toInt(), easing = LinearEasing))
            }
        }
    }

    LaunchedEffect(
        autoScanningActive,
        effectiveDelayMs,
        scanState.focusMode,
        activeCursor.size,
        scanState.dwellRevision,
        paused,
        frozen,
        lifecycleOwner
    ) {
        if (!autoScanningActive || paused || frozen) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            scanState = scanState.clearActiveGrace()
            runScanTimer(
                interval = { effectiveDelayMs },
                onFailure = { android.util.Log.e("ScanCursor", "News tick failed", it) }
            ) {
                scanState = scanState.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
            }
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        when (scanState.focusMode) {
            NewsFocusMode.BROWSE -> when (signal) {
                ControlSignal.LEFT_LOOK -> {
                    if (articles.size > 1) moveArticle(-1)
                    else {
                        scanState = scanState.enterBrowseMenu()
                        currentOnBoundary()
                    }
                }
                ControlSignal.RIGHT_LOOK -> {
                    if (articles.size > 1) moveArticle(1)
                    else {
                        scanState = scanState.enterBrowseMenu()
                        currentOnBoundary()
                    }
                }
                ControlSignal.BITE -> {
                    scanState = scanState.enterBrowseMenu()
                    currentOnBoundary()
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }

            NewsFocusMode.READING -> when (signal) {
                ControlSignal.LEFT_LOOK -> turnReadingPage(-1)
                ControlSignal.RIGHT_LOOK -> turnReadingPage(1)
                ControlSignal.BITE -> {
                    scanState = scanState.enterReadingMenu()
                    currentOnBoundary()
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }

            NewsFocusMode.BROWSE_MENU,
            NewsFocusMode.SOURCES,
            NewsFocusMode.ARTICLES,
            NewsFocusMode.READING_MENU -> when (signal) {
                ControlSignal.LEFT_LOOK -> scanState = scanState.look(-1, scanMode)
                ControlSignal.RIGHT_LOOK -> scanState = scanState.look(1, scanMode)
                ControlSignal.BITE -> {
                    val grace = if (frozen) 0L else AppUiSettings.graceMs(context)
                    val selection = scanState.selectOnBite(
                        nowMs = SystemClock.elapsedRealtime(),
                        graceMs = grace,
                        mode = scanMode
                    ) ?: return@rememberUpdatedState
                    when (scanState.focusMode) {
                        NewsFocusMode.BROWSE_MENU -> executeBrowseMenu(selection.targetIndex)
                        NewsFocusMode.SOURCES -> {
                            if (selection.targetIndex == 0) {
                                returnFromSubpanel()
                            } else {
                                safeSources.getOrNull(selection.targetIndex - 1)?.let(::selectSource)
                            }
                        }
                        NewsFocusMode.ARTICLES -> {
                            if (selection.targetIndex == 0) {
                                returnFromSubpanel()
                            } else {
                                selectArticleFromDirectory(selection.targetIndex - 1)
                            }
                        }
                        NewsFocusMode.READING_MENU -> executeReadingMenu(selection.targetIndex)
                        NewsFocusMode.BROWSE, NewsFocusMode.READING -> Unit
                    }
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
        }
    }
    val tickHandler = rememberUpdatedState<() -> Unit> {
        scanState = scanState.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
    }
    val signalSink = remember { NewsSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        NewsInputBus.attach(signalSink, onTick = { tickHandler.value() })
        onDispose {
            NewsInputBus.detach(signalSink)
            fetchJob?.cancel()
            detailJob?.cancel()
            currentOnStopSpeak()
        }
    }

    val progressProvider: (() -> Float?)? =
        if (autoScanningActive) { { dwellProgress.value } } else null

    when (scanState.focusMode) {
        NewsFocusMode.BROWSE, NewsFocusMode.BROWSE_MENU -> NewsBrowseStage(
            source = activeSource,
            article = currentArticle,
            nextArticle = nextArticle,
            articleIndex = safeArticleIndex,
            articleCount = articles.size,
            loading = loading,
            detailLoading = detailLoading,
            errorMessage = errorMessage,
            usingCache = usingCache,
            fetchedAtMs = fetchedAtMs,
            menuOpen = scanState.focusMode == NewsFocusMode.BROWSE_MENU,
            enabledMenuTargets = scanState.enabledBrowseMenuTargets(),
            focusedMenuTarget = activeTargetIndex,
            scanDirection = if (scanMode == ScanMode.AUTO) activeCursor.direction else 0,
            progressProvider = progressProvider,
            onPrevArticle = { moveArticle(-1) },
            onNextArticle = { moveArticle(1) },
            onOpenMenu = {
                scanState = scanState.enterBrowseMenu()
                currentOnBoundary()
            },
            onOpenReading = ::openReadingForCurrent,
            onMenuClick = ::executeBrowseMenu
        )

        NewsFocusMode.READING, NewsFocusMode.READING_MENU -> NewsFullReadingStage(
            source = activeSource,
            article = currentArticle,
            page = readingPages.getOrNull(safePageIndex),
            pageIndex = safePageIndex,
            pageCount = readingPages.size.coerceAtLeast(1),
            hasNextArticle = articles.size > 1,
            detailLoading = detailLoading,
            isSpeaking = isSpeaking,
            menuOpen = scanState.focusMode == NewsFocusMode.READING_MENU,
            enabledMenuTargets = scanState.enabledReadingMenuTargets(),
            focusedMenuTarget = activeTargetIndex,
            scanDirection = if (scanMode == ScanMode.AUTO) activeCursor.direction else 0,
            progressProvider = progressProvider,
            onPrevPage = { turnReadingPage(-1) },
            onNextPage = { turnReadingPage(1) },
            onOpenMenu = {
                scanState = scanState.enterReadingMenu()
                currentOnBoundary()
            },
            onMenuClick = ::executeReadingMenu
        )

        NewsFocusMode.SOURCES -> NewsSourcePickerStage(
            sources = safeSources,
            activeSourceId = activeSource.id,
            focusedSlot = activeTargetIndex,
            scanDirection = if (scanMode == ScanMode.AUTO) activeCursor.direction else 0,
            progressProvider = progressProvider,
            onBack = ::returnFromSubpanel,
            onSelectSource = ::selectSource
        )

        NewsFocusMode.ARTICLES -> NewsArticleDirectoryStage(
            source = activeSource,
            articles = articles,
            currentArticleIndex = safeArticleIndex,
            focusedSlot = activeTargetIndex,
            scanMode = scanMode,
            directoryDelayMs = effectiveDelayMs,
            scanDirection = if (scanMode == ScanMode.AUTO) activeCursor.direction else 0,
            progressProvider = progressProvider,
            onBack = ::returnFromSubpanel,
            onSelectArticle = ::selectArticleFromDirectory
        )
    }
}

@Composable
private fun NewsBrowseStage(
    source: NewsSource,
    article: NewsArticle?,
    nextArticle: NewsArticle?,
    articleIndex: Int,
    articleCount: Int,
    loading: Boolean,
    detailLoading: Boolean,
    errorMessage: String?,
    usingCache: Boolean,
    fetchedAtMs: Long?,
    menuOpen: Boolean,
    enabledMenuTargets: List<Int>,
    focusedMenuTarget: Int,
    scanDirection: Int,
    progressProvider: (() -> Float?)?,
    onPrevArticle: () -> Unit,
    onNextArticle: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenReading: () -> Unit,
    onMenuClick: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val todayLabel = remember {
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
    }
    val statusText = remember(loading, usingCache, fetchedAtMs) {
        when {
            loading -> "正在更新…"
            usingCache && fetchedAtMs != null -> {
                val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(fetchedAtMs))
                "缓存 · $hm"
            }
            fetchedAtMs != null -> {
                val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(fetchedAtMs))
                "更新于 $hm"
            }
            else -> ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(c.focusTint, CircleShape)
                        .border(1.5.dp, c.focus, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = source.name,
                        color = c.focus,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Text(
                    text = "${source.provider} · ${source.category}",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "·  $todayLabel",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (statusText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(c.panel2, CircleShape)
                            .border(1.dp, c.line, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = c.muted,
                            fontFamily = BciType.chinese,
                            fontSize = 15.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }

            Text(
                text = if (articleCount > 0) "${articleIndex + 1} / $articleCount" else "— / —",
                color = c.text,
                fontFamily = BciType.latin,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        // 2. Main preview card (quiet, zero scanning distraction while browsing; click card to read)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(c.panel)
                .border(1.5.dp, c.line, RoundedCornerShape(22.dp))
                .clickable(enabled = article != null && !menuOpen) { onOpenReading() }
                .padding(horizontal = 32.dp, vertical = 22.dp)
        ) {
            when {
                article != null -> {
                    val leadImage = article.leadImageUrl
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(26.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${source.name}  ·  ${article.publishedAtText}",
                                        color = c.muted,
                                        fontFamily = BciType.chinese,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 18.sp,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                    if (article.imageUrls.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .background(c.panel2, CircleShape)
                                                .border(1.dp, c.line, CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "含 ${article.imageUrls.size} 张配图",
                                                color = c.muted,
                                                fontFamily = BciType.chinese,
                                                fontSize = 14.sp,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                    } else if (detailLoading) {
                                        Text(
                                            text = "正在加载正文…",
                                            color = c.muted,
                                            fontFamily = BciType.chinese,
                                            fontSize = 15.sp,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = article.title,
                                    color = c.text,
                                    fontFamily = BciType.chinese,
                                    fontWeight = FontWeight(900),
                                    fontSize = 33.sp,
                                    lineHeight = 44.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                                Spacer(Modifier.height(14.dp))
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(c.line)
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = article.summary,
                                    color = c.text.copy(alpha = 0.90f),
                                    fontFamily = BciType.chinese,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 24.sp,
                                    lineHeight = 38.sp,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }

                            if (!leadImage.isNullOrBlank()) {
                                NewsRemoteImage(
                                    url = leadImage,
                                    refererUrl = article.link,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(360.dp)
                                        .fillMaxHeight()
                                )
                            }
                        }

                        if (nextArticle != null) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(c.panel2, RoundedCornerShape(12.dp))
                                    .border(1.dp, c.line, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "下一条",
                                    color = c.focus,
                                    fontFamily = BciType.chinese,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                                Text(
                                    text = nextArticle.title,
                                    color = c.muted,
                                    fontFamily = BciType.chinese,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }

                loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        CircularProgressIndicator(
                            color = c.focus,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "正在获取「${source.name}」最新新闻…",
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "暂时无法获取「${source.name}」",
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        )
                        Text(
                            text = errorMessage ?: "请检查网络连接，或在下方选择“换新闻源”或“刷新”。",
                            color = c.muted,
                            fontFamily = BciType.chinese,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 3. Bottom area: either quiet direct-control strip (left=prev, bite=menu, right=next) or scan menu
        if (menuOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BROWSE_MENU_DEFINITIONS.forEach { (target, label) ->
                    val enabled = target in enabledMenuTargets
                    val focused = enabled && focusedMenuTarget == target
                    val isNav = target == NewsScanState.BROWSE_BACK
                    val isPrimary = target == NewsScanState.BROWSE_READ_FULL

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = scanDirection,
                                picked = false,
                                progress = progressProvider,
                                navigation = isNav,
                                enabled = enabled,
                                cornerRadius = 16.dp
                            )
                            .clickable(enabled = enabled) { onMenuClick(target) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = when {
                                focused -> c.text
                                isPrimary && enabled -> c.focus
                                isNav -> c.muted
                                else -> c.text
                            },
                            fontFamily = BciType.chinese,
                            fontWeight = if (focused || isPrimary) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        } else {
            val canCycle = articleCount > 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = canCycle) { onPrevArticle() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = "◀ 上一条",
                        color = if (canCycle) c.text else c.dim,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable { onOpenMenu() }
                        .padding(horizontal = 26.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = "左看上一条 · 咬牙打开菜单（阅读全文 / 换源 / 目录） · 右看下一条",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = canCycle) { onNextArticle() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = "下一条 ▶",
                        color = if (canCycle) c.text else c.dim,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsFullReadingStage(
    source: NewsSource,
    article: NewsArticle?,
    page: NewsReadingPage?,
    pageIndex: Int,
    pageCount: Int,
    hasNextArticle: Boolean,
    detailLoading: Boolean,
    isSpeaking: Boolean,
    menuOpen: Boolean,
    enabledMenuTargets: List<Int>,
    focusedMenuTarget: Int,
    scanDirection: Int,
    progressProvider: (() -> Float?)?,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenMenu: () -> Unit,
    onMenuClick: (Int) -> Unit
) {
    val c = LocalBciColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Reading header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(c.focusTint, CircleShape)
                        .border(1.5.dp, c.focus, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = source.name,
                        color = c.focus,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Text(
                    text = article?.title.orEmpty(),
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            Spacer(Modifier.width(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSpeaking) {
                    Row(
                        modifier = Modifier
                            .background(c.okTint, CircleShape)
                            .border(1.5.dp, c.ok, CircleShape)
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.size(9.dp).background(c.ok, CircleShape))
                        Text(
                            text = "正在朗读",
                            color = c.ok,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
                Text(
                    text = "第 ${pageIndex + 1} / $pageCount 页",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        // 2. Full-article paginated reading surface (fills full card area; side-by-side when image + text)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(c.panel)
                .border(1.5.dp, c.line, RoundedCornerShape(22.dp))
                .padding(horizontal = 36.dp, vertical = 22.dp)
        ) {
            if (page == null && detailLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = c.focus,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "正在加载完整正文与配图…",
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp
                    )
                }
            } else if (page != null) {
                val imageBlocks = page.blocks.filterIsInstance<NewsContentBlock.Image>()
                val paragraphBlocks = page.blocks.filterIsInstance<NewsContentBlock.Paragraph>()

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (pageIndex == 0 && article != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = article.title,
                                color = c.text,
                                fontFamily = BciType.chinese,
                                fontWeight = FontWeight(900),
                                fontSize = 30.sp,
                                lineHeight = 40.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = "${source.provider}  ·  ${article.publishedAtText}" +
                                    if (detailLoading) "  ·  正在同步完整图文…" else "",
                                color = c.muted,
                                fontFamily = BciType.chinese,
                                fontSize = 17.sp,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }

                    when {
                        // Image-only page: give the image the entire remaining card height & width.
                        imageBlocks.isNotEmpty() && paragraphBlocks.isEmpty() -> {
                            val img = imageBlocks.first()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NewsRemoteImage(
                                    url = img.url,
                                    refererUrl = article?.link,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                                if (img.caption.isNotBlank()) {
                                    Text(
                                        text = img.caption,
                                        color = c.muted,
                                        fontFamily = BciType.chinese,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Image + text page: landscape side-by-side so both text & image use full height.
                        imageBlocks.isNotEmpty() -> {
                            val img = imageBlocks.first()
                            val textScroll = rememberScrollState()
                            LaunchedEffect(article?.id, pageIndex) { textScroll.scrollTo(0) }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(28.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(0.54f)
                                        .fillMaxHeight()
                                        .verticalScroll(textScroll),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    paragraphBlocks.forEach { block ->
                                        Text(
                                            text = block.text,
                                            color = c.text.copy(alpha = 0.94f),
                                            fontFamily = BciType.chinese,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 25.sp,
                                            lineHeight = 39.sp,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(0.46f)
                                        .fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NewsRemoteImage(
                                        url = img.url,
                                        refererUrl = article?.link,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    )
                                    if (img.caption.isNotBlank()) {
                                        Text(
                                            text = img.caption,
                                            color = c.muted,
                                            fontFamily = BciType.chinese,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Text-only page: full-width calm reading flow.
                        else -> {
                            val textScroll = rememberScrollState()
                            LaunchedEffect(article?.id, pageIndex) { textScroll.scrollTo(0) }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(textScroll),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                paragraphBlocks.forEach { block ->
                                    Text(
                                        text = block.text,
                                        color = c.text.copy(alpha = 0.94f),
                                        fontFamily = BciType.chinese,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 25.sp,
                                        lineHeight = 39.sp,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Bottom area: either compact reading menu (when bite pressed) or page navigation strip
        if (menuOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                READING_MENU_DEFINITIONS.forEach { (target, defaultLabel) ->
                    val enabled = target in enabledMenuTargets
                    val focused = enabled && focusedMenuTarget == target
                    val isSpeakActive = target == NewsScanState.READING_SPEAK && isSpeaking
                    val label = if (isSpeakActive) "停止朗读" else defaultLabel
                    val isNav = target == NewsScanState.READING_EXIT

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = scanDirection,
                                picked = isSpeakActive,
                                progress = progressProvider,
                                navigation = isNav,
                                enabled = enabled,
                                cornerRadius = 16.dp
                            )
                            .clickable(enabled = enabled) { onMenuClick(target) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = when {
                                isSpeakActive -> c.ok
                                focused -> c.text
                                isNav -> c.muted
                                else -> c.text
                            },
                            fontFamily = BciType.chinese,
                            fontWeight = if (focused || isSpeakActive) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 23.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        } else {
            val isLastPage = pageIndex >= pageCount - 1
            val rightEnabled = !isLastPage || hasNextArticle
            val rightText = when {
                !isLastPage -> "下一页 ▶"
                hasNextArticle -> "下一篇 ▶"
                else -> "已到最后一页"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = pageIndex > 0) { onPrevPage() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (pageIndex > 0) "◀ 上一页" else "已是第一页",
                        color = if (pageIndex > 0) c.text else c.dim,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable { onOpenMenu() }
                        .padding(horizontal = 26.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (isLastPage && hasNextArticle) {
                            "左看上一页 · 咬牙打开菜单 · 右看继续下一篇"
                        } else {
                            "左看上一页 · 咬牙打开菜单 · 右看下一页"
                        },
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.panel2)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = rightEnabled) { onNextPage() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = rightText,
                        color = if (rightEnabled) c.text else c.dim,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsRemoteImage(
    url: String,
    refererUrl: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(NewsImageLoader.getCached(url)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            failed = false
            val loaded = NewsImageLoader.load(url, refererUrl)
            if (loaded != null) {
                bitmap = loaded
            } else {
                failed = true
            }
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(c.panel2)
            .border(1.dp, c.line, shape),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        when {
            img != null -> Image(
                bitmap = img,
                contentDescription = "新闻配图",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
            failed -> Text(
                text = "图片加载失败",
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 16.sp
            )
            else -> CircularProgressIndicator(
                color = c.focus,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun NewsSourcePickerStage(
    sources: List<NewsSource>,
    activeSourceId: String,
    focusedSlot: Int,
    scanDirection: Int,
    progressProvider: (() -> Float?)?,
    onBack: () -> Unit,
    onSelectSource: (NewsSource) -> Unit
) {
    val c = LocalBciColors.current
    val totalCells = sources.size + 1
    val columns = 3
    val rowCount = (totalCells + columns - 1) / columns

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择新闻源",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 30.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "共 ${sources.size} 个公开 RSS 频道 · 切换后自动记住偏好",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            Text(
                text = if (focusedSlot == 0) "返回" else "$focusedSlot / ${sources.size}",
                color = c.muted,
                fontFamily = BciType.latin,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for (r in 0 until rowCount) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    for (col in 0 until columns) {
                        val slot = r * columns + col
                        if (slot >= totalCells) {
                            Spacer(Modifier.weight(1f))
                        } else if (slot == 0) {
                            val focused = focusedSlot == 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .scanItem(
                                        focused = focused,
                                        direction = scanDirection,
                                        picked = false,
                                        progress = progressProvider,
                                        navigation = true,
                                        cornerRadius = 18.dp
                                    )
                                    .clickable { onBack() }
                                    .padding(22.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "← 返回新闻",
                                        color = if (focused) c.text else c.muted,
                                        fontFamily = BciType.chinese,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 26.sp,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                    Text(
                                        text = "继续浏览当前频道",
                                        color = c.muted,
                                        fontFamily = BciType.chinese,
                                        fontSize = 17.sp,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                            }
                        } else {
                            val source = sources[slot - 1]
                            val focused = focusedSlot == slot
                            val isActive = source.id == activeSourceId
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .scanItem(
                                        focused = focused,
                                        direction = scanDirection,
                                        picked = isActive,
                                        progress = progressProvider,
                                        cornerRadius = 18.dp
                                    )
                                    .clickable { onSelectSource(source) }
                                    .padding(horizontal = 24.dp, vertical = 20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = source.name,
                                            color = c.text,
                                            fontFamily = BciType.chinese,
                                            fontWeight = FontWeight(900),
                                            fontSize = 28.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                        if (isActive) {
                                            Box(
                                                modifier = Modifier
                                                    .background(c.okTint, CircleShape)
                                                    .border(1.dp, c.ok, CircleShape)
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "当前",
                                                    color = c.ok,
                                                    fontFamily = BciType.chinese,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "${source.category} · ${source.provider}",
                                        color = c.muted,
                                        fontFamily = BciType.chinese,
                                        fontSize = 18.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsArticleDirectoryStage(
    source: NewsSource,
    articles: List<NewsArticle>,
    currentArticleIndex: Int,
    focusedSlot: Int,
    scanMode: ScanMode,
    directoryDelayMs: Long,
    scanDirection: Int,
    progressProvider: (() -> Float?)?,
    onBack: () -> Unit,
    onSelectArticle: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(focusedSlot) {
        val target = focusedSlot.coerceIn(0, articles.size)
        listState.animateScrollToItem((target - 1).coerceAtLeast(0))
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${source.name} · 新闻目录",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 30.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "共 ${articles.size} 条 · 咬牙进入全文阅读",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (scanMode == ScanMode.AUTO) {
                    Box(
                        modifier = Modifier
                            .background(c.panel2, CircleShape)
                            .border(1.dp, c.line, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = String.format(Locale.ROOT, "慢速浏览 · %.1fs/条", directoryDelayMs / 1000.0),
                            color = c.muted,
                            fontFamily = BciType.chinese,
                            fontSize = 15.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
            Text(
                text = if (focusedSlot == 0) "返回" else "$focusedSlot / ${articles.size}",
                color = c.muted,
                fontFamily = BciType.latin,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "directory-back") {
                val focused = focusedSlot == 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .scanItem(
                            focused = focused,
                            direction = scanDirection,
                            picked = false,
                            progress = progressProvider,
                            navigation = true,
                            grow = false,
                            cornerRadius = 16.dp
                        )
                        .clickable { onBack() }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "← 返回新闻",
                        color = if (focused) c.text else c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            itemsIndexed(articles, key = { idx, item -> "${idx}_${item.id}" }) { index, article ->
                val slot = index + 1
                val focused = focusedSlot == slot
                val isCurrent = index == currentArticleIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp)
                        .scanItem(
                            focused = focused,
                            direction = scanDirection,
                            picked = isCurrent,
                            progress = progressProvider,
                            grow = false,
                            cornerRadius = 16.dp
                        )
                        .clickable { onSelectArticle(index) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = String.format(Locale.ROOT, "%02d", index + 1),
                        color = if (isCurrent) c.ok else if (focused) c.focus else c.muted,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier.width(38.dp)
                    )
                    Text(
                        text = article.title,
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = if (focused || isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 23.sp,
                        lineHeight = 31.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = article.publishedAtText,
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 17.sp,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}
