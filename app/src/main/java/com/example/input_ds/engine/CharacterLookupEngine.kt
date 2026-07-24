package com.example.input_ds.engine

import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.PinyinDictionary
import com.example.input_ds.model.CharCandidate
import com.example.input_ds.model.CompleteCandidate

/**
 * 汉字查找引擎
 *
 * 将拼音序列转换为汉字候选列表，并结合语言模型排序。
 */
class CharacterLookupEngine {

    /**
     * 根据拼音序列查找汉字候选
     *
     * @param pinyinSequence 拼音音节序列，如 ["ni", "hao"]
     * @param prevChar 前一个已确认的汉字（用于语言模型上下文）
     * @return 按得分降序排列的汉字候选列表
     */
    fun lookup(
        pinyinSequence: List<String>,
        prevChar: String? = null
    ): List<CompleteCandidate> {
        if (pinyinSequence.isEmpty()) return emptyList()

        // 为每个拼音找到汉字列表
        val charLists = pinyinSequence.map { pinyin ->
            CharacterDictionary.PINYIN_TO_CHARS[pinyin]?.map { it.char } ?: emptyList()
        }

        // 如果某个拼音没有匹配，直接返回
        if (charLists.any { it.isEmpty() }) return emptyList()

        // 生成所有组合
        val results = mutableListOf<CompleteCandidate>()
        generateCombinations(charLists, 0, mutableListOf(), results)

        // 计算每个组合的得分
        val scoredResults = results.map { candidate ->
            val score = scoreCandidate(candidate, prevChar)
            candidate.copy(score = score)
        }

        // 按得分降序排列
        return scoredResults.sortedByDescending { it.score }.take(30)
    }

    /**
     * 仅根据一个字拼音查找汉字候选（不分词）
     */
    fun lookupSingleChar(
        pinyin: String,
        prevChar: String? = null
    ): List<CharCandidate> {
        val entries = CharacterDictionary.PINYIN_TO_CHARS[pinyin] ?: return emptyList()
        return entries.map { entry ->
            val lmScore = LanguageModel.getBigramScore(prevChar, entry.char).toDouble() / 100.0
            val freqScore = entry.weight.toDouble() / 100.0
            CharCandidate(
                char = entry.char,
                pinyin = pinyin,
                score = freqScore * 0.6 + lmScore * 0.4
            )
        }.sortedByDescending { it.score }
    }

    /**
     * 根据字母块选择，直接生成所有可能的汉字候选
     * （用于一级扫描时实时预览）
     */
    fun lookupByBlockSelection(
        selectedBlocks: List<Int>,
        recoveryEngine: PinyinRecoveryEngine,
        prevChar: String? = null
    ): List<CompleteCandidate> {
        if (selectedBlocks.isEmpty()) return emptyList()

        val pinyinCandidates = recoveryEngine.recover(selectedBlocks)
        val allResults = mutableListOf<CompleteCandidate>()

        for (pinyinCandidate in pinyinCandidates) {
            val pinyins = recoveryEngine.segmentPinyin(pinyinCandidate.letters)
            if (pinyins.isNotEmpty()) {
                allResults.addAll(lookup(pinyins, prevChar))
            }
        }

        // 去重并按得分排序
        return allResults
            .distinctBy { it.text }
            .sortedByDescending { it.score }
            .take(20)
    }

    /**
     * 递归生成所有可能的汉字组合
     */
    private fun generateCombinations(
        charLists: List<List<String>>,
        index: Int,
        current: MutableList<String>,
        results: MutableList<CompleteCandidate>
    ) {
        if (index >= charLists.size) {
            if (current.isNotEmpty()) {
                // 生成对应的拼音序列（需要映射回来）
                results.add(
                    CompleteCandidate(
                        pinyinSequence = emptyList(), // 简化为空，实际使用中需要映射
                        charSequence = current.toList(),
                        text = current.joinToString(""),
                        score = 0.0
                    )
                )
            }
            return
        }

        // 限制分支数量，避免组合爆炸
        val chars = charLists[index].take(10)
        for (c in chars) {
            current.add(c)
            generateCombinations(charLists, index + 1, current, results)
            current.removeAt(current.size - 1)
        }
    }

    /**
     * 为候选打分
     */
    private fun scoreCandidate(
        candidate: CompleteCandidate,
        prevChar: String?
    ): Double {
        val chars = candidate.charSequence
        if (chars.isEmpty()) return 0.0

        var totalScore = 0.0

        // 单字频率分
        for (ch in chars) {
            // 查找这个字的所有拼音
            for ((pinyin, entries) in CharacterDictionary.PINYIN_TO_CHARS) {
                val entry = entries.find { it.char == ch }
                if (entry != null) {
                    totalScore += entry.weight.toDouble() / 100.0
                    break
                }
            }
        }

        // 语言模型分
        val lmScore = LanguageModel.scoreSequence(
            listOfNotNull(prevChar) + chars
        )
        totalScore += lmScore * 10.0  // 语言模型权重高于单字频率

        return totalScore
    }

    /**
     * 获取常用预测短语
     */
    fun getCommonPhrases(prevChar: String): List<String> {
        val followers = LanguageModel.BIGRAM_FREQUENCIES[prevChar] ?: return emptyList()
        return followers.entries
            .sortedByDescending { it.value }
            .map { prevChar + it.key }
    }
}
