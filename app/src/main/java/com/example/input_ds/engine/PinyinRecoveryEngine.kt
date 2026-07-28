package com.example.input_ds.engine

import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.PinyinDictionary
import com.example.input_ds.data.PinyinTrie
import com.example.input_ds.data.TrieNode
import com.example.input_ds.model.PinyinCandidate

/**
 * 九键拼音恢复引擎（单音节模式）
 *
 * digit 序列 → 枚举该序列能表示的合法拼音音节
 * 例如 [9,4,2,6] → "xian", "xiao" 等
 */
class PinyinRecoveryEngine(
    private val trie: PinyinTrie = PinyinDictionary.buildTrie()
) {
    private val digitToPinyins = mutableMapOf<String, List<Pair<String, Double>>>()

    /** 只返回单音节拼音候选 */
    fun recover(digits: List<Int>): List<PinyinCandidate> {
        if (digits.isEmpty()) return emptyList()
        return getPinyinsForDigits(digits)
            .sortedByDescending { it.second }
            .map { (pinyin, _) -> PinyinCandidate(letters = pinyin, isComplete = true) }
    }

    fun segmentPinyin(letters: String): List<String> {
        if (letters.isEmpty()) return emptyList()
        val n = letters.length
        val subDp = Array<MutableList<List<String>>?>(n + 1) { null }
        subDp[n] = mutableListOf(emptyList())
        for (i in n - 1 downTo 0) {
            val results = mutableListOf<List<String>>()
            for (j in i + 1..n) {
                val seg = letters.substring(i, j)
                if (trie.isWord(seg)) {
                    subDp[j]?.forEach { rest -> results.add(listOf(seg) + rest) }
                }
            }
            if (results.isNotEmpty()) subDp[i] = results
        }
        val all = subDp[0] ?: return emptyList()
        return all.maxByOrNull { seg ->
            seg.sumOf { PinyinDictionary.PINYIN_FREQUENCY[it] ?: 20 }
        } ?: all.first()
    }

    // ==================== 内部 ====================

    /** digit 子序列 → 合法拼音音节 + 频率分 */
    private fun getPinyinsForDigits(digits: List<Int>): List<Pair<String, Double>> {
        val key = digits.joinToString(",")
        digitToPinyins[key]?.let { return it }

        val lettersPerPos = digits.map { d ->
            LetterBlockMapping.DIGIT_TO_LETTERS[d] ?: return emptyList()
        }

        val results = mutableListOf<Pair<String, Double>>()
        // 枚举所有字母组合（3^6 = 729 最坏情况，可接受）
        enumerateCombinations(lettersPerPos, 0, StringBuilder(), trie.root, results)

        val sorted = results.sortedByDescending { it.second }
        digitToPinyins[key] = sorted
        return sorted
    }

    /** 递归枚举字母组合，Trie 剪枝 */
    private fun enumerateCombinations(
        lettersPerPos: List<List<Char>>,
        pos: Int,
        current: StringBuilder,
        node: TrieNode,
        results: MutableList<Pair<String, Double>>
    ) {
        if (pos >= lettersPerPos.size) return

        for (c in lettersPerPos[pos]) {
            val child = node.getChild(c) ?: continue
            current.append(c)
            if (child.isWord) {
                val pinyin = current.toString()
                val freq = (PinyinDictionary.PINYIN_FREQUENCY[pinyin] ?: 20).toDouble()
                results.add(pinyin to freq)
            }
            // 继续扩展（即使已经是完整词，也可能有更长音节如 xia→xian）
            enumerateCombinations(lettersPerPos, pos + 1, current, child, results)
            current.deleteCharAt(current.length - 1)
        }
    }
}
