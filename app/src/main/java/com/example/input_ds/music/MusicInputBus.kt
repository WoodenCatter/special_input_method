package com.example.input_ds.music

import com.example.input_ds.model.ControlSignal

internal fun interface MusicSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the shared EEG controller/floating ball to the music screen. */
internal object MusicInputBus {
    @Volatile
    private var sink: MusicSignalSink? = null

    fun attach(candidate: MusicSignalSink) {
        sink = candidate
    }

    fun detach(candidate: MusicSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }
}
