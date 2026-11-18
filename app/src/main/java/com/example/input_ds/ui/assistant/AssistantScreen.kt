package com.example.input_ds.ui.assistant

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.assistant.AssistantCatalogItem
import com.example.input_ds.assistant.AssistantConnection
import com.example.input_ds.assistant.AssistantResult
import com.example.input_ds.assistant.AssistantService
import com.example.input_ds.assistant.AssistantServiceException
import com.example.input_ds.assistant.HomeAction
import com.example.input_ds.assistant.SimulatedHome
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.music.MusicLibrary
import com.example.input_ds.ui.components.MainScreen
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraDarkAccentSoft
import com.example.input_ds.ui.theme.AuroraDarkBorder
import com.example.input_ds.ui.theme.AuroraDarkSurfaceStrong
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.tv.TV_CHANNELS
import com.example.input_ds.viewmodel.InputMethodViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Serializable

private const val MAX_INPUT_LENGTH = 240
private const val MAX_CHAT_TURNS = 50
private val ASSISTANT_PHRASES = listOf(
    "开灯", "关灯", "打开窗帘", "关闭窗帘", "开空调", "关空调", "我热",
    "降温", "打开床头灯", "关闭床头灯", "播放音乐", "看电视", "看央视新闻"
)

private enum class AssistantAction(val label: String) {
    INPUT("输入"),
    CLEAR("清空输入内容"),
    SEND("发送"),
    EXIT("退出");

    fun labelFor(draft: String): String =
        if (this == INPUT && draft.isNotEmpty()) "继续输入" else label

    fun isAvailable(draft: String, sending: Boolean): Boolean = when (this) {
        INPUT -> !sending
        CLEAR -> !sending && draft.isNotEmpty()
        SEND -> !sending && draft.isNotBlank()
        EXIT -> true
    }
}

private data class ChatTurn(val prompt: String, val reply: String)
private data class HomeConfirmation(val prompt: String, val options: List<HomeAction>) : Serializable

private val ChatTurnsSaver = listSaver<List<ChatTurn>, String>(
    save = { turns -> turns.flatMap { listOf(it.prompt, it.reply) } },
    restore = { values -> values.chunked(2).map { ChatTurn(it[0], it[1]) } }
)

internal object AssistantInputBus {
    private var handler: ((ControlSignal) -> Unit)? = null

    fun attach(onSignal: (ControlSignal) -> Unit) {
        handler = onSignal
    }

    fun detach(onSignal: (ControlSignal) -> Unit) {
        if (handler === onSignal) handler = null
    }

    fun dispatch(signal: ControlSignal) {
        handler?.invoke(signal)
    }
}

@Composable
internal fun AssistantScreen(
    scanIntervalMs: Long,
    onBack: () -> Unit,
    onOpenMedia: (AppDestination, String) -> Unit
) {
    val context = LocalContext.current.applicationContext as Application
    val input: InputMethodViewModel = viewModel(key = "assistant_input") {
        InputMethodViewModel(context, remotePredictionsEnabled = false, priorityPhrases = ASSISTANT_PHRASES)
    }
    val inputState by input.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val preferences = remember(context) {
        context.getSharedPreferences("assistant_connection", Context.MODE_PRIVATE)
    }
    val library = remember(context) { MusicLibrary(context) }
    val allowHttp = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val channels = remember { TV_CHANNELS.map { AssistantCatalogItem(it.id, it.name) } }

    var address by remember { mutableStateOf(preferences.getString("address", "").orEmpty()) }
    var accessCode by remember { mutableStateOf(preferences.getString("access_code", "").orEmpty()) }
    var showSetup by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    var draft by rememberSaveable { mutableStateOf("") }
    var turns by rememberSaveable(stateSaver = ChatTurnsSaver) { mutableStateOf(emptyList<ChatTurn>()) }
    var pendingPrompt by remember { mutableStateOf<String?>(null) }
    var home by rememberSaveable { mutableStateOf(SimulatedHome()) }
    var confirmation by rememberSaveable { mutableStateOf<HomeConfirmation?>(null) }
    var homeTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun appendTurn(prompt: String, reply: String) {
        turns = (turns + ChatTurn(prompt, reply)).takeLast(MAX_CHAT_TURNS)
    }

    fun submit() {
        val prompt = draft.trim()
        if (prompt.length > MAX_INPUT_LENGTH) {
            val preview = prompt.take(MAX_INPUT_LENGTH).trimEnd { it.isHighSurrogate() }
            appendTurn("$preview…", "请把输入缩短到 ${MAX_INPUT_LENGTH} 字以内")
            return
        }
        val connection = AssistantConnection.parse(address, accessCode, allowHttp)
        if (connection == null) {
            appendTurn(prompt, "请在右上角「设置」中完成连接配置")
            return
        }
        pendingPrompt = prompt
        scope.launch {
            try {
                val tracks = library.initializeAndLoadTracks().take(100)
                val catalog = tracks.mapIndexed { index, track ->
                    AssistantCatalogItem("track_${index + 1}", "${track.title} · ${track.artist}".take(180))
                }
                val reply = AssistantService.submit(connection, prompt, catalog, channels, home)
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
                        selected = 0
                        confirmation = HomeConfirmation(prompt, reply.options)
                        draft = ""
                        return@launch
                    }
                }
                appendTurn(prompt, responseText)
                // Keep the original text available when the user needs to clarify it.
                if (reply !is AssistantResult.Message || !reply.clarify) draft = ""
                media?.let { (destination, id) -> onOpenMedia(destination, id) }
            } catch (error: AssistantServiceException) {
                appendTurn(prompt, error.message ?: "生活助手暂时无法回复，请稍后重试")
            } catch (_: IOException) {
                appendTurn(prompt, "未能连接电脑服务，请检查同一局域网和服务地址")
            } finally {
                pendingPrompt = null
            }
        }
    }

    fun finishInput() {
        draft = input.state.value.outputText
        editing = false
        selected = AssistantAction.INPUT.ordinal
    }

    fun choose(action: AssistantAction) {
        if (showSetup || confirmation != null || !action.isAvailable(draft, pendingPrompt != null)) return
        if (action != AssistantAction.CLEAR) focusManager.clearFocus()
        when (action) {
            AssistantAction.INPUT -> {
                input.loadDraft(draft)
                editing = true
            }
            AssistantAction.CLEAR -> draft = ""
            AssistantAction.SEND -> submit()
            AssistantAction.EXIT -> onBack()
        }
    }

    fun confirmHome(index: Int) {
        val request = confirmation ?: return
        if (showSetup || index !in 0..request.options.size) return
        val action = request.options.getOrNull(index)
        val response = if (action == null) {
            "已取消操作"
        } else {
            val now = System.currentTimeMillis()
            val current = home.expireTimer(now)
            val updated = current.apply(action, now)
            if (updated == null) {
                home = current
                "设备状态已变化，请重新发送指令"
            } else {
                home = updated
                "模拟：已执行「${action.describe(current)}」"
            }
        }
        appendTurn(request.prompt, response)
        confirmation = null
        selected = 0
    }

    val currentSignalHandler by rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        when {
            showSetup -> Unit
            editing -> input.handleSignal(signal, ::finishInput)
            else -> when (signal) {
                ControlSignal.LEFT_LOOK -> direction = -1
                ControlSignal.RIGHT_LOOK -> direction = 1
                ControlSignal.BITE -> {
                    if (confirmation != null) confirmHome(selected)
                    else choose(AssistantAction.entries[selected])
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
        }
    }
    DisposableEffect(Unit) {
        val handler: (ControlSignal) -> Unit = { currentSignalHandler(it) }
        AssistantInputBus.attach(handler)
        onDispose { AssistantInputBus.detach(handler) }
    }
    LaunchedEffect(scanIntervalMs) { input.setScanInterval(scanIntervalMs) }
    LaunchedEffect(editing, showSetup, selected, direction, scanIntervalMs, confirmation) {
        if (!editing && !showSetup) {
            delay(scanIntervalMs)
            val count = confirmation?.let { it.options.size + 1 } ?: AssistantAction.entries.size
            selected = (selected + direction + count) % count
        }
    }
    LaunchedEffect(home.airConditioner.offAtMillis) {
        val deadline = home.airConditioner.offAtMillis ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            homeTime = now
            val remaining = deadline - now
            if (remaining <= 0) {
                home = home.expireTimer(now)
                break
            }
            delay((remaining - 1) % 60_000L + 1)
        }
    }
    BackHandler(enabled = editing) { finishInput() }
    BackHandler(enabled = confirmation != null) {
        confirmation?.let { confirmHome(it.options.size) }
    }

    AuroraBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (editing) "输入内容" else "生活助手", style = MaterialTheme.typography.headlineSmall)
                if (!editing) {
                    TextButton(
                        onClick = { showSetup = true },
                        enabled = pendingPrompt == null && confirmation == null
                    ) {
                        Text("设置")
                    }
                }
            }
            if (editing) {
                MainScreen(
                    state = inputState,
                    onBlockClick = { input.selectBlockByTouch(it, ::finishInput) },
                    onPinyinClick = input::selectPinyinByTouch,
                    onPinyinNavigationClick = input::selectPinyinNavigationByTouch,
                    onCharacterClick = input::selectCharacterByTouch,
                    onCommonPhraseClick = input::selectCommonPhraseByTouch,
                    onPredictionClick = input::selectPredictionByTouch,
                    onInitialPredictionClick = input::selectInitialPredictionByTouch,
                    sendLabel = "完成",
                    commonPhrases = input::rankedCommonPhrases
                )
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val showSidePanel = maxWidth >= 720.dp
                    val sending = pendingPrompt != null
                    val question = confirmation
                    val homeStatus = home.expireTimer(homeTime)
                    val updateDraft: (String) -> Unit = { draft = it }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatHistory(
                                turns = turns,
                                pendingPrompt = pendingPrompt,
                                confirmation = question,
                                optionLabels = question?.options?.map { it.describe(homeStatus) }.orEmpty(),
                                selectedOption = selected,
                                onConfirm = ::confirmHome,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            if (!showSidePanel) {
                                SimulatedHomeStatus(homeStatus, homeTime)
                                TestKeyboardInput(draft, !sending && question == null, updateDraft)
                            }
                            DraftControls(draft, selected, sending, question != null, ::choose)
                        }
                        if (showSidePanel) {
                            Column(
                                Modifier.width(248.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                                    Text("触摸测试", style = MaterialTheme.typography.titleMedium)
                                    TestKeyboardInput(draft, !sending && question == null, updateDraft)
                                    AssistantAction.entries.chunked(2).forEach { actions ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            actions.forEach { action ->
                                                OutlinedButton(
                                                    onClick = { choose(action) },
                                                    enabled = question == null && action.isAvailable(draft, sending),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(action.labelFor(draft), textAlign = TextAlign.Center)
                                                }
                                            }
                                        }
                                    }
                                }
                                SimulatedHomeStatus(homeStatus, homeTime)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSetup) {
        AssistantConnectionDialog(
            address = address,
            accessCode = accessCode,
            allowHttp = allowHttp,
            onClose = { showSetup = false },
            onSave = { connection ->
                address = connection.address.toString()
                accessCode = connection.accessCode
                preferences.edit().putString("address", address).putString("access_code", accessCode).apply()
                showSetup = false
            }
        )
    }
}

@Composable
private fun ChatHistory(
    turns: List<ChatTurn>,
    pendingPrompt: String?,
    confirmation: HomeConfirmation?,
    optionLabels: List<String>,
    selectedOption: Int,
    onConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberLazyListState()
    val inProgress = confirmation?.prompt ?: pendingPrompt
    LaunchedEffect(turns, pendingPrompt, confirmation) {
        if (inProgress != null) scroll.scrollToItem(turns.size + 1)
        else if (turns.isNotEmpty()) scroll.scrollToItem(turns.lastIndex)
    }
    GlassPanel(modifier, contentPadding = PaddingValues(0.dp)) {
        if (turns.isEmpty() && inProgress == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("选择「输入」开始对话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = scroll,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
            ) {
                items(turns) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatBubble(turn.prompt, fromUser = true)
                        ChatBubble(turn.reply, fromUser = false)
                    }
                }
                if (inProgress != null) {
                    item { ChatBubble(inProgress, fromUser = true) }
                    item {
                        if (confirmation == null) {
                            ChatBubble("正在回复…", fromUser = false)
                        } else {
                            val question = if (optionLabels.size == 1) "要执行这项操作吗？" else "请选择一项操作。"
                            ChatBubble(question, fromUser = false) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    (optionLabels + "取消").forEachIndexed { index, label ->
                                        ScanButton(
                                            label = label,
                                            highlighted = selectedOption == index,
                                            enabled = true,
                                            onClick = { onConfirm(index) },
                                            modifier = Modifier.weight(1f)
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
}

@Composable
private fun ChatBubble(text: String, fromUser: Boolean, actions: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier.fillMaxWidth(.82f),
            contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (fromUser) AuroraDarkAccentSoft else AuroraDarkSurfaceStrong,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        if (fromUser) "你" else "生活助手",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (fromUser) AuroraVioletBright else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    actions?.invoke()
                }
            }
        }
    }
}

@Composable
private fun SimulatedHomeStatus(home: SimulatedHome, now: Long) {
    val ac = home.airConditioner
    val minutes = ac.remainingMinutes(now)
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Text("模拟家居", style = MaterialTheme.typography.titleSmall)
        Text(
            "大灯 ${if (home.mainLightOn) "开" else "关"}  ·  " +
                "床头灯 ${if (home.bedsideLightOn) "开" else "关"}  ·  窗帘 ${home.curtainPositionPercent}%",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "空调 ${if (ac.powerOn) "开" else "关"}  ·  ${ac.temperatureC}°C  ·  ${ac.mode.label}\n" +
                "风向 ${ac.swing.label}  ·  ${if (minutes == 0) "未定时" else "$minutes 分钟后关机"}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TestKeyboardInput(draft: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = draft,
        onValueChange = onChange,
        label = { Text("键盘输入") },
        enabled = enabled,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DraftControls(
    draft: String,
    selected: Int,
    sending: Boolean,
    awaitingConfirmation: Boolean,
    onAction: (AssistantAction) -> Unit
) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = draft.ifEmpty { "请输入内容" },
                color = if (draft.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantAction.entries.forEach { action ->
                ScanButton(
                    label = action.labelFor(draft),
                    highlighted = !awaitingConfirmation && action.ordinal == selected,
                    enabled = !awaitingConfirmation && action.isAvailable(draft, sending),
                    onClick = { onAction(action) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ScanButton(
    label: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            if (highlighted) 2.dp else 1.dp,
            if (highlighted) AuroraVioletBright else AuroraDarkBorder
        ),
        color = if (highlighted) AuroraDarkAccentSoft else AuroraDarkSurfaceStrong
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = when {
                    highlighted -> AuroraVioletBright
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AssistantConnectionDialog(
    address: String,
    accessCode: String,
    allowHttp: Boolean,
    onClose: () -> Unit,
    onSave: (AssistantConnection) -> Unit
) {
    var addressDraft by remember { mutableStateOf(address) }
    var codeDraft by remember { mutableStateOf(accessCode) }
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connection = AssistantConnection.parse(addressDraft, codeDraft, allowHttp)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("生活助手设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = addressDraft,
                    onValueChange = { addressDraft = it; status = "" },
                    label = { Text("电脑地址，例如 http://192.168.1.10:8765") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    enabled = !checking
                )
                OutlinedTextField(
                    value = codeDraft,
                    onValueChange = { codeDraft = it; status = "" },
                    label = { Text("电脑服务连接码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !checking
                )
                if (status.isNotEmpty()) Text(status)
            }
        },
        confirmButton = {
            Button(onClick = { connection?.let(onSave) }, enabled = connection != null && !checking) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                enabled = connection != null && !checking,
                onClick = {
                    val target = connection ?: return@TextButton
                    checking = true
                    status = "正在测试…"
                    scope.launch {
                        try {
                            status = if (AssistantService.checkConnection(target)) {
                                "已连上电脑，已配置 DeepSeek Key"
                            } else {
                                "已连上电脑，请在电脑端配置 DeepSeek Key"
                            }
                        } catch (error: AssistantServiceException) {
                            status = error.message.orEmpty()
                        } catch (_: IOException) {
                            status = "无法连接电脑服务"
                        } finally {
                            checking = false
                        }
                    }
                }
            ) { Text("测试连接") }
        }
    )
}
