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
import com.example.input_ds.game.MazeGame
import com.example.input_ds.game.MazeProtocol
import com.example.input_ds.game.SnakeAction
import com.example.input_ds.game.SnakeClimbGame
import com.example.input_ds.mahjong.MahjongInputBus
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.music.MusicInputBus
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
import com.example.input_ds.ui.home.HomeScreen
import com.example.input_ds.ui.mahjong.MahjongScreen
import com.example.input_ds.ui.music.MusicScreen
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
    private val mazeState = mutableStateOf(
        MazeGame.create(MazeProtocol.FOUR_CLASS)
    )
    private val snakeState = mutableStateOf(SnakeClimbGame.create())
    private val bciActive = mutableStateOf(false)
    private val bciStatus = mutableStateOf("耳机未连接")

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
        navigateTo(AppDestination.HOME)
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

        setContent {
            InputDSTheme {
                val vm: InputMethodViewModel = viewModel()
                inputViewModel = vm
                val bleState by bleManager.state.collectAsState()

                LaunchedEffect(bleState) {
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
                        visible = true,
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
            BackHandler { navigateTo(AppDestination.HOME) }
        }

        when (destination.value) {
            AppDestination.HOME -> HomeScreen(
                selection = homeSelection.value,
                bleManager = bleManager,
                onRequestPermissions = ::requestBluetoothPermissions,
                onSelect = { module ->
                    homeSelection.value = HomeSelectionState(HomeModule.entries.indexOf(module))
                    enterModule(module)
                },
                onMoveLeft = { homeSelection.value = homeSelection.value.moveLeft() },
                onMoveRight = { homeSelection.value = homeSelection.value.moveRight() },
                onConfirm = {
                    homeSelection.value.selectedModule
                        .takeUnless { it == HomeModule.SETTINGS }
                        ?.let(::enterModule)
                }
            )

            AppDestination.INPUT_METHOD -> InputMethodPage(vm)

            AppDestination.ASYNC_MAZE -> MazeScreen(
                state = mazeState.value,
                controlStatus = bciStatus.value,
                onBack = { navigateTo(AppDestination.HOME) },
                onAction = { action -> mazeState.value = MazeGame.applyAction(mazeState.value, action) },
                onReset = { resetMazeForActiveModel() }
            )

            AppDestination.SNAKE_CLIMB -> SnakeClimbScreen(
                state = snakeState.value,
                moveIntervalMs = vm.state.value.scanIntervalMs,
                onAction = ::applySnakeAction,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.WIZARD_GAME -> AuroraBackground {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("魔法师游戏运行中…", style = MaterialTheme.typography.headlineMedium)
                }
            }

            AppDestination.CHINESE_CHESS -> ChineseChessScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.DOUDIZHU -> DoudizhuScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.MAHJONG -> MahjongScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.TELEVISION -> TvScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
                onBack = { navigateTo(AppDestination.HOME) }
            )

            AppDestination.MUSIC -> MusicScreen(
                scanIntervalMs = vm.state.value.scanIntervalMs,
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
                        onCharacterClick = vm::selectCharacterByTouch,
                        onPredictionClick = vm::selectPredictionByTouch,
                        onInitialPredictionClick = vm::selectInitialPredictionByTouch
                    )
                }
            }
        }
    }

    private fun enterModule(module: HomeModule) {
        when (module) {
            HomeModule.REALTIME_COMMUNICATION -> navigateTo(AppDestination.INPUT_METHOD)
            HomeModule.MAZE -> {
                resetMazeForActiveModel()
                if (bleManager.state.value == NaoyunBleManager.State.READY) restartBciController()
                navigateTo(AppDestination.ASYNC_MAZE)
            }
            HomeModule.SNAKE_CLIMB -> {
                snakeState.value = SnakeClimbGame.create()
                navigateTo(AppDestination.SNAKE_CLIMB)
            }
            HomeModule.WIZARD_GAME -> {
                navigateTo(AppDestination.WIZARD_GAME)
                wizardGameLauncher.launch(Intent(this, WizardGameActivity::class.java))
            }
            HomeModule.CHINESE_CHESS -> navigateTo(AppDestination.CHINESE_CHESS)
            HomeModule.DOUDIZHU -> navigateTo(AppDestination.DOUDIZHU)
            HomeModule.MAHJONG -> navigateTo(AppDestination.MAHJONG)
            HomeModule.TELEVISION -> navigateTo(AppDestination.TELEVISION)
            HomeModule.MUSIC -> navigateTo(AppDestination.MUSIC)
            HomeModule.SETTINGS -> navigateTo(AppDestination.SETTINGS)
        }
    }

    private fun navigateTo(target: AppDestination) {
        destination.value = target
        val commandsEnabled = target != AppDestination.DEVICE_STATUS &&
            target != AppDestination.COLLECTION && target != AppDestination.SETTINGS
        bciController?.setCommandDeliveryEnabled(commandsEnabled)
    }

    private fun routeSignal(signal: ControlSignal) {
        if (WizardGameInputBus.dispatch(signal)) return
        when (destination.value) {
            AppDestination.HOME -> when (signal) {
                ControlSignal.LEFT_LOOK -> homeSelection.value = homeSelection.value.moveLeft()
                ControlSignal.RIGHT_LOOK -> homeSelection.value = homeSelection.value.moveRight()
                ControlSignal.BITE -> homeSelection.value.selectedModule
                    .takeUnless { it == HomeModule.SETTINGS }
                    ?.let(::enterModule)
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
                mazeState.value = MazeGame.applyAction(mazeState.value, action)
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
            AppDestination.TELEVISION -> {
                TvInputBus.dispatch(signal)
            }
            AppDestination.MUSIC -> {
                MusicInputBus.dispatch(signal)
            }
            AppDestination.SETTINGS -> Unit
            AppDestination.DEVICE_STATUS, AppDestination.COLLECTION -> Unit
        }
    }

    private fun applySnakeAction(action: SnakeAction) {
        val next = SnakeClimbGame.applyAction(snakeState.value, action)
        snakeState.value = next
        if (next.exitRequested) navigateTo(AppDestination.HOME)
    }

    private fun startBciController() {
        stopBciController()
        attemptedModelIdentity = activeModelIdentity()
        val controller = BciController(applicationContext, bleManager, ::routeSignal)
        bciController = controller
        val commandsEnabled = destination.value != AppDestination.DEVICE_STATUS &&
            destination.value != AppDestination.COLLECTION &&
            destination.value != AppDestination.SETTINGS
        controller.setCommandDeliveryEnabled(commandsEnabled)
        if (!controller.loadModel()) {
            bciStatus.value = "耳机已连接，控制模型不可用"
            controller.stop()
            bciController = null
            bciActive.value = false
            return
        }
        controller.start()
        bciActive.value = true
        val protocol = controller.loadedProtocol ?: ClassificationProtocol.FOUR_CLASS
        bciStatus.value = if (protocol == ClassificationProtocol.SIX_ACTION) {
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
        NaoyunBleManager.State.READY -> if (bciActive.value) "耳机已连接 · 控制运行中" else "耳机已连接"
        NaoyunBleManager.State.SCANNING -> "正在扫描耳机"
        NaoyunBleManager.State.CONNECTING, NaoyunBleManager.State.INITIALIZING -> "正在连接耳机"
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
        mutableStateOf(if (bleState == NaoyunBleManager.State.READY) "monitor" else "scan")
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
