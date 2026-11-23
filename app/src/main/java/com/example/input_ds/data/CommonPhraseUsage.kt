package com.example.input_ds.data

import android.content.Context
import android.content.SharedPreferences

/** 常用语独立使用权重；紧急常用语固定置顶，其余同权重时保持原始顺序。 */
object CommonPhraseUsage {
    private const val PREFS_NAME = "common_phrase_usage"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun record(phrase: String) {
        val store = prefs ?: return
        val next = store.getInt(phrase, 0) + 1
        store.edit().putInt(phrase, next).apply()
    }

    fun sorted(
        phrases: List<String>,
        urgentPinned: Set<String> = emptySet(),
        usageProvider: ((String) -> Int)? = null
    ): List<String> {
        val countOf: (String) -> Int = usageProvider ?: { phrase -> prefs?.getInt(phrase, 0) ?: 0 }
        return phrases.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { it.value in urgentPinned }
                    .thenBy { if (it.value in urgentPinned) it.index else Int.MAX_VALUE }
                    .thenByDescending { countOf(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }
}
