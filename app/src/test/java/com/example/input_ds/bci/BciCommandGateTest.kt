package com.example.input_ds.bci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BciCommandGateTest {
    @Test
    fun disablingInvalidatesCapturedWindow() {
        val gate = BciCommandGate(initiallyEnabled = true)
        val window = requireNotNull(gate.snapshot(acquisitionEpoch = 4L))

        gate.setEnabled(false)

        assertFalse(gate.isCurrent(window, acquisitionEpoch = 4L))
        assertNull(gate.snapshot(acquisitionEpoch = 4L))
    }

    @Test
    fun reenablingStartsNewCommandEpoch() {
        val gate = BciCommandGate(initiallyEnabled = true)
        val oldWindow = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))

        gate.setEnabled(false)
        gate.setEnabled(true)
        val newWindow = requireNotNull(gate.snapshot(acquisitionEpoch = 1L))

        assertNotEquals(oldWindow.commandEpoch, newWindow.commandEpoch)
        assertFalse(gate.isCurrent(oldWindow, acquisitionEpoch = 1L))
        assertTrue(gate.isCurrent(newWindow, acquisitionEpoch = 1L))
    }

    @Test
    fun acquisitionResetInvalidatesCapturedWindow() {
        val gate = BciCommandGate(initiallyEnabled = true)
        val beforeDisconnect = requireNotNull(gate.snapshot(acquisitionEpoch = 7L))

        assertFalse(gate.isCurrent(beforeDisconnect, acquisitionEpoch = 8L))
        assertTrue(
            gate.isCurrent(
                requireNotNull(gate.snapshot(acquisitionEpoch = 8L)),
                acquisitionEpoch = 8L
            )
        )
    }

    @Test
    fun explicitInvalidationRejectsInflightResult() {
        val gate = BciCommandGate(initiallyEnabled = true)
        val inflight = requireNotNull(gate.snapshot(acquisitionEpoch = 2L))

        gate.invalidate()

        assertFalse(gate.isCurrent(inflight, acquisitionEpoch = 2L))
    }
}
