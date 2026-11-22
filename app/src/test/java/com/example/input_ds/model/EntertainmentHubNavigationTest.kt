package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EntertainmentHubNavigationTest {
    @Test
    fun hubContainsSeniorMazeAndMigratedEntertainmentModules() {
        assertEquals(
            listOf(
                EntertainmentHubModule.MAZE,
                EntertainmentHubModule.SNAKE_CLIMB,
                EntertainmentHubModule.WIZARD_GAME,
                EntertainmentHubModule.CHINESE_CHESS,
                EntertainmentHubModule.DOUDIZHU,
                EntertainmentHubModule.MAHJONG,
                EntertainmentHubModule.TELEVISION,
                EntertainmentHubModule.MUSIC,
                EntertainmentHubModule.BACK
            ),
            EntertainmentHubModule.entries
        )
    }

    @Test
    fun hubAutomaticScanHonorsDirection() {
        val initial = EntertainmentHubSelectionState()

        assertEquals(EntertainmentHubModule.SNAKE_CLIMB, initial.advance().selectedModule)
        assertEquals(
            EntertainmentHubModule.BACK,
            initial.changeDirection(-1).advance().selectedModule
        )
    }
}
