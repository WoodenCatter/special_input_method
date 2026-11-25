package com.example.input_ds.news

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

object NewsImageLoader {
    private const val MAX_DECODED_EDGE_PX = 1280
    private val cache = LruCache<String, ImageBitmap>(24)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getCached(url: String): ImageBitmap? = synchronized(cache) { cache.get(url) }

    suspend fun load(url: String, refererUrl: String? = null): ImageBitmap? {
        if (url.isBlank()) return null
        getCached(url)?.let { return it }

        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .header("Accept", "image/jpeg, image/png, image/webp, */*;q=0.5")

                val referer = buildReferer(refererUrl, url)
                if (referer != null) {
                    requestBuilder.header("Referer", referer)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    if (bytes.isEmpty()) return@use null
                    decodeSampledBitmap(bytes)?.asImageBitmap()
                }
            }.getOrNull()
        } ?: return null

        synchronized(cache) {
            cache.put(url, decoded)
        }
        return decoded
    }

    private fun decodeSampledBitmap(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODED_EDGE_PX ||
            bounds.outHeight / sampleSize > MAX_DECODED_EDGE_PX
        ) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun buildReferer(articleLink: String?, imageUrl: String): String? {
        val target = articleLink?.takeIf { it.isNotBlank() } ?: imageUrl
        return runCatching {
            val uri = URI(target)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else null
        }.getOrNull()
    }
}
