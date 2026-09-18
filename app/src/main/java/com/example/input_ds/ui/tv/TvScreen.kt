package com.example.input_ds.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.input_ds.media.PlaybackUiState
import com.example.input_ds.media.TvChannel
import com.example.input_ds.model.TvSelectionState
import com.example.input_ds.ui.entertainment.EntertainmentFocusButton
import com.example.input_ds.ui.entertainment.EntertainmentTopBar
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.GlassPanel

@Composable
fun TvScreen(
    channels: List<TvChannel>,
    playback: PlaybackUiState,
    selection: TvSelectionState,
    onBack: () -> Unit,
    onChannel: (Int) -> Unit
) {
    val channel = channels[playback.currentIndex.coerceIn(channels.indices)]
    var webStatus by remember(channel.id) { mutableStateOf(CctvWebStatus.LOADING) }
    AuroraBackground {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EntertainmentTopBar(
                title = "电视",
                onBack = onBack,
                backSelected = selection.selectedIndex == 0
            )
            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playback.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            webStatus == CctvWebStatus.ERROR -> "央视播放器加载失败，请检查网络后重新选择频道"
                            webStatus == CctvWebStatus.PLAYING -> "播放中"
                            else -> "正在加载央视播放器"
                        },
                        color = if (webStatus == CctvWebStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            AuroraCyan
                        }
                    )
                }
                CctvOfficialPlayer(
                    channel = channel,
                    onStatus = { webStatus = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    channels.forEachIndexed { index, channel ->
                        EntertainmentFocusButton(
                            text = channel.displayName,
                            selected = selection.selectedIndex == index + 1,
                            onClick = { onChannel(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    "画面由央视网官方播放器提供",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
