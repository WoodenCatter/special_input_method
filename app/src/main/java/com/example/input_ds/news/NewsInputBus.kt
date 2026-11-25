package com.example.input_ds.news

import com.example.input_ds.model.ControlSignal

internal fun interface NewsSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the shared EEG controller/floating ball to the news briefing screen. */
internal object NewsInputBus {
    @Volatile
    private var sink: NewsSignalSink? = null

    @Volatile
    private var tickHandler: (() -> Unit)? = null

    fun attach(
        candidate: NewsSignalSink,
        onTick: (() -> Unit)? = null
    ) {
        sink = candidate
        tickHandler = onTick
    }

    fun detach(candidate: NewsSignalSink) {
        if (sink === candidate) {
            sink = null
            tickHandler = null
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
}
