package com.example.input_ds.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RssFeedParserTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    // 2026-11-25 17:00:00 +08:00
    private val fixedNowMs = RssFeedParser.parseTimestampMs("Wed, 25 Nov 2026 17:00:00 +0800")!!

    @Test
    fun parsesChinaNewsStyleRssAndFormatsTodayAndYesterday() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <title>中新网要闻导读</title>
                <item>
                  <title>我国将牵头制定全球首个油气生产物联网国际标准</title>
                  <link>https://www.chinanews.com.cn/cj/1.shtml</link>
                  <description>中新网11月25日电 据国家市场监督管理总局消息，近日国际标准提案成功立项。</description>
                  <pubDate>Wed, 25 Nov 2026 16:25:26 +0800</pubDate>
                </item>
                <item>
                  <title>昨日天气预报更新</title>
                  <link>https://www.chinanews.com.cn/sh/2.shtml</link>
                  <description>冷空气东移南下，全国多地迎来降温，提醒市民注意防寒保暖。</description>
                  <pubDate>Tue, 24 Nov 2026 21:10:00 +0800</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val articles = RssFeedParser.parse(xml, nowMs = fixedNowMs, timeZone = tz)
        assertEquals(2, articles.size)
        assertEquals("我国将牵头制定全球首个油气生产物联网国际标准", articles[0].title)
        assertEquals("今天 16:25", articles[0].publishedAtText)
        assertTrue(articles[0].summary.contains("国际标准提案成功立项"))
        assertEquals("昨天 21:10", articles[1].publishedAtText)
    }

    @Test
    fun extractsImagesFullTextAndStripsBoilerplateFromItHomeRss() {
        val xml = """
            <rss version="2.0">
              <channel>
                <title>IT之家</title>
                <item>
                  <title>开源项目&#8211;新版本发布 &amp; 性能提升</title>
                  <link>https://www.ithome.com/1/032/371.htm</link>
                  <description><![CDATA[
                    <p data-vmark="1">IT之家 11 月 25 日消息，新版正式上线，带来了大幅度的渲染优化和续航提升。</p>
                    <p><img src="https://img.ithome.com/newsuploadfiles/2026/11/sample.jpg" alt="新版截图" /></p>
                    <p>支持&ldquo;低功耗&rdquo;模式与更快的启动速度，同时修复了多项长期存在的问题。</p>
                    <script>alert('x')</script>
                    <a href="https://example.com">查看全文</a>
                  ]]></description>
                  <pubDate>Wed, 25 Nov 2026 08:30:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val articles = RssFeedParser.parse(xml, nowMs = fixedNowMs, timeZone = tz)
        assertEquals(1, articles.size)
        assertEquals("开源项目–新版本发布 & 性能提升", articles[0].title)
        assertEquals("今天 16:30", articles[0].publishedAtText)
        assertEquals(
            listOf("https://img.ithome.com/newsuploadfiles/2026/11/sample.jpg"),
            articles[0].imageUrls
        )
        assertTrue(articles[0].fullText.contains("支持“低功耗”模式"))
        assertTrue(!articles[0].fullText.contains("<p"))
        assertTrue(!articles[0].fullText.contains("alert"))
        assertTrue(!articles[0].fullText.contains("查看全文"))
    }

    @Test
    fun extractsFullArticleAndPhotosFromNestedChinaNewsHtml() {
        val html = """
            <html><body>
              <div class="left_zw">
                <p>第一段正文内容，介绍事件背景与核心进展，字数完整无截断。</p>
                <div class="left_ph">
                  <img src="//i2.chinanews.com.cn/simg/cmshd/2026/11/25/photo1.jpg" alt="现场画面" />
                </div>
                <p>第二段正文内容，进一步阐述专家分析与社会影响，提供详尽细节。</p>
                <p>责任编辑：张三</p>
              </div>
            </body></html>
        """.trimIndent()

        val detail = NewsArticleExtractor.extractFromHtml(html, "https://www.chinanews.com.cn/gn/1.shtml")
        assertNotNull(detail)
        assertEquals(
            listOf("https://i2.chinanews.com.cn/simg/cmshd/2026/11/25/photo1.jpg"),
            detail!!.imageUrls
        )
        val paragraphs = detail.blocks.filterIsInstance<NewsContentBlock.Paragraph>().map { it.text }
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("第一段"))
        assertTrue(paragraphs[1].startsWith("第二段"))
    }

    @Test
    fun paginatorSplitsLongArticleWithImagesAndAvoidsTinyOrphanTailPages() {
        val longPara = "这是一段很长的新闻报道内容，用于测试分页器能否按句子边界自然切分。".repeat(14)
        val blocks = listOf(
            NewsContentBlock.Paragraph(longPara),
            NewsContentBlock.Image("https://img.example.com/1.jpg", "配图一"),
            NewsContentBlock.Paragraph("这是配图之后的补充说明段落，内容清晰完整，介绍后续进展。"),
            NewsContentBlock.Paragraph("目前，相关部门已依法处理。")
        )
        val pages = NewsArticlePaginator.paginate(blocks)
        assertTrue("Expected multi-page split, got ${pages.size}", pages.size >= 2)
        assertTrue(pages.any { page -> page.blocks.any { it is NewsContentBlock.Image } })
        val lastText = pages.last().spokenText
        assertTrue("Final page should not be a tiny 1-line orphan: '$lastText'", lastText.length > 25)
    }

    @Test
    fun parsesAtomFeedAndDeduplicatesTitles() {
        val atom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>示例 Atom</title>
              <entry>
                <id>tag:1</id>
                <title>第一篇技术简报</title>
                <link rel="alternate" href="https://example.org/1" />
                <published>2026-11-25T15:05:00+08:00</published>
                <summary>这是一段足够长的 Atom 摘要内容，用于验证客户端的解析与展示逻辑是否正常工作。</summary>
              </entry>
              <entry>
                <id>tag:2</id>
                <title>第一篇技术简报</title>
                <published>2026-11-25T15:05:00+08:00</published>
                <summary>重复标题应当被自动去重。</summary>
              </entry>
            </feed>
        """.trimIndent()

        val articles = RssFeedParser.parse(atom, nowMs = fixedNowMs, timeZone = tz)
        assertEquals(1, articles.size)
        assertEquals("第一篇技术简报", articles[0].title)
        assertEquals("今天 15:05", articles[0].publishedAtText)
        assertEquals("https://example.org/1", articles[0].link)
    }

    @Test
    fun handlesMalformedXmlAndEmptyDescriptionGracefully() {
        assertEquals(emptyList<NewsArticle>(), RssFeedParser.parse("not valid xml <<<"))

        val shortDescXml = """
            <rss version="2.0"><channel>
              <item>
                <title>快讯标题</title>
                <description></description>
              </item>
            </channel></rss>
        """.trimIndent()
        val parsed = RssFeedParser.parse(shortDescXml, nowMs = fixedNowMs, timeZone = tz)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].summary.contains("阅读全文"))
    }
}
