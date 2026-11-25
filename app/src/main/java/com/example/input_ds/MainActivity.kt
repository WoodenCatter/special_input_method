package com.example.input_ds

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.roundToInt
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.example.input_ds.news.NewsInputBus
import com.example.input_ds.ui.news.NewsBriefingScreen
import com.example.input_ds.settings.AppSettingsInputBus
import com.example.input_ds.settings.SpeedPracticeInputBus
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.TrainingWorkScheduler
import com.example.input_ds.personalization.UserModelManager
import com.example.input_ds.ui.bci.BleScanScreen
import com.example.input_ds.ui.bci.CollectionScreen
import com.example.input_ds.ui.bci.SignalMonitorScreen
import com.example.input_ds.ui.calibration.SpeedPracticeScreen
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.input_ds.chess.ChineseChessInputBus
import com.example.input_ds.doudizhu.DoudizhuInputBus
import com.example.input_ds.ui.chess.ChineseChessScreen
import com.example.input_ds.mahjong.MahjongInputBus
import com.example.input_ds.ui.doudizhu.DoudizhuScreen
import com.example.input_ds.ui.mahjong.MahjongScreen
import com.example.input_ds.game.SnakeDirection
import com.example.input_ds.game.SnakeAction
import com.example.input_ds.game.SnakeClimbGame
import com.example.input_ds.game.WatermelonAction
import com.example.input_ds.game.WatermelonEngine
import com.example.input_ds.game.WatermelonFocus
import com.example.input_ds.game.WatermelonGame
import com.example.input_ds.game.WatermelonUiState
import com.example.input_ds.ui.assistant.AssistantInputBus
import com.example.input_ds.ui.assistant.AssistantScreen
import com.example.input_ds.ui.components.FloatingEegSignalSource
import com.example.input_ds.ui.components.FloatingEegSignalWindow
import com.example.input_ds.wizard.WizardGameActivity
import com.example.input_ds.wizard.WizardGameInputBus
import com.example.input_ds.ui.game.SnakeClimbScreen
import com.example.input_ds.ui.game.WatermelonScreen
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
import com.example.input_ds.ui.settings.AppSettingItem
import com.example.input_ds.ui.settings.AppSettingsLogic
import com.example.input_ds.ui.settings.AppSettingsScreen
import com.example.input_ds.ui.tv.TvScreen
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.ui.theme.BciBackground
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
    private val watermelonEngine = WatermelonEngine()
    private val watermelonState = mutableStateOf(watermelonEngine.uiState)
    private var watermelonRenderRevision by androidx.compose.runtime.mutableLongStateOf(0L)
    private var assistantMediaId: String? = null
    private val homeModules = HomeModule.entries
    private var cursor by mutableStateOf(ScanCursor(homeModules.size))
    private var dwellRevision by mutableIntStateOf(0)
    private var frozen by mutableStateOf(false)
    private var displayMode by mutableStateOf(AppUiSettings.DisplayMode.AUTO)
    private var nurseCall by mutableStateOf<NurseCallState?>(null)
    private var speakOverlay by mutableStateOf<SpeakOverlayState?>(null)
    private var confirmOverlay by mutableStateOf<Pair<ConfirmOverlaySpec, ScanCursor>?>(null)
    private var adjustOverlay by mutableStateOf<AdjustOverlayState?>(null)
    private var settingsInitialIndex by mutableIntStateOf(AppSettingsLogic.INITIAL_INDEX)
    private var volumePercent by mutableIntStateOf(60)
    private var brightnessPercent by mutableIntStateOf(80)
    private var assistantEditing by mutableStateOf(false)
    private var careOpen by mutableStateOf(false)
    private var feedbackSound by mutableStateOf(true)
    private var floatingEegVisible by mutableStateOf(false)
    private var debugNoContacts by mutableStateOf(false)
    private var debugFakeAssistant by mutableStateOf(false)
    private var bluetoothPermissionRequested = false
    private var statusPath = listOf("主页")
    private val feedback = GazeFeedback()
    private var foreground = false
    private var unregisterDebug: () -> Unit = {}
    private val caregiverPages = setOf(AppDestination.SETTINGS, AppDestination.COLLECTION, AppDestination.DEVICE_STATUS)

    private var entCursor by mutableStateOf(
        ScanCursor(
            size = EntertainmentHubModule.entries.size,
            index = EntertainmentHubModule.MAZE.ordinal,
            direction = 1
        )
    )
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
        refreshHardwareSettings()
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
                val inputCovered = careOpen || nurseCall != null || speakOverlay != null ||
                    confirmOverlay != null || adjustOverlay != null || call != null
                LifecycleResumeEffect(chat, vm, inputCovered) {
                    chat.setPaused(inputCovered)
                    vm.setPaused(inputCovered)
                    onPauseOrDispose {
                        chat.setPaused(true)
                        vm.setPaused(true)
                    }
                }
                LaunchedEffect(call?.id, call?.status, video.connected, video.camera, video.confirmEnd) {
                    if (call != null) {
                        if (!hadActiveCall) resetCursorsForPauseBoundary()
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
                        resetCursorsForPauseBoundary()
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
                LaunchedEffect(confirmOverlay != null, call?.id, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    val active = confirmOverlay
                    if (active != null && call == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            confirmOverlay = confirmOverlay?.let { (spec, c) ->
                                spec to c.copy(previousIndex = -1, advancedAtMs = 0L)
                            }
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
                LaunchedEffect(speakOverlay != null, call?.id, confirmOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (speakOverlay != null && call == null && confirmOverlay == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            speakOverlay = speakOverlay?.let {
                                it.copy(cursor = it.cursor.copy(previousIndex = -1, advancedAtMs = 0L))
                            }
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Speak tick failed", it) }) {
                                advanceSpeakOverlay()
                            }
                        }
                    }
                }
                val adjustPaused = careOpen || call != null || confirmOverlay != null || speakOverlay != null || nurseCall != null
                LaunchedEffect(adjustPaused) {
                    adjustOverlay = adjustOverlay?.let { it.copy(cursor = it.cursor.reset()) }
                }
                LaunchedEffect(adjustOverlay != null, call?.id, confirmOverlay != null, speakOverlay != null, nurseCall != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (adjustOverlay != null && call == null && confirmOverlay == null && speakOverlay == null && nurseCall == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            adjustOverlay = adjustOverlay?.let {
                                it.copy(cursor = it.cursor.copy(previousIndex = -1, advancedAtMs = 0L))
                            }
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Adjust tick failed", it) }) {
                                advanceAdjustOverlay()
                            }
                        }
                    }
                }
                LaunchedEffect(destination.value, call?.id, nurseCall != null, speakOverlay != null, confirmOverlay != null, adjustOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (destination.value == AppDestination.HOME && call == null && nurseCall == null && speakOverlay == null && confirmOverlay == null && adjustOverlay == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            cursor = cursor.copy(previousIndex = -1, advancedAtMs = 0L)
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Home tick failed", it) }) {
                                advanceHome()
                            }
                        }
                    }
                }
                LaunchedEffect(destination.value, call?.id, nurseCall != null, speakOverlay != null, confirmOverlay != null, adjustOverlay != null, careOpen, frozen, scanMode.value, dwellRevision, state.scanIntervalMs) {
                    if (destination.value == AppDestination.ENTERTAINMENT && call == null && nurseCall == null && speakOverlay == null && confirmOverlay == null && adjustOverlay == null && !careOpen && !frozen && scanMode.value == ScanMode.AUTO) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            entCursor = entCursor.copy(previousIndex = -1, advancedAtMs = 0L)
                            runScanTimer({ state.scanIntervalMs }, { android.util.Log.e("ScanCursor", "Entertainment tick failed", it) }) {
                                advanceEntertainment()
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
                LaunchedEffect(path, state.phase, state.charPage, state.predictionPage, state.initialPage, video.confirmEnd, nurseCall != null, speakOverlay != null, confirmOverlay != null, adjustOverlay != null, careOpen) {
                    bciController?.invalidatePendingCommands()
                }
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
                        val activeAdjust = adjustOverlay
                        val hasTopOverlay = activeNurseCall != null || activeSpeak != null || activeConfirm != null || activeAdjust != null
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
                            activeAdjust != null -> ActionHints.list(
                                AdjustOverlayState.LABELS.getOrElse(activeAdjust.cursor.index) { "" },
                                scanMode.value,
                                activeAdjust.cursor.direction
                            )
                            destination.value == AppDestination.HOME -> ActionHints.list(homeModules[cursor.index].displayName, scanMode.value, cursor.direction)
                            destination.value == AppDestination.ENTERTAINMENT -> ActionHints.list(EntertainmentHubModule.entries[entCursor.index].displayName, scanMode.value, entCursor.direction, true)
                            destination.value == AppDestination.COMMUNICATION -> communicationHints(conversation, state)
                            destination.value == AppDestination.INPUT_METHOD -> inputHints(state)
                            destination.value == AppDestination.SNAKE_CLIMB -> ActionHints("向左转", if (snakeState.value.direction == SnakeDirection.UP) "退出游戏" else "转向上方", "向右转")
                            destination.value == AppDestination.WATERMELON -> {
                                val ws = watermelonState.value
                                when (val f = ws.focus) {
                                    WatermelonFocus.Exit -> ActionHints("移到最右", "离开游戏", "移到最左")
                                    is WatermelonFocus.DropSlot -> ActionHints(
                                        if (f.slot == 0) "离开游戏" else "向左移",
                                        if (!ws.dropReady) "落稳中…" else "放下「${ws.currentTier.label}」",
                                        if (f.slot == WatermelonGame.SLOT_COUNT - 1) "离开游戏" else "向右移"
                                    )
                                }
                            }
                            destination.value == AppDestination.ASYNC_MAZE -> ActionHints("向左", "向上", "向右")
                            destination.value == AppDestination.TV -> ActionHints("上一台", "打开菜单", "下一台")
                            else -> ActionHints("向左轮转", "选择当前项", "向右轮转")
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
                            onCycleDisplayMode = {
                                val next = AppSettingsLogic.cycleDisplayMode(displayMode)
                                displayMode = next
                                AppUiSettings.writeDisplayMode(applicationContext, next)
                                if (!frozen) {
                                    feedback.toast("显示模式：${AppSettingsLogic.displayModeLabel(next)}", true)
                                }
                            },
                            overlay = if (hasTopOverlay) {
                                {
                                    activeAdjust?.let { adjust ->
                                        val adjValueText = when (adjust.kind) {
                                            AdjustOverlayKind.VOLUME -> "${volumePercent}%"
                                            AdjustOverlayKind.BRIGHTNESS -> "${brightnessPercent}%"
                                            AdjustOverlayKind.SPEED -> AppSettingsLogic.formatScanInterval(state.scanIntervalMs)
                                        }
                                        val adjFraction = AppSettingsLogic.adjustFraction(
                                            kind = adjust.kind,
                                            volPercent = volumePercent,
                                            briPercent = brightnessPercent,
                                            intervalMs = state.scanIntervalMs
                                        )
                                        AdjustOverlay(
                                            state = adjust,
                                            valueText = adjValueText,
                                            progressFraction = adjFraction,
                                            scanMode = scanMode.value,
                                            scanIntervalMs = state.scanIntervalMs,
                                            dwellRevision = dwellRevision,
                                            paused = adjustPaused,
                                            frozen = frozen,
                                            onChoose = ::chooseAdjustOption
                                        )
                                    }
                                    activeSpeak?.let { speak ->
                                        SpeakOverlay(
                                            state = speak,
                                            scanMode = scanMode.value,
                                            scanIntervalMs = state.scanIntervalMs,
                                            dwellRevision = dwellRevision,
                                            paused = careOpen || call != null || activeConfirm != null,
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
                                            paused = careOpen || call != null,
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
                                                cancelLabel = "继续通话",
                                                confirmLabel = "挂断",
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
                                    displayMode = displayMode,
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
                                            feedback.toast(if (enabled) "耳机控制已启用" else "耳机控制已停用")
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
                                    onDisplayModeChange = { mode ->
                                        displayMode = mode
                                        AppUiSettings.writeDisplayMode(applicationContext, mode)
                                        if (!frozen) {
                                            feedback.toast("显示模式：${AppSettingsLogic.displayModeLabel(mode)}", true)
                                        }
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
                    // Conditionally mounted so opening an overlay registers on top of OnBackPressedDispatcher (LIFO).
                    androidx.compose.runtime.key(destination.value, call?.id) {
                        if (adjustOverlay != null) {
                            BackHandler(enabled = call == null && !careOpen && confirmOverlay == null && speakOverlay == null && nurseCall == null) { dismissAdjustOverlay() }
                        }
                        if (nurseCall != null) {
                            BackHandler(enabled = call == null && !careOpen && confirmOverlay == null && speakOverlay == null) { dismissNurseCall() }
                        }
                        if (speakOverlay != null) {
                            BackHandler(enabled = call == null && !careOpen && confirmOverlay == null) { dismissSpeakOverlay() }
                        }
                        if (confirmOverlay != null) {
                            BackHandler(enabled = call == null && !careOpen) { dismissConfirmOverlay() }
                        }
                        if (careOpen) {
                            BackHandler { toggleCareSheet(false) }
                        }
                    }
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
        val videoState by chat.video.state.collectAsState()
        val hasActiveCall = videoState.call != null
        if (destination.value != AppDestination.HOME &&
            destination.value != AppDestination.DEVICE_STATUS
        ) {
            BackHandler(enabled = !hasActiveCall && !careOpen && adjustOverlay == null && confirmOverlay == null && speakOverlay == null && nurseCall == null) {
                if (destination.value == AppDestination.INPUT_METHOD ||
                    (destination.value == AppDestination.COMMUNICATION && chat.state.value.stage == CommunicationStage.CHAT_INPUT)
                ) {
                    vm.handleBackPress()
                    return@BackHandler
                }
                if (destination.value == AppDestination.COMMUNICATION) {
                    if (!chat.back()) navigateTo(AppDestination.HOME)
                    return@BackHandler
                }
                if (destination.value == AppDestination.WATERMELON) {
                    applyWatermelonAction(WatermelonAction.REQUEST_EXIT)
                    return@BackHandler
                }
                if (destination.value in setOf(AppDestination.ASYNC_MAZE, AppDestination.SNAKE_CLIMB,
                        AppDestination.CHINESE_CHESS, AppDestination.MAHJONG, AppDestination.DOUDIZHU)) {
                    confirmGameExit()
                    return@BackHandler
                }
                val parent = when (destination.value) {
                    AppDestination.ENTERTAINMENT -> AppDestination.HOME
                    AppDestination.ASYNC_MAZE,
                    AppDestination.SNAKE_CLIMB,
                    AppDestination.WATERMELON,
                    AppDestination.WIZARD_GAME,
                    AppDestination.CHINESE_CHESS,
                    AppDestination.DOUDIZHU,
                    AppDestination.MAHJONG,
                    AppDestination.NEWS_BRIEFING -> AppDestination.ENTERTAINMENT
                    AppDestination.TV, AppDestination.MUSIC -> mediaParent()
                    AppDestination.SPEED_PRACTICE -> AppDestination.APP_SETTINGS
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
                paused = careOpen || nurseCall != null || adjustOverlay != null || hasActiveCall,
                frozen = frozen,
                onSelect = { module ->
                    cursor = cursor.reset(newSize = homeModules.size, initial = homeModules.indexOf(module))
                    enterModule(module)
                }
            )

            AppDestination.ENTERTAINMENT -> EntertainmentScreen(
                selection = EntertainmentHubSelectionState(entCursor.index, entCursor.direction),
                scanMode = scanMode.value,
                scanIntervalMs = inputState.scanIntervalMs,
                dwellRevision = dwellRevision,
                paused = careOpen || nurseCall != null || adjustOverlay != null || hasActiveCall,
                frozen = frozen,
                onSelect = { module ->
                    entCursor = entCursor.reset(
                        newSize = EntertainmentHubModule.entries.size,
                        initial = EntertainmentHubModule.entries.indexOf(module)
                    )
                    enterEntertainmentModule(module)
                }
            )

            AppDestination.INPUT_METHOD -> InputMethodPage(vm, hasActiveCall)
            AppDestination.COMMUNICATION -> {
                CommunicationScreen(
                    chat = chat,
                    input = vm,
                    paused = careOpen || nurseCall != null || adjustOverlay != null || hasActiveCall,
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
                    paused = careOpen || adjustOverlay != null || confirmOverlay != null || speakOverlay != null || nurseCall != null || hasActiveCall,
                    frozen = frozen,
                    fakeAssistant = debugFakeAssistant,
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

            AppDestination.WIZARD_GAME -> BciBackground {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("魔法师游戏运行中…", style = MaterialTheme.typography.headlineMedium)
                }
            }

            AppDestination.CHINESE_CHESS -> ChineseChessScreen(inputState.scanIntervalMs, scanMode.value, careOpen || hasActiveCall || frozen || confirmOverlay != null, feedback::toast, { bciController?.invalidatePendingCommands() }) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.DOUDIZHU -> DoudizhuScreen(inputState.scanIntervalMs, scanMode.value, careOpen || hasActiveCall || frozen || confirmOverlay != null, feedback::toast, { bciController?.invalidatePendingCommands() }) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.MAHJONG -> MahjongScreen(inputState.scanIntervalMs, scanMode.value, careOpen || hasActiveCall || frozen || confirmOverlay != null, feedback::toast, { bciController?.invalidatePendingCommands() }) {
                navigateTo(AppDestination.ENTERTAINMENT)
            }

            AppDestination.SNAKE_CLIMB -> SnakeClimbScreen(
                state = snakeState.value,
                moveIntervalMs = inputState.scanIntervalMs,
                paused = careOpen || hasActiveCall || confirmOverlay != null || frozen,
                onAction = ::applySnakeAction,
                onBack = { confirmGameExit() }
            )

            AppDestination.WATERMELON -> WatermelonScreen(
                engine = watermelonEngine,
                state = watermelonState.value,
                externalRenderRevision = watermelonRenderRevision,
                paused = careOpen || hasActiveCall || confirmOverlay != null || adjustOverlay != null || speakOverlay != null || nurseCall != null,
                frozen = frozen,
                soundEnabled = feedbackSound,
                onStepPhysics = ::stepWatermelonPhysics,
                onTouchAimAtX = ::aimWatermelonAtX,
                onTouchDropAtX = ::dropWatermelonAtX,
                onBack = { applyWatermelonAction(WatermelonAction.REQUEST_EXIT) }
            )

            AppDestination.ASYNC_MAZE -> MazeScreen(
                state = mazeState.value,
                controlStatus = bciStatus.value,
                onBack = { confirmGameExit() },
                onAction = ::applyMazeAction,
                onReset = { resetMazeForActiveModel() }
            )

            AppDestination.TV -> TvScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                onBack = { navigateTo(mediaParent()) },
                initialChannelId = assistantMediaId,
                fromAssistant = assistantMediaId != null,
                paused = careOpen || hasActiveCall || confirmOverlay != null || adjustOverlay != null || speakOverlay != null || nurseCall != null,
                frozen = frozen,
                onToast = { message, ok -> feedback.toast(message, ok) },
                onBoundary = { bciController?.invalidatePendingCommands() }
            )

            AppDestination.MUSIC -> MusicScreen(
                onConfirmation = ::openConfirmation,
                paused = careOpen || hasActiveCall || confirmOverlay != null || adjustOverlay != null || speakOverlay != null || nurseCall != null,
                frozen = frozen,
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                onBack = { navigateTo(mediaParent()) },
                initialTrackId = assistantMediaId,
                fromAssistant = assistantMediaId != null,
                onToast = { message, ok -> feedback.toast(message, ok) },
                onBoundary = { bciController?.invalidatePendingCommands() }
            )

            AppDestination.NEWS_BRIEFING -> NewsBriefingScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                paused = careOpen || hasActiveCall || confirmOverlay != null || adjustOverlay != null || speakOverlay != null || nurseCall != null,
                frozen = frozen,
                onSpeak = { text, onDone ->
                    speechOutput.speak(
                        text = text,
                        onUnavailable = { feedback.toast("朗读不可用", false) },
                        onDone = onDone
                    )
                },
                onStopSpeak = { speechOutput.stop() },
                onToast = { message, ok -> feedback.toast(message, ok) },
                onBoundary = { bciController?.invalidatePendingCommands() },
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.APP_SETTINGS -> AppSettingsScreen(
                scanIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                displayMode = displayMode,
                floatingBallVisible = floatingBallVisible.value,
                volumePercent = volumePercent,
                brightnessPercent = brightnessPercent,
                paused = careOpen || adjustOverlay != null || nurseCall != null || hasActiveCall,
                frozen = frozen,
                initialIndex = settingsInitialIndex,
                onOpenAdjust = ::openAdjustOverlay,
                onOpenSpeedPractice = { navigateTo(AppDestination.SPEED_PRACTICE) },
                onScanModeChange = { mode ->
                    scanMode.value = ScanSettings.writeMode(applicationContext, mode)
                    dwellRevision++
                },
                onDisplayModeChange = { mode ->
                    displayMode = mode
                    AppUiSettings.writeDisplayMode(applicationContext, mode)
                },
                onFloatingBallVisibleChange = { visible ->
                    floatingBallVisible.value = visible
                    AppUiSettings.writeFloatingBallVisible(applicationContext, visible)
                },
                onToast = { message, ok -> feedback.toast(message, ok) },
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.SPEED_PRACTICE -> SpeedPracticeScreen(
                initialIntervalMs = inputState.scanIntervalMs,
                scanMode = scanMode.value,
                paused = careOpen || adjustOverlay != null || confirmOverlay != null || speakOverlay != null || nurseCall != null || hasActiveCall,
                frozen = frozen,
                onApplyInterval = { intervalMs ->
                    vm.setScanInterval(intervalMs)
                    navigateTo(AppDestination.APP_SETTINGS)
                },
                onToast = { message, ok -> feedback.toast(message, ok) },
                onBack = { navigateTo(AppDestination.APP_SETTINGS) }
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
    private fun InputMethodPage(vm: InputMethodViewModel, hasActiveCall: Boolean) {
        val state by vm.state.collectAsState()
        val phrases = remember(state.mode, state.phase, state.dwellRevision) { vm.rankedCommonPhrases(state.mode) }
        ComposerScreen(
            state = state,
            phrases = phrases,
            paused = careOpen || adjustOverlay != null || nurseCall != null || speakOverlay != null || confirmOverlay != null || hasActiveCall,
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
        val spec = confirmOverlay?.first ?: return
        confirmOverlay = null
        spec.onCancel()
        dwellRevision++
    }

    private fun readSystemVolumePercent(): Int {
        val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return AppSettingsLogic.streamLevelToPercent(vol, maxVol)
    }

    private fun stepSystemVolume(delta: Int) {
        if (frozen) {
            volumePercent = AppSettingsLogic.stepVolumePercent(volumePercent, delta)
            return
        }
        val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val beforeVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVol)
        val targetVol = AppSettingsLogic.nextStreamVolumeLevel(beforeVol, maxVol, delta)
        val writeResult = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }
        val afterVol = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVol)
        }.getOrDefault(beforeVol)
        volumePercent = AppSettingsLogic.streamLevelToPercent(afterVol, maxVol)
        if (writeResult.isFailure || (targetVol != beforeVol && afterVol == beforeVol)) {
            feedback.toast("系统未允许调整音量", false)
        }
    }

    private fun applyBrightnessPercent(targetPercent: Int) {
        val clamped = targetPercent.coerceIn(10, 100)
        brightnessPercent = clamped
        if (frozen) return
        val written = AppUiSettings.writeScreenBrightness(applicationContext, clamped / 100f)
        brightnessPercent = (written * 100f).roundToInt().coerceIn(10, 100)
        window.attributes = window.attributes.apply {
            screenBrightness = written
        }
    }

    private fun refreshHardwareSettings() {
        if (frozen) {
            volumePercent = 60
            brightnessPercent = 80
        } else {
            volumePercent = readSystemVolumePercent()
            brightnessPercent = (AppUiSettings.readScreenBrightness(applicationContext) * 100f).roundToInt().coerceIn(10, 100)
        }
    }

    private fun openAdjustOverlay(kind: AdjustOverlayKind) {
        refreshHardwareSettings()
        adjustOverlay = AdjustOverlayState(kind = kind)
        dwellRevision++
    }

    private fun dismissAdjustOverlay() {
        adjustOverlay = null
        dwellRevision++
    }

    private fun chooseAdjustOption(index: Int) {
        val current = adjustOverlay ?: return
        when (index) {
            0 -> stepAdjustValue(current.kind, -1)
            1 -> stepAdjustValue(current.kind, 1)
            else -> dismissAdjustOverlay()
        }
    }

    private fun stepAdjustValue(kind: AdjustOverlayKind, delta: Int) {
        when (kind) {
            AdjustOverlayKind.VOLUME -> stepSystemVolume(delta)
            AdjustOverlayKind.BRIGHTNESS -> applyBrightnessPercent(AppSettingsLogic.stepBrightnessPercent(brightnessPercent, delta))
            AdjustOverlayKind.SPEED -> {
                val cur = inputViewModel?.state?.value?.scanIntervalMs ?: ScanSettings.read(applicationContext)
                inputViewModel?.setScanInterval(AppSettingsLogic.stepScanIntervalMs(cur, delta))
            }
        }
        dwellRevision++
    }

    private fun advanceAdjustOverlay() {
        val current = adjustOverlay ?: return
        adjustOverlay = current.copy(cursor = current.cursor.advance(SystemClock.elapsedRealtime(), scanMode.value))
    }

    private fun advanceEntertainment() {
        entCursor = entCursor.advance(SystemClock.elapsedRealtime(), scanMode.value)
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
            if (!frozen) feedback.toast("已自动${spec.cancelLabel}")
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
                    cancelLabel = "取消",
                    confirmLabel = "清空",
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
        confirmOverlay = null
        dwellRevision++
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

    private fun resetCursorsForPauseBoundary() {
        adjustOverlay = adjustOverlay?.let { it.copy(cursor = it.cursor.reset()) }
        speakOverlay = speakOverlay?.let { it.copy(cursor = it.cursor.reset()) }
        confirmOverlay = confirmOverlay?.let { (spec, c) -> spec to c.copy(previousIndex = -1, advancedAtMs = 0L, direction = 1) }
        entCursor = entCursor.reset()
        cursor = cursor.reset()
    }

    private fun toggleCareSheet(open: Boolean) {
        if (careOpen == open) return
        careOpen = open
        resetCursorsForPauseBoundary()
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

    private fun openConfirmation(spec: ConfirmOverlaySpec) {
        bciController?.invalidatePendingCommands()
        confirmOverlay = spec to ScanCursor(2)
        dwellRevision++
    }

    private fun confirmGameExit(onCancel: () -> Unit = {}, onExit: () -> Unit = { navigateTo(AppDestination.ENTERTAINMENT) }) {
        openConfirmation(ConfirmOverlaySpec(
            title = "离开游戏？",
            cancelLabel = "继续游戏",
            confirmLabel = "退出游戏",
            onConfirm = onExit,
            onCancel = onCancel
        ))
    }

    private fun applySnakeAction(action: SnakeAction) {
        val previous = snakeState.value
        val next = SnakeClimbGame.applyAction(previous, action)
        snakeState.value = next
        when {
            next.exitRequested -> navigateTo(AppDestination.ENTERTAINMENT)
            next.exitConfirmation && !previous.exitConfirmation -> confirmGameExit(
                onCancel = { applySnakeAction(SnakeAction.CANCEL_EXIT) },
                onExit = { applySnakeAction(SnakeAction.CONFIRM_EXIT) }
            )
            next.gameOver && !previous.gameOver -> openConfirmation(ConfirmOverlaySpec(
                title = "游戏结束",
                description = next.message,
                cancelLabel = "重新开始",
                confirmLabel = "退出游戏",
                onConfirm = { applySnakeAction(SnakeAction.CONFIRM_EXIT) },
                onCancel = { applySnakeAction(SnakeAction.RESTART) }
            ))
        }
    }

    private fun readWatermelonBestScore(): Int =
        getSharedPreferences("watermelon_game", Context.MODE_PRIVATE).getInt("best_score", 0)

    private fun saveWatermelonBestScore(score: Int) {
        val prefs = getSharedPreferences("watermelon_game", Context.MODE_PRIVATE)
        if (score > prefs.getInt("best_score", 0)) {
            prefs.edit().putInt("best_score", score).apply()
        }
    }

    private fun handleWatermelonStateTransition(
        previous: WatermelonUiState,
        next: WatermelonUiState
    ) {
        if (next != previous) {
            watermelonState.value = next
        }
        if (next.bestScore > previous.bestScore) {
            saveWatermelonBestScore(next.bestScore)
        }
        when {
            next.exitRequested -> navigateTo(AppDestination.ENTERTAINMENT)
            next.exitConfirmation && !previous.exitConfirmation -> confirmGameExit(
                onCancel = { applyWatermelonAction(WatermelonAction.CANCEL_EXIT) },
                onExit = { applyWatermelonAction(WatermelonAction.CONFIRM_EXIT) }
            )
            next.gameOver && !previous.gameOver -> openConfirmation(ConfirmOverlaySpec(
                title = "游戏结束",
                description = next.message,
                cancelLabel = "再来一局",
                confirmLabel = "退出游戏",
                onConfirm = { applyWatermelonAction(WatermelonAction.CONFIRM_EXIT) },
                onCancel = { applyWatermelonAction(WatermelonAction.RESTART) }
            ))
        }
    }

    private fun applyWatermelonAction(action: WatermelonAction) {
        val previous = watermelonState.value
        val next = watermelonEngine.applyAction(action)
        watermelonRenderRevision++
        handleWatermelonStateTransition(previous, next)
    }

    private fun stepWatermelonPhysics(dtSeconds: Float): Boolean {
        val previous = watermelonState.value
        val stepped = watermelonEngine.step(dtSeconds)
        if (stepped) {
            handleWatermelonStateTransition(previous, watermelonEngine.uiState)
        }
        return stepped
    }

    private fun aimWatermelonAtX(worldX: Float) {
        val previous = watermelonState.value
        val next = watermelonEngine.aimAtX(worldX)
        handleWatermelonStateTransition(previous, next)
    }

    private fun dropWatermelonAtX(worldX: Float) {
        val previous = watermelonState.value
        val next = watermelonEngine.aimAndDropAtX(worldX)
        watermelonRenderRevision++
        handleWatermelonStateTransition(previous, next)
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
            EntertainmentHubModule.WATERMELON -> {
                watermelonState.value = watermelonEngine.reset(
                    seed = System.nanoTime(),
                    bestScoreOverride = readWatermelonBestScore()
                )
                watermelonRenderRevision++
                navigateTo(AppDestination.WATERMELON)
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
            EntertainmentHubModule.NEWS_BRIEFING -> navigateTo(AppDestination.NEWS_BRIEFING)
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
        if (target != AppDestination.APP_SETTINGS) {
            adjustOverlay = null
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
        if (target == AppDestination.LIFE_ASSISTANT && previous in setOf(AppDestination.TV, AppDestination.MUSIC)) {
            AssistantInputBus.resetFocusToInput()
        }
        if (target == AppDestination.ENTERTAINMENT) {
            entCursor = ScanCursor(
                size = EntertainmentHubModule.entries.size,
                index = EntertainmentHubModule.initialIndexForEntry(previous),
                direction = 1
            )
        }
        if (target == AppDestination.APP_SETTINGS) {
            settingsInitialIndex = if (previous == AppDestination.SPEED_PRACTICE) {
                AppSettingItem.SPEED_PRACTICE.ordinal
            } else {
                AppSettingsLogic.INITIAL_INDEX
            }
            refreshHardwareSettings()
            AppSettingsInputBus.resetFocus(settingsInitialIndex)
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
            if (!frozen) feedback.toast("已自动继续通话")
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
        adjustOverlay = null
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
            "nav ent" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.ENTERTAINMENT)
                entCursor = ScanCursor(
                    size = EntertainmentHubModule.entries.size,
                    index = EntertainmentHubModule.MAZE.ordinal,
                    direction = 1
                )
                dwellRevision++
            }
            "nav tv" -> {
                clearTransientOverlaysForNav()
                assistantMediaId = null
                navigateTo(AppDestination.TV)
                TvInputBus.reset()
                dwellRevision++
            }
            "nav watermelon" -> {
                clearTransientOverlaysForNav()
                watermelonState.value = watermelonEngine.reset(
                    seed = 42L,
                    bestScoreOverride = readWatermelonBestScore()
                )
                watermelonRenderRevision++
                navigateTo(AppDestination.WATERMELON)
                dwellRevision++
            }
            "nav news" -> {
                clearTransientOverlaysForNav()
                navigateTo(AppDestination.NEWS_BRIEFING)
                dwellRevision++
            }
            "nav settings" -> {
                clearTransientOverlaysForNav()
                settingsInitialIndex = AppSettingsLogic.INITIAL_INDEX
                navigateTo(AppDestination.APP_SETTINGS)
                refreshHardwareSettings()
                AppSettingsInputBus.resetFocus(AppSettingsLogic.INITIAL_INDEX)
                dwellRevision++
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
            "fake assistant" -> {
                debugFakeAssistant = true
                dwellRevision++
            }
            "fake home reset" -> {
                AssistantInputBus.resetHome()
                dwellRevision++
            }
            "care on", "care off" -> toggleCareSheet(cmd.endsWith("on"))
            "freeze on", "freeze off" -> {
                frozen = cmd.endsWith("on")
                if (frozen) nurseCallAlarm.stop()
                refreshHardwareSettings()
                cursor = cursor.reset()
                dwellRevision++
            }
            "scan auto", "scan manual" -> {
                scanMode.value = if (cmd.endsWith("auto")) ScanMode.AUTO else ScanMode.MANUAL
                cursor = cursor.reset()
                dwellRevision++
            }
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
                    adjustOverlay != null -> advanceAdjustOverlay()
                    destination.value == AppDestination.HOME -> advanceHome()
                    destination.value == AppDestination.ENTERTAINMENT -> advanceEntertainment()
                    destination.value == AppDestination.APP_SETTINGS -> AppSettingsInputBus.debugTick()
                    destination.value == AppDestination.SPEED_PRACTICE -> SpeedPracticeInputBus.debugTick()
                    destination.value == AppDestination.SNAKE_CLIMB -> applySnakeAction(SnakeAction.TICK)
                    destination.value == AppDestination.WATERMELON -> {
                        repeat(15) { stepWatermelonPhysics(WatermelonGame.FIXED_DT) }
                        watermelonRenderRevision++
                    }
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
                    destination.value == AppDestination.TV -> if (!TvInputBus.debugTick()) return "pending: page cursor"
                    destination.value == AppDestination.MUSIC -> if (!com.example.input_ds.music.MusicInputBus.debugTick()) return "pending: page cursor"
                    destination.value == AppDestination.NEWS_BRIEFING -> if (!NewsInputBus.debugTick()) return "pending: page cursor"
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
        val activeAdjust = adjustOverlay
        if (activeAdjust != null) {
            when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    adjustOverlay = activeAdjust.copy(cursor = activeAdjust.cursor.look(step, scanMode.value))
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val chosen = activeAdjust.cursor.selected(
                        SystemClock.elapsedRealtime(),
                        if (frozen) 0 else AppUiSettings.graceMs(this),
                        scanMode.value
                    )
                    chooseAdjustOption(chosen)
                }
                else -> Unit
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
                    }
                    enterModule(homeModules[selected])
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.ENTERTAINMENT -> when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    entCursor = entCursor.look(if (signal == ControlSignal.LEFT_LOOK) -1 else 1, scanMode.value)
                    dwellRevision++
                }
                ControlSignal.BITE -> {
                    val selected = entCursor.selected(
                        SystemClock.elapsedRealtime(),
                        if (frozen) 0 else AppUiSettings.graceMs(this),
                        scanMode.value
                    )
                    if (selected != entCursor.index) {
                        entCursor = entCursor.copy(index = selected)
                    }
                    enterEntertainmentModule(EntertainmentHubModule.entries[selected])
                }
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
            AppDestination.WATERMELON -> when (signal) {
                ControlSignal.LEFT_LOOK -> applyWatermelonAction(WatermelonAction.LOOK_LEFT)
                ControlSignal.RIGHT_LOOK -> applyWatermelonAction(WatermelonAction.LOOK_RIGHT)
                ControlSignal.BITE -> applyWatermelonAction(WatermelonAction.BITE)
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
            AppDestination.NEWS_BRIEFING -> {
                NewsInputBus.dispatch(signal)
            }
            AppDestination.APP_SETTINGS -> {
                AppSettingsInputBus.dispatch(signal)
            }
            AppDestination.SPEED_PRACTICE -> {
                SpeedPracticeInputBus.dispatch(signal)
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
        val opened = !mazeState.value.exitDialogVisible && nextState.exitDialogVisible
        mazeState.value = nextState
        if (opened) confirmGameExit(onCancel = { resolveMazeExit(MazeExitChoice.CANCEL) },
            onExit = { resolveMazeExit(MazeExitChoice.CONFIRM) })
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
        resetCursorsForPauseBoundary()
        bciController?.setCommandDeliveryEnabled(false)
        bciController?.invalidatePendingCommands()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        resetCursorsForPauseBoundary()
        refreshHardwareSettings()
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
