package com.example.input_ds.chess

import com.example.input_ds.model.ControlSignal

internal fun interface ChineseChessSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local bridge from the existing BCI controller to the active chess WebView. */
internal object ChineseChessInputBus {
    @Volatile
    private var sink: ChineseChessSignalSink? = null

    fun attach(candidate: ChineseChessSignalSink) {
        sink = candidate
    }

    fun detach(candidate: ChineseChessSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }
}
