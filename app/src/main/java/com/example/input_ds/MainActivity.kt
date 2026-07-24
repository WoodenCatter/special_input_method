package com.example.input_ds

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.ui.components.MainScreen
import com.example.input_ds.ui.theme.InputDSTheme
import com.example.input_ds.viewmodel.InputMethodViewModel

/**
 * BCI 扫描式中文输入法 - 主 Activity
 *
 * 该应用实现了基于扫描交互的中文输入系统，
 * 用户通过"左看""右看""咬牙"三种控制信号完成中文输入。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化字库（从 assets 加载完整拼音-汉字映射）
        CharacterDictionary.init(applicationContext)

        setContent {
            InputDSTheme {
                val viewModel: InputMethodViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                MainScreen(
                    state = state,
                    onLeftLook = { viewModel.handleSignal(ControlSignal.LEFT_LOOK) },
                    onRightLook = { viewModel.handleSignal(ControlSignal.RIGHT_LOOK) },
                    onBite = { viewModel.handleSignal(ControlSignal.BITE) },
                    onSpeedUp = { viewModel.adjustSpeed(true) },
                    onSpeedDown = { viewModel.adjustSpeed(false) }
                )
            }
        }
    }
}
