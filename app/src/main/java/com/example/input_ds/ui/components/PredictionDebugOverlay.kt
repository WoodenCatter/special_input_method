package com.example.input_ds.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.engine.PredictionDebugInfo
import kotlinx.coroutines.delay

/** Temporary Debug-build overlay. Remove this file and its Activity hook after diagnosis. */
@Composable
fun PredictionDebugOverlay(
    info: PredictionDebugInfo?,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(info?.eventId) {
        val current = info
        if (current == null) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(DISPLAY_DURATION_MS)
        visible = false
        delay(FADE_DURATION_MS.toLong())
        onExpired(current.eventId)
    }

    AnimatedVisibility(
        visible = visible && info != null,
        modifier = modifier,
        enter = fadeIn(tween(FADE_DURATION_MS)),
        exit = fadeOut(tween(FADE_DURATION_MS))
    ) {
        val current = info ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            color = Color(0xE6F2F3F5),
            contentColor = Color(0xFF25272A),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 3.dp
        ) {
            Text(
                text = current.asDisplayText(),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun PredictionDebugInfo.asDisplayText(): String = buildString {
    append("上下文末尾：")
    append(contextPreview.ifEmpty { "（空）" })
    append("  |  Rime提交：")
    append(submittedRimeContext.ifEmpty { "（无）" })
    append('\n')
    append("本地：${localCount.asCount()}  Rime原始：${rimeRawCount.asCount()}  ")
    append("OpenCC后：${rimeConvertedCount.asCount()}")
    append('\n')
    append("Rime有效：${rimeFilteredCount.asCount()}  入选：${rimeMergedCount.asCount()}  ")
    append("合并：${mergedCount.asCount()}")
    append('\n')
    append("LLM原始：${llmRawCount.asCount()}  有效：${llmAcceptedCount.asCount()}  ")
    append("追加：${llmMergedCount.asCount()}  ")
    append("LLM耗时：${llmElapsedMs}ms")
    append('\n')
    append("本地/Rime耗时：${elapsedMs}ms  状态：${status.displayName}")
    requestId?.let { append("  requestId：$it") }
    message?.takeIf { it.isNotBlank() }?.let {
        append('\n')
        append(it)
    }
}

private fun Int.asCount(): String = if (this < 0) "未知" else toString()

private const val DISPLAY_DURATION_MS = 4_000L
private const val FADE_DURATION_MS = 220
