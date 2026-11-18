package com.example.input_ds.wizard

import com.example.input_ds.model.ControlSignal

internal fun interface WizardGameSignalSink {
    fun onSignal(signal: ControlSignal)
}

/** Process-local handoff from the existing BCI controller to the foreground Godot game. */
internal object WizardGameInputBus {
    @Volatile
    private var sink: WizardGameSignalSink? = null

    fun attach(candidate: WizardGameSignalSink) {
        sink = candidate
    }

    fun detach(candidate: WizardGameSignalSink) {
        if (sink === candidate) sink = null
    }

    fun dispatch(signal: ControlSignal): Boolean {
        val current = sink ?: return false
        current.onSignal(signal)
        return true
    }

    fun hasConsumer(): Boolean = sink != null
}
