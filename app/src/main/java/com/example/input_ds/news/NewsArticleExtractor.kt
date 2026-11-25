package com.example.input_ds.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

fun interface NewsArticleDetailFetcher {
    suspend fun fetchDetail(article: NewsArticle): NewsArticleDetail?
}

object NewsArticleExtractor : NewsArticleDetailFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val CHARSET_META_REGEX = Regex("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)")
    private val DIV_OPEN_REGEX = Regex("(?is)<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\b(%s)\\b[^\"']*[\"'][^>]*>")
    private val DIV_TAG_REGEX = Regex("(?is)</?div\\b[^>]*>")
    private val ARTICLE_TAG_REGEX = Regex("(?is)<article\\b[^>]*>(.*?)</article>")
    private val NOISE_CONTAINER_REGEX = Regex(
        "(?is)<(?:div|section|aside|footer|nav|figure)[^>]+class\\s*=\\s*[\"'][^\"']*(?:share|related|recommend|comment|editor|copyright|qrcode|ad-|banner|tool|sidebar)[^\"']*[\"'][^>]*>.*?</(?:div|section|aside|footer|nav|figure)>"
    )
    private val EDITOR_TAIL_REGEX = Regex("^(?:责任编辑|编辑|校对|来源|图源|【编辑)[:：].*$")

    private val KNOWN_CONTENT_CLASSES = listOf(
        "left_zw",
        "content_maincontent_content",
        "post_content",
        "p_mainnew",
        "article-content",
        "article_content",
        "news_content"
    )

    override suspend fun fetchDetail(article: NewsArticle): NewsArticleDetail? {
        if (article.link.isBlank()) return detailFromCachedArticle(article)
        val html = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(article.link)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    decodeHtmlBytes(bytes, response.header("Content-Type"))
                }
            }.getOrNull()
        } ?: return detailFromCachedArticle(article)

        val extracted = extractFromHtml(html, baseUrl = article.link)
        return if (extracted != null && extracted.blocks.isNotEmpty()) {
            extracted
        } else {
            detailFromCachedArticle(article)
        }
    }

    fun detailFromCachedArticle(article: NewsArticle): NewsArticleDetail {
        val blocks = mutableListOf<NewsContentBlock>()
        val paragraphs = article.fullText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(article.summary) }

        if (article.imageUrls.isNotEmpty()) {
            // Interleave lead image cleanly right after the opening paragraph (or first if single short para).
            val first = paragraphs.firstOrNull()
            if (first != null) blocks += NewsContentBlock.Paragraph(first)
            blocks += NewsContentBlock.Image(article.imageUrls.first())
            val remainingParagraphs = paragraphs.drop(1)
            val remainingImages = article.imageUrls.drop(1)
            var imgCursor = 0
            remainingParagraphs.forEachIndexed { idx, para ->
                blocks += NewsContentBlock.Paragraph(para)
                if ((idx + 1) % 2 == 0 && imgCursor < remainingImages.size) {
                    blocks += NewsContentBlock.Image(remainingImages[imgCursor++])
                }
            }
            while (imgCursor < remainingImages.size) {
                blocks += NewsContentBlock.Image(remainingImages[imgCursor++])
            }
        } else {
            paragraphs.forEach { blocks += NewsContentBlock.Paragraph(it) }
        }

        val plain = paragraphs.joinToString("\n")
        return NewsArticleDetail(
            blocks = blocks,
            imageUrls = article.imageUrls,
            plainText = plain
        )
    }

    internal fun extractFromHtml(html: String, baseUrl: String): NewsArticleDetail? {
        if (html.isBlank()) return null

        val containerHtml = buildString {
            // ChinaNews sometimes places a lead photo in a sibling <div class="left_ph"> just before left_zw.
            val leadPhotoDiv = extractBalancedDivByClass(html, "left_ph")
            val mainDiv = KNOWN_CONTENT_CLASSES.firstNotNullOfOrNull { cls ->
                extractBalancedDivByClass(html, cls)
            }
            if (mainDiv != null) {
                if (leadPhotoDiv != null && !mainDiv.contains(leadPhotoDiv)) {
                    append(leadPhotoDiv)
                    append("\n")
                }
                append(mainDiv)
            } else {
                val articleMatch = ARTICLE_TAG_REGEX.find(html)?.groupValues?.getOrNull(1)
                if (!articleMatch.isNullOrBlank()) {
                    append(articleMatch)
                } else {
                    append(html)
                }
            }
        }

        val denoised = NOISE_CONTAINER_REGEX.replace(containerHtml, " ")
        val rawBlocks = RssFeedParser.extractBlocksFromHtml(denoised, baseUrl = baseUrl)
        val filtered = rawBlocks.filter { block ->
            when (block) {
                is NewsContentBlock.Image -> true
                is NewsContentBlock.Paragraph -> {
                    val t = block.text.trim()
                    t.length >= 6 &&
                        !EDITOR_TAIL_REGEX.matches(t) &&
                        !t.startsWith("©") &&
                        !t.contains("版权所有") &&
                        !t.contains("扫一扫") &&
                        !t.contains("下载客户端")
                }
            }
        }
        val paragraphs = filtered.filterIsInstance<NewsContentBlock.Paragraph>()
        if (paragraphs.isEmpty()) return null

        val images = filtered.filterIsInstance<NewsContentBlock.Image>().map { it.url }.distinct()
        val plain = paragraphs.joinToString("\n") { it.text }
        return NewsArticleDetail(
            blocks = filtered,
            imageUrls = images,
            plainText = plain
        )
    }

    internal fun extractBalancedDivByClass(html: String, className: String): String? {
        val openPattern = Regex(DIV_OPEN_REGEX.pattern.format(Regex.escape(className)))
        val openMatch = openPattern.find(html) ?: return null
        val contentStart = openMatch.range.last + 1
        var depth = 1
        var pos = contentStart

        while (pos < html.length) {
            val nextTag = DIV_TAG_REGEX.find(html, pos) ?: break
            val isClosing = nextTag.value.startsWith("</", ignoreCase = true)
            if (isClosing) {
                depth--
                if (depth == 0) {
                    return html.substring(contentStart, nextTag.range.first)
                }
            } else {
                depth++
            }
            pos = nextTag.range.last + 1
        }
        return null
    }

    private fun decodeHtmlBytes(bytes: ByteArray, contentTypeHeader: String?): String {
        val headerCharset = contentTypeHeader?.let { CHARSET_META_REGEX.find(it)?.groupValues?.getOrNull(1) }
        val headAscii = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val metaCharset = CHARSET_META_REGEX.find(headAscii)?.groupValues?.getOrNull(1)
        val preferred = (headerCharset ?: metaCharset)?.lowercase(Locale.ROOT)
        val charset = when {
            preferred == null -> Charsets.UTF_8
            preferred.contains("gb") -> runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { Charset.forName(preferred) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, charset)
    }
}
