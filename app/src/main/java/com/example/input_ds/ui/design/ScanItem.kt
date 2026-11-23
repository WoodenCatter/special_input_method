package com.example.input_ds.ui.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.example.input_ds.ui.theme.*

fun Modifier.scanItem(
    focused: Boolean,
    direction: Int,
    picked: Boolean,
    progress: (() -> Float?)?,
    navigation: Boolean = false,
    enabled: Boolean = true,
    grow: Boolean = true,
    cornerRadius: Dp = 16.dp,
    alarm: Boolean = false,
    borderWidth: Dp = 2.dp,
    customBorderColor: Color? = null,
    customFillColor: Color? = null,
    urgentBar: Boolean = false,
    unfocusedAlpha: Float = 1f,
    dwellInset: Dp = 14.dp,
    dwellBottom: Dp = 8.dp,
    dwellHeight: Dp = 5.dp,
    showShadow: Boolean = !navigation
): Modifier = composed {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val scale by animateFloatAsState(if (grow && focused) 1.025f else 1f, tween(140), label = "focus")
    val measure = rememberTextMeasurer()
    val arrowColor = if (alarm) c.onAlarm else c.focus
    val arrow = measure.measure(
        if (direction < 0) "←" else "→",
        TextStyle(color = arrowColor, fontFamily = BciType.latin, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    )
    val stroke = when {
        picked -> c.ok
        alarm -> c.alarm
        urgentBar -> c.alarm
        focused -> c.focus
        customBorderColor != null -> customBorderColor
        else -> c.line
    }
    val fill = when {
        picked -> c.okTint.compositeOver(c.panel)
        alarm -> c.alarm
        focused -> c.focusTint.compositeOver(c.panel2)
        customFillColor != null -> customFillColor
        navigation -> Color.Transparent
        else -> c.panel
    }
    val barColor = if (alarm) c.onAlarm else c.focus
    val effectiveAlpha = when {
        !enabled -> .32f
        !focused -> unfocusedAlpha
        else -> 1f
    }
    this.zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = effectiveAlpha }
        .drawBehind {
            if (focused) {
                if (alarm) {
                    val outer = 9.dp.toPx()
                    val gap = 4.dp.toPx()
                    drawRoundRect(
                        c.focus,
                        Offset(-outer, -outer),
                        Size(size.width + 2 * outer, size.height + 2 * outer),
                        CornerRadius((cornerRadius + 9.dp).toPx())
                    )
                    drawRoundRect(
                        c.bg,
                        Offset(-gap, -gap),
                        Size(size.width + 2 * gap, size.height + 2 * gap),
                        CornerRadius((cornerRadius + 4.dp).toPx())
                    )
                } else {
                    val ring = 5.dp.toPx()
                    drawRoundRect(
                        c.focusRing,
                        Offset(-ring, -ring),
                        Size(size.width + 2 * ring, size.height + 2 * ring),
                        CornerRadius((cornerRadius + 5.dp).toPx())
                    )
                }
            }
        }
        .shadow(if (showShadow && !focused && c.bg.luminance() > .5f) 2.dp else 0.dp, shape, clip = false)
        .background(fill, shape)
        .drawWithContent {
            if (urgentBar) {
                val clipPath = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(cornerRadius.toPx())))
                }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(clipPath)
                drawRect(c.alarm, Offset.Zero, Size(8.dp.toPx(), size.height))
                drawContext.canvas.restore()
            }
            drawContent()
            val border = borderWidth.toPx()
            val dash = if (navigation && !focused) PathEffect.dashPathEffect(floatArrayOf(border * 3, border * 2)) else null
            drawRoundRect(
                stroke,
                Offset(border / 2, border / 2),
                Size(size.width - border, size.height - border),
                CornerRadius((cornerRadius - borderWidth / 2).coerceAtLeast(0.dp).toPx()),
                style = Stroke(border, pathEffect = dash)
            )
            if (focused && direction != 0) {
                drawText(arrow, topLeft = Offset(size.width - 12.dp.toPx() - arrow.size.width, 8.dp.toPx()))
            }
            val fraction = progress?.invoke()
            if (focused && fraction != null) {
                val inset = dwellInset.toPx()
                val h = dwellHeight.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f)
                drawRoundRect(
                    barColor,
                    Offset(if (direction < 0) size.width - inset - width else inset, size.height - dwellBottom.toPx() - h),
                    Size(width, h),
                    CornerRadius((h / 2f).coerceAtLeast(2.dp.toPx()))
                )
            }
        }
}
