package com.example.input_ds.communication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

internal val videoPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

internal data class VideoCall(
    val id: String,
    val caller: String,
    val callerName: String,
    val status: String,
    val revision: Long,
    val endReason: String
) {
    companion object {
        fun parse(json: JSONObject) = VideoCall(
            json.getString("call_id"), json.getString("caller"),
            json.getString("caller_name"), json.getString("status"),
            json.getLong("revision"),
            if (json.isNull("end_reason")) "" else json.optString("end_reason")
        )
    }
}

internal data class VideoState(
    val call: VideoCall? = null,
    val incoming: Boolean = false,
    val peerName: String = "",
    val connected: Boolean = false,
    val camera: Boolean = false,
    val mic: Boolean = false,
    val remoteVideo: VideoTrack? = null,
    val localVideo: VideoTrack? = null,
    val room: Room? = null,
    val activeSince: Long? = null,
    val pending: Boolean = false,
    val confirmEnd: Boolean = false
)

internal data class VideoAction(val label: String, val invoke: () -> Unit)

/** Single owner for foreground call state, media, and signaling recovery. */
internal class VideoSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
    private val preferSpeaker: Boolean = false
) {
    private data class Account(
        val key: String,
        val request: (String, String, JSONObject?) -> JSONObject,
        val identity: () -> String
    )

    private val mutable = MutableStateFlow(VideoState())
    val state = mutable.asStateFlow()
    private var account: Account? = null
    private var media: Room? = null
    private var mediaJob: Job? = null
    private var events: Job? = null
    private var poll: Job? = null
    private var sync: Job? = null
    private var canReceive = false
    private var syncAgain = false
    private var blockedMediaCall: String? = null
    private var retryMediaCall: String? = null
    private var permissionAction: Pair<String?, () -> Unit>? = null
    private var waitingCreate: Pair<String, String>? = null
    private val finished = LinkedHashSet<String>()
    private val canSwitch by lazy {
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            manager.cameraIdList.mapNotNull {
                manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            }.let { android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT in it &&
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK in it }
        }.getOrDefault(false)
    }

    fun attach(key: String, request: (String, String, JSONObject?) -> JSONObject, identity: () -> String) {
        if (account?.key == key) return
        clear()
        account = Account(key, request, identity)
        recover()
    }

    fun foreground(value: Boolean) {
        canReceive = value
        if (value) {
            recover()
        } else {
            val current = state.value
            if (current.call != null && current.call.status != "RINGING") {
                blockedMediaCall = current.call.id
                command("end")
                stopMedia()
            }
        }
    }

    fun clear() {
        account = null
        permissionAction = null
        waitingCreate = null
        blockedMediaCall = null
        retryMediaCall = null
        syncAgain = false
        finished.clear()
        sync?.cancel()
        poll?.cancel()
        stopMedia()
        mutable.value = VideoState()
    }

    fun event(json: JSONObject) {
        if (!json.optString("type").startsWith("call.")) return
        val payload = json.optJSONObject("payload") ?: return
        val call = runCatching { VideoCall.parse(payload) }.getOrNull() ?: return
        if (state.value.call?.id == call.id) {
            applyCall(call)
        } else if (canReceive && call.status != "ENDED") {
            // An event can arrive late. Ask the server which call currently owns our slot.
            recover()
        }
    }

    fun recover() {
        val owner = account ?: return
        if (!canReceive) return
        if (sync?.isActive == true) {
            syncAgain = true
            return
        }
        val id = state.value.call?.id
        sync = scope.launch {
            try {
                val response = request(owner, if (id == null) "/calls/current" else "/calls/$id")
                if (account !== owner || !canReceive) return@launch
                val json = if (id == null) response.optJSONObject("call") else response
                if (json != null) applyCall(VideoCall.parse(json))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // WebSocket reconnect and the active call poll both retry.
            } finally {
                if (account === owner) {
                    sync = null
                    if (syncAgain) {
                        syncAgain = false
                        recover()
                    }
                }
            }
        }
    }

    private suspend fun request(owner: Account, path: String, method: String = "GET",
                                body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { owner.request(path, method, body) }

    fun start(conversation: String, name: String, ask: () -> Unit) {
        val owner = account ?: return
        if (!canReceive || state.value.call != null || state.value.pending || permissionAction != null) return
        withPermissions(null, ask) {
            if (account !== owner || !canReceive || state.value.call != null) return@withPermissions
            val previous = waitingCreate
            val uuid = if (previous?.first == conversation) previous.second else UUID.randomUUID().toString()
            waitingCreate = conversation to uuid
            mutable.value = VideoState(peerName = name, pending = true)
            scope.launch {
                try {
                    val json = request(owner, "/calls", "POST", JSONObject()
                        .put("conversation_id", conversation).put("client_request_id", uuid))
                    if (account === owner) {
                        waitingCreate = null
                        applyCall(VideoCall.parse(json))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (account === owner && canReceive) {
                        val conflict = e.message?.contains("CALL_BUSY") == true
                        if (conflict) waitingCreate = null
                        notice(if (conflict) "你或对方正在通话" else "呼叫请求失败，请重试")
                        recover()
                    }
                } finally {
                    if (account === owner) mutable.value = state.value.copy(pending = false)
                }
            }
        }
    }

    private fun withPermissions(id: String?, ask: () -> Unit, proceed: () -> Unit) {
        if (hasPermissions()) {
            proceed()
        } else if (permissionAction == null) {
            permissionAction = id to proceed
            ask()
        }
    }

    fun permissionResult() {
        val (id, proceed) = permissionAction ?: return
        permissionAction = null
        if (id != null && state.value.call?.id != id) return
        if (hasPermissions()) proceed()
        else if (id != null) command("permission_denied")
        else notice("请允许摄像头和麦克风权限后再发起视频通话")
    }

    private fun hasPermissions() = videoPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun actions(ask: () -> Unit): List<VideoAction> {
        val current = state.value
        val call = current.call ?: return emptyList()
        if (call.status == "RINGING") {
            return if (current.incoming) listOf(
                VideoAction("接听") { withPermissions(call.id, ask) { command("accept") } },
                VideoAction("拒绝") { command("reject") }
            ) else listOf(VideoAction("取消呼叫") { command("cancel") })
        }
        if (current.confirmEnd) return listOf(
            VideoAction("继续通话") { mutable.value = state.value.copy(confirmEnd = false) },
            VideoAction("确认挂断") { command("end") }
        )
        return buildList {
            if (current.connected) {
                add(VideoAction(if (current.mic) "静音" else "取消静音") { toggle(Track.Source.MICROPHONE) })
                add(VideoAction(if (current.camera) "关闭摄像头" else "打开摄像头") { toggle(Track.Source.CAMERA) })
                if (canSwitch && current.camera) {
                    add(VideoAction("切换摄像头") {
                        runCatching { (state.value.localVideo as? LocalVideoTrack)?.switchCamera() }
                            .onFailure { notice("无法切换摄像头") }
                    })
                }
            }
            add(VideoAction("挂断") { mutable.value = state.value.copy(confirmEnd = true) })
        }
    }

    fun back() {
        val current = state.value
        val call = current.call ?: return
        if (call.status == "RINGING") command(if (current.incoming) "reject" else "cancel")
        else mutable.value = current.copy(confirmEnd = !current.confirmEnd)
    }

    private fun command(action: String) {
        val owner = account ?: return
        val id = state.value.call?.id ?: return
        if (state.value.pending && action != "failed" && action != "end") return
        mutable.value = state.value.copy(pending = true)
        scope.launch {
            try {
                val json = request(owner, "/calls/$id/$action", "POST")
                if (account === owner && state.value.call?.id == id) applyCall(VideoCall.parse(json))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (account === owner && canReceive) {
                    notice("通话操作未确认，正在同步")
                    recover()
                }
            } finally {
                if (account === owner && state.value.call?.id == id) {
                    mutable.value = state.value.copy(pending = false)
                }
            }
        }
    }

    private fun applyCall(call: VideoCall) {
        val owner = account ?: return
        val current = state.value
        val known = current.call
        if (call.id in finished || (known != null && (known.id != call.id || known.revision > call.revision))) return
        if (call.status == "ENDED") {
            if (blockedMediaCall == call.id) blockedMediaCall = null
            if (retryMediaCall == call.id) retryMediaCall = null
            finished.add(call.id)
            if (finished.size > 64) finished.remove(finished.first())
            if (known == null && !current.pending) return
            permissionAction = null
            poll?.cancel()
            stopMedia()
            mutable.value = VideoState()
            if (canReceive) notice(when (call.endReason) {
                "timeout" -> "无人接听"
                "rejected" -> "通话已拒绝"
                "cancelled" -> "呼叫已取消"
                "permission_denied" -> "缺少相机或麦克风权限，通话已结束"
                "failed", "disconnected" -> "音视频连接已中断"
                else -> "通话已结束"
            })
            return
        }
        if (!canReceive && known == null) return
        val identity = owner.identity()
        if (identity.isBlank()) return
        val incoming = identity != call.caller
        mutable.value = current.copy(
            call = call, incoming = incoming,
            peerName = if (incoming) call.callerName else current.peerName.ifBlank { "联系人" },
            activeSince = if (call.status == "ACTIVE") current.activeSince ?: SystemClock.elapsedRealtime() else null
        )
        if (poll?.isActive != true) {
            poll = scope.launch {
                while (state.value.call?.id == call.id) {
                    delay(3_000)
                    recover()
                }
            }
        }
        if (call.status != "RINGING" && blockedMediaCall != call.id &&
            mediaJob?.isActive != true && media == null && canReceive
        ) {
            if (!hasPermissions()) command("permission_denied")
            else connect(owner, call.id)
        }
    }

    private fun connect(owner: Account, id: String) {
        mediaJob = scope.launch {
            try {
                val room = LiveKit.create(context.applicationContext)
                media = room
                if (preferSpeaker) {
                    room.audioSwitchHandler?.preferredDeviceList = listOf(
                        com.twilio.audioswitch.AudioDevice.Speakerphone::class.java,
                        com.twilio.audioswitch.AudioDevice.Earpiece::class.java
                    )
                }
                events = scope.launch {
                    room.events.collect { event ->
                        if (media !== room) return@collect
                        when (event) {
                            is RoomEvent.Reconnecting -> mutable.value = state.value.copy(connected = false, remoteVideo = null)
                            is RoomEvent.Reconnected -> {
                                mutable.value = state.value.copy(connected = true)
                                updateRemote(room)
                                recover()
                            }
                            is RoomEvent.Disconnected -> {
                                stopMedia()
                                if (canReceive && retryMediaCall != id) {
                                    retryMediaCall = id
                                    recover()
                                } else {
                                    blockedMediaCall = id
                                    command("failed")
                                }
                            }
                            is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed,
                            is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted,
                            is RoomEvent.ParticipantDisconnected -> updateRemote(room)
                            else -> Unit
                        }
                    }
                }
                val credentials = request(owner, "/calls/$id/join-token", "POST")
                withTimeout(30_000) {
                    room.connect(credentials.getString("url"), credentials.getString("token"))
                    check(room.localParticipant.setMicrophoneEnabled(true))
                    check(room.localParticipant.setCameraEnabled(true))
                }
                mutable.value = state.value.copy(room = room, connected = true, camera = true, mic = true,
                    localVideo = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack)
                updateRemote(room)
                while (media === room && state.value.call?.id == id) {
                    if (state.value.connected) {
                        try {
                            val result = request(owner, "/calls/$id/ready", "POST")
                            if (media === room) applyCall(VideoCall.parse(result))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            recover()
                        }
                    }
                    delay(10_000)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                blockedMediaCall = id
                stopMedia()
                command("failed")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                blockedMediaCall = id
                stopMedia()
                command("failed")
            }
        }
    }

    private fun updateRemote(room: Room) {
        val publication = room.remoteParticipants.values.firstOrNull()?.getTrackPublication(Track.Source.CAMERA)
        val track = if (publication?.muted == false) publication.track as? VideoTrack else null
        mutable.value = state.value.copy(remoteVideo = track)
    }

    private fun toggle(source: Track.Source) {
        val room = media ?: return
        val current = state.value
        if (current.pending || !current.connected) return
        mutable.value = current.copy(pending = true)
        scope.launch {
            try {
                val camera = source == Track.Source.CAMERA
                val enabled = if (camera) !current.camera else !current.mic
                val changed = if (camera) room.localParticipant.setCameraEnabled(enabled)
                              else room.localParticipant.setMicrophoneEnabled(enabled)
                check(changed)
                if (media === room) {
                    mutable.value = if (camera) state.value.copy(camera = enabled,
                        localVideo = if (enabled) room.localParticipant.getTrackPublication(source)?.track as? VideoTrack else null)
                    else state.value.copy(mic = enabled)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (media === room) notice("无法切换音视频设备")
            } finally {
                if (media === room) mutable.value = state.value.copy(pending = false)
            }
        }
    }

    private fun stopMedia() {
        val room = media
        media = null
        events?.cancel()
        events = null
        mediaJob?.cancel()
        mediaJob = null
        if (room != null) {
            mutable.value = state.value.copy(room = null, localVideo = null, remoteVideo = null, connected = false)
            runCatching { room.disconnect() }
            runCatching { room.release() }
        }
    }
}

internal class CallVideoRenderer(context: Context, room: Room) {
    val view = TextureViewRenderer(context).also { room.initVideoRenderer(it) }
    private var track: VideoTrack? = null

    fun bind(next: VideoTrack?) {
        if (track === next) return
        track?.removeRenderer(view)
        track = next
        next?.addRenderer(view)
    }

    fun release() {
        runCatching { bind(null) }
        view.release()
    }
}
