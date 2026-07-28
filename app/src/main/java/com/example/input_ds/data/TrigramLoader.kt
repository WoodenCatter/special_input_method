package com.example.input_ds.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 从 assets/trigrams.txt 加载 Trigrams 表
 * 格式: prev2,prev1:candidate1:score1,candidate2:score2,...
 */
object TrigramLoader {
    private var loaded: Map<String, Map<String, Int>>? = null

    fun load(context: Context): Map<String, Map<String, Int>> {
        loaded?.let { return it }
        val map = mutableMapOf<String, Map<String, Int>>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open("trigrams.txt"), "UTF-8"))
            reader.forEachLine { line ->
                val colonIdx = line.indexOf(':')
                if (colonIdx < 0) return@forEachLine
                val key = line.substring(0, colonIdx).trim()  // "prev2,prev1"
                val followers = mutableMapOf<String, Int>()
                line.substring(colonIdx + 1).split(",").forEach { entry ->
                    val kv = entry.split(":")
                    if (kv.size == 2) {
                        val score = kv[1].toIntOrNull() ?: 50
                        followers[kv[0].trim()] = score
                    }
                }
                if (followers.isNotEmpty()) map[key] = followers
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loaded = map
        return map
    }
}
