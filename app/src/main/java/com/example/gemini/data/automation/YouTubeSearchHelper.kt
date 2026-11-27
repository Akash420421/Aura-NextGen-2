package com.example.gemini.data.automation

import java.util.Locale

/**
 * Pure-Kotlin helper for YouTube query cleaning, multi-format HTML search parsing
 * (videoRenderer, lockupViewModel, compactVideoRenderer), and Accessibility video card filtering (#16).
 */
object YouTubeSearchHelper {

    data class ParsedVideoCandidate(
        val videoId: String,
        val title: String,
        val channelTitle: String = "",
        val score: Int = 0
    )

    private val CLEAN_PATTERNS = listOf(
        Regex("""\b(on|in|from|or|and)\s+(youtube|yt)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(youtube|yt)\s+(pe|par|mein|me|par\s+ja\s*kar|video|song|songs|audio)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(youtube|yt)\b""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(play|open|search|listen\s+to|select)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(chalao|chala\s+do|karo|kar\s+do|lagao|laga\s+do|sunao|suna\s+do|bajao|baja\s+do|dikhao|dikha\s+do|select\s+karo)\b""", RegexOption.IGNORE_CASE)
    )

    fun cleanQuery(rawQuery: String): String {
        var clean = rawQuery.trim()
        for (pattern in CLEAN_PATTERNS) {
            clean = pattern.replace(clean, " ")
        }
        clean = clean.replace(Regex("""(?i)\s+(song|songs|video|videos|gana|gaana|gane|track)\s*(wala|wali|ka|ke|ki)?$"""), "")
        clean = clean.replace(Regex("""(?i)\s+(ka|ke|ki)$"""), "")
        return clean.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', '!', '?', '"', '\'')
    }

    fun decodeHtmlString(raw: String): String =
        raw.replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    /**
     * Parses top matching playable video from YouTube HTML search payload.
     * Supports legacy `videoRenderer`, mobile `compactVideoRenderer`, and 2025/2026 `lockupViewModel`.
     */
    fun parseBestVideoFromHtml(html: String, query: String): ParsedVideoCandidate? {
        if (html.isBlank()) return null
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val wantsShort = query.contains("short", ignoreCase = true)

        val candidates = mutableListOf<ParsedVideoCandidate>()
        val seenIds = HashSet<String>()

        fun addScored(id: String, rawTitle: String, snippet: String, rankIdx: Int) {
            if (id.length != 11 || !seenIds.add(id)) return
            val title = decodeHtmlString(rawTitle).ifBlank { query }
            val titleLower = title.lowercase(Locale.ROOT)
            val isShort = snippet.contains("\"/shorts/$id\"") ||
                titleLower.contains("#shorts") ||
                titleLower.contains("#short")
            val isAd = snippet.contains("adSlotRenderer", ignoreCase = true) ||
                snippet.contains("promotedSparkles", ignoreCase = true) ||
                titleLower.startsWith("sponsored")
            if (isAd) return

            var score = (12 - rankIdx).coerceAtLeast(1) * 2
            score += queryTokens.count { token -> titleLower.contains(token) } * 5
            if (titleLower.contains("official") || titleLower.contains("lyrics") || titleLower.contains("audio")) score += 2
            if (isShort && !wantsShort) score -= 10
            candidates.add(ParsedVideoCandidate(videoId = id, title = title, score = score))
        }

        // 1. Classic videoRenderer / compactVideoRenderer blocks (multiline-safe bounded window)
        val rendererMarker = Regex(""""(?:videoRenderer|compactVideoRenderer)"\s*:\s*\{"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
        val runsTitleRegex = Regex(""""title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"((?:[^"\\]|\\.)+)"""")
        val simpleTitleRegex = Regex(""""title"\s*:\s*\{\s*"simpleText"\s*:\s*"((?:[^"\\]|\\.)+)"""")
        for ((idx, m) in rendererMarker.findAll(html).take(12).withIndex()) {
            val id = m.groupValues[1]
            val window = html.substring((m.range.first - 80).coerceAtLeast(0), (m.range.first + 2400).coerceAtMost(html.length))
            val fwdWindow = html.substring(m.range.first, (m.range.first + 2400).coerceAtMost(html.length))
            val title = runsTitleRegex.find(fwdWindow)?.groupValues?.get(1)
                ?: simpleTitleRegex.find(fwdWindow)?.groupValues?.get(1)
                ?: ""
            addScored(id, title, window, idx)
        }

        // 2. Modern 2025/2026 YouTube lockupViewModel blocks
        val lockupMarker = Regex(""""lockupViewModel"\s*:\s*\{""")
        val lockupIdRegex = Regex(""""contentId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
        val lockupTitleRegex = Regex(""""title"\s*:\s*\{\s*"content"\s*:\s*"((?:[^"\\]|\\.)+)"""")
        for ((idx, m) in lockupMarker.findAll(html).take(12).withIndex()) {
            val window = html.substring(m.range.first, (m.range.first + 2600).coerceAtMost(html.length))
            if (window.contains("LOCKUP_CONTENT_TYPE_PLAYLIST") && !query.contains("playlist", ignoreCase = true)) {
                continue
            }
            val id = lockupIdRegex.find(window)?.groupValues?.get(1) ?: continue
            val title = lockupTitleRegex.find(window)?.groupValues?.get(1) ?: ""
            addScored(id, title, window, idx)
        }

        candidates.maxByOrNull { it.score }?.let { return it }

        // 3. Final generic videoId fallback
        val fallbackId = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""watch\?v=([a-zA-Z0-9_-]{11})""").find(html)?.groupValues?.get(1)
            ?: return null
        val fallbackTitle = runsTitleRegex.find(html)?.groupValues?.get(1)?.let(::decodeHtmlString) ?: query
        return ParsedVideoCandidate(videoId = fallbackId, title = fallbackTitle, score = 1)
    }

    /**
     * Filters Android Accessibility candidates in YouTube so sponsored ads, filter chips,
     * Shorts shelves, and search chrome are never mistakenly clicked as a full video (#16).
     */
    fun isPlayableYouTubeVideoCard(
        viewId: String,
        text: String,
        contentDescription: String,
        topPx: Int,
        heightPx: Int,
        screenHeightPx: Int
    ): Boolean {
        val h = screenHeightPx.coerceAtLeast(1)
        if (topPx < (h * 0.13f).toInt() || topPx > (h * 0.88f).toInt()) return false
        if (heightPx < 110) return false

        val vid = viewId.lowercase(Locale.ROOT)
        val combined = "$text $contentDescription".lowercase(Locale.ROOT).trim()

        // Exclude ads, search/filter chrome, Shorts shelf, and action buttons
        val blockedTerms = listOf(
            "sponsored", "ad ·", "visit advertiser", "install ", "shop now",
            "search youtube", "clear search", "voice search", "navigate up",
            "filter", "subscribe", "notifications", "library", "subscriptions"
        )
        if (blockedTerms.any { combined.contains(it) }) return false
        if (combined == "shorts" || combined.startsWith("shorts ") || combined.contains("#shorts") ||
            vid.contains("reel") || vid.contains("shorts")
        ) {
            return false
        }

        val knownVideoViewIds = listOf(
            "thumbnail", "video_info", "compact_renderer", "watch_player", "video_title"
        )
        if (knownVideoViewIds.any { vid.contains(it) } && combined.length >= 4) return true

        val metaMarkers = listOf(
            "views", "ago", "minute", "minutes", "second", "seconds", "hour", "hours",
            "play video", "go to channel", "official", "lyrics", "audio",
            "बार देखा", "पहले", "मिनट", "सेकंड", "बजाएं"
        )
        if (metaMarkers.any { combined.contains(it) }) return true

        // Large media card with descriptive title text (> 180px tall, >= 12 chars title)
        return heightPx >= 180 && combined.length >= 12
    }

    private val COUNTDOWN_ONLY_REGEX = Regex("""^(?:\d{1,2}\s*s?)$""")
    private val SKIP_IN_COUNTDOWN_REGEX = Regex("""(?i).*\bskip\b.*\bin\s+\d+.*|.*\bad\s+\d+\s+of\s+\d+.*|.*\byou\s+can\s+skip.*""")
    private val NON_AD_SKIP_TERMS = listOf(
        "skip navigation", "skip to content", "skip backward", "skip forward",
        "skip back", "skip ahead", "skip 10", "skip 5", "skip intro", "skip recap"
    )
    private val EXACT_SKIP_LABELS = setOf(
        "skip", "skip ad", "skip ads", "close ad", "dismiss ad",
        "स्किप", "स्किप करें", "विज्ञापन छोड़ें", "छोड़ें"
    )

    fun isCountdownAdLabel(rawLabel: String): Boolean {
        val clean = rawLabel.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false
        return COUNTDOWN_ONLY_REGEX.matches(clean) ||
            SKIP_IN_COUNTDOWN_REGEX.matches(clean) ||
            (clean.contains("remaining") && clean.contains("ad"))
    }

    /**
     * Validates that an Accessibility node represents a genuine clickable YouTube Skip Ad / Close Ad
     * button rather than a pre-roll countdown badge ("5", "Skip in 3s") or "Skip navigation" link (#17).
     */
    fun isSkippableAdButton(
        viewId: String,
        text: String,
        contentDescription: String,
        isVisible: Boolean,
        isEnabled: Boolean,
        widthPx: Int,
        heightPx: Int
    ): Boolean {
        if (!isVisible || !isEnabled) return false
        if (widthPx < 16 || heightPx < 16 || heightPx > 320) return false

        val t = text.trim().lowercase(Locale.ROOT)
        val d = contentDescription.trim().lowercase(Locale.ROOT)
        val vid = viewId.lowercase(Locale.ROOT)
        val combined = "$t $d".trim()

        if (NON_AD_SKIP_TERMS.any { combined.contains(it) }) return false
        if (isCountdownAdLabel(t) || isCountdownAdLabel(d)) return false

        if (t in EXACT_SKIP_LABELS || d in EXACT_SKIP_LABELS) return true
        if ((t.startsWith("skip ad") || d.startsWith("skip ad")) && combined.length <= 24) return true

        val isSkipViewId = vid.contains("skip_ad") || vid.contains("modern_skip_ad") || vid.contains("ad_skip_button")
        if (isSkipViewId) {
            // Accept skip_ad viewIds as long as label is empty or looks like skip text (and not a countdown!)
            return combined.isEmpty() || combined.contains("skip") || combined.contains("स्किप") || combined.contains("छोड़")
        }
        return false
    }
}
