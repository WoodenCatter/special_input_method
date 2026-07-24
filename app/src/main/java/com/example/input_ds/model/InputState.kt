package com.example.input_ds.model

/** 输入法五阶段交互状态。 */
enum class InputPhase {
    /** 两侧按键自动轮转，用户持续输入拼音按键。 */
    PINYIN_KEY_INPUT,

    /** 从合法拼音组合中选择一个拼音。 */
    PINYIN_SELECTION,

    /** 按行选择候选汉字。 */
    CHARACTER_SELECTION,

    /** 选择以当前汉字开头的词语。 */
    WORD_SELECTION,

    /** 选择大模型预测句子。 */
    SENTENCE_SELECTION
}

enum class ScanSide {
    LEFT, RIGHT
}

/** 模拟 BCI 输入信号。 */
enum class ControlSignal {
    LEFT_LOOK,
    RIGHT_LOOK,
    BITE
}

/** Compose 页面所需的完整、不可变状态。 */
data class InputState(
    val phase: InputPhase = InputPhase.PINYIN_KEY_INPUT,

    // 两侧自动扫描
    val scanSide: ScanSide = ScanSide.LEFT,
    val highlightedSideKeyIndex: Int = 0,
    val selectedBlocks: List<Int> = emptyList(),

    // 第二块：拼音选择（末项固定为“返回选择拼音”）
    val pinyinCombinations: List<String> = emptyList(),
    val highlightedPinyinOptionIndex: Int = 0,
    val currentPinyin: String = "",

    // 第三块：候选字，每行四个字；选项位置 0..6 对应
    // 上一行、返回、字1、字2、字3、字4、下一行。
    val charCandidates: List<String> = emptyList(),
    val charRowIndex: Int = 0,
    val highlightedCharOptionIndex: Int = 0,
    val selectedCharacter: String = "",

    // 第四、五块当前预留真实预测接口，因此候选暂时只有已选内容本身。
    val wordCandidates: List<String> = emptyList(),
    val highlightedWordIndex: Int = 0,
    val selectedWord: String = "",
    val sentenceCandidates: List<String> = emptyList(),
    val highlightedSentenceIndex: Int = 0,

    val outputText: String = "",
    val scanIntervalMs: Long = 1200L
)
