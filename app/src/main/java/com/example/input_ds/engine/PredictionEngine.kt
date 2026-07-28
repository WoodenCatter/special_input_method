package com.example.input_ds.engine

import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LanguageModel
import com.example.input_ds.data.UserDictionary

/**
 * 预测引擎（增强版）
 *
 * - 双字上下文（Bigram + 仿Trigram 连贯性评分）
 * - 用户词典加权（输入过的词优先）
 * - 常用短语兜底
 */
class PredictionEngine {

    /**
     * 根据上下文预测后续词
     * @param context 已输出文本的最后 1~2 字
     */
    fun predictNext(context: String): List<String> {
        if (context.isEmpty()) return CharacterDictionary.COMMON_PHRASES.take(12)

        val lastChar = context.last().toString()
        val prev2 = if (context.length >= 2) context[context.length - 2].toString() else null
        val candidates = mutableSetOf<String>()

        // 1. Bigram：基于最后一字的后继字
        LanguageModel.BIGRAM_FREQUENCIES[lastChar]?.forEach { (next, _) ->
            candidates.add(lastChar + next)
        }

        // 2. 常用短语匹配
        for (phrase in CharacterDictionary.COMMON_PHRASES) {
            if (phrase.startsWith(lastChar) && phrase.length > lastChar.length) {
                candidates.add(phrase)
            }
        }

        // 3. 用户词典补充（用户之前输入过的，以 context 开头的词）
        UserDictionary.getUserWords(context).take(15).forEach { (word, _) ->
            if (word.length > lastChar.length) candidates.add(word)
        }
        // 也搜以 lastChar 开头且为用户常用词的（匹配双字词，如"怎么了"以"怎"开头）
        if (lastChar.length == 1) {
            UserDictionary.getUserWords(lastChar).take(10).forEach { (word, _) ->
                if (word.length > 1) candidates.add(word)
            }
        }

        // 去重 + 打分排序
        return candidates
            .toList()
            .sortedByDescending { scorePrediction(it, lastChar, prev2) }
            .take(12)
    }

    /**
     * 预测词打分（双字上下文 + 用户词典加权）
     */
    private fun scorePrediction(phrase: String, lastChar: String, prev2: String?): Double {
        var score = 0.0

        // 真 Trigram / Bigram 上下文分
        if (phrase.length >= 2) {
            val nextChar = phrase.substring(lastChar.length, minOf(lastChar.length + 1, phrase.length))
            if (prev2 != null) {
                score += LanguageModel.getTrigramScore(prev2, lastChar, nextChar) * 2.0
            } else {
                score += LanguageModel.getBigramScore(lastChar, nextChar) * 1.5
            }
        }

        // 用户词典加权
        val userScore = UserDictionary.getScore(phrase)
        if (userScore > 0) score += userScore * 15.0

        // 短词偏好
        score += 8.0 / phrase.length

        // 常用短语额外加分
        if (phrase in CharacterDictionary.COMMON_PHRASES) score += 3.0

        return score
    }
}
