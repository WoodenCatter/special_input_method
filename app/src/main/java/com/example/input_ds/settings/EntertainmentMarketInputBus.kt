package com.example.input_ds.settings

import com.example.input_ds.model.ControlSignal

internal fun interface EntertainmentMarketSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Routes BCI signals, debug ticks, and focus resets to the entertainment market screen. */
internal object EntertainmentMarketInputBus {
    @Volatile
    private var sink: EntertainmentMarketSignalSink? = null

    @Volatile
    private var tickHook: (() -> Unit)? = null

    @Volatile
    private var resetFocusHook: ((Int) -> Unit)? = null

    fun attach(
        candidate: EntertainmentMarketSignalSink,
        onTick: () -> Unit = {},
        onResetFocus: (Int) -> Unit = {}
    ) {
        sink = candidate
        tickHook = onTick
        resetFocusHook = onResetFocus
    }

    fun detach(candidate: EntertainmentMarketSignalSink) {
        if (sink === candidate) {
            sink = null
            tickHook = null
            resetFocusHook = null
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

    fun resetFocus(index: Int = 1) {
        resetFocusHook?.invoke(index)
    }
}
