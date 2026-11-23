package com.example.input_ds.settings

import com.example.input_ds.model.ControlSignal

internal fun interface AppSettingsSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Routes BCI signals, debug ticks, and focus resets to the patient settings screen. */
internal object AppSettingsInputBus {
    @Volatile
    private var sink: AppSettingsSignalSink? = null

    @Volatile
    private var tickHook: (() -> Unit)? = null

    @Volatile
    private var resetFocusHook: ((Int) -> Unit)? = null

    fun attach(
        candidate: AppSettingsSignalSink,
        onTick: () -> Unit = {},
        onResetFocus: (Int) -> Unit = {}
    ) {
        sink = candidate
        tickHook = onTick
        resetFocusHook = onResetFocus
    }

    fun detach(candidate: AppSettingsSignalSink) {
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
