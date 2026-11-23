package com.example.input_ds.model

import com.example.input_ds.engine.PredictionCandidate

enum class SelectionItemAction {
    SELECT,
    NEXT_PAGE,
    PREVIOUS_PAGE,
    BACK,
    CONTINUE_INPUT,
    SEND
}

data class SelectionItem(
    val label: String,
    val action: SelectionItemAction,
    val sourceIndex: Int = -1,
    val sourceId: String? = null,
    val urgent: Boolean = false
)

object SelectionPaging {
    const val CHARACTERS_PER_PAGE = 10
    const val PHRASES_PER_PAGE = 9
    const val MAX_PREDICTIONS = 7
    const val INITIAL_PER_PAGE = 10
    const val GRID_SIZE = 12

    fun characterItems(candidates: List<String>, page: Int): List<SelectionItem> {
        val pageCount = totalPages(candidates.size, CHARACTERS_PER_PAGE)
        val clamped = page.coerceIn(0, pageCount - 1)
        val start = clamped * CHARACTERS_PER_PAGE
        val end = minOf(start + CHARACTERS_PER_PAGE, candidates.size)
        return buildList {
            add(SelectionItem("换拼音", SelectionItemAction.BACK))
            for (index in start until end) {
                add(SelectionItem(candidates[index], SelectionItemAction.SELECT, index))
            }
            if (pageCount > 1) {
                add(
                    SelectionItem(
                        if (clamped < pageCount - 1) "下一页" else "回第一页",
                        SelectionItemAction.NEXT_PAGE
                    )
                )
            }
        }
    }

    fun commonPhraseItems(
        phrases: List<PredictionCandidate>,
        page: Int,
        urgentPhrases: Set<String> = emptySet()
    ): List<SelectionItem> {
        val pageCount = totalPages(phrases.size, PHRASES_PER_PAGE)
        val clamped = page.coerceIn(0, pageCount - 1)
        val start = clamped * PHRASES_PER_PAGE
        val end = minOf(start + PHRASES_PER_PAGE, phrases.size)
        return buildList {
            add(SelectionItem("返回打字", SelectionItemAction.BACK))
            for (index in start until end) {
                val candidate = phrases[index]
                add(
                    SelectionItem(
                        label = candidate.text,
                        action = SelectionItemAction.SELECT,
                        sourceIndex = index,
                        sourceId = candidate.id,
                        urgent = candidate.text in urgentPhrases
                    )
                )
            }
            if (pageCount > 1) {
                add(
                    SelectionItem(
                        if (clamped < pageCount - 1) "下一页" else "回第一页",
                        SelectionItemAction.NEXT_PAGE
                    )
                )
            }
        }
    }

    fun predictionItems(
        predictions: List<PredictionCandidate>,
        sendLabel: String = "发送"
    ): List<SelectionItem> = buildList {
        add(SelectionItem("继续打字", SelectionItemAction.CONTINUE_INPUT))
        predictions.take(MAX_PREDICTIONS).forEachIndexed { index, value ->
            add(SelectionItem(value.text, SelectionItemAction.SELECT, index, value.id))
        }
        add(SelectionItem(sendLabel, SelectionItemAction.SEND))
    }

    /** Navigation never appears before published candidates, so async tail appends cannot move them. */
    fun initialSentenceItems(predictions: List<PredictionCandidate>, requestedPage: Int): List<SelectionItem> {
        val pageCount = totalPages(predictions.size, INITIAL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val start = page * INITIAL_PER_PAGE
        val end = minOf(start + INITIAL_PER_PAGE, predictions.size)
        return buildList {
            add(SelectionItem("返回", SelectionItemAction.BACK))
            for (index in start until end) {
                val candidate = predictions[index]
                add(SelectionItem(candidate.text, SelectionItemAction.SELECT, index, candidate.id))
            }
            if (pageCount > 1) {
                add(
                    SelectionItem(
                        if (page < pageCount - 1) "下一页" else "回第一页",
                        SelectionItemAction.NEXT_PAGE
                    )
                )
            }
        }
    }

    fun totalPages(itemCount: Int, perPage: Int = CHARACTERS_PER_PAGE): Int =
        maxOf(1, (itemCount + perPage - 1) / perPage)
}
