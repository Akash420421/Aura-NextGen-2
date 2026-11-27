package com.example.gemini.data.vision

import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.gemini.data.automation.YouTubeSearchHelper
import java.util.Locale
import kotlin.math.abs

/**
 * Structured representation of an interactive or visible UI element
 * extracted directly from the Android Accessibility Tree with exact pixel bounds.
 */
data class UiElementInfo(
    val id: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val stateDescription: String = "",
    val hasCheckableDescendant: Boolean = false,
    val isDescendantChecked: Boolean = false,
    val descendantRadioBounds: Rect? = null
) {
    val center: PointF
        get() = PointF(bounds.exactCenterX(), bounds.exactCenterY())

    val primaryLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            viewId.isNotBlank() -> viewId.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }

    fun isSwitchOrToggle(): Boolean {
        val stateLower = stateDescription.lowercase(Locale.ROOT)
        return isCheckable || hasCheckableDescendant ||
            className.contains("RadioButton", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) ||
            stateLower == "checked" || stateLower == "unchecked" ||
            stateLower == "selected" || stateLower == "not selected"
    }

    fun isEffectivelyChecked(): Boolean {
        if (isChecked || isDescendantChecked || isSelected) return true
        val stateLower = stateDescription.lowercase(Locale.ROOT).trim()
        return stateLower == "checked" || stateLower == "selected" || stateLower == "on" || stateLower == "true"
    }

    val hasTextOrDesc: Boolean
        get() = text.isNotBlank() || contentDescription.isNotBlank()

    val isImageOrThumbnail: Boolean
        get() {
            val simpleClass = className.substringAfterLast(".").lowercase(Locale.ROOT)
            val simpleVid = viewId.substringAfterLast("/").lowercase(Locale.ROOT)
            val lbl = primaryLabel.lowercase(Locale.ROOT)

            val isImgClass = simpleClass.contains("image") || simpleClass.contains("photo") || simpleClass.contains("thumb") || simpleClass.contains("picture")
            val isImgVid = simpleVid.contains("thumb") || simpleVid.contains("image") || simpleVid.contains("photo") || simpleVid.contains("pic") || simpleVid.contains("icon_thumbnail")
            val isImgLabel = lbl.endsWith(".jpg") || lbl.endsWith(".jpeg") || lbl.endsWith(".png") || lbl.endsWith(".webp") ||
                    lbl.startsWith("img_") || lbl.startsWith("pxl_") || lbl.startsWith("photo") || lbl == "image" || lbl == "photo"

            return (isImgClass || isImgVid || isImgLabel) && bounds.width() >= 60 && bounds.height() >= 60
        }

    val isGenericContainer: Boolean
        get() {
            if (hasTextOrDesc) return false
            if (isImageOrThumbnail) return false
            val simple = className.substringAfterLast(".").lowercase(Locale.ROOT)
            return simple in GENERIC_CONTAINERS
        }

    companion object {
        val GENERIC_CONTAINERS = setOf(
            "framelayout", "linearlayout", "relativelayout", "viewgroup",
            "view", "viewpager", "recyclerview", "scrollview", "nestedscrollview",
            "cardview", "coordinatorlayout", "constraintlayout"
        )
    }
}

/**
 * Dual-Anchor UI Grounding Engine.
 * Traverses Android's native Accessibility hierarchy and maps high-level
 * visual intents and spoken targets directly to hardware-accurate pixel bounding boxes.
 */
class UiGroundingEngine {

    companion object {
        private const val TAG = "UiGroundingEngine"
    }

    /**
     * Extracts all visible, interactive, or descriptive elements currently on the screen.
     */
    fun scanScreen(root: AccessibilityNodeInfo?): List<UiElementInfo> {
        if (root == null) return emptyList()
        val elements = mutableListOf<UiElementInfo>()
        traverseSubtree(root, elements) { elements.size + 1 }
        return elements
    }

    /**
     * Scans multiple interactive windows (e.g. main app window + PopupWindow menus, dialogs,
     * floating action sheets) into one unified grounded list with continuous [#id] numbering.
     */
    fun scanAllWindows(roots: List<AccessibilityNodeInfo>): List<UiElementInfo> {
        if (roots.isEmpty()) return emptyList()
        val elements = mutableListOf<UiElementInfo>()
        val seenKeys = HashSet<String>()
        var nextId = 1
        for (root in roots) {
            val windowElements = mutableListOf<UiElementInfo>()
            traverseSubtree(root, windowElements) { nextId++ }
            for (el in windowElements) {
                val key = "${el.viewId}|${el.text}|${el.contentDescription}|${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}"
                if (seenKeys.add(key)) {
                    elements.add(el)
                }
            }
        }
        return elements
    }

    private fun traverseSubtree(
        root: AccessibilityNodeInfo,
        elements: MutableList<UiElementInfo>,
        idProvider: () -> Int
    ) {
        fun traverse(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            var text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            var p = node.parent
            var parentClickable = false
            var depth = 0
            while (p != null && depth < 3) {
                if (p.isClickable) {
                    parentClickable = true
                    break
                }
                p = p.parent
                depth++
            }
            val isClickable = node.isClickable || parentClickable
            val isCheckable = node.isCheckable || cls.contains("RadioButton", ignoreCase = true) || cls.contains("CheckBox", ignoreCase = true)
            val isSelected = node.isSelected
            val isEnabled = node.isEnabled
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.trim().orEmpty()
            } else ""
            val hasSetTextAction = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE
            }
            val isEditable = node.isEditable || cls.contains("EditText", ignoreCase = true) || hasSetTextAction
            val isScrollable = node.isScrollable

            var hasCheckableDescendant = false
            var isDescendantChecked = false
            var descendantRadioBounds: Rect? = null
            if (!isCheckable && node.childCount > 0) {
                val descInfo = inspectCheckableDescendant(node, depth = 0)
                if (descInfo != null) {
                    hasCheckableDescendant = true
                    isDescendantChecked = descInfo.first
                    descendantRadioBounds = descInfo.second
                }
            }

            // If a row/card has no direct text, extract combined text from its descendants
            if (text.isBlank() && desc.isBlank() && node.childCount > 0) {
                val childTexts = collectNodeText(node)
                if (childTexts.isNotEmpty()) {
                    text = childTexts.joinToString(" • ")
                }
            }

            val isVisible = bounds.width() > 5 && bounds.height() > 5
            val isInteractiveOrMeaningful = isVisible && (
                isClickable || isCheckable || hasCheckableDescendant || isEditable || isScrollable ||
                    text.isNotBlank() || desc.isNotBlank()
                )

            if (isInteractiveOrMeaningful) {
                elements.add(
                    UiElementInfo(
                        id = idProvider(),
                        text = text,
                        contentDescription = desc,
                        className = cls,
                        viewId = vid,
                        bounds = bounds,
                        isClickable = isClickable,
                        isCheckable = isCheckable,
                        isChecked = node.isChecked,
                        isEditable = isEditable,
                        isScrollable = isScrollable,
                        isSelected = isSelected,
                        isEnabled = isEnabled,
                        stateDescription = stateDesc,
                        hasCheckableDescendant = hasCheckableDescendant,
                        isDescendantChecked = isDescendantChecked,
                        descendantRadioBounds = descendantRadioBounds
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
            }
        }

        try {
            traverse(root)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree: ${e.message}")
        }
    }

    private fun inspectCheckableDescendant(node: AccessibilityNodeInfo, depth: Int): Pair<Boolean, Rect>? {
        if (depth >= 2) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val cCls = child.className?.toString().orEmpty()
            val cCheckable = child.isCheckable ||
                cCls.contains("RadioButton", ignoreCase = true) ||
                cCls.contains("CheckBox", ignoreCase = true)
            if (cCheckable) {
                val rect = Rect().also { child.getBoundsInScreen(it) }
                val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    child.stateDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                } else ""
                val checked = child.isChecked || child.isSelected || stateDesc == "checked" || stateDesc == "selected"
                if (rect.width() > 4 && rect.height() > 4) {
                    return checked to rect
                }
            }
            inspectCheckableDescendant(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun normalizeSynonymTokens(queryLower: String): List<String> {
        val extras = mutableListOf<String>()
        if (Regex("\\b(3|three|teen)\\s*(dot|dots|point|line|lines|bindu|menu)\\b").containsMatchIn(queryLower) ||
            queryLower.contains("overflow") || queryLower.contains("kebab") || queryLower.contains("hamburger") ||
            queryLower.contains("three dot") || queryLower.contains("3 dot") || queryLower.contains("3 line")
        ) {
            extras += listOf("menu_button", "more options", "customize and control", "overflow", "more_menu", "open navigation")
        }
        if (queryLower.contains("incognito") || queryLower.contains("inkinido") || queryLower.contains("in cognito") ||
            queryLower.contains("cognition") || queryLower.contains("private tab") || queryLower.contains("private mode")
        ) {
            extras += listOf("new incognito tab", "incognito tab", "incognito", "new private tab", "private")
        }
        if (queryLower.contains("close") || queryLower.contains("cross") || queryLower == "x" || queryLower.contains("band")) {
            extras += listOf("close", "dismiss", "cancel", "close_button", "close_btn")
        }
        if (queryLower.contains("skip") || queryLower.contains("ad skip")) {
            extras += listOf("skip ad", "skip ads", "skip", "skip_ad_button")
        }
        if (queryLower.contains("setting")) {
            extras += listOf("settings", "preferences", "menu_item_settings")
        }
        return extras
    }

    /**
     * True when the element's visible label or viewId plausibly matches what the model asked for.
     * A blank label (icon-only button) cannot be disproven, so it is accepted.
     */
    private fun labelMatchesName(el: UiElementInfo, queryLower: String): Boolean {
        val labels = listOf(el.text, el.contentDescription, el.viewId.substringAfterLast("/"))
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return true
        val synonymTerms = normalizeSynonymTokens(queryLower)
        val qTokens = McqGrounder.tokenizeUnicode(queryLower)
        val qNorm = McqGrounder.normalizeUnicode(queryLower)
        val optLetter = McqGrounder.parseMcqOptionLetter(queryLower)
        return labels.any { label ->
            val lNorm = McqGrounder.normalizeUnicode(label)
            label.contains(queryLower) ||
                (label.length >= 2 && queryLower.contains(label)) ||
                (qNorm.length >= 2 && (lNorm.contains(qNorm) || (lNorm.length >= 2 && qNorm.contains(lNorm)))) ||
                qTokens.any { t -> label.contains(t) } ||
                synonymTerms.any { syn -> label.contains(syn) } ||
                (optLetter != null && Regex("""^\s*[(]?${optLetter}[).:\s]""").containsMatchIn(label))
        }
    }

    fun findTargetElement(
        elements: List<UiElementInfo>,
        targetName: String,
        isToggleIntent: Boolean = false,
        elementId: Int? = null
    ): UiElementInfo? {
        if (elementId != null && elementId > 0) {
            val byId = elements.firstOrNull { it.id == elementId }
            if (byId != null) {
                val q = targetName.trim().lowercase(Locale.ROOT)
                if (q.isBlank() || labelMatchesName(byId, q)) return byId
                Log.w(TAG, "element_id=$elementId now points to '${byId.primaryLabel}' but target_name='$targetName'. " +
                    "Screen changed since inspect_screen; matching by label instead of tapping the id.")
            }
        }

        val query = targetName.trim().lowercase(Locale.ROOT)
        if (query.isBlank()) return null

        if (isGoogleFlowSurface(elements) && query.contains("google flow") &&
            !query.contains("new project") && !query.contains("open project")) {
            return null
        }

        val cleanQuery = McqGrounder.normalizeUnicode(query)
        val strippedQuery = query
            .replace("\\b(channel|group|folder|chat|app|page|tab|telegram|whatsapp|file)\\b".toRegex(RegexOption.IGNORE_CASE), "")
            .trim()
        val cleanStrippedQuery = McqGrounder.normalizeUnicode(strippedQuery)

        // MCQ Option Grounding Priority: match visible quiz/radio options BEFORE ordinal card heuristics
        val quizState = McqGrounder.detectQuizState(elements)
        if (quizState.isMcqScreen) {
            if (McqGrounder.isNextNavigationTarget(query) && quizState.nextButton != null) {
                Log.i(TAG, "Grounded MCQ Next button: '${quizState.nextButton.primaryLabel}' (enabled=${quizState.nextButton.isEnabled})")
                return quizState.nextButton
            }
            if (McqGrounder.isPreviousNavigationTarget(query) && quizState.previousButton != null) {
                Log.i(TAG, "Grounded MCQ Previous button: '${quizState.previousButton.primaryLabel}'")
                return quizState.previousButton
            }
            McqGrounder.findMatchingOption(quizState, targetName)?.let { matchedOpt ->
                Log.i(TAG, "Grounded MCQ option '${matchedOpt.letter}': '${matchedOpt.label}' (checked=${matchedOpt.isChecked})")
                return matchedOpt.radioElement ?: matchedOpt.element
            }
        }

        // Ordinal matching (1st, 2nd, 3rd, pehli, dusri, teesri, etc.) — ONLY for card/list ordinals,
        // NEVER hijack icon queries ("3 dots", "3 line") and NEVER tap top Chrome chrome or small question pills.
        val isIconCountPhrase = Regex("\\b(dot|dots|line|lines|bar|bars|dash|bindu|menu)\\b").containsMatchIn(query)
        val ordinalIndex = if (isIconCountPhrase || quizState.isMcqScreen) null else when {
            query.contains("pehli") || query.contains("first") || query.contains("1st") || query == "1" || Regex("""^(1|item\s+1|result\s+1|video\s+1|option\s+1)\b""").containsMatchIn(query) -> 1
            query.contains("dusri") || query.contains("doosri") || query.contains("second") || query.contains("2nd") || query == "2" || Regex("""^(2|item\s+2|result\s+2|video\s+2|option\s+2)\b""").containsMatchIn(query) -> 2
            query.contains("teesri") || query.contains("tisri") || query.contains("third") || query.contains("3rd") || query == "3" || Regex("""^(3|item\s+3|result\s+3|video\s+3|option\s+3)\b""").containsMatchIn(query) -> 3
            query.contains("chauthi") || query.contains("fourth") || query.contains("4th") || query == "4" || Regex("""^(4|item\s+4|result\s+4|video\s+4|option\s+4)\b""").containsMatchIn(query) -> 4
            query.contains("paanchvi") || query.contains("fifth") || query.contains("5th") || query == "5" || Regex("""^(5|item\s+5|result\s+5|video\s+5|option\s+5)\b""").containsMatchIn(query) -> 5
            else -> null
        }
        val isYouTubeScreen = elements.any { it.viewId.contains("com.google.android.youtube", ignoreCase = true) }
        val screenH = (elements.maxOfOrNull { it.bounds.bottom } ?: 2400).coerceAtLeast(800)

        if (ordinalIndex != null) {
            if (isYouTubeScreen) {
                val ytCards = elements.filter {
                    (it.isClickable || it.isImageOrThumbnail) &&
                        YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                            viewId = it.viewId,
                            text = it.text,
                            contentDescription = it.contentDescription,
                            topPx = it.bounds.top,
                            heightPx = it.bounds.height(),
                            screenHeightPx = screenH
                        )
                }.sortedBy { it.bounds.top }
                if (ytCards.size >= ordinalIndex) {
                    val card = ytCards[ordinalIndex - 1]
                    Log.i(TAG, "Grounded YouTube playable ordinal ($ordinalIndex): '${card.primaryLabel}' (id=${card.id})")
                    return card
                }
            }
            val cards = elements.filter {
                (it.isClickable || it.isImageOrThumbnail) &&
                    it.bounds.height() >= 80 &&
                    it.bounds.top >= 200 &&
                    !it.isGenericContainer &&
                    !McqGrounder.isBrowserChromeUi(it) &&
                    !McqGrounder.isQuestionPillElement(it)
            }.sortedBy { it.bounds.top }
            if (cards.size >= ordinalIndex) {
                val card = cards[ordinalIndex - 1]
                Log.i(TAG, "Grounded ordinal ($ordinalIndex): '${card.primaryLabel}' (id=${card.id})")
                return card
            }
        }

        // Generic YouTube video selection ("song", "gaana", "video", "play song") -> pick top playable non-Ad video card (#16)
        val isGenericYouTubeSongQuery = isYouTubeScreen && query in setOf(
            "song", "gaana", "gana", "video", "music", "play song", "play video", "play first", "first video", "first song", "select"
        )
        if (isGenericYouTubeSongQuery) {
            val topYtCard = elements.filter {
                (it.isClickable || it.isImageOrThumbnail) &&
                    YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                        viewId = it.viewId,
                        text = it.text,
                        contentDescription = it.contentDescription,
                        topPx = it.bounds.top,
                        heightPx = it.bounds.height(),
                        screenHeightPx = screenH
                    )
            }.minByOrNull { it.bounds.top }
            if (topYtCard != null) {
                Log.i(TAG, "Grounded generic YouTube playable card: '${topYtCard.primaryLabel}' (id=${topYtCard.id})")
                return topYtCard
            }
        }

        // Icon / Menu / Incognito / Overflow synonym matching (works across Chrome, Settings, Instagram, YouTube, etc.)
        val synonymTerms = normalizeSynonymTokens(query)
        if (synonymTerms.isNotEmpty()) {
            val isOverflowQuery = Regex("\\b(3|three|teen)\\s*(dot|dots|point|line|lines|bindu|menu)\\b").containsMatchIn(query) ||
                query.contains("overflow") || query.contains("more options") || query.contains("3 dot")
            val clickableFirst = (elements.filter { !it.isEditable && (it.isClickable || it.isCheckable) } +
                elements.filter { !it.isEditable && !it.isClickable && !it.isCheckable })
                .sortedByDescending { el ->
                    var boost = 0
                    if (isOverflowQuery && el.bounds.top in 0..420) boost += 20
                    if (el.viewId.contains("menu_button", ignoreCase = true) || el.viewId.contains("menu_item", ignoreCase = true)) boost += 30
                    if (el.primaryLabel.contains("incognito", ignoreCase = true)) boost += 30
                    boost
                }
            for (syn in synonymTerms) {
                clickableFirst.firstOrNull { el ->
                    val textL = el.text.lowercase(Locale.ROOT)
                    val descL = el.contentDescription.lowercase(Locale.ROOT)
                    val vidL = el.viewId.substringAfterLast("/").lowercase(Locale.ROOT)
                    textL.contains(syn) || descL.contains(syn) || vidL.contains(syn)
                }?.let { matched ->
                    Log.i(TAG, "Grounded via icon/UI synonym '$query' -> '${matched.primaryLabel}' (vid=${matched.viewId}, id=${matched.id})")
                    return matched
                }
            }
        }

        // Fix 15: Reels Tab Priority (bottom navigation bar Reels tab)
        val isReelsTabRequest = query == "reels" || query == "reel" || query.contains("reel tab") ||
                query.contains("reels tab") || query.contains("reel button") || query.contains("reels button") ||
                query.contains("reel wala button") || query.contains("reels wala button") ||
                query.contains("clips tab") || query.contains("clips")
        if (isReelsTabRequest) {
            val reelsTab = elements.firstOrNull { el ->
                val vId = el.viewId.lowercase(Locale.ROOT)
                val label = el.primaryLabel.lowercase(Locale.ROOT)
                (vId.contains("clips_tab") || vId.contains("reels_tab") || vId.contains("tab_reels") ||
                 label == "reels" || label == "reel") &&
                (el.bounds.top >= 1500 || vId.contains("tab") || vId.contains("nav"))
            }
            if (reelsTab != null) {
                Log.i(TAG, "Grounded Reels bottom navigation tab: '${reelsTab.primaryLabel}' (id=${reelsTab.id}, bounds=${reelsTab.bounds})")
                return reelsTab
            }
        }

        // Fix 16: Instagram Direct Message / Inbox button priority
        val isInstagramInboxRequest = (query == "direct" || query == "dm" || query.contains("inbox") || query.contains("direct message") || query.contains("messages")) &&
                elements.any { it.viewId.contains("instagram") }
        if (isInstagramInboxRequest) {
            val inboxBtn = elements.firstOrNull {
                val vId = it.viewId.lowercase(Locale.ROOT)
                val label = it.primaryLabel.lowercase(Locale.ROOT)
                vId.contains("action_bar_inbox") || vId.contains("action_bar_button_action_inbox") ||
                label.contains("direct") || label.contains("message") || label.contains("inbox")
            }
            if (inboxBtn != null) {
                Log.i(TAG, "Grounded Instagram DM / Inbox button: '${inboxBtn.primaryLabel}' (id=${inboxBtn.id})")
                return inboxBtn
            }
        }

        // Explicit button clicks (send/generate/submit/enter) — NEVER route to input fields
        val isExplicitButtonQuery = query.contains("send button") || query.contains("generate button") ||
                query.contains("submit button") || query.contains("enter button") ||
                query.contains("create button") || query.contains("go button") ||
                query == "send" || query == "generate" || query == "submit" ||
                query == "enter" || query == "done" || query == "run" ||
                query.contains("click send") || query.contains("press send") ||
                query.contains("press enter") || query.contains("press generate")

        val isExplicitSearchBox = !isExplicitButtonQuery && (
                query == "search" || query == "search bar" || query == "search box" ||
                query == "search input" || query == "search field" || query == "type" || query == "url bar" || query == "address bar" ||
                query == "input box" || query == "search query" || query == "text box" ||
                query.contains("prompt") || query.contains("creation") || query.contains("describe") || query.contains("input")
        )

        // Case 0: Explicit input/search/prompt intent (ONLY when targeting an input field, exclude URL bar)
        if (isExplicitSearchBox) {
            // 1. Direct match on web prompt placeholders (like Google Flow: "What do you want to create?")
            val webPromptPlaceholder = elements.firstOrNull {
                val label = it.primaryLabel.lowercase(Locale.ROOT)
                (label.contains("what do you want to create") ||
                 label.contains("describe your") ||
                 label.contains("type a prompt") ||
                 label.contains("enter a prompt") ||
                 label.contains("enter prompt") ||
                 label.contains("ask gemini") ||
                 label.contains("search or type")) &&
                !it.viewId.contains("url_bar", ignoreCase = true) &&
                !it.viewId.contains("omnibox", ignoreCase = true)
            }
            if (webPromptPlaceholder != null) {
                Log.i(TAG, "Grounded web prompt placeholder input: '${webPromptPlaceholder.primaryLabel}' (id=${webPromptPlaceholder.id})")
                return webPromptPlaceholder
            }

            val nonUrlInputs = elements.filter {
                (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                !it.viewId.contains("url_bar", ignoreCase = true) &&
                !it.viewId.contains("omnibox", ignoreCase = true)
            }
            if (nonUrlInputs.isNotEmpty()) {
                val promptInput = nonUrlInputs.firstOrNull {
                    it.text.contains("prompt", ignoreCase = true) ||
                    it.contentDescription.contains("prompt", ignoreCase = true) ||
                    it.text.contains("describe", ignoreCase = true) ||
                    it.contentDescription.contains("describe", ignoreCase = true) ||
                    it.viewId.contains("prompt", ignoreCase = true) ||
                    it.viewId.contains("input", ignoreCase = true)
                }
                return promptInput ?: nonUrlInputs.first()
            }
            elements.firstOrNull { it.isEditable && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }?.let { return it }
        }

        // Case 1: Toggle/Switch intent for a labeled item (e.g. "enable Wireless Debugging")
        if (isToggleIntent) {
            // Find the label element first
            val labelElement = elements.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                text.contains(query) || desc.contains(query) || 
                (cleanQuery.isNotEmpty() && cleanText.contains(cleanQuery)) ||
                query.contains(text.takeIf { t -> t.length > 3 } ?: "____")
            }.minByOrNull {
                abs(it.primaryLabel.length - query.length)
            }

            if (labelElement != null) {
                val adjacentSwitch = elements.filter {
                    it.isSwitchOrToggle() &&
                    abs(it.bounds.centerY() - labelElement.bounds.centerY()) < 150 &&
                    it.bounds.centerX() >= labelElement.bounds.centerX()
                }.minByOrNull {
                    abs(it.bounds.centerY() - labelElement.bounds.centerY())
                }

                if (adjacentSwitch != null) {
                    Log.i(TAG, "Grounded toggle intent: matched adjacent switch ${adjacentSwitch.id} for label '${labelElement.text}'")
                    return adjacentSwitch
                }

                if (labelElement.isClickable || labelElement.isCheckable) {
                    return labelElement
                }
            }
        }

        // Case 1.5: Specialized Web Search Result Links Priority
        // When searching on Google / Web / Store, search result links below the search box (bounds.top > 200)
        // MUST take absolute precedence over the search bar or address bar!
        val resultLinks = if (isGoogleSearchResults(elements)) elements.filter {
            !it.isEditable && it.bounds.top > 200 && (it.isClickable || it.bounds.top > 250) &&
                (it.text.isNotBlank() || it.contentDescription.isNotBlank())
        } else emptyList()
        if (resultLinks.isNotEmpty()) {
            // Exact / normalized match on result links
            resultLinks.firstOrNull {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                text == query || desc == query ||
                (cleanQuery.isNotEmpty() && (cleanText == cleanQuery || cleanDesc == cleanQuery)) ||
                (cleanStrippedQuery.isNotEmpty() && (cleanText == cleanStrippedQuery || cleanDesc == cleanStrippedQuery))
            }?.let {
                Log.i(TAG, "Grounded search result link exact match: '${it.primaryLabel}' (id=${it.id})")
                return it
            }

            // Substring containment on result links
            resultLinks.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                (cleanQuery.length >= 3 && (cleanText.contains(cleanQuery) || cleanQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____"))) ||
                (cleanStrippedQuery.length >= 3 && (cleanText.contains(cleanStrippedQuery) || cleanStrippedQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____")))
            }.minByOrNull { it.primaryLabel.length }?.let {
                Log.i(TAG, "Grounded search result link substring match: '${it.primaryLabel}' (id=${it.id})")
                return it
            }

            // Token overlap on result links
            val queryTokens = query.split("\\s+".toRegex()).filter { it.length > 2 }
            if (queryTokens.isNotEmpty()) {
                val bestResultLink = resultLinks.filter { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.any { token -> elText.contains(token) }
                }.maxByOrNull { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.count { token -> elText.contains(token) }
                }
                if (bestResultLink != null) {
                    Log.i(TAG, "Grounded search result link token overlap: '${bestResultLink.primaryLabel}' (id=${bestResultLink.id})")
                    return bestResultLink
                }
            }
        }

        // Fix 16: Bottom nav tabs exclusion when searching for contacts, messages, cards, or content
        val isExplicitTabQuery = query.contains("tab") || query.contains("bar") || query.contains("nav") ||
                query in listOf("search", "explore", "home", "feed", "reels", "profile", "notifications")
        val candidateElements = if (isExplicitTabQuery) elements else elements.filter { el ->
            val vId = el.viewId.lowercase(Locale.ROOT)
            val label = el.primaryLabel.lowercase(Locale.ROOT)
            val isNavTab = (vId.contains("search_tab") || vId.contains("feed_tab") || vId.contains("clips_tab") ||
                    vId.contains("profile_tab") || vId.contains("news_tab") || vId.contains("bottom_nav")) ||
                   (el.bounds.top >= 1800 && el.bounds.height() in 80..220 && (label == "search" || label == "search and explore" || label == "home" || label == "reels" || label == "profile"))
            !isNavTab
        }

        // Tiered matching candidate lists:
        // Tier 1: Clickable non-editable interactive items (links, buttons, list cards, icons)
        // Tier 2: Non-editable content items (headings, labels, descriptions)
        // Tier 3: Editable inputs (search bars, text fields - ONLY as a last resort)
        val tiers = listOf(
            candidateElements.filter { !it.isEditable && (it.isClickable || it.isCheckable) },
            candidateElements.filter { !it.isEditable },
            candidateElements.filter { it.isEditable }
        )

        // Case 2: Exact text, contentDescription, or viewId match with non-editable priority
        for (tier in tiers) {
            tier.firstOrNull {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val vidShort = it.viewId.substringAfterLast("/").lowercase(Locale.ROOT)
                val cleanText = McqGrounder.normalizeUnicode(text)
                val cleanDesc = McqGrounder.normalizeUnicode(desc)
                val cleanVid = McqGrounder.normalizeUnicode(vidShort)

                text == query || desc == query || vidShort == query ||
                    (cleanQuery.isNotEmpty() && (cleanText == cleanQuery || cleanDesc == cleanQuery || cleanVid == cleanQuery)) ||
                    (cleanStrippedQuery.isNotEmpty() && (cleanText == cleanStrippedQuery || cleanDesc == cleanStrippedQuery || cleanVid == cleanStrippedQuery))
            }?.let { return it }
        }

        // Case 3: Normalized Substring Containment (text, desc, viewId — Unicode/Hindi safe!) with non-editable priority
        for (tier in tiers) {
            tier.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val vidShort = it.viewId.substringAfterLast("/").lowercase(Locale.ROOT)
                val cleanText = McqGrounder.normalizeUnicode(text)
                val cleanDesc = McqGrounder.normalizeUnicode(desc)
                val cleanVid = McqGrounder.normalizeUnicode(vidShort)

                (cleanQuery.length >= 2 && (
                    cleanText.contains(cleanQuery) || cleanDesc.contains(cleanQuery) || cleanVid.contains(cleanQuery) ||
                        cleanQuery.contains(cleanText.takeIf { t -> t.length >= 2 } ?: "____")
                    )) ||
                    (cleanStrippedQuery.length >= 2 && (
                        cleanText.contains(cleanStrippedQuery) || cleanDesc.contains(cleanStrippedQuery) || cleanVid.contains(cleanStrippedQuery) ||
                            cleanStrippedQuery.contains(cleanText.takeIf { t -> t.length >= 2 } ?: "____")
                        ))
            }.minWithOrNull(
                compareBy<UiElementInfo> { element ->
                    val text = McqGrounder.normalizeUnicode(element.text)
                    val desc = McqGrounder.normalizeUnicode(element.contentDescription)
                    if (text == cleanQuery || desc == cleanQuery) 0 else element.primaryLabel.length
                }.thenBy { it.bounds.width().toLong() * it.bounds.height().toLong() }
            )?.let { return it }
        }

        // Case 4: Unicode Token/Word Overlap with non-editable priority
        val queryTokens = McqGrounder.tokenizeUnicode(query)
        if (queryTokens.isNotEmpty()) {
            for (tier in tiers) {
                val bestTokenMatch = tier.filter { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.any { token -> elText.contains(token) }
                }.maxByOrNull { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.count { token -> elText.contains(token) }
                }
                if (bestTokenMatch != null) {
                    return bestTokenMatch
                }
            }
        }

        // Case 6: Spatial position or generic image matching (e.g. "image at top-center", "first photo", "photo at top right")
        val spatialTerms = listOf(
            "top-left", "top left", "top-center", "top center", "top-right", "top right",
            "mid-left", "mid left", "mid-center", "mid center", "mid-right", "mid right",
            "bottom-left", "bottom left", "bottom-center", "bottom center", "bottom-right", "bottom right"
        )
        val combinedText = "$query $cleanStrippedQuery".lowercase(Locale.ROOT)
        for (spatial in spatialTerms) {
            if (combinedText.contains(spatial)) {
                val normSpatial = spatial.replace(" ", "-")
                val matched = elements.filter { it.isImageOrThumbnail }.firstOrNull { el ->
                    val elSpatial = getSpatialPosition(el.bounds, 1080, 2400).lowercase(Locale.ROOT)
                    elSpatial == normSpatial
                }
                if (matched != null) return matched
            }
        }

        if (query == "image" || query == "photo" || query == "thumbnail" || query == "picture" ||
            query.contains("first photo") || query.contains("first image")) {
            elements.firstOrNull { it.isImageOrThumbnail }?.let { return it }
        }

        return null
    }

    fun extractScreenContext(root: AccessibilityNodeInfo?, elements: List<UiElementInfo>): Pair<String, String> {
        val pkg = root?.packageName?.toString() ?: ""
        val titleCandidate = elements
            .filter { it.text.isNotBlank() && it.bounds.top in 60..380 && it.bounds.height() in 20..220 && it.text.length in 3..60 }
            .minByOrNull { it.bounds.top }
            ?.text ?: ""
        return Pair(pkg, titleCandidate)
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 6) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val ct = child.text?.toString()?.trim() ?: child.contentDescription?.toString()?.trim() ?: ""
            if (ct.isNotBlank() && !list.contains(ct)) {
                list.add(ct)
            }
            if (child.childCount > 0) {
                list.addAll(collectNodeText(child, depth + 1))
            }
        }
        return list
    }

    fun getSpatialPosition(bounds: Rect, screenWidth: Int, screenHeight: Int): String {
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val vPos = when {
            cy < screenHeight * 0.35f -> "Top"
            cy < screenHeight * 0.65f -> "Mid"
            else -> "Bottom"
        }

        val hPos = when {
            cx < screenWidth * 0.33f -> "Left"
            cx < screenWidth * 0.67f -> "Center"
            else -> "Right"
        }

        return "$vPos-$hPos"
    }

    /**
     * Compact summary of visible interactive elements to attach to prompt or context.
     * Groups dense numeric question pills so MCQ questions, options, tick states, and nav buttons
     * are never truncated off the screen summary.
     */
    fun formatElementsSummary(elements: List<UiElementInfo>, root: AccessibilityNodeInfo? = null): String {
        val sb = StringBuilder()
        val (pkg, title) = extractScreenContext(root, elements)
        if (pkg.isNotBlank()) sb.append("App: $pkg | ")
        if (title.isNotBlank()) sb.append("Screen Title: \"$title\"\n") else if (pkg.isNotBlank()) sb.append("\n")

        val quizState = McqGrounder.detectQuizState(elements)
        if (quizState.isMcqScreen) {
            sb.append("[MCQ / QUIZ STATE]\n")
            if (quizState.questionHeader.isNotBlank()) sb.append("Header: ${quizState.questionHeader}\n")
            if (quizState.questionText.isNotBlank()) sb.append("Question: \"${quizState.questionText}\"\n")
            if (quizState.checkedOption != null) {
                sb.append("Selected Option: ✓ CHECKED -> [${quizState.checkedOption.letter.uppercaseChar()}] ${quizState.checkedOption.label}\n")
            } else {
                sb.append("Selected Option: NONE (⚠️ NO option checked — do NOT click Next yet!)\n")
            }
            if (!quizState.warningBanner.isNullOrBlank()) {
                sb.append("Warning Banner: ⚠️ \"${quizState.warningBanner}\"\n")
            }
            quizState.options.forEach { opt ->
                val stateMark = if (opt.isChecked) "✓ CHECKED" else "○ UNCHECKED"
                sb.append("  Option ${opt.letter.uppercaseChar()} [#${opt.element.id}] ($stateMark): ${opt.label}\n")
            }
            val navParts = buildList {
                quizState.previousButton?.let { add("[#${it.id}] Previous") }
                quizState.nextButton?.let {
                    add("[#${it.id}] ${it.primaryLabel.ifBlank { "Next" }} (${if (it.isEnabled) "ENABLED" else "DISABLED"})")
                }
                quizState.submitButton?.let { add("[#${it.id}] ${it.primaryLabel}") }
            }
            if (navParts.isNotEmpty()) sb.append("Quiz Nav: ${navParts.joinToString(" | ")}\n")
            sb.append("\n")
        }

        sb.append("Visible Elements:\n")
        val meaningfulAll = elements.filter {
            (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.isEditable || it.isCheckable ||
                it.hasCheckableDescendant || (it.isClickable && it.isImageOrThumbnail) || (it.isClickable && it.viewId.isNotBlank())) &&
                !it.isGenericContainer
        }

        val pills = meaningfulAll.filter { McqGrounder.isQuestionPillElement(it) }
        val collapsePills = pills.size >= 6
        val meaningful = if (collapsePills) {
            meaningfulAll.filterNot { McqGrounder.isQuestionPillElement(it) }
        } else {
            meaningfulAll
        }

        val screenBounds = Rect()
        root?.getBoundsInScreen(screenBounds)
        val screenWidth = if (screenBounds.width() > 0) screenBounds.width() else elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = if (screenBounds.height() > 0) screenBounds.height() else elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val isWebSearchPage = (pkg.contains("chrome", ignoreCase = true) || pkg.contains("browser", ignoreCase = true)) &&
            isGoogleSearchResults(elements)

        if (isWebSearchPage) {
            sb.append("⚠️ [CURRENT VIEW: WEB SEARCH RESULTS]\n")
            sb.append("RULE: Do NOT type your generation/creative prompt into the Google search bar! Look at the clickable website result links below and click the matching result to enter the website.\n")
        }

        if (collapsePills) {
            val pillList = pills.joinToString(", ") { "[#${it.id}]=${it.text.trim()}" }
            sb.append("Question Pills (${pills.size}): $pillList\n")
        }

        for (el in meaningful.take(160)) {
            val label = when {
                el.text.isNotBlank() && el.contentDescription.isNotBlank() && !el.contentDescription.equals(el.text, true) ->
                    "${el.text} [desc: ${el.contentDescription}]"
                el.isImageOrThumbnail && (el.primaryLabel.isBlank() || el.primaryLabel.contains("ImageView", ignoreCase = true)) ->
                    "Image Thumbnail"
                else -> el.primaryLabel
            }

            if (isWebSearchPage && el.isEditable) {
                sb.append("[#${el.id}] Search / URL Bar (InputField - DO NOT TYPE CREATIVE PROMPTS HERE)\n")
                continue
            } else if (isWebSearchPage && el.isClickable && !el.isEditable && el.bounds.top > 250 && el.text.isNotBlank()) {
                sb.append("[#${el.id}] Search Result Link: \"$label\" (Clickable - Tap to open site)\n")
                continue
            }

            sb.append("[#${el.id}] $label")
            if (el.isSwitchOrToggle()) {
                val stateLabel = if (el.className.contains("Radio", ignoreCase = true) || el.hasCheckableDescendant) {
                    if (el.isEffectivelyChecked()) "Radio: CHECKED ✓" else "Radio: UNCHECKED ○"
                } else {
                    if (el.isEffectivelyChecked()) "Switch: ON" else "Switch: OFF"
                }
                sb.append(" ($stateLabel)")
            } else if (el.isSelected) {
                sb.append(" (Selected ✓)")
            } else if (el.isEditable) {
                sb.append(" (InputField)")
            } else if (!el.isEnabled && el.isClickable) {
                sb.append(" (DISABLED)")
            } else if (el.isImageOrThumbnail) {
                val spatial = getSpatialPosition(el.bounds, screenWidth, screenHeight)
                sb.append(" (Position: $spatial, bounds=[${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}])")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /** Flow pages contain their own search and project controls; they are not web search results. */
    fun isGoogleFlowSurface(elements: List<UiElementInfo>): Boolean {
        val urlText = elements.asSequence()
            .filter { it.viewId.contains("url_bar", ignoreCase = true) || it.viewId.contains("omnibox", ignoreCase = true) }
            .joinToString(" ") { "${it.text} ${it.contentDescription}" }
            .lowercase(Locale.ROOT)
        if (urlText.contains("flow.google") || urlText.contains("labs.google/fx/tools/flow")) return true

        val textDump = elements.joinToString(" ") {
            "${it.text.lowercase(Locale.ROOT)} ${it.contentDescription.lowercase(Locale.ROOT)}"
        }
        val flowMarkers = listOf(
            "what do you want to create", "start creating or drop media", "long live the process",
            "google flow can make mistakes", "omni 1.1 flash", "generating will use", "new project"
        )
        return flowMarkers.count(textDump::contains) >= 2
    }

    /** Only call a page search results when its actual URL identifies a search query. */
    fun isGoogleSearchResults(elements: List<UiElementInfo>): Boolean {
        if (isGoogleFlowSurface(elements)) return false
        val urlText = elements.asSequence()
            .filter { it.viewId.contains("url_bar", ignoreCase = true) || it.viewId.contains("omnibox", ignoreCase = true) }
            .joinToString(" ") { "${it.text} ${it.contentDescription}" }
            .lowercase(Locale.ROOT)
        val explicitSearchUrl = urlText.contains("google.") &&
            (urlText.contains("/search") || urlText.contains("?q=") || urlText.contains("&q="))
        if (explicitSearchUrl || urlText.contains("google.com/search")) return true
        val textDump = elements.joinToString(" ") {
            "${it.text.lowercase(Locale.ROOT)} ${it.contentDescription.lowercase(Locale.ROOT)}"
        }
        return textDump.contains("google search results") || textDump.contains("search results for")
    }
}
