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
    private const val KEY_DISABLED_ENTERTAINMENT_IDS = "disabled_entertainment_ids"

    enum class DisplayMode { AUTO, LIGHT, DARK }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun displayMode(context: Context) = DisplayMode.entries.firstOrNull {
        it.name == preferences(context).getString("display_mode", null)
    } ?: DisplayMode.AUTO
    fun writeDisplayMode(context: Context, mode: DisplayMode) {
        preferences(context).edit().putString("display_mode", mode.name).apply()
    }
    fun feedbackSound(context: Context) = preferences(context).getBoolean("feedback_sound", true)
    fun writeFeedbackSound(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("feedback_sound", enabled).apply()
    }
    fun floatingEegVisible(context: Context) = preferences(context).getBoolean("floating_eeg_visible", false)
    fun writeFloatingEegVisible(context: Context, visible: Boolean) {
        preferences(context).edit().putBoolean("floating_eeg_visible", visible).apply()
    }
    fun graceMs(context: Context) = preferences(context).getLong("grace_ms", 400L)

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

    fun readDisabledEntertainmentIds(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_DISABLED_ENTERTAINMENT_IDS, emptySet())
            ?.intersect(EntertainmentHubModule.allSubFeatureIds)
            ?: emptySet()

    fun readEnabledEntertainmentIds(context: Context): Set<String> =
        EntertainmentHubModule.allSubFeatureIds - readDisabledEntertainmentIds(context)

    fun writeEnabledEntertainmentIds(context: Context, enabledIds: Set<String>): Set<String> {
        val validEnabled = enabledIds.intersect(EntertainmentHubModule.allSubFeatureIds)
        val disabled = EntertainmentHubModule.allSubFeatureIds - validEnabled
        preferences(context)
            .edit()
            .putStringSet(KEY_DISABLED_ENTERTAINMENT_IDS, HashSet(disabled))
            .apply()
        return validEnabled
    }
}
