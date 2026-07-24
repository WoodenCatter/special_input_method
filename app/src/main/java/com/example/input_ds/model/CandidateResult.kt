package com.example.input_ds.model

/**
 * 候选结果数据类
 */

/** 拼音候选：字母串 + 是否完整 */
data class PinyinCandidate(
    val letters: String,
    val isComplete: Boolean  // 是否构成完整拼音音节
)

/** 汉字候选 */
data class CharCandidate(
    val char: String,
    val pinyin: String,
    val score: Double
)

/** 拼音切分结果 */
data class SegmentationResult(
    val pinyinSequence: List<String>,  // 如 ["ni", "hao"]
    val score: Double
)

/** 完整的输入候选（从数字到汉字） */
data class CompleteCandidate(
    val pinyinSequence: List<String>,
    val charSequence: List<String>,
    val text: String,
    val score: Double
)
