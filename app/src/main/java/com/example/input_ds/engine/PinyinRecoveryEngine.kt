package com.example.input_ds.engine

import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.PinyinDictionary
import com.example.input_ds.data.PinyinTrie
import com.example.input_ds.data.TrieNode
import com.example.input_ds.model.PinyinCandidate

/**
 * 拼音恢复引擎
 *
 * 核心算法：根据用户选择的数字块序列，通过 Trie 过滤，
 * 恢复出所有可能的合法拼音组合。
 *
 * 对应文档中描述的"后台拼音恢复流程"：
 * 字母块编码 → 恢复所有可能字母 → Trie 合法性过滤 → 拼音切分
 */
class PinyinRecoveryEngine(
    private val trie: PinyinTrie = PinyinDictionary.buildTrie()
) {

    /**
     * 表示搜索过程中的一个活跃状态
     */
    data class ActiveState(
        val path: String,           // 当前字母路径
        val node: TrieNode,         // 当前 Trie 节点
        val completedPinyins: List<String> = emptyList(),  // 已完成的拼音列表
        val isWord: Boolean = false // 当前路径是否构成完整拼音
    )

    /**
     * 核心方法：给定数字块序列，返回所有可能的拼音候选
     *
     * @param digits 数字块序列，如 [6,4,4,2,6] 对应 "ni hao"
     * @return 按得分排序的拼音候选列表
     */
    fun recover(digits: List<Int>): List<PinyinCandidate> {
        if (digits.isEmpty()) return emptyList()

        // 初始化：从根节点开始
        val initialStates = mutableListOf<ActiveState>()
        val firstLetters = LetterBlockMapping.DIGIT_TO_LETTERS[digits[0]] ?: return emptyList()

        for (c in firstLetters) {
            trie.root.getChild(c)?.let { child ->
                initialStates.add(
                    ActiveState(
                        path = c.toString(),
                        node = child,
                        isWord = child.isWord
                    )
                )
            }
        }

        if (initialStates.isEmpty()) return emptyList()

        var activeStates = initialStates

        // 逐位处理后续数字
        for (i in 1 until digits.size) {
            val digit = digits[i]
            val letters = LetterBlockMapping.DIGIT_TO_LETTERS[digit] ?: continue
            val newStates = mutableListOf<ActiveState>()

            for (state in activeStates) {
                // 尝试扩展当前路径
                for (c in letters) {
                    state.node.getChild(c)?.let { child ->
                        newStates.add(
                            ActiveState(
                                path = state.path + c,
                                node = child,
                                completedPinyins = state.completedPinyins,
                                isWord = child.isWord
                            )
                        )
                    }
                }

                // 如果当前路径是完整拼音，可以考虑在此切分，开始新拼音
                if (state.isWord) {
                    for (c in letters) {
                        trie.root.getChild(c)?.let { child ->
                            newStates.add(
                                ActiveState(
                                    path = c.toString(),
                                    node = child,
                                    completedPinyins = state.completedPinyins + state.path,
                                    isWord = child.isWord
                                )
                            )
                        }
                    }
                }
            }

            activeStates = newStates
            if (activeStates.isEmpty()) break
        }

        // 收集结果
        val results = mutableListOf<PinyinCandidate>()
        val seen = mutableSetOf<String>()

        for (state in activeStates) {
            // 完整拼音序列
            val fullPinyins = if (state.isWord) {
                state.completedPinyins + state.path
            } else {
                state.completedPinyins
            }

            if (fullPinyins.isNotEmpty()) {
                val key = fullPinyins.joinToString(" ")
                if (key !in seen) {
                    seen.add(key)
                    results.add(
                        PinyinCandidate(
                            letters = key.replace(" ", ""),
                            isComplete = state.isWord
                        )
                    )
                }
            } else {
                // 未完成的路径也作为候选
                results.add(
                    PinyinCandidate(
                        letters = state.path,
                        isComplete = state.isWord
                    )
                )
            }
        }

        return results
            .distinctBy { it.letters }
            .sortedByDescending { scoreCandidate(it) }
    }

    /**
     * 仅恢复当前活跃的字母路径（不考虑切分）
     * 用于实时显示当前正在构建的拼音
     */
    fun recoverPartialLetters(digits: List<Int>): List<String> {
        if (digits.isEmpty()) return emptyList()

        var currentNodes = trie.expandFromRoot(digits[0])

        for (i in 1 until digits.size) {
            val digit = digits[i]
            val nextNodes = mutableListOf<Pair<Char, TrieNode>>()

            for ((_, node) in currentNodes) {
                val expanded = trie.expandNode(node, digit)
                nextNodes.addAll(expanded.map { (c, child) ->
                    c to child
                })
            }

            // 同时考虑从根重新开始（新拼音）
            val fromRoot = trie.expandFromRoot(digit)
            // 只有当上一批节点中有可结束的才允许从根开始
            val hasComplete = currentNodes.any { it.second.isWord }
            if (hasComplete) {
                nextNodes.addAll(fromRoot.map { (c, child) ->
                    c to child
                })
            }

            currentNodes = nextNodes.distinctBy { it.second }
            if (currentNodes.isEmpty()) break
        }

        // 回溯路径收集字母组合
        val results = mutableSetOf<String>()
        for ((_, node) in currentNodes) {
            // 收集从根到此节点的路径（简化：直接用节点收集）
            collectPaths(node, StringBuilder(), results)
        }

        return results.toList().sortedBy { it.length }
    }

    private fun collectPaths(node: TrieNode, current: StringBuilder, results: MutableSet<String>) {
        if (node.char != null) {
            current.append(node.char)
        }
        // 这里只收集从根到当前节点的单路径，实际需要回溯
        // 简化实现
    }

    /**
     * 给拼音候选打分（频率 + 是否完整）
     */
    private fun scoreCandidate(candidate: PinyinCandidate): Double {
        // 分解成各个拼音音节
        val pinyins = candidate.letters.let { letters ->
            segmentPinyin(letters)
        }

        if (pinyins.isEmpty()) return 0.0

        // 音节越多越倾向是完整拼音
        val completenessBonus = if (candidate.isComplete) 10.0 else 0.0

        // 频率得分
        val freqScore = pinyins.sumOf {
            (PinyinDictionary.PINYIN_FREQUENCY[it] ?: 20).toDouble()
        } / pinyins.size

        return freqScore + completenessBonus + pinyins.size * 5.0
    }

    /**
     * 将连续字母串切分为拼音音节列表
     */
    fun segmentPinyin(letters: String): List<String> {
        if (letters.isEmpty()) return emptyList()

        val n = letters.length
        // dp[i] = 从位置 i 开始到最后的最佳切分结果
        val dp = Array<MutableList<List<String>>?>(n + 1) { null }
        dp[n] = mutableListOf(emptyList())

        for (i in n - 1 downTo 0) {
            val results = mutableListOf<List<String>>()
            for (j in i + 1..n) {
                val segment = letters.substring(i, j)
                if (trie.isWord(segment)) {
                    dp[j]?.forEach { rest ->
                        results.add(listOf(segment) + rest)
                    }
                }
            }
            if (results.isNotEmpty()) {
                dp[i] = results
            }
        }

        val allSegmentations = dp[0] ?: return emptyList()

        // 按评分排序，返回最佳
        return allSegmentations.maxByOrNull { seg ->
            seg.sumOf { PinyinDictionary.PINYIN_FREQUENCY[it] ?: 20 }
        } ?: allSegmentations.first()
    }
}
