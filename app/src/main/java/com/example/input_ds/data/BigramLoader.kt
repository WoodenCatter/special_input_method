package com.example.input_ds.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 从 assets/bigrams.txt 加载 Bigram 词库
 * 格式: 前字:后字1:分1,后字2:分2,...
 */
object BigramLoader {
    private var loaded: Map<String, Map<String, Int>>? = null

    fun load(context: Context): Map<String, Map<String, Int>> {
        loaded?.let { return it }
        val map = mutableMapOf<String, Map<String, Int>>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open("bigrams.txt"), "UTF-8"))
            reader.forEachLine { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val prev = parts[0].trim()
                    val followers = mutableMapOf<String, Int>()
                    parts[1].split(",").forEach { entry ->
                        val kv = entry.split(":")
                        if (kv.size == 2) {
                            val score = kv[1].toIntOrNull() ?: 50
                            followers[kv[0].trim()] = score
                        }
                    }
                    if (followers.isNotEmpty()) map[prev] = followers
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loaded = map
        return map
    }
}
