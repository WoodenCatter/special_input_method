package com.example.input_ds.engine

enum class PredictionSource {
    USER,
    LOCAL,
    RIME
}

data class PredictionCandidate(
    val text: String,
    val source: PredictionSource,
    val score: Double = 0.0
)

interface PredictionProvider {
    /** [PredictionCandidate.text] is always a suffix to append to [context]. */
    suspend fun predict(context: String, limit: Int = 12): List<PredictionCandidate>
}

internal object PredictionText {
    fun takeLastCodePoints(text: String, count: Int): String {
        if (text.isEmpty() || count <= 0) return ""
        val codePointCount = text.codePointCount(0, text.length)
        if (codePointCount <= count) return text
        val start = text.offsetByCodePoints(0, codePointCount - count)
        return text.substring(start)
    }

    fun codePointStrings(text: String): List<String> {
        val result = ArrayList<String>(text.codePointCount(0, text.length))
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            result += String(Character.toChars(codePoint))
            offset += Character.charCount(codePoint)
        }
        return result
    }

    fun removeCompleteContextPrefix(context: String, value: String): String {
        val trimmed = value.trim()
        return if (context.isNotEmpty() && trimmed.startsWith(context)) {
            trimmed.removePrefix(context)
        } else {
            trimmed
        }
    }

    fun removeLocalContextPrefix(context: String, value: String): String {
        var suffix = removeCompleteContextPrefix(context, value)
        if (suffix != value.trim()) return suffix

        val contextCodePoints = codePointStrings(context)
        for (length in minOf(2, contextCodePoints.size) downTo 1) {
            val prefix = contextCodePoints.takeLast(length).joinToString("")
            if (suffix.startsWith(prefix)) {
                suffix = suffix.removePrefix(prefix)
                break
            }
        }
        return suffix
    }

    fun isDisplayableSuffix(text: String, maxCodePoints: Int = 8): Boolean {
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        val count = text.codePointCount(0, text.length)
        if (count !in 1..maxCodePoints) return false

        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }
}
