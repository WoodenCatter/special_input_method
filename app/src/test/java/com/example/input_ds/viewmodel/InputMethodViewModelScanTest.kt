package com.example.input_ds.viewmodel

import com.example.input_ds.model.*
import org.junit.Assert.*
import org.junit.Test

class InputMethodViewModelScanTest {
    @Test fun directionsPreserveAllCandidateIndicesIncludingWhenRepeated() {
        for (phase in InputPhase.entries.filterNot { it == InputPhase.LEVEL_1_SCANNING }) {
            var state = InputState(phase = phase, highlightedPinyinIndex = 2, highlightedCharIndex = 3,
                highlightedPredictionIndex = 4, highlightedInitialIndex = 5)
            for (step in listOf(1, 1, -1, -1)) {
                val result = redirectCandidateScan(state, if (step < 0) ControlSignal.LEFT_LOOK else ControlSignal.RIGHT_LOOK)
                assertEquals(state.copy(charScanDirection = step), result)
                state = requireNotNull(result)
            }
            assertNull(redirectCandidateScan(state, ControlSignal.BITE))
        }
        assertNull(redirectCandidateScan(InputState(), ControlSignal.LEFT_LOOK))
    }
}
