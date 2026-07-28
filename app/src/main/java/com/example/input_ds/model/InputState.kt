package com.example.input_ds.model

/**
 * 输入法状态机定义
 *
 * 对应 PRD 中描述的三级交互：
 * Level 1 - 一级扫描：选择字母块
 * Level 2 - 二级选择：选择具体拼音字母
 * Level 3 - 三级选择：选择汉字
 * PREDICTION - 预测词选择
 */
enum class InputPhase {
    /** 一级扫描：八个字母块循环高亮 */
    LEVEL_1_SCANNING,

    /** 二级选择：选拼音 → 咬牙后焦点移到汉字 */
    LEVEL_2_LETTER_SELECT,

    /** 三级选择：选汉字（预留，当前合并到二级内部处理） */
    LEVEL_3_CHAR_SELECT,

    /** 预测词选择 */
    PREDICTION
}

/**
 * 扫描侧（左侧或右侧）
 */
enum class ScanSide {
    LEFT, RIGHT
}

/**
 * 控制信号（模拟 BCI 信号）
 */
enum class ControlSignal {
    /** 左看 - 选择左侧高亮项 / 切换到左侧 */
    LEFT_LOOK,

    /** 右看 - 选择右侧高亮项 / 切换到右侧 */
    RIGHT_LOOK,

    /** 咬牙 - 确认选择 */
    BITE
}

/**
 * 输入法完整 UI 状态
 */
data class InputState(
    // === 扫描状态 ===
    val phase: InputPhase = InputPhase.LEVEL_1_SCANNING,
    val scanSide: ScanSide = ScanSide.LEFT,
    val highlightedBlockIndex: Int = 0,     // 当前高亮的块索引 (0-3 左侧 or 右侧)
    val highlightedLetterIndex: Int = 0,    // 二级：高亮字母索引
    val highlightedCharIndex: Int = 0,      // 三级：高亮汉字索引
    val charScanDirection: Int = 1,         // 三级：汉字循环方向（1=右, -1=左）
    val highlightedPredictionIndex: Int = 0, // 预测词：高亮索引

    // === 已选块 ===
    val selectedBlocks: List<Int> = emptyList(),  // 用户已选择的数字块 (2-9)

    // === 拼音候选 ===
    val pinyinCandidates: List<String> = emptyList(),  // 拼音候选列表（一级实时预览）
    val currentPinyin: String = "",                    // 当前正在构建的拼音
    val pinyinCombinations: List<String> = emptyList(), // 二级可选拼音组合（如["bu","cu"]）
    val highlightedPinyinIndex: Int = 0,               // 二级：高亮拼音索引
    val isCharFocused: Boolean = false,                 // 二级内：false=选拼音, true=选汉字

    // === 汉字候选 ===
    val charCandidates: List<String> = emptyList(),    // 汉字候选列表
    val selectedLetters: List<Char> = emptyList(),     // 二级选择的字母（已废弃，保留兼容）

    // === 输出 ===
    val outputText: String = "",                       // 已输出的文本
    val currentChar: String = "",                      // 当前正在输入的汉字（已确认）

    // === 预测 ===
    val predictionCandidates: List<String> = emptyList(),

    // === 系统 ===
    val scanIntervalMs: Long = 1200L                   // 扫描间隔（毫秒）
)
