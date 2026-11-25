package com.example.gemini.data.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.example.gemini.data.live.ChatContextBridge
import com.example.gemini.ui.browser.BrowserSessionManager
import java.util.Locale

/**
 * YouTube controller for Aura:
 * - Resolves named OR vague ("your choice") song requests directly to a real videoId (0 quota)
 * - Plays inside native YouTube or Aura's In-Built Browser
 * - Handles media keys and smart UI player actions (Skip Ad, Like, Subscribe, Fullscreen)
 */
object YouTubeController {

    private const val TAG = "YouTubeController"

    data class PlayResult(val success: Boolean, val title: String?, val message: String?)

    private val CURATED_HIT_SONGS = listOf(
        "Kesariya Arijit Singh official video song",
        "Apna Bana Le Arijit Singh official song",
        "Chaleya Jawan Arijit Singh official video",
        "Heeriye Jasleen Royal Arijit Singh official",
        "Husn Anuv Jain official audio",
        "Tauba Tauba Karan Aujla official video",
        "Pehle Bhi Main Animal Vishal Mishra",
        "Tere Vaaste Zara Hatke Zara Bachke",
        "Ve Kamleya Rocky Aur Rani Arijit Singh",
        "Satranga Animal Arijit Singh official",
        "Sajni Laapataa Ladies Arijit Singh",
        "Softly Karan Aujla official music video"
    )

    private var lastPickedIndex = -1

    fun cleanQuery(rawQuery: String): String {
        var clean = rawQuery.trim()
        val patterns = listOf(
            Regex("""\b(on|in|from|or|and)\s+(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\s+(pe|par|mein|me|video|song|songs|audio)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(play|open|search|listen\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(chalao|chala\s+do|karo|kar\s+do|lagao|laga\s+do|sunao|suna\s+do|bajao|baja\s+do|dikhao|dikha\s+do)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            clean = pattern.replace(clean, " ")
        }
        clean = clean.replace(Regex("""(?i)\s+(song|songs|video|videos|gana|gaana|gane|track)$"""), "")
        val result = clean.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', '!', '?', '"', '\'')
        return result.ifBlank { "" }
    }

    private fun isVagueSurpriseQuery(rawQuery: String, cleanQ: String): Boolean {
        val rawLower = rawQuery.lowercase(Locale.ROOT)
        val cleanLower = cleanQ.lowercase(Locale.ROOT)
        val vagueMarkers = listOf(
            "apni marzi", "apne marzi", "apne man", "apni pasand", "man pasand",
            "marzi se", "maje se", "man se", "tumhari marzi", "teri marzi",
            "koi bhi", "koi accha", "koi achha", "koi badhiya", "koi badiya",
            "your choice", "surprise me", "random song", "any good song",
            "any song", "anything good", "best song", "good song"
        )
        if (vagueMarkers.any { rawLower.contains(it) || cleanLower.contains(it) }) {
            // Strip generic filler and check whether any concrete artist/song token remains
            val residual = cleanLower
                .replace(Regex("""\b(koi|bhi|apni|apne|marzi|maje|man|pasand|se|tumhari|teri|accha|achha|acha|badhiya|badiya|best|good|nice|random|any|anything|surprise|me|your|choice|song|songs|gaana|gana|gane|music|track|ek|aur)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            return residual.length < 3
        }
        return cleanLower in setOf("song", "songs", "gaana", "gana", "gane", "music", "track", "audio", "koi gaana", "koi gana")
    }

    private fun pickCuratedSongQuery(): String {
        val candidates = CURATED_HIT_SONGS.indices.filter { it != lastPickedIndex }
        val nextIdx = candidates.randomOrNull() ?: 0
        lastPickedIndex = nextIdx
        return CURATED_HIT_SONGS[nextIdx]
    }

    fun searchAndPlay(context: Context, query: String, explicitApiKey: String? = null): PlayResult {
        val isBackground = query.contains("background", ignoreCase = true) ||
            query.contains("browser", ignoreCase = true) ||
            query.contains("inbuild", ignoreCase = true) ||
            query.contains("in-built", ignoreCase = true) ||
            query.contains("inbuilt", ignoreCase = true)

        val cleanedInput = query
            .replace(Regex("""(?i)\b(in\s+)?(the\s+)?(in[-\s]?built|inbuild|inbuilt)\s*(browser)?\b"""), " ")
            .replace(Regex("""(?i)\b(background\s*(browser)?|browser\s+mein)\b"""), " ")
            .trim()
        val rawCleanQ = cleanQuery(cleanedInput)

        val isJustOpenYouTube = !isVagueSurpriseQuery(cleanedInput, rawCleanQ) && (
            rawCleanQ.isBlank() ||
            rawCleanQ.equals("youtube", ignoreCase = true) ||
            rawCleanQ.equals("yt", ignoreCase = true) ||
            rawCleanQ.equals("youtube app", ignoreCase = true)
        )
        if (isJustOpenYouTube && !isBackground) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (launchIntent != null && ActivityLauncherHelper.startActivitySafely(context, launchIntent)) {
                return PlayResult(true, null, "Opened native YouTube app.")
            }
            BrowserSessionManager.instance.openUrl("https://m.youtube.com", newTab = false)
            ChatContextBridge.requestViewMode("BROWSER")
            return PlayResult(true, null, "YouTube app not installed; opened YouTube in Aura In-Built Browser.")
        }

        val effectiveQuery = if (isVagueSurpriseQuery(cleanedInput, rawCleanQ)) {
            pickCuratedSongQuery().also {
                Log.i(TAG, "Vague song request \"$query\" -> resolved to curated track \"$it\"")
            }
        } else {
            rawCleanQ.ifBlank { cleanedInput.ifBlank { pickCuratedSongQuery() } }
        }

        val apiKey = explicitApiKey.orEmpty()
        Log.i(TAG, "searchAndPlay requested for: \"$query\" -> effectiveQuery: \"$effectiveQuery\" (inBrowser=$isBackground)")

        // Zero-quota direct HTML lookup + optional Data API resolution
        val resolved = YouTubeApiClient.searchTopVideo(apiKey, effectiveQuery)
        val videoId = resolved?.videoId?.takeIf { it.isNotBlank() }
        val resolvedTitle = resolved?.title?.takeIf { it.isNotBlank() } ?: effectiveQuery

        if (isBackground) {
            return playInBackgroundBrowser(context, effectiveQuery, resolved)
        }

        if (!videoId.isNullOrBlank()) {
            AutomationAccessibilityService.instance?.armYouTubeAdSkipWatcher()
            val played = playVideoId(context, videoId)
            if (played) {
                return PlayResult(true, resolvedTitle, "Playing \"$resolvedTitle\" on YouTube (auto ad-skip armed).")
            }
        }

        // Fallback: launch YouTube search results with modern Litho auto-play + ad-skip watcher
        return try {
            AutomationAccessibilityService.instance?.armYouTubePlay()
            AutomationAccessibilityService.instance?.armYouTubeAdSkipWatcher()

            val encoded = Uri.encode(effectiveQuery)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val started = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (started) {
                PlayResult(true, resolvedTitle, "Opening \"$resolvedTitle\" on YouTube.")
            } else {
                playInBackgroundBrowser(context, effectiveQuery, resolved)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback fallback failed", e)
            PlayResult(false, null, "Couldn't open YouTube: ${e.message}")
        }
    }

    fun playInBackgroundBrowser(
        context: Context,
        query: String,
        preResolved: YouTubeApiClient.VideoResult? = null
    ): PlayResult {
        val cleanQ = cleanQuery(query).ifBlank { pickCuratedSongQuery() }
        val resolved = preResolved ?: YouTubeApiClient.searchTopVideo("", cleanQ)
        val url = if (!resolved?.videoId.isNullOrBlank()) {
            "https://m.youtube.com/watch?v=${resolved!!.videoId}"
        } else {
            "https://m.youtube.com/results?search_query=${Uri.encode(cleanQ)}"
        }
        val title = resolved?.title?.takeIf { it.isNotBlank() } ?: cleanQ
        return try {
            BrowserSessionManager.instance.openUrl(url, newTab = false)
            ChatContextBridge.requestViewMode("BROWSER")
            PlayResult(true, title, "Playing \"$title\" in Aura In-Built Browser ($url).")
        } catch (e: Exception) {
            Log.e(TAG, "In-built browser playback failed, falling back to YouTube intent", e)
            searchYouTube(context, cleanQ)
        }
    }

    fun searchYouTube(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        if (cleanQ.isBlank() || cleanQ.equals("youtube", ignoreCase = true)) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (launchIntent != null && ActivityLauncherHelper.startActivitySafely(context, launchIntent)) {
                return PlayResult(true, null, "Opened native YouTube app.")
            }
        }
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
                val browserUrl = if (cleanQ.isBlank()) "https://m.youtube.com" else "https://m.youtube.com/results?search_query=$encoded"
                BrowserSessionManager.instance.openUrl(browserUrl, newTab = false)
                ChatContextBridge.requestViewMode("BROWSER")
                PlayResult(true, null, "YouTube app not installed; showing results for \"$cleanQ\" in Aura In-Built Browser.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search intent failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    fun playVideoId(context: Context, videoId: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (ActivityLauncherHelper.startActivitySafely(context, intent)) {
                true
            } else {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, appIntent)) {
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
            Log.e(TAG, "Deep link play failed", e)
            false
        }
    }

    fun sendMediaKey(context: Context, action: String): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val keyCode = when (action.lowercase(Locale.ROOT)) {
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
            Log.e(TAG, "Media key dispatch failed", e)
            false
        }
    }

    /**
     * Executes smart YouTube player actions (`skip_ad`, `like`, `subscribe`, `fullscreen`, `play_first`,
     * `play`, `pause`, `next`, `previous`) in either Aura's In-Built Browser (when active on YouTube)
     * or the foreground native YouTube app via Accessibility.
     */
    suspend fun performPlayerAction(context: Context, action: String): PlayResult {
        val normalized = action.trim().lowercase(Locale.ROOT)
        if (ChatContextBridge.isInBuiltBrowserActive()) {
            val activeUrl = BrowserSessionManager.instance.getActiveTab()?.let { it.webView?.url ?: it.url }.orEmpty().lowercase(Locale.ROOT)
            if (activeUrl.contains("youtube") || activeUrl.contains("youtu.be")) {
                val res = BrowserSessionManager.instance.controlWebVideo(normalized)
                return PlayResult(!res.startsWith("Failed", ignoreCase = true) && !res.startsWith("No ", ignoreCase = true), null, res)
            }
        }

        val svc = AutomationAccessibilityService.instance
        return when (normalized) {
            "skip_ad", "skip", "skip_ads", "ad_skip" -> {
                if (svc == null) return PlayResult(false, null, "Accessibility Service is not running; cannot click YouTube Skip Ad.")
                val skipped = svc.skipYouTubeAd()
                if (skipped) {
                    PlayResult(true, null, "Clicked YouTube Skip Ad button.")
                } else {
                    svc.armYouTubeAdSkipWatcher(20_000L)
                    PlayResult(false, null, "Skip Ad button is not clickable yet (ad timer may still be counting down). Auto ad-skip watcher has been armed and will skip it automatically as soon as 'Skip' appears.")
                }
            }
            "like" -> {
                if (svc == null) return PlayResult(false, null, "Accessibility Service is not running.")
                val ok = svc.likeYouTubeVideo()
                PlayResult(ok, null, if (ok) "Liked the YouTube video." else "Could not find YouTube Like button on screen.")
            }
            "subscribe" -> {
                if (svc == null) return PlayResult(false, null, "Accessibility Service is not running.")
                val ok = svc.subscribeYouTubeChannel()
                PlayResult(ok, null, if (ok) "Clicked Subscribe on YouTube." else "Could not find Subscribe button on screen.")
            }
            "fullscreen" -> {
                if (svc == null) return PlayResult(false, null, "Accessibility Service is not running.")
                val ok = svc.toggleYouTubeFullscreen()
                PlayResult(ok, null, if (ok) "Toggled YouTube fullscreen." else "Could not find Fullscreen button on screen.")
            }
            "play_first", "play_visible" -> {
                if (svc == null) return PlayResult(false, null, "Accessibility Service is not running.")
                val ok = svc.clickFirstYouTubeVideoNow()
                PlayResult(ok, null, if (ok) "Tapped first visible YouTube video." else "No visible YouTube video card found to play.")
            }
            else -> {
                val ok = sendMediaKey(context, normalized)
                PlayResult(ok, null, if (ok) "Dispatched media action '$normalized'." else "Unsupported or failed media action '$normalized'.")
            }
        }
    }
}
