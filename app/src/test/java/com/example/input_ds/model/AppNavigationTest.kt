package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun homeHasFiveModulesAndDemoAlsoEntersContacts() {
        assertEquals(
            listOf(
                HomeModule.COMMUNICATION,
                HomeModule.LIFE_ASSISTANT,
                HomeModule.ENTERTAINMENT,
                HomeModule.APP_SETTINGS,
                HomeModule.CALL_NURSE
            ),
            HomeModule.entries
        )
        assertEquals(AppDestination.COMMUNICATION, HomeModule.COMMUNICATION.resolveDestination(demoCommunication = true))
        assertEquals(AppDestination.COMMUNICATION, HomeModule.COMMUNICATION.resolveDestination(demoCommunication = false))
        assertEquals(AppDestination.LIFE_ASSISTANT, HomeModule.LIFE_ASSISTANT.resolveDestination(true))
    }

    @Test
    fun manualHomeWrapsAcrossAllFiveModules() {
        val initial = HomeSelectionState()
        assertEquals(HomeModule.COMMUNICATION, initial.selectedModule)
        assertEquals(HomeModule.CALL_NURSE, initial.onDirection(-1, ScanMode.MANUAL).selectedModule)
        assertEquals(HomeModule.LIFE_ASSISTANT, initial.onDirection(1, ScanMode.MANUAL).selectedModule)
        assertEquals(
            HomeModule.COMMUNICATION,
            initial.moveRight().moveRight().moveRight().moveRight().moveRight().selectedModule
        )
    }

    @Test
    fun automaticHomeChangesDirectionOnlyUntilAdvance() {
        val initial = HomeSelectionState()
        val left = initial.onDirection(-1, ScanMode.AUTO)
        assertEquals(initial.selectedIndex, left.selectedIndex)
        assertEquals(-1, left.scanDirection)
        assertEquals(HomeModule.CALL_NURSE, left.advance().selectedModule)
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
