package com.example.input_ds.bci

/** Converts a BLE callback's packet batch into packet-end monotonic timestamps. */
internal object EegCallbackTimestampPolicy {
    fun packetEndTimesUs(
        callbackTimeUs: Long,
        packetSampleCounts: List<Int>,
        samplePeriodUs: Long = 2_000L
    ): LongArray {
        require(samplePeriodUs > 0L)
        require(packetSampleCounts.all { it >= 0 })
        val result = LongArray(packetSampleCounts.size)
        var trailingSamples = 0L
        for (index in packetSampleCounts.indices.reversed()) {
            result[index] = callbackTimeUs - trailingSamples * samplePeriodUs
            trailingSamples += packetSampleCounts[index]
        }
        return result
    }
}
