package com.example.input_ds.ui.communication

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.communication.CallVideoRenderer
import com.example.input_ds.communication.VideoActionStyle
import com.example.input_ds.communication.VideoSession
import com.example.input_ds.communication.VideoState
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.ContactAvatar
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

private val VideoSurfaceBg = Color(0xFF0E1114)
private val VideoTextPrimary = Color(0xFFF2EEE8)
private val VideoTextSecondary = Color(0xFFB9B2A8)
private val VideoLocalBg = Color(0xFF252B31)
private val VideoAcceptColor = Color(0xFF5EE0A0)
private val VideoHangColor = Color(0xFFFF8A7E)

@Composable
internal fun PadVideoScreen(
    session: VideoSession,
    state: VideoState,
    cursor: ScanCursor,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    ask: () -> Unit
) {
    val call = state.call ?: return
    val c = LocalBciColors.current
    BackHandler { session.back() }

    val actions = session.actions(ask)
    val selected = cursor.index.coerceIn(0, actions.lastIndex.coerceAtLeast(0))
    val autoScan = scanMode == ScanMode.AUTO && !state.confirmEnd
    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(selected, cursor.direction, dwellRevision, scanIntervalMs, autoScan, paused, frozen) {
        when {
            frozen -> dwellProgress.snapTo(if (autoScan) 0.5f else 0f)
            paused || !autoScan -> dwellProgress.snapTo(0f)
            else -> {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }
    val progressProvider: () -> Float? = { if (autoScan) dwellProgress.value else null }

    val activeSince = state.activeSince
    var seconds by remember(activeSince, frozen) {
        mutableLongStateOf(if (frozen && activeSince != null) 83L else 0L)
    }
    LaunchedEffect(activeSince, frozen) {
        if (frozen && activeSince != null) {
            seconds = 83L
            return@LaunchedEffect
        }
        if (activeSince != null) {
            while (true) {
                seconds = ((SystemClock.elapsedRealtime() - activeSince) / 1000L).coerceAtLeast(0L)
                delay(500L)
            }
        } else {
            seconds = 0L
        }
    }

    val isIncomingRinging = call.status == "RINGING" && state.incoming
    val isActive = call.status == "ACTIVE" || (state.connected && activeSince != null)
    val statusLabel = when {
        call.status == "RINGING" -> if (state.incoming) "视频来电…" else "正在呼叫…"
        !state.connected -> "正在连接音视频…"
        activeSince == null -> "等待对方连接…"
        else -> "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    val topTag = when {
        isActive -> "● 通话中"
        isIncomingRinging -> "来电"
        else -> "视频通话"
    }

    val containerShape = RoundedCornerShape(18.dp)
    val hue = ((state.avatarHue % 360f) + 360f) % 360f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .clip(containerShape)
            .background(VideoSurfaceBg)
    ) {
        // Remote surface: LiveKit track when available, else radial-gradient + avatar placeholder.
        val room = state.room
        if (room != null && state.remoteVideo != null) {
            key(room) {
                VideoView(room, state.remoteVideo, Modifier.fillMaxSize())
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VideoSurfaceBg)
                    .drawBehind {
                        if (isActive) {
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.hsl(hue, 0.30f, 0.28f), VideoSurfaceBg),
                                    center = Offset(size.width * 0.5f, size.height * 0.35f),
                                    radius = maxOf(size.width * 0.65f, size.height * 0.85f)
                                )
                            )
                        }
                    }
                    .padding(bottom = 110.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ContactAvatar(
                        text = state.peerName.ifBlank { "联" },
                        size = 150.dp,
                        fontSize = 64,
                        self = false,
                        online = false,
                        hue = hue
                    )
                    Text(
                        text = state.peerName,
                        color = VideoTextPrimary,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(900),
                        fontSize = 44.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = statusLabel,
                        color = VideoTextSecondary,
                        fontFamily = if (isActive) BciType.latin else BciType.chinese,
                        fontWeight = FontWeight(500),
                        fontSize = 26.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }

        // Top-left status tag.
        Text(
            text = topTag,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 18.dp),
            color = VideoTextSecondary,
            fontFamily = BciType.chinese,
            fontSize = 18.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )

        // Top-right local preview (hidden during incoming ringing).
        if (!isIncomingRinging) {
            val localShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 20.dp)
                    .size(width = 230.dp, height = 144.dp)
                    .clip(localShape)
                    .background(VideoLocalBg)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), localShape),
                contentAlignment = Alignment.Center
            ) {
                if (room != null && state.localVideo != null && state.camera) {
                    key(room) {
                        VideoView(room, state.localVideo, Modifier.fillMaxSize())
                    }
                    if (!state.mic) {
                        Text(
                            text = "已静音",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            color = VideoTextPrimary,
                            fontFamily = BciType.chinese,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    val localLabel = (if (state.camera) "我的画面" else "摄像头已关闭") +
                        if (!state.mic) " · 已静音" else ""
                    Text(
                        text = localLabel,
                        color = VideoTextSecondary,
                        fontFamily = BciType.chinese,
                        fontSize = 18.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }

        // Bottom control row (hidden when hang-up confirm overlay is above).
        if (!state.confirmEnd && actions.isNotEmpty()) {
            val dir = if (scanMode == ScanMode.AUTO) cursor.direction else 0
            val single = actions.size == 1
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (single) Modifier.fillMaxWidth(0.36f)
                        else Modifier.fillMaxWidth().padding(horizontal = 22.dp)
                    )
                    .padding(bottom = 20.dp)
                    .height(92.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    val focused = index == selected
                    val textColor = when (action.style) {
                        VideoActionStyle.ACCEPT -> VideoAcceptColor
                        VideoActionStyle.HANG -> VideoHangColor
                        VideoActionStyle.NORMAL -> VideoTextPrimary
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = dir,
                                picked = false,
                                progress = if (focused) progressProvider else null,
                                customBorderColor = Color.White.copy(alpha = 0.16f),
                                customFillColor = Color.White.copy(alpha = 0.08f).compositeOver(VideoSurfaceBg),
                                customFocusedFillColor = Color.White.copy(alpha = 0.15f).compositeOver(VideoSurfaceBg),
                                showShadow = false
                            )
                            .clickable(enabled = !state.pending) { action.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = action.label,
                            color = textColor,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoView(room: Room, track: VideoTrack?, modifier: Modifier) {
    var renderer by remember(room) { mutableStateOf<CallVideoRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context -> CallVideoRenderer(context, room).also { renderer = it }.view },
        onReset = null,
        update = { renderer?.bind(track) },
        onRelease = { renderer?.release(); renderer = null }
    )
}
