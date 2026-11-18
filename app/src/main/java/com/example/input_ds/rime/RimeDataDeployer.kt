 package com.example.input_ds.rime

import android.content.Context
import com.osfans.trime.data.opencc.OpenCCDictManager
import java.io.File

data class RimeDirectories(val shared: File, val user: File)

/** Deploys only the assets needed by the isolated prediction-only Rime schema. */
class RimeDataDeployer(private val context: Context) {
    fun prepare(): RimeDirectories {
        val root = File(context.filesDir, "rime_prediction")
        val shared = File(root, "shared")
        val user = File(root, "user")
        val marker = File(shared, ".asset-version")
        user.mkdirs()

        if (!marker.isFile || marker.readText() != ASSET_VERSION || !hasOpenCcDictionaries(shared)) {
            if (shared.exists()) shared.deleteRecursively()
            shared.mkdirs()
            copyAssetTree(ASSET_ROOT, shared)
            buildOpenCcDictionaries(shared)
            check(hasOpenCcDictionaries(shared)) { "OpenCC dictionaries could not be prepared" }
            buildContextDictionary(shared)
            marker.writeText(ASSET_VERSION)
        }
        return RimeDirectories(shared, user)
    }

    private fun buildOpenCcDictionaries(shared: File) {
        val openCcDir = File(shared, "opencc")
        OPEN_CC_DICTIONARIES.forEach { name ->
            val source = File(openCcDir, "$name.txt")
            val destination = File(openCcDir, "$name.ocd2")
            check(source.isFile && source.canRead() && source.length() > 0L) {
                "OpenCC source dictionary missing: $name"
            }
            if (!destination.isFile) {
                OpenCCDictManager.openCCDictConv(
                    source.absolutePath,
                    destination.absolutePath,
                    OpenCCDictManager.MODE_TEXT_TO_BIN
                )
            }
        }
    }

    private fun hasOpenCcDictionaries(shared: File): Boolean =
        OPEN_CC_DICTIONARIES.all { name ->
            val dictionary = File(shared, "opencc/$name.ocd2")
            dictionary.isFile && dictionary.canRead() && dictionary.length() > 0L
        }

    private fun buildContextDictionary(shared: File) {
        val s2tConfig = File(shared, "opencc/s2t.json").absolutePath
        val readings = ArrayList<ContextReading>()
        context.assets.open("pinyin_map.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size != 2) return@forEach
                val pinyin = parts[0].trim()
                if (pinyin.isEmpty()) return@forEach
                parts[1].split(',').forEachIndexed { index, rawText ->
                    val text = rawText.trim()
                    if (!isSingleHanCodePoint(text)) return@forEachIndexed
                    val weight = maxOf(100 - index * 2, 10)
                    readings += ContextReading(text, pinyin, weight)
                }
            }
        }

        val characters = readings.map { it.text }.distinct()
        check(characters.isNotEmpty()) { "Rime pinyin dictionary is empty" }
        // Newlines keep individual characters independent while loading the converter only once.
        val converted = OpenCCDictManager.openCCLineConv(characters.joinToString("\n"), s2tConfig)
            .split('\n')
        check(converted.size == characters.size) { "Unexpected OpenCC dictionary output" }
        val traditionalByCharacter = characters.zip(converted).toMap()

        val entries = LinkedHashSet<String>()
        readings.forEach { (text, pinyin, weight) ->
            entries += "$text\t$pinyin\t$weight"
            val traditional = traditionalByCharacter.getValue(text)
            if (traditional.isNotEmpty() && traditional != text) {
                entries += "$traditional\t$pinyin\t$weight"
            }
        }

        File(shared, "prediction_context.dict.yaml").bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("# Rime dictionary generated from the app's existing pinyin_map.txt")
            writer.appendLine("---")
            writer.appendLine("name: prediction_context")
            writer.appendLine("version: \"1.0\"")
            writer.appendLine("sort: by_weight")
            writer.appendLine("use_preset_vocabulary: false")
            writer.appendLine("...")
            entries.forEach { writer.appendLine(it) }
        }
    }

    private data class ContextReading(val text: String, val pinyin: String, val weight: Int)

    private fun isSingleHanCodePoint(text: String): Boolean {
        if (text.codePointCount(0, text.length) != 1) return false
        return Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private companion object {
        val OPEN_CC_DICTIONARIES = listOf("STPhrases", "STCharacters", "TSPhrases", "TSCharacters")
        const val ASSET_ROOT = "rime_prediction"
        const val ASSET_VERSION = "predict-data-1.0-2a5a2b7c-context-v1"
    }
}
