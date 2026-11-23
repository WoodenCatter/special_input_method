package com.example.input_ds

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.example.input_ds.alert.NurseCallAlarm
import com.example.input_ds.alert.NurseCallState
import com.example.input_ds.assistant.AssistantCareStore
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.speech.SpeechOutput
import com.example.input_ds.ui.communication.ComposerScreen
import com.example.input_ds.ui.design.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.communication.videoPermissions
import com.example.input_ds.ui.communication.PadVideoScreen
import com.example.input_ds.bci.BciController
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.game.MazeAction
import com.example.input_ds.game.MazeExitChoice
import com.example.input_ds.game.MazeGame
import com.example.input_ds.game.MazeProtocol
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentHubSelectionState
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.music.MusicInputBus
import com.example.input_ds.settings.AppSettingsInputBus
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.TrainingWorkScheduler
import com.example.input_ds.personalization.UserModelManager
import com.example.input_ds.ui.bci.BleScanScreen
import com.example.input_ds.ui.bci.CollectionScreen
import com.example.input_ds.ui.bci.SignalMonitorScreen
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.input_ds.chess.ChineseChessInputBus
import com.example.input_ds.doudizhu.DoudizhuInputBus
import com.example.input_ds.ui.chess.ChineseChessScreen
import com.example.input_ds.mahjong.MahjongInputBus
import com.example.input_ds.ui.doudizhu.DoudizhuScreen
import com.example.input_ds.ui.mahjong.MahjongScreen
import com.example.input_ds.game.SnakeAction
import com.example.input_ds.game.SnakeClimbGame
import com.example.input_ds.ui.assistant.AssistantInputBus
import com.example.input_ds.ui.assistant.AssistantScreen
import com.example.input_ds.ui.components.FloatingEegSignalSource
import com.example.input_ds.ui.components.FloatingEegSignalWindow
import com.example.input_ds.wizard.WizardGameActivity
import com.example.input_ds.wizard.WizardGameInputBus
import com.example.input_ds.ui.game.SnakeClimbScreen
import com.example.input_ds.ui.components.FloatingControlBall
import com.example.input_ds.ui.communication.CommunicationScreen
import com.example.input_ds.communication.CommunicationSignalOutcome
import com.example.input_ds.communication.CommunicationStage
import com.example.input_ds.communication.CommunicationViewModel
import com.example.input_ds.communication.ContactSelectionPolicy
import com.example.input_ds.ui.game.MazeScreen
import com.example.input_ds.ui.home.EntertainmentScreen
import com.example.input_ds.ui.home.HomeScreen
import com.example.input_ds.ui.music.MusicScreen
import com.example.input_ds.ui.settings.AppSettingsScreen
import com.example.input_ds.ui.tv.TvScreen
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.viewmodel.InputMethodViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: NaoyunBleManager
    private lateinit var nurseCallAlarm: NurseCallAlarm
    private lateinit var speechOutput: SpeechOutput
    private var bciController: BciController? = null
    private var activeBciController by mutableStateOf<BciController?>(null)
    private var inputViewModel: InputMethodViewModel? = null
    private var communicationViewModel: CommunicationViewModel? = null
    private var videoCursor by mutableStateOf(ScanCursor(1))
    private var videoConfirmCursor by mutableStateOf(ScanCursor(2))
    private var lastCallWasIncoming = false
    private var hadActiveCall = false
    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { communicationViewModel?.video?.permissionResult() }
    private val wizardGameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun askVideoPermissions() = videoPermissionLauncher.launch(videoPermissions)
    private var attemptedModelIdentity: String? = null

    private val destination = mutableStateOf(AppDestination.HOME)
    private val snakeState = mutableStateOf(SnakeClimbGame.create())
    private var assistantMediaId: String? = null
    private val homeModules = HomeModule.entries
    private var cursor by mutableStateOf(ScanCursor(homeModules.size))
    private var dwellRevision by mutableIntStateOf(0)
    private var frozen by mutableStateOf(false)
    private var displayMode by mutableStateOf(AppUiSettings.DisplayMode.AUTO)
    private var nurseCall by mutableStateOf<NurseCallState?>(null)
    private var speakOverlay by mutableStateOf<SpeakOverlayState?>(null)
    private var confirmOverlay by mutableStateOf<Pair<ConfirmOverlaySpec, ScanCursor>?>(null)
    private var assistantEditing by mutableStateOf(false)
    private var careOpen by mutableStateOf(false)
    private var feedbackSound by mutableStateOf(true)
    private var floatingEegVisible by mutableStateOf(false)
    private var debugNoContacts by mutableStateOf(false)
    private var bluetoothPermissionRequested = false
    private var statusPath = listOf("主页")
    private val feedback = GazeFeedback()
    private var foreground = false
    private var unregisterDebug: () -> Unit = {}
    private val caregiverPages = setOf(AppDestination.SETTINGS, AppDestination.COLLECTION, AppDestination.DEVICE_STATUS)

    private val entertainmentSelection = mutableStateOf(EntertainmentHubSelectionState())
    private val mazeState = mutableStateOf(
        MazeGame.create(MazeProtocol.FOUR_CLASS)
    )
    private val scanMode = mutableStateOf(ScanMode.AUTO)
    private val bciActive = mutableStateOf(false)
    private val bciStatus = mutableStateOf("耳机未连接")
    private val floatingBallVisible = mutableStateOf(true)
    private val headsetControlEnabled = mutableStateOf(false)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it } && bleManager.hasPermissions()) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CharacterDictionary.init(applicationContext)
        CommonPhraseUsage.init(applicationContext)
        LanguageModel.init(applicationContext)
        UserDictionary.init(applicationContext)
        bleManager = NaoyunBleManager(applicationContext)
        nurseCallAlarm = NurseCallAlarm(applicationContext)
        speechOutput = SpeechOutput(applicationContext)
        FloatingEegSignalSource.attach(bleManager)
        TrainingWorkScheduler.resumeIncomplete(applicationContext)
        floatingBallVisible.value = AppUiSettings.readFloatingBallVisible(applicationContext)
        feedbackSound = AppUiSettings.feedbackSound(applicationContext)
        floatingEegVisible = AppUiSettings.floatingEegVisible(applicationContext)
        AssistantCareStore.load(applicationContext)
        scanMode.value = ScanSettings.readMode(applicationContext)
        window.attributes = window.attributes.apply {
            screenBrightness = AppUiSettings.readScreenBrightness(applicationContext)
        }

        displayMode = AppUiSettings.displayMode(this)
        unregisterDebug = registerRedesignDebug(this, ::debugCommand)
        setContent {
            var hour by remember { mutableIntStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
            LaunchedEffect(Unit) {
                while (true) {
                    hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
            val dark = when (displayMode) {
                AppUiSettings.DisplayMode.AUTO -> (if (frozen) 9 else hour) !in 7..18
                AppUiSettings.DisplayMode.DARK -> true
                AppUiSettings.DisplayMode.LIGHT -> false
            }
            InputDSTheme(dark) {
                val vm: InputMethodViewModel = viewModel {
                    InputMethodViewModel(application, remotePredictionsEnabled = !BuildConfig.DEMO_COMMUNICATION)
                }
                val chat: CommunicationViewModel = viewModel()
                inputViewModel = vm
                communicationViewModel = chat
                chat.requestVideoPermissions = ::askVideoPermissions
                val lifecycle = this@MainActivity.lifecycle
                val video by chat.video.state.collectAsState()
                val call = video.call
                LaunchedEffect(chat, lifecycle) {
                    if (BuildConfig.DEMO_COMMUNICATION) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        chat.video.foreground(true)
                        try {
                            var previous: String? = null
                            while (true) {
                                val repository = PersonalizationRepository(applicationContext)
                                val user = repository.activeUser()
                                val binding = repository.deviceServerBinding()
                                val identity = "${binding?.serverUserId}:${user?.profileId}:${user?.profileEnabled}"
                                if (identity != previous) {
                                    if (previous != null) vm.clearOutputAndReset()
                                    previous = identity
                                    chat.open()
                                }
                                delay(1_000)
                            }
                        } finally {
                            chat.video.foreground(false)
                        }
                    }
                }
                LaunchedEffect(chat) {
                    chat.setOnToast { message, ok -> feedback.toast(message, ok) }
                    chat.setGraceMs(AppUiSettings.graceMs(applicationContext))
                    chat.videoNotices.collect { feedback.toast(it) }
                }
                LaunchedEffect(chat, scanMode.value) { chat.setScanMode(scanMode.value) }
                LaunchedEffect(chat, frozen) { chat.setFrozen(frozen) }
                LaunchedEffect(chat, destination.value) {
                    chat.setScreenActive(destination.value == AppDestination.COMMUNICATION)
                }
                LaunchedEffect(chat, careOpen, nurseCall != null, speakOverlay != null, confirmOverlay != null, call != null) {
                    chat.setPaused(careOpen || nurseCall != null || speakOverlay != null || confirmOverlay != null || call != null)
                }
                LaunchedEffect(call?.id, call?.status, video.connected, video.camera, video.confirmEnd) {
                    if (call != null) {
                        if (!hadActiveCall && video.incoming && careOpen) toggleCareSheet(false)
                        hadActiveCall = true
                        lastCallWasIncoming = video.incoming
                        val actionsCount = chat.video.actions(::askVideoPermissions).size.coerceAtLeast(1)
                        videoCursor = ScanCursor(size = actionsCount, index = 0, direction = 1)
                        if (video.confirmEnd) {
                            videoConfirmCursor = ScanCursor(size = 2, index = 0, direction = 1)
                        }
                        dwellRevision++
                    } else if (hadActiveCall) {
                        hadActiveCall = false
                        chat.onVideoCallEnded(lastCallWasIncoming)
                        dwellRevision++
                    }
                    updateBciCommandDelivery()
                }
                LaunchedEffect(call?.id, video.confirmEnd, careOpen, frozen, scanMode.value, dwellRevision) {
                    if (call != null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            if (video.confirmEnd) {
                                runScanTimer(
                                    interval = { videoConfirmCursor.dwell(vm.state.value.scanIntervalMs, confirmation = true) },
                                    onFailure = { android.util.Log.e("ScanCursor", "Video confirm tick failed", it) }
                                ) {
                                    advanceVideoConfirm()
                                }
                            } else {
                                runScanTimer(
                                    interval = { vm.state.value.scanIntervalMs },
                                    onFailure = { android.util.Log.e("ScanCursor", "Video tick failed", it) }
                                ) {
                                    advanceVideoCursor()
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(vm) {
                    vm.setOnToast { message, ok -> feedback.toast(message, ok) }
                    vm.setGraceMs(AppUiSettings.graceMs(applicationContext))
                    vm.deactivate()
                }
                LaunchedEffect(vm, scanMode.value) { vm.setScanMode(scanMode.value) }
                LaunchedEffect(vm, frozen) { vm.setFrozen(frozen) }
                LaunchedEffect(vm, careOpen, nurseCall != null, speakOverlay != null, confirmOverlay != null, call != null) {
                    vm.setPaused(careOpen || nurseCall != null || speakOverlay != null || confirmOverlay != null || call != null)
                }
                val bleState by bleManager.state.collectAsState()

                LaunchedEffect(bleState) {
                    if (bleState != NaoyunBleManager.State.READY && headsetControlEnabled.value) {
                        setHeadsetControlEnabled(false)
                    }
                    if (bleState == NaoyunBleManager.State.READY) {
                        UserModelManager.activePreset(applicationContext)?.let { preset ->
                            bleManager.setPreprocessing(preset.displaySteps())
                        }
                        if (!bciActive.value) startBciController()
                        while (bleManager.state.value == NaoyunBleManager.State.READY) {
                            delay(2_000)
                            val identity = activeModelIdentity()
                            if (identity != attemptedModelIdentity) restartBciController()
                        }
                    } else if (bleState in setOf(
                            NaoyunBleManager.State.IDLE,
                            NaoyunBleManager.State.STREAM_STALLED,
                            NaoyunBleManager.State.RECOVERING,
                            NaoyunBleManager.State.DISCONNECTED,
                            NaoyunBleManager.State.ERROR
                        )
                    ) {
                        stopBciController()
                    }
                }

                val state by vm.state.collectAsState()
                val conversation by chat.state.collectAsState()
                val telemetry by bleManager.deviceTelemetry.collectAsState()
                val connectedDevice by bleManager.connectedDevice.collectAsState()
                val assistantConfig by AssistantCareStore.config.collectAsState()
                val assistantDraft by AssistantCareStore.debugDraft.collectAsState()
                val allowHttpAssistant = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                val fallbackReadiness = remember { MutableStateFlow(BciController.Readiness()) }
                val rawReadiness by (activeBciController?.readiness ?: fallbackReadiness).collectAsState()
                val effectiveReadiness = if (bleState == NaoyunBleManager.State.READY && headsetControlEnabled.value) {
                    rawReadiness
                } else {
                    BciController.Readiness()
                }
                LaunchedEffect(bleState, connectedDevice, frozen) {
                    if (!frozen && connectedDevice == null && bleState in setOf(
                            NaoyunBleManager.State.IDLE,
                            NaoyunBleManager.State.DISCONNECTED,
                            NaoyunBleManager.State.ERROR
                        )
                    ) {
                        if (bleManager.hasPermissions()) {
                            bleManager.startScan()
                        } else if (!bluetoothPermissionRequested) {
                            bluetoothPermissionRequested = true
                            requestBluetoothPermissions()
                        }
                    }
                }
                LaunchedEffect(nurseCall != null, nurseCall?.sent, frozen, careOpen) {
                    val current = nurseCall
                    if (current == null || !current.sent || frozen) {
                        nurseCallAlarm.stop()
                    } else {
                        nurseCallAlarm.start()
                    }
                    if (current != null && !current.sent && !frozen && !careOpen) {
                        while (true) {
                            delay(1_000L)
                            val latest = nurseCall ?: break
                            if (latest.sent) break
                            val next = latest.tick()
                            nurseCall = next
                            if (next.sent) {
                                if (!frozen) nurseCallAlarm.start()
                                break
                            }
                        }
                    }
                }
                val patient = call != null || destination.value !in caregiverPages
                LaunchedEffect(patient) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (patient) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                LaunchedEffect(confirmOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    val active = confirmOverlay
                    if (active != null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            runScanTimer(
                                interval = {
                                    (confirmOverlay?.second ?: active.second).dwell(state.scanIntervalMs, confirmation = true)
                                },
                                onFailure = { android.util.Log.e("ScanCursor", "Confirm tick failed", it) }
                            ) {
                                advanceConfirmOverlay()
                            }
                        }
                    }
                }
                LaunchedEffect(speakOverlay != null, confirmOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (speakOverlay != null && confirmOverlay == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Speak tick failed", it) }) {
                                advanceSpeakOverlay()
                            }
                        }
                    }
                }
                LaunchedEffect(destination.value, call?.id, nurseCall != null, speakOverlay != null, confirmOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (destination.value == AppDestination.HOME && call == null && nurseCall == null && speakOverlay == null && confirmOverlay == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            cursor = cursor.copy(previousIndex = -1)
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Home tick failed", it) }) {
                                advanceHome()
                            }
                        }
                    }
                }
                val path = if (destination.value == AppDestination.LIFE_ASSISTANT && assistantEditing && call == null) {
                    listOf("生活助手", "输入")
                } else {
                    patientPath(destination.value, conversation, video, mediaParent(), state.mode)
                }
                androidx.compose.runtime.SideEffect { statusPath = path }
                LaunchedEffect(path, state.phase, state.charPage, state.predictionPage, state.initialPage, video.confirmEnd, nurseCall != null, speakOverlay != null, confirmOverlay != null, careOpen) {
                    bciController?.invalidatePendingCommands()
                }
                BackHandler(enabled = careOpen) { toggleCareSheet(false) }
                BackHandler(enabled = !careOpen && confirmOverlay != null) { dismissConfirmOverlay() }
                BackHandler(enabled = !careOpen && confirmOverlay == null && speakOverlay != null) { dismissSpeakOverlay() }
                BackHandler(enabled = !careOpen && confirmOverlay == null && speakOverlay == null && nurseCall != null) { dismissNurseCall() }
                Box(Modifier.fillMaxSize()) {
                    if (patient) {
                        val connection = when (bleState) {
                            NaoyunBleManager.State.READY -> "耳机已连接"
                            NaoyunBleManager.State.STREAM_STALLED, NaoyunBleManager.State.RECOVERING -> "信号中断"
                            else -> "耳机未连接"
                        }
                        val display = (if (dark) "夜间" else "日间") + (if (displayMode == AppUiSettings.DisplayMode.AUTO) " · 自动" else "")
                        val battery = telemetry.takeIf { connection != "耳机未连接" }
                        val status = PatientStatus(path, connection, battery?.leftBatteryPercent, battery?.rightBatteryPercent,
                            scanMode.value, state.scanIntervalMs, display)
                        val activeNurseCall = nurseCall
                        val activeSpeak = speakOverlay
                        val activeConfirm = confirmOverlay
                        val hasTopOverlay = activeNurseCall != null || activeSpeak != null || activeConfirm != null
                        val baseHints = when {
                            call != null && video.confirmEnd ->
                                confirmHints(if (videoConfirmCursor.index == 0) "继续通话" else "挂断", scanMode.value)
                            call != null -> ActionHints.list(
                                chat.video.actions(::askVideoPermissions).getOrNull(videoCursor.index)?.label.orEmpty(),
                                scanMode.value,
                                videoCursor.direction
                            )
                            activeConfirm != null -> {
                                val (spec, confirmCursor) = activeConfirm
                                val label = if (confirmCursor.index == 0) spec.cancelLabel else spec.confirmLabel
                                confirmHints(label, scanMode.value)
                            }
                            activeSpeak != null -> ActionHints.list(
                                SpeakOverlayState.LABELS.getOrElse(activeSpeak.cursor.index) { "" },
                                scanMode.value,
                                activeSpeak.cursor.direction
                            )
                            activeNurseCall != null -> if (activeNurseCall.sent) {
                                ActionHints("关闭", "关闭", "关闭")
                            } else {
                                ActionHints("取消呼叫", "立即呼叫", "立即呼叫")
                            }
                            destination.value == AppDestination.HOME -> ActionHints.list(homeModules[cursor.index].displayName, scanMode.value, cursor.direction)
                            destination.value == AppDestination.ENTERTAINMENT -> ActionHints.list(entertainmentSelection.value.selectedModule.displayName, scanMode.value, entertainmentSelection.value.scanDirection, true)
                            destination.value == AppDestination.COMMUNICATION -> communicationHints(conversation, state)
                            destination.value == AppDestination.INPUT_METHOD -> inputHints(state)
                            destination.value == AppDestination.TV -> ActionHints("上一台", "打开菜单", "下一台")
                            else -> ActionHints("游戏内 · 向左", "离开游戏…", "游戏内 · 向右")
                        }
                        var pageHints by remember(destination.value) { mutableStateOf<ActionHints?>(null) }
                        val careInfo = remember(bleState, connectedDevice, telemetry, activeBciController, careOpen) {
                            buildCaregiverInfo(bleState, connectedDevice, telemetry)
                        }
                        PatientScaffold(
                            status = status,
                            hints = if (call == null && !hasTopOverlay) pageHints ?: baseHints else baseHints,
                            feedback = feedback,
                            readiness = effectiveReadiness,
                            onCare = { toggleCareSheet(true) },
                            overlay = if (hasTopOverlay) {
                                {
                                    activeSpeak?.let { speak ->
                                        SpeakOverlay(
                                            state = speak,
                                            scanMode = scanMode.value,
                                            scanIntervalMs = state.scanIntervalMs,
                                            dwellRevision = dwellRevision,
                                            paused = careOpen || activeConfirm != null,
                                            frozen = frozen,
                                            onChoose = ::chooseSpeakOption
                                        )
                                    }
                                    activeConfirm?.let { (spec, confirmCursor) ->
                                        ConfirmOverlay(
                                            spec = spec,
                                            cursor = confirmCursor,
                                            scanMode = scanMode.value,
                                            scanIntervalMs = state.scanIntervalMs,
                                            dwellRevision = dwellRevision,
                                            paused = careOpen,
                                            frozen = frozen,
                                            onChoose = ::chooseConfirmOption
                                        )
                                    }
                                    activeNurseCall?.let { currentCall ->
                                        NurseCallOverlay(
                                            state = currentCall,
                                            onDismissWhenSent = ::dismissNurseCall
                                        )
                                    }
                                }
                            } else null,
                            videoLayer = if (call != null) {
                                {
                                    PadVideoScreen(
                                        session = chat.video,
                                        state = video,
                                        cursor = videoCursor,
                                        scanMode = scanMode.value,
                                        scanIntervalMs = state.scanIntervalMs,
                                        dwellRevision = dwellRevision,
                                        paused = careOpen || video.confirmEnd,
                                        frozen = frozen,
                                        ask = ::askVideoPermissions
                                    )
                                    if (video.confirmEnd) {
                                        ConfirmOverlay(
                                            spec = ConfirmOverlaySpec(
                                                title = "挂断和${video.peerName}的视频？",
                                                description = "通话会立即结束。",
                                                cancelLabel = "继续通话",
                                                cancelSub = "回到通话",
                                                confirmLabel = "挂断",
                                                confirmSub = "结束视频",
                                                onConfirm = { chat.video.confirmEndCall(frozen) },
                                                onCancel = {
                                                    chat.video.cancelEndConfirm()
                                                    dwellRevision++
                                                }
                                            ),
                                            cursor = videoConfirmCursor,
                                            scanMode = scanMode.value,
                                            scanIntervalMs = state.scanIntervalMs,
                                            dwellRevision = dwellRevision,
                                            paused = careOpen,
                                            frozen = frozen,
                                            onChoose = { idx ->
                                                if (idx == 1) chat.video.confirmEndCall(frozen)
                                                else {
                                                    chat.video.cancelEndConfirm()
                                                    dwellRevision++
                                                }
                                            }
                                        )
                                    }
                                }
                            } else null,
                            drawer = {
                                CaregiverSheet(
                                    open = careOpen,
                                    frozen = frozen,
                                    headsetControlEnabled = headsetControlEnabled.value,
                                    feedbackSound = feedbackSound,
                                    floatingEegVisible = floatingEegVisible,
                                    info = careInfo,
                                    assistantConfig = assistantConfig,
                                    assistantDraft = assistantDraft,
                                    allowHttpAssistant = allowHttpAssistant,
                                    debugNoContacts = debugNoContacts,
                                    bleManager = bleManager,
                                    actionLog = feedback.log,
                                    onClose = { toggleCareSheet(false) },
                                    onHeadsetControlChange = { enabled ->
                                        if (enabled && bleManager.state.value != NaoyunBleManager.State.READY) {
                                            feedback.toast("请先连接耳机后再启用耳机控制")
                                        } else {
                                            setHeadsetControlEnabled(enabled)
                                            feedback.toast(if (enabled) "耳机控制已启用" else "耳机控制已停用，仅模拟输入")
                                        }
                                    },
                                    onFeedbackSoundChange = { enabled ->
                                        feedbackSound = enabled
                                        AppUiSettings.writeFeedbackSound(applicationContext, enabled)
                                    },
                                    onFloatingEegVisibleChange = { visible ->
                                        floatingEegVisible = visible
                                        AppUiSettings.writeFloatingEegVisible(applicationContext, visible)
                                        feedback.toast(if (visible) "悬浮波形窗口已显示" else "悬浮波形窗口已隐藏")
                                    },
                                    onSaveAssistantConfig = { connection ->
                                        AssistantCareStore.save(applicationContext, connection)
                                    },
                                    onAssistantDraftChange = AssistantCareStore::setDebugDraft,
                                    onAssistantNotice = { message, ok -> feedback.toast(message, ok) },
                                    onDebugNoContactsChange = { enabled ->
                                        debugNoContacts = enabled
                                        chat.setFakeNoContacts(enabled)
                                        feedback.toast(if (enabled) "已模拟：还没有绑定家属" else "已恢复示例联系人")
                                        if (destination.value == AppDestination.COMMUNICATION) {
                                            toggleCareSheet(false)
                                        }
                                    },
                                    onDebugIncomingCall = {
                                        debugCommand("fake incoming")
                                    },
                                    onOpenDeviceAndModel = {
                                        toggleCareSheet(false)
                                        navigateTo(AppDestination.SETTINGS)
                                    }
                                )
                            }
                        ) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalActionHints provides { pageHints = it }) {
                                AppContent(vm = vm, chat = chat, bleState = bleState)
                            }
                        }
                    } else AppContent(vm = vm, chat = chat, bleState = bleState)
                    FloatingEegSignalWindow(
                        bleManager = bleManager,
                        visible = floatingEegVisible && call == null && !careOpen &&
                            destination.value !in caregiverPages
                    )
                    FloatingControlBall(
                        visible = floatingBallVisible.value && !careOpen,
                        onLeftLook = { routeSignal(ControlSignal.LEFT_LOOK) },
                        onRightLook = { routeSignal(ControlSignal.RIGHT_LOOK) },
                        onBite = { routeSignal(ControlSignal.BITE) }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppContent(vm: InputMethodViewModel, chat: CommunicationViewModel, bleState: NaoyunBleManager.State) {
        val assistantState = rememberSaveableStateHolder()
        val inputState by vm.state.collectAsState()
        if (destination.value != AppDestination.HOME &&
            destination.value != AppDestination.DEVICE_STATUS
        ) {
            BackHandler {
                if (destination.value == AppDestination.INPUT_METHOD) {
                    inputViewModel?.handleBackPress()
                    return@BackHandler
                }
                if (destination.value == AppDestination.COMMUNICATION) {
                    if (!chat.back()) navigateTo(AppDestination.HOME)
                    return@BackHandler
                }
                val parent = when (destination.value) {
                    AppDestination.ENTERTAINMENT -> AppDestination.HOME
                    AppDestination.ASYNC_MAZE,
                    AppDestination.SNAKE_CLIMB,
                    AppDestination.WIZARD_GAME,
                    AppDestination.CHINESE_CHESS,
                    AppDestination.DOUDIZHU,
                    AppDestination.MAHJONG -> AppDestination.ENTERTAINMENT
                    AppDestination.TV, AppDestination.MUSIC -> mediaParent()
                    else -> AppDestination.HOME
                }
                navigateTo(parent)
            }
        }

        when (destination.value) {
            AppDestination.HOME -> HomeScreen(
                selection = HomeSelectionState(cursor.index, cursor.direction),
                scanMode = scanMode.value,
                scanIntervalMs = inputState.scanIntervalMs,
                dwellRevision = dwellRevision,
                paused = careOpen || nurseCall != null,
                frozen = frozen,
                onSelect = { module ->
                    cursor = cursor.reset(newSize = homeModules.size, initial = homeModules.indexOf(module))
                    enterModule(module)
                }
            )

            AppDestination.ENTERTAINMENT -> EntertainmentScreen(
                selection = entertainmentSelection.value,
                scanMode = scanMode.value,
                scanIntervalMs = inputState.scanIntervalMs,
                onSelect = { module ->
                    entertainmentSelection.value = entertainmentSelection.value.copy(
                        selectedIndex = EntertainmentHubModule.entries.indexOf(module)
                    )
                    enterEntertainmentModule(module)
                },
                onAdvance = {
                    entertainmentSelection.value = entertainmentSelection.value.advance()
                }
            )

            AppDestination.INPUT_METHOD -> InputMethodPage(vm)
            AppDestination.COMMUNICATION -> {
                val videoState by chat.video.state.collectAsState()
                androidx.compose.runtime.DisposableEffect(vm) {
                    onDispose { vm.deactivate() }
                }
                CommunicationScreen(
                    chat = chat,
                    input = vm,
                    paused = careOpen || nurseCall != null || videoState.call != null,
                    frozen = frozen,
                    onToast = { message, ok -> feedback.toast(message, ok) },
                    onOpenTalk = ::openTalkComposer,
                    onHome = { navigateTo(AppDestination.HOME) }
                )
            }

            AppDestination.LIFE_ASSISTANT -> assistantState.SaveableStateProvider("life_assistant") {
                AssistantScreen(
                    scanIntervalMs = inputState.scanIntervalMs,
                    scanMode = scanMode.value,
                    paused = careOpen || nurseCall != null,
                    frozen = frozen,
                    onToast = { message, ok -> feedback.toast(message, ok) },
                    onEditingChange = { assistantEditing = it },
                    onBack = {
                        assistantEditing = false
                        navigateTo(AppDestination.HOME)
                    },
                    onOpenMedia = { target, id ->
                        assistantEditing = false
                        assistantMediaId = id
                        navigateTo(target)
                    }
                )
            }

            AppDestination.WIZARD_GAME -> AuroraBackground {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("魔法师游戏运行中…", style = MaterialTheme.typography.headlineMedium)
                }
            }

            AppDestination.CHINESE_CHESS -> ChineseChessScreen(inputState.scanIntervalMs) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.DOUDIZHU -> DoudizhuScreen(inputState.scanIntervalMs) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.MAHJONG -> MahjongScreen(inputState.scanIntervalMs) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.SNAKE_CLIMB -> SnakeClimbScreen(
                state = snakeState.value,
                moveIntervalMs = inputState.scanIntervalMs,
                onAction = ::applySnakeAction,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.ASYNC_MAZE -> MazeScreen(
                state = mazeState.value,
                controlStatus = bciStatus.value,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) },
                onAction = ::applyMazeAction,
                onExitDecision = ::resolveMazeExit,
                onReset = { resetMazeForActiveModel() }
            )

            AppDestination.TV -> TvScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                onBack = { navigateTo(mediaParent()) },
                initialChannelId = assistantMediaId
            )

            AppDestination.MUSIC -> MusicScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                onBack = { navigateTo(mediaParent()) },
                initialTrackId = assistantMediaId
            )

            AppDestination.APP_SETTINGS -> AppSettingsScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                onScanModeChange = { mode ->
                    scanMode.value = ScanSettings.writeMode(applicationContext, mode)
                },
                floatingBallVisible = floatingBallVisible.value,
                headsetControlEnabled = headsetControlEnabled.value,
                onFloatingBallVisibleChange = { visible ->
                    floatingBallVisible.value = visible
                    AppUiSettings.writeFloatingBallVisible(applicationContext, visible)
                },
                onHeadsetControlEnabledChange = ::setHeadsetControlEnabled,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.SETTINGS -> CollectionScreen(
                bleManager = bleManager,
                scanIntervalMs = inputState.scanIntervalMs,
                onScanIntervalChange = vm::setScanInterval,
                onTrainingActivated = {
                    restartBciController()
                    navigateTo(AppDestination.HOME)
                },
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.DEVICE_STATUS, AppDestination.COLLECTION -> DeviceFlow(
                bleManager = bleManager,
                onBackHome = { navigateTo(AppDestination.HOME) },
                onCollectionFinished = { restartBciController() }
            )
        }
    }

    @Composable
    private fun InputMethodPage(vm: InputMethodViewModel) {
        val state by vm.state.collectAsState()
        val phrases = remember(state.mode, state.phase, state.dwellRevision) { vm.rankedCommonPhrases(state.mode) }
        ComposerScreen(
            state = state,
            phrases = phrases,
            paused = careOpen || nurseCall != null || speakOverlay != null || confirmOverlay != null,
            frozen = frozen,
            compactWings = false,
            onBlockClick = vm::selectBlockByTouch,
            onPinyinClick = vm::selectPinyinByTouch,
            onPinyinNavigationClick = vm::selectPinyinNavigationByTouch,
            onCharacterClick = vm::selectCharacterByTouch,
            onCharacterItemClick = vm::selectCharacterItemByTouch,
            onCommonPhraseClick = vm::selectCommonPhraseByTouch,
            onPredictionClick = vm::selectPredictionByTouch,
            onInitialPredictionClick = vm::selectInitialPredictionByTouch
        )
    }

    private fun openTalkComposer() {
        speakOverlay = null
        confirmOverlay = null
        nurseCall = null
        nurseCallAlarm.stop()
        toggleCareSheet(false)
        navigateTo(AppDestination.INPUT_METHOD)
        inputViewModel?.openMode(
            mode = InputMode.Talk,
            onSend = ::openSpeakOverlay,
            onBack = {
                inputViewModel?.deactivate()
                navigateTo(AppDestination.COMMUNICATION)
                communicationViewModel?.enterContactList(focusRow = ContactSelectionPolicy.SELF_INDEX)
            }
        )
    }

    private fun openSpeakOverlay(text: String) {
        if (text.isBlank()) {
            if (!frozen) feedback.toast("还没有要说的话")
            return
        }
        speakOverlay = SpeakOverlayState(text = text)
        dwellRevision++
        if (!frozen) {
            speechOutput.speak(text) { feedback.toast("朗读不可用") }
        }
    }

    private fun dismissSpeakOverlay() {
        speakOverlay = null
        speechOutput.stop()
        dwellRevision++
    }

    private fun dismissConfirmOverlay() {
        confirmOverlay = null
        dwellRevision++
    }

    private fun advanceSpeakOverlay() {
        val current = speakOverlay ?: return
        speakOverlay = current.copy(cursor = current.cursor.advance(SystemClock.elapsedRealtime(), scanMode.value))
    }

    private fun advanceConfirmOverlay() {
        val (spec, currentCursor) = confirmOverlay ?: return
        val next = currentCursor.advance(SystemClock.elapsedRealtime(), scanMode.value)
        if (next.timedOut(confirmation = true)) {
            confirmOverlay = null
            if (!frozen) feedback.toast("三轮都没有选择，已按「${spec.cancelLabel}」处理")
            spec.onCancel()
            dwellRevision++
        } else {
            confirmOverlay = spec to next
        }
    }

    private fun chooseSpeakOption(index: Int) {
        val current = speakOverlay ?: return
        when (index) {
            0 -> dismissSpeakOverlay()
            1 -> {
                if (!frozen) {
                    speechOutput.speak(current.text) { feedback.toast("朗读不可用") }
                    feedback.toast("再说一遍")
                }
                dwellRevision++
            }
            2 -> {
                val spec = ConfirmOverlaySpec(
                    title = "清空这句话？",
                    description = "清空后不能恢复。",
                    cancelLabel = "取消",
                    cancelSub = "保留这句话",
                    confirmLabel = "清空",
                    confirmSub = "开始新的一句",
                    onConfirm = {
                        confirmOverlay = null
                        speakOverlay = null
                        speechOutput.stop()
                        inputViewModel?.clearOutputAndReset()
                        if (!frozen) feedback.toast("已清空", true)
                        dwellRevision++
                    },
                    onCancel = {
                        confirmOverlay = null
                        dwellRevision++
                    }
                )
                confirmOverlay = spec to ScanCursor(size = 2, index = 0, direction = 1)
                dwellRevision++
            }
        }
    }

    private fun chooseConfirmOption(index: Int) {
        val (spec, _) = confirmOverlay ?: return
        if (index == 1) spec.onConfirm() else spec.onCancel()
    }

    private fun enterModule(module: HomeModule) {
        if (module == HomeModule.CALL_NURSE) {
            bciController?.invalidatePendingCommands()
            nurseCall = NurseCallState()
            return
        }
        navigateTo(module.resolveDestination(BuildConfig.DEMO_COMMUNICATION))
    }

    private fun dismissNurseCall() {
        val wasSent = nurseCall?.sent == true
        nurseCall = null
        nurseCallAlarm.stop()
        if (!wasSent && !frozen) feedback.toast("已取消呼叫")
        dwellRevision++
    }

    private fun toggleCareSheet(open: Boolean) {
        if (careOpen == open) return
        careOpen = open
        bciController?.invalidatePendingCommands()
        updateBciCommandDelivery()
        if (!open) dwellRevision++
    }

    private fun buildCaregiverInfo(
        bleState: NaoyunBleManager.State,
        connectedDevice: NaoyunBleManager.ConnectedDevice?,
        telemetry: NaoyunBleManager.DeviceTelemetry?
    ): CaregiverInfo {
        val connected = bleState in setOf(
            NaoyunBleManager.State.READY,
            NaoyunBleManager.State.STREAM_STALLED,
            NaoyunBleManager.State.RECOVERING
        )
        val deviceName = connectedDevice?.name ?: "Naoyun Pods BLE"
        val deviceSummary = when (bleState) {
            NaoyunBleManager.State.READY -> "$deviceName · 已连接"
            NaoyunBleManager.State.STREAM_STALLED, NaoyunBleManager.State.RECOVERING -> "$deviceName · 信号中断"
            NaoyunBleManager.State.CONNECTING, NaoyunBleManager.State.INITIALIZING -> "正在连接…"
            NaoyunBleManager.State.SCANNING -> "正在扫描…"
            else -> "未连接"
        }
        val batteryWearingSummary = if (connected && telemetry != null) {
            val worn = if (telemetry.leftWorn && telemetry.rightWorn) "佩戴正常" else "请检查佩戴"
            "L ${telemetry.leftBatteryPercent}% R ${telemetry.rightBatteryPercent}% · $worn"
        } else {
            "L -- R -- · 未连接"
        }
        val repository = PersonalizationRepository(applicationContext)
        val activeUserId = repository.activeUserId()
        val activeModel = activeUserId?.let { UserModelManager.activeModel(applicationContext, it) }
        val modelSummary = when {
            activeModel != null -> {
                val proto = if (activeModel.protocol == ClassificationProtocol.SIX_ACTION) "六分类" else "四分类"
                "个性化 ${activeModel.modelKey} $proto"
            }
            activeBciController?.loadedProtocol == ClassificationProtocol.SIX_ACTION -> "内置六分类模型"
            else -> "内置四分类模型"
        }
        val host = BuildConfig.BACKNET_URL.toHttpUrlOrNull()?.host
            ?: BuildConfig.BACKNET_URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val commSuffix = when {
            BuildConfig.DEMO_COMMUNICATION -> "演示模式"
            repository.deviceServerBinding() != null -> "已绑定"
            else -> "未配置"
        }
        return CaregiverInfo(
            deviceSummary = deviceSummary,
            batteryWearingSummary = batteryWearingSummary,
            modelSummary = modelSummary,
            communicationSummary = "$host · $commSuffix"
        )
    }

    private fun mediaParent(): AppDestination =
        if (assistantMediaId == null) AppDestination.ENTERTAINMENT else AppDestination.LIFE_ASSISTANT

    private fun applySnakeAction(action: SnakeAction) {
        val next = SnakeClimbGame.applyAction(snakeState.value, action)
        snakeState.value = next
        if (next.exitRequested) navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun enterEntertainmentModule(module: EntertainmentHubModule) {
        when (module) {
            EntertainmentHubModule.MAZE -> {
                resetMazeForActiveModel()
                if (bleManager.state.value == NaoyunBleManager.State.READY) restartBciController()
                navigateTo(AppDestination.ASYNC_MAZE)
            }
            EntertainmentHubModule.SNAKE_CLIMB -> {
                snakeState.value = SnakeClimbGame.create()
                navigateTo(AppDestination.SNAKE_CLIMB)
            }
            EntertainmentHubModule.WIZARD_GAME -> {
                navigateTo(AppDestination.WIZARD_GAME)
                wizardGameLauncher.launch(Intent(this, WizardGameActivity::class.java))
            }
            EntertainmentHubModule.CHINESE_CHESS -> navigateTo(AppDestination.CHINESE_CHESS)
            EntertainmentHubModule.DOUDIZHU -> navigateTo(AppDestination.DOUDIZHU)
            EntertainmentHubModule.MAHJONG -> navigateTo(AppDestination.MAHJONG)
            EntertainmentHubModule.TELEVISION -> navigateTo(AppDestination.TV)
            EntertainmentHubModule.MUSIC -> navigateTo(AppDestination.MUSIC)
            EntertainmentHubModule.BACK -> navigateTo(AppDestination.HOME)
        }
    }

    private fun navigateTo(target: AppDestination) {
        if (target != AppDestination.MUSIC && target != AppDestination.TV) assistantMediaId = null
        if (target != AppDestination.LIFE_ASSISTANT) assistantEditing = false
        if (target != AppDestination.HOME) {
            nurseCall = null
            nurseCallAlarm.stop()
        }
        if (target != AppDestination.INPUT_METHOD) {
            speakOverlay = null
            confirmOverlay = null
            speechOutput.stop()
        }
        bciController?.invalidatePendingCommands()
        val previous = destination.value
        val homeFocus = if (target == AppDestination.HOME) {
            when (previous) {
                AppDestination.COMMUNICATION, AppDestination.INPUT_METHOD -> HomeModule.COMMUNICATION.ordinal
                AppDestination.LIFE_ASSISTANT -> HomeModule.LIFE_ASSISTANT.ordinal
                AppDestination.ENTERTAINMENT -> HomeModule.ENTERTAINMENT.ordinal
                AppDestination.APP_SETTINGS -> HomeModule.APP_SETTINGS.ordinal
                else -> cursor.index
            }
        } else {
            cursor.index
        }
        cursor = cursor.reset(newSize = homeModules.size, initial = homeFocus)
        destination.value = target
        if (target != AppDestination.INPUT_METHOD && target != AppDestination.COMMUNICATION) {
            inputViewModel?.deactivate()
        }
        if (target == AppDestination.COMMUNICATION && previous != AppDestination.INPUT_METHOD && previous != AppDestination.COMMUNICATION) {
            communicationViewModel?.enterContactList()
        }
        updateBciCommandDelivery()
    }

    private fun advanceHome() {
        cursor = cursor.advance(SystemClock.elapsedRealtime(), scanMode.value)
    }

    private fun advanceVideoCursor() {
        val video = communicationViewModel?.video ?: return
        val size = video.actions(::askVideoPermissions).size.coerceAtLeast(1)
        val synced = if (videoCursor.size != size) videoCursor.reset(newSize = size) else videoCursor
        videoCursor = synced.advance(SystemClock.elapsedRealtime(), scanMode.value)
    }

    private fun advanceVideoConfirm() {
        val video = communicationViewModel?.video ?: return
        val next = videoConfirmCursor.advance(SystemClock.elapsedRealtime(), scanMode.value)
        if (next.timedOut(confirmation = true)) {
            video.cancelEndConfirm()
            if (!frozen) feedback.toast("三轮都没有选择，已按「继续通话」处理")
            dwellRevision++
        } else {
            videoConfirmCursor = next
        }
    }

    private fun clearTransientOverlaysForNav() {
        nurseCall = null
        nurseCallAlarm.stop()
        speakOverlay = null
        confirmOverlay = null
        speechOutput.stop()
        communicationViewModel?.video?.clear()
        hadActiveCall = false
        toggleCareSheet(false)
    }

    private fun debugCommand(cmd: String): String {
        when (cmd) {
            "nav home" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.HOME)
                cursor = cursor.reset(newSize = homeModules.size, initial = 0)
                dwellRevision++
            }
            "nav contacts" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.COMMUNICATION)
                communicationViewModel?.enterContactList()
                dwellRevision++
            }
            "nav cact" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.COMMUNICATION)
                communicationViewModel?.debugOpenFirstContactActions()
                dwellRevision++
            }
            "nav chat" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.COMMUNICATION)
                communicationViewModel?.debugOpenFirstContactChat()
                dwellRevision++
            }
            "nav invite" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.COMMUNICATION)
                communicationViewModel?.debugOpenInvite()
                dwellRevision++
            }
            "nav talk" -> {
                communicationViewModel?.video?.clear()
                hadActiveCall = false
                openTalkComposer()
            }
            "nav asst" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.LIFE_ASSISTANT)
            }
            "fake contacts" -> {
                communicationViewModel?.injectFakeContacts()
                dwellRevision++
            }
            "fake incoming" -> {
                toggleCareSheet(false)
                communicationViewModel?.injectFakeIncomingCall()
                dwellRevision++
            }
            "care on", "care off" -> toggleCareSheet(cmd.endsWith("on"))
            "freeze on", "freeze off" -> {
                frozen = cmd.endsWith("on")
                if (frozen) nurseCallAlarm.stop()
                cursor = cursor.reset()
                dwellRevision++
            }
            "scan auto", "scan manual" -> { scanMode.value = if (cmd.endsWith("auto")) ScanMode.AUTO else ScanMode.MANUAL; cursor = cursor.reset() }
            "mode auto", "mode light", "mode dark" -> displayMode = AppUiSettings.DisplayMode.valueOf(cmd.substringAfter(' ').uppercase())
            "signal L" -> if (foreground) routeSignal(ControlSignal.LEFT_LOOK) else return "inactive"
            "signal R" -> if (foreground) routeSignal(ControlSignal.RIGHT_LOOK) else return "inactive"
            "signal B" -> if (foreground) routeSignal(ControlSignal.BITE) else return "inactive"
            "tick" -> {
                val videoState = communicationViewModel?.video?.state?.value
                when {
                    careOpen -> return "pending: page cursor"
                    videoState?.call != null && videoState.confirmEnd -> advanceVideoConfirm()
                    videoState?.call != null -> advanceVideoCursor()
                    confirmOverlay != null -> advanceConfirmOverlay()
                    speakOverlay != null -> advanceSpeakOverlay()
                    nurseCall != null -> return "pending: page cursor"
                    destination.value == AppDestination.HOME -> advanceHome()
                    destination.value == AppDestination.INPUT_METHOD -> inputViewModel?.debugTick()
                    destination.value == AppDestination.COMMUNICATION -> {
                        val chat = communicationViewModel
                        if (chat?.state?.value?.stage == CommunicationStage.CHAT_INPUT) {
                            inputViewModel?.debugTick()
                        } else {
                            chat?.debugTick()
                        }
                    }
                    destination.value == AppDestination.LIFE_ASSISTANT -> AssistantInputBus.debugTick()
                    else -> return "pending: page cursor"
                }
            }
            else -> return "pending: page integration"
        }
        return "ok"
    }

    private fun setHeadsetControlEnabled(enabled: Boolean) {
        headsetControlEnabled.value = enabled
        updateBciCommandDelivery()
        if (enabled && bciActive.value) {
            bciStatus.value = if (bciController?.loadedProtocol == ClassificationProtocol.SIX_ACTION) {
                "六分类异步控制运行中"
            } else {
                "四分类异步控制运行中"
            }
        } else if (!enabled && bleManager.state.value == NaoyunBleManager.State.READY) {
            bciStatus.value = "耳机已连接 · 控制已停用"
        }
    }

    private fun updateBciCommandDelivery() {
        val targetAcceptsCommands = communicationViewModel?.video?.state?.value?.call != null ||
            (destination.value != AppDestination.DEVICE_STATUS &&
                destination.value != AppDestination.COLLECTION && destination.value != AppDestination.SETTINGS)
        bciController?.setCommandDeliveryEnabled(
            foreground && !careOpen && headsetControlEnabled.value && targetAcceptsCommands
        )
    }

    private fun routeHeadsetSignal(signal: ControlSignal) {
        val inCall = communicationViewModel?.video?.state?.value?.call != null
        if (!careOpen && headsetControlEnabled.value && (inCall || (destination.value != AppDestination.DEVICE_STATUS &&
                destination.value != AppDestination.COLLECTION && destination.value != AppDestination.SETTINGS))
        ) routeSignal(signal)
    }

    private fun routeSignal(signal: ControlSignal) {
        if (careOpen) {
            if (!frozen) feedback.toast("照护面板打开中，患者指令已暂停")
            return
        }
        if (!frozen && destination.value !in caregiverPages) feedback.emit(signal, statusPath.joinToString(" › "), feedbackSound)
        if (destination.value == AppDestination.WIZARD_GAME) {
            WizardGameInputBus.dispatch(signal)
            return
        }
        val video = communicationViewModel?.video
        if (video?.state?.value?.call != null) {
            val vState = video.state.value
            if (vState.confirmEnd) {
                when (signal) {
                    ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                        val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                        videoConfirmCursor = videoConfirmCursor.look(step, scanMode.value, hold = true)
                        dwellRevision++
                    }
                    ControlSignal.BITE -> {
                        val chosen = videoConfirmCursor.selected(
                            SystemClock.elapsedRealtime(),
                            if (frozen) 0L else AppUiSettings.graceMs(this),
                            scanMode.value
                        )
                        if (chosen != videoConfirmCursor.index && !frozen) {
                            feedback.toast("已按上一项执行（延迟补偿）")
                        }
                        if (chosen == 1) video.confirmEndCall(frozen)
                        else {
                            video.cancelEndConfirm()
                            dwellRevision++
                        }
                    }
                    else -> Unit
                }
                return
            }
            val actions = video.actions(::askVideoPermissions)
            if (actions.isEmpty() || vState.pending) return
            val synced = if (videoCursor.size != actions.size) videoCursor.reset(newSize = actions.size) else videoCursor
            when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    videoCursor = synced.look(step, scanMode.value)
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val chosen = synced.selected(
                        SystemClock.elapsedRealtime(),
                        if (frozen) 0L else AppUiSettings.graceMs(this),
                        scanMode.value
                    )
                    if (chosen != synced.index && !frozen) {
                        feedback.toast("已按上一项执行（延迟补偿）")
                    }
                    actions[chosen.coerceIn(0, actions.lastIndex)].invoke()
                }
                else -> Unit
            }
            return
        }
        val activeConfirm = confirmOverlay
        if (activeConfirm != null) {
            val (spec, confirmCursor) = activeConfirm
            when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    confirmOverlay = spec to confirmCursor.look(step, scanMode.value, hold = true)
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val chosen = confirmCursor.selected(
                        SystemClock.elapsedRealtime(),
                        if (frozen) 0 else AppUiSettings.graceMs(this),
                        scanMode.value
                    )
                    if (chosen != confirmCursor.index && !frozen) {
                        feedback.toast("已按上一项执行（延迟补偿）")
                    }
                    chooseConfirmOption(chosen)
                }
                else -> Unit
            }
            return
        }
        val activeSpeak = speakOverlay
        if (activeSpeak != null) {
            when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    speakOverlay = activeSpeak.copy(cursor = activeSpeak.cursor.look(step, scanMode.value))
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val chosen = activeSpeak.cursor.selected(
                        SystemClock.elapsedRealtime(),
                        if (frozen) 0 else AppUiSettings.graceMs(this),
                        scanMode.value
                    )
                    if (chosen != activeSpeak.cursor.index && !frozen) {
                        feedback.toast("已按上一项执行（延迟补偿）")
                    }
                    chooseSpeakOption(chosen)
                }
                else -> Unit
            }
            return
        }
        val activeNurseCall = nurseCall
        if (activeNurseCall != null) {
            when (val action = activeNurseCall.onSignal(signal)) {
                NurseCallState.Action.Stay -> Unit
                NurseCallState.Action.Cancel -> {
                    nurseCall = null
                    nurseCallAlarm.stop()
                    if (!frozen) feedback.toast("已取消呼叫")
                    dwellRevision++
                }
                is NurseCallState.Action.Trigger -> {
                    nurseCall = action.state
                    if (!frozen) nurseCallAlarm.start()
                }
                NurseCallState.Action.Dismiss -> dismissNurseCall()
            }
            return
        }
        when (destination.value) {
            AppDestination.HOME -> when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    cursor = cursor.look(if (signal == ControlSignal.LEFT_LOOK) -1 else 1, scanMode.value)
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val selected = cursor.selected(SystemClock.elapsedRealtime(), if (frozen) 0 else AppUiSettings.graceMs(this), scanMode.value)
                    if (selected != cursor.index) {
                        cursor = cursor.copy(index = selected)
                        feedback.toast("已按上一项执行（延迟补偿）")
                    }
                    enterModule(homeModules[selected])
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.ENTERTAINMENT -> when (signal) {
                ControlSignal.LEFT_LOOK -> entertainmentSelection.value =
                    entertainmentSelection.value.onDirection(-1, scanMode.value)
                ControlSignal.RIGHT_LOOK -> entertainmentSelection.value =
                    entertainmentSelection.value.onDirection(1, scanMode.value)
                ControlSignal.BITE -> enterEntertainmentModule(
                    entertainmentSelection.value.selectedModule
                )
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.INPUT_METHOD -> inputViewModel?.handleSignal(signal)
            AppDestination.LIFE_ASSISTANT -> AssistantInputBus.dispatch(signal)
            AppDestination.WIZARD_GAME -> Unit
            AppDestination.CHINESE_CHESS -> ChineseChessInputBus.dispatch(signal)
            AppDestination.DOUDIZHU -> DoudizhuInputBus.dispatch(signal)
            AppDestination.MAHJONG -> MahjongInputBus.dispatch(signal)
            AppDestination.SNAKE_CLIMB -> when (signal) {
                ControlSignal.LEFT_LOOK -> applySnakeAction(SnakeAction.LOOK_LEFT)
                ControlSignal.RIGHT_LOOK -> applySnakeAction(SnakeAction.LOOK_RIGHT)
                ControlSignal.BITE -> applySnakeAction(SnakeAction.BITE)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.COMMUNICATION -> {
                val chat = communicationViewModel ?: return
                if (chat.state.value.stage == CommunicationStage.CHAT_INPUT) {
                    if (signal in setOf(ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK, ControlSignal.BITE)) {
                        inputViewModel?.handleSignal(signal)
                    }
                } else {
                    when (chat.onSignal(signal)) {
                        CommunicationSignalOutcome.OPEN_TALK -> openTalkComposer()
                        CommunicationSignalOutcome.EXIT_HOME -> navigateTo(AppDestination.HOME)
                        CommunicationSignalOutcome.HANDLED -> Unit
                    }
                }
            }
            AppDestination.ASYNC_MAZE -> {
                val action = when (signal) {
                    ControlSignal.LEFT_LOOK -> MazeAction.LOOK_LEFT
                    ControlSignal.RIGHT_LOOK -> MazeAction.LOOK_RIGHT
                    ControlSignal.BITE -> MazeAction.BITE
                    ControlSignal.LEFT_RIGHT -> MazeAction.LEFT_RIGHT
                    ControlSignal.RIGHT_LEFT -> MazeAction.RIGHT_LEFT
                }
                applyMazeAction(action)
            }
            AppDestination.TV -> {
                TvInputBus.dispatch(signal)
            }
            AppDestination.MUSIC -> {
                MusicInputBus.dispatch(signal)
            }
            AppDestination.APP_SETTINGS -> {
                AppSettingsInputBus.dispatch(signal)
            }
            AppDestination.SETTINGS -> Unit
            AppDestination.DEVICE_STATUS, AppDestination.COLLECTION -> Unit
        }
    }

    private fun startBciController() {
        stopBciController()
        attemptedModelIdentity = activeModelIdentity()
        val controller = BciController(applicationContext, bleManager, ::routeHeadsetSignal)
        bciController = controller
        activeBciController = controller
        updateBciCommandDelivery()
        if (!controller.loadModel()) {
            bciStatus.value = "耳机已连接，控制模型不可用"
            controller.stop()
            bciController = null
            activeBciController = null
            bciActive.value = false
            headsetControlEnabled.value = false
            return
        }
        controller.start()
        bciActive.value = true
        val protocol = controller.loadedProtocol ?: ClassificationProtocol.FOUR_CLASS
        bciStatus.value = if (!headsetControlEnabled.value) {
            "耳机已连接 · 等待手动启用控制"
        } else if (protocol == ClassificationProtocol.SIX_ACTION) {
            "六分类异步控制运行中"
        } else {
            "四分类异步控制运行中"
        }
    }

    private fun restartBciController() {
        if (bleManager.state.value == NaoyunBleManager.State.READY) {
            startBciController()
            if (destination.value == AppDestination.ASYNC_MAZE) resetMazeForActiveModel()
        }
    }

    private fun stopBciController() {
        bciController?.stop()
        bciController = null
        activeBciController = null
        bciActive.value = false
        if (bleManager.state.value != NaoyunBleManager.State.READY) {
            bciStatus.value = "耳机未连接"
        }
    }

    private fun resetMazeForActiveModel() {
        val protocol = if (bciController?.loadedProtocol == ClassificationProtocol.SIX_ACTION) {
            MazeProtocol.SIX_ACTION
        } else {
            MazeProtocol.FOUR_CLASS
        }
        mazeState.value = MazeGame.create(protocol)
    }

    private fun applyMazeAction(action: MazeAction) {
        val nextState = MazeGame.applyAction(mazeState.value, action)
        mazeState.value = nextState
        if (nextState.exitConfirmed) navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun resolveMazeExit(choice: MazeExitChoice) {
        val nextState = MazeGame.resolveExitDialog(mazeState.value, choice)
        mazeState.value = nextState
        if (nextState.exitConfirmed) navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun activeModelIdentity(): String {
        val repository = PersonalizationRepository(applicationContext)
        val userId = repository.activeUserId() ?: return "builtin-four-class"
        val active = UserModelManager.activeModel(applicationContext, userId)
            ?: return "builtin-four-class"
        return "$userId:${active.modelFile}:${active.protocol.wireName}:${active.labelNames.joinToString(",")}"
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        bluetoothPermissionLauncher.launch(permissions)
    }

    private fun deviceStatusText(state: NaoyunBleManager.State): String = when (state) {
        NaoyunBleManager.State.READY -> when {
            bciActive.value && headsetControlEnabled.value -> "耳机已连接 · 控制已启用"
            bciActive.value -> "耳机已连接 · 等待手动启用控制"
            else -> "耳机已连接"
        }
        NaoyunBleManager.State.SCANNING -> "正在扫描耳机"
        NaoyunBleManager.State.CONNECTING, NaoyunBleManager.State.INITIALIZING -> "正在连接耳机"
        NaoyunBleManager.State.STREAM_STALLED -> "耳机数据流中断"
        NaoyunBleManager.State.RECOVERING -> "正在自动恢复耳机数据流"
        NaoyunBleManager.State.ERROR -> "耳机连接异常"
        else -> "耳机未连接"
    }

    override fun onPause() {
        foreground = false
        bciController?.setCommandDeliveryEnabled(false)
        bciController?.invalidatePendingCommands()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateBciCommandDelivery()
        dwellRevision++
    }

    override fun onDestroy() {
        unregisterDebug()
        nurseCallAlarm.release()
        speechOutput.release()
        feedback.release()
        FloatingEegSignalSource.detach(bleManager)
        communicationViewModel?.requestVideoPermissions = {}
        stopBciController()
        bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
private fun DeviceFlow(
    bleManager: NaoyunBleManager,
    onBackHome: () -> Unit,
    onCollectionFinished: () -> Unit
) {
    val bleState by bleManager.state.collectAsState()
    var screen by remember {
        mutableStateOf(
            if (bleState in setOf(
                    NaoyunBleManager.State.READY,
                    NaoyunBleManager.State.STREAM_STALLED,
                    NaoyunBleManager.State.RECOVERING
                )
            ) "monitor" else "scan"
        )
    }

    LaunchedEffect(bleState) {
        if (bleState == NaoyunBleManager.State.READY) screen = "monitor"
        if (bleState == NaoyunBleManager.State.DISCONNECTED || bleState == NaoyunBleManager.State.ERROR) {
            screen = "scan"
        }
    }

    BackHandler {
        if (screen == "collect") {
            screen = "monitor"
            onCollectionFinished()
        } else {
            onBackHome()
        }
    }

    when (screen) {
        "scan" -> BleScanScreen(
            bleManager = bleManager,
            onConnected = { screen = "monitor" },
            onBack = onBackHome
        )
        "monitor" -> SignalMonitorScreen(
            bleManager = bleManager,
            onBack = onBackHome,
            onStartCollect = { screen = "collect" },
            onDisconnect = {
                bleManager.disconnect()
                screen = "scan"
            }
        )
        "collect" -> CollectionScreen(
            bleManager = bleManager,
            onBack = {
                screen = "monitor"
                onCollectionFinished()
            }
        )
    }
}
