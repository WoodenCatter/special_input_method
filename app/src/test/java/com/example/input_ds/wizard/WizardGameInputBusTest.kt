package com.example.input_ds.wizard

import com.example.input_ds.model.ControlSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardGameInputBusTest {
    @Test
    fun dispatchesOnlyWhileGameSinkIsAttached() {
        val received = mutableListOf<ControlSignal>()
        val sink = WizardGameSignalSink(received::add)

        assertFalse(WizardGameInputBus.dispatch(ControlSignal.LEFT_LOOK))
        WizardGameInputBus.attach(sink)
        assertTrue(WizardGameInputBus.dispatch(ControlSignal.RIGHT_LOOK))
        assertEquals(listOf(ControlSignal.RIGHT_LOOK), received)
        WizardGameInputBus.detach(sink)
        assertFalse(WizardGameInputBus.dispatch(ControlSignal.BITE))
    }
}
