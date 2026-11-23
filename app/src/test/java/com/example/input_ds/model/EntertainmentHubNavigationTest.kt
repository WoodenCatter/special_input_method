package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EntertainmentHubNavigationTest {
    @Test
    fun hubContainsBackFirstAndMigratedEntertainmentModules() {
        assertEquals(
            listOf(
                EntertainmentHubModule.BACK,
                EntertainmentHubModule.MAZE,
                EntertainmentHubModule.SNAKE_CLIMB,
                EntertainmentHubModule.WIZARD_GAME,
                EntertainmentHubModule.CHINESE_CHESS,
                EntertainmentHubModule.DOUDIZHU,
                EntertainmentHubModule.MAHJONG,
                EntertainmentHubModule.TELEVISION,
                EntertainmentHubModule.MUSIC
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
    fun entryFocusUsesMazeFromHomeAndCentersReturningModule() {
        assertEquals(
            EntertainmentHubModule.MAZE.ordinal,
            EntertainmentHubModule.initialIndexForEntry(AppDestination.HOME)
        )
        assertEquals(
            EntertainmentHubModule.MAZE.ordinal,
            EntertainmentHubModule.initialIndexForEntry(AppDestination.LIFE_ASSISTANT)
        )
        assertEquals(
            EntertainmentHubModule.SNAKE_CLIMB.ordinal,
            EntertainmentHubModule.initialIndexForEntry(AppDestination.SNAKE_CLIMB)
        )
        assertEquals(
            EntertainmentHubModule.TELEVISION.ordinal,
            EntertainmentHubModule.initialIndexForEntry(AppDestination.TV)
        )
        assertEquals(
            EntertainmentHubModule.MUSIC.ordinal,
            EntertainmentHubModule.initialIndexForEntry(AppDestination.MUSIC)
        )
    }
}
