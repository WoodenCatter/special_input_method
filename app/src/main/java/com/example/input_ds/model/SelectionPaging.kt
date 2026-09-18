package com.example.input_ds.model

import com.example.input_ds.engine.PredictionCandidate

enum class SelectionItemAction {
    SELECT,
    NEXT_PAGE,
    PREVIOUS_PAGE,
    BACK,
    CONTINUE_INPUT
}

data class SelectionItem(
    val label: String,
    val action: SelectionItemAction,
    val sourceIndex: Int = -1,
    val sourceId: String? = null
)

object SelectionPaging {
    const val GRID_SIZE = 15
    private const val CONTENTS_PER_PAGE = 12

    fun characterItems(candidates: List<String>, page: Int): List<SelectionItem> =
        pagedItems(candidates, page, backLabel = "返回")

    fun commonPhraseItems(phrases: List<PredictionCandidate>, page: Int): List<SelectionItem> =
        pagedCandidateItems(phrases, page, backLabel = "返回")

    fun predictionItems(predictions: List<PredictionCandidate>): List<SelectionItem> =
        buildList {
            add(SelectionItem("继续输入", SelectionItemAction.CONTINUE_INPUT))
            predictions.take(GRID_SIZE - 1).forEachIndexed { index, value ->
                add(SelectionItem(value.text, SelectionItemAction.SELECT, index, value.id))
            }
        }

    /** Navigation never appears before published candidates, so async tail appends cannot move them. */
    fun initialSentenceItems(predictions: List<PredictionCandidate>, requestedPage: Int): List<SelectionItem> {
        val pageCount = totalPages(predictions.size)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val start = page * CONTENTS_PER_PAGE
        val end = minOf(start + CONTENTS_PER_PAGE, predictions.size)
        return buildList {
            add(SelectionItem("返回", SelectionItemAction.BACK))
            if (page > 0) add(SelectionItem("上一页", SelectionItemAction.PREVIOUS_PAGE))
            for (index in start until end) {
                val candidate = predictions[index]
                add(SelectionItem(candidate.text, SelectionItemAction.SELECT, index, candidate.id))
            }
            if (page < pageCount - 1) add(SelectionItem("下一页", SelectionItemAction.NEXT_PAGE))
        }
    }

    fun totalPages(itemCount: Int): Int =
        maxOf(1, (itemCount + CONTENTS_PER_PAGE - 1) / CONTENTS_PER_PAGE)

    private fun pagedItems(
        contents: List<String>,
        requestedPage: Int,
        backLabel: String
    ): List<SelectionItem> {
        val pageCount = totalPages(contents.size)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val start = page * CONTENTS_PER_PAGE
        val end = minOf(start + CONTENTS_PER_PAGE, contents.size)
        return buildList {
            add(SelectionItem(backLabel, SelectionItemAction.BACK))
            if (page > 0) {
                add(SelectionItem("上一页", SelectionItemAction.PREVIOUS_PAGE))
            }
            for (index in start until end) {
                add(SelectionItem(contents[index], SelectionItemAction.SELECT, index))
            }
            if (page < pageCount - 1) {
                add(SelectionItem("下一页", SelectionItemAction.NEXT_PAGE))
            }
        }
    }

    private fun pagedCandidateItems(
        contents: List<PredictionCandidate>,
        requestedPage: Int,
        backLabel: String
    ): List<SelectionItem> {
        val pageCount = totalPages(contents.size)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val start = page * CONTENTS_PER_PAGE
        val end = minOf(start + CONTENTS_PER_PAGE, contents.size)
        return buildList {
            add(SelectionItem(backLabel, SelectionItemAction.BACK))
            if (page > 0) add(SelectionItem("上一页", SelectionItemAction.PREVIOUS_PAGE))
            for (index in start until end) {
                val candidate = contents[index]
                add(SelectionItem(candidate.text, SelectionItemAction.SELECT, index, candidate.id))
            }
            if (page < pageCount - 1) add(SelectionItem("下一页", SelectionItemAction.NEXT_PAGE))
        }
    }
}
