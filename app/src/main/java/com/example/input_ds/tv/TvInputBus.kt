package com.example.input_ds.tv

import com.example.input_ds.model.ControlSignal

internal fun interface TvSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge shared by the headset controller and the TV screen. */
internal object TvInputBus {
    @Volatile
    private var sink: TvSignalSink? = null

    fun attach(candidate: TvSignalSink) {
        sink = candidate
    }

    fun detach(candidate: TvSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }
}
