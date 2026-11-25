package com.example.input_ds.ui.game

import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.game.FruitTier
import com.example.input_ds.game.WatermelonEngine
import com.example.input_ds.game.WatermelonFocus
import com.example.input_ds.game.WatermelonGame
import com.example.input_ds.game.WatermelonUiState
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun WatermelonScreen(
    engine: WatermelonEngine,
    state: WatermelonUiState,
    externalRenderRevision: Long,
    paused: Boolean,
    frozen: Boolean,
    soundEnabled: Boolean,
    onStepPhysics: (Float) -> Boolean,
    onTouchAimAtX: (Float) -> Unit,
    onTouchDropAtX: (Float) -> Unit,
    onBack: () -> Unit
) {
    val c = LocalBciColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = remember { WatermelonSoundPlayer() }
    var frameTick by remember { mutableLongStateOf(0L) }

    DisposableEffect(audio) {
        onDispose { audio.release() }
    }

    LaunchedEffect(state.dropSoundRevision) {
        if (state.dropSoundRevision > 0 && soundEnabled && !frozen) {
            audio.playDrop()
        }
    }
    LaunchedEffect(state.mergeSoundRevision) {
        if (state.mergeSoundRevision > 0 && soundEnabled && !frozen) {
            audio.playMerge(state.lastMergeSoundTier)
        }
    }

    // 60Hz fixed-timestep Box2D loop — invalidates only the Canvas draw phase via frameTick.
    LaunchedEffect(paused, frozen, state.gameOver, state.exitConfirmation, state.exitRequested, lifecycleOwner) {
        if (!paused && !frozen && !state.gameOver && !state.exitConfirmation && !state.exitRequested) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var lastNanos = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    if (lastNanos != 0L) {
                        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                        if (dt > 0f && onStepPhysics(dt)) {
                            frameTick++
                        }
                    }
                    lastNanos = now
                }
            }
        }
    }

    val hints = remember(state.focus, state.currentTier, state.dropReady) {
        when (val focus = state.focus) {
            WatermelonFocus.Exit -> ActionHints(
                left = "移到最右",
                bite = "离开游戏",
                right = "移到最左"
            )
            is WatermelonFocus.DropSlot -> ActionHints(
                left = if (focus.slot == 0) "离开游戏" else "向左移",
                bite = if (!state.dropReady) "落稳中…" else "放下「${state.currentTier.label}」",
                right = if (focus.slot == WatermelonGame.SLOT_COUNT - 1) "离开游戏" else "向右移"
            )
        }
    }
    ReportActionHints(hints)

    val animatedHeldX by animateFloatAsState(
        targetValue = state.aimX,
        animationSpec = if (frozen) spring(stiffness = Spring.StiffnessHigh) else spring(
            dampingRatio = 0.80f,
            stiffness = 460f
        ),
        label = "heldFruitX"
    )

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left column: Title + Score + Next Fruit + Exit BCI slot
        Column(
            modifier = Modifier
                .width(284.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "合成大西瓜",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 32.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (state.watermelonsCreated > 0) {
                    Box(
                        modifier = Modifier
                            .background(c.okTint, CircleShape)
                            .border(1.dp, c.ok, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "大西瓜 ×${state.watermelonsCreated}",
                            color = c.ok,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }

            // Score card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.panel, RoundedCornerShape(18.dp))
                    .border(2.dp, c.line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "当前得分",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = state.score.toString(),
                        color = c.focus,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight(800),
                        fontSize = 40.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(c.line)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "最高纪录",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 16.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = state.bestScore.toString(),
                        color = c.text,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            // Next fruit preview card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(c.panel, RoundedCornerShape(18.dp))
                    .border(2.dp, c.line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "下一个",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = state.nextTier.label,
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Canvas(modifier = Modifier.size(96.dp)) {
                    val previewR = (size.minDimension * 0.36f)
                        .coerceAtMost(22.dp.toPx() + state.nextTier.level * 4.dp.toPx())
                    drawFruit(
                        tier = state.nextTier,
                        center = Offset(size.width / 2f, size.height / 2f + 2.dp.toPx()),
                        radius = previewR,
                        angleRad = 0f,
                        outlineColor = c.text.copy(alpha = 0.22f)
                    )
                }
                Text(
                    text = "不限时间 · 慢慢瞄准",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 15.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            // Exit card — reachable by BCI looking past either edge of the jar, or by touch.
            val exitFocused = state.focus == WatermelonFocus.Exit
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .scanItem(
                        focused = exitFocused,
                        direction = 0,
                        picked = false,
                        progress = null,
                        navigation = true,
                        showDirectionTag = false
                    )
                    .clickable(onClick = onBack)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_arrow_left),
                    contentDescription = null,
                    tint = if (exitFocused) c.text else c.muted,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "离开游戏",
                    color = if (exitFocused) c.text else c.muted,
                    fontFamily = BciType.chinese,
                    fontWeight = if (exitFocused) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 22.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        // Center column: Box2D fruit jar playfield
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            WatermelonBoard(
                engine = engine,
                state = state,
                heldX = animatedHeldX,
                renderTrigger = { frameTick + externalRenderRevision },
                onTouchAimAtX = onTouchAimAtX,
                onTouchDropAtX = onTouchDropAtX,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(WatermelonGame.WORLD_WIDTH / WatermelonGame.WORLD_HEIGHT)
            )
        }

        // Right column: 11-tier Evolution Ladder
        FruitEvolutionPanel(
            currentTier = state.currentTier,
            highestTier = state.highestTier,
            modifier = Modifier
                .width(304.dp)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun WatermelonBoard(
    engine: WatermelonEngine,
    state: WatermelonUiState,
    heldX: Float,
    renderTrigger: () -> Long,
    onTouchAimAtX: (Float) -> Unit,
    onTouchDropAtX: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    val exitFocused = state.focus == WatermelonFocus.Exit

    val popupPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    val boardDescription = "合成大西瓜果篮，当前得分${state.score}，手持${state.currentTier.label}，共${state.fruitCount}个水果"

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.panel)
            .border(
                width = if (state.dangerActive) 3.dp else 2.dp,
                color = if (state.dangerActive) c.alarm else c.line,
                shape = RoundedCornerShape(24.dp)
            )
            .semantics { contentDescription = boardDescription }
            .pointerInput(state.gameOver, state.exitConfirmation) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    val widthPx = size.width.coerceAtLeast(1).toFloat()
                    onTouchAimAtX((lastX / widthPx) * WatermelonGame.WORLD_WIDTH)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        lastX = change.position.x
                        if (event.type == PointerEventType.Move) {
                            onTouchAimAtX((lastX / widthPx) * WatermelonGame.WORLD_WIDTH)
                        } else if (!change.pressed) {
                            onTouchDropAtX((lastX / widthPx) * WatermelonGame.WORLD_WIDTH)
                            break
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read frame trigger strictly inside draw scope so 60fps Box2D only redraws Canvas.
            @Suppress("UNUSED_VARIABLE")
            val tick = renderTrigger()
            val fruits = engine.fruitsSnapshot()
            val mergeEvents = engine.mergeEventsSnapshot()
            val dangerFraction = engine.dangerFraction()
            val dangerActive = dangerFraction > 0.05f

            val sx = size.width / WatermelonGame.WORLD_WIDTH
            val sy = size.height / WatermelonGame.WORLD_HEIGHT
            val scale = minOf(sx, sy)
            val boardW = WatermelonGame.WORLD_WIDTH * scale
            val boardH = WatermelonGame.WORLD_HEIGHT * scale
            val ox = (size.width - boardW) / 2f
            val oy = (size.height - boardH) / 2f

            fun wx(x: Float): Float = ox + x * scale
            fun wy(y: Float): Float = oy + y * scale

            // Soft glass-jar depth gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c.panel2.copy(alpha = 0.40f),
                        c.panel,
                        c.panel2.copy(alpha = 0.85f)
                    ),
                    startY = oy,
                    endY = oy + boardH
                ),
                topLeft = Offset(ox, oy),
                size = Size(boardW, boardH)
            )

            // Danger threshold dashed line + smooth countdown fill bar
            val dangerY = wy(WatermelonGame.DANGER_LINE_Y)
            drawLine(
                color = if (dangerActive) c.alarm.copy(alpha = 0.85f) else c.line,
                start = Offset(ox + 10.dp.toPx(), dangerY),
                end = Offset(ox + boardW - 10.dp.toPx(), dangerY),
                strokeWidth = if (dangerActive) 3.dp.toPx() else 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
            )
            if (dangerActive) {
                val barTotalW = boardW - 20.dp.toPx()
                val fillW = barTotalW * dangerFraction
                drawRoundRect(
                    color = c.alarm.copy(alpha = 0.25f),
                    topLeft = Offset(ox + 10.dp.toPx(), dangerY - 3.dp.toPx()),
                    size = Size(fillW, 6.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }

            // Top aim rail with 9 crisp BCI slot ticks
            val railY = wy(15f)
            drawLine(
                color = c.line,
                start = Offset(wx(WatermelonGame.slotX(0, 14f)), railY),
                end = Offset(wx(WatermelonGame.slotX(WatermelonGame.SLOT_COUNT - 1, 14f)), railY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            for (slot in 0 until WatermelonGame.SLOT_COUNT) {
                val dotX = wx(WatermelonGame.slotX(slot, state.currentTier.radius))
                val isCurrentSlot = !exitFocused && state.activeSlot == slot
                drawCircle(
                    color = if (isCurrentSlot) c.focus else c.dim.copy(alpha = 0.50f),
                    radius = if (isCurrentSlot) 5.dp.toPx() else 2.8.dp.toPx(),
                    center = Offset(dotX, railY)
                )
            }

            // Vertical landing guide line & landing ghost circle
            if (!state.gameOver) {
                val dropCenterX = wx(heldX)
                val spawnCenterY = wy(WatermelonGame.SPAWN_Y)
                val landingWorldY = WatermelonGame.predictLandingY(
                    fruits = fruits,
                    dropX = heldX,
                    radius = state.currentTier.radius
                )
                val landingY = wy(landingWorldY)
                val guideAlpha = when {
                    exitFocused -> 0.14f
                    !state.dropReady -> 0.22f
                    else -> 0.55f
                }
                drawLine(
                    color = c.focus.copy(alpha = guideAlpha),
                    start = Offset(dropCenterX, spawnCenterY + state.currentTier.radius * scale),
                    end = Offset(dropCenterX, landingY),
                    strokeWidth = 2.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx()))
                )
                if (!exitFocused && state.dropReady) {
                    drawCircle(
                        color = c.focus.copy(alpha = 0.26f),
                        radius = state.currentTier.radius * scale,
                        center = Offset(dropCenterX, landingY),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                        )
                    )
                }

                // Held fruit ready at top
                val heldAlpha = when {
                    exitFocused -> 0.35f
                    !state.dropReady -> 0.45f
                    else -> 1f
                }
                drawFruit(
                    tier = state.currentTier,
                    center = Offset(dropCenterX, spawnCenterY),
                    radius = state.currentTier.radius * scale,
                    angleRad = 0f,
                    outlineColor = if (!exitFocused && state.dropReady) c.focus else c.text.copy(alpha = 0.2f),
                    alpha = heldAlpha,
                    highlightRing = !exitFocused && state.dropReady
                )
            }

            // Placed Box2D physics fruits with subtle contact shadow
            for (fruit in fruits) {
                val p = fruit.popProgress
                val popScale = if (p < 1f) {
                    if (p < 0.55f) 0.78f + (p / 0.55f) * 0.30f else 1.08f - ((p - 0.55f) / 0.45f) * 0.08f
                } else {
                    1f
                }
                val cx = wx(fruit.x)
                val cy = wy(fruit.y)
                val r = fruit.radius * scale * popScale
                // Soft grounding shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.08f),
                    radius = r * 0.98f,
                    center = Offset(cx, cy + 3.dp.toPx())
                )
                drawFruit(
                    tier = fruit.tier,
                    center = Offset(cx, cy),
                    radius = r,
                    angleRad = fruit.angle,
                    outlineColor = c.text.copy(alpha = 0.22f)
                )
            }

            // Merge burst rings, droplets & floating score callouts
            for (ev in mergeEvents) {
                val p = (ev.elapsedSeconds / WatermelonGame.MERGE_EFFECT_DURATION_SECONDS).coerceIn(0f, 1f)
                val fade = (1f - p).pow(1.4f)
                val cx = wx(ev.x)
                val cy = wy(ev.y)
                val palette = fruitPalette(ev.tier)
                val baseR = ev.tier.radius * scale

                drawCircle(
                    color = palette.core.copy(alpha = 0.65f * fade),
                    radius = baseR * (0.75f + p * 0.95f),
                    center = Offset(cx, cy),
                    style = Stroke(width = (5.dp.toPx() * fade).coerceAtLeast(1f))
                )

                val dropletCount = if (ev.isDoubleWatermelon) 14 else 8
                for (k in 0 until dropletCount) {
                    val theta = (2.0 * PI * k / dropletCount) + (ev.id % 5) * 0.25
                    val travel = baseR * (0.45f + p * 1.15f)
                    val dx = cx + (cos(theta) * travel).toFloat()
                    val dy = cy + (sin(theta) * travel).toFloat()
                    drawCircle(
                        color = palette.highlight.copy(alpha = 0.85f * fade),
                        radius = (5.dp.toPx() * (1f - p * 0.6f)).coerceAtLeast(1.5f),
                        center = Offset(dx, dy)
                    )
                }

                val floatY = cy - baseR * 0.35f - p * 44.dp.toPx()
                val label = when {
                    ev.isDoubleWatermelon -> "大西瓜大爆发 +${ev.points}"
                    ev.tier == FruitTier.WATERMELON -> "大西瓜！+${ev.points}"
                    ev.combo >= 2 -> "连击×${ev.combo} +${ev.points}"
                    else -> "+${ev.points}"
                }
                popupPaint.textSize = if (ev.tier == FruitTier.WATERMELON || ev.combo >= 2) {
                    22.sp.toPx()
                } else {
                    18.sp.toPx()
                }
                popupPaint.color = c.bg.copy(alpha = 0.88f * fade).toArgb()
                popupPaint.style = Paint.Style.STROKE
                popupPaint.strokeWidth = 5f
                drawContext.canvas.nativeCanvas.drawText(label, cx, floatY, popupPaint)

                popupPaint.color = (if (ev.tier == FruitTier.WATERMELON) c.ok else c.focus)
                    .copy(alpha = fade)
                    .toArgb()
                popupPaint.style = Paint.Style.FILL
                drawContext.canvas.nativeCanvas.drawText(label, cx, floatY, popupPaint)
            }

            // Warm basket base accent strip
            drawRoundRect(
                color = c.line.copy(alpha = 0.65f),
                topLeft = Offset(ox + 16.dp.toPx(), oy + boardH - 5.dp.toPx()),
                size = Size(boardW - 32.dp.toPx(), 4.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }

        // Danger warning pill
        if (state.dangerActive && !state.gameOver) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(c.alarm, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "快到顶线了 · ${state.dangerRemainingSeconds}s",
                    color = c.onAlarm,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun FruitEvolutionPanel(
    currentTier: FruitTier,
    highestTier: FruitTier,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    Column(
        modifier = modifier
            .background(c.panel, RoundedCornerShape(18.dp))
            .border(2.dp, c.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "合成路线",
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = "${highestTier.level + 1} / ${FruitTier.entries.size}",
                color = c.muted,
                fontFamily = BciType.latin,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        val rows = FruitTier.entries.take(10).chunked(2)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { tier ->
                        EvolutionChip(
                            tier = tier,
                            isCurrent = tier == currentTier,
                            isUnlocked = tier.level <= highestTier.level,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
            EvolutionChip(
                tier = FruitTier.WATERMELON,
                isCurrent = false,
                isUnlocked = highestTier == FruitTier.WATERMELON,
                featured = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            )
        }
    }
}

@Composable
private fun EvolutionChip(
    tier: FruitTier,
    isCurrent: Boolean,
    isUnlocked: Boolean,
    modifier: Modifier = Modifier,
    featured: Boolean = false
) {
    val c = LocalBciColors.current
    val bg = when {
        featured && isUnlocked -> c.okTint
        isCurrent -> c.focusTint
        else -> c.panel2
    }
    val borderColor = when {
        featured && isUnlocked -> c.ok
        isCurrent -> c.focus
        else -> c.line
    }
    val contentAlpha = if (isUnlocked || isCurrent) 1f else 0.45f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (isCurrent || (featured && isUnlocked)) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(if (featured) 34.dp else 28.dp)) {
            drawFruit(
                tier = tier,
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension * 0.42f,
                angleRad = 0f,
                outlineColor = c.text.copy(alpha = 0.2f * contentAlpha),
                alpha = contentAlpha,
                simplified = true
            )
        }
        Text(
            text = if (featured) "终极目标 · ${tier.label}" else tier.label,
            color = if (isCurrent) c.text else c.text.copy(alpha = contentAlpha),
            fontFamily = BciType.chinese,
            fontWeight = if (isCurrent || featured) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (featured) 18.sp else 16.sp,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

private data class FruitPalette(
    val highlight: Color,
    val core: Color,
    val rim: Color,
    val detail: Color
)

private fun fruitPalette(tier: FruitTier): FruitPalette = when (tier) {
    FruitTier.CHERRY -> FruitPalette(Color(0xFFFF7A7A), Color(0xFFD92B3A), Color(0xFF961522), Color(0xFF5D3A1A))
    FruitTier.STRAWBERRY -> FruitPalette(Color(0xFFFF8882), Color(0xFFF04747), Color(0xFFB5222D), Color(0xFFFFE082))
    FruitTier.GRAPE -> FruitPalette(Color(0xFFC38BFF), Color(0xFF8B47C7), Color(0xFF56238A), Color(0xFF53A653))
    FruitTier.TANGERINE -> FruitPalette(Color(0xFFFFD166), Color(0xFFF59E1D), Color(0xFFC76B08), Color(0xFF4BA651))
    FruitTier.ORANGE -> FruitPalette(Color(0xFFFFA96B), Color(0xFFF26D2B), Color(0xFFC24312), Color(0xFFFFD8A8))
    FruitTier.APPLE -> FruitPalette(Color(0xFFFF7E73), Color(0xFFD93636), Color(0xFF941B22), Color(0xFF53A653))
    FruitTier.PEAR -> FruitPalette(Color(0xFFF2F291), Color(0xFFC9CF42), Color(0xFF8C9420), Color(0xFF8B5A2B))
    FruitTier.PEACH -> FruitPalette(Color(0xFFFFCFD2), Color(0xFFFF93A2), Color(0xFFD95B72), Color(0xFF57A857))
    FruitTier.PINEAPPLE -> FruitPalette(Color(0xFFFFE57F), Color(0xFFF0B92E), Color(0xFFB87F12), Color(0xFF479E4C))
    FruitTier.MELON -> FruitPalette(Color(0xFFC8F296), Color(0xFF84C953), Color(0xFF4F8F2B), Color(0xFFEAF7D4))
    FruitTier.WATERMELON -> FruitPalette(Color(0xFF66D18B), Color(0xFF289653), Color(0xFF135C31), Color(0xFF0E4524))
}

private fun DrawScope.drawFruit(
    tier: FruitTier,
    center: Offset,
    radius: Float,
    angleRad: Float,
    outlineColor: Color,
    alpha: Float = 1f,
    highlightRing: Boolean = false,
    simplified: Boolean = false
) {
    if (radius <= 0.5f) return
    val p = fruitPalette(tier)
    val deg = (angleRad * 180.0 / PI).toFloat()

    if (highlightRing) {
        drawCircle(
            color = outlineColor.copy(alpha = 0.25f * alpha),
            radius = radius + 4.dp.toPx(),
            center = center
        )
    }

    rotate(degrees = deg, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to p.highlight.copy(alpha = alpha),
                    0.58f to p.core.copy(alpha = alpha),
                    1.0f to p.rim.copy(alpha = alpha)
                ),
                center = Offset(center.x - radius * 0.28f, center.y - radius * 0.30f),
                radius = radius * 1.38f
            ),
            radius = radius,
            center = center
        )

        when (tier) {
            FruitTier.CHERRY -> {
                val stemPath = Path().apply {
                    moveTo(center.x, center.y - radius * 0.72f)
                    quadraticTo(
                        center.x + radius * 0.28f,
                        center.y - radius * 1.15f,
                        center.x + radius * 0.52f,
                        center.y - radius * 0.96f
                    )
                }
                drawPath(stemPath, p.detail.copy(alpha = alpha), style = Stroke(max(2f, radius * 0.14f), cap = StrokeCap.Round))
            }

            FruitTier.STRAWBERRY -> {
                val leaf = Path().apply {
                    moveTo(center.x - radius * 0.42f, center.y - radius * 0.65f)
                    lineTo(center.x, center.y - radius * 0.96f)
                    lineTo(center.x + radius * 0.42f, center.y - radius * 0.65f)
                    lineTo(center.x, center.y - radius * 0.55f)
                    close()
                }
                drawPath(leaf, Color(0xFF4CAF50).copy(alpha = alpha))
                if (!simplified) {
                    val seeds = listOf(
                        Offset(-0.36f, -0.10f), Offset(0.36f, -0.10f),
                        Offset(-0.22f, 0.36f), Offset(0.22f, 0.36f), Offset(0f, 0.54f)
                    )
                    seeds.forEach { s ->
                        drawCircle(
                            color = p.detail.copy(alpha = 0.85f * alpha),
                            radius = max(1.4f, radius * 0.065f),
                            center = Offset(center.x + s.x * radius, center.y + s.y * radius)
                        )
                    }
                }
            }

            FruitTier.GRAPE -> {
                if (!simplified) {
                    drawCircle(
                        color = p.highlight.copy(alpha = 0.32f * alpha),
                        radius = radius * 0.34f,
                        center = Offset(center.x - radius * 0.26f, center.y - radius * 0.24f)
                    )
                    drawCircle(
                        color = p.highlight.copy(alpha = 0.22f * alpha),
                        radius = radius * 0.28f,
                        center = Offset(center.x + radius * 0.28f, center.y - radius * 0.16f)
                    )
                }
                drawOval(
                    color = p.detail.copy(alpha = alpha),
                    topLeft = Offset(center.x + radius * 0.05f, center.y - radius * 0.95f),
                    size = Size(radius * 0.44f, radius * 0.24f)
                )
            }

            FruitTier.TANGERINE -> {
                drawCircle(
                    color = p.highlight.copy(alpha = alpha),
                    radius = radius * 0.20f,
                    center = Offset(center.x, center.y - radius * 0.86f)
                )
                drawOval(
                    color = p.detail.copy(alpha = alpha),
                    topLeft = Offset(center.x + radius * 0.08f, center.y - radius * 0.98f),
                    size = Size(radius * 0.42f, radius * 0.22f)
                )
            }

            FruitTier.ORANGE -> {
                drawCircle(
                    color = p.rim.copy(alpha = 0.7f * alpha),
                    radius = max(2f, radius * 0.09f),
                    center = Offset(center.x, center.y - radius * 0.76f)
                )
                if (!simplified) {
                    val pores = listOf(Offset(-0.46f, 0.22f), Offset(0.44f, 0.28f), Offset(-0.34f, -0.34f))
                    pores.forEach { pt ->
                        drawCircle(
                            color = p.detail.copy(alpha = 0.45f * alpha),
                            radius = max(1.5f, radius * 0.045f),
                            center = Offset(center.x + pt.x * radius, center.y + pt.y * radius)
                        )
                    }
                }
            }

            FruitTier.APPLE -> {
                drawLine(
                    color = Color(0xFF6B3F1D).copy(alpha = alpha),
                    start = Offset(center.x, center.y - radius * 0.68f),
                    end = Offset(center.x + radius * 0.06f, center.y - radius * 0.98f),
                    strokeWidth = max(2.2f, radius * 0.09f),
                    cap = StrokeCap.Round
                )
                drawOval(
                    color = p.detail.copy(alpha = alpha),
                    topLeft = Offset(center.x + radius * 0.06f, center.y - radius * 0.96f),
                    size = Size(radius * 0.42f, radius * 0.22f)
                )
            }

            FruitTier.PEAR -> {
                drawLine(
                    color = p.detail.copy(alpha = alpha),
                    start = Offset(center.x, center.y - radius * 0.72f),
                    end = Offset(center.x - radius * 0.08f, center.y - radius * 0.98f),
                    strokeWidth = max(2.2f, radius * 0.08f),
                    cap = StrokeCap.Round
                )
                if (!simplified) {
                    drawCircle(
                        color = Color(0xFFFFAB91).copy(alpha = 0.28f * alpha),
                        radius = radius * 0.28f,
                        center = Offset(center.x + radius * 0.34f, center.y + radius * 0.22f)
                    )
                }
            }

            FruitTier.PEACH -> {
                val cleft = Path().apply {
                    moveTo(center.x, center.y - radius * 0.88f)
                    quadraticTo(
                        center.x + radius * 0.22f,
                        center.y - radius * 0.15f,
                        center.x,
                        center.y + radius * 0.84f
                    )
                }
                drawPath(cleft, p.rim.copy(alpha = 0.32f * alpha), style = Stroke(max(2f, radius * 0.05f), cap = StrokeCap.Round))
                drawOval(
                    color = p.detail.copy(alpha = alpha),
                    topLeft = Offset(center.x + radius * 0.05f, center.y - radius * 0.98f),
                    size = Size(radius * 0.40f, radius * 0.20f)
                )
            }

            FruitTier.PINEAPPLE -> {
                if (!simplified) {
                    val lineCol = p.rim.copy(alpha = 0.34f * alpha)
                    val sw = max(1.6f, radius * 0.035f)
                    for (offset in listOf(-0.42f, 0f, 0.42f)) {
                        drawLine(
                            lineCol,
                            Offset(center.x + (offset - 0.36f) * radius, center.y - 0.45f * radius),
                            Offset(center.x + (offset + 0.36f) * radius, center.y + 0.45f * radius),
                            sw
                        )
                        drawLine(
                            lineCol,
                            Offset(center.x + (offset + 0.36f) * radius, center.y - 0.45f * radius),
                            Offset(center.x + (offset - 0.36f) * radius, center.y + 0.45f * radius),
                            sw
                        )
                    }
                }
                val crown = Path().apply {
                    moveTo(center.x - radius * 0.34f, center.y - radius * 0.72f)
                    lineTo(center.x - radius * 0.18f, center.y - radius * 1.02f)
                    lineTo(center.x, center.y - radius * 0.82f)
                    lineTo(center.x + radius * 0.18f, center.y - radius * 1.02f)
                    lineTo(center.x + radius * 0.34f, center.y - radius * 0.72f)
                    close()
                }
                drawPath(crown, p.detail.copy(alpha = alpha))
            }

            FruitTier.MELON -> {
                if (!simplified) {
                    val netCol = p.detail.copy(alpha = 0.45f * alpha)
                    val sw = max(1.8f, radius * 0.03f)
                    for (k in listOf(-0.48f, 0f, 0.48f)) {
                        val arcPath = Path().apply {
                            moveTo(center.x + k * radius * 0.4f, center.y - radius * 0.82f)
                            quadraticTo(
                                center.x + k * radius * 1.15f,
                                center.y,
                                center.x + k * radius * 0.4f,
                                center.y + radius * 0.82f
                            )
                        }
                        drawPath(arcPath, netCol, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
            }

            FruitTier.WATERMELON -> {
                val stripeCol = p.detail.copy(alpha = 0.78f * alpha)
                val sw = max(2.5f, radius * 0.085f)
                for (k in listOf(-0.56f, -0.20f, 0.20f, 0.56f)) {
                    val stripe = Path().apply {
                        moveTo(center.x + k * radius * 0.36f, center.y - radius * 0.84f)
                        quadraticTo(
                            center.x + k * radius * 1.15f,
                            center.y,
                            center.x + k * radius * 0.36f,
                            center.y + radius * 0.84f
                        )
                    }
                    drawPath(stripe, stripeCol, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
        }

        // Glossy top-left specular highlight
        drawOval(
            color = Color.White.copy(alpha = 0.34f * alpha),
            topLeft = Offset(center.x - radius * 0.52f, center.y - radius * 0.58f),
            size = Size(radius * 0.40f, radius * 0.22f)
        )

        // Subtle cheerful face on full-size fruits
        if (!simplified && radius >= 13f) {
            val eyeColor = Color(0xFF2B211C).copy(alpha = 0.78f * alpha)
            val eyeY = center.y + radius * 0.04f
            val eyeSpread = radius * 0.25f
            val eyeR = max(1.7f, radius * 0.072f)
            drawCircle(eyeColor, eyeR, Offset(center.x - eyeSpread, eyeY))
            drawCircle(eyeColor, eyeR, Offset(center.x + eyeSpread, eyeY))
            drawCircle(Color.White.copy(alpha = 0.85f * alpha), eyeR * 0.36f, Offset(center.x - eyeSpread - eyeR * 0.24f, eyeY - eyeR * 0.24f))
            drawCircle(Color.White.copy(alpha = 0.85f * alpha), eyeR * 0.36f, Offset(center.x + eyeSpread - eyeR * 0.24f, eyeY - eyeR * 0.24f))

            val smile = Path().apply {
                moveTo(center.x - radius * 0.14f, center.y + radius * 0.18f)
                quadraticTo(
                    center.x,
                    center.y + radius * 0.30f,
                    center.x + radius * 0.14f,
                    center.y + radius * 0.18f
                )
            }
            drawPath(smile, eyeColor, style = Stroke(max(1.5f, radius * 0.048f), cap = StrokeCap.Round))
        }

        drawCircle(
            color = outlineColor.copy(alpha = outlineColor.alpha * alpha),
            radius = radius,
            center = center,
            style = Stroke(width = max(1.5f, radius * 0.045f))
        )
    }
}

private class WatermelonSoundPlayer {
    private var track: AudioTrack? = null

    fun playDrop() {
        playTone(startHz = 320.0, endHz = 210.0, durationMs = 90, gain = 0.11)
    }

    fun playMerge(tierLevel: Int) {
        val pentatonic = doubleArrayOf(
            392.00, 440.00, 493.88, 587.33, 659.25,
            783.99, 880.00, 987.77, 1174.66, 1318.51, 1567.98, 1760.00
        )
        val base = pentatonic[tierLevel.coerceIn(0, pentatonic.lastIndex)]
        val duration = if (tierLevel >= FruitTier.WATERMELON.level) 280 else 160
        playTone(startHz = base * 0.92, endHz = base * 1.12, durationMs = duration, gain = 0.14)
    }

    fun release() {
        track?.release()
        track = null
    }

    private fun playTone(startHz: Double, endHz: Double, durationMs: Int, gain: Double) {
        release()
        val rate = 24000
        val samples = (rate * durationMs) / 1000
        var phase = 0.0
        val pcm = ShortArray(samples) { i ->
            val u = i.toDouble() / samples
            val freq = startHz + (endHz - startHz) * u
            phase += 2.0 * PI * freq / rate
            val env = if (u < 0.08) u / 0.08 else (1.0 - (u - 0.08) / 0.92).pow(1.6)
            val wave = sin(phase) * 0.78 + sin(phase * 2.0) * 0.22
            (Short.MAX_VALUE * gain * env * wave).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        try {
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track = built
            if (built.write(pcm, 0, pcm.size) > 0) built.play()
        } catch (failure: RuntimeException) {
            release()
            android.util.Log.w("WatermelonSound", "Audio unavailable", failure)
        }
    }
}
