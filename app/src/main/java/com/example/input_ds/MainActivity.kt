package com.example.input_ds

import android.Manifest
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
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.game.MazeAction
import com.example.input_ds.game.MazeExitChoice
import com.example.input_ds.game.MazeGame
import com.example.input_ds.game.MazeProtocol
import com.example.input_ds.media.EntertainmentMediaMode
import com.example.input_ds.media.EntertainmentPlayer
import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentAction
import com.example.input_ds.model.EntertainmentSelectionState
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.model.MusicControlAction
import com.example.input_ds.model.MusicSelectionEffect
import com.example.input_ds.model.MusicSelectionState
import com.example.input_ds.model.TvSelectionEffect
import com.example.input_ds.model.TvSelectionState
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.TrainingWorkScheduler
import com.example.input_ds.personalization.UserModelManager
import com.example.input_ds.ui.bci.BleScanScreen
import com.example.input_ds.ui.bci.CollectionScreen
import com.example.input_ds.ui.bci.SignalMonitorScreen
import com.example.input_ds.ui.components.MainScreen
import com.example.input_ds.ui.entertainment.EntertainmentScreen
import com.example.input_ds.ui.game.MazeScreen
import com.example.input_ds.ui.home.HomeScreen
import com.example.input_ds.ui.music.MusicScreen
import com.example.input_ds.ui.tv.TvScreen
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.viewmodel.InputMethodViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: NaoyunBleManager
    private lateinit var entertainmentPlayer: EntertainmentPlayer
    private var bciController: BciController? = null
    private var inputViewModel: InputMethodViewModel? = null
    private var attemptedModelIdentity: String? = null

    private val destination = mutableStateOf(AppDestination.HOME)
    private val homeSelection = mutableStateOf(HomeSelectionState())
    private val entertainmentSelection = mutableStateOf(EntertainmentSelectionState())
    private val musicSelection = mutableStateOf(MusicSelectionState())
    private val tvSelection = mutableStateOf(TvSelectionState())
    private val mazeState = mutableStateOf(
        MazeGame.create(MazeProtocol.FOUR_CLASS)
    )
    private val bciActive = mutableStateOf(false)
    private val bciStatus = mutableStateOf("耳机未连接")

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
        entertainmentPlayer = EntertainmentPlayer(applicationContext)
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
                            NaoyunBleManager.State.STREAM_STALLED,
                            NaoyunBleManager.State.RECOVERING,
                            NaoyunBleManager.State.DISCONNECTED,
                            NaoyunBleManager.State.ERROR
                        )
                    ) {
                        stopBciController()
                    }
                }

                AppContent(vm = vm, bleState = bleState)
            }
        }
    }

    @Composable
    private fun AppContent(vm: InputMethodViewModel, bleState: NaoyunBleManager.State) {
        val playbackState by entertainmentPlayer.state.collectAsState()
        if (destination.value != AppDestination.HOME &&
            destination.value != AppDestination.DEVICE_STATUS
        ) {
            BackHandler { navigateBack() }
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
                onAction = ::applyMazeAction,
                onExitDecision = ::resolveMazeExit,
                onReset = { resetMazeForActiveModel() }
            )

            AppDestination.ENTERTAINMENT -> EntertainmentScreen(
                selection = entertainmentSelection.value,
                onBack = { navigateTo(AppDestination.HOME) },
                onMusic = ::openMusic,
                onTelevision = ::openTelevision
            )

            AppDestination.MUSIC -> MusicScreen(
                tracks = EntertainmentPlayer.musicTracks,
                playback = playbackState,
                selection = musicSelection.value,
                onBack = ::leaveMusic,
                onControl = ::handleMusicControlTouch,
                onTrack = ::handleMusicTrackTouch
            )

            AppDestination.TV -> TvScreen(
                channels = EntertainmentPlayer.tvChannels,
                playback = playbackState,
                selection = tvSelection.value,
                onBack = ::leaveTelevision,
                onChannel = ::handleTvChannelTouch
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
            HomeModule.REALTIME_COMMUNICATION -> navigateTo(AppDestination.INPUT_METHOD)
            HomeModule.MAZE -> {
                resetMazeForActiveModel()
                if (bleManager.state.value == NaoyunBleManager.State.READY) restartBciController()
                navigateTo(AppDestination.ASYNC_MAZE)
            }
            HomeModule.ENTERTAINMENT -> {
                entertainmentSelection.value = EntertainmentSelectionState()
                navigateTo(AppDestination.ENTERTAINMENT)
            }
            HomeModule.SETTINGS -> navigateTo(AppDestination.SETTINGS)
        }
    }

    private fun navigateBack() {
        when (destination.value) {
            AppDestination.MUSIC -> leaveMusic()
            AppDestination.TV -> leaveTelevision()
            AppDestination.ENTERTAINMENT -> navigateTo(AppDestination.HOME)
            else -> navigateTo(AppDestination.HOME)
        }
    }

    private fun openMusic() {
        entertainmentSelection.value = entertainmentSelection.value.select(EntertainmentAction.MUSIC)
        if (entertainmentPlayer.state.value.mode != EntertainmentMediaMode.MUSIC) {
            musicSelection.value = MusicSelectionState(
                highlightedTrackIndex = musicSelection.value.highlightedTrackIndex
            )
            entertainmentPlayer.prepareMusic(
                index = musicSelection.value.highlightedTrackIndex,
                playWhenReady = false
            )
        }
        navigateTo(AppDestination.MUSIC)
    }

    private fun openTelevision() {
        entertainmentSelection.value = entertainmentSelection.value.select(EntertainmentAction.TV)
        val channelIndex = (tvSelection.value.selectedIndex - 1).coerceAtLeast(0)
        tvSelection.value = tvSelection.value.selectChannel(
            channelIndex,
            EntertainmentPlayer.tvChannels.size
        )
        entertainmentPlayer.playChannel(channelIndex)
        navigateTo(AppDestination.TV)
    }

    private fun leaveTelevision() {
        entertainmentPlayer.stopTelevision()
        navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun leaveMusic() {
        entertainmentPlayer.stopMusic()
        navigateTo(AppDestination.ENTERTAINMENT)
    }

    private fun handleMusicControlTouch(action: MusicControlAction) {
        musicSelection.value = musicSelection.value.selectControl(action)
        confirmMusicSelection()
    }

    private fun handleMusicTrackTouch(index: Int) {
        musicSelection.value = musicSelection.value.selectTrack(
            index,
            EntertainmentPlayer.musicTracks.size
        )
        entertainmentPlayer.playMusic(index)
    }

    private fun confirmMusicSelection() {
        val result = musicSelection.value.confirm(
            trackCount = EntertainmentPlayer.musicTracks.size,
            currentTrackIndex = entertainmentPlayer.state.value.currentIndex
        )
        musicSelection.value = result.state
        when (val effect = result.effect) {
            MusicSelectionEffect.Back -> leaveMusic()
            MusicSelectionEffect.Previous -> entertainmentPlayer.previousTrack()
            MusicSelectionEffect.TogglePlayback -> entertainmentPlayer.toggleMusicPlayback()
            MusicSelectionEffect.Next -> entertainmentPlayer.nextTrack()
            is MusicSelectionEffect.PlayTrack -> entertainmentPlayer.playMusic(effect.index)
            null -> Unit
        }
    }

    private fun handleTvChannelTouch(index: Int) {
        tvSelection.value = tvSelection.value.selectChannel(index, EntertainmentPlayer.tvChannels.size)
        entertainmentPlayer.playChannel(index)
    }

    private fun confirmTelevisionSelection() {
        when (val effect = tvSelection.value.confirm(EntertainmentPlayer.tvChannels.size)) {
            TvSelectionEffect.Back -> leaveTelevision()
            is TvSelectionEffect.PlayChannel -> entertainmentPlayer.playChannel(effect.index)
        }
    }

    private fun navigateTo(target: AppDestination) {
        destination.value = target
        val commandsEnabled = target != AppDestination.DEVICE_STATUS &&
            target != AppDestination.COLLECTION && target != AppDestination.SETTINGS
        bciController?.setCommandDeliveryEnabled(commandsEnabled)
    }

    private fun routeSignal(signal: ControlSignal) {
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
                applyMazeAction(action)
            }
            AppDestination.ENTERTAINMENT -> when (signal) {
                ControlSignal.LEFT_LOOK -> entertainmentSelection.value = entertainmentSelection.value.moveLeft()
                ControlSignal.RIGHT_LOOK -> entertainmentSelection.value = entertainmentSelection.value.moveRight()
                ControlSignal.BITE -> when (entertainmentSelection.value.selectedAction) {
                    EntertainmentAction.BACK -> navigateTo(AppDestination.HOME)
                    EntertainmentAction.MUSIC -> openMusic()
                    EntertainmentAction.TV -> openTelevision()
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.MUSIC -> when (signal) {
                ControlSignal.LEFT_LOOK -> musicSelection.value = musicSelection.value.moveLeft(
                    EntertainmentPlayer.musicTracks.size
                )
                ControlSignal.RIGHT_LOOK -> musicSelection.value = musicSelection.value.moveRight(
                    EntertainmentPlayer.musicTracks.size
                )
                ControlSignal.BITE -> confirmMusicSelection()
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.TV -> when (signal) {
                ControlSignal.LEFT_LOOK -> tvSelection.value = tvSelection.value.moveLeft(
                    EntertainmentPlayer.tvChannels.size
                )
                ControlSignal.RIGHT_LOOK -> tvSelection.value = tvSelection.value.moveRight(
                    EntertainmentPlayer.tvChannels.size
                )
                ControlSignal.BITE -> confirmTelevisionSelection()
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            AppDestination.SETTINGS -> Unit
            AppDestination.DEVICE_STATUS, AppDestination.COLLECTION -> Unit
        }
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

    private fun applyMazeAction(action: MazeAction) {
        val nextState = MazeGame.applyAction(mazeState.value, action)
        mazeState.value = nextState
        if (nextState.exitConfirmed) navigateTo(AppDestination.HOME)
    }

    private fun resolveMazeExit(choice: MazeExitChoice) {
        val nextState = MazeGame.resolveExitDialog(mazeState.value, choice)
        mazeState.value = nextState
        if (nextState.exitConfirmed) navigateTo(AppDestination.HOME)
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
        NaoyunBleManager.State.STREAM_STALLED -> "耳机数据流中断"
        NaoyunBleManager.State.RECOVERING -> "正在自动恢复耳机数据流"
        NaoyunBleManager.State.ERROR -> "耳机连接异常"
        else -> "耳机未连接"
    }

    override fun onDestroy() {
        stopBciController()
        entertainmentPlayer.close()
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
