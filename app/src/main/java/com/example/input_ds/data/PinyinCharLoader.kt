package com.example.input_ds.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 从 assets/pinyin_map.txt 加载完整拼音字库
 *
 * 文件格式：每行一个拼音，格式为 "pinyin:char1,char2,char3,..."
 * 字符按常用度降序排列，排前面的更常用（权重更高）。
 */
object PinyinCharLoader {

    private var loaded: Map<String, List<CharacterDictionary.CharEntry>>? = null

    /**
     * 加载字库（首次调用时解析文件，后续从缓存返回）
     */
    fun load(context: Context): Map<String, List<CharacterDictionary.CharEntry>> {
        loaded?.let { return it }

        val map = mutableMapOf<String, List<CharacterDictionary.CharEntry>>()
        try {
            val inputStream = context.assets.open("pinyin_map.txt")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

            reader.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.contains(":")) return@forEach

                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size != 2) return@forEach

                    val pinyin = parts[0].trim()
                    val chars = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    if (chars.isNotEmpty()) {
                        // 按位置赋权重：第1个字权重100，依次递减
                        val entries = chars.mapIndexed { index, char ->
                            CharacterDictionary.CharEntry(char, maxOf(100 - index * 2, 10))
                        }
                        map[pinyin] = entries
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return CharacterDictionary.buildFallbackMap()
        }

        loaded = map
        return map
    }
}
