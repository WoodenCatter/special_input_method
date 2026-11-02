package com.example.input_ds.communication

import com.example.input_ds.personalization.DeviceServerBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** All chat calls are scoped by the device token AND the selected server profile. */
class ChatApi(private val binding: DeviceServerBinding, private val profileId: String) {
    private val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var token: String? = null
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base = "https://smu-brain.online"

    private fun authorization(): String {
        token?.let { return it }
        synchronized(this) {
            token?.let { return it }
            val body = JSONObject().put("user_id", binding.serverUserId)
                .put("api_key", binding.apiKey).toString().toRequestBody(jsonType)
            val request = Request.Builder().url("$base/api/v1/auth/token").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("设备认证失败 (${response.code})")
                return JSONObject(response.body?.string() ?: "{}").getString("access_token")
                    .also { token = it }
            }
        }
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        repeat(2) { attempt ->
            val builder = Request.Builder().url("$base/api/v1/chat$path")
                .header("Authorization", "Bearer ${authorization()}")
                .header("X-Chat-Profile-Id", profileId)
            val request = when (method) {
                "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
                else -> builder.get().build()
            }
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) { token = null; return@use }
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("聊天请求失败 (${response.code}): $text")
                return text
            }
        }
        throw IOException("聊天认证已失效")
    }

    fun contacts(): List<ChatContact> {
        val array = org.json.JSONArray(request("/contacts"))
        return (0 until array.length()).map { ChatContact.fromJson(array.getJSONObject(it)) }
    }
    fun me(): String = JSONObject(request("/me")).getString("principal_id")
    fun conversations(): List<JSONObject> {
        val array = org.json.JSONArray(request("/conversations"))
        return (0 until array.length()).map(array::getJSONObject)
    }
    fun messages(id: String, cursor: String, seq: Long, limit: Int = 50): Pair<List<ChatMessage>, Boolean> {
        val obj = JSONObject(request("/conversations/$id/messages?$cursor=$seq&limit=$limit"))
        val array = obj.getJSONArray("messages")
        return (0 until array.length()).map { ChatMessage.fromJson(array.getJSONObject(it)) } to obj.optBoolean("has_more")
    }
    fun send(id: String, clientId: String, text: String): ChatMessage = ChatMessage.fromJson(
        JSONObject(request("/conversations/$id/messages", "POST", JSONObject()
            .put("client_message_id", clientId).put("kind", "text").put("text", text)))
    )
    fun markRead(id: String, seq: Long) {
        request("/conversations/$id/read", "POST", JSONObject().put("last_read_seq", seq))
    }
    fun invitation(): JSONObject = JSONObject(request("/invitations", "POST"))
    fun socket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url("wss://smu-brain.online/api/v1/chat/ws")
            .header("Authorization", "Bearer ${authorization()}")
            .header("X-Chat-Profile-Id", profileId).build()
        return client.newWebSocket(request, listener)
    }
}
