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

fun Modifier.scanItem(focused: Boolean, direction: Int, picked: Boolean, progress: (() -> Float?)?,
                      navigation: Boolean = false, enabled: Boolean = true, grow: Boolean = true): Modifier = composed {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(16.dp)
    val scale by animateFloatAsState(if (grow && focused) 1.025f else 1f, tween(140), label = "focus")
    val measure = rememberTextMeasurer()
    val arrow = measure.measure(if (direction < 0) "←" else "→", TextStyle(color = c.focus, fontFamily = BciType.latin,
        fontWeight = FontWeight.Bold, fontSize = 16.sp))
    val stroke = when { picked -> c.ok; focused -> c.focus; else -> c.line }
    val fill = when { picked -> c.okTint.compositeOver(c.panel); focused -> c.focusTint.compositeOver(c.panel2)
        navigation -> Color.Transparent; else -> c.panel }
    this.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .32f }
        .drawBehind {
            if (focused) {
                val ring = 5.dp.toPx()
                drawRoundRect(c.focusRing, Offset(-ring,-ring), Size(size.width+2*ring,size.height+2*ring), CornerRadius(21.dp.toPx()))
            }
        }.shadow(if (!navigation && c.bg.luminance() > .5f) 2.dp else 0.dp, shape, clip = false)
        .background(fill,shape).drawWithContent {
            drawContent()
            val border = 2.dp.toPx()
            val dash = if (navigation && !focused) PathEffect.dashPathEffect(floatArrayOf(border*3,border*2)) else null
            drawRoundRect(stroke,Offset(border/2,border/2),Size(size.width-border,size.height-border),CornerRadius(15.dp.toPx()),style = Stroke(border,pathEffect = dash))
            if (focused && direction != 0) drawText(arrow, topLeft = Offset(size.width-12.dp.toPx()-arrow.size.width,8.dp.toPx()))
            val fraction = progress?.invoke()
            if (focused && fraction != null) {
                val inset = 14.dp.toPx()
                val width = (size.width-2*inset).coerceAtLeast(0f)*fraction.coerceIn(0f,1f)
                drawRoundRect(c.focus,Offset(if (direction < 0) size.width-inset-width else inset,size.height-13.dp.toPx()),
                    Size(width,5.dp.toPx()),CornerRadius(3.dp.toPx()))
            }
        }
}
