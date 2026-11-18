package com.example.input_ds.communication

import com.example.input_ds.model.ControlSignal

/** A matching direction skips exactly one highlighted candidate. */
object ScanInputPolicy {
    fun shouldSkip(signal: ControlSignal, direction: Int): Boolean =
        (signal == ControlSignal.LEFT_LOOK && direction == -1) ||
            (signal == ControlSignal.RIGHT_LOOK && direction == 1)
}
