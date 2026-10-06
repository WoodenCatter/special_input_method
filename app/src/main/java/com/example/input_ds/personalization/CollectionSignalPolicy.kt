package com.example.input_ds.personalization

/** Pure collection acceptance rules shared by the collector and JVM tests. */
internal object CollectionSignalPolicy {
    const val RATE_CHECK_WINDOW_MS = 1_000L
    const val REQUIRED_STABLE_WINDOWS = 2
    const val MIN_STABLE_RATE_HZ = 450f
    const val MAX_STABLE_RATE_HZ = 550f
    const val ACTION_WINDOW_GRACE_MS = 3_000L

    fun rateHz(samples: Int, elapsedMs: Long): Float {
        if (samples < 0 || elapsedMs <= 0L) return 0f
        return samples * 1_000f / elapsedMs
    }

    fun isStableRate(rateHz: Float): Boolean =
        rateHz in MIN_STABLE_RATE_HZ..MAX_STABLE_RATE_HZ

    fun actionWindowTimeoutMs(requiredPoints: Int, sampleRateHz: Int): Long {
        if (requiredPoints <= 0 || sampleRateHz <= 0) return ACTION_WINDOW_GRACE_MS
        return requiredPoints * 1_000L / sampleRateHz + ACTION_WINDOW_GRACE_MS
    }

    fun hasCompleteActionWindow(startSample: Int, endSample: Int, requiredPoints: Int): Boolean =
        requiredPoints > 0 && endSample - startSample >= requiredPoints

    fun hasValidActionWindow(
        startSample: Int,
        endSample: Int,
        requiredPoints: Int,
        startEpoch: Long,
        endEpoch: Long
    ): Boolean = startEpoch == endEpoch &&
        hasCompleteActionWindow(startSample, endSample, requiredPoints)
}
