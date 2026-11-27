package com.example.gemini.data.live

import java.util.Locale

/**
 * Pure-Kotlin conversation lookup and ranking engine for voice chat switching.
 * Prioritizes pinned conversations, strips Hindi/Hinglish/English command fillers,
 * and scores exact, prefix, substring, and token matches across title, project, and summary.
 */
object ChatMatcher {

    data class ChatCandidate(
        val id: String,
        val title: String,
        val projectName: String = "",
        val workspaceUri: String = "",
        val summary: String = "",
        val updatedAt: Long = 0L,
        val isPinned: Boolean = false,
        val isArchived: Boolean = false,
        val agentName: String = "AGY"
    )

    data class MatchResult(
        val candidate: ChatCandidate,
        val score: Int,
        val reason: String
    )

    private val FILLER_TOKENS = setOf(
        "chat", "chats", "session", "conversation", "thread",
        "kholo", "khol", "kholdo", "open", "show", "dikhao", "dikha",
        "jao", "me", "mein", "par", "pe", "ko", "ka", "ki", "ke",
        "wala", "wali", "wale", "wo", "woh", "jo", "hai", "tha",
        "please", "pls", "the", "a", "an", "that", "this", "named", "naam", "jiska"
    )

    private val PIN_TOKENS = setOf(
        "pin", "pinned", "upar", "top", "sabse"
    )

    private val NON_ALNUM_UNICODE = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun normalizeText(raw: String): String =
        HindiAsrNormalizer.normalize(raw)
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM_UNICODE, " ")
            .trim()

    fun tokenizeContent(raw: String): List<String> =
        normalizeText(raw)
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in FILLER_TOKENS }

    fun findBestMatch(rawQuery: String, candidates: List<ChatCandidate>): MatchResult? {
        if (candidates.isEmpty()) return null
        val normalizedQuery = normalizeText(rawQuery)
        if (normalizedQuery.isBlank()) return null

        val rawTokens = tokenizeContent(rawQuery)
        val hasPinIntent = rawTokens.any { it in PIN_TOKENS } ||
            normalizedQuery.contains("pin kiya") ||
            normalizedQuery.contains("sabse upar")
        val wantsArchived = normalizedQuery.contains("archiv")

        val contentTokens = rawTokens.filterNot { it in PIN_TOKENS }
        val cleanQueryPhrase = contentTokens.joinToString(" ").trim()

        // Pure pinned request ("pinned chat kholo", "jo chat pin hai usko kholo", "top chat kholo")
        if (hasPinIntent && contentTokens.isEmpty()) {
            val topPinned = candidates
                .filter { it.isPinned && (!it.isArchived || wantsArchived) }
                .maxByOrNull { it.updatedAt }
                ?: candidates.filter { it.isPinned }.maxByOrNull { it.updatedAt }
            if (topPinned != null) {
                return MatchResult(topPinned, 900, "top_pinned")
            }
        }

        // Exact ID prefix fallback if query looks like an ID
        val trimmedRaw = rawQuery.trim()
        if (trimmedRaw.length >= 6 && !trimmedRaw.contains(" ")) {
            candidates.firstOrNull { it.id == trimmedRaw || it.id.startsWith(trimmedRaw) }?.let {
                return MatchResult(it, 1100, "exact_id")
            }
        }

        var best: MatchResult? = null
        for (cand in candidates) {
            val normTitle = normalizeText(cand.title)
            val normProject = normalizeText(
                cand.projectName.ifBlank {
                    cand.workspaceUri.removePrefix("file://").trimEnd('/').substringAfterLast('/')
                }
            )
            val normSummary = normalizeText(cand.summary)
            val titleTokens = normTitle.split(" ").filter { it.length >= 2 }.toSet()
            val projectTokens = normProject.split(" ").filter { it.length >= 2 }.toSet()

            var baseScore = 0
            var reason = ""

            if (cleanQueryPhrase.isNotBlank()) {
                when {
                    normTitle == cleanQueryPhrase || normTitle == normalizedQuery -> {
                        baseScore = 1000
                        reason = "exact_title"
                    }
                    normTitle.length >= 4 && (
                        normTitle.startsWith(cleanQueryPhrase) ||
                            cleanQueryPhrase.startsWith(normTitle)
                        ) -> {
                        baseScore = 820
                        reason = "prefix_title"
                    }
                    cleanQueryPhrase.length >= 3 && (
                        normTitle.contains(cleanQueryPhrase) ||
                            (normTitle.length >= 4 && cleanQueryPhrase.contains(normTitle))
                        ) -> {
                        baseScore = 680
                        reason = "substring_title"
                    }
                }
            }

            if (baseScore == 0 && contentTokens.isNotEmpty()) {
                val matchedTitleTokens = contentTokens.count { qt ->
                    titleTokens.any { tt -> tt == qt || (qt.length >= 3 && (tt.contains(qt) || qt.contains(tt))) }
                }
                val matchedProjTokens = contentTokens.count { qt ->
                    projectTokens.any { pt -> pt == qt || (qt.length >= 3 && (pt.contains(qt) || qt.contains(pt))) }
                }
                val matchedSummaryTokens = contentTokens.count { qt ->
                    qt.length >= 3 && normSummary.contains(qt)
                }

                if (matchedTitleTokens > 0 || matchedProjTokens > 0 || matchedSummaryTokens >= 2) {
                    val ratio = ((matchedTitleTokens + matchedProjTokens).toFloat() / contentTokens.size.toFloat())
                    baseScore = matchedTitleTokens * 140 + matchedProjTokens * 90 + matchedSummaryTokens * 35 + (ratio * 120).toInt()
                    reason = "token_overlap"
                }
            }

            if (baseScore > 0) {
                var finalScore = baseScore
                if (cand.isPinned) {
                    finalScore += if (hasPinIntent) 180 else 90
                }
                if (cand.isArchived && !wantsArchived) {
                    finalScore -= 40
                }
                if (best == null || finalScore > best.score || (finalScore == best.score && cand.updatedAt > best.candidate.updatedAt)) {
                    best = MatchResult(cand, finalScore, reason)
                }
            }
        }

        return best
    }

    /**
     * Detects phrases meant to switch chats (used as safety net if Voice calls click_ui_element on Aura).
     */
    fun isLikelyChatOpenPhrase(targetName: String): Boolean {
        val lower = targetName.trim().lowercase(Locale.ROOT)
        return lower.contains("wala chat") ||
            lower.contains("wali chat") ||
            lower.contains("wale chat") ||
            lower.contains("pinned chat") ||
            lower.contains("pin chat") ||
            lower.endsWith(" chat") ||
            lower.contains("chat kholo") ||
            lower.contains("chat open")
    }
}
