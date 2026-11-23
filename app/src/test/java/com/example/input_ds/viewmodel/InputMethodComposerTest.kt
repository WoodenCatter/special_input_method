package com.example.input_ds.viewmodel

import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.SpeakOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodComposerTest {
    @Test
    fun sixBlockWingsMatchPrototypeLayout() {
        assertEquals(
            listOf(2, 3, 4, 5, LetterBlockMapping.DELETE_BLOCK, LetterBlockMapping.BACK_BLOCK),
            LetterBlockMapping.LEFT_BLOCKS
        )
        assertEquals(
            listOf(6, 7, 8, 9, LetterBlockMapping.SEND_BLOCK, LetterBlockMapping.PHRASES_BLOCK),
            LetterBlockMapping.RIGHT_BLOCKS
        )
        assertEquals("说出", LetterBlockMapping.labelFor(LetterBlockMapping.SEND_BLOCK, talkMode = true))
        assertEquals("发送", LetterBlockMapping.labelFor(LetterBlockMapping.SEND_BLOCK, talkMode = false))
        assertEquals("返回", LetterBlockMapping.labelFor(LetterBlockMapping.BACK_BLOCK))
        assertEquals("常用语", LetterBlockMapping.labelFor(LetterBlockMapping.PHRASES_BLOCK))
    }

    @Test
    fun urgentPhrasesStayPinnedAheadOfFrequentlyUsedNormalPhrases() {
        val sorted = CommonPhraseUsage.sorted(
            phrases = CharacterDictionary.COMMON_PHRASES,
            urgentPinned = CharacterDictionary.URGENT_PHRASE_SET,
            usageProvider = { phrase ->
                when (phrase) {
                    "谢谢你们" -> 99
                    "我想喝水" -> 50
                    else -> 0
                }
            }
        )
        assertEquals(CharacterDictionary.URGENT_PHRASES, sorted.take(3))
        assertEquals("谢谢你们", sorted[3])
        assertEquals("我想喝水", sorted[4])
    }

    @Test
    fun inputModesHaveDistinctDraftKeysLabelsAndPhrases() {
        val talk = InputMode.Talk
        val chatMom = InputMode.Chat("mom", "妈妈")
        val chatYu = InputMode.Chat("yu", "小雨")
        val asst = InputMode.Assistant

        assertNull(talk.pillLabel)
        assertEquals("说出", talk.sendLabel)
        assertFalse(talk.usesAssistantPhrases)

        assertEquals("发给 妈妈", chatMom.pillLabel)
        assertEquals("发送", chatMom.sendLabel)
        assertFalse(chatMom.draftKey == chatYu.draftKey)

        assertEquals("问助手", asst.pillLabel)
        assertEquals("发送", asst.sendLabel)
        assertTrue(asst.usesAssistantPhrases)
        assertEquals(16, CharacterDictionary.ASSISTANT_PHRASES.size)
        assertEquals("开灯", CharacterDictionary.ASSISTANT_PHRASES.first())
        assertEquals("为什么天空是蓝色的", CharacterDictionary.ASSISTANT_PHRASES.last())
    }

    @Test
    fun speakOverlayAndClearConfirmFollowFocusAndTimeoutRules() {
        val speak = SpeakOverlayState("你好")
        assertEquals(1, speak.cursor.index)
        assertEquals("再说一遍", SpeakOverlayState.LABELS[speak.cursor.index])

        var confirm = ScanCursor(size = 2, index = 0, direction = 1)
        assertEquals(2250L, confirm.dwell(1500L, confirmation = true))
        repeat(5) {
            confirm = confirm.advance(1000L * it, ScanMode.AUTO)
            assertFalse(confirm.timedOut(confirmation = true))
        }
        confirm = confirm.advance(6000L, ScanMode.AUTO)
        assertTrue(confirm.timedOut(confirmation = true))
    }
}
