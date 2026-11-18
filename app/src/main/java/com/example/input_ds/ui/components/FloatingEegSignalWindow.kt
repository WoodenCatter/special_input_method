package com.example.input_ds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.ui.bci.EegStereoView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Shares the existing acquisition instance with the separate Godot activity. */
internal object FloatingEegSignalSource {
    private val currentManager = MutableStateFlow<NaoyunBleManager?>(null)
    val manager: StateFlow<NaoyunBleManager?> = currentManager

    fun attach(manager: NaoyunBleManager) {
        currentManager.value = manager
    }

    fun detach(manager: NaoyunBleManager) {
        if (currentManager.value === manager) currentManager.value = null
    }
}

/** Passive signal monitor. Only its header captures touches for dragging. */
@Composable
internal fun FloatingEegSignalWindow(
    bleManager: NaoyunBleManager,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val state by bleManager.state.collectAsState()
    val diagnostics by bleManager.streamDiagnostics.collectAsState()
    val dataEpoch by bleManager.synchronizedDataEpoch.collectAsState()
    var offsetX by rememberSaveable { mutableStateOf(Float.NaN) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }

    // Keep the monitor visible during a stalled/recovering stream: these are
    // precisely the conditions for which the waveform is useful.
    val connected = state == NaoyunBleManager.State.READY ||
        state == NaoyunBleManager.State.STREAM_STALLED ||
        state == NaoyunBleManager.State.RECOVERING
    if (!visible || !connected) return

    BoxWithConstraints(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
            .zIndex(900f)
    ) {
        val panelWidth = minOf(340.dp, maxWidth)
        val panelHeight = minOf(210.dp, maxHeight)
        val density = LocalDensity.current
        val maxOffsetX = with(density) { (maxWidth - panelWidth).toPx().coerceAtLeast(0f) }
        val maxOffsetY = with(density) { (maxHeight - panelHeight).toPx().coerceAtLeast(0f) }

        LaunchedEffect(maxOffsetX, maxOffsetY) {
            offsetX = if (offsetX.isNaN()) maxOffsetX else offsetX.coerceIn(0f, maxOffsetX)
            offsetY = offsetY.coerceIn(0f, maxOffsetY)
        }

        val warning = state != NaoyunBleManager.State.READY ||
            diagnostics.delayed || diagnostics.stalledSide != null
        val status = when {
            state == NaoyunBleManager.State.RECOVERING -> "恢复中"
            state == NaoyunBleManager.State.STREAM_STALLED || diagnostics.stalledSide != null -> "数据中断"
            diagnostics.delayed -> "数据延迟"
            else -> "实时"
        }
        val statusColor = if (warning) Color(0xFFFFB36B) else Color(0xFF85E6BA)
        val shape = RoundedCornerShape(14.dp)

        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (if (offsetX.isNaN()) maxOffsetX else offsetX.coerceIn(0f, maxOffsetX)).roundToInt(),
                        offsetY.coerceIn(0f, maxOffsetY).roundToInt()
                    )
                }
                .size(panelWidth, panelHeight)
                .clip(shape)
                .background(Color(0xF20B1025))
                .border(1.dp, Color(0xFF6D7CA9), shape)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(30.dp)
                    .pointerInput(maxOffsetX, maxOffsetY) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val currentX = if (offsetX.isNaN()) maxOffsetX else offsetX
                            offsetX = (currentX + drag.x).coerceIn(0f, maxOffsetX)
                            offsetY = (offsetY + drag.y).coerceIn(0f, maxOffsetY)
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⠿  耳机波形", color = Color(0xFFE6EAF6), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(status, color = statusColor, fontSize = 12.sp)
            }

            // A fresh view after an acquisition gap prevents an old trace from
            // looking like current data while the new timeline fills up.
            key(bleManager, dataEpoch) {
                AndroidView(
                    factory = {
                        EegStereoView(it, bleManager, refreshIntervalMs = 100L, compactMode = true)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            Text(
                "1–45 Hz · 延迟 L:${diagnostics.leftAgeMs ?: "--"} ms  R:${diagnostics.rightAgeMs ?: "--"} ms",
                color = if (warning) statusColor else Color(0xFFB5C0DB),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
