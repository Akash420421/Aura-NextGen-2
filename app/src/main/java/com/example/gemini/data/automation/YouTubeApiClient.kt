package com.example.gemini.data.automation

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * YouTube search client combining optional YouTube Data API v3 lookup with a
 * zero-quota direct HTML search scraper fallback so song/video resolution never
 * stalls on search result screens when no Data API key is configured.
 */
object YouTubeApiClient {

    private const val TAG = "YouTubeApiClient"

    data class VideoResult(val videoId: String, val title: String, val channelTitle: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val networkExecutor = Executors.newCachedThreadPool()

    fun searchTopVideo(apiKey: String, query: String): VideoResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                networkExecutor.submit<VideoResult?> { searchTopVideoInternal(apiKey, query) }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Background worker searchTopVideo failed: ${e.message}")
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
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val items = JSONObject(body).optJSONArray("items")
                            if (items != null && items.length() > 0) {
                                val item = items.getJSONObject(0)
                                val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
                                val snippet = item.optJSONObject("snippet")
                                val title = snippet?.optString("title")?.takeIf { it.isNotBlank() } ?: clean
                                val channel = snippet?.optString("channelTitle").orEmpty()
                                if (videoId.isNotBlank()) {
                                    Log.d(TAG, "Found video via Data API: $videoId ($title)")
                                    return VideoResult(videoId, title, channel)
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "YouTube Data API search returned HTTP ${response.code}, falling back to direct search")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube API search failed: ${e.message}, falling back to direct search")
            }
        }

        // Zero-quota fallback works without an API key or when Data API quota is exhausted.
        return searchTopVideoDirectInternal(clean)
    }

    /**
     * Parses YouTube's initial search HTML payload to select the best-matching full video
     * (`videoId` + title) without requiring any API key or UI search-screen clicks.
     */
    fun searchTopVideoDirect(query: String): VideoResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                networkExecutor.submit<VideoResult?> { searchTopVideoDirectInternal(query) }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Background worker searchTopVideoDirect failed: ${e.message}")
                null
            }
        }
        return searchTopVideoDirectInternal(query)
    }

    private fun fetchSearchHtmlAndPickBest(url: String, cleanQuery: String, desktopUa: Boolean): VideoResult? {
        val ua = if (desktopUa) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .header("Cookie", "CONSENT=YES+1; PREF=tz=UTC")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "YouTube search HTTP ${response.code} for $url")
                return null
            }
            val html = response.body?.string() ?: return null
            val parsed = YouTubeSearchHelper.parseBestVideoFromHtml(html, cleanQuery) ?: return null
            Log.i(TAG, "Direct search matched video: ${parsed.videoId} (${parsed.title})")
            return VideoResult(parsed.videoId, parsed.title, parsed.channelTitle)
        }
    }

    private fun searchTopVideoDirectInternal(query: String): VideoResult? {
        val clean = YouTubeController.cleanQuery(query).ifBlank { query.trim() }
        if (clean.isBlank()) return null

        val encodedQuery = URLEncoder.encode(clean, "UTF-8")
        return try {
            fetchSearchHtmlAndPickBest(
                "https://www.youtube.com/results?search_query=$encodedQuery&gl=IN&hl=en",
                clean,
                desktopUa = true
            ) ?: fetchSearchHtmlAndPickBest(
                "https://m.youtube.com/results?search_query=$encodedQuery&gl=IN&hl=en",
                clean,
                desktopUa = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Direct search failed: ${e.message}", e)
            null
        }
    }
}
