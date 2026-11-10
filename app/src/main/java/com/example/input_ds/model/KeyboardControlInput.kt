package com.example.input_ds.model

import android.view.KeyEvent

/** Retired keyboard simulator. Keep the mapping commented for reference only. */
internal object KeyboardControlInput {
    @Suppress("UNUSED_PARAMETER")
    fun dispatch(event: KeyEvent, onSignal: (ControlSignal) -> Unit): Boolean {
        /* A/S/D keyboard simulation is disabled. Do not consume keyboard events.
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false

        val signal = when (event.keyCode) {
            KeyEvent.KEYCODE_A -> ControlSignal.LEFT_LOOK
            KeyEvent.KEYCODE_S -> ControlSignal.BITE
            KeyEvent.KEYCODE_D -> ControlSignal.RIGHT_LOOK
            else -> return false
        }

        // Consume key-up and repeats too, but send only one command per press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
            android.util.Log.d("KeyboardControlInput", "Keyboard simulation: $signal")
            onSignal(signal)
        }
        return true
        */
        return false
    }
}
