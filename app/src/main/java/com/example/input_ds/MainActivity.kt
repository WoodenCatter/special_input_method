package com.example.input_ds

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.bci.BciController
import com.example.input_ds.bci.DataCollector
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.InputPhase
import com.example.input_ds.ui.bci.BleScanScreen
import com.example.input_ds.ui.bci.CollectionScreen
import com.example.input_ds.ui.bci.SignalMonitorScreen
import com.example.input_ds.ui.components.MainScreen
import com.example.input_ds.ui.components.PredictionDebugOverlay
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.viewmodel.InputMethodViewModel

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: NaoyunBleManager
    private var bciController: BciController? = null
    private val showBci = mutableStateOf(false)
    private val bciActive = mutableStateOf(false)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it } && bleManager.hasPermissions()) {
            bciController?.stop()
            bciController = null
            bciActive.value = false
            showBci.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CharacterDictionary.init(applicationContext)
        LanguageModel.init(applicationContext)
        UserDictionary.init(applicationContext)

        bleManager = NaoyunBleManager(applicationContext)

        setContent {
            InputDSTheme {
                val vm: InputMethodViewModel = viewModel()
                val state by vm.state.collectAsState()
                val predictionDebugInfo by vm.predictionDebugInfo.collectAsState()
                val highlightKey = when (state.phase) {
                    InputPhase.LEVEL_1_SCANNING ->
                        "level1:${state.scanSide}:${state.highlightedBlockIndex}"
                    InputPhase.LEVEL_2_LETTER_SELECT ->
                        if (state.isCharFocused) {
                            "level2-char:${state.highlightedCharIndex}"
                        } else {
                            "level2-pinyin:${state.highlightedPinyinIndex}"
                        }
                    InputPhase.LEVEL_3_CHAR_SELECT ->
                        "level3-char:${state.highlightedCharIndex}"
                    InputPhase.PREDICTION ->
                        "prediction:${state.highlightedPredictionIndex}"
                }

                // The visible highlight transition is t=0 for one pseudo-
                // asynchronous BCI round. Returning from the BCI screen also
                // starts a fresh round for the item currently on screen.
                LaunchedEffect(
                    highlightKey,
                    bciActive.value,
                    showBci.value
                ) {
                    if (bciActive.value && !showBci.value) {
                        bciController?.onHighlightStarted(state.scanIntervalMs)
                    }
                }

                if (!showBci.value) {
                    // 正常输入法界面
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            MainScreen(
                                state = state,
                                onLeftLook = { vm.handleSignal(ControlSignal.LEFT_LOOK) },
                                onRightLook = { vm.handleSignal(ControlSignal.RIGHT_LOOK) },
                                onBite = { vm.handleSignal(ControlSignal.BITE) },
                                onSpeedUp = { vm.adjustSpeed(true) },
                                onSpeedDown = { vm.adjustSpeed(false) }
                            )
                            PredictionDebugOverlay(
                                info = predictionDebugInfo,
                                onExpired = vm::clearPredictionDebugInfo,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 20.dp, end = 20.dp, bottom = 108.dp)
                            )
                        }
                        // BCI 入口按钮
                        TextButton(
                            onClick = {
                                if (bleManager.hasPermissions()) {
                                    bciController?.stop()
                                    bciController = null
                                    bciActive.value = false
                                    showBci.value = true
                                }
                                else {
                                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                    bluetoothPermissionLauncher.launch(perms)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A2E))
                        ) {
                            Text(
                                if (bciActive.value) "🧠 BCI 控制运行中 · 点击管理"
                                else "🧠 连接脑电耳机 (BCI)",
                                color = if (bciActive.value) Color(0xFF4CAF50) else Color(0xFF5B8DEF),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    // BCI 流程界面
                    BciFlow(
                        bleManager = bleManager,
                        onStartControl = {
                            bciController?.stop()
                            BciController(applicationContext, bleManager) { signal ->
                                vm.handleSignal(signal)
                            }.also { controller ->
                                bciController = controller
                                if (!controller.loadModel()) {
                                    bciController = null
                                    bciActive.value = false
                                    controller.stop()
                                    return@BciFlow false
                                }
                                controller.start()
                                bciActive.value = true
                            }
                            true
                        },
                        onBack = { showBci.value = false }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        bciController?.stop()
        bciController = null
        bciActive.value = false
        bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
fun BciFlow(
    bleManager: NaoyunBleManager,
    onStartControl: () -> Boolean,
    onBack: () -> Unit
) {
    var screen by remember { mutableStateOf("scan") }
    var controlError by remember { mutableStateOf<String?>(null) }

    when (screen) {
        "scan" -> BleScanScreen(bleManager, onConnected = { screen = "monitor" })
        "monitor" -> SignalMonitorScreen(bleManager, onBack = {
            bleManager.disconnect(); screen = "scan"
        }, onStartCollect = { screen = "collect" }, onStartControl = {
            if (onStartControl()) {
                controlError = null
                // 返回输入界面，BCI 控制器由 Activity 持有并在后台运行。
                onBack()
            } else {
                controlError = "模型加载失败，无法启动 BCI 控制"
            }
        })
        "collect" -> {
            val collector = remember { DataCollector(bleManager, bleManager.context) }
            CollectionScreen(collector = collector, onBack = { collector.stop(); screen = "monitor" })
        }
    }

    controlError?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(
                bleManager.context,
                message,
                android.widget.Toast.LENGTH_LONG
            ).show()
            controlError = null
        }
    }
}
