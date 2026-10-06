package com.example.input_ds.ui.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.input_ds.game.MazeAction
import com.example.input_ds.game.MazeExitChoice
import com.example.input_ds.game.MazeGame
import com.example.input_ds.game.MazeProtocol
import com.example.input_ds.game.MazeState
import com.example.input_ds.ui.theme.AuroraBad
import com.example.input_ds.ui.theme.AuroraOk
import com.example.input_ds.ui.theme.AuroraPink
import com.example.input_ds.ui.theme.AuroraViolet
import com.example.input_ds.ui.theme.AuroraButton
import com.example.input_ds.ui.theme.AuroraButtonStyle
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.auroraBackground
import kotlinx.coroutines.delay

@Composable
fun MazeScreen(
    state: MazeState,
    controlStatus: String,
    onBack: () -> Unit,
    onAction: (MazeAction) -> Unit,
    onExitDecision: (MazeExitChoice) -> Unit,
    onReset: () -> Unit
) {
    LaunchedEffect(state.completed) {
        if (state.completed) {
            delay(1_500)
            onReset()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .auroraBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
    ) {
        Text("迷宫游戏", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxSize()) {
            GlassPanel(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("动作说明：")
                    Text("咬牙 = 向上")
                    Text("左看 = 向左")
                    Text("右看 = 向右")
                }
            }
            MazeBoard(state, Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (state.exitDialogVisible) {
        ExitConfirmationDialog(
            selection = state.exitSelection,
            onDecision = onExitDecision
        )
    }
}

@Composable
private fun ExitConfirmationDialog(
    selection: MazeExitChoice,
    onDecision: (MazeExitChoice) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDecision(MazeExitChoice.CANCEL) },
        title = { Text("确认退出迷宫？") },
        text = {
            Text("左看选择取消，右看选择确认退出，咬牙确认。")
        },
        dismissButton = {
            TextButton(onClick = { onDecision(MazeExitChoice.CANCEL) }) {
                Text(
                    text = if (selection == MazeExitChoice.CANCEL) "● 取消" else "取消",
                    fontWeight = if (selection == MazeExitChoice.CANCEL) FontWeight.Bold else FontWeight.Normal
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(MazeExitChoice.CONFIRM) }) {
                Text(
                    text = if (selection == MazeExitChoice.CONFIRM) "● 确认退出" else "确认退出",
                    fontWeight = if (selection == MazeExitChoice.CONFIRM) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    )
}

@Composable
private fun MazeBoard(state: MazeState, modifier: Modifier = Modifier) {
    val wallColor = MaterialTheme.colorScheme.background
    val pathColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val gridColor = MaterialTheme.colorScheme.outline
    val playerColor = MaterialTheme.colorScheme.onSurface
    val playerBorder = MaterialTheme.colorScheme.background
    val exitColor = MaterialTheme.colorScheme.tertiaryContainer
    val exitTextColor = MaterialTheme.colorScheme.onTertiaryContainer.toArgb()
    val exitTextPaint = remember(exitTextColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = exitTextColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val description = "${state.columns}\u5217${state.rows}\u884c\u8ff7\u5bab\uff0c\u73a9\u5bb6\u7b2c${state.player.column + 1}\u5217" +
        "\u7b2c${state.player.row + 1}\u884c\uff0c\u7ec8\u70b9\u7b2c${state.finish.column + 1}\u5217\u7b2c${state.finish.row + 1}\u884c" +
        state.exitPoint?.let { "，退出点第${it.column + 1}列第${it.row + 1}行" }.orEmpty()
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .aspectRatio(state.columns.toFloat() / state.rows)
                .fillMaxSize()
                .semantics { contentDescription = description }
        ) {
            val cell = minOf(size.width / state.columns, size.height / state.rows)
            val boardWidth = cell * state.columns
            val boardHeight = cell * state.rows
            val origin = Offset((size.width - boardWidth) / 2f, (size.height - boardHeight) / 2f)
            for (row in 0 until state.rows) {
                for (column in 0 until state.columns) {
                    val position = com.example.input_ds.game.MazePosition(column, row)
                    val topLeft = Offset(origin.x + column * cell, origin.y + row * cell)
                    drawRect(if (position in state.walkable) pathColor else wallColor, topLeft, Size(cell, cell))
                    drawRect(gridColor, topLeft, Size(cell, cell), style = Stroke(width = 1f))
                }
            }
            fun center(position: com.example.input_ds.game.MazePosition) = Offset(
                origin.x + position.column * cell + cell / 2f,
                origin.y + position.row * cell + cell / 2f
            )
            drawCircle(AuroraOk, radius = cell * .28f, center = center(state.start), style = Stroke(cell * .08f))
            drawCircle(AuroraViolet, radius = cell * .3f, center = center(state.finish))
            state.exitPoint?.let { exit ->
                val topLeft = Offset(
                    origin.x + exit.column * cell + cell * .1f,
                    origin.y + exit.row * cell + cell * .18f
                )
                drawRoundRect(
                    color = exitColor,
                    topLeft = topLeft,
                    size = Size(cell * .8f, cell * .64f),
                    cornerRadius = CornerRadius(cell * .12f)
                )
                exitTextPaint.textSize = cell * .25f
                val metrics = exitTextPaint.fontMetrics
                drawContext.canvas.nativeCanvas.drawText(
                    "退出",
                    center(exit).x,
                    center(exit).y - (metrics.ascent + metrics.descent) / 2f,
                    exitTextPaint
                )
            }
            state.barriers.forEach { (position, action) ->
                val topLeft = Offset(
                    origin.x + position.column * cell + cell * .18f,
                    origin.y + position.row * cell + cell * .18f
                )
                if (action == MazeAction.LEFT_RIGHT) {
                    drawRect(AuroraBad, topLeft, Size(cell * .64f, cell * .64f))
                } else {
                    drawCircle(AuroraPink, radius = cell * .32f, center = center(position))
                }
            }
            drawCircle(playerColor, radius = cell * .24f, center = center(state.player))
            drawCircle(playerBorder, radius = cell * .24f, center = center(state.player), style = Stroke(cell * .05f))
        }
        if (state.completed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text("\u5230\u8fbe\u7ec8\u70b9\uff01\u5373\u5c06\u5f00\u59cb\u65b0\u4e00\u5c40", modifier = Modifier.padding(18.dp))
            }
        }
    }
}

@Composable
private fun MazeStatus(state: MazeState, controlStatus: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("\u63a7\u5236\u72b6\u6001", style = MaterialTheme.typography.titleMedium)
            Text(controlStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("\u6700\u8fd1\u52a8\u4f5c", style = MaterialTheme.typography.titleMedium)
            Text(state.message)
            Text("\u52a8\u4f5c\u8bf4\u660e", style = MaterialTheme.typography.titleMedium)
            Text("\u54ac\u7259 = \u5411\u4e0a")
            Text("\u5de6\u770b = \u5411\u5de6")
            Text("\u53f3\u770b = \u5411\u53f3")
            if (state.protocol == MazeProtocol.SIX_ACTION) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(AuroraBad))
                    Text("  \u5de6\u53f3 = \u6e05\u9664\u65b9\u5f62\u969c\u788d")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(AuroraPink, CircleShape))
                    Text("  \u53f3\u5de6 = \u6e05\u9664\u5706\u5f62\u969c\u788d")
                }
            }
        }
    }
}
