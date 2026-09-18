package com.example.input_ds.model

import android.content.Context

object ScanSettings {
    const val DEFAULT_INTERVAL_MS = 1_500L
    const val MIN_INTERVAL_MS = 1_100L
    const val MAX_INTERVAL_MS = 3_000L

    private const val PREFERENCES = "input_method_ui_settings"
    private const val KEY_INTERVAL_MS = "scan_interval_ms"

    fun read(context: Context): Long = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
        .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    fun write(context: Context, intervalMs: Long): Long {
        val validated = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL_MS, validated)
            .apply()
        return validated
    }
}
