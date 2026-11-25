package com.example.input_ds.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface NewsFeedFetcher {
    suspend fun fetch(source: NewsSource): List<NewsArticle>
}

class NewsFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

object OkHttpNewsFeedFetcher : NewsFeedFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun fetch(source: NewsSource): List<NewsArticle> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) BCI-MorningBriefing/1.0")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
            .build()

        val bodyText = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw NewsFetchException("新闻源暂时不可用（HTTP ${response.code}）")
                }
                response.body?.string().orEmpty()
            }
        } catch (error: NewsFetchException) {
            throw error
        } catch (error: IOException) {
            throw NewsFetchException("网络连接失败，请检查网络或切换其他新闻源", error)
        }

        val articles = RssFeedParser.parse(bodyText)
        if (articles.isEmpty()) {
            throw NewsFetchException("未能解析到「${source.name}」的新闻内容")
        }
        articles
    }
}
