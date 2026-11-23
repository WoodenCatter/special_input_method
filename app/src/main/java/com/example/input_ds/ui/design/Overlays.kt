package com.example.input_ds.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.alert.NurseCallState
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors

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
