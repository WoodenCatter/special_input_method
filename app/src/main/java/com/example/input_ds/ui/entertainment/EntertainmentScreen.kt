package com.example.input_ds.ui.entertainment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.EntertainmentAction
import com.example.input_ds.model.EntertainmentSelectionState
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.SelectableGlassPanel

@Composable
fun EntertainmentScreen(
    selection: EntertainmentSelectionState,
    onBack: () -> Unit,
    onMusic: () -> Unit,
    onTelevision: () -> Unit
) {
    AuroraBackground {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntertainmentTopBar(
                title = "娱乐",
                onBack = onBack,
                backSelected = selection.selectedAction == EntertainmentAction.BACK
            )
            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EntertainmentCard(
                        symbol = "♪",
                        title = "音乐",
                        subtitle = "播放列表与基础控制",
                        selected = selection.selectedAction == EntertainmentAction.MUSIC,
                        onClick = onMusic,
                        modifier = Modifier.weight(1f)
                    )
                    EntertainmentCard(
                        symbol = "▻",
                        title = "电视",
                        subtitle = "直播频道与快速换台",
                        selected = selection.selectedAction == EntertainmentAction.TV,
                        onClick = onTelevision,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "左看 / 右看切换焦点 · 咬牙确认",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EntertainmentCard(
    symbol: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableGlassPanel(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(260.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                symbol,
                color = if (selected) AuroraVioletBright else AuroraCyan,
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
