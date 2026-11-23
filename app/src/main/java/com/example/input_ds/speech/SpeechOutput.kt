package com.example.input_ds.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID

/** Android TextToSpeech wrapper configured for zh-CN at 0.9x speed. */
class SpeechOutput(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var available = false
    private var pendingText: String? = null
    private var pendingUnavailable: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post { onInitialized(status) }
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "TextToSpeech init failed", failure)
            initialized = true
            available = false
        }
    }

    private fun onInitialized(status: Int) {
        initialized = true
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            available = false
        } else {
            val langResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            available = langResult != TextToSpeech.LANG_MISSING_DATA &&
                langResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (available) {
                engine.setSpeechRate(SPEECH_RATE)
            }
        }
        val queued = pendingText
        val onUnavailable = pendingUnavailable
        pendingText = null
        pendingUnavailable = null
        if (queued != null) {
            speak(queued, onUnavailable ?: {})
        }
    }

    fun speak(text: String, onUnavailable: () -> Unit = {}) {
        if (text.isBlank()) return
        if (!initialized) {
            pendingText = text
            pendingUnavailable = onUnavailable
            return
        }
        val engine = tts
        if (!available || engine == null) {
            onUnavailable()
            return
        }
        try {
            engine.stop()
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bci-${UUID.randomUUID()}")
            if (result != TextToSpeech.SUCCESS) onUnavailable()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "TextToSpeech speak failed", failure)
            onUnavailable()
        }
    }

    fun stop() {
        pendingText = null
        pendingUnavailable = null
        try {
            tts?.stop()
        } catch (_: RuntimeException) {
        }
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: RuntimeException) {
        }
        tts = null
        available = false
    }

    private companion object {
        const val TAG = "SpeechOutput"
        const val SPEECH_RATE = 0.9f
    }
}
