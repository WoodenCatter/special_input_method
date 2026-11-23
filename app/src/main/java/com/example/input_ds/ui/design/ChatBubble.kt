package com.example.input_ds.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatChatTime(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    // Preserve short human-readable strings (e.g. "09:12", "昨天", "周一").
    if (trimmed.length <= 6 && !trimmed.contains('T')) return trimmed
    // Parse ISO-8601 timestamps into local HH:mm if possible.
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )
    for (pattern in patterns) {
        val parsed = runCatching {
            val parser = SimpleDateFormat(pattern, Locale.ROOT)
            if (pattern.endsWith("'Z'")) parser.timeZone = TimeZone.getTimeZone("UTC")
            parser.parse(trimmed)
        }.getOrNull()
        if (parsed != null) {
            return SimpleDateFormat("HH:mm", Locale.ROOT).format(parsed)
        }
    }
    val tIdx = trimmed.indexOf('T')
    if (tIdx >= 0 && trimmed.length >= tIdx + 6) {
        return trimmed.substring(tIdx + 1, tIdx + 6)
    }
    return trimmed
}

internal fun currentChatTime(frozen: Boolean, plusMinutes: Int = 0): String {
    if (frozen) return if (plusMinutes > 0) "09:51" else "09:41"
    val now = Date(System.currentTimeMillis() + plusMinutes * 60_000L)
    return SimpleDateFormat("HH:mm", Locale.ROOT).format(now)
}

@Composable
fun ChatBubble(
    text: String,
    meta: String,
    own: Boolean,
    modifier: Modifier = Modifier,
    fontSize: Int = 23,
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 11.dp,
    maxWidth: Dp = 560.dp
) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(18.dp)
    val bg = if (own) c.focusTint.compositeOver(c.panel) else c.panel

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(bg, shape)
                .border(1.dp, c.line, shape)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start
        ) {
            Text(
                text = text,
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Normal,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.45f).sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildAnnotatedString {
                        for (ch in meta) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese
                                )
                            ) { append(ch) }
                        }
                    },
                    color = c.muted,
                    fontWeight = FontWeight(500),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    textAlign = if (own) TextAlign.End else TextAlign.Start,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}
