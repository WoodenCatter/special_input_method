package com.example.input_ds.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.input_ds.R
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

@Composable
fun HomeScreen(
    selection: HomeSelectionState,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    onSelect: (HomeModule) -> Unit
) {
    val c = LocalBciColors.current
    var clockParts by remember { mutableStateOf(currentClockParts()) }
    LaunchedEffect(frozen) {
        if (frozen) return@LaunchedEffect
        while (true) {
            clockParts = currentClockParts()
            delay(10_000L)
        }
    }
    val (clockText, dateText) = if (frozen) FROZEN_CLOCK else clockParts

    val progress = remember { Animatable(0f) }
    LaunchedEffect(selection, dwellRevision, scanIntervalMs, scanMode, paused, frozen) {
        when {
            frozen -> progress.snapTo(0.5f)
            paused || scanMode != ScanMode.AUTO -> progress.snapTo(0f)
            else -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    val scanDirection = if (scanMode == ScanMode.AUTO) selection.scanDirection else 0

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Text(
                text = clockText,
                modifier = Modifier.alignByBaseline(),
                color = c.text,
                fontFamily = BciType.latin,
                fontWeight = FontWeight(800),
                fontSize = 84.sp,
                lineHeight = 84.sp,
                letterSpacing = (-0.01).em,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = dateText,
                modifier = Modifier.alignByBaseline(),
                color = c.muted,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Normal,
                fontSize = 26.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            HomeModule.entries.forEachIndexed { index, module ->
                val focused = index == selection.selectedIndex
                val targetWeight = if (focused) 1.5f else 1f
                val animatedWeight by animateFloatAsState(
                    targetValue = targetWeight,
                    animationSpec = tween(if (frozen) 0 else 300, easing = FastOutSlowInEasing),
                    label = "homeTileWeight"
                )
                HomeTile(
                    module = module,
                    focused = focused,
                    direction = scanDirection,
                    progress = { if (scanMode == ScanMode.AUTO) progress.value else null },
                    onClick = { onSelect(module) },
                    modifier = Modifier
                        .weight(if (frozen) targetWeight else animatedWeight)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    module: HomeModule,
    focused: Boolean,
    direction: Int,
    progress: () -> Float?,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    val isCall = module == HomeModule.CALL_NURSE
    val iconRes = when (module) {
        HomeModule.COMMUNICATION -> R.drawable.ic_lucide_message_square_text
        HomeModule.LIFE_ASSISTANT -> R.drawable.ic_lucide_bot
        HomeModule.ENTERTAINMENT -> R.drawable.ic_lucide_gamepad_2
        HomeModule.APP_SETTINGS -> R.drawable.ic_lucide_sliders_horizontal
        HomeModule.CALL_NURSE -> R.drawable.ic_lucide_bell_ring
    }
    val iconColor = when {
        isCall -> c.onAlarm
        focused -> c.focus
        else -> c.muted
    }
    val titleColor = if (isCall) c.onAlarm else c.text
    val subtitleColor = if (isCall) c.onAlarm.copy(alpha = 0.88f) else c.muted

    Box(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = if (focused) progress else null,
                grow = false,
                cornerRadius = 24.dp,
                alarm = isCall
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = module.displayName,
                color = titleColor,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 38.sp,
                lineHeight = 41.8.sp,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = module.subtitle,
                color = subtitleColor,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 24.3.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

private val FROZEN_CLOCK = "09:41" to "11月23日 星期一 · 上午"
private val WEEKDAYS = charArrayOf('日', '一', '二', '三', '四', '五', '六')

private fun currentClockParts(calendar: Calendar = Calendar.getInstance()): Pair<String, String> {
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    val weekday = WEEKDAYS[(calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)]
    val period = when {
        hour < 12 -> "上午"
        hour < 18 -> "下午"
        else -> "晚上"
    }
    val clock = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    val date = "${month}月${day}日 星期${weekday} · $period"
    return clock to date
}
