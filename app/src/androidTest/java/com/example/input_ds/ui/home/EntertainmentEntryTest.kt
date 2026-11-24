package com.example.input_ds.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.input_ds.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EntertainmentEntryTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val grantPermissionsRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
                for (perm in listOf(
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO"
                )) {
                    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant $pkg $perm")
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                }
                base.evaluate()
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
    fun snakeGameOverRestartsAfterThreeUnansweredRounds() {
        bci("freeze on")
        bci("scan auto")
        bci("nav ent")
        bci("tick")
        bci("signal B")
        val gameOver = composeRule.onAllNodesWithText("游戏结束")
        composeRule.waitUntil(timeoutMillis = 60_000) {
            if (gameOver.fetchSemanticsNodes().isEmpty()) bci("tick")
            gameOver.fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选「重新开始」").assertIsDisplayed()
        repeat(5) { bci("tick") }
        composeRule.onNodeWithText("选「退出游戏」").assertIsDisplayed()
        bci("tick")
        composeRule.onNodeWithText("游戏结束").assertDoesNotExist()
        composeRule.onNodeWithText("娱乐 › 向上贪吃蛇").assertIsDisplayed()
    }

    @Test
    fun enteringEntertainmentFromHomeFocusesMazeInsteadOfBack() {
        bci("freeze on")
        bci("scan manual")
        bci("mode light")
        bci("nav home")
        composeRule.waitForIdle()

        // Home modules: [COMMUNICATION, LIFE_ASSISTANT, ENTERTAINMENT, ...]; two R moves focus 娱乐.
        bci("signal R")
        bci("signal R")
        bci("signal B")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1 / 8").assertIsDisplayed()
        composeRule.onNodeWithText("左 · 右 · 上 直接控制").assertIsDisplayed()
        composeRule.onNodeWithText("选「迷宫」").assertIsDisplayed()

        // Immediate bite enters Maze instead of navigating back to Home.
        bci("signal B")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("娱乐 › 迷宫").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("1 / 8").fetchSemanticsNodes().size)
    }
}
