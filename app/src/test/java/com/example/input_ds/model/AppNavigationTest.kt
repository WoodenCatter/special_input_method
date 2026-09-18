package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun gamesAppearBeforeMusic() {
        assertEquals(
            listOf(
                HomeModule.SETTINGS,
                HomeModule.REALTIME_COMMUNICATION,
                HomeModule.MAZE,
                HomeModule.SNAKE_CLIMB,
                HomeModule.WIZARD_GAME,
                HomeModule.CHINESE_CHESS,
                HomeModule.DOUDIZHU,
                HomeModule.MAHJONG,
                HomeModule.TELEVISION,
                HomeModule.MUSIC
            ),
            HomeModule.entries
        )
    }

    @Test
    fun homeSelectionWrapsInBothDirections() {
        val initial = HomeSelectionState()

        assertEquals(HomeModule.SETTINGS, initial.selectedModule)
        assertEquals(HomeModule.MUSIC, initial.moveLeft().selectedModule)
        assertEquals(HomeModule.REALTIME_COMMUNICATION, initial.moveRight().selectedModule)
        var wrapped = initial
        repeat(HomeModule.entries.size) { wrapped = wrapped.moveRight() }
        assertEquals(HomeModule.SETTINGS, wrapped.selectedModule)
    }
}
