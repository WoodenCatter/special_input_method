package com.example.input_ds.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class AssistantConfig(
    val address: String = "",
    val accessCode: String = ""
) {
    fun hostSummary(): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return "未配置"
        val url = trimmed.toHttpUrlOrNull() ?: return trimmed.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val defaultPort = (url.isHttps && url.port == 443) || (!url.isHttps && url.port == 80)
        return if (defaultPort) url.host else "${url.host}:${url.port}"
    }
}

/** Shared configuration and debug draft state between CaregiverSheet and AssistantScreen. */
internal object AssistantCareStore {
    private const val PREFS_NAME = "assistant_connection"
    private const val KEY_ADDRESS = "address"
    private const val KEY_ACCESS_CODE = "access_code"

    private val _config = MutableStateFlow(AssistantConfig())
    val config: StateFlow<AssistantConfig> = _config

    private val _debugDraft = MutableStateFlow("")
    val debugDraft: StateFlow<String> = _debugDraft

    fun load(context: Context): AssistantConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loaded = AssistantConfig(
            address = prefs.getString(KEY_ADDRESS, "").orEmpty(),
            accessCode = prefs.getString(KEY_ACCESS_CODE, "").orEmpty()
        )
        _config.value = loaded
        return loaded
    }

    fun save(context: Context, connection: AssistantConnection) {
        val updated = AssistantConfig(
            address = connection.address.toString(),
            accessCode = connection.accessCode
        )
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, updated.address)
            .putString(KEY_ACCESS_CODE, updated.accessCode)
            .apply()
        _config.value = updated
    }

    fun setDebugDraft(text: String) {
        _debugDraft.value = text
    }
}
