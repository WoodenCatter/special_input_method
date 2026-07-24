package com.example.input_ds.data

/**
 * 拼音前缀树（Trie）节点
 *
 * 每个节点代表一个字母，isWord 标记该节点是否构成一个完整合法拼音音节。
 * 使用 HashMap 存储子节点，支持 O(1) 查询。
 */
class TrieNode(
    val char: Char? = null,
    var isWord: Boolean = false
) {
    val children: MutableMap<Char, TrieNode> = mutableMapOf()

    /** 获取或创建子节点 */
    fun getOrCreateChild(c: Char): TrieNode {
        return children.getOrPut(c) { TrieNode(c) }
    }

    /** 查询子节点是否存在 */
    fun hasChild(c: Char): Boolean = children.containsKey(c)

    /** 获取子节点（可能为 null） */
    fun getChild(c: Char): TrieNode? = children[c]
}

/**
 * 拼音前缀树
 *
 * 存储所有合法拼音音节，支持：
 * 1. 插入拼音音节
 * 2. 查询某个字母路径是否在 Trie 中存在
 * 3. 查询某个路径是否构成完整拼音
 */
class PinyinTrie {
    val root = TrieNode()

    /** 插入一个拼音音节 */
    fun insert(pinyin: String) {
        var node = root
        for (c in pinyin) {
            node = node.getOrCreateChild(c)
        }
        node.isWord = true
    }

    /** 查询是否存在以 prefix 为前缀的路径 */
    fun existsPrefix(prefix: String): Boolean {
        var node = root
        for (c in prefix) {
            node = node.getChild(c) ?: return false
        }
        return true
    }

    /** 查询 prefix 是否是一个完整拼音音节 */
    fun isWord(prefix: String): Boolean {
        var node = root
        for (c in prefix) {
            node = node.getChild(c) ?: return false
        }
        return node.isWord
    }

    /**
     * 批量尝试扩展：给定当前节点和数字，返回所有匹配的子节点
     *
     * @param node 当前 Trie 节点
     * @param digit 输入的数字（2-9）
     * @return 该数字对应字母中，在 Trie 中存在的子节点列表，Pair(字母, 子节点)
     */
    fun expandNode(node: TrieNode, digit: Int): List<Pair<Char, TrieNode>> {
        val letters = LetterBlockMapping.DIGIT_TO_LETTERS[digit] ?: return emptyList()
        return letters.mapNotNull { c ->
            node.getChild(c)?.let { child -> c to child }
        }
    }

    /**
     * 批量尝试从根节点扩展：给定数字，返回所有匹配的根级子节点
     */
    fun expandFromRoot(digit: Int): List<Pair<Char, TrieNode>> {
        return expandNode(root, digit)
    }

    /** 获取完整拼音音节对应的 Trie 节点（从根开始） */
    fun getNode(pinyin: String): TrieNode? {
        var node = root
        for (c in pinyin) {
            node = node.getChild(c) ?: return null
        }
        return node
    }
}
