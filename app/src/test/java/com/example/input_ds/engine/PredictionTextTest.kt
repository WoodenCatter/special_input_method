package com.example.input_ds.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTextTest {
    @Test
    fun `normalizes full and local context prefixes to append-only suffixes`() {
        assertEquals("很好", PredictionText.removeCompleteContextPrefix("今天", "今天很好"))
        assertEquals("吗", PredictionText.removeLocalContextPrefix("你好", "好吗"))
        assertEquals("世界", PredictionText.removeLocalContextPrefix("你好", "好世界"))
    }

    @Test
    fun `keeps supplementary Han code points intact`() {
        val extensionHan = String(Character.toChars(0x20000))
        val text = "甲${extensionHan}乙"

        assertEquals("${extensionHan}乙", PredictionText.takeLastCodePoints(text, 2))
        assertEquals(listOf("甲", extensionHan, "乙"), PredictionText.codePointStrings(text))
        assertTrue(PredictionText.isDisplayableSuffix(extensionHan, maxCodePoints = 1))
    }

    @Test
    fun `filters whitespace latin and overlong predictions`() {
        assertFalse(PredictionText.isDisplayableSuffix("你 好"))
        assertFalse(PredictionText.isDisplayableSuffix("hello"))
        assertFalse(PredictionText.isDisplayableSuffix("一二三四五六七八九", maxCodePoints = 8))
        assertTrue(PredictionText.isDisplayableSuffix("需要帮助"))
    }
}
