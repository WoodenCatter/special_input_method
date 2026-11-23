package com.example.input_ds.ui.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.example.input_ds.assistant.AssistantCareStore
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AssistantScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun initDictionaries() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        CharacterDictionary.init(app)
        CommonPhraseUsage.init(app)
        LanguageModel.init(app)
        UserDictionary.init(app)
        AssistantCareStore.setDebugDraft("")
    }

    @Test
    fun longConversationKeepsConfirmationVisibleAndSelectable() {
        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    AssistantScreen(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        paused = false,
                        frozen = true,
                        fakeAssistant = true,
                        onBack = {},
                        onOpenMedia = { _, _ -> }
                    )
                }
            }
        }

        repeat(6) { round ->
            val multilinePrompt =
                "第${round + 1}条长问题：\n补充说明当前房间的状态与具体感受\n第三行继续增加对话气泡高度确保超出列表可视区域"
            composeRule.runOnUiThread { AssistantCareStore.setDebugDraft(multilinePrompt) }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
                AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
                AssistantInputBus.dispatch(ControlSignal.BITE)
            }
            composeRule.waitForIdle()
        }

        composeRule.runOnUiThread { AssistantCareStore.setDebugDraft("我热") }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
            AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
            AssistantInputBus.dispatch(ControlSignal.BITE)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("要执行这项操作吗？").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("降温到 25°C").assertIsDisplayed()

        composeRule.runOnIdle {
            AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
            AssistantInputBus.dispatch(ControlSignal.BITE)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("制冷 25°C").assertIsDisplayed()
        composeRule.onNodeWithText("模拟：已执行「降温到 25°C」").assertIsDisplayed()
    }

    @Test
    fun backgroundingDoesNotTimeoutConfirmation() {
        val owner = InstrumentationRegistry.getInstrumentation().let { instr ->
            lateinit var holder: FakeLifecycleOwner
            instr.runOnMainSync {
                holder = FakeLifecycleOwner().apply {
                    registry.currentState = Lifecycle.State.RESUMED
                }
            }
            holder
        }
        var scanMode by mutableStateOf(ScanMode.MANUAL)

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                InputDSTheme(false) {
                    Box(Modifier.size(1224.dp, 592.dp)) {
                        AssistantScreen(
                            scanIntervalMs = 1_100L,
                            scanMode = scanMode,
                            paused = false,
                            frozen = false,
                            fakeAssistant = true,
                            onBack = {},
                            onOpenMedia = { _, _ -> }
                        )
                    }
                }
            }
        }

        composeRule.runOnUiThread { AssistantCareStore.setDebugDraft("我热") }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
            AssistantInputBus.dispatch(ControlSignal.RIGHT_LOOK)
            AssistantInputBus.dispatch(ControlSignal.BITE)
        }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        try {
            fun readFocusedDwellProgress(): Float {
                val reader = composeRule
                    .onNode(SemanticsMatcher.keyIsDefined(AssistantDwellProgressReaderKey))
                    .fetchSemanticsNode()
                    .config[AssistantDwellProgressReaderKey]
                val value = reader()
                assertNotNull("Expected active dwell progress during confirmation AUTO scan", value)
                return value!!
            }

            composeRule.runOnUiThread { scanMode = ScanMode.AUTO }
            composeRule.mainClock.advanceTimeByFrame()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            composeRule.onNodeWithText("要执行这项操作吗？").assertIsDisplayed()
            composeRule.onNodeWithText("取消").assertIsDisplayed()

            // Advance partway through the first-round 1650 ms dwell on "取消".
            composeRule.mainClock.advanceTimeBy(800L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val midProgress = readFocusedDwellProgress()
            assertTrue("Expected mid-dwell progress in 0.25..0.75, was $midProgress", midProgress in 0.25f..0.75f)

            // Background longer than 3 * 2 dwells (1.5*1100 + 5*1100 = 7150 ms) and past dwell anim duration.
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            composeRule.mainClock.advanceTimeBy(3_000L)
            Thread.sleep(7_500L)
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.mainClock.advanceTimeByFrame()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            composeRule.onNodeWithText("要执行这项操作吗？").assertIsDisplayed()
            composeRule.onNodeWithText("取消").assertIsDisplayed()
            composeRule.onNodeWithText("取消：保持现状，不做改变").assertIsDisplayed()

            val restartedProgress = readFocusedDwellProgress()
            assertTrue("Expected dwell progress to restart < 0.2 after resume, was $restartedProgress", restartedProgress < 0.2f)

            composeRule.mainClock.advanceTimeBy(500L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val resumedMidProgress = readFocusedDwellProgress()
            assertTrue("Expected resumed dwell progress in 0.2..0.5, was $resumedMidProgress", resumedMidProgress in 0.2f..0.5f)

            // Wait for one resumed dwell (1650 ms) to advance focus from "取消" to "降温到 25°C".
            var advanced = false
            repeat(25) {
                if (composeRule.onAllNodesWithText("预览：确认后会这样变化").fetchSemanticsNodes().isNotEmpty()) {
                    advanced = true
                    return@repeat
                }
                Thread.sleep(100L)
                composeRule.mainClock.advanceTimeBy(100L)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            assertTrue("Expected confirmation focus to advance after one resumed dwell", advanced)
            composeRule.onNodeWithText("预览：确认后会这样变化").assertIsDisplayed()
            composeRule.onNodeWithText("制冷 25°C").assertIsDisplayed()
        } finally {
            composeRule.runOnUiThread { scanMode = ScanMode.MANUAL }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
