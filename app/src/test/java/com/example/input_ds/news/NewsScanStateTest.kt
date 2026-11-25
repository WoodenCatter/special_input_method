package com.example.input_ds.news

import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.news.NewsFocusMode
import com.example.input_ds.ui.news.NewsScanState
import com.example.input_ds.ui.news.effectiveNewsScanDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsScanStateTest {
    @Test
    fun browseAndReadingAreDirectNavigationModesWithNoAutoAdvance() {
        val browse = NewsScanState().syncCounts(newArticleCount = 5, newSourceCount = 8)
        assertEquals(NewsFocusMode.BROWSE, browse.focusMode)
        assertTrue(browse.isDirectNavigationMode)
        assertEquals(browse, browse.advance(1_000L, ScanMode.AUTO))

        val reading = browse.enterReading()
        assertEquals(NewsFocusMode.READING, reading.focusMode)
        assertTrue(reading.isDirectNavigationMode)
        assertEquals(reading, reading.advance(1_000L, ScanMode.AUTO))
    }

    @Test
    fun browseMenuDefaultsToReadFullWhenArticlesExistAndLimitsItemsWhenEmpty() {
        val emptyMenu = NewsScanState().syncCounts(newArticleCount = 0, newSourceCount = 8)
            .enterBrowseMenu()
        assertEquals(
            listOf(NewsScanState.BROWSE_SOURCES, NewsScanState.BROWSE_REFRESH, NewsScanState.BROWSE_BACK),
            emptyMenu.enabledBrowseMenuTargets()
        )
        assertEquals(NewsScanState.BROWSE_SOURCES, emptyMenu.activeTargetIndex())

        val loadedMenu = emptyMenu.syncCounts(newArticleCount = 5, newSourceCount = 8)
            .enterBrowseMenu()
        assertEquals(6, loadedMenu.enabledBrowseMenuTargets().size)
        assertEquals(NewsScanState.BROWSE_READ_FULL, loadedMenu.activeTargetIndex())
    }

    @Test
    fun singleArticleDisablesNextArticleInReadingMenu() {
        val single = NewsScanState().syncCounts(newArticleCount = 1, newSourceCount = 8)
        val inMenu = single.enterReading().enterReadingMenu()
        assertEquals(
            listOf(NewsScanState.READING_CONTINUE, NewsScanState.READING_SPEAK, NewsScanState.READING_EXIT),
            inMenu.enabledReadingMenuTargets()
        )
    }

    @Test
    fun directoryUsesSlowerScanIntervalThanShortMenuButtons() {
        val menuDelay = effectiveNewsScanDelayMs(NewsFocusMode.BROWSE_MENU, 1_500L)
        val directoryDelay = effectiveNewsScanDelayMs(NewsFocusMode.ARTICLES, 1_500L)
        assertEquals(1_500L, menuDelay)
        assertTrue("Directory delay ($directoryDelay ms) should be >= 4000ms", directoryDelay >= 4_000L)
    }

    @Test
    fun biteGraceCompensatesRecentAutoAdvanceInMenus() {
        val menu = NewsScanState().syncCounts(newArticleCount = 5, newSourceCount = 8)
            .enterBrowseMenu(NewsScanState.BROWSE_READ_FULL)
        val advanced = menu.advance(nowMs = 1_000L, mode = ScanMode.AUTO)
        assertEquals(NewsScanState.BROWSE_CONTINUE, advanced.activeTargetIndex())

        val result = advanced.selectOnBite(nowMs = 1_150L, graceMs = 300L, mode = ScanMode.AUTO)
        assertNotNull(result)
        assertTrue(result!!.compensated)
        assertEquals(NewsScanState.BROWSE_READ_FULL, result.targetIndex)
    }

    @Test
    fun clearingArticlesWhileReadingDropsBackToBrowseMenu() {
        val reading = NewsScanState().syncCounts(newArticleCount = 4, newSourceCount = 8).enterReading()
        assertEquals(NewsFocusMode.READING, reading.focusMode)

        val emptied = reading.syncCounts(newArticleCount = 0, newSourceCount = 8)
        assertEquals(NewsFocusMode.BROWSE_MENU, emptied.focusMode)
    }
}
