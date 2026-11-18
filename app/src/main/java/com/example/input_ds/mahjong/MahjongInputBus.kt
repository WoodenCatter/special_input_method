package com.example.input_ds.mahjong

import com.example.input_ds.model.ControlSignal

internal fun interface MahjongSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the shared EEG controller/floating ball to Mahjong WebView. */
internal object MahjongInputBus {
    @Volatile
    private var sink: MahjongSignalSink? = null

    fun attach(candidate: MahjongSignalSink) {
        sink = candidate
    }

    fun detach(candidate: MahjongSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val consumer = sink ?: return false
        consumer.onSignal(signal)
        return true
    }
}
