package com.example.input_ds.ui.tv

import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TvScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullscreenLeftAndRightSwitchChannelsAndExitFullscreen() {
        var resetKey by mutableIntStateOf(0)
        var hiddenCalls = 0
        val callback = WebChromeClient.CustomViewCallback { hiddenCalls += 1 }

        composeRule.setContent {
            val context = LocalContext.current
            key(resetKey) {
                val fullscreen = remember(resetKey) { FrameLayout(context) to callback }
                InputDSTheme(false) {
                    Box(Modifier.size(1224.dp, 592.dp)) {
                        TvScreenContent(
                            scanIntervalMs = 1_500L,
                            scanMode = ScanMode.AUTO,
                            onBack = {},
                            initialChannelId = TV_CHANNELS[1].id,
                            fromAssistant = true,
                            initialFullscreen = fullscreen,
                            enableWebView = false
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.LEFT_LOOK) }
        composeRule.waitForIdle()

        assertEquals(1, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("左看上一台 · 右看下一台 · 咬牙打开菜单 · 当前：${TV_CHANNELS[0].name}").assertIsDisplayed()

        composeRule.runOnUiThread { resetKey += 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.RIGHT_LOOK) }
        composeRule.waitForIdle()

        assertEquals(2, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("左看上一台 · 右看下一台 · 咬牙打开菜单 · 当前：${TV_CHANNELS[2].name}").assertIsDisplayed()
    }

    @Test
    fun fullscreenBiteExitsFullscreenAndOpensMenuFocusedOnContinue() {
        var hiddenCalls = 0
        val callback = WebChromeClient.CustomViewCallback { hiddenCalls += 1 }

        composeRule.setContent {
            val context = LocalContext.current
            val fullscreen = remember { FrameLayout(context) to callback }
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        onBack = {},
                        initialChannelId = TV_CHANNELS[1].id,
                        fromAssistant = true,
                        initialFullscreen = fullscreen,
                        enableWebView = false
                    )
                }
            }
        }

        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertEquals(1, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)
        // "电视操作" and returnLabel appear both in the top bar and inside TvOperationOverlay.
        assertEquals(2, composeRule.onAllNodesWithText("电视操作").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("继续观看").assertIsDisplayed()
        composeRule.onNodeWithText("重新加载").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("返回助手").fetchSemanticsNodes().size)

        // Second bite confirms the initially focused "继续观看" item and closes the menu.
        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithText("电视操作").fetchSemanticsNodes().size)
    }

    @Test
    fun backPressClosesTvMenuWithoutInvokingOnBack() {
        var hiddenCalls = 0
        var onBackCalls = 0
        var fallbackBackCalls = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        val callback = WebChromeClient.CustomViewCallback { hiddenCalls += 1 }

        composeRule.setContent {
            val context = LocalContext.current
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val fullscreen = remember { FrameLayout(context) to callback }
            BackHandler { fallbackBackCalls += 1 }
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        onBack = { onBackCalls += 1 },
                        initialChannelId = TV_CHANNELS[1].id,
                        fromAssistant = true,
                        initialFullscreen = fullscreen,
                        enableWebView = false
                    )
                }
            }
        }

        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()
        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertEquals(1, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("电视操作").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("继续观看").assertIsDisplayed()

        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithText("电视操作").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("继续观看").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("左看上一台 · 右看下一台 · 咬牙打开菜单 · 当前：${TV_CHANNELS[1].name}").assertIsDisplayed()
        assertEquals(0, onBackCalls)
        assertEquals(0, fallbackBackCalls)

        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, fallbackBackCalls)
        assertEquals(0, onBackCalls)
    }
}
