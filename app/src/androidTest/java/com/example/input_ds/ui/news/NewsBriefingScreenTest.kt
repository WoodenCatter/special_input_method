package com.example.input_ds.ui.news

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.news.NewsArticle
import com.example.input_ds.news.NewsArticleDetail
import com.example.input_ds.news.NewsArticleDetailFetcher
import com.example.input_ds.news.NewsContentBlock
import com.example.input_ds.news.NewsFeedFetcher
import com.example.input_ds.news.NewsInputBus
import com.example.input_ds.news.NewsSource
import com.example.input_ds.ui.theme.InputDSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NewsBriefingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testSources = listOf(
        NewsSource("src_a", "中新要闻", "中国新闻网", "综合热点", "https://example.invalid/a.xml"),
        NewsSource("src_b", "IT 科技", "IT之家", "数码前沿", "https://example.invalid/b.xml")
    )

    private val longBodyPage1 = "第一段详细报道正文，介绍本次事件的核心背景与重要影响，内容完整连贯，涵盖各方最新动态与数据解读。".repeat(6)
    private val longBodyPage2 = "第二页深入分析报道正文，进一步解读后续举措与各方反响，补充更多细节说明。".repeat(6)

    private val testFetcher = NewsFeedFetcher { source ->
        when (source.id) {
            "src_a" -> listOf(
                NewsArticle(
                    id = "a1",
                    title = "第一条要闻标题",
                    summary = "简短预览",
                    publishedAtText = "今天 08:15",
                    fullText = "$longBodyPage1\n$longBodyPage2",
                    fullContentLoaded = false
                ),
                NewsArticle(
                    id = "a2",
                    title = "第二条要闻标题",
                    summary = "这是第二条要闻的详细摘要内容。",
                    publishedAtText = "今天 08:40",
                    fullContentLoaded = true
                )
            )
            else -> listOf(
                NewsArticle(
                    id = "b1",
                    title = "科技前沿新进展",
                    summary = "这是科技频道的最新摘要内容。",
                    publishedAtText = "今天 09:00",
                    fullContentLoaded = true
                )
            )
        }
    }

    private val testDetailFetcher = NewsArticleDetailFetcher {
        NewsArticleDetail(
            blocks = listOf(
                NewsContentBlock.Paragraph(longBodyPage1),
                NewsContentBlock.Paragraph(longBodyPage2)
            ),
            imageUrls = emptyList(),
            plainText = "$longBodyPage1\n$longBodyPage2"
        )
    }

    @Test
    fun supportsDirectBrowseSwitchingFullReadingAutoNextArticleAndSourceSwitch() {
        val spokenTexts = mutableListOf<String>()

        composeRule.setContent {
            InputDSTheme(false) {
                Box(Modifier.size(1224.dp, 592.dp)) {
                    NewsBriefingScreen(
                        scanIntervalMs = 1_500L,
                        scanMode = ScanMode.MANUAL,
                        paused = false,
                        frozen = true,
                        onSpeak = { text, _ -> spokenTexts += text },
                        onStopSpeak = {},
                        onBack = {},
                        sources = testSources,
                        fetcher = testFetcher,
                        detailFetcher = testDetailFetcher
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("第一条要闻标题").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()

        // Direct browse: right look goes to article 2, left look goes back to article 1.
        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.RIGHT_LOOK) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()

        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.LEFT_LOOK) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()

        // Bite opens browse menu (default focused on "阅读全文"); second bite enters full reading.
        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("阅读全文").assertIsDisplayed()

        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 / 2 页").assertIsDisplayed()

        // Right look turns to page 2; another right look on last page advances straight to article 2.
        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.RIGHT_LOOK) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 2 / 2 页").assertIsDisplayed()

        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.RIGHT_LOOK) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("第二条要闻标题")[0].assertIsDisplayed()

        // Bite opens reading menu; speak current page then return to browse.
        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("朗读本页").performClick()
        composeRule.waitForIdle()
        assertEquals(1, spokenTexts.size)
        assertTrue(spokenTexts.first().contains("第二条要闻标题"))

        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("返回浏览").performClick()
        composeRule.waitForIdle()

        // Open browse menu and switch news source.
        composeRule.runOnUiThread { NewsInputBus.dispatch(ControlSignal.BITE) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("换新闻源").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("选择新闻源").assertIsDisplayed()
        composeRule.onNodeWithText("IT 科技").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("科技前沿新进展").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
    }
}
