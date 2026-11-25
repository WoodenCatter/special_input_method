package com.example.input_ds.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.EntertainmentCatalog
import com.example.input_ds.model.EntertainmentCategory
import com.example.input_ds.model.EntertainmentGalleryEntry
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EntertainmentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun galleryStartsCenteredOnFirstCategoryShowsCounterAndFlipsNextBadgeOnLeftDirection() {
        val entries = EntertainmentCatalog.entriesFor(null, EntertainmentHubModule.allSubFeatureIds)
        var cursor by mutableStateOf(ScanCursor(size = entries.size, index = 1, direction = 1))

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    EntertainmentScreen(
                        title = "娱乐",
                        entries = entries,
                        selectedIndex = cursor.index,
                        scanDirection = cursor.direction,
                        scanMode = ScanMode.AUTO,
                        scanIntervalMs = 1_500L,
                        dwellRevision = 0,
                        paused = false,
                        frozen = true,
                        onSelect = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("视听资讯").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("下一个 ▶").assertIsDisplayed()

        composeRule.runOnUiThread {
            cursor = cursor.look(-1, ScanMode.AUTO)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("◀ 下一个").assertIsDisplayed()
    }

    @Test
    fun manualWrapAcrossBackUpdatesCounterAndRailSelection() {
        val entries = EntertainmentCatalog.entriesFor(null, EntertainmentHubModule.allSubFeatureIds)
        var cursor by mutableStateOf(ScanCursor(size = entries.size, index = 1, direction = 1))
        val selected = mutableListOf<EntertainmentGalleryEntry>()

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    EntertainmentScreen(
                        title = "娱乐",
                        entries = entries,
                        selectedIndex = cursor.index,
                        scanDirection = cursor.direction,
                        scanMode = ScanMode.MANUAL,
                        scanIntervalMs = 1_500L,
                        dwellRevision = 0,
                        paused = false,
                        frozen = true,
                        onSelect = { selected += it }
                    )
                }
            }
        }

        composeRule.runOnUiThread {
            cursor = cursor.look(-1, ScanMode.MANUAL)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("— / 4").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("返回主页").fetchSemanticsNodes().size)

        composeRule.runOnUiThread {
            cursor = cursor.look(-1, ScanMode.MANUAL)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4 / 4").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("功能市场").fetchSemanticsNodes().size)

        composeRule.onAllNodesWithText("游戏")[0].performClick()
        composeRule.waitForIdle()
        assertEquals(
            EntertainmentCategory.GAMES,
            (selected.single() as EntertainmentGalleryEntry.Category).category
        )
    }

    @Test
    fun flattenedGalleryShowsDirectModulesWhenFourOrFewerEnabled() {
        val subset = setOf(
            EntertainmentHubModule.TELEVISION.id,
            EntertainmentHubModule.CHINESE_CHESS.id,
            EntertainmentHubModule.MAZE.id
        )
        val entries = EntertainmentCatalog.entriesFor(null, subset)
        var idx by mutableIntStateOf(1)

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    EntertainmentScreen(
                        title = "娱乐",
                        entries = entries,
                        selectedIndex = idx,
                        scanDirection = 1,
                        scanMode = ScanMode.MANUAL,
                        scanIntervalMs = 1_500L,
                        dwellRevision = 0,
                        paused = false,
                        frozen = true,
                        onSelect = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("看电视").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("视听资讯").fetchSemanticsNodes().size)
    }
}
