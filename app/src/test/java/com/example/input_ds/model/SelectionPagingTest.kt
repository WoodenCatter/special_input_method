package com.example.input_ds.model

import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.engine.PredictionCandidate
import com.example.input_ds.engine.PredictionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPagingTest {
    @Test
    fun characterPage_usesTenCharsWithSwapPinyinAndWrapNext() {
        val candidates = (1..15).map { "字$it" }

        val firstPage = SelectionPaging.characterItems(candidates, 0)
        assertEquals(12, firstPage.size)
        assertEquals(SelectionItemAction.BACK, firstPage[0].action)
        assertEquals("换拼音", firstPage[0].label)
        assertEquals("字1", firstPage[1].label)
        assertEquals("字10", firstPage[10].label)
        assertEquals(SelectionItemAction.NEXT_PAGE, firstPage[11].action)
        assertEquals("下一页", firstPage[11].label)

        val secondPage = SelectionPaging.characterItems(candidates, 1)
        assertEquals("字11", secondPage[1].label)
        assertEquals("字15", secondPage[5].label)
        assertEquals("回第一页", secondPage.last().label)
    }

    @Test
    fun commonPhrasePage_usesNinePhrasesMarksUrgentAndWraps() {
        val phrases = (CharacterDictionary.URGENT_PHRASES + (1..12).map { "常用语$it" })
            .map { PredictionCandidate(it, PredictionSource.USER, id = "c:$it") }

        val firstPage = SelectionPaging.commonPhraseItems(phrases, 0, CharacterDictionary.URGENT_PHRASE_SET)
        assertEquals(11, firstPage.size)
        assertEquals("返回打字", firstPage[0].label)
        assertTrue(firstPage[1].urgent)
        assertTrue(firstPage[2].urgent)
        assertTrue(firstPage[3].urgent)
        assertFalse(firstPage[4].urgent)
        assertEquals("下一页", firstPage.last().label)

        val secondPage = SelectionPaging.commonPhraseItems(phrases, 1, CharacterDictionary.URGENT_PHRASE_SET)
        assertEquals("回第一页", secondPage.last().label)
    }

    @Test
    fun prediction_placesContinueInputFirstAndSendLastUpToSeven() {
        val items = SelectionPaging.predictionItems(
            (1..9).map { PredictionCandidate("词$it", PredictionSource.LOCAL, id = "local-$it") },
            sendLabel = "说出"
        )

        assertEquals(9, items.size)
        assertEquals(SelectionItemAction.CONTINUE_INPUT, items[0].action)
        assertEquals("继续打字", items[0].label)
        assertEquals("词1", items[1].label)
        assertEquals("词7", items[7].label)
        assertEquals(SelectionItemAction.SEND, items[8].action)
        assertEquals("说出", items[8].label)
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
