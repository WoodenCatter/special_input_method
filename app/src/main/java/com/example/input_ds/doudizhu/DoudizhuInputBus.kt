package com.example.input_ds.doudizhu

import com.example.input_ds.model.ControlSignal

internal fun interface DoudizhuSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the existing BCI controller to the active Dou Dizhu WebView. */
internal object DoudizhuInputBus {
    @Volatile
    private var sink: DoudizhuSignalSink? = null

    fun attach(candidate: DoudizhuSignalSink) {
        sink = candidate
    }

    fun detach(candidate: DoudizhuSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val consumer = sink ?: return false
        consumer.onSignal(signal)
        return true
    }
}
