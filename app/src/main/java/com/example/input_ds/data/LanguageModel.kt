package com.example.input_ds.data

/**
 * 简单的中文语言模型
 *
 * 使用二元语法（Bigram）统计常见中文词组的共现概率，
 * 用于在后端拼音恢复完成后对候选词进行排序。
 */
object LanguageModel {

    /**
     * 二元组频率表：给定前一个字，后续字的频率权重
     * key: 前一个汉字, value: Map<后一个汉字, 权重>
     */
    val BIGRAM_FREQUENCIES: Map<String, Map<String, Int>> = mapOf(
        "你" to mapOf("好" to 100, "们" to 70, "的" to 60, "想" to 50, "说" to 40),
        "我" to mapOf("们" to 100, "的" to 90, "想" to 85, "要" to 80, "是" to 75, "会" to 70, "不" to 65, "就" to 60, "在" to 55, "很" to 50),
        "他" to mapOf("们" to 100, "的" to 90, "是" to 80, "说" to 75, "想" to 65, "不" to 60),
        "她" to mapOf("的" to 100, "说" to 90, "是" to 80, "很" to 75),
        "不" to mapOf("是" to 100, "要" to 95, "会" to 90, "能" to 85, "可" to 80, "想" to 75, "知" to 70, "太" to 65, "错" to 60, "好" to 55),
        "一" to mapOf("个" to 100, "些" to 90, "种" to 85, "起" to 80, "定" to 75, "样" to 70, "点" to 65, "下" to 60, "直" to 55),
        "是" to mapOf("的" to 100, "我" to 85, "不" to 80, "一" to 75, "在" to 70, "很" to 65, "什" to 60, "他" to 55),
        "的" to mapOf("人" to 100, "时" to 90, "事" to 85, "地" to 80, "东" to 75, "确" to 70),
        "在" to mapOf("这" to 100, "那" to 90, "家" to 80, "学" to 75, "地" to 70),
        "有" to mapOf("人" to 100, "一" to 90, "的" to 85, "些" to 80, "用" to 75, "趣" to 70),
        "人" to mapOf("们" to 100, "民" to 90, "生" to 85, "家" to 80, "才" to 70),
        "大" to mapOf("家" to 100, "的" to 90, "人" to 80, "学" to 75, "会" to 70, "门" to 65),
        "这" to mapOf("个" to 100, "样" to 95, "些" to 90, "里" to 85, "是" to 80, "么" to 75),
        "那" to mapOf("个" to 100, "么" to 95, "些" to 90, "样" to 85, "里" to 80, "是" to 70),
        "好" to mapOf("的" to 100, "像" to 90, "吃" to 85, "看" to 80, "玩" to 75, "听" to 70, "用" to 65),
        "要" to mapOf("求" to 100, "是" to 90, "做" to 85, "不" to 80, "去" to 75, "吃" to 70, "学" to 65),
        "会" to mapOf("议" to 100, "有" to 90, "说" to 85, "不" to 80, "员" to 70, "做" to 65),
        "过" to mapOf("来" to 100, "去" to 95, "程" to 85, "了" to 80, "得" to 70),
        "来" to mapOf("到" to 100, "了" to 95, "说" to 85, "看" to 80, "自" to 70),
        "就" to mapOf("是" to 100, "要" to 90, "会" to 85, "能" to 80, "可" to 75, "说" to 70, "在" to 65),
        "时" to mapOf("间" to 100, "候" to 90, "代" to 80, "常" to 70),
        "上" to mapOf("面" to 100, "午" to 90, "海" to 85, "帝" to 75, "次" to 70),
        "下" to mapOf("午" to 100, "面" to 90, "次" to 85, "来" to 80, "去" to 75),
        "们" to mapOf("的" to 100, "都" to 90, "要" to 80, "是" to 70),
        "么" to mapOf("样" to 100, "事" to 85),
        "什" to mapOf("么" to 100),
        "很" to mapOf("好" to 100, "多" to 90, "大" to 85, "高" to 80, "难" to 75, "快" to 70),
        "想" to mapOf("要" to 100, "到" to 90, "法" to 85, "起" to 80, "不" to 70),
        "可" to mapOf("以" to 100, "能" to 95, "是" to 85, "爱" to 75, "怕" to 70),
        "以" to mapOf("为" to 100, "后" to 90, "前" to 85, "上" to 80),
        "没" to mapOf("有" to 100, "关" to 85, "想" to 75, "办" to 70),
        "为" to mapOf("什" to 100, "了" to 90, "人" to 85, "何" to 75),
        "对" to mapOf("不" to 100, "于" to 90, "象" to 80, "策" to 70),
        "今" to mapOf("天" to 100, "年" to 90, "晚" to 85),
        "天" to mapOf("气" to 100, "天" to 90, "空" to 85, "下" to 80, "亮" to 70),
        "谢" to mapOf("谢" to 100),
        "吃" to mapOf("饭" to 100, "的" to 85, "药" to 75),
        "喝" to mapOf("水" to 100, "酒" to 85),
        "说" to mapOf("话" to 100, "的" to 90, "明" to 85, "法" to 75, "不" to 65),
        "知" to mapOf("道" to 100, "识" to 90),
        "做" to mapOf("事" to 100, "法" to 90, "到" to 85, "人" to 75),
        "能" to mapOf("力" to 100, "够" to 95, "不" to 80),
        "家" to mapOf("里" to 100, "庭" to 90, "人" to 85, "长" to 75),
        "里" to mapOf("面" to 100, "的" to 90, "程" to 80),
        "出" to mapOf("来" to 100, "去" to 95, "现" to 90, "生" to 80),
        "看" to mapOf("到" to 100, "见" to 95, "不" to 80, "书" to 70),
        "见" to mapOf("面" to 100, "到" to 95, "过" to 85)
    )

    /**
     * 给定前一个字，返回后续字的排序权重
     * @param prevChar 前一个汉字（null 表示首字）
     * @param candidate 候选汉字
     * @return 该候选的权重分（0-100）
     */
    fun getBigramScore(prevChar: String?, candidate: String): Int {
        if (prevChar == null) return 50 // 首字无上下文
        val followers = BIGRAM_FREQUENCIES[prevChar] ?: return 30
        return followers[candidate] ?: 10 // 未知组合给低分
    }

    /**
     * 评估一个汉字序列的整体语言模型得分
     */
    fun scoreSequence(chars: List<String>): Double {
        if (chars.isEmpty()) return 0.0
        var score = 1.0
        // 为简单计，取平均分
        var totalScore = 0
        for (i in chars.indices) {
            val prev = if (i > 0) chars[i - 1] else null
            totalScore += getBigramScore(prev, chars[i])
        }
        return totalScore.toDouble() / chars.size
    }
}
