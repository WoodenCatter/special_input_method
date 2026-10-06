package com.example.input_ds.personalization

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays the short, single collection cue used at every phase transition. */
internal class CollectionCuePlayer : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    private var toneGenerator: ToneGenerator? = null

    suspend fun play(): Boolean = withContext(Dispatchers.Main.immediate) {
        val generator = synchronized(this@CollectionCuePlayer) {
            if (closed) return@withContext false
            toneGenerator ?: runCatching {
                ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME_PERCENT)
            }.getOrNull()?.also { toneGenerator = it }
        } ?: return@withContext false

        generator.stopTone()
        generator.startTone(ToneGenerator.TONE_DTMF_S, CUE_DURATION_MS)
    }

    override fun close() {
        val generator = synchronized(this) {
            if (closed) return
            closed = true
            toneGenerator.also { toneGenerator = null }
        } ?: return

        val release = {
            generator.stopTone()
            generator.release()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release()
        } else {
            mainHandler.post(release)
        }
    }

    private companion object {
        const val CUE_VOLUME_PERCENT = 80
        const val CUE_DURATION_MS = 120
    }
}
