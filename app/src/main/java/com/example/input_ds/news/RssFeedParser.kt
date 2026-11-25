package com.example.input_ds.news

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

object RssFeedParser {
    private const val MAX_ARTICLES = 20
    private const val MAX_PREVIEW_CHARS = 180
    private const val FALLBACK_SUMMARY = "点击「阅读全文」可加载本篇完整图文内容。"

    private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>")
    private val IMG_TAG_REGEX = Regex("(?is)<img\\b[^>]*>")
    private val IMG_SRC_REGEX = Regex("(?i)\\b(?:data-original|data-src|src)\\s*=\\s*[\"']([^\"']+)[\"']")
    private val IMG_ALT_REGEX = Regex("(?i)\\balt\\s*=\\s*[\"']([^\"']*)[\"']")
    private val BLOCK_BREAK_REGEX = Regex("(?i)</?(p|div|br|li|h[1-6]|tr|blockquote)[^>]*>")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val READ_MORE_REGEX = Regex("(?:查看全文|阅读全文|点击查看全文|原文链接)\\s*$")
    private val MULTI_SPACE_REGEX = Regex("[\\t\\x0B\\f\\r ]+")
    private val MULTI_NEWLINE_REGEX = Regex("\\n{2,}")
    private val INVALID_XML_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(x?[0-9a-fA-F]+);")

    private val IGNORED_IMAGE_HINTS = listOf(
        "logo", "icon", "favicon", "qrcode", "ewm", "wechat", "weixin",
        "avatar", "share", "btn_", "button", "placeholder", "loading", "spacer", "1x1", ".svg"
    )

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "middot" to "·"
    )

    private val RFC822_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss"
    )

    private val ISO8601_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    fun parse(
        xml: String,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<NewsArticle> {
        val sanitized = sanitizeXml(xml)
        if (sanitized.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(sanitized)))
        } catch (_: Exception) {
            return emptyList()
        }

        val root = doc.documentElement ?: return emptyList()
        val seenKeys = HashSet<String>()
        val results = mutableListOf<NewsArticle>()

        val rssItems = root.getElementsByTagName("item")
        if (rssItems.length > 0) {
            for (i in 0 until rssItems.length) {
                val element = rssItems.item(i) as? Element ?: continue
                val article = parseRssItem(element, i, nowMs, timeZone) ?: continue
                if (seenKeys.add(article.title)) {
                    results += article
                    if (results.size >= MAX_ARTICLES) break
                }
            }
            return results
        }

        val atomEntries = root.getElementsByTagName("entry")
        for (i in 0 until atomEntries.length) {
            val element = atomEntries.item(i) as? Element ?: continue
            val article = parseAtomEntry(element, i, nowMs, timeZone) ?: continue
            if (seenKeys.add(article.title)) {
                results += article
                if (results.size >= MAX_ARTICLES) break
            }
        }
        return results
    }

    private fun parseRssItem(
        item: Element,
        index: Int,
        nowMs: Long,
        timeZone: TimeZone
    ): NewsArticle? {
        val title = cleanInlineText(childText(item, "title"))
        if (title.isBlank()) return null

        val link = childText(item, "link").trim()
        val guid = childText(item, "guid").trim()
        val description = childText(item, "description")
        val contentEncoded = childText(item, "content:encoded").ifBlank { childText(item, "encoded") }
        val chosenHtml = chooseBestHtml(description, contentEncoded)

        val blocks = extractBlocksFromHtml(chosenHtml, baseUrl = link)
        val extraImages = extractEnclosureImages(item, baseUrl = link)
        val mergedImages = (blocks.filterIsInstance<NewsContentBlock.Image>().map { it.url } + extraImages).distinct()
        val fullPlainText = blocks.filterIsInstance<NewsContentBlock.Paragraph>()
            .joinToString("\n") { it.text }
            .ifBlank { cleanHtmlToPlainText(chosenHtml) }

        val preview = formatSummary(fullPlainText, title)
        val isRichAlready = fullPlainText.length >= 280 || (fullPlainText.length >= 160 && mergedImages.isNotEmpty())

        val rawDate = childText(item, "pubDate").ifBlank { childText(item, "dc:date") }.trim()
        val publishedMs = parseTimestampMs(rawDate)
        val publishedText = formatPublishedTime(publishedMs, rawDate, nowMs, timeZone)

        return NewsArticle(
            id = guid.ifBlank { link }.ifBlank { "rss-$index-${title.hashCode()}" },
            title = title,
            summary = preview,
            publishedAtText = publishedText,
            publishedAtMs = publishedMs,
            link = link,
            fullText = fullPlainText.ifBlank { preview },
            imageUrls = mergedImages,
            fullContentLoaded = isRichAlready
        )
    }

    private fun parseAtomEntry(
        entry: Element,
        index: Int,
        nowMs: Long,
        timeZone: TimeZone
    ): NewsArticle? {
        val title = cleanInlineText(childText(entry, "title"))
        if (title.isBlank()) return null

        val link = atomLink(entry)
        val id = childText(entry, "id").trim().ifBlank { link }.ifBlank { "atom-$index-${title.hashCode()}" }
        val summaryRaw = childText(entry, "summary")
        val contentRaw = childText(entry, "content")
        val chosenHtml = chooseBestHtml(summaryRaw, contentRaw)

        val blocks = extractBlocksFromHtml(chosenHtml, baseUrl = link)
        val images = blocks.filterIsInstance<NewsContentBlock.Image>().map { it.url }.distinct()
        val fullPlainText = blocks.filterIsInstance<NewsContentBlock.Paragraph>()
            .joinToString("\n") { it.text }
            .ifBlank { cleanHtmlToPlainText(chosenHtml) }
        val preview = formatSummary(fullPlainText, title)
        val isRichAlready = fullPlainText.length >= 280 || (fullPlainText.length >= 160 && images.isNotEmpty())

        val rawDate = childText(entry, "published").ifBlank { childText(entry, "updated") }.trim()
        val publishedMs = parseTimestampMs(rawDate)
        val publishedText = formatPublishedTime(publishedMs, rawDate, nowMs, timeZone)

        return NewsArticle(
            id = id,
            title = title,
            summary = preview,
            publishedAtText = publishedText,
            publishedAtMs = publishedMs,
            link = link,
            fullText = fullPlainText.ifBlank { preview },
            imageUrls = images,
            fullContentLoaded = isRichAlready
        )
    }

    private fun chooseBestHtml(primary: String, secondary: String): String {
        val primaryLen = cleanHtmlToPlainText(primary).length
        val secondaryLen = cleanHtmlToPlainText(secondary).length
        return if (secondaryLen > primaryLen) secondary else primary
    }

    internal fun extractBlocksFromHtml(html: String, baseUrl: String = ""): List<NewsContentBlock> {
        if (html.isBlank()) return emptyList()
        val noScripts = SCRIPT_STYLE_REGEX.replace(html, " ")
        val blocks = mutableListOf<NewsContentBlock>()
        val seenImages = HashSet<String>()
        var cursor = 0

        for (match in IMG_TAG_REGEX.findAll(noScripts)) {
            val before = noScripts.substring(cursor, match.range.first)
            appendParagraphBlocks(before, blocks)

            val tag = match.value
            val rawSrc = IMG_SRC_REGEX.find(tag)?.groupValues?.getOrNull(1).orEmpty()
            val normalizedUrl = normalizeImageUrl(rawSrc, baseUrl)
            if (normalizedUrl != null && seenImages.add(normalizedUrl)) {
                val alt = cleanInlineText(IMG_ALT_REGEX.find(tag)?.groupValues?.getOrNull(1).orEmpty())
                    .takeIf { it.length in 2..60 }
                    .orEmpty()
                blocks += NewsContentBlock.Image(url = normalizedUrl, caption = alt)
            }
            cursor = match.range.last + 1
        }
        if (cursor < noScripts.length) {
            appendParagraphBlocks(noScripts.substring(cursor), blocks)
        }
        return blocks
    }

    private fun appendParagraphBlocks(fragment: String, out: MutableList<NewsContentBlock>) {
        val cleaned = cleanHtmlToPlainText(fragment)
        if (cleaned.isBlank()) return
        for (line in cleaned.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                out += NewsContentBlock.Paragraph(trimmed)
            }
        }
    }

    internal fun normalizeImageUrl(rawSrc: String, baseUrl: String): String? {
        val trimmed = decodeHtmlEntities(rawSrc.trim())
        if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return null
        val resolved = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.replaceFirst("http://", "https://", ignoreCase = true)
            trimmed.startsWith("//") -> "https:$trimmed"
            baseUrl.isNotBlank() -> runCatching {
                URI(baseUrl).resolve(trimmed).toString()
            }.getOrNull() ?: return null
            else -> return null
        }
        val lower = resolved.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        if (IGNORED_IMAGE_HINTS.any { lower.contains(it) }) return null
        return resolved
    }

    private fun extractEnclosureImages(item: Element, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        val children = item.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            val tag = el.nodeName.lowercase(Locale.ROOT)
            if (tag == "enclosure" || tag == "media:content" || tag == "media:thumbnail") {
                val type = el.getAttribute("type").lowercase(Locale.ROOT)
                val url = el.getAttribute("url").trim()
                if (url.isNotEmpty() && (type.isEmpty() || type.startsWith("image/") || tag == "media:thumbnail")) {
                    normalizeImageUrl(url, baseUrl)?.let(urls::add)
                }
            }
        }
        return urls
    }

    internal fun cleanInlineText(raw: String): String =
        decodeHtmlEntities(HTML_TAG_REGEX.replace(raw, " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun cleanHtmlToPlainText(raw: String): String {
        if (raw.isBlank()) return ""
        val noScripts = SCRIPT_STYLE_REGEX.replace(raw, " ")
        val withBreaks = BLOCK_BREAK_REGEX.replace(noScripts, "\n")
        val stripped = HTML_TAG_REGEX.replace(withBreaks, " ")
        val decoded = decodeHtmlEntities(stripped)
        val normalizedLines = decoded.lines()
            .map { MULTI_SPACE_REGEX.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val withoutBoilerplate = READ_MORE_REGEX.replace(normalizedLines, "").trim()
        return MULTI_NEWLINE_REGEX.replace(withoutBoilerplate, "\n").trim()
    }

    internal fun formatSummary(cleanedBody: String, title: String): String {
        val trimmed = cleanedBody.trim()
        if (trimmed.isBlank()) return FALLBACK_SUMMARY
        if (trimmed == title || (trimmed.length < 16 && !trimmed.contains('。'))) {
            return "$trimmed\n$FALLBACK_SUMMARY"
        }
        return truncateAtSentence(trimmed.replace('\n', ' '), MAX_PREVIEW_CHARS)
    }

    internal fun truncateAtSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val slice = text.substring(0, maxChars)
        val minKeep = (maxChars * 0.55f).toInt()
        val sentenceCut = maxOf(
            slice.lastIndexOf('。'),
            slice.lastIndexOf('！'),
            slice.lastIndexOf('？'),
            slice.lastIndexOf('；')
        )
        if (sentenceCut >= minKeep) {
            return slice.substring(0, sentenceCut + 1).trimEnd()
        }
        val clauseCut = maxOf(
            slice.lastIndexOf('，'),
            slice.lastIndexOf(','),
            slice.lastIndexOf('\n'),
            slice.lastIndexOf(' ')
        )
        val cut = if (clauseCut >= minKeep) clauseCut else maxChars
        return slice.substring(0, cut).trimEnd('，', ',', '、', ' ', '\n') + "……"
    }

    internal fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var text = NUMERIC_ENTITY_REGEX.replace(input) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.substring(1).toIntOrNull(16)
            } else {
                token.toIntOrNull(10)
            }
            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                match.value
            }
        }
        for ((entity, replacement) in NAMED_ENTITIES) {
            text = text.replace("&$entity;", replacement)
        }
        return text
    }

    internal fun parseTimestampMs(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (pattern in RFC822_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                if (!pattern.contains("Z") && !pattern.contains("z")) {
                    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                }
            }
            val parsed = runCatching { fmt.parse(text)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        for (pattern in ISO8601_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                if (pattern.endsWith("'Z'")) {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
            val parsed = runCatching { fmt.parse(text)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun formatPublishedTime(
        publishedMs: Long?,
        rawFallback: String,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        if (publishedMs == null) {
            return rawFallback.take(18).ifBlank { "最新资讯" }
        }
        val nowCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val pubCal = Calendar.getInstance(timeZone).apply { timeInMillis = publishedMs }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA).apply { this.timeZone = timeZone }
        val hm = timeFmt.format(Date(publishedMs))

        val sameYear = nowCal.get(Calendar.YEAR) == pubCal.get(Calendar.YEAR)
        val dayDiff = nowCal.get(Calendar.DAY_OF_YEAR) - pubCal.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "今天 $hm"
            sameYear && dayDiff == 1 -> "昨天 $hm"
            sameYear -> "${pubCal.get(Calendar.MONTH) + 1}月${pubCal.get(Calendar.DAY_OF_MONTH)}日 $hm"
            else -> "${pubCal.get(Calendar.YEAR)}年${pubCal.get(Calendar.MONTH) + 1}月${pubCal.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    private fun sanitizeXml(raw: String): String {
        val withoutBom = raw.removePrefix("\uFEFF").trim()
        return INVALID_XML_CHARS.replace(withoutBom, "")
    }

    private fun childText(parent: Element, tagName: String): String {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals(tagName, ignoreCase = true)) {
                return node.textContent.orEmpty()
            }
        }
        return ""
    }

    private fun atomLink(entry: Element): String {
        val children = entry.childNodes
        var fallback = ""
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals("link", ignoreCase = true)) {
                val el = node as Element
                val href = el.getAttribute("href").trim()
                val rel = el.getAttribute("rel").trim()
                if (href.isNotEmpty()) {
                    if (rel.isEmpty() || rel.equals("alternate", ignoreCase = true)) return href
                    if (fallback.isEmpty()) fallback = href
                } else {
                    val text = el.textContent.orEmpty().trim()
                    if (text.isNotEmpty() && fallback.isEmpty()) fallback = text
                }
            }
        }
        return fallback
    }
}
