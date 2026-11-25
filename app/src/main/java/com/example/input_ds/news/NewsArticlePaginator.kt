package com.example.input_ds.news

import kotlin.math.ceil

/**
 * Splits a full news article's ordered paragraphs & images into calm, well-balanced reading pages
 * tailored for a 1280x800 landscape tablet:
 * - Text-only pages use full card width (~42 CJK chars/line, ~8.8 lines per page, ~6.4 lines on page 0).
 * - Image + text pages render side-by-side (left text ~22 chars/line, right image full card height).
 * - Tiny orphan tail pages (e.g. a single 1-line sentence at the end) are merged or rebalanced with
 *   the preceding text page so every page looks full and natural.
 */
object NewsArticlePaginator {
    private const val FULL_WIDTH_CHARS_PER_LINE = 42.0
    private const val HALF_WIDTH_CHARS_PER_LINE = 22.0
    private const val PARAGRAPH_GAP_LINES = 0.55

    private const val PAGE0_TEXT_MAX_LINES = 6.2
    private const val TEXT_PAGE_MAX_LINES = 8.8
    private const val PAGE0_SPLIT_TEXT_MAX_LINES = 5.4
    private const val SPLIT_TEXT_MAX_LINES = 8.2

    private const val MAX_SINGLE_CHUNK_CHARS = 260
    private const val ORPHAN_TAIL_MAX_LINES = 2.2
    private const val MERGE_SLACK_LINES = 1.0

    fun paginate(blocks: List<NewsContentBlock>): List<NewsReadingPage> {
        if (blocks.isEmpty()) return emptyList()

        val normalized = mutableListOf<NewsContentBlock>()
        for (block in blocks) {
            when (block) {
                is NewsContentBlock.Image -> normalized += block
                is NewsContentBlock.Paragraph -> {
                    val trimmed = block.text.trim()
                    if (trimmed.isNotEmpty()) {
                        splitLongParagraph(trimmed, MAX_SINGLE_CHUNK_CHARS).forEach { chunk ->
                            normalized += NewsContentBlock.Paragraph(chunk)
                        }
                    }
                }
            }
        }

        val pages = mutableListOf<MutableList<NewsContentBlock>>()
        var current = mutableListOf<NewsContentBlock>()
        var hasImage = false

        fun isFirstPage() = pages.isEmpty()

        fun maxLinesForCurrent(withImage: Boolean): Double = when {
            isFirstPage() && withImage -> PAGE0_SPLIT_TEXT_MAX_LINES
            isFirstPage() -> PAGE0_TEXT_MAX_LINES
            withImage -> SPLIT_TEXT_MAX_LINES
            else -> TEXT_PAGE_MAX_LINES
        }

        fun currentParagraphs(): List<NewsContentBlock.Paragraph> =
            current.filterIsInstance<NewsContentBlock.Paragraph>()

        fun flush() {
            if (current.isNotEmpty()) {
                pages += current
                current = mutableListOf()
                hasImage = false
            }
        }

        for (block in normalized) {
            when (block) {
                is NewsContentBlock.Image -> {
                    if (hasImage) {
                        flush()
                    } else if (current.isNotEmpty()) {
                        // Check if existing text on this page still fits nicely beside the image in 2-column layout.
                        val textLinesBesideImage = estimateParagraphLines(currentParagraphs(), HALF_WIDTH_CHARS_PER_LINE)
                        if (textLinesBesideImage > maxLinesForCurrent(withImage = true)) {
                            flush()
                        }
                    }
                    current += block
                    hasImage = true
                }

                is NewsContentBlock.Paragraph -> {
                    val charsPerLine = if (hasImage) HALF_WIDTH_CHARS_PER_LINE else FULL_WIDTH_CHARS_PER_LINE
                    val limit = maxLinesForCurrent(withImage = hasImage)
                    val candidateParagraphs = currentParagraphs() + block
                    val candidateLines = estimateParagraphLines(candidateParagraphs, charsPerLine)

                    if (current.isNotEmpty() && candidateLines > limit) {
                        // If we're on a split image page that has ZERO text yet, still try to put a short slice beside it.
                        if (hasImage && currentParagraphs().isEmpty() && block.text.length > 90) {
                            val firstSliceMax = ((limit - 0.5) * HALF_WIDTH_CHARS_PER_LINE).toInt().coerceAtLeast(80)
                            val pieces = splitLongParagraph(block.text, firstSliceMax)
                            if (pieces.size >= 2) {
                                current += NewsContentBlock.Paragraph(pieces.first())
                                flush()
                                pieces.drop(1).forEach { tail ->
                                    if (current.isNotEmpty() &&
                                        estimateParagraphLines(currentParagraphs() + NewsContentBlock.Paragraph(tail), FULL_WIDTH_CHARS_PER_LINE) > maxLinesForCurrent(false)
                                    ) {
                                        flush()
                                    }
                                    current += NewsContentBlock.Paragraph(tail)
                                }
                                continue
                            }
                        }
                        flush()
                    }
                    current += block
                }
            }
        }
        flush()

        rebalanceOrphanTail(pages)
        return pages.map { NewsReadingPage(it.toList()) }
    }

    private fun rebalanceOrphanTail(pages: MutableList<MutableList<NewsContentBlock>>) {
        if (pages.size < 2) return
        val last = pages.last()
        val prev = pages[pages.lastIndex - 1]
        if (last.any { it is NewsContentBlock.Image }) return

        val lastParagraphs = last.filterIsInstance<NewsContentBlock.Paragraph>()
        val lastLines = estimateParagraphLines(lastParagraphs, FULL_WIDTH_CHARS_PER_LINE)
        if (lastLines > ORPHAN_TAIL_MAX_LINES) return

        // Case 1: previous page is text-only — either merge directly (if within slack) or rebalance 50/50.
        if (prev.none { it is NewsContentBlock.Image }) {
            val prevIsFirst = pages.size == 2
            val prevLimit = if (prevIsFirst) PAGE0_TEXT_MAX_LINES else TEXT_PAGE_MAX_LINES
            val combined = prev.filterIsInstance<NewsContentBlock.Paragraph>() + lastParagraphs
            val combinedLines = estimateParagraphLines(combined, FULL_WIDTH_CHARS_PER_LINE)

            if (combinedLines <= prevLimit + MERGE_SLACK_LINES) {
                prev.addAll(last)
                pages.removeAt(pages.lastIndex)
                return
            }

            // Rebalance text across the last two pages roughly evenly by moving trailing paragraph(s)
            // or splitting the last paragraph of prev if needed.
            if (prev.size >= 2) {
                val moved = prev.removeAt(prev.lastIndex)
                last.add(0, moved)
                val newPrevLines = estimateParagraphLines(prev.filterIsInstance<NewsContentBlock.Paragraph>(), FULL_WIDTH_CHARS_PER_LINE)
                val newLastLines = estimateParagraphLines(last.filterIsInstance<NewsContentBlock.Paragraph>(), FULL_WIDTH_CHARS_PER_LINE)
                if (newLastLines > TEXT_PAGE_MAX_LINES + 0.5 || newPrevLines < ORPHAN_TAIL_MAX_LINES) {
                    // Revert if moving made balance worse.
                    last.removeAt(0)
                    prev.add(moved)
                }
            }
            return
        }

        // Case 2: previous page has an image — merge tiny tail into the side text column if it fits!
        val prevIsFirst = pages.size == 2
        val splitLimit = if (prevIsFirst) PAGE0_SPLIT_TEXT_MAX_LINES else SPLIT_TEXT_MAX_LINES
        val combinedBesideImage = prev.filterIsInstance<NewsContentBlock.Paragraph>() + lastParagraphs
        val combinedLines = estimateParagraphLines(combinedBesideImage, HALF_WIDTH_CHARS_PER_LINE)
        if (combinedLines <= splitLimit + 0.8) {
            prev.addAll(last)
            pages.removeAt(pages.lastIndex)
        }
    }

    internal fun estimateParagraphLines(
        paragraphs: List<NewsContentBlock.Paragraph>,
        charsPerLine: Double
    ): Double {
        if (paragraphs.isEmpty()) return 0.0
        var total = 0.0
        paragraphs.forEachIndexed { idx, para ->
            val weightedChars = effectiveCharWidth(para.text)
            val lines = ceil(weightedChars / charsPerLine).coerceAtLeast(1.0)
            total += lines
            if (idx > 0) total += PARAGRAPH_GAP_LINES
        }
        return total
    }

    private fun effectiveCharWidth(text: String): Double {
        var width = 0.0
        for (ch in text) {
            width += if (ch.code < 128) 0.56 else 1.0
        }
        return width
    }

    internal fun splitLongParagraph(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        val minCut = (maxChars * 0.45f).toInt()

        while (remaining.length > maxChars) {
            val window = remaining.substring(0, maxChars)
            val sentenceBreak = maxOf(
                window.lastIndexOf('。'),
                window.lastIndexOf('！'),
                window.lastIndexOf('？'),
                window.lastIndexOf('；'),
                window.lastIndexOf(". ")
            )
            val cutIndex = when {
                sentenceBreak >= minCut -> sentenceBreak + 1
                else -> {
                    val clauseBreak = maxOf(
                        window.lastIndexOf('，'),
                        window.lastIndexOf(','),
                        window.lastIndexOf('、'),
                        window.lastIndexOf(' ')
                    )
                    if (clauseBreak >= minCut) clauseBreak + 1 else maxChars
                }
            }
            val head = remaining.substring(0, cutIndex).trim()
            if (head.isNotEmpty()) chunks += head
            remaining = remaining.substring(cutIndex).trim()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }
}
