package com.example.input_ds.ui.home

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentCatalog
import com.example.input_ds.model.EntertainmentCategory
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.EntertainmentMarketPreset
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.settings.EntertainmentMarketInputBus
import com.example.input_ds.settings.EntertainmentMarketSignalSink
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors

private sealed interface MarketScanItem {
    data object Back : MarketScanItem
    data class Feature(val module: EntertainmentHubModule) : MarketScanItem
}

@Composable
fun EntertainmentMarketScreen(
    enabledIds: Set<String>,
    returnLabel: String,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    paused: Boolean,
    frozen: Boolean,
    onToggleModule: (EntertainmentHubModule) -> Unit,
    onApplyPreset: (EntertainmentMarketPreset) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val mediaModules = remember { EntertainmentHubModule.modulesIn(EntertainmentCategory.MEDIA) }
    val boardModules = remember { EntertainmentHubModule.modulesIn(EntertainmentCategory.BOARD) }
    val gameModules = remember { EntertainmentHubModule.modulesIn(EntertainmentCategory.GAMES) }
    val scanItems = remember(mediaModules, boardModules, gameModules) {
        buildList<MarketScanItem> {
            add(MarketScanItem.Back)
            mediaModules.forEach { add(MarketScanItem.Feature(it)) }
            boardModules.forEach { add(MarketScanItem.Feature(it)) }
            gameModules.forEach { add(MarketScanItem.Feature(it)) }
        }
    }
    val indexByModule = remember(scanItems) {
        scanItems.mapIndexedNotNull { idx, item ->
            (item as? MarketScanItem.Feature)?.module?.let { it to idx }
        }.toMap()
    }

    val currentOnToggle by rememberUpdatedState(onToggleModule)
    val currentOnBack by rememberUpdatedState(onBack)

    var cursor by remember {
        mutableStateOf(
            ScanCursor(
                size = scanItems.size,
                index = 1.coerceIn(scanItems.indices),
                direction = 1
            )
        )
    }
    var dwellRevision by remember { mutableIntStateOf(0) }

    val focusedItem = scanItems[cursor.index.coerceIn(scanItems.indices)]
    val biteHint = when (focusedItem) {
        MarketScanItem.Back -> "选「$returnLabel」"
        is MarketScanItem.Feature -> {
            if (focusedItem.module.id in enabledIds) "移除「${focusedItem.module.displayName}」"
            else "添加「${focusedItem.module.displayName}」"
        }
    }
    val actionHints = if (scanMode == ScanMode.MANUAL) {
        ActionHints("上一项", biteHint, "下一项")
    } else {
        ActionHints("向左轮转", biteHint, "向右轮转", cursor.direction)
    }
    ReportActionHints(actionHints)

    fun choose(index: Int) {
        val item = scanItems.getOrNull(index) ?: return
        cursor = cursor.copy(index = index)
        when (item) {
            MarketScanItem.Back -> currentOnBack()
            is MarketScanItem.Feature -> {
                currentOnToggle(item.module)
                dwellRevision++
            }
        }
    }

    val signalHandler by rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        val now = SystemClock.elapsedRealtime()
        val grace = if (frozen) 0L else AppUiSettings.graceMs(context)
        when (signal) {
            ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                cursor = cursor.look(step, scanMode)
                dwellRevision++
            }
            ControlSignal.BITE -> {
                val chosen = cursor.selected(now, grace, scanMode)
                choose(chosen)
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }
    val tickHandler by rememberUpdatedState<() -> Unit> {
        cursor = cursor.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
    }
    val resetFocusHandler by rememberUpdatedState<(Int) -> Unit> { targetIndex ->
        cursor = ScanCursor(size = scanItems.size, index = targetIndex.coerceIn(scanItems.indices), direction = 1)
        dwellRevision++
    }
    val signalSink = remember { EntertainmentMarketSignalSink { signalHandler(it) } }

    DisposableEffect(signalSink) {
        EntertainmentMarketInputBus.attach(
            candidate = signalSink,
            onTick = { tickHandler() },
            onResetFocus = { resetFocusHandler(it) }
        )
        onDispose { EntertainmentMarketInputBus.detach(signalSink) }
    }

    LaunchedEffect(paused) {
        cursor = cursor.reset()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(paused, frozen, scanMode, dwellRevision, scanIntervalMs, lifecycleOwner) {
        if (!paused && !frozen && scanMode == ScanMode.AUTO) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                cursor = cursor.copy(previousIndex = -1, advancedAtMs = 0L)
                runScanTimer(
                    interval = { scanIntervalMs },
                    onFailure = { android.util.Log.e("ScanCursor", "Market tick failed", it) }
                ) {
                    cursor = cursor.advance(SystemClock.elapsedRealtime(), ScanMode.AUTO)
                }
            }
        }
    }

    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(cursor.index, cursor.direction, dwellRevision, scanIntervalMs, scanMode, paused, frozen, lifecycleOwner) {
        when {
            frozen -> dwellProgress.snapTo(if (scanMode == ScanMode.AUTO) 0.5f else 0f)
            paused || scanMode != ScanMode.AUTO -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    val c = LocalBciColors.current
    val dir = if (scanMode == ScanMode.AUTO) cursor.direction else 0
    val progressProvider: () -> Float? = { if (scanMode == ScanMode.AUTO) dwellProgress.value else null }
    val totalCount = EntertainmentCatalog.orderedSubFeatures.size
    val enabledCount = enabledIds.intersect(EntertainmentHubModule.allSubFeatureIds).size
    val cssLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header bar: title, enabled count, and quick presets for caregiver touch setup.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "功能市场",
                    modifier = Modifier.alignByBaseline(),
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 32.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "已启用 $enabledCount / $totalCount",
                    modifier = Modifier.alignByBaseline(),
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntertainmentMarketPreset.entries.forEach { preset ->
                    val active = enabledIds == preset.enabledIds
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) c.focus else Color.Transparent, CircleShape)
                            .border(1.dp, if (active) c.focus else c.line, CircleShape)
                            .clickable(enabled = !paused) {
                                onApplyPreset(preset)
                                dwellRevision++
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.label,
                            color = if (active) c.bg else c.muted,
                            fontFamily = BciType.chinese,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 16.sp,
                            style = TextStyle(
                                lineHeightStyle = cssLineBox,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            }
        }

        // Row 1: Back + 视听资讯 (3)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MarketCategoryTag(EntertainmentCategory.MEDIA.displayName)
            MarketBackCard(
                label = returnLabel,
                focused = cursor.index == 0,
                direction = dir,
                progress = if (cursor.index == 0) progressProvider else null,
                enabled = !paused,
                onClick = { choose(0) },
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight()
            )
            mediaModules.forEach { module ->
                val idx = indexByModule.getValue(module)
                MarketFeatureCard(
                    module = module,
                    isAdded = module.id in enabledIds,
                    focused = cursor.index == idx,
                    direction = dir,
                    progress = if (cursor.index == idx) progressProvider else null,
                    enabled = !paused,
                    onClick = { choose(idx) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // Row 2: 棋牌 (3)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MarketCategoryTag(EntertainmentCategory.BOARD.displayName)
            boardModules.forEach { module ->
                val idx = indexByModule.getValue(module)
                MarketFeatureCard(
                    module = module,
                    isAdded = module.id in enabledIds,
                    focused = cursor.index == idx,
                    direction = dir,
                    progress = if (cursor.index == idx) progressProvider else null,
                    enabled = !paused,
                    onClick = { choose(idx) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // Row 3: 游戏 (4)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MarketCategoryTag(EntertainmentCategory.GAMES.displayName)
            gameModules.forEach { module ->
                val idx = indexByModule.getValue(module)
                MarketFeatureCard(
                    module = module,
                    isAdded = module.id in enabledIds,
                    focused = cursor.index == idx,
                    direction = dir,
                    progress = if (cursor.index == idx) progressProvider else null,
                    enabled = !paused,
                    onClick = { choose(idx) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun MarketCategoryTag(label: String) {
    val c = LocalBciColors.current
    Box(
        modifier = Modifier.width(88.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun MarketBackCard(
    label: String,
    focused: Boolean,
    direction: Int,
    progress: (() -> Float?)?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    Column(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = progress,
                navigation = true,
                showDirectionTag = false
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_arrow_left),
            contentDescription = null,
            tint = if (focused) c.focus else c.muted,
            modifier = Modifier.size(30.dp)
        )
        Text(
            text = label,
            color = c.text,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun MarketFeatureCard(
    module: EntertainmentHubModule,
    isAdded: Boolean,
    focused: Boolean,
    direction: Int,
    progress: (() -> Float?)?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    val context = LocalContext.current
    val thumb = remember(module.id) {
        runCatching {
            context.assets.open("entertainment/${module.id}.webp").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
    val contentAlpha = if (isAdded || focused) 1f else 0.56f
    val accentColor = Color.hsl(module.hue, 0.42f, 0.42f)

    Column(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = progress,
                navigation = false,
                showDirectionTag = false
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(contentAlpha)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isAdded) c.ok else Color.Transparent, CircleShape)
                    .border(1.dp, if (isAdded) c.ok else c.line, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isAdded) "已启用" else "未启用",
                    color = if (isAdded) Color.White else c.muted,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
        Text(
            text = module.displayName,
            color = c.text.copy(alpha = contentAlpha),
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}
