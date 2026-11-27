package com.example.gemini.data.automation

import java.util.Locale

/**
 * Pure-Kotlin decision engine, notification content classifier, safety filter,
 * and contextual fallback draft generator for #18 WhatsApp Notification Auto-Reply.
 *
 * Kept free of Android framework imports so all classification, anti-loop cooldown,
 * sensitive-message override, and contact-matching logic runs fast in unit tests.
 */
object WhatsAppAutoReplyHelper {

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val DEFAULT_FALLBACK_TEMPLATE = "Abhi busy hoon, thodi der mein baat karta hoon."
    const val DEFAULT_COOLDOWN_MS = 120_000L

    enum class ReplyAction {
        IGNORE,
        SMART_CONFIRM,
        DIRECT_AUTO_REPLY
    }

    data class IncomingWhatsAppMessage(
        val id: String,
        val notificationKey: String,
        val packageName: String,
        val senderName: String,
        val cleanSenderName: String,
        val messageText: String,
        val isGroupChat: Boolean = false,
        val groupName: String? = null,
        val conversationTitle: String? = null,
        val isSavedContact: Boolean = false,
        val contactDirectWhitelisted: Boolean = false,
        val hasRemoteInput: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class AutoReplyPolicyConfig(
        val enabled: Boolean = true,
        val directAutoReplyForSavedContacts: Boolean = false,
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        val requireRemoteInputForDirect: Boolean = true
    )

    data class AutoReplyDecision(
        val action: ReplyAction,
        val reason: String,
        val isSensitive: Boolean = false
    )

    private val BIDI_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val TRAILING_COUNT_REGEX = Regex("\\s*\\(\\d+\\s+(?:new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    private val AGGREGATE_SUMMARY_REGEX = Regex("^\\d+\\s+(?:new\\s+)?messages?(?:\\s+from\\s+\\d+\\s+chats?)?$", RegexOption.IGNORE_CASE)

    private val SYSTEM_TEXT_PREFIXES = listOf(
        "you:",
        "you ",
        "you reacted",
        "checking for new messages",
        "whatsapp web is currently active",
        "backup in progress",
        "uploading",
        "downloading media",
        "restoring",
        "finished backup",
        "ongoing voice call",
        "ongoing video call",
        "incoming voice call",
        "incoming video call",
        "missed voice call",
        "missed video call",
        "ringing",
        "calling",
        "waiting for this message",
        "this message was deleted"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "one time password", "verification code", "verify code", "cvv",
        "atm pin", "upi pin", "mpin", "password", "passcode",
        "bank account", "netbanking", "aadhaar", "aadhar", "pan card",
        "send money", "paise bhej", "paise transfer", "pay me", "upi id",
        "gpay", "phonepe", "paytm", "neft", "imps", "ifsc",
        "emergency", "hospital", "accident", "police", "urgent help", "bachao"
    )

    private val CURRENCY_ASK_REGEX = Regex(
        "(?:₹|rs\\.?|inr|rupees?)\\s*\\d+|\\d+\\s*(?:rupees?|rs\\.?|inr)",
        RegexOption.IGNORE_CASE
    )

    fun isWhatsAppPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT) ?: return false
        return pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE
    }

    fun sanitizeText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return BIDI_REGEX.replace(raw, "").replace(Regex("\\s+"), " ").trim()
    }

    fun cleanSenderDisplay(rawTitle: String?): String {
        val cleaned = sanitizeText(rawTitle)
        return TRAILING_COUNT_REGEX.replace(cleaned, "").trim()
    }

    fun normalizeSenderKey(sender: String?): String {
        return cleanSenderDisplay(sender)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s+]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Detects raw phone numbers like "+91 98765 43210" or "9876543210" so unsaved
     * numbers are never mistakenly treated as a saved contact name.
     */
    fun looksLikeUnsavedPhoneNumber(sender: String?): Boolean {
        val s = cleanSenderDisplay(sender)
        if (s.isBlank()) return true
        val hasLetters = s.any { it.isLetter() }
        if (hasLetters) return false
        val digits = s.count { it.isDigit() }
        return digits >= 7
    }

    /**
     * Extracts whether a WhatsApp notification represents a group chat and returns
     * `(isGroup, groupName, individualSender)` when encoded as `"Sender @ Group"` or `"Group: Sender"`.
     */
    fun parseGroupMetadata(
        rawTitle: String?,
        conversationTitle: String?,
        isGroupConversationExtra: Boolean = false
    ): Triple<Boolean, String?, String> {
        val cleanTitle = cleanSenderDisplay(rawTitle)
        val cleanConv = cleanSenderDisplay(conversationTitle)

        if (cleanConv.isNotBlank() && !cleanConv.equals(cleanTitle, ignoreCase = true)) {
            if (cleanTitle.contains(" @ ")) {
                val senderPart = cleanTitle.substringBefore(" @ ").trim()
                val groupPart = cleanTitle.substringAfter(" @ ").trim().ifBlank { cleanConv }
                return Triple(true, groupPart, senderPart.ifBlank { cleanTitle })
            }
            return Triple(true, cleanConv, cleanTitle)
        }

        if (cleanTitle.contains(" @ ")) {
            val senderPart = cleanTitle.substringBefore(" @ ").trim()
            val groupPart = cleanTitle.substringAfter(" @ ").trim()
            if (senderPart.isNotBlank() && groupPart.isNotBlank()) {
                return Triple(true, groupPart, senderPart)
            }
        }

        if (isGroupConversationExtra) {
            return Triple(true, cleanConv.ifBlank { cleanTitle }, cleanTitle)
        }

        return Triple(false, null, cleanTitle)
    }

    fun shouldIgnoreNotificationContent(
        sender: String?,
        text: String?,
        packageName: String?,
        isGroupSummary: Boolean = false
    ): Boolean {
        if (isGroupSummary) return true
        if (!isWhatsAppPackage(packageName)) return true
        val cleanSender = cleanSenderDisplay(sender)
        val cleanText = sanitizeText(text)
        if (cleanSender.isBlank() || cleanText.isBlank()) return true

        val lowerSender = cleanSender.lowercase(Locale.ROOT)
        val lowerText = cleanText.lowercase(Locale.ROOT)

        if (lowerSender == "whatsapp" || lowerSender == "whatsapp business") return true
        if (AGGREGATE_SUMMARY_REGEX.matches(lowerText)) return true
        if (SYSTEM_TEXT_PREFIXES.any { lowerText == it || lowerText.startsWith(it) }) return true

        // Media-only / call placeholders or self-message echoes often start with "You:"
        if (lowerSender == "you") return true

        return false
    }

    fun isSensitiveOrRiskyMessage(text: String?): Boolean {
        val clean = sanitizeText(text)
        if (clean.isBlank()) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (SENSITIVE_KEYWORDS.any { keyword ->
                val escaped = Regex.escape(keyword)
                Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lower)
            }
        ) {
            return true
        }
        return CURRENCY_ASK_REGEX.containsMatchIn(lower)
    }

    fun evaluateIncomingMessage(
        message: IncomingWhatsAppMessage,
        config: AutoReplyPolicyConfig,
        lastAutoRepliedAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
        isGroupSummary: Boolean = false
    ): AutoReplyDecision {
        if (!config.enabled) {
            return AutoReplyDecision(ReplyAction.IGNORE, "WhatsApp Auto-Reply is disabled")
        }
        if (shouldIgnoreNotificationContent(
                sender = message.cleanSenderName.ifBlank { message.senderName },
                text = message.messageText,
                packageName = message.packageName,
                isGroupSummary = isGroupSummary
            )
        ) {
            return AutoReplyDecision(ReplyAction.IGNORE, "System/summary/empty/self-echo notification ignored")
        }

        val sensitive = isSensitiveOrRiskyMessage(message.messageText)

        if (lastAutoRepliedAtMs > 0L && nowMs - lastAutoRepliedAtMs in 0L until config.cooldownMs) {
            return AutoReplyDecision(
                action = if (sensitive) ReplyAction.SMART_CONFIRM else ReplyAction.IGNORE,
                reason = "Cooldown active (${(config.cooldownMs - (nowMs - lastAutoRepliedAtMs)) / 1000L}s left)",
                isSensitive = sensitive
            )
        }

        if (sensitive) {
            return AutoReplyDecision(
                action = ReplyAction.SMART_CONFIRM,
                reason = "Sensitive/OTP/payment/emergency content always requires Smart Confirm",
                isSensitive = true
            )
        }

        if (message.isGroupChat) {
            return AutoReplyDecision(
                action = ReplyAction.SMART_CONFIRM,
                reason = "Group chats stay in Smart Confirm Mode",
                isSensitive = false
            )
        }

        val unsavedNumber = looksLikeUnsavedPhoneNumber(message.cleanSenderName)
        val verifiedSaved = message.isSavedContact && !unsavedNumber

        val allowDirect = (config.directAutoReplyForSavedContacts && verifiedSaved) ||
            (message.contactDirectWhitelisted && verifiedSaved)

        if (!allowDirect) {
            val why = if (!verifiedSaved) {
                "Sender is unfamiliar/unsaved; holding in Smart Confirm Mode"
            } else {
                "Direct Auto-Reply is off (Smart Confirm Mode active)"
            }
            return AutoReplyDecision(ReplyAction.SMART_CONFIRM, why, isSensitive = false)
        }

        if (config.requireRemoteInputForDirect && !message.hasRemoteInput) {
            return AutoReplyDecision(
                action = ReplyAction.SMART_CONFIRM,
                reason = "Notification missing RemoteInput reply action; holding in Smart Confirm Mode",
                isSensitive = false
            )
        }

        return AutoReplyDecision(
            action = ReplyAction.DIRECT_AUTO_REPLY,
            reason = if (message.contactDirectWhitelisted) {
                "Whitelisted saved contact eligible for Direct Auto-Reply"
            } else {
                "Saved contact eligible for Direct Auto-Reply"
            },
            isSensitive = false
        )
    }

    fun generateContextualFallbackDraft(
        senderName: String,
        incomingText: String,
        defaultTemplate: String = DEFAULT_FALLBACK_TEMPLATE
    ): String {
        val clean = sanitizeText(incomingText).lowercase(Locale.ROOT)
        val baseTemplate = sanitizeText(defaultTemplate).ifBlank { DEFAULT_FALLBACK_TEMPLATE }

        return when {
            clean.contains("good morning") || clean == "gm" || clean.startsWith("gm ") ->
                "Good morning! Abhi thoda busy hoon, thodi der mein baat karta hoon."
            clean.contains("good night") || clean == "gn" || clean.startsWith("gn ") ->
                "Good night! Kal baat karte hain."
            clean.contains("thank") || clean.contains("shukriya") || clean.contains("dhanyavad") || clean == "thx" ->
                "You're welcome! 😊"
            clean.contains("kaise ho") || clean.contains("kya haal") || clean.contains("how are you") ->
                "Sab badhiya hai! Abhi thoda busy hoon, thodi der mein baat karte hain."
            clean.contains("kahan") || clean.contains("where are you") || clean.contains("kab aa") ||
                clean.contains("free ho") || clean.contains("call karu") ->
                "Abhi thoda kaam mein hoon, free hote hi aapko call ya message karta hoon."
            clean == "hi" || clean == "hello" || clean == "hey" || clean == "hii" ||
                clean == "hlo" || clean.contains("namaste") || clean.contains("assalam") ->
                "Hi! Abhi thoda busy hoon, thodi der mein reply karta hoon."
            else -> baseTemplate
        }
    }

    fun matchPendingSenderScore(
        query: String,
        candidateSender: String,
        cleanSender: String
    ): Int {
        val normQ = normalizeSenderKey(query)
        val normCand = normalizeSenderKey(candidateSender)
        val normClean = normalizeSenderKey(cleanSender)
        if (normQ.isBlank()) return 0
        if (normQ == normClean || normQ == normCand) return 100
        if (normClean.startsWith(normQ) || normCand.startsWith(normQ)) return 85
        if (normClean.contains(normQ) || normCand.contains(normQ)) return 70
        val qTokens = normQ.split(" ").filter { it.length >= 2 }
        val cTokens = (normClean.split(" ") + normCand.split(" ")).filter { it.length >= 2 }.toSet()
        val overlap = qTokens.count { it in cTokens }
        return if (overlap > 0) 40 + overlap * 15 else 0
    }
}
