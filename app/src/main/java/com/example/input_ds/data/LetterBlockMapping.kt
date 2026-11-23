package com.example.input_ds.data

/**
 * T9 键盘映射：数字键 → 对应字母块，及双翼 6 块布局
 * LEFT  = [2 ABC, 3 DEF, 4 GHI, 5 JKL, DELETE 删除, BACK 返回]
 * RIGHT = [6 MNO, 7 PQRS, 8 TUV, 9 WXYZ, SEND 发送|说出, PHRASES 常用语]
 */
object LetterBlockMapping {

    /** 数字到字母块的映射 */
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

    /** 字母到数字的逆向映射 */
    val LETTER_TO_DIGIT: Map<Char, Int> = run {
        val map = mutableMapOf<Char, Int>()
        DIGIT_TO_LETTERS.forEach { (digit, letters) ->
            letters.forEach { map[it] = digit }
        }
        map
    }

    /** 功能块 ID */
    const val DELETE_BLOCK = 0
    const val SEND_BLOCK = 1
    const val BACK_BLOCK = -1
    const val PHRASES_BLOCK = -2

    /** 块的默认显示标签 */
    val DIGIT_LABELS: Map<Int, String> = mapOf(
        2 to "ABC",
        3 to "DEF",
        4 to "GHI",
        5 to "JKL",
        6 to "MNO",
        7 to "PQRS",
        8 to "TUV",
        9 to "WXYZ",
        DELETE_BLOCK to "删除",
        BACK_BLOCK to "返回",
        SEND_BLOCK to "发送",
        PHRASES_BLOCK to "常用语"
    )

    /** 左侧 6 块 */
    val LEFT_BLOCKS = listOf(2, 3, 4, 5, DELETE_BLOCK, BACK_BLOCK)

    /** 右侧 6 块 */
    val RIGHT_BLOCKS = listOf(6, 7, 8, 9, SEND_BLOCK, PHRASES_BLOCK)

    /** 所有字母块（2-9） */
    val ALL_BLOCKS = (2..9).toList()

    fun isFunctionBlock(block: Int): Boolean = block !in 2..9

    fun labelFor(block: Int, talkMode: Boolean = false): String =
        if (block == SEND_BLOCK && talkMode) "说出" else DIGIT_LABELS[block].orEmpty()
}
