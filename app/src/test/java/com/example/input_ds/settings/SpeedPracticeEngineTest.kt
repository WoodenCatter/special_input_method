package com.example.input_ds.settings

import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPracticeEngineTest {
    @Test
    fun slotZeroExitsPracticeImmediately() {
        val state = SpeedPracticeEngine.create(1_500L, nowMs = 100L)
        val (_, outcome) = SpeedPracticeEngine.chooseIndex(state, 0, nowMs = 200L)
        assertEquals(SpeedPracticeOutcome.Exit, outcome)
    }

    @Test
    fun twelveFastCleanHitsRecommendFasterClampedInterval() {
        var state = SpeedPracticeEngine.create(1_500L, nowMs = 1_000L)
        var now = 1_000L
        repeat(SpeedPracticeEngine.TOTAL_TRIALS) {
            while (state.cursor.index != state.targetIndex) {
                now += state.currentIntervalMs
                state = SpeedPracticeEngine.onTick(state, now, ScanMode.AUTO)
            }
            now += 400L
            val (next, outcome) = SpeedPracticeEngine.onBite(state, now, graceMs = 400L, scanMode = ScanMode.MANUAL)
            state = next
            assertTrue(outcome is SpeedPracticeOutcome.Continue)
        }
        assertEquals(SpeedPracticePhase.RESULT, state.phase)
        assertEquals(12, state.records.size)
        assertTrue(state.records.all { it.hit })
        assertTrue(state.recommendedIntervalMs < 1_500L)
        assertTrue(state.recommendedIntervalMs in ScanSettings.MIN_INTERVAL_MS..ScanSettings.MAX_INTERVAL_MS)
        assertEquals(0L, state.recommendedIntervalMs % 100L)

        val (_, applyOutcome) = SpeedPracticeEngine.chooseIndex(state, 0, now + 500L)
        assertEquals(SpeedPracticeOutcome.ApplyInterval(state.recommendedIntervalMs), applyOutcome)

        val (_, keepOutcome) = SpeedPracticeEngine.chooseIndex(state, 1, now + 500L)
        assertEquals(SpeedPracticeOutcome.KeepCurrent, keepOutcome)

        val (restarted, _) = SpeedPracticeEngine.chooseIndex(state, 2, now + 500L)
        assertEquals(SpeedPracticePhase.PRACTICING, restarted.phase)
        assertEquals(0, restarted.trialIndex)
        assertTrue(restarted.records.isEmpty())
    }

    @Test
    fun missesAndOvershootsRecommendSlowerIntervalAndPauseClearsDelayState() {
        var state = SpeedPracticeEngine.create(1_500L, nowMs = 1_000L)
        state = SpeedPracticeEngine.onLook(state, -1, ScanMode.AUTO)
        assertEquals(-1, state.cursor.direction)

        state = SpeedPracticeEngine.onTick(state, nowMs = 2_500L, scanMode = ScanMode.AUTO)
        assertTrue(state.cursor.previousIndex >= 0)

        val paused = SpeedPracticeEngine.resetForPauseBoundary(state)
        assertEquals(1, paused.cursor.direction)
        assertEquals(-1, paused.cursor.previousIndex)
        assertEquals(0L, paused.cursor.advancedAtMs)

        var now = 3_000L
        repeat(SpeedPracticeEngine.TOTAL_TRIALS) {
            val wrongSlot = (state.targetIndex % 4) + 1
            now += state.currentIntervalMs
            val (next, _) = SpeedPracticeEngine.chooseIndex(state, wrongSlot, now)
            state = next
        }
        assertEquals(SpeedPracticePhase.RESULT, state.phase)
        assertEquals(ScanSettings.MAX_INTERVAL_MS, state.recommendedIntervalMs)
    }

    @Test
    fun interveningCallPauseDoesNotInflateReactionTimeOrRecommendation() {
        fun runSequence(pauseOnTrials: Set<Int>): SpeedPracticeState {
            var state = SpeedPracticeEngine.create(1_500L, nowMs = 1_000L)
            var now = 1_000L
            for (trial in 0 until SpeedPracticeEngine.TOTAL_TRIALS) {
                while (state.cursor.index != state.targetIndex) {
                    now += state.currentIntervalMs
                    state = SpeedPracticeEngine.onTick(state, now, ScanMode.AUTO)
                }
                if (trial in pauseOnTrials) {
                    now += 100L
                    state = SpeedPracticeEngine.resetForPauseBoundary(state, resumed = false, nowMs = now)
                    now += 30_000L
                    state = SpeedPracticeEngine.resetForPauseBoundary(state, resumed = true, nowMs = now)
                }
                now += 500L
                val (next, outcome) = SpeedPracticeEngine.onBite(state, now, graceMs = 400L, scanMode = ScanMode.AUTO)
                assertTrue(outcome is SpeedPracticeOutcome.Continue)
                state = next
            }
            return state
        }

        val uninterrupted = runSequence(emptySet())
        val withCallPauses = runSequence(setOf(0, 4, 9))
        assertEquals(SpeedPracticePhase.RESULT, uninterrupted.phase)
        assertEquals(SpeedPracticePhase.RESULT, withCallPauses.phase)
        assertTrue(uninterrupted.records.all { it.hit })
        assertTrue(withCallPauses.records.all { it.hit })
        assertEquals(uninterrupted.records.map { it.reactionMs }, withCallPauses.records.map { it.reactionMs })
        assertEquals(uninterrupted.records.map { it.intervalMs }, withCallPauses.records.map { it.intervalMs })
        assertEquals(uninterrupted.recommendedIntervalMs, withCallPauses.recommendedIntervalMs)
    }
}
