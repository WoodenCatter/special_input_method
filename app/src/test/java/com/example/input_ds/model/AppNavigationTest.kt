package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun homeSelectionWrapsInBothDirections() {
        val initial = HomeSelectionState()

        assertEquals(HomeModule.SETTINGS, initial.selectedModule)
        assertEquals(HomeModule.ENTERTAINMENT, initial.moveLeft().selectedModule)
        assertEquals(HomeModule.REALTIME_COMMUNICATION, initial.moveRight().selectedModule)
        assertEquals(
            HomeModule.SETTINGS,
            initial.moveRight().moveRight().moveRight().selectedModule
        )
    }

    @Test
    fun automaticScanDirectionCanBeChanged() {
        val initial = HomeSelectionState()

        assertEquals(HomeModule.REALTIME_COMMUNICATION, initial.advance().selectedModule)
        assertEquals(
            HomeModule.ENTERTAINMENT,
            initial.changeDirection(-1).advance().selectedModule
        )
    }
}
