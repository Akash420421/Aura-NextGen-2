package com.example.gemini.data.live

/**
 * Context-aware voice transcript normalizer for Hindi/Hinglish misrecognitions of the
 * brand name "Aura" / "Aura Pro".
 *
 * Strictly requires brand/app/pro/wake context so ordinary Hindi conjunctions ("aur" / "और",
 * e.g. "aur neeche scroll karo", "aur batao", "HTML aur CSS") are NEVER altered.
 */
object AuraBrandAliasNormalizer {

    // 1. Brand + Pro combinations (Roman + Devanagari)
    private val auraProRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(?:aurya|auria|auraa|aora|oura|ora|aur|औरया|औरा|ओरा|और)\s+(?:pro|प्रो)(?!\p{L})"""
    )

    // 2. Brand + App / AI combinations (Roman + Devanagari)
    private val auraAppRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(?:aurat|aurya|auria|auraa|aora|oura|ora|aur|औरया|औरा|ओरा|और)\s+(?:app|ऐप|एप)(?!\p{L})"""
    )

    private val auraAiRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(?:aurya|auria|auraa|aora|oura|ora|aur|औरया|औरा|ओरा)\s+(?:ai|एआई)(?!\p{L})"""
    )

    // 3. Explicit wake greetings directed at Aura ("hey aur", "hello ora", "ok aurya", "hi aora", "सुनो और")
    private val wakePrefixRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(hey|hello|hi|ok|okay|yo|suno|सुनो|हेलो|हाय)\s+(?:aurat|aurya|auria|auraa|aora|oura|ora|aur|औरया|औरा|ओरा)(?=[\s,!?]|$)"""
    )

    // 4. Unambiguous phonetic misspellings of Aura (NOT the common Hindi word "aur"/"और")
    private val standalonePhoneticRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(?:aurya|auria|aora|oura|औरया|औरा)(?!\p{L})"""
    )

    // 5. Explicit app-action context around "aur" / "और" / "ora" (e.g. "open aur app", "aur ko kholo", "aur mein jaao")
    // Note: "aur app" is already handled above; this catches "open ora", "switch to aur", "aur kholo", "ora kholo"
    private val appActionContextAfterRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(?:ora|aora|oura|aurya|aur|ओरा)\s+(?:ko\s+)?(kholo|open\s+karo|band\s+karo|close\s+karo|mein\s+jaao|mein\s+wapas|par\s+wapas|pe\s+wapas)(?!\p{L})"""
    )

    private val appActionContextBeforeRegex = Regex(
        pattern = """(?iu)(?<!\p{L})(open|launch|switch\s+to|back\s+to|go\s+to)\s+(?:ora|aora|oura|aurya|aurat)(?!\p{L})"""
    )

    /**
     * Normalizes Hindi/Hinglish misrecognitions of "Aura" / "Aura Pro" while preserving
     * ordinary Hindi conjunction uses of "aur" / "और".
     */
    fun normalize(input: String): String {
        if (input.isBlank()) return input
        var text = input

        text = auraProRegex.replace(text, "Aura Pro")
        text = auraAppRegex.replace(text, "Aura app")
        text = auraAiRegex.replace(text, "Aura AI")
        text = wakePrefixRegex.replace(text) { m -> "${m.groupValues[1]} Aura" }
        text = appActionContextAfterRegex.replace(text) { m -> "Aura ${m.groupValues[1]}" }
        text = appActionContextBeforeRegex.replace(text) { m -> "${m.groupValues[1]} Aura" }
        text = standalonePhoneticRegex.replace(text, "Aura")

        return text
    }

    /**
     * Returns true if [appName] refers to Aura / Aura Pro (including common spoken/ASR variants).
     */
    fun isAuraAppAlias(appName: String): Boolean {
        val clean = normalize(appName).trim().lowercase()
            .replace(Regex("""\s+"""), " ")
        return clean in setOf(
            "aura", "aura ai", "aura app", "aura pro", "aura pro app",
            "antigem", "antigem app", "ora", "aora", "oura", "aurya", "aurya pro", "aur app"
        )
    }
}
