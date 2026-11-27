package com.example.gemini.data.live

import java.util.Locale

/**
 * Pure-Kotlin browser target resolver & verifier for In-Built Browser reliability:
 * 1. Exact/name/index tab resolution (prevents closing the wrong tab).
 * 2. Organic link vs ad/search-bar click disambiguation (prevents clicking sponsored ads or search boxes on Google/Amazon).
 * 3. Editable input filtering for browser_type.
 * 4. Search-submit navigation verification (prevents claiming search succeeded when URL/page didn't move).
 * 5. False browser-close guard against noisy ASR slips like "one"/"open" -> "band".
 */
object BrowserTargetHelper {

    data class TabCandidate(
        val id: String,
        val title: String,
        val url: String,
        val isActive: Boolean,
        val index: Int
    )

    data class TabResolveResult(
        val tab: TabCandidate?,
        val error: String? = null
    )

    data class FindMatch(
        val ref: String,
        val role: String,
        val name: String,
        val host: String,
        val isAd: Boolean,
        val inNav: Boolean,
        val offScreen: Boolean,
        val lineOrder: Int,
        val rawLine: String
    )

    data class SubmitVerification(
        val verified: Boolean,
        val navigated: Boolean,
        val reason: String
    )

    private val EDITABLE_ROLES = setOf("textbox", "searchbox", "combobox")

    private val NON_ALNUM = Regex("[^\\p{L}\\p{M}\\p{N}.]+")

    private val FIND_LINE_REGEX = Regex("""^\s*-\s*(ref_\d+):\s*([a-z0-9_-]+)(?:\s+"((?:[^"\\]|\\.)*)")?(.*)$""", RegexOption.IGNORE_CASE)
    private val HOST_TAG_REGEX = Regex("""\(host:\s*([^)]+)\)""", RegexOption.IGNORE_CASE)

    fun resolveTargetTab(
        rawQuery: String?,
        tabs: List<TabCandidate>,
        activeTabId: String?
    ): TabResolveResult {
        if (tabs.isEmpty()) {
            return TabResolveResult(null, "No browser tabs are open.")
        }

        val query = rawQuery?.trim().orEmpty()
        val norm = HindiAsrNormalizer.normalize(query).lowercase(Locale.ROOT).trim()

        if (norm.isBlank() || norm in setOf("current", "active", "this", "this tab", "current tab", "ye tab", "abhi wala", "abhi")) {
            val active = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
            return TabResolveResult(active)
        }

        // 1. Exact ID or unambiguous ID prefix
        tabs.firstOrNull { it.id.equals(query, ignoreCase = true) }?.let {
            return TabResolveResult(it)
        }
        if (query.length >= 6 && !query.contains(" ")) {
            val prefixMatches = tabs.filter { it.id.startsWith(query, ignoreCase = true) }
            if (prefixMatches.size == 1) return TabResolveResult(prefixMatches.first())
        }

        // 2. Positional tab references ("1", "first tab", "dusra tab", "last", "previous")
        val requestedOneBased = when {
            norm == "1" || norm.contains("first") || norm.contains("pahla") || norm.contains("pehle") || norm.contains("pehla") || norm.contains("1st") -> 1
            norm == "2" || norm.contains("second") || norm.contains("dusra") || norm.contains("doosra") || norm.contains("2nd") -> 2
            norm == "3" || norm.contains("third") || norm.contains("teesra") || norm.contains("tisra") || norm.contains("3rd") -> 3
            norm == "4" || norm.contains("fourth") || norm.contains("chautha") || norm.contains("4th") -> 4
            norm.contains("last") || norm.contains("aakhiri") || norm.contains("akhiri") -> tabs.size
            else -> norm.toIntOrNull()
        }
        if (requestedOneBased != null) {
            val idx = requestedOneBased - 1
            return if (idx in tabs.indices) {
                TabResolveResult(tabs[idx])
            } else {
                TabResolveResult(null, "Tab #$requestedOneBased does not exist; only ${tabs.size} tab(s) open: ${formatOpenTabs(tabs)}.")
            }
        }

        if (norm.contains("previous") || norm.contains("pichhla") || norm.contains("pichla") || norm.contains("other")) {
            val nonActive = tabs.filterNot { it.id == activeTabId }
            val activeIdx = tabs.indexOfFirst { it.id == activeTabId }.takeIf { it >= 0 } ?: 0
            val prev = nonActive.lastOrNull { it.index < activeIdx } ?: nonActive.lastOrNull()
            return if (prev != null) {
                TabResolveResult(prev)
            } else {
                TabResolveResult(null, "Only 1 tab is currently open (${tabs.first().title}).")
            }
        }

        // 3. Domain / Title / URL keyword scoring (never falls back to wrong tab if explicit name fails)
        val stop = setOf("tab", "tabs", "wala", "wali", "wale", "page", "site", "website", "open", "close", "band", "karo", "kr", "the", "this")
        val tokens = norm.replace(NON_ALNUM, " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stop }

        if (tokens.isEmpty()) {
            val active = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
            return TabResolveResult(active)
        }

        var bestTab: TabCandidate? = null
        var bestScore = 0
        for (t in tabs) {
            val titleL = t.title.lowercase(Locale.ROOT)
            val urlL = t.url.lowercase(Locale.ROOT)
            var score = 0
            for (tok in tokens) {
                if (urlL.contains(tok)) score += 3
                if (titleL.contains(tok)) score += 4
            }
            // Prefer inactive/popup tab when score ties & user didn't say "current", or keep deterministic order
            if (score > bestScore) {
                bestScore = score
                bestTab = t
            }
        }

        return if (bestTab != null && bestScore > 0) {
            TabResolveResult(bestTab)
        } else {
            TabResolveResult(
                null,
                "Could not find an open tab matching '$rawQuery'. Open tabs: ${formatOpenTabs(tabs)}."
            )
        }
    }

    private fun formatOpenTabs(tabs: List<TabCandidate>): String =
        tabs.joinToString("; ") { "#${it.index + 1} [${it.id.take(8)}] '${it.title}' (${it.url.take(45)})${if (it.isActive) " [ACTIVE]" else ""}" }

    fun parseFindOutput(findText: String): List<FindMatch> {
        if (findText.isBlank()) return emptyList()
        val out = mutableListOf<FindMatch>()
        val lines = findText.lines()
        lines.forEachIndexed { idx, line ->
            val m = FIND_LINE_REGEX.matchEntire(line) ?: return@forEachIndexed
            val ref = m.groupValues[1]
            val role = m.groupValues[2].lowercase(Locale.ROOT)
            val name = m.groupValues[3].replace("\\\"", "\"")
            val tail = m.groupValues[4]
            val host = HOST_TAG_REGEX.find(tail)?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
            val tailLower = tail.lowercase(Locale.ROOT)
            val isAd = tailLower.contains("[sponsored]") || tailLower.contains("(sponsored)")
            val inNav = tailLower.contains("[nav]")
            val offScreen = tailLower.contains("(off-screen)")
            out += FindMatch(
                ref = ref,
                role = role,
                name = name,
                host = host,
                isAd = isAd,
                inNav = inNav,
                offScreen = offScreen,
                lineOrder = idx,
                rawLine = line
            )
        }
        return out
    }

    fun pickBestClickCandidate(query: String, findText: String): FindMatch? {
        val parsed = parseFindOutput(findText)
        if (parsed.isEmpty()) return null

        val qLower = HindiAsrNormalizer.normalize(query).lowercase(Locale.ROOT)
        val userAskedForInput = listOf("input", "box", "field", "textarea", "searchbox", "email", "password", "textbox").any { qLower.contains(it) }
        val userAskedForAd = qLower.contains("sponsored") || qLower.contains("ad ") || qLower.endsWith(" ad")
        val qTokens = qLower.replace(NON_ALNUM, " ")
            .split(" ")
            .filter { it.length >= 2 && it !in setOf("link", "button", "click", "tap", "open", "first", "result", "pe", "par", "wala", "wali") }

        val nonInputs = parsed.filterNot { it.role in EDITABLE_ROLES }
        val pool0 = if (!userAskedForInput && nonInputs.isNotEmpty()) nonInputs else parsed
        val nonAds = pool0.filterNot { it.isAd }
        val pool = if (!userAskedForAd && nonAds.isNotEmpty()) nonAds else pool0

        var best: FindMatch? = null
        var bestScore = Int.MIN_VALUE
        for (item in pool) {
            var score = 1000 - (item.lineOrder * 15)
            val nameL = item.name.lowercase(Locale.ROOT)
            if (item.role == "link" || item.role == "button") score += 120
            if (item.role in EDITABLE_ROLES && !userAskedForInput) score -= 500
            if (item.isAd && !userAskedForAd) score -= 450
            if (item.inNav) score -= 80
            if (item.host.isNotBlank()) {
                for (tok in qTokens) {
                    if (item.host.contains(tok)) score += 180
                }
                if (qLower.contains("google flow") || (qTokens.contains("flow") && qTokens.contains("google"))) {
                    if (item.host.contains("labs.google") || item.host.contains("flow.google")) score += 420
                }
            }
            for (tok in qTokens) {
                if (nameL.contains(tok)) score += 45
            }
            if (!item.offScreen) score += 25
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }

    fun pickBestInputCandidate(query: String, findText: String): FindMatch? {
        val parsed = parseFindOutput(findText)
        if (parsed.isEmpty()) return null
        val qLower = HindiAsrNormalizer.normalize(query).lowercase(Locale.ROOT)
        val editables = parsed.filter { it.role in EDITABLE_ROLES || it.role == "clickable" }
        val strictInputs = parsed.filter { it.role in EDITABLE_ROLES }
        val pool = when {
            strictInputs.isNotEmpty() -> strictInputs
            editables.isNotEmpty() -> editables
            else -> return null
        }

        val wantsEmail = qLower.contains("email") || qLower.contains("mail") || qLower.contains("phone") || qLower.contains("login")
        val wantsPass = qLower.contains("password") || qLower.contains("passcode")
        val wantsSearch = qLower.contains("search") || qLower.contains("find") || qLower.contains("query")

        return pool.maxByOrNull { item ->
            val nL = item.name.lowercase(Locale.ROOT)
            var s = 500 - item.lineOrder * 10
            if (item.role == "searchbox") s += if (wantsSearch) 250 else 80
            if (item.role == "textbox") s += 100
            if (wantsEmail && (nL.contains("email") || nL.contains("phone") || nL.contains("username") || nL.contains("identifier"))) s += 300
            if (wantsPass && nL.contains("password")) s += 300
            if (wantsSearch && (nL.contains("search") || nL.contains("amazon") || nL.contains("google"))) s += 200
            if (!item.offScreen) s += 20
            s
        }
    }

    fun evaluateSearchSubmitOutcome(
        beforeUrl: String,
        afterUrl: String,
        beforeSig: String,
        afterSig: String,
        submitDomResultOk: Boolean
    ): SubmitVerification {
        val normBefore = normalizeComparableUrl(beforeUrl)
        val normAfter = normalizeComparableUrl(afterUrl)
        val urlChanged = normAfter.isNotBlank() && normBefore != normAfter
        val textChanged = beforeSig.isNotBlank() && afterSig.isNotBlank() && beforeSig != afterSig

        return when {
            urlChanged -> SubmitVerification(
                verified = true,
                navigated = true,
                reason = "Submitted and navigated to $afterUrl"
            )
            textChanged && submitDomResultOk -> SubmitVerification(
                verified = true,
                navigated = false,
                reason = "Submitted form (page content updated in-place on $afterUrl)"
            )
            else -> SubmitVerification(
                verified = false,
                navigated = false,
                reason = "Typed into field, but page did NOT navigate or submit (still on $afterUrl)."
            )
        }
    }

    private fun normalizeComparableUrl(url: String): String =
        url.trim().trimEnd('/').substringBefore("#")

    /**
     * Prevents false-positive close_in_app_browser triggers from noisy Hindi ASR slips ("one"/"open" -> "band")
     * or single-tab close phrases ("ye ad tab band karo" when multiple tabs are open).
     */
    fun getCloseBrowserGuardReason(
        lastUserUtterance: String,
        utteranceAgeMs: Long,
        openTabsCount: Int
    ): String? {
        if (lastUserUtterance.isBlank() || utteranceAgeMs !in 0L..5_500L) return null
        val lower = HindiAsrNormalizer.normalize(lastUserUtterance)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (lower.isBlank()) return null

        val hasCloseIntent = listOf(
            "band", "bnd", "close", "hata", "exit", "nikal", "niklo",
            "quit", "wapas", "back", "chat pe", "chat par", "stop"
        ).any { lower.contains(it) }

        if (!hasCloseIntent) {
            return "Blocked accidental browser close: recent user speech ('$lastUserUtterance') had no close/exit intent. Keep the browser open and continue helping on the current page."
        }

        val mentionsBrowser = lower.contains("browser") || lower.contains("preview") || lower.contains("sab") || lower.contains("poora") || lower.contains("pura")
        val mentionsSingleTab = Regex("""\b(tab|tabe|ad\s+tab)\b""").containsMatchIn(lower)
        if (openTabsCount > 1 && mentionsSingleTab && !mentionsBrowser) {
            return "User asked to close a single tab ('$lastUserUtterance') while $openTabsCount tabs are open — do NOT close the entire browser! Call browser_tabs(action='close', tab_name='...') instead."
        }
        return null
    }
}
