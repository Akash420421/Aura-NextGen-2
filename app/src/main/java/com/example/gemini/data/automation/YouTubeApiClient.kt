package com.example.gemini.data.automation

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
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

    private fun searchTopVideoDirectInternal(query: String): VideoResult? {
        val clean = YouTubeController.cleanQuery(query).ifBlank { query.trim() }
        if (clean.isBlank()) return null

        return try {
            val encodedQuery = URLEncoder.encode(clean, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encodedQuery&gl=IN&hl=en"
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .header("Cookie", "CONSENT=YES+1; PREF=tz=UTC")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Direct web search failed: HTTP ${response.code}")
                    return null
                }
                val html = response.body?.string() ?: return null
                parseBestVideoFromHtml(html, clean)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct search failed: ${e.message}", e)
            null
        }
    }

    private fun parseBestVideoFromHtml(html: String, query: String): VideoResult? {
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }

        // Extract multiple videoRenderer blocks (~first 8) and score against the query so we don't pick a random ad/Short.
        val blockRegex = Regex("\"videoRenderer\"\\s*:\\s*\\{\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\".*?\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"")
        val candidates = mutableListOf<Pair<VideoResult, Int>>()

        for ((index, match) in blockRegex.findAll(html).take(8).withIndex()) {
            val id = match.groupValues[1]
            val title = decodeHtmlTitle(match.groupValues[2])
            val snippetWindow = html.substring(match.range.first, (match.range.last + 600).coerceAtMost(html.length))
            val isShortUrl = snippetWindow.contains("\"/shorts/$id\"")
            val titleLower = title.lowercase(Locale.ROOT)
            var score = (8 - index) * 2
            score += queryTokens.count { token -> titleLower.contains(token) } * 5
            if (titleLower.contains("official") || titleLower.contains("lyrics") || titleLower.contains("audio")) score += 2
            if (isShortUrl && !query.contains("short", ignoreCase = true)) score -= 8
            candidates.add(VideoResult(id, title, "") to score)
        }

        val bestScored = candidates.maxByOrNull { it.second }?.first
        if (bestScored != null) {
            Log.i(TAG, "Direct search matched video: ${bestScored.videoId} (${bestScored.title})")
            return bestScored
        }

        val fallbackId = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
            ?: Regex("""watch\?v=([a-zA-Z0-9_-]{11})""").find(html)?.groupValues?.get(1)
            ?: return null
        val fallbackTitle = Regex("\"title\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)?.let(::decodeHtmlTitle)
            ?: query
        Log.i(TAG, "Direct search matched fallback video: $fallbackId ($fallbackTitle)")
        return VideoResult(fallbackId, fallbackTitle, "")
    }

    private fun decodeHtmlTitle(raw: String): String =
        raw.replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
}
