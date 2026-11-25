package com.example.input_ds.ui.settings

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentHubModule
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.settings.AppSettingsInputBus
import com.example.input_ds.settings.AppSettingsSignalSink
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.AdjustOverlayKind
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import java.util.Locale
import kotlin.math.roundToInt

internal enum class AppSettingItem(
    val label: String,
    @param:DrawableRes val iconRes: Int,
    val description: String,
    val isNavigation: Boolean = false
) {
    BACK("返回主页", R.drawable.ic_lucide_arrow_left, "", isNavigation = true),
    VOLUME("音量", R.drawable.ic_lucide_volume_2, "朗读、通话与提示音音量"),
    BRIGHTNESS("屏幕亮度", R.drawable.ic_lucide_sun, "当前界面显示亮度"),
    SPEED("每项停留", R.drawable.ic_lucide_gauge, "自动扫描停留时长（1.1–3.0 秒）"),
    SCAN_MODE("扫描方式", R.drawable.ic_lucide_arrow_left_right, "自动轮转，或左右手动切换"),
    DISPLAY_MODE("显示模式", R.drawable.ic_lucide_sun_moon, "自动模式下 7:00–19:00 为日间"),
    FLOATING_BALL("模拟悬浮球", R.drawable.ic_lucide_circle_dot, "屏幕悬浮控制按钮"),
    SPEED_PRACTICE("速度练习", R.drawable.ic_lucide_target, "12 次目标选择，测算推荐停留时长"),
    ENTERTAINMENT_MARKET("功能市场", R.drawable.ic_lucide_gamepad_2, "添加或移除娱乐板块中的子功能")
}

internal object AppSettingsLogic {
    const val INITIAL_INDEX = 1

    fun cycleDisplayMode(current: AppUiSettings.DisplayMode): AppUiSettings.DisplayMode = when (current) {
        AppUiSettings.DisplayMode.AUTO -> AppUiSettings.DisplayMode.LIGHT
        AppUiSettings.DisplayMode.LIGHT -> AppUiSettings.DisplayMode.DARK
        AppUiSettings.DisplayMode.DARK -> AppUiSettings.DisplayMode.AUTO
    }

    fun displayModeLabel(mode: AppUiSettings.DisplayMode): String = when (mode) {
        AppUiSettings.DisplayMode.AUTO -> "自动"
        AppUiSettings.DisplayMode.LIGHT -> "日间"
        AppUiSettings.DisplayMode.DARK -> "夜间"
    }

    fun stepVolumePercent(percent: Int, delta: Int): Int =
        (percent + delta * 10).coerceIn(0, 100)

    fun streamLevelToPercent(level: Int, maxLevel: Int): Int {
        val safeMax = maxLevel.coerceAtLeast(1)
        return (level.coerceIn(0, safeMax) * 100f / safeMax).roundToInt().coerceIn(0, 100)
    }

    fun nextStreamVolumeLevel(currentLevel: Int, maxLevel: Int, delta: Int): Int {
        val safeMax = maxLevel.coerceAtLeast(1)
        val clampedCurrent = currentLevel.coerceIn(0, safeMax)
        if (delta == 0) return clampedCurrent
        val targetPercent = stepVolumePercent(streamLevelToPercent(clampedCurrent, safeMax), delta)
        val mapped = (targetPercent / 100f * safeMax).roundToInt().coerceIn(0, safeMax)
        return when {
            delta > 0 && clampedCurrent < safeMax && mapped <= clampedCurrent -> clampedCurrent + 1
            delta < 0 && clampedCurrent > 0 && mapped >= clampedCurrent -> clampedCurrent - 1
            else -> mapped
        }
    }

    fun stepBrightnessPercent(percent: Int, delta: Int): Int =
        (percent + delta * 10).coerceIn(10, 100)

    fun stepScanIntervalMs(intervalMs: Long, delta: Int): Long =
        (intervalMs + delta * 100L).coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)

    fun adjustFraction(
        kind: AdjustOverlayKind,
        volPercent: Int,
        briPercent: Int,
        intervalMs: Long
    ): Float = when (kind) {
        AdjustOverlayKind.VOLUME -> volPercent.coerceIn(0, 100) / 100f
        AdjustOverlayKind.BRIGHTNESS -> briPercent.coerceIn(0, 100) / 100f
        AdjustOverlayKind.SPEED -> ((intervalMs - ScanSettings.MIN_INTERVAL_MS).toFloat() /
            (ScanSettings.MAX_INTERVAL_MS - ScanSettings.MIN_INTERVAL_MS).toFloat()).coerceIn(0f, 1f)
    }

    fun formatScanInterval(intervalMs: Long): String =
        String.format(Locale.ROOT, "%.1f 秒", intervalMs / 1000.0)

    fun valueFor(
        item: AppSettingItem,
        volumePercent: Int,
        brightnessPercent: Int,
        scanIntervalMs: Long,
        scanMode: ScanMode,
        displayMode: AppUiSettings.DisplayMode,
        floatingBallVisible: Boolean,
        enabledEntertainmentCount: Int = EntertainmentHubModule.subFeatures.size
    ): String = when (item) {
        AppSettingItem.BACK -> ""
        AppSettingItem.VOLUME -> "${volumePercent}%"
        AppSettingItem.BRIGHTNESS -> "${brightnessPercent}%"
        AppSettingItem.SPEED -> formatScanInterval(scanIntervalMs)
        AppSettingItem.SCAN_MODE -> if (scanMode == ScanMode.AUTO) "自动" else "手动"
        AppSettingItem.DISPLAY_MODE -> displayModeLabel(displayMode)
        AppSettingItem.FLOATING_BALL -> if (floatingBallVisible) "显示" else "隐藏"
        AppSettingItem.SPEED_PRACTICE -> "12 题"
        AppSettingItem.ENTERTAINMENT_MARKET -> "$enabledEntertainmentCount / ${EntertainmentHubModule.subFeatures.size}"
    }
}

@Composable
fun AppSettingsScreen(
    scanIntervalMs: Long,
    scanMode: ScanMode,
    displayMode: AppUiSettings.DisplayMode,
    floatingBallVisible: Boolean,
    volumePercent: Int,
    brightnessPercent: Int,
    enabledEntertainmentCount: Int = EntertainmentHubModule.subFeatures.size,
    paused: Boolean,
    frozen: Boolean,
    initialIndex: Int = AppSettingsLogic.INITIAL_INDEX,
    onOpenAdjust: (AdjustOverlayKind) -> Unit,
    onOpenSpeedPractice: () -> Unit,
    onOpenEntertainmentMarket: () -> Unit = {},
    onScanModeChange: (ScanMode) -> Unit,
    onDisplayModeChange: (AppUiSettings.DisplayMode) -> Unit,
    onFloatingBallVisibleChange: (Boolean) -> Unit,
    onToast: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val items = AppSettingItem.entries
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnOpenAdjust by rememberUpdatedState(onOpenAdjust)
    val currentOnOpenSpeedPractice by rememberUpdatedState(onOpenSpeedPractice)
    val currentOnOpenEntertainmentMarket by rememberUpdatedState(onOpenEntertainmentMarket)
    val currentOnScanModeChange by rememberUpdatedState(onScanModeChange)
    val currentOnDisplayModeChange by rememberUpdatedState(onDisplayModeChange)
    val currentOnFloatingBallChange by rememberUpdatedState(onFloatingBallVisibleChange)
    val currentOnToast by rememberUpdatedState(onToast)

    var cursor by remember {
        mutableStateOf(
            ScanCursor(
                size = items.size,
                index = initialIndex.coerceIn(items.indices),
                direction = 1
            )
        )
    }
    var dwellRevision by remember { mutableIntStateOf(0) }

    val focusedItem = items[cursor.index.coerceIn(items.indices)]
    ReportActionHints(ActionHints.list(focusedItem.label, scanMode, cursor.direction))

    fun choose(index: Int) {
        val item = items.getOrNull(index) ?: return
        cursor = cursor.copy(index = index)
        when (item) {
            AppSettingItem.BACK -> currentOnBack()
            AppSettingItem.VOLUME -> currentOnOpenAdjust(AdjustOverlayKind.VOLUME)
            AppSettingItem.BRIGHTNESS -> currentOnOpenAdjust(AdjustOverlayKind.BRIGHTNESS)
            AppSettingItem.SPEED -> currentOnOpenAdjust(AdjustOverlayKind.SPEED)
            AppSettingItem.SCAN_MODE -> {
                val nextMode = if (scanMode == ScanMode.AUTO) ScanMode.MANUAL else ScanMode.AUTO
                currentOnScanModeChange(nextMode)
                if (!frozen) {
                    currentOnToast("扫描方式：${if (nextMode == ScanMode.AUTO) "自动" else "手动"}", true)
                }
                dwellRevision++
            }
            AppSettingItem.DISPLAY_MODE -> {
                val nextDisplay = AppSettingsLogic.cycleDisplayMode(displayMode)
                currentOnDisplayModeChange(nextDisplay)
                if (!frozen) {
                    currentOnToast("显示模式：${AppSettingsLogic.displayModeLabel(nextDisplay)}", true)
                }
                dwellRevision++
            }
            AppSettingItem.FLOATING_BALL -> {
                val nextVisible = !floatingBallVisible
                currentOnFloatingBallChange(nextVisible)
                if (!frozen) {
                    currentOnToast(if (nextVisible) "模拟悬浮球已显示" else "模拟悬浮球已隐藏", true)
                }
                dwellRevision++
            }
            AppSettingItem.SPEED_PRACTICE -> currentOnOpenSpeedPractice()
            AppSettingItem.ENTERTAINMENT_MARKET -> currentOnOpenEntertainmentMarket()
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
        cursor = ScanCursor(size = items.size, index = targetIndex.coerceIn(items.indices), direction = 1)
        dwellRevision++
    }
    val signalSink = remember { AppSettingsSignalSink { signalHandler(it) } }

    DisposableEffect(signalSink) {
        AppSettingsInputBus.attach(
            candidate = signalSink,
            onTick = { tickHandler() },
            onResetFocus = { targetIndex -> resetFocusHandler(targetIndex) }
        )
        onDispose { AppSettingsInputBus.detach(signalSink) }
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
                    onFailure = { android.util.Log.e("ScanCursor", "Settings tick failed", it) }
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
    val cssLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // Left column: 8 equal-height setting rows.
        Column(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items.forEachIndexed { idx, item ->
                val focused = idx == cursor.index
                val valueText = AppSettingsLogic.valueFor(
                    item = item,
                    volumePercent = volumePercent,
                    brightnessPercent = brightnessPercent,
                    scanIntervalMs = scanIntervalMs,
                    scanMode = scanMode,
                    displayMode = displayMode,
                    floatingBallVisible = floatingBallVisible,
                    enabledEntertainmentCount = enabledEntertainmentCount
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scanItem(
                            focused = focused,
                            direction = dir,
                            picked = false,
                            progress = if (focused) progressProvider else null,
                            navigation = item.isNavigation,
                            showDirectionTag = false
                        )
                        .clickable(enabled = !paused) { choose(idx) }
                        .padding(horizontal = 26.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = null,
                            tint = c.muted,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = item.label,
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            style = TextStyle(
                                lineHeightStyle = cssLineBox,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                    if (valueText.isNotEmpty()) {
                        Text(
                            text = mixedLatinText(valueText),
                            color = if (focused) c.focus else c.muted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            style = TextStyle(
                                lineHeightStyle = cssLineBox,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            }
        }

        // Right column (.sdesc): zoomed value and description for the focused row.
        val focusedValue = AppSettingsLogic.valueFor(
            item = focusedItem,
            volumePercent = volumePercent,
            brightnessPercent = brightnessPercent,
            scanIntervalMs = scanIntervalMs,
            scanMode = scanMode,
            displayMode = displayMode,
            floatingBallVisible = floatingBallVisible,
            enabledEntertainmentCount = enabledEntertainmentCount
        )
        val panelShape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(c.mag, panelShape)
                .border(2.dp, c.line, panelShape)
                .padding(34.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = focusedItem.label,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 22.sp,
                lineHeight = 31.sp,
                style = TextStyle(
                    lineHeightStyle = cssLineBox,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
            if (focusedValue.isNotEmpty()) {
                Text(
                    text = mixedLatinText(focusedValue),
                    color = c.focus,
                    fontWeight = FontWeight(800),
                    fontSize = 92.sp,
                    lineHeight = 92.sp,
                    style = TextStyle(
                        lineHeightStyle = cssLineBox,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
            if (focusedItem.description.isNotBlank()) {
                Text(
                    text = mixedLatinText(focusedItem.description),
                    color = c.text,
                    fontWeight = FontWeight.Normal,
                    fontSize = 24.sp,
                    lineHeight = 36.sp,
                    style = TextStyle(
                        lineHeightStyle = cssLineBox,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
        }
    }
}

internal fun displayModeBrightnessDefaultNote(value: Float): Int =
    (value.coerceIn(AppUiSettings.MIN_BRIGHTNESS, AppUiSettings.MAX_BRIGHTNESS) * 100f).roundToInt()

private fun mixedLatinText(value: String) = buildAnnotatedString {
    for (ch in value) {
        withStyle(SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)) {
            append(ch)
        }
    }
}
