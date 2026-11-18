package com.example.input_ds.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AssistantConnection(val address: HttpUrl, val accessCode: String) {
    companion object {
        fun parse(address: String, accessCode: String, allowHttp: Boolean): AssistantConnection? {
            val url = address.trim().toHttpUrlOrNull() ?: return null
            val isLocalDebug = allowHttp && (url.host == "localhost" || url.host == "127.0.0.1")
            if ((!url.isHttps && !isLocalDebug) || url.encodedPath != "/" ||
                url.query != null || url.fragment != null ||
                url.username.isNotEmpty() || url.password.isNotEmpty()
            ) return null
            val code = accessCode.trim()
            if (!code.matches(Regex("[A-Za-z0-9_-]{6,64}"))) return null
            return AssistantConnection(url, code)
        }
    }
}

internal data class AssistantCatalogItem(val id: String, val name: String)

internal sealed interface AssistantResult {
    data class Message(val text: String, val clarify: Boolean) : AssistantResult
    data class PlayMusic(val trackId: String) : AssistantResult
    data class PlayTv(val channelId: String) : AssistantResult
    data class ConfirmHome(val options: List<HomeAction>) : AssistantResult
}

internal class AssistantServiceException(message: String) : IOException(message)

internal object AssistantService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkConnection(connection: AssistantConnection): Boolean {
        val result = request(connection, "health")
        if (result.opt("status") != "ok") throw AssistantServiceException("电脑服务响应异常")
        return result.optBoolean("model_configured")
    }

    suspend fun submit(
        connection: AssistantConnection,
        text: String,
        tracks: List<AssistantCatalogItem>,
        channels: List<AssistantCatalogItem>,
        home: SimulatedHome
    ): AssistantResult {
        val payload = JSONObject()
            .put("text", text)
            .put("tracks", encodeCatalog(tracks))
            .put("channels", encodeCatalog(channels))
            .put("home_state", encodeHome(home))
        val result = request(connection, "assistant", payload)
        val target = result.opt("target_id") as? String
        val message = result.opt("message") as? String
        val homeOptions = result.optJSONArray("home_options")
        val hasHomeOptions = homeOptions != null && homeOptions.length() > 0
        val kind = result.opt("kind")
        return when {
            (kind == "answer" || kind == "clarify") && !message.isNullOrBlank() &&
                target == null && !hasHomeOptions -> AssistantResult.Message(message, kind == "clarify")
            kind == "play_music" && target != null && message == null && !hasHomeOptions &&
                tracks.any { it.id == target } -> AssistantResult.PlayMusic(target)
            kind == "play_tv" && target != null && message == null && !hasHomeOptions &&
                channels.any { it.id == target } -> AssistantResult.PlayTv(target)
            kind == "confirm_home" && target == null && message == null && homeOptions != null ->
                AssistantResult.ConfirmHome(decodeHomeOptions(homeOptions))
            else -> throw AssistantServiceException("电脑服务返回了无法执行的结果")
        }
    }

    private fun encodeHome(home: SimulatedHome): JSONObject {
        val now = System.currentTimeMillis()
        val current = home.expireTimer(now)
        val ac = current.airConditioner
        return JSONObject()
            .put("main_light_on", current.mainLightOn)
            .put("bedside_light_on", current.bedsideLightOn)
            .put("curtain_position_percent", current.curtainPositionPercent)
            .put("air_conditioner", JSONObject()
                .put("power_on", ac.powerOn)
                .put("temperature_c", ac.temperatureC)
                .put("mode", ac.mode.name.lowercase())
                .put("swing", ac.swing.name.lowercase())
                .put("off_timer_minutes", ac.remainingMinutes(now)))
    }

    private fun decodeHomeOptions(options: JSONArray): List<HomeAction> {
        fun invalid(): Nothing = throw AssistantServiceException("电脑服务返回了无法执行的模拟家居操作")

        if (options.length() !in 1..2) invalid()
        return List(options.length()) { index ->
            val option = options.optJSONObject(index) ?: invalid()
            val id = option.opt("device_id") as? String ?: invalid()
            val settings = option.optJSONArray("settings") ?: invalid()
            if (settings.length() !in 1..5) invalid()
            val values = mutableMapOf<String, String>()
            for (settingIndex in 0 until settings.length()) {
                val setting = settings.optJSONObject(settingIndex) ?: invalid()
                val name = setting.opt("name") as? String ?: invalid()
                val value = setting.opt("value") as? String ?: invalid()
                if (values.put(name, value) != null) invalid()
            }
            HomeAction.parse(id, values) ?: invalid()
        }.distinct()
    }

    private fun encodeCatalog(items: List<AssistantCatalogItem>): JSONArray =
        JSONArray(items.map { JSONObject().put("id", it.id).put("name", it.name) })

    private suspend fun request(
        connection: AssistantConnection,
        path: String,
        payload: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(connection.address.newBuilder().addPathSegment(path).build())
            .header("Authorization", "Bearer ${connection.accessCode}")
            .apply { if (payload != null) post(payload.toString().toRequestBody(jsonType)) }
            .build()
        client.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(content).opt("detail") as? String
                } catch (_: JSONException) {
                    null
                }
                throw AssistantServiceException(detail ?: "电脑服务请求失败（HTTP ${response.code}）")
            }
            try {
                JSONObject(content)
            } catch (_: JSONException) {
                throw AssistantServiceException("电脑服务返回了无效的 JSON")
            }
        }
    }
}
