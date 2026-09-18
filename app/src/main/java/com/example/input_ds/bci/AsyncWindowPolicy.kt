package com.example.input_ds.bci

import kotlin.math.roundToInt

object AsyncWindowPolicy {
    const val STRIDE_FRACTION = 0.25f

    fun stridePoints(windowPoints: Int): Int =
        maxOf(1, (windowPoints * STRIDE_FRACTION).roundToInt())
}
