package com.example.input_ds.bci

import kotlin.math.ceil
import kotlin.math.roundToInt

object AsyncWindowPolicy {
    const val STRIDE_SECONDS = 0.1f
    const val TASK_OVERLAP_FRACTION = 2f / 3f
    const val EVIDENCE_WINDOWS = 5
    const val CONFIDENCE_THRESHOLD = 0.85f
    const val SUPPORT_REQUIRED = 3
    const val PHYSICAL_SUPPORT_REQUIRED = 2
    const val REST_RESET_REQUIRED = 3

    fun stridePoints(windowPoints: Int, sampleRateHz: Int = 500): Int =
        minOf(windowPoints, maxOf(1, (STRIDE_SECONDS * sampleRateHz).roundToInt()))

    fun taskOverlapPoints(windowPoints: Int): Int =
        ceil(windowPoints * TASK_OVERLAP_FRACTION.toDouble()).toInt()
}
