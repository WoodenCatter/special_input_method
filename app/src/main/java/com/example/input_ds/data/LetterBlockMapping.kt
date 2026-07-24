package com.example.input_ds.data

/** 两侧轮转按键的类型。 */
enum class SideKeyType {
    PINYIN,
    DELETE,
    SEND,
    COMMON_PHRASES,
    ENGLISH
}

/**
 * 一个可被两侧自动扫描选中的按键。
 * 拼音键带 digit，其余按键由 type 表示动作。
 */
data class SideKey(
    val label: String,
    val type: SideKeyType,
    val digit: Int? = null
)

object LetterBlockMapping {
    val DIGIT_TO_LETTERS: Map<Int, List<Char>> = mapOf(
        2 to listOf('a', 'b', 'c'),
        3 to listOf('d', 'e', 'f'),
        4 to listOf('g', 'h', 'i'),
        5 to listOf('j', 'k', 'l'),
        6 to listOf('m', 'n', 'o'),
        7 to listOf('p', 'q', 'r', 's'),
        8 to listOf('t', 'u', 'v'),
        9 to listOf('w', 'x', 'y', 'z')
    )

    val LETTER_TO_DIGIT: Map<Char, Int> = buildMap {
        DIGIT_TO_LETTERS.forEach { (digit, letters) ->
            letters.forEach { put(it, digit) }
        }
    }

    val DIGIT_LABELS: Map<Int, String> = mapOf(
        2 to "ABC",
        3 to "DEF",
        4 to "GHI",
        5 to "JKL",
        6 to "MNO",
        7 to "PQRS",
        8 to "TUV",
        9 to "WXYZ"
    )

    val LEFT_KEYS: List<SideKey> = listOf(
        pinyinKey(2),
        pinyinKey(3),
        pinyinKey(4),
        pinyinKey(5),
        SideKey("删除", SideKeyType.DELETE),
        SideKey("常用词库", SideKeyType.COMMON_PHRASES)
    )

    val RIGHT_KEYS: List<SideKey> = listOf(
        pinyinKey(6),
        pinyinKey(7),
        pinyinKey(8),
        pinyinKey(9),
        SideKey("发送", SideKeyType.SEND),
        SideKey("英文", SideKeyType.ENGLISH)
    )

    val ALL_BLOCKS = (2..9).toList()

    fun keysFor(isLeft: Boolean): List<SideKey> = if (isLeft) LEFT_KEYS else RIGHT_KEYS

    private fun pinyinKey(digit: Int) = SideKey(
        label = DIGIT_LABELS.getValue(digit),
        type = SideKeyType.PINYIN,
        digit = digit
    )
}
