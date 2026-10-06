package com.example.input_ds.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentHubSelectionState
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.SelectableGlassPanel
import kotlinx.coroutines.delay

@Composable
fun EntertainmentScreen(
    selection: EntertainmentHubSelectionState,
    scanIntervalMs: Long,
    onSelect: (EntertainmentHubModule) -> Unit,
    onAdvance: () -> Unit
) {
    LaunchedEffect(selection.selectedIndex, selection.scanDirection, scanIntervalMs) {
        delay(scanIntervalMs.coerceAtLeast(200L))
        onAdvance()
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("娱乐", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "光标自动轮转 ${if (selection.scanDirection < 0) "←" else "→"}  ·  " +
                        "左看/右看改变方向 · 咬牙选择",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = if (maxWidth >= 720.dp) 3 else 2
                    val rows = EntertainmentHubModule.entries.toList().chunked(columns)
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rows.forEach { modules ->
                            Row(
                                Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                modules.forEach { module ->
                                    EntertainmentCard(
                                        module = module,
                                        selected = module == selection.selectedModule,
                                        onClick = { onSelect(module) },
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                                repeat(columns - modules.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntertainmentCard(
    module: EntertainmentHubModule,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableGlassPanel(
        selected = selected,
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                module.symbol,
                color = if (selected) AuroraVioletBright else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(module.displayName, style = MaterialTheme.typography.titleLarge)
        }
    }
}
