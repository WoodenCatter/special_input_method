package com.example.input_ds.model

import com.example.input_ds.engine.PredictionCandidate
import com.example.input_ds.engine.PredictionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPagingTest {
    @Test
    fun pinyinCandidatePage_placesNextPageLast() {
        val candidates = (1..20).map { "候选$it" }

        val firstPage = SelectionPaging.characterItems(candidates, 0)

        assertEquals(SelectionItemAction.BACK, firstPage[0].action)
        assertEquals("候选1", firstPage[1].label)
        assertEquals(SelectionItemAction.NEXT_PAGE, firstPage.last().action)
    }

    @Test
    fun commonPhrasePage_hasNextAndReturnOnFirstPage() {
        val phrases = (1..30).map { PredictionCandidate("常用语$it", PredictionSource.USER) }

        val firstPage = SelectionPaging.commonPhraseItems(phrases, 0)

        assertEquals("返回", firstPage[0].label)
        assertEquals("下一页", firstPage.last().label)
        assertTrue(firstPage.size <= SelectionPaging.GRID_SIZE)
    }

    @Test
    fun prediction_placesContinueInputFirst() {
        val items = SelectionPaging.predictionItems(
            listOf(
                PredictionCandidate("你好", PredictionSource.LOCAL, id = "local-1"),
                PredictionCandidate("谢谢", PredictionSource.LLM, id = "llm-1")
            )
        )

        assertEquals(SelectionItemAction.CONTINUE_INPUT, items[0].action)
        assertEquals("继续输入", items[0].label)
        assertEquals("你好", items[1].label)
        assertEquals("local-1", items[1].sourceId)
    }

    @Test
    fun initialSentenceAppend_keepsPublishedCandidateIndexesStable() {
        val original = (1..10).map {
            PredictionCandidate("候选$it", PredictionSource.INITIAL_INDEX, id = "id-$it")
        }
        val appended = original + (11..14).map {
            PredictionCandidate("候选$it", PredictionSource.LLM, id = "id-$it")
        }

        val before = SelectionPaging.initialSentenceItems(original, 0)
        val after = SelectionPaging.initialSentenceItems(appended, 0)

        assertEquals(before.map { it.sourceId }, after.take(before.size).map { it.sourceId })
        assertEquals(SelectionItemAction.NEXT_PAGE, after.last().action)
    }
}
