package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppNavigationTest {
    @Test
    fun manualHomeMovesAndSkipsHeadsetSettings() {
        val initial = HomeSelectionState()
        assertEquals(HomeModule.APP_SETTINGS, initial.selectedModule)
        assertEquals(HomeModule.ENTERTAINMENT, initial.onDirection(-1, ScanMode.MANUAL).selectedModule)
        assertEquals(HomeModule.REALTIME_COMMUNICATION, initial.onDirection(1, ScanMode.MANUAL).selectedModule)
        assertEquals(HomeModule.APP_SETTINGS, initial.moveRight().moveRight().moveRight().selectedModule)
        assertFalse(HomeModule.HEADSET_SETTINGS.scanEnabled)
    }

    @Test
    fun automaticHomeChangesDirectionOnlyUntilAdvance() {
        val initial = HomeSelectionState()
        val left = initial.onDirection(-1, ScanMode.AUTO)
        assertEquals(initial.selectedIndex, left.selectedIndex)
        assertEquals(-1, left.scanDirection)
        assertEquals(HomeModule.ENTERTAINMENT, left.advance().selectedModule)
    }

    @Test
    fun entertainmentManualAndAutomaticWrap() {
        val initial = EntertainmentHubSelectionState()
        assertEquals(EntertainmentHubModule.BACK, initial.onDirection(-1, ScanMode.MANUAL).selectedModule)
        val reverse = initial.onDirection(-1, ScanMode.AUTO)
        assertEquals(EntertainmentHubModule.MAZE, reverse.selectedModule)
        assertEquals(EntertainmentHubModule.BACK, reverse.advance().selectedModule)
    }
}
