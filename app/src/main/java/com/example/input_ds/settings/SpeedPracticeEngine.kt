package com.example.input_ds.settings

import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SpeedPracticePhase {
    PRACTICING,
    RESULT
}

internal data class SpeedPracticeTrial(
    val trialNumber: Int,
    val targetIndex: Int,
    val intervalMs: Long,
    val hit: Boolean,
    val reactionMs: Long,
    val overshoots: Int
)

internal sealed interface SpeedPracticeOutcome {
    data object Exit : SpeedPracticeOutcome
    data class Continue(val toast: String? = null, val positive: Boolean = false) : SpeedPracticeOutcome
    data class ApplyInterval(val intervalMs: Long) : SpeedPracticeOutcome
    data object KeepCurrent : SpeedPracticeOutcome
}

internal data class SpeedPracticeState(
    val phase: SpeedPracticePhase,
    val initialIntervalMs: Long,
    val currentIntervalMs: Long,
    val trialIndex: Int,
    val targetIndex: Int,
    val cursor: ScanCursor,
    val overshootsInTrial: Int = 0,
    val targetEnteredAtMs: Long = 0L,
    val records: List<SpeedPracticeTrial> = emptyList(),
    val recommendedIntervalMs: Long = initialIntervalMs
)

/** Deterministic 12-target practice engine that calibrates recommended auto-scan dwell time. */
internal object SpeedPracticeEngine {
    const val TOTAL_TRIALS = 12
    const val OPTION_COUNT = 5 // 0: exit navigation row, 1..4: target slots
    val TARGET_SEQUENCE = listOf(2, 4, 1, 3, 2, 1, 4, 3, 1, 4, 2, 3)
    val RESULT_LABELS = listOf("采用推荐", "保持当前", "再练一次")

    fun create(initialIntervalMs: Long, nowMs: Long = 0L): SpeedPracticeState {
        val clamped = initialIntervalMs.coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)
        return SpeedPracticeState(
            phase = SpeedPracticePhase.PRACTICING,
            initialIntervalMs = clamped,
            currentIntervalMs = clamped,
            trialIndex = 0,
            targetIndex = TARGET_SEQUENCE[0],
            cursor = ScanCursor(size = OPTION_COUNT, index = 1, direction = 1, advancedAtMs = nowMs),
            targetEnteredAtMs = 0L
        )
    }

    fun onLook(state: SpeedPracticeState, step: Int, scanMode: ScanMode): SpeedPracticeState {
        val effectiveMode = if (state.phase == SpeedPracticePhase.PRACTICING) ScanMode.AUTO else scanMode
        return state.copy(cursor = state.cursor.look(step, effectiveMode))
    }

    fun onTick(state: SpeedPracticeState, nowMs: Long, scanMode: ScanMode): SpeedPracticeState {
        if (state.phase == SpeedPracticePhase.RESULT) {
            return state.copy(cursor = state.cursor.advance(nowMs, scanMode))
        }
        val nextCursor = state.cursor.advance(nowMs, ScanMode.AUTO)
        val overshoot = if (state.cursor.index == state.targetIndex && nextCursor.index != state.targetIndex) 1 else 0
        val enteredAt = if (nextCursor.index == state.targetIndex) nowMs else state.targetEnteredAtMs
        return state.copy(
            cursor = nextCursor,
            overshootsInTrial = state.overshootsInTrial + overshoot,
            targetEnteredAtMs = enteredAt
        )
    }

    fun onBite(
        state: SpeedPracticeState,
        nowMs: Long,
        graceMs: Long,
        scanMode: ScanMode
    ): Pair<SpeedPracticeState, SpeedPracticeOutcome> {
        val effectiveMode = if (state.phase == SpeedPracticePhase.PRACTICING) ScanMode.AUTO else scanMode
        val chosen = state.cursor.selected(nowMs, graceMs, effectiveMode)
        return chooseIndex(state, chosen, nowMs)
    }

    fun chooseIndex(
        state: SpeedPracticeState,
        chosenIndex: Int,
        nowMs: Long
    ): Pair<SpeedPracticeState, SpeedPracticeOutcome> {
        if (state.phase == SpeedPracticePhase.RESULT) {
            return when (chosenIndex.coerceIn(0, RESULT_LABELS.lastIndex)) {
                0 -> state to SpeedPracticeOutcome.ApplyInterval(state.recommendedIntervalMs)
                1 -> state to SpeedPracticeOutcome.KeepCurrent
                else -> create(state.initialIntervalMs, nowMs) to SpeedPracticeOutcome.Continue(
                    toast = "重新开始速度练习",
                    positive = true
                )
            }
        }
        if (chosenIndex <= 0) {
            return state to SpeedPracticeOutcome.Exit
        }
        val hit = chosenIndex == state.targetIndex
        val entered = if (hit && state.targetEnteredAtMs > 0L) state.targetEnteredAtMs else state.cursor.advancedAtMs
        val reaction = if (nowMs >= entered && entered > 0L) {
            (nowMs - entered).coerceIn(0L, state.currentIntervalMs * 2)
        } else {
            state.currentIntervalMs / 2
        }
        val record = SpeedPracticeTrial(
            trialNumber = state.trialIndex + 1,
            targetIndex = state.targetIndex,
            intervalMs = state.currentIntervalMs,
            hit = hit,
            reactionMs = reaction,
            overshoots = state.overshootsInTrial
        )
        val records = state.records + record
        val deltaMs = when {
            !hit || state.overshootsInTrial > 0 -> 200L
            reaction <= state.currentIntervalMs * 3L / 4L -> -100L
            else -> 0L
        }
        val nextInterval = (state.currentIntervalMs + deltaMs)
            .coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)

        if (records.size >= TOTAL_TRIALS) {
            val recommended = computeRecommendedIntervalMs(records, state.initialIntervalMs)
            return state.copy(
                phase = SpeedPracticePhase.RESULT,
                currentIntervalMs = nextInterval,
                cursor = ScanCursor(size = RESULT_LABELS.size, index = 0, direction = 1),
                overshootsInTrial = 0,
                targetEnteredAtMs = 0L,
                records = records,
                recommendedIntervalMs = recommended
            ) to SpeedPracticeOutcome.Continue(
                toast = "练习完成，推荐停留 ${formatInterval(recommended)}",
                positive = true
            )
        }

        val nextTrialIndex = records.size
        val nextTarget = TARGET_SEQUENCE[nextTrialIndex]
        val startIndex = (nextTarget % 4) + 1
        val feedbackToast = if (hit) "命中第 ${record.trialNumber} 题" else "未命中目标（目标为 ${record.targetIndex} 号）"
        return state.copy(
            phase = SpeedPracticePhase.PRACTICING,
            currentIntervalMs = nextInterval,
            trialIndex = nextTrialIndex,
            targetIndex = nextTarget,
            cursor = ScanCursor(size = OPTION_COUNT, index = startIndex, direction = 1, advancedAtMs = nowMs),
            overshootsInTrial = 0,
            targetEnteredAtMs = 0L,
            records = records
        ) to SpeedPracticeOutcome.Continue(toast = feedbackToast, positive = hit)
    }

    fun resetForPauseBoundary(
        state: SpeedPracticeState,
        resumed: Boolean = false,
        nowMs: Long = 0L
    ): SpeedPracticeState {
        val nextEnteredAt = if (
            resumed &&
            state.phase == SpeedPracticePhase.PRACTICING &&
            state.cursor.index == state.targetIndex
        ) {
            nowMs
        } else {
            0L
        }
        return state.copy(
            cursor = state.cursor.reset(),
            targetEnteredAtMs = nextEnteredAt
        )
    }

    fun computeRecommendedIntervalMs(records: List<SpeedPracticeTrial>, fallbackMs: Long): Long {
        if (records.isEmpty()) {
            return fallbackMs.coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)
        }
        val hits = records.filter { it.hit }
        val baseMs = if (hits.isNotEmpty()) {
            hits.map { it.intervalMs * 0.6 + (it.reactionMs + 450L) * 0.4 }.average()
        } else {
            ScanSettings.MAX_INTERVAL_MS.toDouble()
        }
        val misses = records.count { !it.hit }
        val overshoots = records.sumOf { it.overshoots }
        val raw = baseMs + misses * 120.0 + overshoots * 50.0
        val rounded = (raw / 100.0).roundToInt() * 100L
        return rounded.coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)
    }

    fun formatInterval(intervalMs: Long): String =
        String.format(Locale.ROOT, "%.1f 秒", intervalMs / 1000.0)
}
