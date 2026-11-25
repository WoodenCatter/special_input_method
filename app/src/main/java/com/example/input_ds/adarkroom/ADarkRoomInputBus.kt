package com.example.input_ds.adarkroom

import com.example.input_ds.model.ControlSignal

internal interface ADarkRoomSignalSink {
    fun onSignal(signal: ControlSignal)
    fun onDebugTick()
}

/** Process-local bridge from MainActivity BCI routing to the active A Dark Room WebView. */
internal object ADarkRoomInputBus {
    @Volatile
    private var sink: ADarkRoomSignalSink? = null

    fun attach(candidate: ADarkRoomSignalSink) {
        sink = candidate
    }

    fun detach(candidate: ADarkRoomSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }

    fun debugTick(): Boolean {
        val current = sink ?: return false
        current.onDebugTick()
        return true
    }
}
