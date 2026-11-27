package com.example.gemini.data.live

import java.util.Locale

/**
 * Context-aware normalizer and hallucination filter for Hindi/Hinglish live speech transcripts.
 * Repairs common domain/app/ordinal ASR confusions (without altering normal everyday Hindi/currency)
 * and suppresses foreign-language noise hallucinations (e.g., spurious Spanish/subtitle filler).
 */
object HindiAsrNormalizer {

    data class ScreenContextHint(
        val foregroundPackage: String = "",
        val isMcqOrExam: Boolean = false,
        val isBrowser: Boolean = false,
        val isAuraForeground: Boolean = false
    )

    private val FOREIGN_FILLER_PHRASES = listOf(
        "gracias por ver",
        "subtítulos",
        "subtitulos",
        "amara.org",
        "suscríbete",
        "suscribete",
        "yo calla",
        "pero bien",
        "qué ha pasado",
        "que ha pasado",
        "esto qué es",
        "esto que es",
        "merci d'avoir",
        "sous-titres",
        "obrigado por assistir",
        "продолжение следует",
        "спасибо за просмотр",
        "подписывайтесь"
    )

    private val UNEXPECTED_SCRIPT_REGEX = Regex("[\\u0400-\\u04FF\\u3040-\\u30FF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]+")
    private val LATIN_OR_DEVANAGARI_REGEX = Regex("[a-zA-Z\\u0900-\\u097F]+")

    private val META_SUITE_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:meta\s+(?:melt|sweet|suit|swit)|melt\s+(?:suite|sweet|suit|wal[aei]|plugin|plugins|tool|tools|extension|extensions|ka\s+(?:kya\s+)?scene)|meetha\s+sweet)(?!\p{L})"""
    )
    private val ANTIGEM_ALIAS_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:anti[\s-]?(?:jam|gem|jen|zam)|aunty\s+gem)(?!\p{L})"""
    )
    private val INBUILT_BROWSER_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:in[\s-]?(?:build|built|bill|bil)\s+browser)(?!\p{L})"""
    )
    private val INCOGNITO_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:in[\s-]?cognit[oe]|in[\s-]?cognitive|inkinido|cognito|cognition)(?=\s*(?:tab|mode|mood|kholo|open|window|$|[,.?!]))"""
    )
    private val THREE_DOTS_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:3|three|teen)\s*(?:dot|dots|doubt|daat|point|bindu|line|lines)(?!\p{L})"""
    )
    private val ORDINAL_RUPEE_SLIP_REGEX = Regex(
        """(?iu)(?<!\p{L})(\d{1,3})\s*(?:rupaye|rupay|rupee|rupees|rs\.?|रुपये|रुपए)\s+(k[aei]|wal[aei])(?=\s+(?:answer|ans|option|vikalp|tick|kya|batao|karo|lagao|kaunsa|solve|$|[?,.!]))"""
    )
    private val ORDINAL_EXAM_RUPEE_REGEX = Regex(
        """(?iu)(?<!\p{L})(\d{1,3})\s*(?:rupaye|rupay|rupee|rupees|rs\.?|रुपये|रुपए)(?:\s+(k[aei]|wal[aei]))?(?!\p{L})"""
    )
    private val REAL_MONEY_WORDS_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:recharge|price|cost|petrol|kharcha|bhejo|pay|payment|upi|gpay|phonepe|paytm|paisa|paise|note|coin|balance|bill|total|discount|offer)(?!\p{L})"""
    )
    private val EXAM_CUED_WORDS_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:question|sawaal|sawal|mcq|option|vikalp|answer|tick|next|prev|previous|exam|quiz|test)(?!\p{L})"""
    )

    fun normalize(input: String, hint: ScreenContextHint = ScreenContextHint()): String {
        if (input.isBlank()) return input
        var text = AuraBrandAliasNormalizer.normalize(input)

        text = ANTIGEM_ALIAS_REGEX.replace(text, "AntiGem")
        text = INBUILT_BROWSER_REGEX.replace(text, "In-Built Browser")
        text = INCOGNITO_REGEX.replace(text, "Incognito")
        text = THREE_DOTS_REGEX.replace(text, "3 dots")

        val lower = text.lowercase(Locale.ROOT)
        val hasIceContext = lower.contains("baraf") || lower.contains("ice") || lower.contains("pighal")
        if (!hasIceContext) {
            text = META_SUITE_REGEX.replace(text) { m ->
                val raw = m.value.lowercase(Locale.ROOT)
                when {
                    raw.contains("wale") -> "Meta Suite wale"
                    raw.contains("wala") -> "Meta Suite wala"
                    raw.contains("wali") -> "Meta Suite wali"
                    raw.contains("extension") -> "Meta Suite extension"
                    raw.contains("plugin") -> "Meta Suite plugin"
                    raw.contains("tool") -> "Meta Suite tool"
                    raw.contains("ka kya scene") -> "Meta Suite ka kya scene"
                    raw.contains("ka scene") -> "Meta Suite ka scene"
                    else -> "Meta Suite"
                }
            }
        }

        // Repair Hindi ordinal misheard as currency ("15ve ka" -> "15 rupaye ka")
        val hasMoneyWord = REAL_MONEY_WORDS_REGEX.containsMatchIn(text)
        if (!hasMoneyWord) {
            text = ORDINAL_RUPEE_SLIP_REGEX.replace(text) { m ->
                val num = m.groupValues[1].toIntOrNull()
                val suffix = m.groupValues[2].trim()
                if (num != null && num in 1..250) {
                    if (suffix.isNotEmpty()) "${num}ve $suffix" else "${num}ve"
                } else {
                    m.value
                }
            }
            if (hint.isMcqOrExam || EXAM_CUED_WORDS_REGEX.containsMatchIn(text)) {
                text = ORDINAL_EXAM_RUPEE_REGEX.replace(text) { m ->
                    val num = m.groupValues[1].toIntOrNull()
                    val suffix = m.groupValues[2].trim()
                    if (num != null && num in 1..250) {
                        if (suffix.isNotEmpty()) "${num}ve $suffix" else "${num}ve"
                    } else {
                        m.value
                    }
                }
            }
        }

        return text
    }

    /**
     * Returns true when a transcript chunk is clearly ambient noise or a spurious foreign-language
     * ASR hallucination (e.g., Spanish/Portuguese/Cyrillic subtitle filler generated from background audio).
     */
    fun isNoiseOrForeignHallucination(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return true
        if (trimmed.contains("¿") || trimmed.contains("¡")) return true

        val lower = trimmed.lowercase(Locale.ROOT)
        if (FOREIGN_FILLER_PHRASES.any { lower.contains(it) }) return true

        val hasUnexpectedScript = UNEXPECTED_SCRIPT_REGEX.containsMatchIn(trimmed)
        val hasExpectedScript = LATIN_OR_DEVANAGARI_REGEX.containsMatchIn(trimmed)
        if (hasUnexpectedScript && !hasExpectedScript) return true

        return false
    }
}
