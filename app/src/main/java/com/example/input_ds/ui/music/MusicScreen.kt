package com.example.input_ds.ui.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.input_ds.media.MusicTrack
import com.example.input_ds.media.PlaybackUiState
import com.example.input_ds.model.MusicControlAction
import com.example.input_ds.model.MusicSelectionMode
import com.example.input_ds.model.MusicSelectionState
import com.example.input_ds.ui.entertainment.EntertainmentFocusButton
import com.example.input_ds.ui.entertainment.EntertainmentTopBar
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.AuroraDarkAccentSoft
import com.example.input_ds.ui.theme.AuroraDarkBorderStrong
import com.example.input_ds.ui.theme.AuroraDarkSurfaceStrong
import com.example.input_ds.ui.theme.AuroraPink
import com.example.input_ds.ui.theme.AuroraViolet
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel

@Composable
fun MusicScreen(
    tracks: List<MusicTrack>,
    playback: PlaybackUiState,
    selection: MusicSelectionState,
    onBack: () -> Unit,
    onControl: (MusicControlAction) -> Unit,
    onTrack: (Int) -> Unit
) {
    AuroraBackground {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EntertainmentTopBar(
                title = "音乐",
                onBack = onBack,
                backSelected = selection.mode == MusicSelectionMode.CONTROLS &&
                    selection.selectedControl == MusicControlAction.BACK
            )
            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    AlbumArtwork(Modifier.size(190.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (playback.isPlaying) "正在播放" else "已暂停",
                            color = if (playback.isPlaying) AuroraCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(playback.title, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            playback.subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (playback.durationMs > 0L) {
                                    (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)),
                            color = AuroraCyan,
                            trackColor = AuroraDarkSurfaceStrong
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatMediaTime(playback.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMediaTime(playback.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        playback.errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MusicControlAction.entries.filterNot { it == MusicControlAction.BACK }.forEach { action ->
                        EntertainmentFocusButton(
                            text = action.label(
                                isPlaying = playback.isPlaying,
                                playbackFailed = playback.errorMessage != null
                            ),
                            leading = action.symbol(playback.isPlaying),
                            selected = selection.mode == MusicSelectionMode.CONTROLS &&
                                selection.selectedControl == action,
                            onClick = { onControl(action) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("歌单", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (selection.mode == MusicSelectionMode.PLAYLIST) {
                            "歌单选择中 · 左右看切换 · 咬牙播放"
                        } else {
                            "选择“选择”后进入歌单"
                        },
                        color = if (selection.mode == MusicSelectionMode.PLAYLIST) AuroraVioletBright
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.forEachIndexed { index, track ->
                        TrackRow(
                            track = track,
                            playing = playback.currentIndex == index && playback.isPlaying,
                            selected = selection.mode == MusicSelectionMode.PLAYLIST &&
                                selection.highlightedTrackIndex == index,
                            onClick = { onTrack(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.large).background(
            Brush.linearGradient(listOf(Color(0xFF27175C), Color(0xFF075A73), Color(0xFF642A70)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Text("♪", color = AuroraVioletBright, style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    playing: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) AuroraDarkAccentSoft else AuroraDarkSurfaceStrong,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) AuroraViolet else AuroraDarkBorderStrong
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (playing) "▮▮" else "♪", color = if (playing) AuroraCyan else AuroraPink)
            Spacer(Modifier.width(14.dp))
            Text(track.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("${track.artist} · ${track.license}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(18.dp))
            Text("开放音源", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun MusicControlAction.label(isPlaying: Boolean, playbackFailed: Boolean): String = when (this) {
    MusicControlAction.BACK -> "返回"
    MusicControlAction.PREVIOUS -> "上一首"
    MusicControlAction.PLAY_PAUSE -> when {
        playbackFailed -> "重试"
        isPlaying -> "暂停"
        else -> "播放"
    }
    MusicControlAction.NEXT -> "下一首"
    MusicControlAction.SELECT -> "选择"
}

private fun MusicControlAction.symbol(isPlaying: Boolean): String = when (this) {
    MusicControlAction.BACK -> "←"
    MusicControlAction.PREVIOUS -> "|◁"
    MusicControlAction.PLAY_PAUSE -> if (isPlaying) "Ⅱ" else "▷"
    MusicControlAction.NEXT -> "▷|"
    MusicControlAction.SELECT -> "≡"
}

private fun formatMediaTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
