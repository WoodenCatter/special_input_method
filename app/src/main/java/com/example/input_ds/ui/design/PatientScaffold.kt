package com.example.input_ds.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.theme.*
import java.util.Locale

data class ActionHints(val left: String, val bite: String, val right: String, val direction: Int = 0) {
    companion object {
        fun list(item: String, mode: ScanMode, direction: Int, gallery: Boolean = false) = when {
            mode == ScanMode.MANUAL -> ActionHints("上一项", "选「$item」", "下一项")
            gallery -> ActionHints("向左滑", "选「$item」", "向右滑", direction)
            else -> ActionHints("向左轮转", "选「$item」", "向右轮转", direction)
        }
    }
}

data class PatientStatus(
    val crumb: List<String>, val connection: String, val leftBattery: Int?, val rightBattery: Int?,
    val mode: ScanMode, val interval: Long, val display: String
)

@Composable
fun PatientScaffold(status: PatientStatus, hints: ActionHints, feedback: GazeFeedback,
                    onCare: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = LocalBciColors.current
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column {
                StatusBar(status, onCare)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) { content() }
                ActionBar(hints, feedback.hit)
            }
            FeedbackOverlay(feedback)
            BciToast(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp))
        }
    }
}

@Composable
private fun StatusBar(s: PatientStatus, onCare: (() -> Unit)?) {
    val c = LocalBciColors.current
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append(s.crumb.dropLast(1).joinToString(" › ", postfix = if (s.crumb.size > 1) " › " else ""))
                withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(s.crumb.last()) }
            }, color = c.muted, fontFamily = BciType.chinese, fontSize = 20.sp, letterSpacing = 0.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = when (s.connection) { "耳机已连接" -> c.ok; "信号中断" -> c.alarm; else -> c.dim }
                Pill(s.connection, dot = dot)
                Pill("L "+(s.leftBattery?.let { "$it%" } ?: "--")+" · R "+(s.rightBattery?.let { "$it%" } ?: "--"))
                Pill(if (s.mode == ScanMode.MANUAL) "手动扫描" else String.format(Locale.ROOT,"每项 %.1f s",s.interval / 1000.0))
                Pill(s.display)
                Pill("照护", inverted = true, modifier = Modifier.pointerInput(onCare) {
                    if (onCare == null) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        val held = withTimeoutOrNull(2000L) { waitForUpOrCancellation(); false } ?: true
                        if (held) onCare()
                    }
                })
            }
        }
        Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun Pill(value: String, modifier: Modifier = Modifier, dot: Color? = null, inverted: Boolean = false) {
    val c = LocalBciColors.current
    Row(modifier.height(if (value.startsWith("L ")) 28.dp else 30.dp).background(if (inverted) c.text else Color.Transparent, CircleShape)
        .border(1.dp, if (inverted) c.text else c.line, CircleShape).padding(horizontal = if (inverted) 14.dp else 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) Spacer(Modifier.size(9.dp).background(dot, CircleShape))
        BciLabel(value, 15, if (inverted) c.bg else c.muted, if (inverted) 500 else 400, latin = !inverted)
    }
}

@Composable
private fun ActionBar(h: ActionHints, hit: ControlSignal?, locked: Boolean = false, rest: Int = 0) {
    val c = LocalBciColors.current
    Column(Modifier.height(112.dp).fillMaxWidth().background(c.bar)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxSize()) {
            for ((i, signal) in listOf(ControlSignal.LEFT_LOOK, ControlSignal.BITE, ControlSignal.RIGHT_LOOK).withIndex()) {
                val middle = i == 1
                val active = (i == 0 && h.direction < 0) || (i == 2 && h.direction > 0)
                Box(Modifier.weight(if (middle) 1.15f else 1f).fillMaxHeight()
                    .background(if (hit == signal) c.focusTint else Color.Transparent)) {
                    if (middle) {
                        Spacer(Modifier.fillMaxHeight().width(1.dp).background(c.line))
                        Spacer(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp).background(c.line))
                    }
                    Row(Modifier.align(listOf(Alignment.CenterStart,Alignment.Center,Alignment.CenterEnd)[i])
                        .padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (i == 0) DirectionIcon("◀", active)
                        if (middle) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repeat(3) { n ->
                                    val filled = !locked || n < rest
                                    Spacer(Modifier.size(12.dp).border(2.dp, if (filled) c.ok else c.dim, CircleShape)
                                        .background(if (filled) c.ok else Color.Transparent, CircleShape))
                                }
                            }
                            Box(Modifier.width(44.dp)) { BciLabel(if (locked) "放松" else "就绪",15, if (locked) c.muted else c.ok) }
                        }
                        Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = if (i == 2) Alignment.End else Alignment.Start) {
                            Box(Modifier.height(18.dp).wrapContentHeight(unbounded = true)) {
                                Text(buildAnnotatedString {
                                append(listOf("左看","咬牙","右看")[i])
                                if (active) withStyle(SpanStyle(color = c.focus)) { append(" · 当前方向") }
                            }, color = c.muted, fontFamily = BciType.chinese, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
                            }
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.height(30.dp).wrapContentHeight(unbounded = true)) {
                                BciLabel(listOf(h.left,h.bite,h.right)[i],25,c.text,700)
                            }
                        }
                        if (i == 2) DirectionIcon("▶", active)
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionIcon(symbol: String, active: Boolean) {
    val c = LocalBciColors.current
    Box(Modifier.size(48.dp).border(2.dp, if (active) c.focus else c.line, CircleShape)
        .background(if (active) c.focus else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 19.dp, height = 22.dp)) {
            val right = symbol == "▶"
            val edge = if (right) 0f else size.width
            val point = if (right) size.width else 0f
            drawPath(Path().apply {
                moveTo(edge, 0f)
                lineTo(point, size.height / 2)
                lineTo(edge, size.height)
                close()
            }, if (active) c.bg else c.dim)
        }
    }
}

@Composable
internal fun BciLabel(text: String, size: Int, color: Color, weight: Int = 400, latin: Boolean = false) {
    // Archivo has no Han glyphs. Use the same explicit fallback order as the prototype.
    Text(buildAnnotatedString {
        for (ch in text) withStyle(SpanStyle(fontFamily = if (latin && ch.code < 0x3000) BciType.latin else BciType.chinese)) { append(ch) }
    }, fontWeight = FontWeight(weight), color = color, fontSize = size.sp, letterSpacing = 0.sp,
        lineHeight = (size * 1.45f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
