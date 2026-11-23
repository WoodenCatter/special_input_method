package com.example.input_ds.ui.home

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentHubSelectionState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import com.example.input_ds.ui.tv.TV_CHANNELS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

private val GalleryEasing = CubicBezierEasing(0.2f, 0.75f, 0.2f, 1f)

@Composable
fun EntertainmentScreen(
    selection: EntertainmentHubSelectionState,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    liveMusicCount: Int,
    onSelect: (EntertainmentHubModule) -> Unit
) {
    val c = LocalBciColors.current
    val modules = EntertainmentHubModule.entries
    val n = modules.size
    val selectedIndex = selection.selectedIndex.coerceIn(0, n - 1)
    val dir = if (selection.scanDirection < 0) -1 else 1
    val nextIndex = (selectedIndex + dir + n) % n
    val playableTvCount = remember { TV_CHANNELS.count { !it.temporarilyUnavailable } }

    val lifecycleOwner = LocalLifecycleOwner.current
    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(selectedIndex, dir, dwellRevision, scanIntervalMs, scanMode, paused, frozen, lifecycleOwner) {
        when {
            frozen -> dwellProgress.snapTo(if (scanMode == ScanMode.AUTO) 0.5f else 0f)
            paused || scanMode != ScanMode.AUTO -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    val cssLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header bar (40 dp).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "娱乐",
                modifier = Modifier.alignByBaseline(),
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 34.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(Modifier.width(18.dp))
            val posText = if (selectedIndex == 0) "— / ${n - 1}" else "$selectedIndex / ${n - 1}"
            Text(
                text = posText,
                modifier = Modifier.alignByBaseline(),
                color = c.muted,
                fontFamily = BciType.latin,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "当前项永远在中间 · 看向左右改变滑动方向",
                modifier = Modifier.alignByBaseline(),
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 18.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        // 2. Horizontal gallery stage extending edge-to-edge across the 28 dp scaffold padding.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val fullWidth = maxWidth + 56.dp
            val stageHeight = maxHeight
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(unbounded = true, align = Alignment.Center)
                    .size(fullWidth, stageHeight)
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                modules.forEachIndexed { i, module ->
                    key(module) {
                        var d = ((i - selectedIndex) % n + n) % n
                        if (d > n / 2) d -= n
                        val ad = abs(d)
                        val sg = d.toFloat().sign
                        val isNext = d == dir
                        val targetScale = when {
                            d == 0 -> 1f
                            ad == 1 && isNext -> 0.60f
                            ad == 1 -> 0.50f
                            else -> 0.40f
                        }
                        val targetX = when {
                            d == 0 -> 0f
                            ad == 1 -> sg * (350f + 350f * targetScale + 32f)
                            else -> sg * 1100f
                        }
                        val targetAlpha = when {
                            d == 0 -> 1f
                            ad == 1 && isNext -> 1f
                            ad == 1 -> 0.60f
                            else -> 0f
                        }
                        val resolvedSubtitle = when {
                            frozen -> module.subtitle
                            module == EntertainmentHubModule.TELEVISION -> "CCTV 直播 $playableTvCount 个频道"
                            module == EntertainmentHubModule.MUSIC -> "本地 $liveMusicCount 首"
                            else -> module.subtitle
                        }

                        GalleryCard(
                            module = module,
                            subtitle = resolvedSubtitle,
                            d = d,
                            zOrder = (10 - ad).toFloat(),
                            isCenter = d == 0,
                            isNext = isNext,
                            targetX = targetX,
                            targetScale = targetScale,
                            targetAlpha = targetAlpha,
                            scanDirection = dir,
                            showDwellBar = d == 0 && scanMode == ScanMode.AUTO,
                            dwellProgress = { dwellProgress.value },
                            frozen = frozen,
                            interactive = targetAlpha > 0.05f,
                            onClick = { onSelect(module) }
                        )
                    }
                }

                // 60 dp left/right vignette matching CSS linear-gradient mask.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(60.dp)
                        .fillMaxHeight()
                        .zIndex(15f)
                        .background(Brush.horizontalGradient(listOf(c.bg, Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(60.dp)
                        .fillMaxHeight()
                        .zIndex(15f)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, c.bg)))
                )

                // Floating next direction indicator circle (.gnext).
                val gnextX by animateDpAsState(
                    targetValue = (dir * 366).dp,
                    animationSpec = tween(if (frozen) 0 else 300, easing = GalleryEasing),
                    label = "gnext"
                )
                Box(
                    modifier = Modifier
                        .offset(x = gnextX)
                        .zIndex(20f)
                        .size(72.dp)
                        .drawBehind {
                            drawCircle(
                                color = c.bg,
                                radius = size.minDimension / 2f + 6.dp.toPx()
                            )
                        }
                        .background(c.focus, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(width = 18.dp, height = 21.dp)) {
                        val right = dir > 0
                        val edge = if (right) 0f else size.width
                        val tip = if (right) size.width else 0f
                        drawPath(
                            Path().apply {
                                moveTo(edge, 0f)
                                lineTo(tip, size.height / 2f)
                                lineTo(edge, size.height)
                                close()
                            },
                            color = c.bg
                        )
                    }
                }
            }
        }

        // 3. Bottom name rail (50 dp).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modules.forEachIndexed { idx, module ->
                val isCurrent = idx == selectedIndex
                val isNext = idx == nextIndex
                val pillFill = if (isCurrent) c.focus else Color.Transparent
                val pillBorder = when {
                    isCurrent -> c.focus
                    isNext -> c.focus
                    else -> c.line
                }
                val textColor = when {
                    isCurrent -> c.bg
                    isNext -> c.text
                    else -> c.muted
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pillFill, CircleShape)
                        .border(1.dp, pillBorder, CircleShape)
                        .clickable { onSelect(module) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = module.displayName,
                        color = textColor,
                        fontFamily = BciType.chinese,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        maxLines = 1,
                        style = TextStyle(
                            lineHeightStyle = cssLineBox,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(
    module: EntertainmentHubModule,
    subtitle: String,
    d: Int,
    zOrder: Float,
    isCenter: Boolean,
    isNext: Boolean,
    targetX: Float,
    targetScale: Float,
    targetAlpha: Float,
    scanDirection: Int,
    showDwellBar: Boolean,
    dwellProgress: () -> Float,
    frozen: Boolean,
    interactive: Boolean,
    onClick: () -> Unit
) {
    val c = LocalBciColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetXPx = with(density) { targetX.dp.toPx() }

    val xAnim = remember { Animatable(targetXPx) }
    val scaleAnim = remember { Animatable(targetScale) }
    val alphaAnim = remember { Animatable(targetAlpha) }
    var prevD by remember { mutableIntStateOf(d) }

    LaunchedEffect(d, targetXPx, targetScale, targetAlpha, frozen) {
        val jump = abs(d - prevD) > 2
        prevD = d
        if (frozen || jump) {
            xAnim.snapTo(targetXPx)
            scaleAnim.snapTo(targetScale)
            alphaAnim.snapTo(targetAlpha)
        } else {
            xAnim.animateTo(targetXPx, tween(500, easing = GalleryEasing))
        }
    }
    LaunchedEffect(d, targetScale, frozen) {
        val jump = abs(d - prevD) > 2
        if (!frozen && !jump) {
            scaleAnim.animateTo(targetScale, tween(500, easing = GalleryEasing))
        }
    }
    LaunchedEffect(d, targetAlpha, frozen) {
        val jump = abs(d - prevD) > 2
        if (!frozen && !jump) {
            alphaAnim.animateTo(targetAlpha, tween(350))
        }
    }

    if (alphaAnim.value <= 0.01f && abs(d) >= 2) return

    val cardBitmap = remember(module.id) {
        runCatching {
            context.assets.open("entertainment/${module.id}.webp").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    val shape = RoundedCornerShape(26.dp)
    val sat = if (module.isNavigation) 0.06f else 0.40f
    val topColor = Color.hsl(module.hue, sat, 0.50f)
    val botColor = Color.hsl(module.hue, (sat + 0.06f).coerceAtMost(1f), 0.21f)
    val artRes = galleryArtRes(module)

    Box(
        modifier = Modifier
            .zIndex(zOrder)
            .size(width = 700.dp, height = 438.dp)
            .graphicsLayer {
                translationX = xAnim.value
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                alpha = alphaAnim.value
            }
            .drawBehind {
                val baseRadius = 26.dp.toPx()
                if (isCenter) {
                    val outer = 10.dp.toPx()
                    val inner = 5.dp.toPx()
                    drawRoundRect(
                        color = c.focus,
                        topLeft = Offset(-outer, -outer),
                        size = Size(size.width + 2f * outer, size.height + 2f * outer),
                        cornerRadius = CornerRadius(baseRadius + outer)
                    )
                    drawRoundRect(
                        color = c.bg,
                        topLeft = Offset(-inner, -inner),
                        size = Size(size.width + 2f * inner, size.height + 2f * inner),
                        cornerRadius = CornerRadius(baseRadius + inner)
                    )
                } else if (isNext) {
                    val outer = 8.dp.toPx()
                    val inner = 4.dp.toPx()
                    drawRoundRect(
                        color = c.focusRing,
                        topLeft = Offset(-outer, -outer),
                        size = Size(size.width + 2f * outer, size.height + 2f * outer),
                        cornerRadius = CornerRadius(baseRadius + outer)
                    )
                    drawRoundRect(
                        color = c.bg,
                        topLeft = Offset(-inner, -inner),
                        size = Size(size.width + 2f * inner, size.height + 2f * inner),
                        cornerRadius = CornerRadius(baseRadius + inner)
                    )
                }
            }
            .shadow(if (isCenter) 24.dp else 10.dp, shape, clip = false)
            .clip(shape)
            .drawBehind {
                // Exact 155 deg CSS linear-gradient line through box center.
                val rad = 155.0 * PI / 180.0
                val ux = sin(rad).toFloat()
                val uy = (-cos(rad)).toFloat()
                val halfLen = (abs(size.width * ux) + abs(size.height * uy)) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(topColor, botColor),
                        start = Offset(cx - ux * halfLen, cy - uy * halfLen),
                        end = Offset(cx + ux * halfLen, cy + uy * halfLen)
                    )
                )
            }
            .clickable(enabled = interactive, onClick = onClick)
    ) {
        // Vector silhouette art layer.
        Icon(
            painter = painterResource(artRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.26f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 26.dp)
                .size(width = 462.dp, height = 288.75.dp)
        )

        if (cardBitmap != null) {
            Image(
                bitmap = cardBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom contrast scrim matching linear-gradient(to top, rgba(6,8,12,.86) 0%, .55 34%, 0 62%).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.38f to Color.Transparent,
                        0.66f to Color(0x8C06080C),
                        1.0f to Color(0xDB06080C)
                    )
                )
        )

        if (isNext) {
            val tagAlign = if (d > 0) Alignment.TopStart else Alignment.TopEnd
            val tagStart = if (d > 0) 28.dp else 0.dp
            val tagEnd = if (d < 0) 28.dp else 0.dp
            Row(
                modifier = Modifier
                    .align(tagAlign)
                    .padding(top = 28.dp, start = tagStart, end = tagEnd)
                    .background(c.focus, CircleShape)
                    .padding(horizontal = 26.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = buildAnnotatedString {
                        val raw = if (d > 0) "下一个 ▶" else "◀ 下一个"
                        for (ch in raw) {
                            withStyle(SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)) {
                                append(ch)
                            }
                        }
                    },
                    color = c.bg,
                    fontWeight = FontWeight(800),
                    fontSize = 38.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 30.dp, end = 30.dp, bottom = 30.dp)
        ) {
            Text(
                text = module.displayName,
                color = Color.White,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 54.sp,
                lineHeight = 59.sp,
                style = TextStyle(
                    shadow = Shadow(Color(0x40000000), Offset(0f, 1f), 12f),
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildAnnotatedString {
                    for (ch in subtitle) {
                        withStyle(SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)) {
                            append(ch)
                        }
                    }
                },
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 24.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        if (showDwellBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 30.dp, end = 30.dp, bottom = 14.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .drawBehind {
                        val frac = dwellProgress().coerceIn(0f, 1f)
                        val w = size.width * frac
                        val x = if (scanDirection < 0) size.width - w else 0f
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(x, 0f),
                            size = Size(w, size.height),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                    }
            )
        }
    }
}

private fun galleryArtRes(module: EntertainmentHubModule): Int = when (module) {
    EntertainmentHubModule.BACK -> R.drawable.gallery_art_back
    EntertainmentHubModule.MAZE -> R.drawable.gallery_art_maze
    EntertainmentHubModule.SNAKE_CLIMB -> R.drawable.gallery_art_snake
    EntertainmentHubModule.WIZARD_GAME -> R.drawable.gallery_art_wizard
    EntertainmentHubModule.CHINESE_CHESS -> R.drawable.gallery_art_chess
    EntertainmentHubModule.DOUDIZHU -> R.drawable.gallery_art_ddz
    EntertainmentHubModule.MAHJONG -> R.drawable.gallery_art_mj
    EntertainmentHubModule.TELEVISION -> R.drawable.gallery_art_tv
    EntertainmentHubModule.MUSIC -> R.drawable.gallery_art_music
}
