package com.example.input_ds.ui.assistant

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.assistant.AssistantCareStore
import com.example.input_ds.assistant.AssistantCatalogItem
import com.example.input_ds.assistant.AssistantConnection
import com.example.input_ds.assistant.AssistantHomePreview
import com.example.input_ds.assistant.AssistantResult
import com.example.input_ds.assistant.AssistantService
import com.example.input_ds.assistant.AssistantServiceException
import com.example.input_ds.assistant.HomeAction
import com.example.input_ds.assistant.HomePreviewRow
import com.example.input_ds.assistant.SimulatedHome
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.music.MusicLibrary
import com.example.input_ds.ui.communication.ComposerScreen
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.confirmHints
import com.example.input_ds.ui.design.inputHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import com.example.input_ds.ui.tv.TV_CHANNELS
import com.example.input_ds.viewmodel.InputMethodViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Serializable

private const val MAX_INPUT_LENGTH = 240
private const val MAX_CHAT_ENTRIES = 50
private const val WELCOME_MESSAGE =
    "你好，我是生活助手。可以问我问题、点播音乐和电视，或控制房间设备。"

internal val AssistantDwellProgressReaderKey =
    SemanticsPropertyKey<() -> Float?>("AssistantDwellProgressReader")

private enum class AssistantAction(val label: String) {
    INPUT("输入"),
    CLEAR("清空草稿"),
    SEND("发送"),
    EXIT("退出");

    fun labelFor(draft: String): String =
        if (this == INPUT && draft.isNotEmpty()) "继续输入" else label

    fun isAvailable(draft: String, busy: Boolean): Boolean = when (this) {
        INPUT -> !busy
        CLEAR -> !busy && draft.isNotEmpty()
        SEND -> !busy && draft.isNotBlank()
        EXIT -> !busy
    }
}

private data class AssistantEntry(val fromUser: Boolean, val text: String)
private data class HomeConfirmation(val prompt: String, val options: List<HomeAction>) : Serializable

private val AssistantEntriesSaver = listSaver<List<AssistantEntry>, String>(
    save = { entries -> entries.flatMap { listOf(if (it.fromUser) "u" else "a", it.text) } },
    restore = { values -> values.chunked(2).map { AssistantEntry(it[0] == "u", it[1]) } }
)

internal object AssistantInputBus {
    private var handler: ((ControlSignal) -> Unit)? = null
    private var ticker: (() -> Unit)? = null
    private var homeResetter: (() -> Unit)? = null
    private var focusResetter: (() -> Unit)? = null

    fun attach(
        onSignal: (ControlSignal) -> Unit,
        onTick: () -> Unit = {},
        onResetHome: () -> Unit = {},
        onResetFocus: () -> Unit = {}
    ) {
        handler = onSignal
        ticker = onTick
        homeResetter = onResetHome
        focusResetter = onResetFocus
    }

    fun detach(onSignal: (ControlSignal) -> Unit) {
        if (handler === onSignal) {
            handler = null
            ticker = null
            homeResetter = null
            focusResetter = null
        }
    }

    fun dispatch(signal: ControlSignal) {
        handler?.invoke(signal)
    }

    fun debugTick() {
        ticker?.invoke()
    }

    fun resetHome() {
        homeResetter?.invoke()
    }

    fun resetFocusToInput() {
        focusResetter?.invoke()
    }
}

@Composable
internal fun AssistantScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode = ScanMode.AUTO,
    paused: Boolean = false,
    frozen: Boolean = false,
    fakeAssistant: Boolean = false,
    initialEditing: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onEditingChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onOpenMedia: (AppDestination, String) -> Unit
) {
    val context = LocalContext.current.applicationContext as Application
    val input: InputMethodViewModel = viewModel(key = "assistant_input") {
        InputMethodViewModel(context, remotePredictionsEnabled = false)
    }
    val inputState by input.state.collectAsState()
    val scope = rememberCoroutineScope()
    val library = remember(context) { MusicLibrary(context) }
    val allowHttp = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val channels = remember {
        TV_CHANNELS.filterNot { it.temporarilyUnavailable }
            .map { AssistantCatalogItem(it.id, it.name) }
    }

    val sharedConfig by AssistantCareStore.config.collectAsState()
    val sharedDraft by AssistantCareStore.debugDraft.collectAsState()
    LaunchedEffect(context) { AssistantCareStore.load(context) }

    var editing by remember { mutableStateOf(initialEditing) }
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sharedDraft) {
        if (sharedDraft != draft) draft = sharedDraft
    }
    LaunchedEffect(draft) {
        if (draft != sharedDraft) AssistantCareStore.setDebugDraft(draft)
    }

    var entries by rememberSaveable(stateSaver = AssistantEntriesSaver) {
        mutableStateOf(listOf(AssistantEntry(fromUser = false, text = WELCOME_MESSAGE)))
    }
    var pending by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableLongStateOf(0L) }
    var activeRequestJob by remember { mutableStateOf<Job?>(null) }
    var home by rememberSaveable { mutableStateOf(SimulatedHome()) }
    var confirmation by rememberSaveable { mutableStateOf<HomeConfirmation?>(null) }
    var homeTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var flashKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dwellRevision by remember { mutableIntStateOf(0) }

    val liveBar = !pending && confirmation == null
    val availableActions = remember(draft, liveBar) {
        if (liveBar) AssistantAction.entries.filter { it.isAvailable(draft, busy = false) }
        else emptyList()
    }
    var barCursor by remember { mutableStateOf(ScanCursor(size = 2, index = 0, direction = 1)) }
    var confirmCursor by remember { mutableStateOf(ScanCursor(size = 2, index = 0, direction = 1)) }

    LaunchedEffect(availableActions.size) {
        if (availableActions.isNotEmpty() && barCursor.size != availableActions.size) {
            barCursor = ScanCursor(
                size = availableActions.size,
                index = barCursor.index.coerceIn(0, availableActions.lastIndex),
                direction = 1
            )
            dwellRevision++
        }
    }
    LaunchedEffect(confirmation) {
        val req = confirmation
        if (req != null && confirmCursor.size != req.options.size + 1) {
            confirmCursor = ScanCursor(size = req.options.size + 1, index = 0, direction = 1)
        }
    }

    fun appendEntry(fromUser: Boolean, text: String) {
        entries = (entries + AssistantEntry(fromUser, text)).takeLast(MAX_CHAT_ENTRIES)
    }

    fun focusBarAction(target: AssistantAction, currentDraft: String = draft) {
        val actions = AssistantAction.entries.filter { it.isAvailable(currentDraft, busy = false) }
        val idx = actions.indexOf(target).coerceAtLeast(0)
        barCursor = ScanCursor(size = actions.size.coerceAtLeast(1), index = idx, direction = 1)
        dwellRevision++
    }

    fun submit(explicitText: String? = null) {
        val prompt = (explicitText ?: draft).trim()
        if (prompt.isEmpty()) {
            if (!frozen) onToast("请先输入内容", false)
            return
        }
        if (prompt.length > MAX_INPUT_LENGTH) {
            val preview = prompt.take(MAX_INPUT_LENGTH).trimEnd { it.isHighSurrogate() }
            appendEntry(true, "$preview…")
            appendEntry(false, "请把输入缩短到 ${MAX_INPUT_LENGTH} 字以内")
            return
        }
        activeRequestJob?.cancel()
        activeRequestJob = null
        val reqId = ++requestGeneration
        appendEntry(true, prompt)
        draft = ""
        AssistantCareStore.setDebugDraft("")

        if (fakeAssistant) {
            val homeNow = home.expireTimer(System.currentTimeMillis())
            val fakeCatalog = listOf(
                AssistantCatalogItem("track_1", "演员 · 薛之谦"),
                AssistantCatalogItem("track_2", "绅士 · 薛之谦")
            )
            val reply = AssistantHomePreview.fakeReply(prompt, homeNow, channels, fakeCatalog)
            when (reply) {
                is AssistantResult.ConfirmHome -> {
                    confirmation = HomeConfirmation(prompt, reply.options)
                    confirmCursor = ScanCursor(size = reply.options.size + 1, index = 0, direction = 1)
                    dwellRevision++
                }
                is AssistantResult.Message -> {
                    appendEntry(false, reply.text)
                    if (reply.clarify) {
                        draft = prompt
                        AssistantCareStore.setDebugDraft(prompt)
                    }
                    focusBarAction(AssistantAction.INPUT, if (reply.clarify) prompt else "")
                }
                is AssistantResult.PlayTv -> {
                    val ch = channels.firstOrNull { it.id == reply.channelId }
                    appendEntry(false, "好的，为你打开 ${ch?.name ?: "电视"}。")
                    focusBarAction(AssistantAction.INPUT, "")
                    pending = true
                    activeRequestJob = scope.launch {
                        try {
                            if (!frozen) delay(1_100L)
                            if (requestGeneration == reqId && !editing) {
                                onOpenMedia(AppDestination.TV, reply.channelId)
                            }
                        } finally {
                            if (requestGeneration == reqId) pending = false
                        }
                    }
                }
                is AssistantResult.PlayMusic -> {
                    appendEntry(false, "好的，打开音乐，播放《演员》。")
                    focusBarAction(AssistantAction.INPUT, "")
                    pending = true
                    activeRequestJob = scope.launch {
                        try {
                            if (!frozen) delay(1_100L)
                            val tracks = library.initializeAndLoadTracks()
                            val targetId = tracks.firstOrNull { it.title.contains("演员") }?.id
                                ?: tracks.firstOrNull()?.id
                                ?: reply.trackId
                            if (requestGeneration == reqId && !editing) {
                                onOpenMedia(AppDestination.MUSIC, targetId)
                            }
                        } finally {
                            if (requestGeneration == reqId) pending = false
                        }
                    }
                }
            }
            return
        }

        val connection = AssistantConnection.parse(sharedConfig.address, sharedConfig.accessCode, allowHttp)
        if (connection == null) {
            appendEntry(false, "请照护者在照护面板「生活助手服务」中完成连接配置")
            focusBarAction(AssistantAction.INPUT, "")
            return
        }

        pending = true
        activeRequestJob = scope.launch {
            try {
                val tracks = library.initializeAndLoadTracks().take(100)
                val catalog = tracks.mapIndexed { index, track ->
                    AssistantCatalogItem("track_${index + 1}", "${track.title} · ${track.artist}".take(180))
                }
                val reply = AssistantService.submit(connection, prompt, catalog, channels, home)
                if (requestGeneration != reqId) return@launch
                var media: Pair<AppDestination, String>? = null
                val responseText = when (reply) {
                    is AssistantResult.Message -> reply.text
                    is AssistantResult.PlayMusic -> {
                        val track = tracks[catalog.indexOfFirst { it.id == reply.trackId }]
                        media = AppDestination.MUSIC to track.id
                        "已打开音乐页 · ${track.title}"
                    }
                    is AssistantResult.PlayTv -> {
                        val channel = channels.first { it.id == reply.channelId }
                        media = AppDestination.TV to channel.id
                        "已打开电视页 · ${channel.name}"
                    }
                    is AssistantResult.ConfirmHome -> {
                        confirmation = HomeConfirmation(prompt, reply.options)
                        confirmCursor = ScanCursor(size = reply.options.size + 1, index = 0, direction = 1)
                        dwellRevision++
                        return@launch
                    }
                }
                appendEntry(false, responseText)
                val keptDraft = if (reply is AssistantResult.Message && reply.clarify) prompt else ""
                if (keptDraft.isNotEmpty()) {
                    draft = keptDraft
                    AssistantCareStore.setDebugDraft(keptDraft)
                }
                focusBarAction(AssistantAction.INPUT, keptDraft)
                media?.let { (destination, id) ->
                    delay(1_100L)
                    if (requestGeneration == reqId && !editing) {
                        onOpenMedia(destination, id)
                    }
                }
            } catch (error: AssistantServiceException) {
                if (requestGeneration == reqId) {
                    appendEntry(false, error.message ?: "生活助手暂时无法回复，请稍后重试")
                    focusBarAction(AssistantAction.INPUT, "")
                }
            } catch (_: IOException) {
                if (requestGeneration == reqId) {
                    appendEntry(false, "未能连接电脑服务，请检查服务地址和调试连接")
                    focusBarAction(AssistantAction.INPUT, "")
                }
            } finally {
                if (requestGeneration == reqId) pending = false
            }
        }
    }

    fun finishInput(submitNow: Boolean = false) {
        val text = input.state.value.outputText
        draft = text
        AssistantCareStore.setDebugDraft(text)
        input.deactivate()
        editing = false
        onEditingChange(false)
        if (submitNow) {
            if (text.trim().isEmpty()) {
                if (!frozen) onToast("还没有要发的内容", false)
                focusBarAction(AssistantAction.INPUT, text)
            } else {
                submit(text)
            }
        } else {
            focusBarAction(
                if (text.isNotBlank()) AssistantAction.SEND else AssistantAction.INPUT,
                text
            )
        }
    }

    fun chooseBarAction(action: AssistantAction) {
        if (!liveBar || !action.isAvailable(draft, busy = false)) return
        when (action) {
            AssistantAction.INPUT -> {
                input.setOnToast(onToast)
                input.openMode(
                    mode = InputMode.Assistant,
                    initialDraft = draft,
                    onSend = { finishInput(submitNow = true) },
                    onBack = { finishInput(submitNow = false) }
                )
                editing = true
                onEditingChange(true)
            }
            AssistantAction.CLEAR -> {
                draft = ""
                AssistantCareStore.setDebugDraft("")
                if (!frozen) onToast("已清空草稿", true)
                focusBarAction(AssistantAction.INPUT, "")
            }
            AssistantAction.SEND -> submit()
            AssistantAction.EXIT -> onBack()
        }
    }

    /** Executes cancel-first home confirmation: index 0 = 取消, index 1..n = options[index - 1]. */
    fun resolveConfirm(index: Int) {
        val request = confirmation ?: return
        if (index !in 0..request.options.size) return
        val response = if (index == 0) {
            "已取消操作"
        } else {
            val action = request.options[index - 1]
            val now = System.currentTimeMillis()
            val current = home.expireTimer(now)
            val updated = current.apply(action, now)
            if (updated == null) {
                home = current
                "设备状态已变化，请重新发送指令"
            } else {
                val changed = AssistantHomePreview.changedKeys(current, updated, now, frozen)
                home = updated
                homeTime = now
                if (changed.isNotEmpty()) {
                    flashKeys = changed
                    scope.launch {
                        delay(1_300L)
                        flashKeys = emptySet()
                    }
                }
                "已执行「${action.describe(current)}」"
            }
        }
        appendEntry(false, response)
        confirmation = null
        focusBarAction(AssistantAction.INPUT, draft)
    }

    fun advanceConfirmTick() {
        if (confirmation == null) return
        val next = confirmCursor.advance(SystemClock.elapsedRealtime(), scanMode)
        if (next.timedOut(confirmation = true)) {
            if (!frozen) onToast("已自动取消", false)
            resolveConfirm(0)
        } else {
            confirmCursor = next
        }
    }

    fun advanceBarTick() {
        if (!liveBar || availableActions.isEmpty()) return
        val synced = if (barCursor.size != availableActions.size) {
            ScanCursor(size = availableActions.size, index = barCursor.index.coerceIn(0, availableActions.lastIndex), direction = barCursor.direction)
        } else barCursor
        barCursor = synced.advance(SystemClock.elapsedRealtime(), scanMode)
    }

    val currentSignalHandler by rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        val now = SystemClock.elapsedRealtime()
        val grace = if (frozen) 0L else AppUiSettings.graceMs(context)
        when {
            editing -> input.handleSignal(signal)
            confirmation != null -> {
                val request = confirmation ?: return@rememberUpdatedState
                val count = request.options.size + 1
                val isTwo = count == 2
                when (signal) {
                    ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                        val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                        confirmCursor = confirmCursor.look(step, scanMode, hold = isTwo)
                        dwellRevision++
                    }
                    ControlSignal.BITE -> {
                        val chosen = confirmCursor.selected(now, grace, scanMode)
                        resolveConfirm(chosen)
                    }
                    else -> Unit
                }
            }
            pending -> Unit
            else -> when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    barCursor = barCursor.look(step, scanMode)
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val chosen = barCursor.selected(now, grace, scanMode)
                    availableActions.getOrNull(chosen)?.let(::chooseBarAction)
                }
                else -> Unit
            }
        }
    }

    val currentTickHandler by rememberUpdatedState<() -> Unit> {
        when {
            editing -> input.debugTick()
            confirmation != null -> advanceConfirmTick()
            liveBar -> advanceBarTick()
        }
    }

    val currentHomeResetter by rememberUpdatedState<() -> Unit> {
        activeRequestJob?.cancel()
        activeRequestJob = null
        requestGeneration++
        home = SimulatedHome()
        homeTime = System.currentTimeMillis()
        flashKeys = emptySet()
        confirmation = null
        pending = false
        draft = ""
        AssistantCareStore.setDebugDraft("")
        entries = listOf(AssistantEntry(fromUser = false, text = WELCOME_MESSAGE))
        if (editing) {
            input.deactivate()
            editing = false
            onEditingChange(false)
        }
        focusBarAction(AssistantAction.INPUT, "")
    }

    val currentFocusResetter by rememberUpdatedState<() -> Unit> {
        if (!editing && confirmation == null && !pending) {
            focusBarAction(AssistantAction.INPUT, draft)
        }
    }

    LaunchedEffect(Unit) {
        if (editing) {
            input.setOnToast(onToast)
            input.resumeOrOpenMode(
                mode = InputMode.Assistant,
                initialDraft = draft,
                onSend = { finishInput(submitNow = true) },
                onBack = { finishInput(submitNow = false) }
            )
            onEditingChange(true)
        }
    }

    DisposableEffect(Unit) {
        val handler: (ControlSignal) -> Unit = { currentSignalHandler(it) }
        AssistantInputBus.attach(
            onSignal = handler,
            onTick = { currentTickHandler() },
            onResetHome = { currentHomeResetter() },
            onResetFocus = { currentFocusResetter() }
        )
        onDispose {
            activeRequestJob?.cancel()
            activeRequestJob = null
            requestGeneration++
            AssistantInputBus.detach(handler)
            if (editing) {
                input.suspendForDetour()
            } else {
                input.deactivate()
            }
            onEditingChange(false)
        }
    }

    LaunchedEffect(scanMode) { input.setScanMode(scanMode) }
    LifecycleResumeEffect(input, paused) {
        input.setPaused(paused)
        onPauseOrDispose { input.setPaused(true) }
    }
    LaunchedEffect(frozen) { input.setFrozen(frozen) }
    LaunchedEffect(scanIntervalMs) { input.setScanInterval(scanIntervalMs) }

    // Auto-scan timer for confirmation bubble and bottom bar, paused whenever activity leaves RESUMED.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(editing, confirmation != null, pending, paused, frozen, scanMode, dwellRevision, scanIntervalMs, lifecycleOwner) {
        if (!editing && !paused && !frozen && scanMode == ScanMode.AUTO) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (confirmation != null) {
                    confirmCursor = confirmCursor.copy(previousIndex = -1)
                    runScanTimer(
                        interval = { confirmCursor.dwell(scanIntervalMs, confirmation = true) },
                        onFailure = { android.util.Log.e("ScanCursor", "Assistant confirm tick failed", it) }
                    ) {
                        advanceConfirmTick()
                    }
                } else if (!pending) {
                    barCursor = barCursor.copy(previousIndex = -1)
                    runScanTimer(
                        interval = { scanIntervalMs },
                        onFailure = { android.util.Log.e("ScanCursor", "Assistant bar tick failed", it) }
                    ) {
                        advanceBarTick()
                    }
                }
            }
        }
    }

    // Expire AC off-timer in real time.
    LaunchedEffect(home.airConditioner.offAtMillis, frozen) {
        if (frozen) return@LaunchedEffect
        val deadline = home.airConditioner.offAtMillis ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            homeTime = now
            val remaining = deadline - now
            if (remaining <= 0) {
                home = home.expireTimer(now)
                appendEntry(false, "定时到了，空调已关闭")
                break
            }
            delay(1_000L)
        }
    }

    val homeStatus = if (frozen) home else home.expireTimer(homeTime)
    val confirmLabels = confirmation?.let { AssistantHomePreview.confirmationLabels(it.options, homeStatus) }.orEmpty()

    ReportActionHints(
        when {
            editing -> inputHints(inputState)
            confirmation != null -> {
                val label = confirmLabels.getOrElse(confirmCursor.index) { "取消" }
                if (confirmLabels.size == 2) confirmHints(label, scanMode)
                else ActionHints.list(label, scanMode, confirmCursor.direction)
            }
            pending -> ActionHints("", "正在回复…", "")
            else -> {
                val label = availableActions.getOrNull(barCursor.index)?.labelFor(draft).orEmpty()
                ActionHints.list(label, scanMode, barCursor.direction)
            }
        }
    )

    BackHandler(enabled = !paused && editing) { input.handleBackPress() }
    BackHandler(enabled = !paused && !editing && confirmation != null) { resolveConfirm(0) }

    if (editing) {
        val phrases = remember(inputState.mode, inputState.phase, inputState.dwellRevision) {
            input.rankedCommonPhrases(inputState.mode)
        }
        ComposerScreen(
            state = inputState,
            phrases = phrases,
            paused = paused,
            frozen = frozen,
            compactWings = false,
            onBlockClick = input::selectBlockByTouch,
            onPinyinClick = input::selectPinyinByTouch,
            onPinyinNavigationClick = input::selectPinyinNavigationByTouch,
            onCharacterClick = input::selectCharacterByTouch,
            onCharacterItemClick = input::selectCharacterItemByTouch,
            onCommonPhraseClick = input::selectCommonPhraseByTouch,
            onPredictionClick = input::selectPredictionByTouch,
            onInitialPredictionClick = input::selectInitialPredictionByTouch
        )
        return
    }

    // Dwell progress bar for active confirmation or bottom bar.
    val dwellMs = if (confirmation != null) {
        confirmCursor.dwell(scanIntervalMs, confirmation = true)
    } else {
        scanIntervalMs
    }
    val dwellAuto = scanMode == ScanMode.AUTO && (confirmation != null || liveBar)
    val dwellKey = if (confirmation != null) {
        "cf:${confirmCursor.index}:${confirmCursor.ticks}:${confirmCursor.direction}"
    } else {
        "bar:${barCursor.index}:${barCursor.direction}:${availableActions.size}"
    }
    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(dwellKey, dwellRevision, dwellMs, dwellAuto, paused, frozen, lifecycleOwner) {
        when {
            frozen -> dwellProgress.snapTo(if (dwellAuto) 0.5f else 0f)
            paused || !dwellAuto -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(dwellMs.toInt(), easing = LinearEasing))
            }
        }
    }
    val progressProvider: () -> Float? = { if (dwellAuto) dwellProgress.value else null }

    val c = LocalBciColors.current
    val focusedOption = confirmation?.let { req ->
        val optIdx = confirmCursor.index - 1
        req.options.getOrNull(optIdx)
    }
    val previewRows = remember(homeStatus, focusedOption, homeTime, frozen) {
        AssistantHomePreview.previewRows(homeStatus, focusedOption, homeTime, frozen)
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Left column: history + draft bar + 4-item action bar.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Conversation history panel: LazyColumn(reverseLayout = true) keeps newest content anchored at the bottom.
            val histListState = rememberLazyListState()
            val visibleEntries = remember(entries) { entries.takeLast(6) }
            LaunchedEffect(entries.size, pending, confirmation != null) {
                histListState.scrollToItem(0)
            }
            val histShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(c.panel, histShape)
                    .border(2.dp, c.line, histShape)
                    .clip(histShape)
            ) {
                LazyColumn(
                    state = histListState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
                ) {
                    val activeConfirm = confirmation
                    if (activeConfirm != null) {
                        item(key = "confirm") {
                            val dir = if (scanMode == ScanMode.AUTO) confirmCursor.direction else 0
                            val cfShape = RoundedCornerShape(18.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(c.panel2, cfShape)
                                    .border(2.dp, c.focus, cfShape)
                                    .padding(horizontal = 20.dp, vertical = 13.dp)
                            ) {
                                val cssLineBox = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.None
                                )
                                Text(
                                    text = "助手",
                                    color = c.muted,
                                    fontFamily = BciType.chinese,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    style = TextStyle(
                                        lineHeightStyle = cssLineBox,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    )
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "要执行这项操作吗？",
                                    color = c.text,
                                    fontFamily = BciType.chinese,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    lineHeight = 32.sp,
                                    style = TextStyle(
                                        lineHeightStyle = cssLineBox,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    )
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(84.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    confirmLabels.forEachIndexed { idx, label ->
                                        val focused = idx == confirmCursor.index
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .scanItem(
                                                    focused = focused,
                                                    direction = dir,
                                                    picked = false,
                                                    progress = if (focused) progressProvider else null
                                                )
                                                .clickable { resolveConfirm(idx) }
                                                .semantics {
                                                    if (focused && dwellAuto) {
                                                        this[AssistantDwellProgressReaderKey] = progressProvider
                                                    }
                                                }
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                color = c.text,
                                                fontFamily = BciType.chinese,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (pending) {
                        item(key = "typing") {
                            AssistantChatBubble(text = "正在回复…", fromUser = false, typing = true)
                        }
                    }
                    items(visibleEntries.asReversed()) { entry ->
                        AssistantChatBubble(text = entry.text, fromUser = entry.fromUser)
                    }
                }
            }

            // 2. Draft bar (72 dp).
            val draftShape = RoundedCornerShape(16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(c.panel, draftShape)
                    .border(2.dp, c.line, draftShape)
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .border(1.dp, c.line, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "草稿",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 16.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Text(
                    text = draft.ifEmpty { "输入问题或指令…" },
                    modifier = Modifier.weight(1f),
                    color = if (draft.isEmpty()) c.dim else c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = if (draft.isEmpty()) FontWeight.Normal else FontWeight(500),
                    fontSize = if (draft.isEmpty()) 22.sp else 30.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            // 3. Bottom 4-item action bar (92 dp).
            val barDir = if (scanMode == ScanMode.AUTO) barCursor.direction else 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .alpha(if (liveBar) 1f else 0.45f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistantAction.entries.forEach { action ->
                    val availIdx = if (liveBar) availableActions.indexOf(action) else -1
                    val enabled = availIdx >= 0
                    val focused = enabled && availIdx == barCursor.index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = barDir,
                                picked = false,
                                progress = if (focused) progressProvider else null,
                                enabled = enabled
                            )
                            .clickable(enabled = enabled) { chooseBarAction(action) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = action.labelFor(draft),
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            maxLines = 1,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }

        // Right column: Room Devices panel (330 dp).
        SimulatedHomePanel(
            rows = previewRows,
            flashKeys = flashKeys,
            modifier = Modifier
                .width(330.dp)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun AssistantChatBubble(
    text: String,
    fromUser: Boolean,
    typing: Boolean = false
) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(18.dp)
    val bg = if (fromUser) c.focusTint.compositeOver(c.panel) else c.panel

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .background(bg, shape)
                .border(1.dp, c.line, shape)
                .padding(horizontal = 19.dp, vertical = 12.dp)
        ) {
            if (!fromUser) {
                Text(
                    text = "助手",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = text,
                color = if (typing) c.muted else c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 35.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun SimulatedHomePanel(
    rows: List<HomePreviewRow>,
    flashKeys: Set<String>,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    val panelShape = RoundedCornerShape(20.dp)

    val panelCssLineBox = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )

    Column(
        modifier = modifier
            .background(c.mag, panelShape)
            .border(2.dp, c.line, panelShape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "房间设备",
            modifier = Modifier.padding(bottom = 6.dp),
            color = c.text,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight(800),
            fontSize = 22.sp,
            lineHeight = 31.sp,
            style = TextStyle(
                lineHeightStyle = panelCssLineBox,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )

        rows.forEach { row ->
            val nextValue = row.nextValue
            val changed = nextValue != null
            val flashed = !changed && row.key in flashKeys
            val rowShape = RoundedCornerShape(12.dp)
            val borderColor = when {
                changed -> c.focus
                flashed -> c.ok
                else -> Color.Transparent
            }
            val bgColor = when {
                changed -> c.focusTint.compositeOver(c.panel)
                flashed -> c.okTint
                else -> Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(bgColor, rowShape)
                    .border(2.dp, borderColor, rowShape)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontSize = 21.sp,
                    lineHeight = 30.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (nextValue != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mixedLatinText(row.currentValue),
                            color = c.muted,
                            fontWeight = FontWeight(500),
                            fontSize = 20.sp,
                            textDecoration = TextDecoration.LineThrough,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Text(
                            text = mixedLatinText(nextValue),
                            color = c.focus,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                } else {
                    Text(
                        text = mixedLatinText(row.currentValue),
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.End,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

private fun mixedLatinText(value: String) = buildAnnotatedString {
    for (ch in value) {
        withStyle(SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)) {
            append(ch)
        }
    }
}
