package com.example.input_ds.data

import android.content.Context
import android.content.SharedPreferences

/** 常用语独立使用权重；同权重时保持内置词表的原始顺序。 */
object CommonPhraseUsage {
    private const val PREFS_NAME = "common_phrase_usage"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun record(phrase: String) {
        val next = prefs.getInt(phrase, 0) + 1
        prefs.edit().putInt(phrase, next).apply()
    }

    fun sorted(phrases: List<String>): List<String> =
        phrases.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { prefs.getInt(it.value, 0) }
                    .thenBy { it.index }
            )
            .map { it.value }
}
