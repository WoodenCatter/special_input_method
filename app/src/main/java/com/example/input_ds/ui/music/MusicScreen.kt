package com.example.input_ds.ui.music

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.music.MusicInputBus
import com.example.input_ds.music.MusicLibrary
import com.example.input_ds.music.MusicPlaybackController
import com.example.input_ds.music.MusicPlaybackMode
import com.example.input_ds.music.MusicPlaybackStore
import com.example.input_ds.music.MusicSignalSink
import com.example.input_ds.music.MusicTrack
import com.example.input_ds.ui.theme.AuroraBad
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.AuroraDarkBorderStrong
import com.example.input_ds.ui.theme.AuroraDarkSurface
import com.example.input_ds.ui.theme.AuroraDarkSurfaceStrong
import com.example.input_ds.ui.theme.AuroraInfo
import com.example.input_ds.ui.theme.AuroraOk
import com.example.input_ds.ui.theme.AuroraPink
import com.example.input_ds.ui.theme.AuroraViolet
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.auroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MusicFocusMode { CONTROLS, PLAYLIST, VOLUME, SETTINGS, DELETE_LIST, DELETE_CONFIRM }
private enum class MusicControlIcon { TEXT, VOLUME, PLAYBACK_MODE }

private const val CONTROL_SETTINGS = 0
private const val CONTROL_VOLUME = 1
private const val CONTROL_PREVIOUS = 2
private const val CONTROL_PLAY_PAUSE = 3
private const val CONTROL_NEXT = 4
private const val CONTROL_MODE = 5
private const val CONTROL_PLAYLIST = 6

private val CONTROL_LABELS = listOf("设置", "音量", "上一首", "暂停/播放", "下一首", "播放方式", "播放列表")
private val CONTROL_SYMBOLS = listOf("⚙", "", "⏮", "⏯", "⏭", "", "≡")
private val VOLUME_LABELS = listOf("减小音量", "增大音量", "返回音乐")
private val SETTINGS_LABELS = listOf("返回音乐", "删除音乐", "退出音乐")

@Composable
fun MusicScreen(scanIntervalMs: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val library = remember(appContext) { MusicLibrary(appContext) }
    val playbackStore = remember(appContext) { MusicPlaybackStore(appContext) }
    val player = remember { MusicPlaybackController() }
    val audioManager = remember(appContext) {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val currentOnBack by rememberUpdatedState(onBack)

    var tracks by remember { mutableStateOf(emptyList<MusicTrack>()) }
    var playbackMode by remember { mutableStateOf(playbackStore.playbackMode()) }
    var shuffleOrder by remember { mutableStateOf(emptyList<String>()) }
    var focusMode by remember { mutableStateOf(MusicFocusMode.CONTROLS) }
    var controlIndex by remember { mutableIntStateOf(CONTROL_PLAY_PAUSE) }
    var playlistIndex by remember { mutableIntStateOf(0) }
    var playlistDirection by remember { mutableIntStateOf(1) }
    var volumeIndex by remember { mutableIntStateOf(0) }
    var settingsIndex by remember { mutableIntStateOf(0) }
    var deleteIndex by remember { mutableIntStateOf(0) }
    var deleteDirection by remember { mutableIntStateOf(1) }
    var confirmIndex by remember { mutableIntStateOf(0) }
    var confirmDirection by remember { mutableIntStateOf(1) }
    var pendingDelete by remember { mutableStateOf<MusicTrack?>(null) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var draggedProgress by remember { mutableStateOf<Float?>(null) }

    val currentTrack = tracks.firstOrNull { it.id == player.currentTrackId } ?: tracks.firstOrNull()
    val scanDelay = scanIntervalMs.coerceIn(1_100L, 3_000L)

    fun persistOrders() {
        playbackStore.saveTrackOrder(tracks)
        playbackStore.saveShuffleOrder(shuffleOrder)
    }

    fun startTrack(track: MusicTrack) {
        player.play(track)
        playbackStore.saveLastTrack(track.id)
    }

    fun playTrackAt(index: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = (index + tracks.size) % tracks.size
        playlistIndex = safeIndex
        startTrack(tracks[safeIndex])
        focusMode = MusicFocusMode.CONTROLS
        controlIndex = CONTROL_PLAY_PAUSE
    }

    fun moveTrack(offset: Int) {
        if (tracks.isEmpty()) return
        val orderedIds = if (playbackMode == MusicPlaybackMode.SHUFFLE) {
            shuffleOrder.ifEmpty { tracks.map(MusicTrack::id) }
        } else {
            tracks.map(MusicTrack::id)
        }
        val currentOrderIndex = orderedIds.indexOf(player.currentTrackId).takeIf { it >= 0 } ?: 0
        val nextId = orderedIds[(currentOrderIndex + offset + orderedIds.size) % orderedIds.size]
        val nextIndex = tracks.indexOfFirst { it.id == nextId }.takeIf { it >= 0 } ?: 0
        playlistIndex = nextIndex
        startTrack(tracks[nextIndex])
    }

    fun openPlaylist() {
        playlistIndex = tracks.indexOfFirst { it.id == player.currentTrackId }.takeIf { it >= 0 } ?: 0
        playlistDirection = 1
        focusMode = MusicFocusMode.PLAYLIST
    }

    fun openSettings() {
        settingsIndex = 0
        focusMode = MusicFocusMode.SETTINGS
    }

    fun openVolume() {
        volumeIndex = 0
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        focusMode = MusicFocusMode.VOLUME
    }

    fun changeVolume(delta: Int) {
        val direction = if (delta < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun openDeleteList() {
        if (tracks.isEmpty()) {
            notice = "当前没有可以删除的音乐"
            return
        }
        deleteIndex = 0
        deleteDirection = 1
        focusMode = MusicFocusMode.DELETE_LIST
    }

    fun requestDelete(track: MusicTrack) {
        pendingDelete = track
        confirmIndex = 0
        confirmDirection = 1
        focusMode = MusicFocusMode.DELETE_CONFIRM
    }

    fun performDelete() {
        val target = pendingDelete ?: return
        scope.launch {
            val wasCurrent = player.currentTrackId == target.id
            val oldIndex = tracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            if (wasCurrent) player.release()
            val deleted = library.deleteTrack(target)
            val loaded = library.reloadTracks()
            tracks = playbackStore.orderTracks(loaded)
            shuffleOrder = playbackStore.reconcileShuffleOrder(tracks)
            persistOrders()
            pendingDelete = null
            notice = if (deleted) "已删除《${target.title}》及 APP 内的音乐文件" else "删除失败"
            if (wasCurrent && tracks.isNotEmpty()) startTrack(tracks[oldIndex.coerceAtMost(tracks.lastIndex)])
            if (wasCurrent && tracks.isEmpty()) playbackStore.saveLastTrack(null)
            if (tracks.isEmpty()) {
                focusMode = MusicFocusMode.SETTINGS
                settingsIndex = 0
            } else {
                deleteIndex = deleteIndex.coerceIn(0, tracks.size)
                focusMode = MusicFocusMode.DELETE_LIST
            }
        }
    }

    fun cyclePlaybackMode() {
        playbackMode = playbackMode.next()
        playbackStore.savePlaybackMode(playbackMode)
        if (playbackMode == MusicPlaybackMode.SHUFFLE) {
            shuffleOrder = playbackStore.reconcileShuffleOrder(tracks, reshuffle = true)
        }
    }

    fun executeControl(index: Int) {
        when (index) {
            CONTROL_SETTINGS -> openSettings()
            CONTROL_VOLUME -> openVolume()
            CONTROL_PREVIOUS -> moveTrack(-1)
            CONTROL_PLAY_PAUSE -> player.togglePlayPause()
            CONTROL_NEXT -> moveTrack(1)
            CONTROL_MODE -> cyclePlaybackMode()
            CONTROL_PLAYLIST -> openPlaylist()
        }
    }

    fun executeVolume(index: Int) {
        when (index) {
            0 -> changeVolume(-1)
            1 -> changeVolume(1)
            2 -> {
                focusMode = MusicFocusMode.CONTROLS
                controlIndex = CONTROL_VOLUME
            }
        }
    }

    fun executeSetting(index: Int) {
        when (index) {
            0 -> {
                focusMode = MusicFocusMode.CONTROLS
                controlIndex = CONTROL_SETTINGS
            }
            1 -> openDeleteList()
            2 -> currentOnBack()
        }
    }

    fun moveSettingsFocus(offset: Int) {
        val available = if (tracks.isEmpty()) listOf(0, 2) else SETTINGS_LABELS.indices.toList()
        val position = available.indexOf(settingsIndex).takeIf { it >= 0 } ?: 0
        settingsIndex = available[(position + offset + available.size) % available.size]
    }

    fun returnFromOverlay() {
        when (focusMode) {
            MusicFocusMode.CONTROLS -> currentOnBack()
            MusicFocusMode.PLAYLIST -> {
                focusMode = MusicFocusMode.CONTROLS
                controlIndex = CONTROL_PLAYLIST
            }
            MusicFocusMode.VOLUME -> {
                focusMode = MusicFocusMode.CONTROLS
                controlIndex = CONTROL_VOLUME
            }
            MusicFocusMode.SETTINGS -> {
                focusMode = MusicFocusMode.CONTROLS
                controlIndex = CONTROL_SETTINGS
            }
            MusicFocusMode.DELETE_LIST -> {
                focusMode = MusicFocusMode.SETTINGS
                settingsIndex = 1
            }
            MusicFocusMode.DELETE_CONFIRM -> {
                pendingDelete = null
                focusMode = MusicFocusMode.DELETE_LIST
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = library.importTracks(uris)
            tracks = playbackStore.orderTracks(library.reloadTracks())
            shuffleOrder = playbackStore.reconcileShuffleOrder(tracks)
            persistOrders()
            importing = false
            notice = buildString {
                append("成功导入 ${result.importedCount} 首")
                if (result.rejectedCount > 0) append("，${result.rejectedCount} 个文件不支持或读取失败")
            }
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        when (focusMode) {
            MusicFocusMode.CONTROLS -> when (signal) {
                ControlSignal.LEFT_LOOK -> controlIndex = (controlIndex - 1 + CONTROL_LABELS.size) % CONTROL_LABELS.size
                ControlSignal.RIGHT_LOOK -> controlIndex = (controlIndex + 1) % CONTROL_LABELS.size
                ControlSignal.BITE -> executeControl(controlIndex)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            MusicFocusMode.PLAYLIST -> when (signal) {
                ControlSignal.LEFT_LOOK -> {
                    playlistDirection = -1
                    if (tracks.isNotEmpty()) playlistIndex = (playlistIndex - 1 + tracks.size) % tracks.size
                }
                ControlSignal.RIGHT_LOOK -> {
                    playlistDirection = 1
                    if (tracks.isNotEmpty()) playlistIndex = (playlistIndex + 1) % tracks.size
                }
                ControlSignal.BITE -> playTrackAt(playlistIndex)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            MusicFocusMode.VOLUME -> when (signal) {
                ControlSignal.LEFT_LOOK -> volumeIndex = (volumeIndex - 1 + VOLUME_LABELS.size) % VOLUME_LABELS.size
                ControlSignal.RIGHT_LOOK -> volumeIndex = (volumeIndex + 1) % VOLUME_LABELS.size
                ControlSignal.BITE -> executeVolume(volumeIndex)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            MusicFocusMode.SETTINGS -> when (signal) {
                ControlSignal.LEFT_LOOK -> moveSettingsFocus(-1)
                ControlSignal.RIGHT_LOOK -> moveSettingsFocus(1)
                ControlSignal.BITE -> executeSetting(settingsIndex)
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            MusicFocusMode.DELETE_LIST -> when (signal) {
                ControlSignal.LEFT_LOOK -> {
                    deleteDirection = -1
                    val candidateCount = tracks.size + 1
                    deleteIndex = (deleteIndex - 1 + candidateCount) % candidateCount
                }
                ControlSignal.RIGHT_LOOK -> {
                    deleteDirection = 1
                    val candidateCount = tracks.size + 1
                    deleteIndex = (deleteIndex + 1) % candidateCount
                }
                ControlSignal.BITE -> {
                    if (deleteIndex == 0) returnFromOverlay()
                    else tracks.getOrNull(deleteIndex - 1)?.let(::requestDelete)
                }
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
            MusicFocusMode.DELETE_CONFIRM -> when (signal) {
                ControlSignal.LEFT_LOOK -> {
                    confirmDirection = -1
                    confirmIndex = (confirmIndex + 1) % 2
                }
                ControlSignal.RIGHT_LOOK -> {
                    confirmDirection = 1
                    confirmIndex = (confirmIndex + 1) % 2
                }
                ControlSignal.BITE -> if (confirmIndex == 0) performDelete() else returnFromOverlay()
                ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
            }
        }
    }
    val signalSink = remember { MusicSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        MusicInputBus.attach(signalSink)
        onDispose {
            MusicInputBus.detach(signalSink)
            player.release()
        }
    }

    SideEffect {
        player.onTrackCompleted = {
            if (tracks.isNotEmpty()) {
                val nextTrack = when (playbackMode) {
                    MusicPlaybackMode.REPEAT_ONE -> tracks.firstOrNull { it.id == player.currentTrackId }
                    MusicPlaybackMode.SEQUENTIAL -> {
                        val index = tracks.indexOfFirst { it.id == player.currentTrackId }.takeIf { it >= 0 } ?: 0
                        tracks[(index + 1) % tracks.size]
                    }
                    MusicPlaybackMode.SHUFFLE -> {
                        val order = shuffleOrder.ifEmpty { tracks.map(MusicTrack::id) }
                        val index = order.indexOf(player.currentTrackId).takeIf { it >= 0 } ?: 0
                        val nextId = order[(index + 1) % order.size]
                        tracks.firstOrNull { it.id == nextId }
                    }
                }
                nextTrack?.let(::startTrack)
            }
        }
    }

    LaunchedEffect(Unit) {
        notice = "正在准备本地音乐库…"
        tracks = playbackStore.orderTracks(library.initializeAndLoadTracks())
        playbackStore.saveTrackOrder(tracks)
        shuffleOrder = playbackStore.reconcileShuffleOrder(tracks)
        val savedTrackId = playbackStore.lastTrackId()
        val initialTrack = tracks.firstOrNull { it.id == savedTrackId } ?: tracks.firstOrNull()
        initialTrack?.let(::startTrack)
        notice = null
        while (true) {
            delay(500)
            player.refreshPosition()
        }
    }

    LaunchedEffect(focusMode, playlistIndex, playlistDirection, tracks.size, scanDelay) {
        if (focusMode == MusicFocusMode.PLAYLIST && tracks.isNotEmpty()) {
            delay(scanDelay)
            playlistIndex = (playlistIndex + playlistDirection + tracks.size) % tracks.size
        }
    }

    LaunchedEffect(focusMode, deleteIndex, deleteDirection, tracks.size, scanDelay) {
        if (focusMode == MusicFocusMode.DELETE_LIST && tracks.isNotEmpty()) {
            delay(scanDelay)
            val candidateCount = tracks.size + 1
            deleteIndex = (deleteIndex + deleteDirection + candidateCount) % candidateCount
        }
    }

    LaunchedEffect(focusMode, confirmIndex, confirmDirection, scanDelay) {
        if (focusMode == MusicFocusMode.DELETE_CONFIRM) {
            delay(scanDelay)
            confirmIndex = (confirmIndex + confirmDirection + 2) % 2
        }
    }

    BackHandler { returnFromOverlay() }

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MusicHeader(
                track = currentTrack,
                importing = importing,
                notice = notice ?: player.errorMessage,
                onImport = {
                    importLauncher.launch(
                        arrayOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/wav", "audio/ogg", "audio/flac")
                    )
                }
            )
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VinylPanel(currentTrack, player.isPlaying, Modifier.weight(.30f).fillMaxHeight())
                LyricsPanel(currentTrack, Modifier.weight(.38f).fillMaxHeight())
                PlaylistPanel(
                    tracks = tracks,
                    currentTrackId = player.currentTrackId,
                    focusIndex = playlistIndex,
                    focusActive = focusMode == MusicFocusMode.PLAYLIST,
                    onTrackClick = ::playTrackAt,
                    modifier = Modifier.weight(.32f).fillMaxHeight()
                )
            }
            PlaybackProgress(
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                draggedProgress = draggedProgress,
                onProgressChange = { draggedProgress = it },
                onProgressFinished = {
                    draggedProgress?.let { player.seekTo((it * player.durationMs).toInt()) }
                    draggedProgress = null
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CONTROL_LABELS.forEachIndexed { index, label ->
                    val displayedLabel = when (index) {
                        CONTROL_PLAY_PAUSE -> if (player.isPlaying) "暂停" else "播放"
                        CONTROL_MODE -> playbackMode.displayName
                        else -> label
                    }
                    MusicControlButton(
                        symbol = if (index == CONTROL_PLAY_PAUSE) {
                            if (player.isPlaying) "Ⅱ" else "▶"
                        } else CONTROL_SYMBOLS[index],
                        label = displayedLabel,
                        focused = focusMode == MusicFocusMode.CONTROLS && controlIndex == index,
                        icon = when (index) {
                            CONTROL_VOLUME -> MusicControlIcon.VOLUME
                            CONTROL_MODE -> MusicControlIcon.PLAYBACK_MODE
                            else -> MusicControlIcon.TEXT
                        },
                        playbackMode = playbackMode,
                        onClick = {
                            controlIndex = index
                            executeControl(index)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        when (focusMode) {
            MusicFocusMode.VOLUME -> VolumeOverlay(
                selectedIndex = volumeIndex,
                volume = volume,
                maxVolume = maxVolume,
                onSelect = { index ->
                    volumeIndex = index
                    executeVolume(index)
                }
            )
            MusicFocusMode.SETTINGS -> SettingsOverlay(
                selectedIndex = settingsIndex,
                hasMusic = tracks.isNotEmpty(),
                onSelect = { index ->
                    settingsIndex = index
                    executeSetting(index)
                }
            )
            MusicFocusMode.DELETE_LIST -> DeleteListOverlay(
                tracks = tracks,
                selectedIndex = deleteIndex,
                onBack = ::returnFromOverlay,
                onSelect = { index ->
                    deleteIndex = index + 1
                    tracks.getOrNull(index)?.let(::requestDelete)
                }
            )
            MusicFocusMode.DELETE_CONFIRM -> DeleteConfirmOverlay(
                track = pendingDelete,
                selectedIndex = confirmIndex,
                onConfirm = ::performDelete,
                onCancel = ::returnFromOverlay
            )
            MusicFocusMode.CONTROLS, MusicFocusMode.PLAYLIST -> Unit
        }
    }
}

@Composable
private fun MusicHeader(track: MusicTrack?, importing: Boolean, notice: String?, onImport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("音乐", style = MaterialTheme.typography.headlineMedium)
            Text(
                track?.let { "正在播放：${it.title} · ${it.artist}" } ?: "暂无歌曲",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            notice?.let {
                Text(it, color = AuroraInfo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onImport, enabled = !importing) {
            Text(if (importing) "正在导入…" else "＋ 导入音乐")
        }
    }
}

@Composable
private fun VinylPanel(track: MusicTrack?, isPlaying: Boolean, modifier: Modifier = Modifier) {
    GlassPanel(modifier, contentPadding = PaddingValues(14.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            VinylRecord(
                isPlaying = isPlaying,
                label = track?.title?.take(1).orEmpty(),
                modifier = Modifier.fillMaxWidth(.74f).aspectRatio(1f)
            )
        }
        Text(
            track?.title ?: "暂无歌曲",
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track?.artist.orEmpty(),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VinylRecord(isPlaying: Boolean, label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val animatedAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "vinyl-angle"
    )
    Box(
        modifier.graphicsLayer { rotationZ = if (isPlaying) animatedAngle else 0f },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF050508), Color(0xFF252634), Color(0xFF07070A))),
                radius
            )
            listOf(.93f, .82f, .70f, .58f).forEach { ratio ->
                drawCircle(Color(0xFF4B4D5D), radius * ratio, style = Stroke(1.2f))
            }
            drawCircle(Brush.radialGradient(listOf(AuroraPink, AuroraViolet, AuroraCyan)), radius * .34f)
            drawCircle(Color(0xFF10121A), radius * .065f)
        }
        Text(label.ifBlank { "♪" }, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LyricsPanel(track: MusicTrack?, modifier: Modifier = Modifier) {
    GlassPanel(modifier, contentPadding = PaddingValues(18.dp)) {
        Text("歌词", color = AuroraVioletBright, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(.4f))
        Text(
            track?.title ?: "暂无歌曲",
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track?.artist.orEmpty(),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = AuroraInfo,
            fontSize = 17.sp
        )
        Text(
            "暂无歌词\n请欣赏音乐",
            Modifier.fillMaxWidth().padding(top = 24.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 18.sp,
            lineHeight = 30.sp
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PlaylistPanel(
    tracks: List<MusicTrack>,
    currentTrackId: String?,
    focusIndex: Int,
    focusActive: Boolean,
    onTrackClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusIndex, focusActive, tracks.size) {
        if (focusActive && tracks.isNotEmpty()) listState.animateScrollToItem(focusIndex.coerceIn(0, tracks.lastIndex))
    }
    GlassPanel(modifier, contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${tracks.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (focusActive) "自动选择中 · 左看/右看改变方向 · 咬牙播放" else "点击列表按键后可用耳机选歌",
            color = if (focusActive) AuroraOk else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                PlaylistRow(
                    track,
                    current = track.id == currentTrackId,
                    focused = focusActive && index == focusIndex,
                    onClick = { onTrackClick(index) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(track: MusicTrack, current: Boolean, focused: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) Color(0x3322D3EE) else AuroraDarkSurface)
            .border(if (focused) 4.dp else 1.dp, if (focused) AuroraOk else AuroraDarkBorderStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(if (current) "▶" else "♪", color = if (current) AuroraCyan else AuroraVioletBright)
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                "${track.artist} · 本地音乐",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    positionMs: Int,
    durationMs: Int,
    draggedProgress: Float?,
    onProgressChange: (Float) -> Unit,
    onProgressFinished: () -> Unit
) {
    val fraction = draggedProgress ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(formatTime(positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onProgressChange,
            onValueChangeFinished = onProgressFinished,
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f)
        )
        Text(formatTime(durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun MusicControlButton(
    symbol: String,
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: MusicControlIcon = MusicControlIcon.TEXT,
    playbackMode: MusicPlaybackMode = MusicPlaybackMode.SEQUENTIAL
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(68.dp)
            .clip(shape)
            .background(if (focused) Color(0x3334D399) else AuroraDarkSurfaceStrong)
            .border(if (focused) 4.dp else 1.dp, if (focused) AuroraOk else AuroraDarkBorderStrong, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (icon) {
            MusicControlIcon.TEXT -> Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            MusicControlIcon.VOLUME -> VolumeLineIcon(Modifier.size(25.dp))
            MusicControlIcon.PLAYBACK_MODE -> PlaybackModeLineIcon(playbackMode, Modifier.size(27.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun VolumeLineIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val strokeWidth = 2.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val speaker = Path().apply {
            moveTo(size.width * .12f, size.height * .40f)
            lineTo(size.width * .34f, size.height * .40f)
            lineTo(size.width * .55f, size.height * .22f)
            lineTo(size.width * .55f, size.height * .78f)
            lineTo(size.width * .34f, size.height * .60f)
            lineTo(size.width * .12f, size.height * .60f)
            close()
        }
        drawPath(speaker, color = color, style = stroke)
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(size.width * .43f, size.height * .30f),
            size = Size(size.width * .28f, size.height * .40f),
            style = stroke
        )
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(size.width * .38f, size.height * .18f),
            size = Size(size.width * .50f, size.height * .64f),
            style = stroke
        )
    }
}

@Composable
private fun PlaybackModeLineIcon(mode: MusicPlaybackMode, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (mode == MusicPlaybackMode.SHUFFLE) {
                val upper = Path().apply {
                    moveTo(size.width * .13f, size.height * .28f)
                    lineTo(size.width * .31f, size.height * .28f)
                    lineTo(size.width * .70f, size.height * .72f)
                    lineTo(size.width * .87f, size.height * .72f)
                }
                val lower = Path().apply {
                    moveTo(size.width * .13f, size.height * .72f)
                    lineTo(size.width * .31f, size.height * .72f)
                    lineTo(size.width * .70f, size.height * .28f)
                    lineTo(size.width * .87f, size.height * .28f)
                }
                drawPath(upper, color = color, style = stroke)
                drawPath(lower, color = color, style = stroke)
                drawLine(color, Offset(size.width * .87f, size.height * .72f), Offset(size.width * .77f, size.height * .63f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .87f, size.height * .72f), Offset(size.width * .77f, size.height * .81f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .87f, size.height * .28f), Offset(size.width * .77f, size.height * .19f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .87f, size.height * .28f), Offset(size.width * .77f, size.height * .37f), strokeWidth, StrokeCap.Round)
            } else {
                drawLine(color, Offset(size.width * .18f, size.height * .30f), Offset(size.width * .78f, size.height * .30f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .78f, size.height * .30f), Offset(size.width * .86f, size.height * .39f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .86f, size.height * .39f), Offset(size.width * .86f, size.height * .65f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .82f, size.height * .70f), Offset(size.width * .22f, size.height * .70f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .22f, size.height * .70f), Offset(size.width * .14f, size.height * .61f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .14f, size.height * .61f), Offset(size.width * .14f, size.height * .36f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .18f, size.height * .30f), Offset(size.width * .29f, size.height * .20f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .18f, size.height * .30f), Offset(size.width * .29f, size.height * .40f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .82f, size.height * .70f), Offset(size.width * .71f, size.height * .60f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .82f, size.height * .70f), Offset(size.width * .71f, size.height * .80f), strokeWidth, StrokeCap.Round)
            }
        }
        if (mode == MusicPlaybackMode.REPEAT_ONE) {
            Text("1", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VolumeOverlay(
    selectedIndex: Int,
    volume: Int,
    maxVolume: Int,
    onSelect: (Int) -> Unit
) {
    MusicOverlay(widthFraction = .52f) {
        Text("音量调节", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("调节音量不会暂停歌曲 · 左看/右看移动焦点 · 咬牙选择", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("音量：$volume / $maxVolume", fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { volume.toFloat() / maxVolume },
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
            color = AuroraCyan,
            trackColor = AuroraDarkSurfaceStrong
        )
        VOLUME_LABELS.forEachIndexed { index, label ->
            FocusOptionButton(
                text = label,
                focused = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun SettingsOverlay(
    selectedIndex: Int,
    hasMusic: Boolean,
    onSelect: (Int) -> Unit
) {
    MusicOverlay(widthFraction = .52f) {
        Text("音乐设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("进入设置不会暂停歌曲 · 左看/右看移动焦点 · 咬牙选择", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SETTINGS_LABELS.forEachIndexed { index, label ->
            val enabled = index != 1 || hasMusic
            FocusOptionButton(
                text = if (index == 1 && !hasMusic) "删除音乐（暂无）" else label,
                focused = index == selectedIndex,
                enabled = enabled,
                danger = index == 2,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun DeleteListOverlay(
    tracks: List<MusicTrack>,
    selectedIndex: Int,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, tracks.size) {
        if (tracks.isNotEmpty() && selectedIndex > 0) {
            listState.animateScrollToItem((selectedIndex - 1).coerceIn(0, tracks.lastIndex))
        }
    }
    MusicOverlay(widthFraction = .68f) {
        Text("删除音乐", style = MaterialTheme.typography.headlineMedium)
        Text("光标自动跳转，咬牙选择后还会再次确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FocusOptionButton(
            text = "返回设置",
            focused = selectedIndex == 0,
            onClick = onBack
        )
        LazyColumn(
            Modifier.fillMaxWidth().height(330.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                FocusOptionButton(
                    text = "${track.title} · ${track.artist}",
                    focused = index + 1 == selectedIndex,
                    danger = true,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmOverlay(
    track: MusicTrack?,
    selectedIndex: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    MusicOverlay(widthFraction = .50f) {
        Text("确定删除这首音乐吗？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            track?.let { "《${it.title}》· ${it.artist}" }.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
        Text(
            "确认后会从播放列表中移除，并删除 APP 私有目录里的音乐副本。设备中原来的文件不会被删除。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusOptionButton("确定删除", selectedIndex == 0, onConfirm, Modifier.weight(1f), danger = true)
            FocusOptionButton("返回", selectedIndex == 1, onCancel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FocusOptionButton(
    text: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val background = when {
        !enabled -> Color(0x15111111)
        danger -> Color(0x35FB7185)
        focused -> Color(0x3334D399)
        else -> AuroraDarkSurfaceStrong
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(background)
            .border(if (focused) 4.dp else 1.dp, if (focused) AuroraOk else AuroraDarkBorderStrong, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (!enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
            } else if (danger) AuroraBad else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MusicOverlay(
    widthFraction: Float = .58f,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(80f)
            .background(Color(0xB80A0D1E))
            .clickable { },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(widthFraction),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF171B32),
            border = androidx.compose.foundation.BorderStroke(2.dp, AuroraViolet)
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
                content = content
            )
        }
    }
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
