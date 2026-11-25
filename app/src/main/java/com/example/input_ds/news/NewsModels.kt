package com.example.input_ds.news

data class NewsSource(
    val id: String,
    val name: String,
    val provider: String,
    val category: String,
    val url: String
)

sealed interface NewsContentBlock {
    data class Paragraph(val text: String) : NewsContentBlock
    data class Image(val url: String, val caption: String = "") : NewsContentBlock
}

data class NewsArticleDetail(
    val blocks: List<NewsContentBlock>,
    val imageUrls: List<String>,
    val plainText: String
)

data class NewsReadingPage(
    val blocks: List<NewsContentBlock>
) {
    val spokenText: String
        get() = blocks.mapNotNull { block ->
            when (block) {
                is NewsContentBlock.Paragraph -> block.text
                is NewsContentBlock.Image -> block.caption.takeIf { it.isNotBlank() }
            }
        }.joinToString("。")
}

data class NewsArticle(
    val id: String,
    val title: String,
    val summary: String,
    val publishedAtText: String,
    val publishedAtMs: Long? = null,
    val link: String = "",
    val fullText: String = summary,
    val imageUrls: List<String> = emptyList(),
    val fullContentLoaded: Boolean = false
) {
    val leadImageUrl: String?
        get() = imageUrls.firstOrNull()
}

data class CachedNewsFeed(
    val sourceId: String,
    val articles: List<NewsArticle>,
    val fetchedAtMs: Long
)

object NewsCatalog {
    val sources: List<NewsSource> = listOf(
        NewsSource(
            id = "chinanews_headlines",
            name = "中新要闻",
            provider = "中国新闻网",
            category = "综合热点",
            url = "https://www.chinanews.com.cn/rss/importnews.xml"
        ),
        NewsSource(
            id = "chinanews_china",
            name = "国内时政",
            provider = "中国新闻网",
            category = "国内动态",
            url = "https://www.chinanews.com.cn/rss/china.xml"
        ),
        NewsSource(
            id = "chinanews_world",
            name = "国际新闻",
            provider = "中国新闻网",
            category = "全球资讯",
            url = "https://www.chinanews.com.cn/rss/world.xml"
        ),
        NewsSource(
            id = "chinanews_finance",
            name = "财经民生",
            provider = "中国新闻网",
            category = "经济生活",
            url = "https://www.chinanews.com.cn/rss/finance.xml"
        ),
        NewsSource(
            id = "chinanews_culture",
            name = "文化生活",
            provider = "中国新闻网",
            category = "人文艺术",
            url = "https://www.chinanews.com.cn/rss/culture.xml"
        ),
        NewsSource(
            id = "chinanews_sports",
            name = "体育赛事",
            provider = "中国新闻网",
            category = "赛事快讯",
            url = "https://www.chinanews.com.cn/rss/sports.xml"
        ),
        NewsSource(
            id = "ithome",
            name = "IT 科技",
            provider = "IT之家",
            category = "数码前沿",
            url = "https://www.ithome.com/rss/"
        ),
        NewsSource(
            id = "solidot",
            name = "奇客科普",
            provider = "Solidot",
            category = "科技探索",
            url = "https://www.solidot.org/index.rss"
        )
    )

    val defaultSource: NewsSource = sources.first()

    fun findById(id: String?): NewsSource =
        sources.firstOrNull { it.id == id } ?: defaultSource
}
