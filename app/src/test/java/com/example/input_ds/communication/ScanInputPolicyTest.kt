package com.example.input_ds.communication

import com.example.input_ds.model.ControlSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanInputPolicyTest {
    @Test fun matchingDirectionSkipsOnce() {
        assertTrue(ScanInputPolicy.shouldSkip(ControlSignal.LEFT_LOOK, -1))
        assertTrue(ScanInputPolicy.shouldSkip(ControlSignal.RIGHT_LOOK, 1))
        assertFalse(ScanInputPolicy.shouldSkip(ControlSignal.RIGHT_LOOK, -1))
        assertFalse(ScanInputPolicy.shouldSkip(ControlSignal.LEFT_LOOK, 1))
        assertFalse(ScanInputPolicy.shouldSkip(ControlSignal.BITE, 1))
    }
}
