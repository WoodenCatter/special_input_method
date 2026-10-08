package com.example.input_ds.model

import android.content.Context
import android.provider.Settings

/** Small, local preferences for patient-facing APP controls. */
object AppUiSettings {
    const val MIN_BRIGHTNESS = 0.10f
    const val MAX_BRIGHTNESS = 1.00f
    const val BRIGHTNESS_STEP = 0.10f

    private const val PREFERENCES = "app_ui_settings"
    private const val KEY_FLOATING_BALL_VISIBLE = "floating_ball_visible"
    private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"

    fun readFloatingBallVisible(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_FLOATING_BALL_VISIBLE, true)

    fun writeFloatingBallVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLOATING_BALL_VISIBLE, visible)
            .apply()
    }

    fun readScreenBrightness(context: Context): Float {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_SCREEN_BRIGHTNESS)) {
            return preferences.getFloat(KEY_SCREEN_BRIGHTNESS, 0.5f)
                .coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        }
        val systemBrightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (systemBrightness / 255f).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    fun writeScreenBrightness(context: Context, brightness: Float): Float {
        val validated = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCREEN_BRIGHTNESS, validated)
            .apply()
        return validated
    }
}
