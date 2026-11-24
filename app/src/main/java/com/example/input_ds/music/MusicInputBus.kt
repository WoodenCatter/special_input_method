package com.example.input_ds.music

import com.example.input_ds.model.ControlSignal

internal fun interface MusicSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the shared EEG controller/floating ball to the music screen. */
internal object MusicInputBus {
    @Volatile
    private var sink: MusicSignalSink? = null

    @Volatile
    private var tickHandler: (() -> Unit)? = null

    fun attach(
        candidate: MusicSignalSink,
        onTick: (() -> Unit)? = null
    ) {
        sink = candidate
        tickHandler = onTick
    }

    fun detach(candidate: MusicSignalSink) {
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
