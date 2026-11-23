package com.example.input_ds.ui.assistant

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
import com.example.input_ds.assistant.AssistantCareStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AssistantBackBehaviorTest {
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
                    execShell("pm grant $pkg $perm")
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(grantPermissionsRule).around(composeRule)

    private fun execShell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

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
    fun backPressWithCaregiverSheetOverConfirmationClosesSheetOnly() {
        bci("freeze on")
        bci("scan manual")
        bci("fake assistant")
        bci("nav asst")
        bci("fake home reset")
        composeRule.waitForIdle()

        composeRule.runOnUiThread { AssistantCareStore.setDebugDraft("我热") }
        composeRule.waitForIdle()

        bci("signal R")
        bci("signal R")
        bci("signal B")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("要执行这项操作吗？").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("选「取消」").assertIsDisplayed()

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

        composeRule.onNodeWithText("要执行这项操作吗？").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("降温到 25°C").assertIsDisplayed()
        composeRule.onNodeWithText("选「取消」").assertIsDisplayed()
    }
}
