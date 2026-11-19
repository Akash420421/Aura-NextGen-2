package com.example.gemini.data.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent

/**
 * YouTube controller for Jarvis:
 *  - search + jump straight to a video via deep links
 *  - play/pause/skip via standard Android media key events
 */
object YouTubeController {

    data class PlayResult(val success: Boolean, val title: String?, val message: String?)

    fun cleanQuery(rawQuery: String): String {
        var clean = rawQuery.trim()
        val patterns = listOf(
            Regex("""\b(on|in|from|or|and)\s+(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\s+(pe|par|mein|video|song|songs|audio)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(play|open|search|listen\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(chalao|chala\s+do|karo|kar\s+do|lagao|laga\s+do|sunao|suna\s+do|bajao|baja\s+do|dikhao|dikha\s+do)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            clean = pattern.replace(clean, " ")
        }
        clean = clean.replace(Regex("""(?i)\s+(song|songs|video|videos|gana|gane|track)$"""), "")
        val result = clean.replace(Regex("""\s+"""), " ").trim()
        return if (result.isNotBlank()) result else rawQuery.trim()
    }

    fun searchAndPlay(context: Context, query: String, explicitApiKey: String? = null): PlayResult {
        val isBackground = query.contains("background", ignoreCase = true) ||
                query.contains("browser", ignoreCase = true) ||
                query.contains("inbuild", ignoreCase = true) ||
                query.contains("in-built", ignoreCase = true)
        if (isBackground) {
            return playInBackgroundBrowser(context, query)
        }

        var cleanQ = cleanQuery(query)
        if (cleanQ.isBlank() || cleanQ.equals("video", ignoreCase = true) || cleanQ.equals("song", ignoreCase = true) ||
            cleanQ.equals("a video", ignoreCase = true) || cleanQ.equals("a song", ignoreCase = true) ||
            cleanQ.equals("music", ignoreCase = true) || cleanQ.equals("songs", ignoreCase = true)) {
            cleanQ = "Trending music"
        }

        val apiKey = explicitApiKey ?: ""
        android.util.Log.i("YouTubeController", "searchAndPlay requested for: \"$query\" -> clean: \"$cleanQ\"")

        // 1. Try search via YouTubeApiClient if key provided
        val result = YouTubeApiClient.searchTopVideo(apiKey, cleanQ)
        val videoId = result?.videoId?.takeIf { it.isNotBlank() }

        if (!videoId.isNullOrBlank()) {
            val title = result.title.takeIf { it.isNotBlank() } ?: cleanQ
            val played = playVideoId(context, videoId)
            if (played) {
                return PlayResult(true, title, "Playing \"$title\" on YouTube.")
            }
        }

        // 2. Direct intent with accessibility auto-play
        return try {
            AutomationAccessibilityService.instance?.armYouTubePlay()

            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            PlayResult(true, cleanQ, "Playing \"$cleanQ\" on YouTube.")
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Playback fallback failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    fun playInBackgroundBrowser(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        val encoded = Uri.encode(cleanQ)
        val url = "https://m.youtube.com/results?search_query=$encoded"
        return try {
            com.example.gemini.ui.browser.BrowserSessionManager.instance.openUrl(url, newTab = true)
            PlayResult(true, cleanQ, "Playing \"$cleanQ\" in Aura In-Built Browser with background playback.")
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "In-built browser playback failed, falling back to YouTube intent", e)
            searchYouTube(context, query)
        }
    }

    fun searchYouTube(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        return try {
            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (ok) {
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ActivityLauncherHelper.startActivitySafely(context, webIntent)
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "YouTube search intent failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    fun playVideoId(context: Context, videoId: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (launched) {
                true
            } else {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val launchedApp = ActivityLauncherHelper.startActivitySafely(context, appIntent)
                if (launchedApp) {
                    true
                } else {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ActivityLauncherHelper.startActivitySafely(context, webIntent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Deep link play failed", e)
            false
        }
    }

    fun sendMediaKey(context: Context, action: String): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val keyCode = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Media key dispatch failed", e)
            false
        }
    }
}
