package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntertainmentHubNavigationTest {
    @Test
    fun hubContainsBackFirstAndMigratedEntertainmentModules() {
        assertEquals(
            listOf(
                EntertainmentHubModule.BACK,
                EntertainmentHubModule.MAZE,
                EntertainmentHubModule.SNAKE_CLIMB,
                EntertainmentHubModule.WATERMELON,
                EntertainmentHubModule.LIFE_RESTART,
                EntertainmentHubModule.A_DARK_ROOM,
                EntertainmentHubModule.WIZARD_GAME,
                EntertainmentHubModule.CHINESE_CHESS,
                EntertainmentHubModule.DOUDIZHU,
                EntertainmentHubModule.MAHJONG,
                EntertainmentHubModule.TELEVISION,
                EntertainmentHubModule.MUSIC,
                EntertainmentHubModule.NEWS_BRIEFING
            ),
            EntertainmentHubModule.entries
        )
    }

    @Test
    fun hubStartsOnMazeAndAutomaticScanHonorsDirection() {
        val initial = EntertainmentHubSelectionState()
        assertEquals(EntertainmentHubModule.MAZE, initial.selectedModule)
        assertEquals(EntertainmentHubModule.SNAKE_CLIMB, initial.advance().selectedModule)
        assertEquals(
            EntertainmentHubModule.BACK,
            initial.changeDirection(-1).advance().selectedModule
        )
    }

    @Test
    fun catalogGroupsIntoThreeCategoriesWhenMoreThanFourItemsAcrossCategories() {
        val all = EntertainmentHubModule.allSubFeatureIds
        assertFalse(EntertainmentCatalog.shouldFlatten(all))

        val root = EntertainmentCatalog.entriesFor(null, all)
        assertEquals(
            listOf("返回主页", "视听资讯", "棋牌", "游戏", "功能市场"),
            root.map { it.displayName }
        )

        val games = EntertainmentCatalog.entriesFor(EntertainmentCategory.GAMES, all)
        assertEquals(
            listOf("返回娱乐", "迷宫", "向上贪吃蛇", "合成大西瓜", "人生重开", "小黑屋", "魔法师"),
            games.map { it.displayName }
        )

        val board = EntertainmentCatalog.entriesFor(EntertainmentCategory.BOARD, all)
        assertEquals(
            listOf("返回娱乐", "中国象棋", "斗地主", "麻将"),
            board.map { it.displayName }
        )

        val media = EntertainmentCatalog.entriesFor(EntertainmentCategory.MEDIA, all)
        assertEquals(
            listOf("返回娱乐", "看电视", "音乐", "看新闻"),
            media.map { it.displayName }
        )
    }

    @Test
    fun catalogAutoFlattensAtOrBelowFourItemsOrSingleCategoryOrEmpty() {
        val fourCrossCategory = setOf(
            EntertainmentHubModule.TELEVISION.id,
            EntertainmentHubModule.MUSIC.id,
            EntertainmentHubModule.CHINESE_CHESS.id,
            EntertainmentHubModule.MAZE.id
        )
        assertTrue(EntertainmentCatalog.shouldFlatten(fourCrossCategory))
        assertEquals(
            listOf("返回主页", "看电视", "音乐", "中国象棋", "迷宫", "功能市场"),
            EntertainmentCatalog.entriesFor(null, fourCrossCategory).map { it.displayName }
        )
        assertNull(EntertainmentCatalog.resolveCategory(EntertainmentCategory.GAMES, fourCrossCategory))

        val onlyGames = EntertainmentHubModule.modulesIn(EntertainmentCategory.GAMES).map { it.id }.toSet()
        assertTrue(EntertainmentCatalog.shouldFlatten(onlyGames))
        assertEquals(
            listOf("返回主页", "迷宫", "向上贪吃蛇", "合成大西瓜", "人生重开", "小黑屋", "魔法师", "功能市场"),
            EntertainmentCatalog.entriesFor(null, onlyGames).map { it.displayName }
        )

        val empty = emptySet<String>()
        assertTrue(EntertainmentCatalog.shouldFlatten(empty))
        assertEquals(
            listOf("返回主页", "功能市场"),
            EntertainmentCatalog.entriesFor(null, empty).map { it.displayName }
        )
    }

    @Test
    fun catalogHidesEmptyCategoriesAndPreservesReturnFocus() {
        val noBoard = EntertainmentHubModule.allSubFeatureIds -
            EntertainmentHubModule.modulesIn(EntertainmentCategory.BOARD).map { it.id }.toSet()
        assertFalse(EntertainmentCatalog.shouldFlatten(noBoard))
        assertEquals(
            listOf("返回主页", "视听资讯", "游戏", "功能市场"),
            EntertainmentCatalog.entriesFor(null, noBoard).map { it.displayName }
        )

        // Returning from Snake stays in GAMES category focused on 向上贪吃蛇 (index 2).
        val (cat, idx) = EntertainmentCatalog.entryContextFor(
            previous = AppDestination.SNAKE_CLIMB,
            currentCategory = null,
            enabledIds = EntertainmentHubModule.allSubFeatureIds
        )
        assertEquals(EntertainmentCategory.GAMES, cat)
        assertEquals(2, idx)

        // Entering from HOME starts at root focused on first category (index 1).
        val (homeCat, homeIdx) = EntertainmentCatalog.entryContextFor(
            previous = AppDestination.HOME,
            currentCategory = EntertainmentCategory.GAMES,
            enabledIds = EntertainmentHubModule.allSubFeatureIds
        )
        assertNull(homeCat)
        assertEquals(1, homeIdx)
    }
}
