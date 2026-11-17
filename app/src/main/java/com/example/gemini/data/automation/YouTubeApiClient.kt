package com.example.gemini.data.automation

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * YouTube Data API v3 search endpoint client with fallback.
 */
object YouTubeApiClient {

    data class VideoResult(val videoId: String, val title: String, val channelTitle: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val networkExecutor = java.util.concurrent.Executors.newCachedThreadPool()

    fun searchTopVideo(apiKey: String, query: String): VideoResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                networkExecutor.submit<VideoResult?> { searchTopVideoInternal(apiKey, query) }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.e("YouTubeApiClient", "Background worker searchTopVideo failed: ${e.message}")
                null
            }
        }
        return searchTopVideoInternal(apiKey, query)
    }

    private fun searchTopVideoInternal(apiKey: String, query: String): VideoResult? {
        val clean = YouTubeController.cleanQuery(query).ifBlank { query.trim() }
        if (clean.isBlank()) return null

        if (apiKey.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(clean, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet&type=video&maxResults=1&q=$encodedQuery&regionCode=IN&key=$apiKey"

            val request = Request.Builder().url(url).get().build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            val item = items.getJSONObject(0)
                            val videoId = item.optJSONObject("id")?.optString("videoId") ?: ""
                            val snippet = item.optJSONObject("snippet")
                            val title = snippet?.optString("title") ?: clean
                            val channel = snippet?.optString("channelTitle") ?: ""

                            if (videoId.isNotBlank()) {
                                android.util.Log.d("YouTubeApiClient", "Found video via Data API: $videoId ($title)")
                                return VideoResult(videoId, title, channel)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("YouTubeApiClient", "YouTube API search failed: ${e.message}")
            }
        }

        return null
    }
}
