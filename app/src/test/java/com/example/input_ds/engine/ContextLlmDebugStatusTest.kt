package com.example.input_ds.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextLlmDebugStatusTest {
    @Test
    fun `successful empty response is not reported as appended`() {
        assertEquals(
            PredictionDebugStatus.LLM_EMPTY,
            contextLlmDebugStatus(
                responseAvailable = true,
                rawCount = 0,
                acceptedCount = 0,
                addedCount = 0
            )
        )
    }

    @Test
    fun `only a real append is reported as mixed success`() {
        assertEquals(
            PredictionDebugStatus.LLM_RESULTS_FILTERED,
            contextLlmDebugStatus(true, rawCount = 2, acceptedCount = 0, addedCount = 0)
        )
        assertEquals(
            PredictionDebugStatus.LLM_NO_NEW_CANDIDATES,
            contextLlmDebugStatus(true, rawCount = 2, acceptedCount = 2, addedCount = 0)
        )
        assertEquals(
            PredictionDebugStatus.LLM_MIXED_SUCCESS,
            contextLlmDebugStatus(true, rawCount = 2, acceptedCount = 2, addedCount = 1)
        )
    }
}
