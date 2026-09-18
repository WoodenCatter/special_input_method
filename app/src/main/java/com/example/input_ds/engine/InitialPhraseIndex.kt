package com.example.input_ds.engine

import android.content.Context
import com.example.input_ds.data.CharacterDictionary

/** Small on-device fast path built from the existing common-phrase list and pinyin map. */
class InitialPhraseIndex(context: Context) {
    private val digitsByCharacter: Map<String, Set<Char>> = buildMap {
        context.assets.open(PINYIN_MAP_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0 || separator >= line.lastIndex) return@forEach
                val initial = line[0].lowercaseChar()
                val digit = initial.toT9Digit() ?: return@forEach
                line.substring(separator + 1).split(',').forEach { value ->
                    val character = value.trim()
                    if (character.isNotEmpty()) {
                        put(character, getOrElse(character) { emptySet() } + digit)
                    }
                }
            }
        }
    }

    fun lookup(blocksKey: String, limit: Int = 16): List<String> =
        CharacterDictionary.COMMON_PHRASES.asSequence()
            .filter { phrase -> matches(phrase, blocksKey) }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun matches(text: String, blocksKey: String): Boolean {
        val characters = text.codePointStrings()
        return characters.size == blocksKey.length &&
            characters.indices.all { index -> blocksKey[index] in digitsByCharacter[characters[index]].orEmpty() }
    }

    private fun String.codePointStrings(): List<String> = buildList {
        var offset = 0
        while (offset < this@codePointStrings.length) {
            val codePoint = this@codePointStrings.codePointAt(offset)
            add(String(Character.toChars(codePoint)))
            offset += Character.charCount(codePoint)
        }
    }

    private fun Char.toT9Digit(): Char? = when (this) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> null
    }

    private companion object {
        const val PINYIN_MAP_ASSET = "pinyin_map.txt"
    }
}
