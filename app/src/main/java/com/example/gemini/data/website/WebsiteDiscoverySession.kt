package com.example.gemini.data.website

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.data.prompt.AuraPromptBuilder
import java.util.Locale

/**
 * App-side state for the website discovery flow.
 *
 * Voice only hands a `[WEBSITE DISCOVERY MODE]` kickoff to chat. From then on this object keeps
 * the chat in DISCOVERY until the user gives an explicit final confirmation. While the chat is in
 * DISCOVERY, every user message is prefixed with a hidden gate directive telling the agent not to
 * create files, run commands, or start a preview server. On confirmation the gate flips to BUILD
 * and the agent receives the approved-build directive.
 *
 * The state is persisted by chat id, so switching chats or restarting the app does not silently
 * unlock a half-finished discovery.
 */
object WebsiteDiscoverySession {
    private const val PREFS = "aura_website_discovery"
    private const val KEY_CHAT = "chat_id"
    private const val KEY_PHASE = "phase"
    private const val KEY_REQUEST = "request"

    enum class Phase { NONE, DISCOVERY, BUILD }

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var chatId: String? = null
    private var phase: Phase = Phase.NONE
    private var initialRequest: String = ""

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            chatId = p.getString(KEY_CHAT, null)?.takeIf { it.isNotBlank() }
            phase = runCatching { Phase.valueOf(p.getString(KEY_PHASE, Phase.NONE.name) ?: Phase.NONE.name) }
                .getOrDefault(Phase.NONE)
            initialRequest = p.getString(KEY_REQUEST, "").orEmpty()
        }
    }

    fun start(chatId: String, initialRequest: String) {
        if (chatId.isBlank()) return
        synchronized(lock) {
            this.chatId = chatId
            this.phase = Phase.DISCOVERY
            this.initialRequest = initialRequest.take(1600)
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            chatId = null
            phase = Phase.NONE
            initialRequest = ""
            persist()
        }
    }

    fun isDiscovery(chatId: String?): Boolean = synchronized(lock) {
        chatId != null && chatId == this.chatId && phase == Phase.DISCOVERY
    }

    fun isBuildApproved(chatId: String?): Boolean = synchronized(lock) {
        chatId != null && chatId == this.chatId && phase == Phase.BUILD
    }

    /**
     * Returns the hidden directive to prepend to [message], or null when this chat is not in a
     * website discovery/build flow.
     */
    fun directiveFor(chatId: String?, message: String): String? {
        synchronized(lock) {
            if (chatId == null || chatId != this.chatId) return null
            return when (phase) {
                Phase.DISCOVERY -> {
                    if (isBuildConfirmation(message)) {
                        phase = Phase.BUILD
                        persist()
                        AuraPromptBuilder.buildApprovedDirective()
                    } else {
                        AuraPromptBuilder.discoveryGateDirective()
                    }
                }
                Phase.BUILD -> null
                Phase.NONE -> null
            }
        }
    }

    /**
     * Only an explicit confirmation unlocks the build. A bare "haan" is intentionally not enough:
     * it is too easy to confuse with a yes to one discovery question.
     */
    private fun isBuildConfirmation(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        val phrases = listOf(
            "haan build karo", "haan build kar do", "haan build kardo",
            "ab build karo", "ab build kar do", "ab build kardo",
            "build karo", "build kar do", "build kardo",
            "build start karo", "build shuru karo", "start build", "start building",
            "final confirmation", "confirm kar diya", "confirmed", "approved",
            "haan bana do", "ab bana do", "ab website bana do",
            "yes build it", "go ahead and build", "build it now"
        )
        return phrases.any { lower.contains(it) }
    }

    private fun persist() {
        prefs?.edit()
            ?.putString(KEY_CHAT, chatId)
            ?.putString(KEY_PHASE, phase.name)
            ?.putString(KEY_REQUEST, initialRequest)
            ?.apply()
    }
}
