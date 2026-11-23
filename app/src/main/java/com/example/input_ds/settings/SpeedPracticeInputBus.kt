package com.example.input_ds.settings

import com.example.input_ds.model.ControlSignal

internal fun interface SpeedPracticeSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Routes BCI signals and debug ticks to the scan speed practice screen. */
internal object SpeedPracticeInputBus {
    @Volatile
    private var sink: SpeedPracticeSignalSink? = null

    @Volatile
    private var tickHook: (() -> Unit)? = null

    fun attach(
        candidate: SpeedPracticeSignalSink,
        onTick: () -> Unit = {}
    ) {
        sink = candidate
        tickHook = onTick
    }

    fun detach(candidate: SpeedPracticeSignalSink) {
        if (sink === candidate) {
            sink = null
            tickHook = null
        }
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }

    fun debugTick(): Boolean {
        val hook = tickHook ?: return false
        hook()
        return true
    }
}
