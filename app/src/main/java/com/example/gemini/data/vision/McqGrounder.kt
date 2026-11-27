package com.example.gemini.data.vision

import android.graphics.Rect
import java.util.Locale
import kotlin.math.abs

/**
 * Pure-Kotlin Chrome/WebView MCQ grounder and state verifier.
 * Handles Unicode/Devanagari option text matching, parent-card <-> radio pairing,
 * question palette pill detection, and anti-skip guards for quiz navigation.
 */
object McqGrounder {

    private val NON_ALNUM_UNICODE_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}]")
    private val TOKEN_SPLIT_UNICODE_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val PURE_NUMBER_PILL_REGEX = Regex("^\\d{1,3}$")
    private val OPTION_PREFIX_LETTER_REGEX = Regex("""^\s*[(]?([a-eA-E])[).:\-\s]+\s*(.*)$""")
    private val OPTION_PREFIX_NUM_REGEX = Regex("""^\s*[(]?([1-5])[).:\s]+\s*(.+)$""")

    data class McqOptionItem(
        val index: Int,
        val letter: Char,
        val label: String,
        val element: UiElementInfo,
        val radioElement: UiElementInfo?,
        val tapRect: Rect,
        val rowRect: Rect,
        val isChecked: Boolean
    )

    data class QuizState(
        val isMcqScreen: Boolean,
        val questionText: String,
        val questionHeader: String,
        val options: List<McqOptionItem>,
        val checkedOption: McqOptionItem?,
        val warningBanner: String?,
        val nextButton: UiElementInfo?,
        val previousButton: UiElementInfo?,
        val submitButton: UiElementInfo?,
        val pillElements: List<UiElementInfo>
    ) {
        val isNextEnabled: Boolean get() = nextButton?.isEnabled != false
    }

    /** Unicode-safe normalization preserving Latin, Devanagari/Indic marks & letters, and digits. */
    fun normalizeUnicode(text: String): String =
        text.lowercase(Locale.ROOT).replace(NON_ALNUM_UNICODE_REGEX, "")

    /** Unicode-safe tokenization preserving Hindi/Indic words (including 2+ char words). */
    fun tokenizeUnicode(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(TOKEN_SPLIT_UNICODE_REGEX)
            .filter { token ->
                val isAsciiWord = token.all { it.code < 128 }
                if (isAsciiWord) token.length >= 2 else token.length >= 2
            }

    fun isQuestionPillElement(el: UiElementInfo): Boolean {
        if (el.isEditable) return false
        val t = el.text.trim()
        if (!PURE_NUMBER_PILL_REGEX.matches(t)) return false
        val num = t.toIntOrNull() ?: return false
        if (num !in 1..250) return false
        val w = el.bounds.width()
        val h = el.bounds.height()
        return w in 18..180 && h in 18..165 && el.bounds.top in 160..1300
    }

    /**
     * Parses explicit MCQ option selectors ('a', 'option b', 'b lagao', 'option 2', 'dusra option', '(c)', etc.).
     * Returns lowercase letter 'a'..'e', or null if [query] is not an option selector.
     */
    fun parseMcqOptionLetter(query: String): Char? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null

        // Never hijack toolbar/overflow icon queries
        if (Regex("\\b(dot|dots|line|lines|menu|tab|tabs|star|rating|page)\\b").containsMatchIn(q)) {
            return null
        }

        Regex("""^[(]?([a-e])[).:\]]?$""").find(q)?.let {
            return it.groupValues[1][0]
        }

        Regex("""^(?:option|vikalp|vikalpa|choice|answer|ans|opt)\s*[-:.#]?\s*([a-e1-5])\b""").find(q)?.let { m ->
            return mapTokenToLetter(m.groupValues[1])
        }

        Regex("""^([a-e])\s*(?:option|vikalp|choice|wala|wali|tick|select|lagao|laga|karo|karde|mark)\b""").find(q)?.let { m ->
            return m.groupValues[1][0]
        }

        Regex("""^[(]?([a-e])[).:]\s+""").find(q)?.let { m ->
            return m.groupValues[1][0]
        }

        return when {
            Regex("""^(?:1st|first|pehla|pehli|पहला|पहली)(?:\s+(?:option|vikalp|choice|wala|answer))?$""").matches(q) -> 'a'
            Regex("""^(?:2nd|second|dusra|doosra|dusri|doosri|दूसरा|दूसरी)(?:\s+(?:option|vikalp|choice|wala|answer))?$""").matches(q) -> 'b'
            Regex("""^(?:3rd|third|teesra|tisra|teesri|tisri|तीसरा|तीसरी)(?:\s+(?:option|vikalp|choice|wala|answer))?$""").matches(q) -> 'c'
            Regex("""^(?:4th|fourth|chautha|chauthi|चौथा|चौथी)(?:\s+(?:option|vikalp|choice|wala|answer))?$""").matches(q) -> 'd'
            Regex("""^(?:5th|fifth|paanchva|panchva|पांचवां|पांचवीं)(?:\s+(?:option|vikalp|choice|wala|answer))?$""").matches(q) -> 'e'
            else -> null
        }
    }

    private fun mapTokenToLetter(tok: String): Char? = when (tok.lowercase(Locale.ROOT)) {
        "a", "1" -> 'a'
        "b", "2" -> 'b'
        "c", "3" -> 'c'
        "d", "4" -> 'd'
        "e", "5" -> 'e'
        else -> null
    }

    fun isNextNavigationTarget(targetName: String): Boolean {
        val q = targetName.trim().lowercase(Locale.ROOT)
        return q == "next" ||
            q == "next question" ||
            q == "save & next" ||
            q == "save and next" ||
            q == "अगला" ||
            q.startsWith("next ") ||
            q.endsWith(" next") ||
            q.contains("next question") ||
            q.contains("save & next") ||
            q.contains("save and next") ||
            q.contains("agla question") ||
            q.contains("agla sawaal") ||
            q.contains("next button")
    }

    fun isPreviousNavigationTarget(targetName: String): Boolean {
        val q = targetName.trim().lowercase(Locale.ROOT)
        return q == "previous" || q == "prev" || q == "previous question" ||
            q.contains("pichla") || q.contains("pichhla") || q.contains("पिछला")
    }

    fun isExplicitSkipGoal(taskGoal: String?, description: String? = null): Boolean {
        val combined = "${taskGoal.orEmpty()} ${description.orEmpty()}".lowercase(Locale.ROOT)
        return combined.contains("skip") ||
            combined.contains("chhod") ||
            combined.contains("bina select") ||
            combined.contains("bina tick") ||
            combined.contains("without answer")
    }

    fun isBrowserChromeUi(el: UiElementInfo): Boolean {
        val vid = el.viewId.lowercase(Locale.ROOT)
        return el.bounds.top < 210 ||
            vid.contains("url_bar") ||
            vid.contains("omnibox") ||
            vid.contains("toolbar") ||
            vid.contains("tab_switcher") ||
            vid.contains("menu_button")
    }

    fun detectQuizState(elements: List<UiElementInfo>): QuizState {
        val pills = elements.filter { isQuestionPillElement(it) }

        val warningBanner = elements.firstOrNull { el ->
            val lbl = el.primaryLabel.lowercase(Locale.ROOT)
            !el.isEditable && (
                lbl.contains("please select an option") ||
                    lbl.contains("please select one") ||
                    lbl.contains("select an option") ||
                    lbl.contains("choose an option") ||
                    lbl.contains("कृपया एक विकल्प") ||
                    lbl.contains("कृपया विकल्प") ||
                    lbl.contains("विकल्प चुनें")
                )
        }?.primaryLabel

        val nonChromeContent = elements.filter { !isBrowserChromeUi(it) && !isQuestionPillElement(it) }

        val nextBtn = nonChromeContent.firstOrNull { el ->
            val l = el.primaryLabel.trim().lowercase(Locale.ROOT)
            !el.isEditable && (el.isClickable || el.bounds.height() >= 36) && (
                l == "next" || l == "next >" || l == "next >>" || l == "next question" ||
                    l == "save & next" || l == "save and next" || l == "अगला" ||
                    (l.startsWith("next") && l.length <= 22 && !l.contains("day") && !l.contains("week"))
                )
        }

        val prevBtn = nonChromeContent.firstOrNull { el ->
            val l = el.primaryLabel.trim().lowercase(Locale.ROOT)
            !el.isEditable && (el.isClickable || el.bounds.height() >= 36) && (
                l == "previous" || l == "prev" || l == "<< previous" || l == "< previous" ||
                    l == "पिछला" || (l.startsWith("prev") && l.length <= 20)
                )
        }

        val submitBtn = nonChromeContent.firstOrNull { el ->
            val l = el.primaryLabel.trim().lowercase(Locale.ROOT)
            !el.isEditable && el.isClickable && (
                l == "submit" || l.contains("submit assessment") || l.contains("submit test") ||
                    l.contains("finish test") || l.contains("submit quiz")
                )
        }

        val rawCandidates = nonChromeContent.filter { el ->
            if (el.isEditable || el.isScrollable || el.isGenericContainer) return@filter false
            if (el == nextBtn || el == prevBtn || el == submitBtn) return@filter false
            val lbl = el.primaryLabel.trim()
            val lower = lbl.lowercase(Locale.ROOT)
            if (lower == "next" || lower == "previous" || lower == "prev" || lower == "submit assessment") return@filter false
            val isRadioNode = el.isCheckable ||
                el.hasCheckableDescendant ||
                el.className.contains("RadioButton", ignoreCase = true) ||
                el.className.contains("CheckBox", ignoreCase = true)
            val hasOptionPrefix = (OPTION_PREFIX_LETTER_REGEX.containsMatchIn(lbl) ||
                OPTION_PREFIX_NUM_REGEX.containsMatchIn(lbl)) && el.bounds.top >= 280
            isRadioNode || (hasOptionPrefix && lbl.length in 2..280)
        }.sortedBy { it.bounds.centerY() }

        // Group separate nodes belonging to the same visual option row (e.g., empty RadioButton + "a. text" card)
        val rowGroups = mutableListOf<MutableList<UiElementInfo>>()
        for (cand in rawCandidates) {
            val matchingGroup = rowGroups.firstOrNull { group ->
                val anchor = group.first()
                val sameYBand = abs(anchor.bounds.centerY() - cand.bounds.centerY()) <= 42 ||
                    Rect.intersects(anchor.bounds, cand.bounds) &&
                    abs(anchor.bounds.top - cand.bounds.top) <= 45
                sameYBand
            }
            if (matchingGroup != null) {
                matchingGroup.add(cand)
            } else {
                rowGroups.add(mutableListOf(cand))
            }
        }

        // Keep genuine vertical option groups (avoid standalone header toggles at top)
        val filteredRows = rowGroups.filter { group ->
            val hasPrefixedText = group.any { el ->
                val t = el.primaryLabel.trim()
                OPTION_PREFIX_LETTER_REGEX.containsMatchIn(t) || OPTION_PREFIX_NUM_REGEX.containsMatchIn(t)
            }
            val topY = group.minOf { it.bounds.top }
            val hasRadioControl = group.any {
                it.isCheckable || it.hasCheckableDescendant || it.className.contains("RadioButton", true)
            }
            (hasRadioControl || hasPrefixedText) && topY >= 260
        }

        val options = filteredRows.mapIndexed { idx, group ->
            val radioEl = group.firstOrNull {
                it.isCheckable || it.className.contains("RadioButton", true)
            } ?: group.firstOrNull { it.hasCheckableDescendant }

            val bestTextEl = group
                .filter { el ->
                    val lbl = el.primaryLabel.trim()
                    lbl.isNotBlank() &&
                        !lbl.equals("RadioButton", ignoreCase = true) &&
                        !lbl.equals("CheckBox", ignoreCase = true) &&
                        !lbl.equals("View", ignoreCase = true)
                }
                .maxByOrNull { el ->
                    val lbl = el.primaryLabel.trim()
                    val prefixBonus = if (OPTION_PREFIX_LETTER_REGEX.containsMatchIn(lbl) || OPTION_PREFIX_NUM_REGEX.containsMatchIn(lbl)) 500 else 0
                    prefixBonus + lbl.length
                } ?: group.first()

            val labelText = bestTextEl.primaryLabel.trim()
            val parsedLetter = OPTION_PREFIX_LETTER_REGEX.find(labelText)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.firstOrNull()
                ?: OPTION_PREFIX_NUM_REGEX.find(labelText)?.groupValues?.get(1)?.let { mapTokenToLetter(it) }
                ?: ('a' + idx.coerceIn(0, 25))

            val rowRect = Rect(
                group.minOf { it.bounds.left },
                group.minOf { it.bounds.top },
                group.maxOf { it.bounds.right },
                group.maxOf { it.bounds.bottom }
            )
            val checked = group.any { it.isEffectivelyChecked() }
            val tapBounds = radioEl?.descendantRadioBounds
                ?: radioEl?.takeIf { it.isCheckable && it.bounds.width() > 6 && it.bounds.height() > 6 }?.bounds
                ?: bestTextEl.bounds

            McqOptionItem(
                index = idx,
                letter = parsedLetter,
                label = labelText,
                element = bestTextEl,
                radioElement = radioEl,
                tapRect = tapBounds,
                rowRect = rowRect,
                isChecked = checked
            )
        }

        val questionHeader = nonChromeContent.firstOrNull { el ->
            val t = el.primaryLabel.trim()
            Regex("""(?i)^(?:question|q\.?|प्रश्न)\s*\d+""").containsMatchIn(t)
        }?.primaryLabel.orEmpty()

        val firstOptionTop = options.minOfOrNull { it.rowRect.top } ?: Int.MAX_VALUE
        val questionText = nonChromeContent
            .filter { el ->
                !el.isEditable &&
                    !el.isCheckable &&
                    !el.isScrollable &&
                    el != nextBtn && el != prevBtn && el != submitBtn &&
                    options.none { opt -> opt.element == el || opt.radioElement == el } &&
                    el.bounds.top in 240..firstOptionTop &&
                    el.primaryLabel.trim().length >= 12 &&
                    !el.primaryLabel.contains("Please select", ignoreCase = true)
            }
            .sortedBy { it.bounds.top }
            .maxByOrNull { it.primaryLabel.length }
            ?.primaryLabel
            ?.trim()
            .orEmpty()

        val hasExamNav = (nextBtn != null && (prevBtn != null || submitBtn != null)) ||
            (pills.size >= 3 && (nextBtn != null || options.isNotEmpty()))
        val isMcq = options.size >= 2 || (hasExamNav && (options.isNotEmpty() || warningBanner != null))

        return QuizState(
            isMcqScreen = isMcq,
            questionText = questionText,
            questionHeader = questionHeader,
            options = options,
            checkedOption = options.firstOrNull { it.isChecked },
            warningBanner = warningBanner,
            nextButton = nextBtn,
            previousButton = prevBtn,
            submitButton = submitBtn,
            pillElements = pills
        )
    }

    /** Matches an option from [quizState] by option letter/index ('a'..'e' / 'option 2') OR option text (Hindi/English). */
    fun findMatchingOption(quizState: QuizState, targetName: String): McqOptionItem? {
        if (quizState.options.isEmpty()) return null

        val optLetter = parseMcqOptionLetter(targetName)
        if (optLetter != null) {
            quizState.options.firstOrNull { it.letter == optLetter }?.let { return it }
            val idx = optLetter - 'a'
            if (idx in quizState.options.indices) return quizState.options[idx]
        }

        val qLower = targetName.trim().lowercase(Locale.ROOT)
        val qNorm = normalizeUnicode(qLower)
        val qTokens = tokenizeUnicode(qLower)

        // Exact normalized match
        quizState.options.firstOrNull { opt ->
            val optBody = OPTION_PREFIX_LETTER_REGEX.find(opt.label)?.groupValues?.get(2) ?: opt.label
            val lblLower = opt.label.lowercase(Locale.ROOT)
            val bodyNorm = normalizeUnicode(optBody)
            lblLower == qLower ||
                (qNorm.length >= 2 && (normalizeUnicode(opt.label) == qNorm || bodyNorm == qNorm))
        }?.let { return it }

        // Substring match (handles Hindi Devanagari cleanly)
        if (qNorm.length >= 3) {
            quizState.options.firstOrNull { opt ->
                val optNorm = normalizeUnicode(opt.label)
                optNorm.contains(qNorm) || (optNorm.length >= 3 && qNorm.contains(optNorm))
            }?.let { return it }
        }

        // Unicode token overlap
        if (qTokens.isNotEmpty()) {
            val best = quizState.options.map { opt ->
                val optTokens = tokenizeUnicode(opt.label)
                val overlap = qTokens.count { t -> optTokens.any { ot -> ot.contains(t) || t.contains(ot) } }
                opt to overlap
            }.maxByOrNull { it.second }
            if (best != null && best.second > 0) return best.first
        }

        return null
    }
}
