package com.example.input_ds.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var pendingDone: (() -> Unit)? = null
    private var activeUtteranceId: String? = null
    private var activeDone: (() -> Unit)? = null

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
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        notifyFinished(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        notifyFinished(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        notifyFinished(utteranceId)
                    }
                })
            }
        }
        val queued = pendingText
        val onUnavailable = pendingUnavailable
        val onDone = pendingDone
        pendingText = null
        pendingUnavailable = null
        pendingDone = null
        if (queued != null) {
            speak(queued, onUnavailable ?: {}, onDone ?: {})
        }
    }

    private fun notifyFinished(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId != null && utteranceId == activeUtteranceId) {
                val callback = activeDone
                activeUtteranceId = null
                activeDone = null
                callback?.invoke()
            }
        }
    }

    fun speak(
        text: String,
        onUnavailable: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        if (text.isBlank()) {
            onDone()
            return
        }
        if (!initialized) {
            pendingText = text
            pendingUnavailable = onUnavailable
            pendingDone = onDone
            return
        }
        val engine = tts
        if (!available || engine == null) {
            onUnavailable()
            onDone()
            return
        }
        try {
            engine.stop()
            val utteranceId = "bci-${UUID.randomUUID()}"
            activeUtteranceId = utteranceId
            activeDone = onDone
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                activeUtteranceId = null
                activeDone = null
                onUnavailable()
                onDone()
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "TextToSpeech speak failed", failure)
            activeUtteranceId = null
            activeDone = null
            onUnavailable()
            onDone()
        }
    }

    fun stop() {
        pendingText = null
        pendingUnavailable = null
        pendingDone = null
        activeUtteranceId = null
        activeDone = null
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
