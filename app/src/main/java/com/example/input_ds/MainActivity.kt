package com.example.input_ds

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.bci.BciController
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.chess.ChineseChessInputBus
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.doudizhu.DoudizhuInputBus
import com.example.input_ds.game.MazeAction
import com.example.input_ds.game.MazeExitChoice
import com.example.input_ds.game.MazeGame
import com.example.input_ds.game.MazeProtocol
import com.example.input_ds.game.SnakeAction
import com.example.input_ds.game.SnakeClimbGame
import com.example.input_ds.mahjong.MahjongInputBus
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentHubSelectionState
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.music.MusicInputBus
import com.example.input_ds.settings.AppSettingsInputBus
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.TrainingWorkScheduler
import com.example.input_ds.personalization.UserModelManager
import com.example.input_ds.ui.bci.BleScanScreen
import com.example.input_ds.ui.bci.CollectionScreen
import com.example.input_ds.ui.bci.SignalMonitorScreen
import com.example.input_ds.ui.chess.ChineseChessScreen
import com.example.input_ds.ui.components.FloatingControlBall
import com.example.input_ds.ui.components.MainScreen
import com.example.input_ds.ui.doudizhu.DoudizhuScreen
import com.example.input_ds.ui.game.MazeScreen
import com.example.input_ds.ui.game.SnakeClimbScreen
import com.example.input_ds.ui.home.EntertainmentScreen
import com.example.input_ds.ui.home.HomeScreen
import com.example.input_ds.ui.mahjong.MahjongScreen
import com.example.input_ds.ui.music.MusicScreen
import com.example.input_ds.ui.settings.AppSettingsScreen
import com.example.input_ds.ui.tv.TvScreen
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.viewmodel.InputMethodViewModel
import com.example.input_ds.wizard.WizardGameActivity
import com.example.input_ds.wizard.WizardGameInputBus
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: NaoyunBleManager
    private var bciController: BciController? = null
    private var inputViewModel: InputMethodViewModel? = null
    private var attemptedModelIdentity: String? = null

    private val destination = mutableStateOf(AppDestination.HOME)
    private val homeSelection = mutableStateOf(HomeSelectionState())
    private val entertainmentSelection = mutableStateOf(EntertainmentHubSelectionState())
    private val mazeState = mutableStateOf(
        MazeGame.create(MazeProtocol.FOUR_CLASS)
    )
    private val snakeState = mutableStateOf(SnakeClimbGame.create())
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

    private val wizardGameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        navigateTo(AppDestination.ENTERTAINMENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CharacterDictionary.init(applicationContext)
        CommonPhraseUsage.init(applicationContext)
        LanguageModel.init(applicationContext)
        UserDictionary.init(applicationContext)
        bleManager = NaoyunBleManager(applicationContext)
        TrainingWorkScheduler.resumeIncomplete(applicationContext)
        floatingBallVisible.value = AppUiSettings.readFloatingBallVisible(applicationContext)
        window.attributes = window.attributes.apply {
            screenBrightness = AppUiSettings.readScreenBrightness(applicationContext)
        }

        setContent {
            InputDSTheme {
                val vm: InputMethodViewModel = viewModel()
                inputViewModel = vm
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

                Box(Modifier.fillMaxSize()) {
                    AppContent(vm = vm, bleState = bleState)
                    FloatingControlBall(
                        visible = floatingBallVisible.value,
                        onLeftLook = { routeSignal(ControlSignal.LEFT_LOOK) },
                        onRightLook = { routeSignal(ControlSignal.RIGHT_LOOK) },
                        onBite = { routeSignal(ControlSignal.BITE) }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppContent(vm: InputMethodViewModel, bleState: NaoyunBleManager.State) {
        if (destination.value != AppDestination.HOME &&
            destination.value != AppDestination.DEVICE_STATUS
        ) {
            BackHandler {
                val parent = when (destination.value) {
                    AppDestination.ENTERTAINMENT -> AppDestination.HOME
                    AppDestination.ASYNC_MAZE,
                    AppDestination.SNAKE_CLIMB,
                    AppDestination.WIZARD_GAME,
                    AppDestination.CHINESE_CHESS,
                    AppDestination.DOUDIZHU,
                    AppDestination.MAHJONG,
                    AppDestination.TV,
                    AppDestination.MUSIC -> AppDestination.ENTERTAINMENT
                    else -> AppDestination.HOME
                }
                navigateTo(parent)
            }
        }

        when (destination.value) {
            AppDestination.HOME -> HomeScreen(
                selection = homeSelection.value,
                scanIntervalMs = vm.state.value.scanIntervalMs,
                bleManager = bleManager,
                onRequestPermissions = ::requestBluetoothPermissions,
                onSelect = { module ->
                    if (module.scanEnabled) {
                        homeSelection.value = homeSelection.value.copy(
                            selectedIndex = HomeModule.entries.indexOf(module)
                        )
                    }
                    enterModule(module)
                },
                onAdvance = { homeSelection.value = homeSelection.value.advance() }
            )

            AppDestination.ENTERTAINMENT -> EntertainmentScreen(
                selection = entertainmentSelection.value,
                scanIntervalMs = vm.state.value.scanIntervalMs,
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

            AppDestination.ASYNC_MAZE -> MazeScreen(
                state = mazeState.value,
                controlStatus = bciStatus.value,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) },
                onAction = ::applyMazeAction,
                onExitDecision = ::resolveMazeExit,
                onReset = { resetMazeForActiveModel() }
            )

            AppDestination.SNAKE_CLIMB -> SnakeClimbScreen(
                state = snakeState.value,
                moveIntervalMs = vm.state.value.scanIntervalMs,
                onAction = ::applySnakeAction,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.WIZARD_GAME -> AuroraBackground {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("魔法师游戏运行中…", style = MaterialTheme.typography.headlineMedium)
                }
            }

            AppDestination.CHINESE_CHESS -> ChineseChessScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.DOUDIZHU -> DoudizhuScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.MAHJONG -> MahjongScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.TV -> TvScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.MUSIC -> MusicScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.ENTERTAINMENT) }
            )

            AppDestination.APP_SETTINGS -> AppSettingsScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
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
                scanIntervalMs = vm.state.value.scanIntervalMs,
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
        AuroraBackground {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Text(
                    "实时沟通",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineMedium
                )
                Box(Modifier.weight(1f)) {
                    MainScreen(
                        state = state,
                        onBlockClick = vm::selectBlockByTouch,
                        onPinyinClick = vm::selectPinyinByTouch,
                        onPinyinNavigationClick = vm::selectPinyinNavigationByTouch,
                        onCharacterClick = vm::selectCharacterByTouch,
                        onCommonPhraseClick = vm::selectCommonPhraseByTouch,
                        onPredictionClick = vm::selectPredictionByTouch,
                        onInitialPredictionClick = vm::selectInitialPredictionByTouch
                    )
                }
            }
        }
    }

    private fun enterModule(module: HomeModule) {
        when (module) {
            HomeModule.APP_SETTINGS -> navigateTo(AppDestination.APP_SETTINGS)
            HomeModule.REALTIME_COMMUNICATION -> navigateTo(AppDestination.INPUT_METHOD)
            HomeModule.ENTERTAINMENT -> navigateTo(AppDestination.ENTERTAINMENT)
            HomeModule.HEADSET_SETTINGS -> navigateTo(AppDestination.SETTINGS)
        }
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
        destination.value = target
        updateBciCommandDelivery()
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
        val targetAcceptsCommands = destination.value != AppDestination.DEVICE_STATUS &&
            destination.value != AppDestination.COLLECTION &&
            destination.value != AppDestination.SETTINGS
        bciController?.setCommandDeliveryEnabled(
            headsetControlEnabled.value && targetAcceptsCommands
        )
    }

    private fun routeHeadsetSignal(signal: ControlSignal) {
        if (headsetControlEnabled.value) routeSignal(signal)
    }

    private fun routeSignal(signal: ControlSignal) {
        if (WizardGameInputBus.dispatch(signal)) return
        when (destination.value) {
            AppDestination.HOME -> when (signal) {
                ControlSignal.LEFT_LOOK -> homeSelection.value = homeSelection.value.changeDirection(-1)
                ControlSignal.RIGHT_LOOK -> homeSelection.value = homeSelection.value.changeDirection(1)
                ControlSignal.BITE -> enterModule(homeSelection.value.selectedModule)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.ENTERTAINMENT -> when (signal) {
                ControlSignal.LEFT_LOOK -> entertainmentSelection.value =
                    entertainmentSelection.value.changeDirection(-1)
                ControlSignal.RIGHT_LOOK -> entertainmentSelection.value =
                    entertainmentSelection.value.changeDirection(1)
                ControlSignal.BITE -> enterEntertainmentModule(
                    entertainmentSelection.value.selectedModule
                )
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.INPUT_METHOD -> inputViewModel?.handleSignal(signal)
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
            AppDestination.SNAKE_CLIMB -> {
                val action = when (signal) {
                    ControlSignal.LEFT_LOOK -> SnakeAction.LOOK_LEFT
                    ControlSignal.RIGHT_LOOK -> SnakeAction.LOOK_RIGHT
                    ControlSignal.BITE -> SnakeAction.BITE
                    ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> return
                }
                applySnakeAction(action)
            }
            AppDestination.WIZARD_GAME -> Unit
            AppDestination.CHINESE_CHESS -> {
                ChineseChessInputBus.dispatch(signal)
            }
            AppDestination.DOUDIZHU -> {
                DoudizhuInputBus.dispatch(signal)
            }
            AppDestination.MAHJONG -> {
                MahjongInputBus.dispatch(signal)
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

    private fun applySnakeAction(action: SnakeAction) {
        val next = SnakeClimbGame.applyAction(snakeState.value, action)
        snakeState.value = next
        if (next.exitRequested) navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun startBciController() {
        stopBciController()
        attemptedModelIdentity = activeModelIdentity()
        val controller = BciController(applicationContext, bleManager, ::routeHeadsetSignal)
        bciController = controller
        updateBciCommandDelivery()
        if (!controller.loadModel()) {
            bciStatus.value = "耳机已连接，控制模型不可用"
            controller.stop()
            bciController = null
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

    override fun onDestroy() {
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
