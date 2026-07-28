package com.example.input_ds.data

/**
 * T9 键盘映射：数字键 → 对应字母块
 * 如文档描述：
 * 2=ABC, 3=DEF, 4=GHI, 5=JKL, 6=MNO, 7=PQRS, 8=TUV, 9=WXYZ
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

    /** 特殊块 ID */
    const val SEND_BLOCK = 1
    const val DELETE_BLOCK = 0

    /** 数字块的显示标签 */
    val DIGIT_LABELS: Map<Int, String> = mapOf(
        2 to "ABC",
        3 to "DEF",
        4 to "GHI",
        5 to "JKL",
        6 to "MNO",
        7 to "PQRS",
        8 to "TUV",
        9 to "WXYZ",
        SEND_BLOCK to "发送",
        DELETE_BLOCK to "删除"
    )

    /** 左侧五个块 */
    val LEFT_BLOCKS = listOf(2, 3, 4, 5, SEND_BLOCK)  // ABC, DEF, GHI, JKL, 发送

    /** 右侧五个块 */
    val RIGHT_BLOCKS = listOf(6, 7, 8, 9, DELETE_BLOCK)  // MNO, PQRS, TUV, WXYZ, 删除

    /** 所有字母块（2-9） */
    val ALL_BLOCKS = (2..9).toList()
}
