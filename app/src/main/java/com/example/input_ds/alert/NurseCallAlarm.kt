package com.example.input_ds.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.example.input_ds.model.ControlSignal

/** State machine for the 5-second cancellable nurse call overlay. */
data class NurseCallState(
    val remainingSeconds: Int = INITIAL_SECONDS,
    val sent: Boolean = false
) {
    sealed interface Action {
        data object Stay : Action
        data object Cancel : Action
        data class Trigger(val state: NurseCallState) : Action
        data object Dismiss : Action
    }

    fun tick(): NurseCallState = when {
        sent -> this
        remainingSeconds <= 1 -> copy(remainingSeconds = 0, sent = true)
        else -> copy(remainingSeconds = remainingSeconds - 1)
    }

    fun onSignal(signal: ControlSignal): Action = when {
        sent -> Action.Dismiss
        signal == ControlSignal.LEFT_LOOK -> Action.Cancel
        signal == ControlSignal.BITE || signal == ControlSignal.RIGHT_LOOK ->
            Action.Trigger(copy(remainingSeconds = 0, sent = true))
        else -> Action.Stay
    }

    companion object {
        const val INITIAL_SECONDS = 5
    }
}

/** Loops the system alarm sound on USAGE_ALARM until explicitly stopped. */
class NurseCallAlarm(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null

    fun start() {
        if (player?.isPlaying == true || ringtone?.isPlaying == true) return
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(appContext, uri)
                isLooping = true
                prepare()
                start()
            }
            return
        } catch (failure: Exception) {
            Log.w(TAG, "MediaPlayer alarm failed, falling back to Ringtone", failure)
            releasePlayer()
        }
        try {
            ringtone = RingtoneManager.getRingtone(appContext, uri)?.apply {
                audioAttributes = attributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Ringtone alarm unavailable", failure)
            ringtone = null
        }
    }

    fun stop() {
        releasePlayer()
        try {
            ringtone?.stop()
        } catch (_: RuntimeException) {
        }
        ringtone = null
    }

    fun release() = stop()

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            player?.release()
        } catch (_: RuntimeException) {
        }
        player = null
    }

    private companion object {
        const val TAG = "NurseCallAlarm"
    }
}
