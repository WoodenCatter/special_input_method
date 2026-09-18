package com.example.input_ds.bci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemporalEvidenceAccumulatorTest {
    @Test
    fun emitsOnceAfterThreeSupportedWindows() {
        val accumulator = TemporalEvidenceAccumulator(4)
        repeat(2) { accumulator.update(floatArrayOf(0.97f, 0.01f, 0.01f, 0.01f)) }
        assertNull(accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)).eventClass)
        assertNull(accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)).eventClass)
        assertEquals(2, accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)).eventClass)
        assertNull(accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)).eventClass)
    }

    @Test
    fun rearmRequiresRestEvidenceAndThreeConsecutiveCycles() {
        val accumulator = TemporalEvidenceAccumulator(4)
        repeat(3) { accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)) }
        accumulator.update(floatArrayOf(0.97f, 0.01f, 0.01f, 0.01f))
        accumulator.update(floatArrayOf(0.97f, 0.01f, 0.01f, 0.01f))
        val last = accumulator.update(floatArrayOf(0.97f, 0.01f, 0.01f, 0.01f))
        assertEquals(TemporalEvidenceAccumulator.Type.REARMED, last.type)
    }

    @Test
    fun rejectedPhysicalCandidateDoesNotLockAccumulator() {
        val accumulator = TemporalEvidenceAccumulator(4)
        repeat(2) { accumulator.update(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)) }
        assertNull(
            accumulator.update(
                floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)
            ) { false }.eventClass
        )
        assertEquals(
            2,
            accumulator.update(
                floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)
            ) { true }.eventClass
        )
    }
}
