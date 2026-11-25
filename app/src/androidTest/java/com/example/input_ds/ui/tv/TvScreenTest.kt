package com.example.input_ds.ui.tv

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.input_ds.bci.BciCommandGate
import com.example.input_ds.bci.BciController
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.tv.TvInputBus
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.GazeFeedback
import com.example.input_ds.ui.design.PatientScaffold
import com.example.input_ds.ui.design.PatientStatus
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

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
        composeRule.onNodeWithText(TV_CHANNELS[0].name.substringAfter(' ')).assertIsDisplayed()

        composeRule.runOnUiThread { resetKey += 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.RIGHT_LOOK) }
        composeRule.waitForIdle()

        assertEquals(2, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)
        composeRule.onNodeWithText(TV_CHANNELS[2].name.substringAfter(' ')).assertIsDisplayed()
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
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("继续观看").assertIsDisplayed()
        composeRule.onNodeWithText("重新加载").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("返回助手").fetchSemanticsNodes().size)

        // Second bite confirms the initially focused "继续观看" item and closes the menu.
        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
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
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("继续观看").assertIsDisplayed()

        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("继续观看").fetchSemanticsNodes().size)
        composeRule.onNodeWithText(TV_CHANNELS[1].name.substringAfter(' ')).assertIsDisplayed()
        assertEquals(0, onBackCalls)
        assertEquals(0, fallbackBackCalls)

        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, fallbackBackCalls)
        assertEquals(0, onBackCalls)
    }

    @Test
    fun frozenTvKeepsBannerAndCircularFiveSlotStripVisible() {
        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.AUTO,
                        onBack = {},
                        enableWebView = true,
                        frozen = true
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(3_500L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("● 直播").assertIsDisplayed()
        composeRule.onNodeWithText("综合").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("CCTV-1").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("CCTV-16").assertIsDisplayed()
        composeRule.onNodeWithText("CCTV-17").assertIsDisplayed()
        composeRule.onNodeWithText("CCTV-2").assertIsDisplayed()
        composeRule.onNodeWithText("CCTV-3").assertIsDisplayed()
    }

    @Test
    fun tvMenuBlocksBackgroundChannelTapsAndHandlesJitteredForegroundTap() {
        var onBackCalls = 0
        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        onBack = { onBackCalls += 1 },
                        initialChannelId = TV_CHANNELS[0].id,
                        enableWebView = false,
                        frozen = true
                    )
                }
            }
        }

        val cctv2Bounds = composeRule.onNodeWithText("CCTV-2").fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("电视菜单").assertIsDisplayed()

        // Tap inside the bottom margin below the menu buttons, directly over the hidden CCTV-2 tile.
        composeRule.onRoot().performTouchInput {
            down(Offset(cctv2Bounds.center.x, cctv2Bounds.bottom - 6f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        assertEquals(0, onBackCalls)

        // Press "继续观看" by root coordinates, jitter 1px, release — menu should dismiss cleanly and channel remain CCTV-1.
        val continueCenter = composeRule.onNodeWithText("继续观看").fetchSemanticsNode().boundsInRoot.center
        composeRule.onRoot().performTouchInput {
            down(continueCenter)
            moveTo(Offset(continueCenter.x + 1f, continueCenter.y))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("综合").assertIsDisplayed()
        assertEquals(0, onBackCalls)
    }

    @Test
    fun tvMenuPausesScanningAndBackWhilePaused() {
        var paused by mutableStateOf(false)
        var fallbackBackCalls = 0
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { fallbackBackCalls += 1 }
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_100L,
                        scanMode = ScanMode.AUTO,
                        onBack = {},
                        enableWebView = false,
                        paused = paused,
                        frozen = true
                    )
                }
            }
        }

        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("电视菜单").assertIsDisplayed()

        // While paused, TV's BackHandler is disabled so outer handler receives Back.
        composeRule.runOnUiThread { paused = true }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, fallbackBackCalls)
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)

        // Unpause and confirm Back closes the TV menu.
        composeRule.runOnUiThread { paused = false }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, fallbackBackCalls)
        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
    }

    @Test
    fun tvMenuBoundaryInvalidatesPendingHeadsetCommandsAcrossTapBiteAndBack() {
        val gate = BciCommandGate(initiallyEnabled = true)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    TvScreenContent(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        onBack = {},
                        initialChannelId = TV_CHANNELS[0].id,
                        enableWebView = false,
                        frozen = true,
                        onBoundary = { gate.invalidate() }
                    )
                }
            }
        }

        // Capture a pending BITE window during playback, then tap live badge to open the menu.
        val preMenuWindow = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))
        composeRule.onNodeWithText("● 直播").performClick()
        composeRule.waitForIdle()

        assertFalse(gate.isCurrent(preMenuWindow, acquisitionEpoch = 1L))
        if (gate.isCurrent(preMenuWindow, acquisitionEpoch = 1L)) {
            TvInputBus.dispatch(ControlSignal.BITE)
        }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)

        // Capture an in-menu window, then close via system Back.
        val inMenuWindow = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertFalse(gate.isCurrent(inMenuWindow, acquisitionEpoch = 1L))
        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)

        // Capture a pre-menu window, open via BITE, verify invalidation, then close via "继续观看".
        val beforeBiteMenu = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))
        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertFalse(gate.isCurrent(beforeBiteMenu, acquisitionEpoch = 1L))
        val inSecondMenu = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))
        composeRule.runOnIdle { TvInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()

        assertFalse(gate.isCurrent(inSecondMenu, acquisitionEpoch = 1L))
        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
    }

    @Test
    fun tvMenuRendersFullStageInsidePatientScaffoldWithoutDoubleInset() {
        var densityPx = 1f
        composeRule.setContent {
            densityPx = LocalDensity.current.density
            InputDSTheme(false) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    PatientScaffold(
                        status = PatientStatus(
                            crumb = listOf("娱乐", "看电视"),
                            connection = "耳机未连接",
                            leftBattery = null,
                            rightBattery = null,
                            mode = ScanMode.MANUAL,
                            interval = 1_500L,
                            display = "日间"
                        ),
                        hints = ActionHints("向左轮转", "选「继续观看」", "向右轮转"),
                        feedback = GazeFeedback(),
                        readiness = BciController.Readiness()
                    ) {
                        TvScreenContent(
                            scanIntervalMs = 1_500L,
                            scanMode = ScanMode.MANUAL,
                            onBack = {},
                            initialChannelId = TV_CHANNELS[0].id,
                            enableWebView = false,
                            frozen = true
                        )
                    }
                }
            }
        }

        val cctv2Bounds = composeRule.onNodeWithText("CCTV-2").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText("● 直播").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("电视菜单").assertIsDisplayed()

        // Title at 48.dp horizontal inset (not 76.dp) and first button row fills stage minus 48.dp each side & 34.dp bottom above ActionBar (112.dp).
        val titleBounds = composeRule.onNodeWithText("电视菜单").fetchSemanticsNode().boundsInRoot
        val continueTextBounds = composeRule.onNodeWithText("继续观看").fetchSemanticsNode().boundsInRoot
        val returnTextBounds = composeRule.onNodeWithText("返回娱乐").fetchSemanticsNode().boundsInRoot
        val stageTopPx = 56f * densityPx
        val actionBarTopPx = (800f - 112f) * densityPx
        val titleLeftDp = titleBounds.left / densityPx
        val titleTopFromStageDp = (titleBounds.top - stageTopPx) / densityPx
        assertTrue("Expected title left ~48dp, was $titleLeftDp", abs(titleLeftDp - 48f) <= 2f)
        assertTrue("Expected title top ~34dp from stage, was $titleTopFromStageDp", abs(titleTopFromStageDp - 34f) <= 2f)

        // Button centers span 48..1232 (with 16dp gaps -> each button 384dp wide, center 240dp and 1040dp).
        val firstCenterDp = continueTextBounds.center.x / densityPx
        val lastCenterDp = returnTextBounds.center.x / densityPx
        val rowCenterFromActionBarDp = (actionBarTopPx - continueTextBounds.center.y) / densityPx
        assertTrue("Expected first button center ~240dp, was $firstCenterDp", abs(firstCenterDp - 240f) <= 3f)
        assertTrue("Expected last button center ~1040dp, was $lastCenterDp", abs(lastCenterDp - 1040f) <= 3f)
        assertTrue("Expected row center ~93dp above ActionBar (59 + 34), was $rowCenterFromActionBarDp", abs(rowCenterFromActionBarDp - 93f) <= 3f)

        // Tap bottom margin over hidden CCTV-2 tile; pointer blocker should eat it without changing channel or closing menu.
        composeRule.onRoot().performTouchInput {
            down(Offset(cctv2Bounds.center.x, actionBarTopPx - 8f * densityPx))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)

        // Jittered 1px foreground tap on "继续观看" closes the menu and preserves CCTV-1.
        composeRule.onRoot().performTouchInput {
            down(continueTextBounds.center)
            moveTo(Offset(continueTextBounds.center.x + 1f, continueTextBounds.center.y))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("电视菜单").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("综合").assertIsDisplayed()
    }

    @Test
    fun freezeCyclesReleaseEachLiveWebViewAndCleanupFullscreen() {
        var frozen by mutableStateOf(false)
        val created = mutableListOf<WebView>()
        val released = mutableListOf<WebView>()
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
                        initialChannelId = TV_CHANNELS[2].id,
                        initialFullscreen = fullscreen,
                        enableWebView = true,
                        frozen = frozen,
                        onWebViewCreated = { created += it },
                        onWebViewReleased = {
                            released += it
                            releaseTvWebView(it)
                        }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, created.size)
        assertEquals(0, released.size)
        composeRule.onNodeWithText("退出全屏").assertIsDisplayed()

        // First freeze removes AndroidView, releases first WebView, and tears down fullscreen custom view.
        composeRule.runOnUiThread { frozen = true }
        composeRule.waitForIdle()
        assertEquals(1, released.size)
        assertTrue(released[0] === created[0])
        assertEquals(1, hiddenCalls)
        assertEquals(0, composeRule.onAllNodesWithText("退出全屏").fetchSemanticsNodes().size)

        // Unfreezing creates a fresh second WebView.
        composeRule.runOnUiThread { frozen = false }
        composeRule.waitForIdle()
        assertEquals(2, created.size)
        assertNotSame(created[0], created[1])

        // Second freeze releases the second WebView too.
        composeRule.runOnUiThread { frozen = true }
        composeRule.waitForIdle()
        assertEquals(2, released.size)
        assertTrue(released[1] === created[1])
    }
}
