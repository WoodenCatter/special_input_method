package com.example.input_ds.engine

import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LanguageModel

/**
 * 预测引擎
 *
 * 根据已确认的汉字上下文，预测最可能的后续词语。
 * 对应 PRD 中的"预测词选择"功能。
 */
class PredictionEngine {

    /**
     * 根据已输入的最后一个字预测后续词
     *
     * @param lastChar 最后一个已确认的汉字
     * @return 预测词列表（按可能概率排序）
     */
    fun predictNext(lastChar: String): List<String> {
        val predictions = mutableListOf<String>()

        // 1. 基于二元组的后续字
        val followers = LanguageModel.BIGRAM_FREQUENCIES[lastChar]
        if (followers != null) {
            for ((nextChar, score) in followers) {
                predictions.add(lastChar + nextChar)
            }
        }

        // 2. 添加常用短语
        for (phrase in CharacterDictionary.COMMON_PHRASES) {
            if (phrase.startsWith(lastChar) && phrase.length > lastChar.length) {
                if (phrase !in predictions) {
                    predictions.add(phrase)
                }
            }
        }

        // 3. 如果上下文包含多个字，尝试多字预测
        // (当前版本简化为仅基于最后一个字)

        // 去重并按频率排序
        return predictions
            .distinct()
            .sortedByDescending { scorePrediction(it, lastChar) }
            .take(12)
    }

    /**
     * 获取系统初始状态的常用语预测
     */
    fun getInitialPredictions(): List<String> {
        return CharacterDictionary.COMMON_PHRASES.take(10)
    }

    /**
     * 给预测词打分
     */
    private fun scorePrediction(phrase: String, lastChar: String): Double {
        var score = 0.0

        // 检查是否在二元组表中
        val followers = LanguageModel.BIGRAM_FREQUENCIES[lastChar]
        if (followers != null && phrase.length >= 2) {
            val secondChar = phrase.substring(1, 2)
            val bigramScore = followers[secondChar] ?: 0
            score += bigramScore * 2.0
        }

        // 短词偏好（通常预测词不该太长）
        score += (10.0 / phrase.length)

        return score
    }
}
