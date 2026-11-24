package com.example.input_ds.tv

import com.example.input_ds.model.ControlSignal

internal fun interface TvSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge shared by the headset controller and the TV screen. */
internal object TvInputBus {
    @Volatile
    private var sink: TvSignalSink? = null

    @Volatile
    private var tickHandler: (() -> Unit)? = null

    @Volatile
    private var resetHandler: (() -> Unit)? = null

    fun attach(
        candidate: TvSignalSink,
        onTick: (() -> Unit)? = null,
        onReset: (() -> Unit)? = null
    ) {
        sink = candidate
        tickHandler = onTick
        resetHandler = onReset
    }

    fun detach(candidate: TvSignalSink) {
        if (sink === candidate) {
            sink = null
            tickHandler = null
            resetHandler = null
        }
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }

    fun debugTick(): Boolean {
        val handler = tickHandler ?: return false
        handler()
        return true
    }

    fun reset(): Boolean {
        val handler = resetHandler ?: return false
        handler()
        return true
    }
}
