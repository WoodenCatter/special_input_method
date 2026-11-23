package com.example.input_ds.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.alert.NurseCallState
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors

data class ConfirmOverlaySpec(
    val title: String,
    val description: String,
    val cancelLabel: String,
    val cancelSub: String,
    val confirmLabel: String,
    val confirmSub: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit
)

data class SpeakOverlayState(
    val text: String,
    val cursor: ScanCursor = ScanCursor(size = 3, index = 1)
) {
    companion object {
        val LABELS = listOf("返回修改", "再说一遍", "清空，开始新的一句")
    }
}

@Composable
fun NurseCallOverlay(
    state: NurseCallState,
    onDismissWhenSent: () -> Unit
) {
    val c = LocalBciColors.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.alarm)
            .clickable(interactionSource = interaction, indication = null) {
                if (state.sent) onDismissWhenSent()
            }
            .padding(horizontal = 48.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier
                .size(210.dp)
                .border(8.dp, c.onAlarm, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.sent) "✓" else state.remainingSeconds.toString(),
                color = c.onAlarm,
                fontFamily = if (state.sent) BciType.chinese else BciType.latin,
                fontWeight = FontWeight(800),
                fontSize = if (state.sent) 80.sp else 110.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Text(
            text = if (state.sent) "已呼叫护士" else "正在呼叫护士",
            modifier = Modifier.padding(top = 8.dp),
            color = c.onAlarm,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Bold,
            fontSize = 56.sp,
            lineHeight = 72.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        Text(
            text = if (state.sent) {
                "做任意动作关闭"
            } else {
                "${state.remainingSeconds} 秒后自动呼叫 · 左看取消 · 咬牙或右看立即呼叫"
            },
            color = c.onAlarm.copy(alpha = 0.9f),
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Normal,
            fontSize = 26.sp,
            lineHeight = 39.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
fun SpeakOverlay(
    state: SpeakOverlayState,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    onChoose: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.cursor.index, state.cursor.direction, dwellRevision, scanIntervalMs, scanMode, paused, frozen) {
        when {
            frozen -> progress.snapTo(0.5f)
            paused || scanMode != ScanMode.AUTO -> progress.snapTo(0f)
            else -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }
    val dir = if (scanMode == ScanMode.AUTO) state.cursor.direction else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.ov)
            .padding(horizontal = 48.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(
            text = "正在朗读 · 照护者请看这里",
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 20.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.text,
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 84.sp,
                lineHeight = 105.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SpeakOverlayState.LABELS.forEachIndexed { index, label ->
                val focused = index == state.cursor.index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .scanItem(
                            focused = focused,
                            direction = dir,
                            picked = false,
                            progress = if (focused) ({ if (scanMode == ScanMode.AUTO) progress.value else null }) else null
                        )
                        .clickable { onChoose(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmOverlay(
    spec: ConfirmOverlaySpec,
    cursor: ScanCursor,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    onChoose: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val dwellMs = cursor.dwell(scanIntervalMs, confirmation = true)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(cursor.index, cursor.ticks, dwellRevision, dwellMs, scanMode, paused, frozen) {
        when {
            frozen -> progress.snapTo(0.5f)
            paused || scanMode != ScanMode.AUTO -> progress.snapTo(0f)
            else -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(dwellMs.toInt(), easing = LinearEasing))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.ov)
            .padding(horizontal = 48.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column {
            Text(
                text = spec.title,
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 44.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = spec.description,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 22.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ConfirmHalfCard(
                topTag = "先亮 · 默认",
                mainLabel = spec.cancelLabel,
                subLabel = spec.cancelSub,
                rightAligned = false,
                focused = cursor.index == 0,
                progress = { if (scanMode == ScanMode.AUTO) progress.value else null },
                onClick = { onChoose(0) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            ConfirmHalfCard(
                topTag = "后亮",
                mainLabel = spec.confirmLabel,
                subLabel = spec.confirmSub,
                rightAligned = true,
                focused = cursor.index == 1,
                progress = { if (scanMode == ScanMode.AUTO) progress.value else null },
                onClick = { onChoose(1) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        Text(
            text = "两项自动轮流高亮，亮到哪项咬牙就选哪项 · 三轮不选按「${spec.cancelLabel}」处理",
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 20.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun ConfirmHalfCard(
    topTag: String,
    mainLabel: String,
    subLabel: String,
    rightAligned: Boolean,
    focused: Boolean,
    progress: () -> Float?,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    Box(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = 0,
                picked = false,
                progress = if (focused) progress else null,
                grow = false,
                cornerRadius = 22.dp,
                borderWidth = 3.dp,
                unfocusedAlpha = 0.55f,
                dwellInset = 34.dp,
                dwellBottom = 16.dp,
                dwellHeight = 7.dp
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(34.dp),
            horizontalAlignment = if (rightAligned) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = topTag,
                color = if (focused) c.focus else c.muted,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Column(
                horizontalAlignment = if (rightAligned) Alignment.End else Alignment.Start
            ) {
                Text(
                    text = mainLabel,
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 72.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = subLabel,
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 24.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}
