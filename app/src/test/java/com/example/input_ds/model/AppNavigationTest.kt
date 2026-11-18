package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppNavigationTest {
    @Test
    fun demoLaunchesStandaloneInputAndProductionLaunchesContacts() {
        val entry = HomeModule.REALTIME_COMMUNICATION
        assertEquals(AppDestination.INPUT_METHOD, entry.resolveDestination(demoCommunication = true))
        assertEquals(AppDestination.COMMUNICATION, entry.resolveDestination(demoCommunication = false))
        assertEquals(AppDestination.LIFE_ASSISTANT, HomeModule.LIFE_ASSISTANT.resolveDestination(true))
    }
    @Test
    fun manualHomeMovesAndSkipsHeadsetSettings() {
        val initial = HomeSelectionState()
        assertEquals(HomeModule.APP_SETTINGS, initial.selectedModule)
        assertEquals(HomeModule.LIFE_ASSISTANT, initial.onDirection(-1, ScanMode.MANUAL).selectedModule)
        assertEquals(HomeModule.REALTIME_COMMUNICATION, initial.onDirection(1, ScanMode.MANUAL).selectedModule)
        assertEquals(HomeModule.APP_SETTINGS, initial.moveRight().moveRight().moveRight().moveRight().selectedModule)
        assertFalse(HomeModule.HEADSET_SETTINGS.scanEnabled)
    }

    @Test
    fun automaticHomeChangesDirectionOnlyUntilAdvance() {
        val initial = HomeSelectionState()
        val left = initial.onDirection(-1, ScanMode.AUTO)
        assertEquals(initial.selectedIndex, left.selectedIndex)
        assertEquals(-1, left.scanDirection)
        assertEquals(HomeModule.LIFE_ASSISTANT, left.advance().selectedModule)
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
