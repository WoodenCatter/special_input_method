package com.example.input_ds.alert

import com.example.input_ds.model.ControlSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NurseCallStateTest {
    @Test
    fun countdownTriggersAutomaticallyAtZero() {
        var state = NurseCallState()
        assertEquals(5, state.remainingSeconds)
        assertFalse(state.sent)
        repeat(4) {
            state = state.tick()
            assertFalse(state.sent)
        }
        assertEquals(1, state.remainingSeconds)
        state = state.tick()
        assertTrue(state.sent)
        assertEquals(0, state.remainingSeconds)
        assertEquals(state, state.tick())
    }

    @Test
    fun leftCancelsAndRightOrBiteTriggersImmediately() {
        val initial = NurseCallState()
        assertEquals(NurseCallState.Action.Cancel, initial.onSignal(ControlSignal.LEFT_LOOK))

        val biteAction = initial.onSignal(ControlSignal.BITE) as NurseCallState.Action.Trigger
        assertTrue(biteAction.state.sent)

        val rightAction = initial.onSignal(ControlSignal.RIGHT_LOOK) as NurseCallState.Action.Trigger
        assertTrue(rightAction.state.sent)
    }

    @Test
    fun anySignalDismissesOnceCallIsSent() {
        val sent = NurseCallState(remainingSeconds = 0, sent = true)
        for (signal in ControlSignal.entries) {
            assertEquals(NurseCallState.Action.Dismiss, sent.onSignal(signal))
        }
    }
}
