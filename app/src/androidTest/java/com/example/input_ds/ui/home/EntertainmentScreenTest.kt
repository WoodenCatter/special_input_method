package com.example.input_ds.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentHubSelectionState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EntertainmentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun galleryStartsCenteredOnMazeShowsCounterAndFlipsNextBadgeOnLeftDirection() {
        var selection by mutableStateOf(EntertainmentHubSelectionState())

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    EntertainmentScreen(
                        selection = selection,
                        scanMode = ScanMode.AUTO,
                        scanIntervalMs = 1_500L,
                        dwellRevision = 0,
                        paused = false,
                        frozen = true,
                        liveMusicCount = 10,
                        onSelect = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("1 / 8").assertIsDisplayed()
        composeRule.onNodeWithText("左 · 右 · 上 直接控制").assertIsDisplayed()
        composeRule.onNodeWithText("下一个 ▶").assertIsDisplayed()

        composeRule.runOnUiThread {
            selection = selection.onDirection(-1, ScanMode.AUTO)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1 / 8").assertIsDisplayed()
        composeRule.onNodeWithText("◀ 下一个").assertIsDisplayed()
    }

    @Test
    fun manualWrapAcrossBackUpdatesCounterAndRailSelection() {
        var selection by mutableStateOf(EntertainmentHubSelectionState())
        val selected = mutableListOf<EntertainmentHubModule>()

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    EntertainmentScreen(
                        selection = selection,
                        scanMode = ScanMode.MANUAL,
                        scanIntervalMs = 1_500L,
                        dwellRevision = 0,
                        paused = false,
                        frozen = true,
                        liveMusicCount = 4,
                        onSelect = { selected += it }
                    )
                }
            }
        }

        composeRule.runOnUiThread {
            selection = selection.onDirection(-1, ScanMode.MANUAL)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("— / 8").assertIsDisplayed()
        composeRule.onNodeWithText("回到主页选择其他功能").assertIsDisplayed()

        composeRule.runOnUiThread {
            selection = selection.onDirection(-1, ScanMode.MANUAL)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("8 / 8").assertIsDisplayed()
        composeRule.onNodeWithText("本地 10 首").assertIsDisplayed()

        composeRule.onAllNodesWithText("向上贪吃蛇")[0].performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(EntertainmentHubModule.SNAKE_CLIMB), selected)
    }
}
