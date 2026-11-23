package com.example.input_ds.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScanCursorTest {
    @Test fun autoAdvancesAndGraceHasAnExclusiveUpperBound() {
        val cursor = ScanCursor(3).advance(1000, ScanMode.AUTO)
        assertEquals(1,cursor.index)
        assertEquals(0,cursor.selected(1399,400,ScanMode.AUTO))
        assertEquals(1,cursor.selected(1400,400,ScanMode.AUTO))
        assertEquals(1,cursor.selected(1401,400,ScanMode.AUTO))
        assertEquals(1,cursor.selected(1000,0,ScanMode.AUTO))
    }

    @Test fun sameOrOppositeLookKeepsFocusAndClearsPreviousItem() {
        val cursor = ScanCursor(3).advance(1000,ScanMode.AUTO)
        for (step in listOf(-1,1)) {
            val held = cursor.look(step,ScanMode.AUTO)
            assertEquals(1,held.index)
            assertEquals(-1,held.previousIndex)
            assertEquals(1 + step,held.advance(2500,ScanMode.AUTO).index)
        }
        assertEquals(ScanCursor(4,2),cursor.reset(4,2))
        assertEquals(0, cursor.reset(0).index)
    }

    @Test fun confirmationDwellsLongerAndCancelsAfterThreeRounds() {
        for (size in listOf(2,4)) {
            var cursor = ScanCursor(size)
            assertEquals(2250,cursor.dwell(1500,true))
            repeat(3 * size) {
                assertFalse(cursor.timedOut(true))
                cursor = cursor.advance(it.toLong(),ScanMode.AUTO)
                assertEquals(1500,cursor.dwell(1500,true))
            }
            assertTrue(cursor.timedOut(true))
            assertFalse(cursor.timedOut(false)) // Incoming calls follow the server timeout.
            assertEquals(1,cursor.look(-1,ScanMode.AUTO,hold=true).direction)
        }
    }

    @Test fun manualMovesWrapWithoutGraceOrTimeout() {
        var cursor = ScanCursor(3).look(-1,ScanMode.MANUAL)
        assertEquals(2,cursor.index)
        repeat(10) { cursor = cursor.advance(it.toLong(),ScanMode.MANUAL) }
        assertEquals(2,cursor.selected(1,400,ScanMode.MANUAL))
        assertFalse(cursor.timedOut(true))
        assertEquals(0,cursor.look(1,ScanMode.MANUAL).index)
    }

    @Test fun timerReschedulesFailedTickAndPropagatesCancellation() {
        val delays = mutableListOf<Long>()
        var ticks = 0
        var errors = 0
        try {
            runBlocking {
                runScanTimer({ 1500L }, { errors++ }, { delays.add(it) }) {
                    ticks++
                    if (ticks == 1) error("tick")
                    throw CancellationException("end")
                }
            }
        } catch (_: CancellationException) { }
        assertEquals(listOf(1500L,1500L), delays)
        assertEquals(2,ticks)
        assertEquals(1,errors)
    }

    @Test fun savedModesArePreservedAndFreshInstallUsesAuto() {
        assertEquals(ScanMode.AUTO,ScanSettings.decodeMode(null))
        assertEquals(ScanMode.AUTO,ScanSettings.decodeMode("invalid"))
        assertEquals(ScanMode.AUTO,ScanSettings.decodeMode("AUTO"))
        assertEquals(ScanMode.MANUAL,ScanSettings.decodeMode("MANUAL"))
    }
}
