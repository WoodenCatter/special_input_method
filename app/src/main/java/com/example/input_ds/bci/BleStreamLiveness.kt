package com.example.input_ds.bci

/** Monotonic per-ear stream liveness used by the BLE recovery state machine. */
internal class BleStreamLiveness(
    private val warningAfterMs: Long = WARNING_AFTER_MS,
    private val stalledAfterMs: Long = STALLED_AFTER_MS
) {
    enum class Health { STARTING, STREAMING, WARNING, STALLED }

    data class Snapshot(
        val health: Health,
        val leftAgeMs: Long?,
        val rightAgeMs: Long?,
        val pairAgeMs: Long?,
        val stalledSide: String?
    )

    private var startedAtMs = 0L
    private var leftAtMs = 0L
    private var rightAtMs = 0L
    private var pairAtMs = 0L

    @Synchronized
    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        leftAtMs = 0L
        rightAtMs = 0L
        pairAtMs = 0L
    }

    @Synchronized fun onLeft(nowMs: Long) { leftAtMs = nowMs }
    @Synchronized fun onRight(nowMs: Long) { rightAtMs = nowMs }
    @Synchronized fun onPair(nowMs: Long) { pairAtMs = nowMs }

    /** Gives local counter-reference recovery a fresh grace period without faking ear activity. */
    @Synchronized fun deferPairDeadline(nowMs: Long) { pairAtMs = nowMs }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        val leftAge = age(nowMs, leftAtMs)
        val rightAge = age(nowMs, rightAtMs)
        val pairAge = age(nowMs, pairAtMs)
        val effectivePairAge = pairAge ?: (nowMs - startedAtMs).coerceAtLeast(0L)
        val health = when {
            pairAtMs == 0L && effectivePairAge < warningAfterMs -> Health.STARTING
            effectivePairAge >= stalledAfterMs -> Health.STALLED
            effectivePairAge >= warningAfterMs -> Health.WARNING
            else -> Health.STREAMING
        }
        val stalledSide = when {
            health != Health.STALLED -> null
            leftAge == null && rightAge == null -> "双耳"
            (leftAge == null || leftAge >= stalledAfterMs) &&
                (rightAge == null || rightAge >= stalledAfterMs) -> "双耳"
            leftAge == null || leftAge >= stalledAfterMs -> "左耳"
            rightAge == null || rightAge >= stalledAfterMs -> "右耳"
            else -> "双耳配对"
        }
        return Snapshot(health, leftAge, rightAge, pairAge, stalledSide)
    }

    private fun age(nowMs: Long, timestampMs: Long): Long? =
        timestampMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }

    companion object {
        const val WARNING_AFTER_MS = 1_000L
        const val STALLED_AFTER_MS = 1_500L
    }
}
