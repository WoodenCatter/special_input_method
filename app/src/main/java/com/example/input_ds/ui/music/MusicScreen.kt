package com.example.input_ds.ui.music

import com.example.input_ds.ui.theme.LocalBciColors

import com.example.input_ds.ui.design.*
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.music.MusicInputBus
import com.example.input_ds.music.MusicLibrary
import com.example.input_ds.music.MusicPlaybackController
import com.example.input_ds.music.MusicPlaybackMode
import com.example.input_ds.music.MusicPlaybackStore
import com.example.input_ds.music.MusicSignalSink
import com.example.input_ds.music.MusicTrack
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.bciBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
fun MusicScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    onBack: () -> Unit,
    initialTrackId: String? = null,
    onConfirmation: (ConfirmOverlaySpec) -> Unit,
    paused: Boolean,
    frozen: Boolean = false,
    fromAssistant: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onBoundary: () -> Unit = {}
) {
    val returnLabel = if (fromAssistant) "返回助手" else "返回娱乐"
    val settingsLabels = remember(returnLabel) { listOf("返回音乐", "删除音乐", returnLabel) }
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
    val currentOnToast by rememberUpdatedState(onToast)
    val currentOnBoundary by rememberUpdatedState(onBoundary)

    var tracks by remember { mutableStateOf(emptyList<MusicTrack>()) }
    var playbackMode by remember { mutableStateOf(playbackStore.playbackMode()) }
    var shuffleOrder by remember { mutableStateOf(emptyList<String>()) }
    var scanState by remember { mutableStateOf(MusicScanState()) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var draggedProgress by remember { mutableStateOf<Float?>(null) }

    val currentTrack = tracks.firstOrNull { it.id == player.currentTrackId } ?: tracks.firstOrNull()
    val scanDelay = scanIntervalMs.coerceIn(1_100L, 3_000L)
    val activeCursor = scanState.activeCursor()
    val activeTargetIndex = scanState.activeTargetIndex()

    val item = when (scanState.focusMode) {
        MusicFocusMode.CONTROLS -> when (activeTargetIndex) {
            CONTROL_PLAY_PAUSE -> if (player.isPlaying) "暂停" else "播放"
            CONTROL_MODE -> playbackMode.displayName
            else -> CONTROL_LABELS[activeTargetIndex]
        }
        MusicFocusMode.PLAYLIST -> tracks.getOrNull(activeTargetIndex)?.title.orEmpty()
        MusicFocusMode.VOLUME -> VOLUME_LABELS[activeTargetIndex]
        MusicFocusMode.SETTINGS -> settingsLabels[activeTargetIndex]
        MusicFocusMode.DELETE_LIST -> tracks.getOrNull(activeTargetIndex - 1)?.title ?: "返回"
    }
    ReportActionHints(ActionHints.list(item, scanMode, activeCursor.direction))

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
        startTrack(tracks[safeIndex])
        val wasInControls = scanState.focusMode == MusicFocusMode.CONTROLS
        scanState = scanState.syncTrackCount(tracks.size).enterControls(CONTROL_PLAY_PAUSE)
        if (!wasInControls) currentOnBoundary()
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
        startTrack(tracks[nextIndex])
    }

    fun openPlaylist() {
        if (tracks.isEmpty()) {
            notice = "当前播放列表为空"
            scanState = scanState.syncTrackCount(0).commitControlSelection(CONTROL_PLAYLIST)
            return
        }
        val trackIdx = tracks.indexOfFirst { it.id == player.currentTrackId }.takeIf { it >= 0 } ?: 0
        scanState = scanState.syncTrackCount(tracks.size).enterPlaylist(trackIdx)
        currentOnBoundary()
    }

    fun openSettings() {
        scanState = scanState.syncTrackCount(tracks.size).enterSettings(0)
        currentOnBoundary()
    }

    fun openVolume() {
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        scanState = scanState.enterVolume(0)
        currentOnBoundary()
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
        scanState = scanState.syncTrackCount(tracks.size).enterDeleteList(0)
        currentOnBoundary()
    }

    fun performDelete(target: MusicTrack) {
        scope.launch {
            val wasCurrent = player.currentTrackId == target.id
            val oldIndex = tracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            if (wasCurrent) player.release()
            val deleted = library.deleteTrack(target)
            val loaded = library.reloadTracks()
            tracks = playbackStore.orderTracks(loaded)
            shuffleOrder = playbackStore.reconcileShuffleOrder(tracks)
            persistOrders()
            notice = if (deleted) "已删除《${target.title}》及 APP 内的音乐文件" else "删除失败"
            if (wasCurrent && tracks.isNotEmpty()) startTrack(tracks[oldIndex.coerceAtMost(tracks.lastIndex)])
            if (wasCurrent && tracks.isEmpty()) playbackStore.saveLastTrack(null)
            val synced = scanState.syncTrackCount(tracks.size)
            if (tracks.isEmpty()) {
                scanState = synced.enterSettings(0)
                currentOnBoundary()
            } else {
                scanState = synced.enterDeleteList(synced.deleteCursor.index.coerceIn(0, tracks.size))
            }
        }
    }

    fun requestDelete(track: MusicTrack) {
        onConfirmation(ConfirmOverlaySpec("删除音乐？", track.title, "取消", "保留这首音乐", "确定删除", "删除应用内副本",
            onConfirm = { performDelete(track) }, onCancel = {}))
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
            CONTROL_PREVIOUS -> {
                moveTrack(-1)
                scanState = scanState.commitControlSelection(index)
            }
            CONTROL_PLAY_PAUSE -> {
                player.togglePlayPause()
                scanState = scanState.commitControlSelection(index)
            }
            CONTROL_NEXT -> {
                moveTrack(1)
                scanState = scanState.commitControlSelection(index)
            }
            CONTROL_MODE -> {
                cyclePlaybackMode()
                scanState = scanState.commitControlSelection(index)
            }
            CONTROL_PLAYLIST -> openPlaylist()
        }
    }

    fun executeVolume(index: Int) {
        when (index) {
            0 -> {
                changeVolume(-1)
                scanState = scanState.commitVolumeSelection(0)
            }
            1 -> {
                changeVolume(1)
                scanState = scanState.commitVolumeSelection(1)
            }
            2 -> {
                scanState = scanState.enterControls(CONTROL_VOLUME)
                currentOnBoundary()
            }
        }
    }

    fun executeSetting(index: Int) {
        when (index) {
            0 -> {
                scanState = scanState.enterControls(CONTROL_SETTINGS)
                currentOnBoundary()
            }
            1 -> openDeleteList()
            2 -> {
                currentOnBoundary()
                currentOnBack()
            }
        }
    }

    fun returnFromOverlay() {
        when (scanState.focusMode) {
            MusicFocusMode.CONTROLS -> {
                currentOnBoundary()
                currentOnBack()
            }
            MusicFocusMode.PLAYLIST -> {
                scanState = scanState.enterControls(CONTROL_PLAYLIST)
                currentOnBoundary()
            }
            MusicFocusMode.VOLUME -> {
                scanState = scanState.enterControls(CONTROL_VOLUME)
                currentOnBoundary()
            }
            MusicFocusMode.SETTINGS -> {
                scanState = scanState.enterControls(CONTROL_SETTINGS)
                currentOnBoundary()
            }
            MusicFocusMode.DELETE_LIST -> {
                scanState = scanState.syncTrackCount(tracks.size).enterSettings(1)
                currentOnBoundary()
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
            scanState = scanState.syncTrackCount(tracks.size)
            persistOrders()
            importing = false
            notice = buildString {
                append("成功导入 ${result.importedCount} 首")
                if (result.rejectedCount > 0) append("，${result.rejectedCount} 个文件不支持或读取失败")
            }
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        when (signal) {
            ControlSignal.LEFT_LOOK -> scanState = scanState.look(-1, scanMode)
            ControlSignal.RIGHT_LOOK -> scanState = scanState.look(1, scanMode)
            ControlSignal.BITE -> {
                val grace = if (frozen) 0L else AppUiSettings.graceMs(context)
                val selection = scanState.selectOnBite(
                    nowMs = SystemClock.elapsedRealtime(),
                    graceMs = grace,
                    mode = scanMode
                ) ?: return@rememberUpdatedState
                if (selection.compensated && !frozen) {
                    currentOnToast("已按上一项执行（延迟补偿）", false)
                }
                when (scanState.focusMode) {
                    MusicFocusMode.CONTROLS -> executeControl(selection.targetIndex)
                    MusicFocusMode.PLAYLIST -> playTrackAt(selection.targetIndex)
                    MusicFocusMode.VOLUME -> executeVolume(selection.targetIndex)
                    MusicFocusMode.SETTINGS -> executeSetting(selection.targetIndex)
                    MusicFocusMode.DELETE_LIST -> {
                        if (selection.targetIndex == 0) {
                            returnFromOverlay()
                        } else {
                            scanState = scanState.commitDeleteSelection(selection.slotIndex)
                            tracks.getOrNull(selection.targetIndex - 1)?.let(::requestDelete)
                        }
                    }
                }
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }
    val tickHandler = rememberUpdatedState<() -> Unit> {
        scanState = scanState.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
    }
    val signalSink = remember { MusicSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        MusicInputBus.attach(signalSink, onTick = { tickHandler.value() })
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
        scanState = scanState.syncTrackCount(tracks.size)
        val initialTrack = if (initialTrackId != null) {
            tracks.firstOrNull { it.id == initialTrackId }
        } else {
            tracks.firstOrNull { it.id == playbackStore.lastTrackId() } ?: tracks.firstOrNull()
        }
        initialTrack?.let(::startTrack)
        if (initialTrackId != null) {
            scanState = scanState.enterControls(CONTROL_PLAY_PAUSE)
        }
        notice = if (initialTrackId != null && initialTrack == null) "指定歌曲已不在本地曲库" else null
        while (true) {
            delay(500)
            player.refreshPosition()
        }
    }

    LaunchedEffect(paused) {
        scanState = scanState.resetForPauseBoundary()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(
        paused,
        frozen,
        scanMode,
        scanState.focusMode,
        activeCursor.size,
        scanState.dwellRevision,
        scanDelay,
        lifecycleOwner
    ) {
        if (!paused && !frozen && scanMode == ScanMode.AUTO && activeCursor.size > 0) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                scanState = scanState.clearActiveGrace()
                runScanTimer(
                    interval = { scanDelay },
                    onFailure = { android.util.Log.e("ScanCursor", "Music tick failed", it) }
                ) {
                    scanState = scanState.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
                }
            }
        }
    }

    BackHandler(enabled = !paused) { returnFromOverlay() }

    val activeDirection = if (scanMode == ScanMode.AUTO) activeCursor.direction else 0
    val dwell = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(
        scanState.focusMode,
        activeCursor.index,
        activeCursor.ticks,
        activeCursor.direction,
        scanState.dwellRevision,
        activeDirection,
        paused,
        frozen,
        scanDelay,
        lifecycleOwner
    ) {
        when {
            frozen -> dwell.snapTo(if (activeDirection != 0 && activeCursor.size > 0) 0.5f else 0f)
            paused || activeDirection == 0 || activeCursor.size <= 0 -> dwell.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwell.snapTo(0f)
                dwell.animateTo(1f, tween(scanDelay.toInt(), easing = LinearEasing))
            }
        }
    }
    val progress: () -> Float? = { if (activeDirection != 0 && activeCursor.size > 0 && (!paused || frozen)) dwell.value else null }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMusicDirection provides activeDirection,
        LocalMusicProgress provides progress,
        LocalMusicPaused provides paused
    ) {
        Box(Modifier.fillMaxSize().bciBackground()) {
            Column(
                modifier = Modifier.fillMaxSize(),
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
                    VinylPanel(currentTrack, player.isPlaying && !frozen, Modifier.weight(.30f).fillMaxHeight())
                    LyricsPanel(currentTrack, Modifier.weight(.38f).fillMaxHeight())
                    PlaylistPanel(
                        tracks = tracks,
                        currentTrackId = player.currentTrackId,
                        focusIndex = scanState.playlistCursor.index,
                        focusActive = scanState.focusMode == MusicFocusMode.PLAYLIST,
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
                            focused = scanState.focusMode == MusicFocusMode.CONTROLS && scanState.controlCursor.index == index,
                            icon = when (index) {
                                CONTROL_VOLUME -> MusicControlIcon.VOLUME
                                CONTROL_MODE -> MusicControlIcon.PLAYBACK_MODE
                                else -> MusicControlIcon.TEXT
                            },
                            playbackMode = playbackMode,
                            onClick = { executeControl(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            when (scanState.focusMode) {
                MusicFocusMode.VOLUME -> VolumeOverlay(
                    selectedIndex = scanState.volumeCursor.index,
                    volume = volume,
                    maxVolume = maxVolume,
                    onSelect = ::executeVolume
                )
                MusicFocusMode.SETTINGS -> SettingsOverlay(
                    labels = settingsLabels,
                    selectedIndex = scanState.targetIndexForSlot(scanState.settingsCursor.index),
                    hasMusic = tracks.isNotEmpty(),
                    onSelect = ::executeSetting
                )
                MusicFocusMode.DELETE_LIST -> DeleteListOverlay(
                    tracks = tracks,
                    selectedIndex = scanState.deleteCursor.index,
                    onBack = ::returnFromOverlay,
                    onSelect = { index ->
                        scanState = scanState.commitDeleteSelection(index + 1)
                        tracks.getOrNull(index)?.let(::requestDelete)
                    }
                )

                MusicFocusMode.CONTROLS, MusicFocusMode.PLAYLIST -> Unit
            }
        }
    }
}

private val LocalMusicDirection = androidx.compose.runtime.compositionLocalOf { 0 }
private val LocalMusicProgress = androidx.compose.runtime.compositionLocalOf<(() -> Float?)?> { null }
private val LocalMusicPaused = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun MusicHeader(track: MusicTrack?, importing: Boolean, notice: String?, onImport: () -> Unit) {
    val paused = LocalMusicPaused.current
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
                Text(it, color = LocalBciColors.current.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onImport, enabled = !importing && !paused) {
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
    val focusColor = LocalBciColors.current.focus
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
            drawCircle(focusColor, radius * .34f)
            drawCircle(Color(0xFF10121A), radius * .065f)
        }
        Text(label.ifBlank { "♪" }, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LyricsPanel(track: MusicTrack?, modifier: Modifier = Modifier) {
    GlassPanel(modifier, contentPadding = PaddingValues(18.dp)) {
        Text("歌词", color = LocalBciColors.current.focus, style = MaterialTheme.typography.titleMedium)
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
            color = LocalBciColors.current.muted,
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
    val paused = LocalMusicPaused.current
    Row(
        Modifier
            .fillMaxWidth()
            .scanItem(focused, LocalMusicDirection.current, false, if (focused) LocalMusicProgress.current else null, cornerRadius = 12.dp, customFillColor = if (current) LocalBciColors.current.okTint else null)
            .clickable(enabled = !paused, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(if (current) "▶" else "♪", color = if (current) LocalBciColors.current.ok else LocalBciColors.current.focus)
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
    val paused = LocalMusicPaused.current
    Column(
        modifier
            .height(76.dp)
            .scanItem(
                focused = focused,
                direction = LocalMusicDirection.current,
                picked = false,
                progress = if (focused) LocalMusicProgress.current else null,
                cornerRadius = 16.dp,
                dwellBottom = 5.dp
            )
            .clickable(enabled = !paused, onClick = onClick)
            .padding(top = 4.dp, bottom = 12.dp),
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
        Text("音量：$volume / $maxVolume", fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { volume.toFloat() / maxVolume },
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
            color = LocalBciColors.current.focus,
            trackColor = LocalBciColors.current.panel2
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
    labels: List<String>,
    selectedIndex: Int,
    hasMusic: Boolean,
    onSelect: (Int) -> Unit
) {
    MusicOverlay(widthFraction = .52f) {
        Text("音乐设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        labels.forEachIndexed { index, label ->
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
private fun FocusOptionButton(
    text: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val paused = LocalMusicPaused.current
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .scanItem(focused, LocalMusicDirection.current, false, if (focused) LocalMusicProgress.current else null, cornerRadius = 14.dp, enabled = enabled, customFillColor = if (danger) LocalBciColors.current.alarmTint else null)
            .clickable(enabled = enabled && !paused, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (!enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
            } else if (danger) LocalBciColors.current.alarm else MaterialTheme.colorScheme.onSurface,
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
            .zIndex(80f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalBciColors.current.ov)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
        )
        Surface(
            modifier = Modifier.fillMaxWidth(widthFraction),
            shape = RoundedCornerShape(24.dp),
            color = LocalBciColors.current.panel,
            border = androidx.compose.foundation.BorderStroke(2.dp, LocalBciColors.current.focus)
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
