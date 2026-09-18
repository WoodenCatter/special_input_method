package com.example.input_ds.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class StablePredictionMergeTest {
    @Test
    fun `remote candidates append without moving published candidates`() {
        val first = PredictionCandidate("吃饭", PredictionSource.LOCAL, id = "local-1")
        val second = PredictionCandidate("出去吃", PredictionSource.RIME, id = "rime-1")
        val result = StablePredictionMerge.append(
            existing = listOf(first),
            incoming = listOf(first.copy(id = "duplicate-text"), second),
            limit = 14
        )

        assertEquals(listOf("local-1", "rime-1"), result.candidates.map { it.id })
        assertEquals(1, result.addedCount)
    }

    @Test
    fun `duplicate id and overflow cannot change frozen prefix`() {
        val existing = listOf(
            PredictionCandidate("甲", PredictionSource.LOCAL, id = "one"),
            PredictionCandidate("乙", PredictionSource.LOCAL, id = "two")
        )
        val result = StablePredictionMerge.append(
            existing = existing,
            incoming = listOf(
                PredictionCandidate("丙", PredictionSource.LLM, id = "two"),
                PredictionCandidate("丁", PredictionSource.LLM, id = "four"),
                PredictionCandidate("戊", PredictionSource.LLM, id = "five")
            ),
            limit = 3
        )

        assertEquals(listOf("one", "two", "four"), result.candidates.map { it.id })
        assertEquals(1, result.addedCount)
    }
}
