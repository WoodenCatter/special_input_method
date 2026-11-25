package com.example.input_ds.model

import com.example.input_ds.engine.PredictionCandidate

/**
 * 统一输入界面的三种使用模式（当面说话 / 文字消息 / 生活助手）
 */
sealed interface InputMode {
    val draftKey: String
    val pillLabel: String?
    val placeholder: String
    val sendLabel: String
    val usesAssistantPhrases: Boolean

    data object Talk : InputMode {
        override val draftKey: String = "talk"
        override val pillLabel: String? = null
        override val placeholder: String = "输入要说的话…"
        override val sendLabel: String = "说出"
        override val usesAssistantPhrases: Boolean = false
    }

    data class Chat(val contactId: String, val contactName: String) : InputMode {
        override val draftKey: String = "chat:$contactId"
        override val pillLabel: String = "发给 $contactName"
        override val placeholder: String = "发给${contactName}…"
        override val sendLabel: String = "发送"
        override val usesAssistantPhrases: Boolean = false
    }

    data object Assistant : InputMode {
        override val draftKey: String = "assistant"
        override val pillLabel: String = "问助手"
        override val placeholder: String = "输入问题或指令…"
        override val sendLabel: String = "发送"
        override val usesAssistantPhrases: Boolean = true
    }
}

/**
 * 输入法状态机定义
 */
enum class InputPhase {
    LEVEL_1_SCANNING,
    LEVEL_2_LETTER_SELECT,
    LEVEL_3_CHAR_SELECT,
    PREDICTION,
    INITIAL_PREDICTION
}

enum class PredictionStatus {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE
}

enum class ScanSide {
    LEFT, RIGHT
}

enum class ControlSignal {
    LEFT_LOOK,
    RIGHT_LOOK,
    BITE,
    LEFT_RIGHT,
    RIGHT_LEFT
}

data class InputState(
    // === 模式与扫描状态 ===
    val mode: InputMode = InputMode.Talk,
    val scanMode: ScanMode = ScanMode.AUTO,
    val phase: InputPhase = InputPhase.LEVEL_1_SCANNING,
    val scanSide: ScanSide = ScanSide.LEFT,
    val highlightedBlockIndex: Int = 0,
    val highlightedLetterIndex: Int = 0,
    val highlightedCharIndex: Int = 0,
    val charPage: Int = 0,
    val charScanDirection: Int = 1,
    val highlightedPredictionIndex: Int = 0,
    val flashedBlock: Int? = null,
    val dwellRevision: Int = 0,

    // === 已选块 ===
    val selectedBlocks: List<Int> = emptyList(),

    // === 拼音候选 ===
    val pinyinCandidates: List<String> = emptyList(),
    val currentPinyin: String = "",
    val pinyinCombinations: List<String> = emptyList(),
    val highlightedPinyinIndex: Int = 0,
    val isCharFocused: Boolean = false,

    // === 汉字候选 ===
    val charCandidates: List<String> = emptyList(),
    val selectedLetters: List<Char> = emptyList(),

    // === 输出 ===
    val outputText: String = "",
    val currentChar: String = "",

    // === 预测 ===
    val predictionCandidates: List<PredictionCandidate> = emptyList(),
    val predictionPage: Int = 0,
    val isCommonPhraseSelection: Boolean = false,

    // === 首字母整句 ===
    val hasInitialPredictionOption: Boolean = false,
    val initialBlocksKey: String = "",
    val initialRequestId: String? = null,
    val initialPredictionStatus: PredictionStatus = PredictionStatus.IDLE,
    val initialCandidates: List<PredictionCandidate> = emptyList(),
    val initialPage: Int = 0,
    val highlightedInitialIndex: Int = 0,
    val initialHasMore: Boolean = false,

    // === 系统 ===
    val scanIntervalMs: Long = ScanSettings.DEFAULT_INTERVAL_MS
)
