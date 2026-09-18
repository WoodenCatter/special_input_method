package com.example.input_ds.music

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class MusicPlaybackController {
    private var player: MediaPlayer? = null

    var currentTrackId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPrepared by mutableStateOf(false)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var onTrackCompleted: (() -> Unit)? = null

    fun play(track: MusicTrack) {
        releasePlayer(clearTrack = false)
        currentTrackId = track.id
        isPrepared = false
        isPlaying = false
        durationMs = 0
        positionMs = 0
        errorMessage = null

        val nextPlayer = MediaPlayer()
        player = nextPlayer
        runCatching {
            nextPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            nextPlayer.setDataSource(track.file.absolutePath)
            nextPlayer.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                isPrepared = true
                durationMs = prepared.duration.coerceAtLeast(0)
                prepared.start()
                isPlaying = true
            }
            nextPlayer.setOnCompletionListener { completed ->
                if (player !== completed) return@setOnCompletionListener
                isPlaying = false
                positionMs = durationMs
                onTrackCompleted?.invoke()
            }
            nextPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    isPlaying = false
                    isPrepared = false
                    errorMessage = "这首歌曲暂时无法播放"
                }
                true
            }
            nextPlayer.prepareAsync()
        }.onFailure {
            if (player === nextPlayer) {
                releasePlayer(clearTrack = false)
                currentTrackId = track.id
                errorMessage = "无法打开这首歌曲"
            }
        }
    }

    fun togglePlayPause() {
        val current = player ?: return
        if (!isPrepared) return
        runCatching {
            if (current.isPlaying) {
                current.pause()
                isPlaying = false
            } else {
                if (positionMs >= durationMs && durationMs > 0) current.seekTo(0)
                current.start()
                isPlaying = true
            }
            refreshPosition()
        }.onFailure {
            isPlaying = false
            errorMessage = "播放控制失败"
        }
    }

    fun seekTo(position: Int) {
        val current = player ?: return
        if (!isPrepared) return
        val safePosition = position.coerceIn(0, durationMs.coerceAtLeast(0))
        runCatching {
            current.seekTo(safePosition)
            positionMs = safePosition
        }
    }

    fun refreshPosition() {
        val current = player ?: return
        if (!isPrepared) return
        runCatching {
            positionMs = current.currentPosition.coerceIn(0, durationMs.coerceAtLeast(0))
            isPlaying = current.isPlaying
        }
    }

    fun release() {
        releasePlayer(clearTrack = true)
        onTrackCompleted = null
    }

    private fun releasePlayer(clearTrack: Boolean) {
        player?.runCatching {
            reset()
            release()
        }
        player = null
        isPrepared = false
        isPlaying = false
        durationMs = 0
        positionMs = 0
        if (clearTrack) currentTrackId = null
    }
}
