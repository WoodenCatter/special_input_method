package com.example.input_ds.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.example.input_ds.MainActivity
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.settings.SpeedPracticeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppSettingsScreenTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val grantPermissionsRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                val pkg = target.packageName
                for (perm in listOf(
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO"
                )) {
                    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant $pkg $perm")
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                }
                val savedInterval = ScanSettings.read(target)
                ScanSettings.write(target, ScanSettings.DEFAULT_INTERVAL_MS)
                try {
                    base.evaluate()
                } finally {
                    ScanSettings.write(target, savedInterval)
                }
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(grantPermissionsRule).around(composeRule)

    private fun bci(cmd: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        val target = instrumentation.targetContext
        val latch = CountDownLatch(1)
        var resultCode = 0
        var resultData: String? = null
        uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.DUMP)
        try {
            target.sendOrderedBroadcast(
                Intent("com.example.input_ds.DEBUG").setPackage(target.packageName).putExtra("cmd", cmd),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        resultCode = this.resultCode
                        resultData = this.resultData
                        latch.countDown()
                    }
                },
                Handler(Looper.getMainLooper()),
                0,
                null,
                null
            )
            assertTrue("Timed out waiting for debug command: $cmd", latch.await(5, TimeUnit.SECONDS))
            assertEquals("Unexpected result for $cmd ($resultData)", -1, resultCode)
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun adjustOverlayUpdatesValueAndRespectsCaregiverSheetBackPriority() {
        bci("freeze on")
        bci("scan manual")
        bci("mode light")
        bci("nav settings")
        composeRule.waitForIdle()

        // Verify all 8 §7.9 setting rows exist and initial focus is 音量 (60%).
        composeRule.onNodeWithText("返回主页").assertIsDisplayed()
        composeRule.onNodeWithText("速度练习").assertIsDisplayed()
        composeRule.onNodeWithText("12 题").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("音量").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("60%").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("整个应用的声音大小，包括朗读、通话和提示音。").assertIsDisplayed()

        // Bite opens AdjustOverlay for 音量 focused on 增大 (index 1), step to 70%.
        bci("signal B")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("减小").assertIsDisplayed()
        composeRule.onNodeWithText("增大").assertIsDisplayed()
        composeRule.onNodeWithText("完成").assertIsDisplayed()

        bci("signal B")
        composeRule.waitForIdle()
        assertEquals(3, composeRule.onAllNodesWithText("70%").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText("70%")[2].assertIsDisplayed()

        // Open CaregiverSheet over AdjustOverlay; Back must close CaregiverSheet only.
        bci("care on")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("照护面板").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("照护面板").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(3, composeRule.onAllNodesWithText("70%").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("减小").assertIsDisplayed()

        // Next Back closes AdjustOverlay, leaving 音量 focused with 70% shown in settings.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("减小").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("70%").fetchSemanticsNodes().size)

        // Toggle 扫描方式 and cycle 显示模式 via manual navigation + Bite.
        bci("signal R") // BRIGHTNESS
        bci("signal R") // SPEED
        bci("signal R") // SCAN_MODE
        bci("signal B") // MANUAL -> AUTO
        composeRule.waitForIdle()
        assertEquals(2, composeRule.onAllNodesWithText("自动").fetchSemanticsNodes().size)

        bci("scan manual")
        composeRule.waitForIdle()
        assertEquals(2, composeRule.onAllNodesWithText("手动").fetchSemanticsNodes().size)

        // Started in LIGHT mode, so cycling goes LIGHT -> DARK -> AUTO -> LIGHT.
        // Note StatusBar also displays "日间" / "夜间" / "日间 · 自动".
        bci("signal R") // DISPLAY_MODE (currently 日间)
        composeRule.waitForIdle()
        assertEquals(3, composeRule.onAllNodesWithText("日间").fetchSemanticsNodes().size)
        bci("signal B")
        composeRule.waitForIdle()
        assertEquals(3, composeRule.onAllNodesWithText("夜间").fetchSemanticsNodes().size)
        bci("signal B")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("日间 · 自动").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("自动").fetchSemanticsNodes().size)
        bci("signal B")
        composeRule.waitForIdle()
        assertEquals(3, composeRule.onAllNodesWithText("日间").fetchSemanticsNodes().size)
    }

    @Test
    fun incomingVideoPausesSettingsAndAdjustAndRejectsCallBeforeClosingAdjust() {
        bci("freeze on")
        bci("scan auto")
        bci("mode light")
        bci("nav settings")
        composeRule.waitForIdle()

        // 1. Incoming video pauses Settings list focus.
        bci("fake incoming")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接听").assertIsDisplayed()

        bci("freeze off")
        Thread.sleep(2_100L)
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        Thread.sleep(150L)
        bci("freeze on")
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("接听").fetchSemanticsNodes().size)
        // Still focused on 音量 (row 1), not advanced during incoming video.
        composeRule.onNodeWithText("整个应用的声音大小，包括朗读、通话和提示音。").assertIsDisplayed()

        // 2. Open AdjustOverlay, launch incoming video, wait > dwell, press Back once -> rejects call only.
        bci("signal B")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("选「增大」").assertIsDisplayed()

        bci("fake incoming")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接听").assertIsDisplayed()

        bci("freeze off")
        Thread.sleep(2_100L)
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        Thread.sleep(150L)
        bci("freeze on")
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("接听").fetchSemanticsNodes().size)
        // AdjustOverlay must remain open behind/after the call, still focused on 增大 (1).
        composeRule.onNodeWithText("减小").assertIsDisplayed()
        composeRule.onNodeWithText("增大").assertIsDisplayed()
        composeRule.onNodeWithText("选「增大」").assertIsDisplayed()

        // Next Back dismisses AdjustOverlay.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("减小").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("整个应用的声音大小，包括朗读、通话和提示音。").assertIsDisplayed()
    }

    @Test
    fun adjustPauseClearsDelayCompensationAndResetsDirectionToForward() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = target.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val savedVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val startVol = (maxVol / 2).coerceIn(2, (maxVol - 2).coerceAtLeast(2))
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, startVol, 0)
        try {
            bci("freeze on")
            bci("scan auto")
            bci("mode light")
            bci("nav settings")
            composeRule.waitForIdle()

            // Open AdjustOverlay for 音量 (defaults to index 1 = 增大).
            bci("signal B")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("选「增大」").assertIsDisplayed()

            // Switch live, turn left, tick once (1 -> 0 减小), immediately pause & resume CaregiverSheet within < 400ms.
            bci("freeze off")
            bci("signal L")
            bci("tick")
            bci("care on")
            bci("care off")

            // Bite must execute current index 0 (减小), NOT stale previousIndex 1 (增大).
            bci("signal B")
            val afterStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertTrue("Expected volume to decrease below $startVol, got $afterStep", afterStep < startVol)

            // Pause reset direction to +1, so one tick moves forward 0 -> 1 (增大).
            bci("tick")
            bci("freeze on")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("选「增大」").assertIsDisplayed()
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
        }
    }

    @Test
    fun volumeAdjustReadsBackRealHardwareLevelAndMatchesOnReopen() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = target.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val savedVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val startVol = (maxVol * 0.6f).toInt().coerceIn(1, (maxVol - 1).coerceAtLeast(1))
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, startVol, 0)
        try {
            bci("scan manual")
            bci("mode light")
            bci("nav settings")
            bci("freeze off")
            composeRule.waitForIdle()

            val initialPct = AppSettingsLogic.streamLevelToPercent(startVol, maxVol)
            assertEquals(2, composeRule.onAllNodesWithText("${initialPct}%").fetchSemanticsNodes().size)

            // Open AdjustOverlay -> Bite 增大 -> readback from AudioManager must match UI now & on reopen.
            bci("signal B")
            composeRule.waitForIdle()
            bci("signal B")
            composeRule.waitForIdle()

            val actualVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val actualPct = AppSettingsLogic.streamLevelToPercent(actualVol, maxVol)
            assertTrue("Expected hardware volume > $startVol, got $actualVol", actualVol > startVol)
            assertEquals(3, composeRule.onAllNodesWithText("${actualPct}%").fetchSemanticsNodes().size)

            // Close AdjustOverlay via 完成 (R -> B), then reopen via B and confirm exact same percentage.
            bci("signal R")
            bci("signal B")
            composeRule.waitForIdle()
            assertEquals(2, composeRule.onAllNodesWithText("${actualPct}%").fetchSemanticsNodes().size)

            bci("signal B")
            composeRule.waitForIdle()
            assertEquals(3, composeRule.onAllNodesWithText("${actualPct}%").fetchSemanticsNodes().size)
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
        }
    }

    @Test
    fun speedPracticeCompletesTwelveTrialsAndAdoptsRecommendedInterval() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val savedInterval = ScanSettings.read(target)
        try {
            ScanSettings.write(target, ScanSettings.DEFAULT_INTERVAL_MS)
            bci("freeze on")
            bci("scan manual")
            bci("mode light")
            bci("nav settings")
            composeRule.waitForIdle()

            // Row 1 -> Row 7 (速度练习) via 6 RIGHT_LOOK moves, then Bite.
            repeat(6) { bci("signal R") }
            bci("signal B")
            composeRule.waitForIdle()

            composeRule.onNodeWithText("第 1 / 12 题").assertIsDisplayed()

            // Trial 1: advance from slot 1 to target slot 2 and bite via BCI signal.
            bci("tick")
            composeRule.waitForIdle()
            bci("signal B")
            composeRule.waitForIdle()

            // Complete trials 2..12 by clicking each target card directly.
            for (trial in 1 until SpeedPracticeEngine.TOTAL_TRIALS) {
                composeRule.onNodeWithText("第 ${trial + 1} / 12 题").assertIsDisplayed()
                val slotLabel = SpeedPracticeEngine.TARGET_SEQUENCE[trial].toString()
                composeRule.onNodeWithText(slotLabel).performClick()
                composeRule.waitForIdle()
            }

            composeRule.onNodeWithText("练习完成 · 12 / 12 题").assertIsDisplayed()
            composeRule.onNodeWithText("命中 12 / 12 题 · 当前停留 1.5 秒").assertIsDisplayed()
            composeRule.onNodeWithText("采用 1.1 秒").performClick()
            composeRule.waitForIdle()

            // Returned to Settings focused on 速度练习 with 每项停留 updated to 1.1 秒.
            composeRule.onNodeWithText("做 12 次目标选择，系统推荐合适的停留时间，由你决定是否采用。").assertIsDisplayed()
            composeRule.onNodeWithText("1.1 秒").assertIsDisplayed()
            assertEquals(1_100L, ScanSettings.read(target))
        } finally {
            ScanSettings.write(target, savedInterval)
        }
    }

    @Test
    fun tappingIncomingVideoOverHiddenBrightnessRowDoesNotOpenAdjust() {
        bci("freeze on")
        bci("scan manual")
        bci("mode light")
        bci("nav settings")
        composeRule.waitForIdle()

        val brightnessCenter = composeRule.onNodeWithText("屏幕亮度").fetchSemanticsNode().boundsInRoot.center

        bci("fake incoming")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接听").assertIsDisplayed()
        composeRule.onNodeWithText("拒绝").assertIsDisplayed()

        // Tap where the hidden 屏幕亮度 row was rendered behind the incoming call screen.
        composeRule.onRoot().performTouchInput { click(brightnessCenter) }
        composeRule.waitForIdle()

        // Press 拒绝 by coordinates, move 1px before release, and confirm the call still dismisses.
        val rejectCenter = composeRule.onNodeWithText("拒绝").fetchSemanticsNode().boundsInRoot.center
        composeRule.onRoot().performTouchInput {
            down(rejectCenter)
            moveTo(Offset(rejectCenter.x + 1f, rejectCenter.y))
            up()
        }
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("接听").fetchSemanticsNodes().isEmpty()
        }

        // Underlying Settings must remain focused on 音量 with no AdjustOverlay opened.
        assertEquals(0, composeRule.onAllNodesWithText("减小").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("增大").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("完成").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("整个应用的声音大小，包括朗读、通话和提示音。").assertIsDisplayed()
    }
}
