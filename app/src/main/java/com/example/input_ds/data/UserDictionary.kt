package com.example.input_ds.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户词典：记录用户输入过的词语及其频率
 * 使用 SharedPreferences 持久化，输入过的词下次优先推荐
 */
object UserDictionary {
    private const val PREFS_NAME = "user_dict"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 记录一个词的使用（频率+1） */
    fun record(word: String) {
        if (word.length < 2) return
        val count = prefs.getInt(word, 0) + 1
        prefs.edit().putInt(word, count).apply()
    }

    /** 获取用户输入过的所有词及频率（用于预测排序加权） */
    fun getUserWords(startingWith: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        for ((key, value) in prefs.all) {
            if (key is String && value is Int && key.startsWith(startingWith)) {
                result.add(key to value)
            }
        }
        return result.sortedByDescending { it.second }
    }

    /** 获取某词的频率分 */
    fun getScore(word: String): Int = prefs.getInt(word, 0)
}
