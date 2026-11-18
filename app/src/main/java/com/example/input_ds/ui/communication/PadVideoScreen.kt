package com.example.input_ds.ui.communication

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.communication.CallVideoRenderer
import com.example.input_ds.communication.VideoSession
import com.example.input_ds.communication.VideoState
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

@Composable
internal fun PadVideoScreen(session: VideoSession, state: VideoState, selected: Int, ask: () -> Unit) {
    val call = state.call ?: return
    BackHandler { session.back() }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF142235)).safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(state.peerName, color = Color.White, fontSize = 32.sp)
        val activeSince = state.activeSince
        var seconds by remember(activeSince) { mutableStateOf(0L) }
        LaunchedEffect(activeSince) {
            if (activeSince != null) while (true) {
                seconds = (SystemClock.elapsedRealtime() - activeSince) / 1000
                delay(500)
            }
        }
        val label = when {
            call.status == "RINGING" -> if (state.incoming) "视频来电" else "正在呼叫…"
            !state.connected -> "正在连接音视频…"
            activeSince == null -> "等待对方连接…"
            else -> "%02d:%02d".format(seconds / 60, seconds % 60)
        }
        Text(label, color = Color.White, fontSize = 22.sp)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val room = state.room
            if (room != null) {
                key(room) {
                    VideoView(room, state.remoteVideo, Modifier.fillMaxSize())
                    if (state.remoteVideo == null) Text("等待对方视频", color = Color.White)
                    if (state.localVideo != null) {
                        VideoView(room, state.localVideo, Modifier.align(Alignment.TopEnd).size(160.dp, 110.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            session.actions(ask).forEachIndexed { index, action ->
                Button(
                    onClick = action.invoke,
                    enabled = !state.pending,
                    modifier = Modifier.heightIn(min = 58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selected == index)
                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                ) { Text(action.label, fontSize = 18.sp) }
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
