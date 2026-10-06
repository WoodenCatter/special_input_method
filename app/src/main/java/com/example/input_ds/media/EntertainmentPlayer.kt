package com.example.input_ds.media

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.Closeable
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val sourceUrl: String,
    val license: String
)

data class TvChannel(
    val id: String,
    val displayName: String,
    val pageUrl: String
)

enum class EntertainmentMediaMode {
    NONE,
    MUSIC,
    TV
}

data class PlaybackUiState(
    val mode: EntertainmentMediaMode = EntertainmentMediaMode.NONE,
    val currentIndex: Int = 0,
    val title: String = "四季·春 第一乐章",
    val subtitle: String = "Antonio Vivaldi / John Harrison",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Runtime media owner shared by the music and television screens.
 *
 * Music comes from openly licensed Wikimedia Commons files. CCTV stream URLs are resolved from
 * the broadcaster's current HTML5 endpoint each time a channel is selected, because CDN URLs are
 * temporary and should not be hard-coded.
 */
@UnstableApi
class EntertainmentPlayer(context: Context) : Player.Listener, Closeable {
    companion object {
        private const val TAG = "EntertainmentPlayer"
        val musicTracks = listOf(
            MusicTrack(
                id = "vivaldi-spring",
                title = "四季·春 第一乐章",
                artist = "Antonio Vivaldi / John Harrison",
                sourceUrl = "https://upload.wikimedia.org/wikipedia/commons/f/ff/Vivaldi_-_Four_Seasons_1_Spring_mvt_1_Allegro_-_John_Harrison_violin.oga",
                license = "CC BY-SA 4.0"
            ),
            MusicTrack(
                id = "beethoven-moonlight-2",
                title = "月光奏鸣曲 第二乐章",
                artist = "Ludwig van Beethoven / Bernd Krueger",
                sourceUrl = "https://upload.wikimedia.org/wikipedia/commons/4/47/Beethoven_Moonlight_2nd_movement.ogg",
                license = "CC BY-SA 2.0 DE"
            ),
            MusicTrack(
                id = "blue-danube",
                title = "蓝色多瑙河",
                artist = "Johann Strauss II / U.S. Marine Band",
                sourceUrl = "https://upload.wikimedia.org/wikipedia/commons/d/de/%22An_der_sch%C3%B6nen%2C_blauen_Donau%22%2C_performed_by_the_US_Marine_Band.mp3",
                license = "Public Domain"
            )
        )

        val tvChannels = listOf(
            TvChannel("cctv1", "CCTV-1", "https://tv.cctv.com/live/cctv1/"),
            TvChannel("cctv2", "CCTV-2", "https://tv.cctv.com/live/cctv2/"),
            TvChannel("cctv3", "CCTV-3", "https://tv.cctv.com/live/cctv3/"),
            TvChannel("cctv4", "CCTV-4", "https://tv.cctv.com/live/cctv4/"),
            TvChannel("cctv5", "CCTV-5", "https://tv.cctv.com/live/cctv5/")
        )
    }

    private val httpClient = MediaNetwork.createClient()
    private val mediaDataSourceFactory = OkHttpDataSource.Factory(httpClient)
        .setUserAgent("InputDS/1.0 (Android Media3)")

    val player: Player = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context.applicationContext)
                .setDataSourceFactory(mediaDataSourceFactory)
        )
        .build().also {
        it.addListener(this)
    }

    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mode = EntertainmentMediaMode.NONE
    private var currentCatalogIndex = 0

    init {
        scope.launch {
            while (isActive) {
                publishState()
                delay(500L)
            }
        }
    }

    fun prepareMusic(index: Int = 0, playWhenReady: Boolean = false) {
        val safeIndex = index.coerceIn(musicTracks.indices)
        currentCatalogIndex = safeIndex
        if (mode != EntertainmentMediaMode.MUSIC || player.mediaItemCount != musicTracks.size) {
            mode = EntertainmentMediaMode.MUSIC
            player.setMediaItems(musicTracks.map(::musicMediaItem), safeIndex, 0L)
        } else if (player.currentMediaItemIndex != safeIndex) {
            player.seekToDefaultPosition(safeIndex)
        }
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            player.prepare()
        }
        player.playWhenReady = playWhenReady
        publishState(errorMessage = null)
    }

    fun playMusic(index: Int) {
        prepareMusic(index, playWhenReady = true)
        player.play()
    }

    fun toggleMusicPlayback() {
        if (mode != EntertainmentMediaMode.MUSIC) {
            prepareMusic(playWhenReady = true)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        publishState(errorMessage = null)
    }

    fun previousTrack() {
        if (mode != EntertainmentMediaMode.MUSIC) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        player.seekToDefaultPosition((current - 1 + musicTracks.size) % musicTracks.size)
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            player.prepare()
        }
        player.play()
        publishState(errorMessage = null)
    }

    fun nextTrack() {
        if (mode != EntertainmentMediaMode.MUSIC) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        player.seekToDefaultPosition((current + 1) % musicTracks.size)
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            player.prepare()
        }
        player.play()
        publishState(errorMessage = null)
    }

    fun playChannel(index: Int) {
        val safeIndex = index.coerceIn(tvChannels.indices)
        val channel = tvChannels[safeIndex]

        // The official CCTV web player owns protected TV video. This method only updates the shared
        // selection state; keeping it idempotent prevents repeated BCI confirmations from reloading
        // the official player while a channel is already active.
        if (mode == EntertainmentMediaMode.TV && currentCatalogIndex == safeIndex) {
            Log.i(TAG, "Ignoring duplicate request for active channel ${channel.id}")
            return
        }
        mode = EntertainmentMediaMode.TV
        currentCatalogIndex = safeIndex
        player.stop()
        player.clearMediaItems()
        publishState(errorMessage = null, forcedIndex = safeIndex)
    }

    fun stopMusic() {
        if (mode == EntertainmentMediaMode.MUSIC) stopAll()
    }

    fun stopTelevision() {
        if (mode == EntertainmentMediaMode.TV) stopAll()
    }

    fun stopAll() {
        player.stop()
        player.clearMediaItems()
        mode = EntertainmentMediaMode.NONE
        currentCatalogIndex = 0
        mutableState.value = PlaybackUiState()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.i(
            TAG,
            "state=${playbackStateName(playbackState)} mode=$mode " +
                "item=${player.currentMediaItem?.mediaId} position=${player.currentPosition} " +
                "buffered=${player.bufferedPosition}"
        )
        publishState()
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Log.i(TAG, "loading=$isLoading item=${player.currentMediaItem?.mediaId}")
    }

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "Rendered first TV frame for ${player.currentMediaItem?.mediaId}")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishState()

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Media3 playback failed for ${mutableState.value.title}", error)
        publishState(
            errorMessage = failureMessage(
                throwable = error,
                fallback = "媒体加载失败，请重新选择曲目或频道"
            )
        )
    }

    override fun close() {
        scope.cancel()
        player.removeListener(this)
        player.release()
    }

    private fun musicMediaItem(track: MusicTrack): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.sourceUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .build()
        )
        .build()

    private fun publishState(errorMessage: String? = mutableState.value.errorMessage, forcedIndex: Int? = null) {
        if (mode == EntertainmentMediaMode.MUSIC && player.currentMediaItemIndex != C.INDEX_UNSET) {
            currentCatalogIndex = player.currentMediaItemIndex
        }
        forcedIndex?.let { currentCatalogIndex = it }
        val rawIndex = currentCatalogIndex
        val index = when (mode) {
            EntertainmentMediaMode.MUSIC -> rawIndex.coerceIn(musicTracks.indices)
            EntertainmentMediaMode.TV -> rawIndex.coerceIn(tvChannels.indices)
            EntertainmentMediaMode.NONE -> 0
        }
        val title: String
        val subtitle: String
        when (mode) {
            EntertainmentMediaMode.MUSIC -> {
                title = musicTracks[index].title
                subtitle = musicTracks[index].artist
            }
            EntertainmentMediaMode.TV -> {
                title = tvChannels[index].displayName
                subtitle = "央视网直播"
            }
            EntertainmentMediaMode.NONE -> {
                title = musicTracks.first().title
                subtitle = musicTracks.first().artist
            }
        }
        mutableState.value = PlaybackUiState(
            mode = mode,
            currentIndex = index,
            title = title,
            subtitle = subtitle,
            isPlaying = mode == EntertainmentMediaMode.TV || player.isPlaying,
            isBuffering = mode == EntertainmentMediaMode.MUSIC &&
                player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            errorMessage = errorMessage
        )
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun failureMessage(throwable: Throwable, fallback: String): String {
        val causes = generateSequence(throwable as Throwable?) { it.cause }.toList()
        return when {
            causes.any { it is UnknownHostException } ->
                "设备当前无法解析网络域名，请确认 Wi-Fi 或移动数据能够访问互联网后重试"
            causes.any { it is SocketTimeoutException } ->
                "网络连接超时，请检查网络后重试"
            else -> fallback
        }
    }
}
