package com.example.input_ds.ui.design

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class GazeFeedback {
    var signal by mutableStateOf<ControlSignal?>(null)
        private set
    var event by mutableLongStateOf(0)
        private set
    var hit by mutableStateOf<ControlSignal?>(null)
        internal set
    var message by mutableStateOf("")
        private set
    var success by mutableStateOf(false)
        private set
    var notice by mutableLongStateOf(0)
        private set
    val log = mutableStateListOf<String>()
    private var audio: AudioTrack? = null

    fun emit(action: ControlSignal, crumb: String, sound: Boolean) {
        val frequency = when (action) {
            ControlSignal.LEFT_LOOK -> 440
            ControlSignal.RIGHT_LOOK -> 660
            ControlSignal.BITE -> 880
            else -> return
        }
        signal = action
        hit = action
        event++
        val label = when (action) { ControlSignal.LEFT_LOOK -> "左看"; ControlSignal.RIGHT_LOOK -> "右看"; else -> "咬牙" }
        log.add(0, SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + "  " + label + "  → " + crumb)
        if (log.size > 7) log.removeAt(7)
        if (sound) play(frequency)
    }

    fun toast(text: String, ok: Boolean = false) { message = text; success = ok; notice++ }
    fun release() { audio?.release(); audio = null }

    private fun play(frequency: Int) {
        release()
        val rate = 24000
        val pcm = ShortArray(rate * 18 / 100) { i ->
            val t = i.toDouble() / rate
            val gain = if (t <= .02) .0001 * 1400.0.pow(t / .02) else .14 * (1.0 / 1400).pow((t - .02) / .16)
            (Short.MAX_VALUE * gain * sin(2 * PI * frequency * t)).toInt().toShort()
        }
        try {
            audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2).build()
            audio?.let { if (it.write(pcm, 0, pcm.size) > 0) it.play() }
        } catch (failure: RuntimeException) { release(); android.util.Log.w("GazeFeedback", "Audio unavailable", failure) }
    }
}

@Composable
internal fun FeedbackOverlay(state: GazeFeedback) {
    val c = LocalBciColors.current
    val progress = remember { Animatable(1f) }
    LaunchedEffect(state.event) {
        if (state.event != 0L) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(450, easing = CubicBezierEasing(0f, 0f, .58f, 1f)))
        }
    }
    LaunchedEffect(state.event) { delay(350); state.hit = null }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        if (state.signal == ControlSignal.BITE) {
            val scale = .6f + .9f * p
            drawCircle(c.focus.copy(alpha = .9f * (1-p)), 77.dp.toPx() * scale, style = Stroke(6.dp.toPx() * scale))
        } else {
            val right = state.signal == ControlSignal.RIGHT_LOOK
            val width = 36.dp.toPx()
            val x = if (right) size.width-width else 0f
            val colors = listOf(c.focus.copy(alpha = .85f * (1-p)), Color.Transparent)
            drawRect(Brush.horizontalGradient(if (right) colors.reversed() else colors, x, x+width),
                Offset(x,0f), Size(width,size.height))
        }
    }
}

@Composable
internal fun BciToast(state: GazeFeedback, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state.notice) {
        if (state.notice > 0) { alpha.snapTo(1f); delay(1600); alpha.animateTo(0f,tween(200)) }
    }
    if (alpha.value > 0f) {
        val c = LocalBciColors.current
        Box(modifier.graphicsLayer { this.alpha = alpha.value }.background(if (state.success) c.ok else c.text, RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp)) {
            BciLabel(state.message,20,if (state.success) Color.White else c.bg)
        }
    }
}
