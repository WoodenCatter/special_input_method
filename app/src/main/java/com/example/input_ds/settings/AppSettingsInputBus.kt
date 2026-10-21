package com.example.input_ds.settings

import com.example.input_ds.model.ControlSignal

internal fun interface AppSettingsSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Routes real-headset and floating-ball commands to the patient settings column. */
internal object AppSettingsInputBus {
    @Volatile
    private var sink: AppSettingsSignalSink? = null

    fun attach(candidate: AppSettingsSignalSink) {
        sink = candidate
    }

    fun detach(candidate: AppSettingsSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }
}
