package com.example.input_ds.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NewsBriefingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedSourceId(): String =
        prefs.getString(KEY_SELECTED_SOURCE, NewsCatalog.defaultSource.id) ?: NewsCatalog.defaultSource.id

    fun saveSelectedSourceId(sourceId: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, sourceId).apply()
    }

    fun loadCachedFeed(sourceId: String): CachedNewsFeed? {
        val raw = prefs.getString(cacheKey(sourceId), null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val fetchedAtMs = root.optLong("fetched_at_ms", 0L)
            val array = root.optJSONArray("articles") ?: return null
            val articles = buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    if (title.isEmpty()) continue
                    val publishedAtMs = if (obj.has("published_at_ms") && !obj.isNull("published_at_ms")) {
                        obj.optLong("published_at_ms")
                    } else {
                        null
                    }
                    val summary = obj.optString("summary", "")
                    val fullText = obj.optString("full_text", "").ifBlank { summary }
                    val imgArr = obj.optJSONArray("image_urls")
                    val imageUrls = buildList {
                        if (imgArr != null) {
                            for (j in 0 until imgArr.length()) {
                                val u = imgArr.optString(j, "").trim()
                                if (u.isNotEmpty()) add(u)
                            }
                        }
                    }
                    add(
                        NewsArticle(
                            id = obj.optString("id", "cached-$i"),
                            title = title,
                            summary = summary,
                            publishedAtText = obj.optString("published_at_text", ""),
                            publishedAtMs = publishedAtMs,
                            link = obj.optString("link", ""),
                            fullText = fullText,
                            imageUrls = imageUrls,
                            fullContentLoaded = obj.optBoolean("full_content_loaded", false)
                        )
                    )
                }
            }
            if (articles.isEmpty()) null else CachedNewsFeed(sourceId, articles, fetchedAtMs)
        }.getOrNull()
    }

    fun saveCachedFeed(feed: CachedNewsFeed) {
        val array = JSONArray()
        for (article in feed.articles) {
            val imgs = JSONArray()
            article.imageUrls.forEach { imgs.put(it) }
            array.put(
                JSONObject()
                    .put("id", article.id)
                    .put("title", article.title)
                    .put("summary", article.summary)
                    .put("published_at_text", article.publishedAtText)
                    .put("published_at_ms", article.publishedAtMs)
                    .put("link", article.link)
                    .put("full_text", article.fullText)
                    .put("image_urls", imgs)
                    .put("full_content_loaded", article.fullContentLoaded)
            )
        }
        val payload = JSONObject()
            .put("source_id", feed.sourceId)
            .put("fetched_at_ms", feed.fetchedAtMs)
            .put("articles", array)
        prefs.edit().putString(cacheKey(feed.sourceId), payload.toString()).apply()
    }

    private fun cacheKey(sourceId: String): String = "feed_cache_v2_$sourceId"

    companion object {
        private const val PREFS_NAME = "news_briefing_prefs"
        private const val KEY_SELECTED_SOURCE = "selected_source_id"
        const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
