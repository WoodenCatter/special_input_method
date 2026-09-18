package com.example.input_ds.ui.game

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.input_ds.game.SnakeAction
import com.example.input_ds.game.SnakeCell
import com.example.input_ds.game.SnakeClimbState
import com.example.input_ds.game.SnakeDirection
import com.example.input_ds.game.SnakeEndChoice
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.auroraBackground
import kotlinx.coroutines.delay

@Composable
fun SnakeClimbScreen(
    state: SnakeClimbState,
    moveIntervalMs: Long,
    onAction: (SnakeAction) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(
        state.step,
        state.gameOver,
        state.gameOverSelection,
        state.exitConfirmation,
        state.exitSelected,
        state.exitRequested,
        moveIntervalMs
    ) {
        if (!state.exitRequested) {
            delay(moveIntervalMs.coerceIn(1_100L, 3_000L))
            onAction(SnakeAction.TICK)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .auroraBackground()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("向上贪吃蛇", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = onBack) { Text("返回首页") }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                SnakeBoard(state, Modifier.fillMaxSize())
                GameInstructions(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(.20f)
                        .fillMaxHeight()
                )
            }
            SnakeLengthStatus(state.bodyLength)
        }

        if (state.exitConfirmation) ExitConfirmation(state, onAction)
        if (state.gameOver) GameOverDialog(state, onAction)
    }
}

@Composable
private fun SnakeLengthStatus(bodyLength: Int) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .background(Color(0xCC142A20), RoundedCornerShape(22.dp))
                .border(2.dp, Color(0xFF58C96F), RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("身体长度  ", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                bodyLength.toString(),
                color = Color(0xFFB7F36A),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun SnakeBoard(state: SnakeClimbState, modifier: Modifier = Modifier) {
    val boardBackground = Color(0xFF142A20)
    val alternateCell = Color(0xFF193426)
    val gridColor = Color(0xFF42614F)
    val snakeBodyColor = Color(0xFF58C96F)
    val snakeHeadColor = Color(0xFFB7F36A)
    val appleColor = Color(0xFFE8564E)
    val wallColor = Color(0xFF8E4C52)
    val wallBorder = Color(0xFFD78678)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val cell = minOf(
                size.height / state.visibleRows,
                size.width * .54f / state.columns
            )
            val width = cell * state.columns
            val height = cell * state.visibleRows
            val origin = Offset((size.width - width) / 2f, (size.height - height) / 2f)

            fun screenRow(worldRow: Int): Int =
                state.headDisplayRow - (worldRow - state.head.row)

            fun topLeft(cellPosition: SnakeCell): Offset {
                val row = screenRow(cellPosition.row)
                return Offset(origin.x + cellPosition.column * cell, origin.y + row * cell)
            }

            fun drawTree(center: Offset, scale: Float, alternate: Boolean) {
                val trunkWidth = cell * .13f * scale
                val trunkHeight = cell * .38f * scale
                drawRect(
                    color = Color(0xFF8A5A37),
                    topLeft = Offset(center.x - trunkWidth / 2f, center.y),
                    size = Size(trunkWidth, trunkHeight)
                )
                val leafColor = if (alternate) Color(0xFF397D49) else Color(0xFF2F6D3D)
                val leafRadius = cell * .22f * scale
                drawCircle(leafColor, leafRadius, Offset(center.x, center.y - leafRadius * .55f))
                drawCircle(leafColor.copy(alpha = .95f), leafRadius * .8f, Offset(center.x - leafRadius * .65f, center.y))
                drawCircle(leafColor.copy(alpha = .9f), leafRadius * .82f, Offset(center.x + leafRadius * .62f, center.y))
            }

            // 景物固定在世界坐标中。蛇向上前进一行时，同一棵树会在屏幕上向下移动一行。
            val leftEdgeWidth = origin.x
            val rightEdgeStart = origin.x + width
            val rightEdgeWidth = size.width - rightEdgeStart
            for (displayRow in -1..state.visibleRows) {
                val worldRow = state.head.row + state.headDisplayRow - displayRow
                val sceneryRandom = kotlin.random.Random(state.seed * 31 + worldRow * 9_973)
                val y = origin.y + (displayRow + .52f) * cell
                if (leftEdgeWidth > cell * .75f && sceneryRandom.nextInt(100) < 54) {
                    val usableWidth = (leftEdgeWidth - cell * .55f).coerceAtLeast(cell * .15f)
                    val x = cell * .28f + sceneryRandom.nextFloat() * usableWidth
                    drawTree(
                        center = Offset(x, y),
                        scale = .72f + sceneryRandom.nextFloat() * .35f,
                        alternate = sceneryRandom.nextBoolean()
                    )
                }
                if (rightEdgeWidth > cell * .75f && sceneryRandom.nextInt(100) < 54) {
                    val usableWidth = (rightEdgeWidth - cell * .55f).coerceAtLeast(cell * .15f)
                    val x = rightEdgeStart + cell * .28f + sceneryRandom.nextFloat() * usableWidth
                    drawTree(
                        center = Offset(x, y),
                        scale = .72f + sceneryRandom.nextFloat() * .35f,
                        alternate = sceneryRandom.nextBoolean()
                    )
                }
            }

            for (row in 0 until state.visibleRows) {
                for (column in 0 until state.columns) {
                    val topLeft = Offset(origin.x + column * cell, origin.y + row * cell)
                    drawRect(if ((row + column) % 2 == 0) boardBackground else alternateCell, topLeft, Size(cell, cell))
                }
            }
            drawRect(gridColor, origin, Size(width, height), style = Stroke(maxOf(2f, cell * .035f)))
            for (row in 0..state.visibleRows) {
                val y = origin.y + row * cell
                drawLine(gridColor, Offset(origin.x, y), Offset(origin.x + width, y), 1f)
            }
            for (column in 0..state.columns) {
                val x = origin.x + column * cell
                drawLine(gridColor, Offset(x, origin.y), Offset(x, origin.y + height), 1f)
            }

            state.walls.forEach { wall ->
                val row = screenRow(wall.row)
                if (row !in 0 until state.visibleRows) return@forEach
                val y = origin.y + row * cell
                for (column in 0 until state.columns) {
                    val wallCell = Offset(origin.x + column * cell, y)
                    drawRect(wallColor, wallCell, Size(cell, cell))
                    drawRect(wallBorder, wallCell, Size(cell, cell), style = Stroke(maxOf(1f, cell * .035f)))
                }
                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = cell * .62f
                    isFakeBoldText = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    wall.value.toString(),
                    origin.x + width / 2f,
                    y + cell * .72f,
                    paint
                )
            }

            state.apples.forEach { apple ->
                val row = screenRow(apple.row)
                if (row !in 0 until state.visibleRows) return@forEach
                val topLeft = topLeft(apple)
                drawCircle(
                    color = appleColor,
                    radius = cell * .28f,
                    center = topLeft + Offset(cell / 2f, cell * .56f)
                )
                drawRect(
                    color = snakeBodyColor,
                    topLeft = topLeft + Offset(cell * .47f, cell * .16f),
                    size = Size(cell * .08f, cell * .18f)
                )
            }

            state.body.asReversed().forEachIndexed { index, part ->
                val row = screenRow(part.row)
                if (part.column !in 0 until state.columns || row !in 0 until state.visibleRows) return@forEachIndexed
                val inset = cell * .11f
                drawRect(
                    color = snakeBodyColor.copy(
                        alpha = .78f + .22f * (index + 1).toFloat() / state.body.size.coerceAtLeast(1).toFloat()
                    ),
                    topLeft = topLeft(part) + Offset(inset, inset),
                    size = Size(cell - inset * 2, cell - inset * 2)
                )
            }

            val headTopLeft = topLeft(state.head)
            val headInset = cell * .07f
            val left = headTopLeft.x + headInset
            val top = headTopLeft.y + headInset
            val right = headTopLeft.x + cell - headInset
            val bottom = headTopLeft.y + cell - headInset
            val centerX = headTopLeft.x + cell / 2f
            val centerY = headTopLeft.y + cell / 2f
            val headPath = Path().apply {
                when (state.direction) {
                    SnakeDirection.UP -> {
                        moveTo(centerX, top)
                        lineTo(right, top + cell * .28f)
                        lineTo(right - cell * .08f, bottom)
                        lineTo(left + cell * .08f, bottom)
                        lineTo(left, top + cell * .28f)
                    }
                    SnakeDirection.LEFT -> {
                        moveTo(left, centerY)
                        lineTo(left + cell * .28f, top)
                        lineTo(right, top + cell * .08f)
                        lineTo(right, bottom - cell * .08f)
                        lineTo(left + cell * .28f, bottom)
                    }
                    SnakeDirection.RIGHT -> {
                        moveTo(right, centerY)
                        lineTo(right - cell * .28f, bottom)
                        lineTo(left, bottom - cell * .08f)
                        lineTo(left, top + cell * .08f)
                        lineTo(right - cell * .28f, top)
                    }
                }
                close()
            }
            drawPath(headPath, snakeHeadColor)

            val eyeWhite = Color(0xFFF5FFF1)
            val eyeDark = Color(0xFF16321E)
            val eyeRadius = cell * .085f
            val pupilRadius = cell * .038f
            val eyes = when (state.direction) {
                SnakeDirection.UP -> listOf(
                    Offset(headTopLeft.x + cell * .34f, headTopLeft.y + cell * .32f),
                    Offset(headTopLeft.x + cell * .66f, headTopLeft.y + cell * .32f)
                )
                SnakeDirection.LEFT -> listOf(
                    Offset(headTopLeft.x + cell * .30f, headTopLeft.y + cell * .35f),
                    Offset(headTopLeft.x + cell * .30f, headTopLeft.y + cell * .65f)
                )
                SnakeDirection.RIGHT -> listOf(
                    Offset(headTopLeft.x + cell * .70f, headTopLeft.y + cell * .35f),
                    Offset(headTopLeft.x + cell * .70f, headTopLeft.y + cell * .65f)
                )
            }
            val pupilOffset = when (state.direction) {
                SnakeDirection.UP -> Offset(0f, -cell * .025f)
                SnakeDirection.LEFT -> Offset(-cell * .025f, 0f)
                SnakeDirection.RIGHT -> Offset(cell * .025f, 0f)
            }
            eyes.forEach { eye ->
                drawCircle(eyeWhite, eyeRadius, eye)
                drawCircle(eyeDark, pupilRadius, eye + pupilOffset)
            }
            val tongueColor = Color(0xFFFF6E78)
            val tongueStart = when (state.direction) {
                SnakeDirection.UP -> Offset(centerX, top)
                SnakeDirection.LEFT -> Offset(left, centerY)
                SnakeDirection.RIGHT -> Offset(right, centerY)
            }
            val tongueEnd = tongueStart + when (state.direction) {
                SnakeDirection.UP -> Offset(0f, -cell * .16f)
                SnakeDirection.LEFT -> Offset(-cell * .16f, 0f)
                SnakeDirection.RIGHT -> Offset(cell * .16f, 0f)
            }
            drawLine(tongueColor, tongueStart, tongueEnd, maxOf(2f, cell * .035f))
        }
    }
}

@Composable
private fun GameInstructions(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xD91A2630), RoundedCornerShape(18.dp))
            .border(2.dp, Color(0xFF42614F), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "游戏玩法",
            color = Color(0xFFB7F36A),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black
        )
        Text("蛇头固定在倒数第三行，地图和树木会随前进向下移动。", fontSize = 15.sp)
        Text("左看：向左转", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("右看：向右转", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("咬牙：转向上方直行", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "直行时再次咬牙：打开退出游戏确认",
            modifier = Modifier
                .background(Color(0xFF6B3538), RoundedCornerShape(12.dp))
                .padding(9.dp),
            color = Color(0xFFFFE27A),
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
        Text("吃到苹果，身体长度增加 1。", fontSize = 15.sp)
        Text("通过数字墙时，身体长度必须大于墙上的数字，通过后扣除相应长度。", fontSize = 15.sp)
        Text("撞到左右边界时长度减 1，并自动转向上方；长度降到 0 则结束。", fontSize = 15.sp)
    }
}

@Composable
private fun ExitConfirmation(state: SnakeClimbState, onAction: (SnakeAction) -> Unit) {
    PromptOverlay(onOutsideClick = { onAction(SnakeAction.CANCEL_EXIT) }) {
        Text("确定要退出向上贪吃蛇吗？", style = MaterialTheme.typography.headlineSmall)
        Text("两个选项会自动轮流高亮，咬牙确认当前选项。也可以直接用手点击。")
        Text(
            if (state.exitSelected) "当前选择：退出游戏" else "当前选择：继续游戏",
            color = AuroraVioletBright,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PromptButton("继续游戏", !state.exitSelected) { onAction(SnakeAction.CANCEL_EXIT) }
            PromptButton("退出游戏", state.exitSelected) { onAction(SnakeAction.CONFIRM_EXIT) }
        }
    }
}

@Composable
private fun GameOverDialog(state: SnakeClimbState, onAction: (SnakeAction) -> Unit) {
    PromptOverlay(onOutsideClick = {}) {
        Text("游戏结束", style = MaterialTheme.typography.headlineSmall)
        Text(state.message)
        Text("共吃到 ${state.applesEaten} 个苹果，向上前进 ${state.head.row} 行。")
        Text("两个选项会自动轮流高亮，咬牙确认当前选项。也可以直接用手点击。")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PromptButton("重新开始", state.gameOverSelection == SnakeEndChoice.RESTART) {
                onAction(SnakeAction.RESTART)
            }
            PromptButton("退出游戏", state.gameOverSelection == SnakeEndChoice.EXIT) {
                onAction(SnakeAction.CONFIRM_EXIT)
            }
        }
    }
}

@Composable
private fun PromptOverlay(
    onOutsideClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color(0x99000000))
            .clickable(onClick = onOutsideClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .border(2.dp, AuroraVioletBright, RoundedCornerShape(24.dp))
                .clickable { }
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun PromptButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AuroraVioletBright else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) Color(0xFF1C1730) else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.then(
            if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(24.dp)) else Modifier
        )
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}
