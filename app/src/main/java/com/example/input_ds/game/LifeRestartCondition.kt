package com.example.input_ds.game

/**
 * Exact Kotlin port of `VickScarlet/remake` (`packages/condition/index.ts`, MIT License).
 * Evaluates LifeRestart boolean expressions over flat game/profile properties, supporting
 * parentheses, `&`, `|`, numeric comparisons (`>`, `<`, `>=`, `<=`, `=`, `!=`), and
 * array/set inclusion (`?`) / exclusion (`!`).
 */
object LifeRestartCondition {
    private sealed interface Node {
        data class Atom(val text: String) : Node
        data class Group(val items: MutableList<Node> = mutableListOf()) : Node
    }

    private val operators = charArrayOf('>', '<', '!', '?', '=')

    fun check(properties: Map<String, Any?>, expression: String?): Boolean {
        if (expression.isNullOrBlank()) return true
        val root = parse(expression)
        return eval(properties, root)
    }

    private fun parse(condition: String): Node.Group {
        val root = Node.Group()
        val stack = ArrayDeque<Node.Group>()
        stack.addFirst(root)
        var cursor = 0
        val len = condition.length

        fun flush(endExclusive: Int) {
            if (endExclusive > cursor) {
                val token = condition.substring(cursor, endExclusive).trim()
                if (token.isNotEmpty()) {
                    stack.firstOrNull()?.items?.add(Node.Atom(token))
                }
            }
            cursor = endExclusive
        }

        for (i in 0 until len) {
            when (condition[i]) {
                ' ' -> continue
                '(' -> {
                    flush(i)
                    cursor = i + 1
                    val sub = Node.Group()
                    stack.firstOrNull()?.items?.add(sub)
                    stack.addFirst(sub)
                }
                ')' -> {
                    flush(i)
                    cursor = i + 1
                    if (stack.size > 1) stack.removeFirst()
                }
                '|', '&' -> {
                    flush(i)
                    flush(i + 1)
                }
            }
        }
        flush(len)
        return root
    }

    private fun eval(properties: Map<String, Any?>, node: Node): Boolean {
        return when (node) {
            is Node.Atom -> checkAtom(properties, node.text)
            is Node.Group -> {
                val items = node.items
                if (items.isEmpty()) return true
                if (items.size == 1) return eval(properties, items[0])

                var result = eval(properties, items[0])
                var i = 1
                while (i < items.size) {
                    val op = (items[i] as? Node.Atom)?.text ?: return false
                    val next = items.getOrNull(i + 1) ?: return false
                    when (op) {
                        "&" -> if (result) result = eval(properties, next)
                        "|" -> {
                            if (result) return true
                            result = eval(properties, next)
                        }
                        else -> return false
                    }
                    i += 2
                }
                result
            }
        }
    }

    private fun checkAtom(properties: Map<String, Any?>, atom: String): Boolean {
        val opIdx = atom.indexOfAny(operators)
        if (opIdx <= 0) return false

        val prop = atom.substring(0, opIdx).trim()
        val isDouble = opIdx + 1 < atom.length && atom[opIdx + 1] == '='
        val symbolEnd = opIdx + if (isDouble) 2 else 1
        val symbol = atom.substring(opIdx, symbolEnd)
        val rawValue = atom.substring(symbolEnd).trim()

        val propData = properties[prop]
        val listValue: List<Double>? = if (rawValue.startsWith("[")) {
            parseNumberArray(rawValue)
        } else {
            null
        }
        val numValue: Double? = if (listValue == null) rawValue.toDoubleOrNull() else null

        return when (symbol) {
            ">" -> numValue != null && asDouble(propData) > numValue
            "<" -> numValue != null && asDouble(propData) < numValue
            ">=" -> numValue != null && asDouble(propData) >= numValue
            "<=" -> numValue != null && asDouble(propData) <= numValue
            "=" -> {
                if (numValue == null) return false
                when (propData) {
                    is Set<*> -> containsNumber(propData, numValue)
                    is Collection<*> -> containsNumber(propData, numValue)
                    else -> asDouble(propData) == numValue
                }
            }
            "!=" -> {
                if (numValue == null) return false
                when (propData) {
                    is Set<*> -> !containsNumber(propData, numValue)
                    is Collection<*> -> !containsNumber(propData, numValue)
                    else -> asDouble(propData) != numValue
                }
            }
            "?" -> {
                if (listValue == null) return false
                when (propData) {
                    is Collection<*> -> listValue.any { containsNumber(propData, it) }
                    else -> {
                        val v = asNullableDouble(propData) ?: return false
                        listValue.any { it == v }
                    }
                }
            }
            "!" -> {
                if (listValue == null) return true
                when (propData) {
                    is Collection<*> -> listValue.none { containsNumber(propData, it) }
                    else -> {
                        val v = asNullableDouble(propData) ?: return true
                        listValue.none { it == v }
                    }
                }
            }
            else -> false
        }
    }

    private fun parseNumberArray(raw: String): List<Double>? {
        val inner = raw.trim()
        if (!inner.startsWith("[") || !inner.endsWith("]")) return null
        val body = inner.substring(1, inner.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { it.trim().toDoubleOrNull() }
    }

    private fun asDouble(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        else -> 0.0
    }

    private fun asNullableDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        else -> null
    }

    private fun containsNumber(col: Collection<*>, target: Double): Boolean {
        val intTarget = target.toInt()
        if (target == intTarget.toDouble() && col.contains(intTarget)) return true
        return col.any { (it as? Number)?.toDouble() == target }
    }
}
