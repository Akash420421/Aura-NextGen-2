package com.example.gemini.data.context

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.BuildConfig
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.PromptExpansion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What every chat is told about the app, for every agent alike, hidden in front of the user's message (the chat never
 * shows it). Two parts, both in Settings → Agents → Global context:
 *
 * - The built-in context, written by the app: where the agent runs (Termux on Android, local mode only) and how to
 *   show images, videos and files in this chat. Read-only, on by default, can be turned off.
 * - The user's own entries. Each goes either into the first message of a chat or into every message, and is sent to
 *   Jarvis only if the user says so (Jarvis has strict rules that a note meant for normal chats could break).
 *
 * The first-message part goes once per chat: the app remembers which version each chat got and sends it again only
 * when it changed (app update, edited entry), after Claude compacted the chat, or when the first message was edited.
 */
object GlobalContext {

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val text: String,
        val enabled: Boolean = true,
        /** Sent with every message instead of only the first one of a chat. */
        val everyMessage: Boolean = false,
        /** Also told to Jarvis (its chats and the chats it starts for tasks). */
        val forJarvis: Boolean = false
    )

    private const val MAX_REMEMBERED_CHATS = 600

    private var sp: SharedPreferences? = null
    private var filesDir: String = "/data/data/com.termux/files"

    private val _builtInEnabled = MutableStateFlow(true)
    val builtInEnabled: StateFlow<Boolean> = _builtInEnabled.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** chat id → fingerprint of the first-message context it got (oldest first, trimmed). */
    private val sent = LinkedHashMap<String, String>()

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("global_context", Context.MODE_PRIVATE)
        filesDir = context.applicationContext.filesDir.absolutePath
        _builtInEnabled.value = p.getBoolean("builtin_enabled", true)
        _entries.value = runCatching {
            val arr = JSONArray(p.getString("entries", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = o.optString("title"),
                    text = o.optString("text"),
                    enabled = o.optBoolean("enabled", true),
                    everyMessage = o.optBoolean("everyMessage", false),
                    forJarvis = o.optBoolean("forJarvis", false)
                )
            }
        }.getOrDefault(emptyList())
        runCatching {
            val o = JSONObject(p.getString("sent", "{}") ?: "{}")
            synchronized(sent) { o.keys().forEach { k -> sent[k] = o.getString(k) } }
        }
        sp = p
    }

    // ------------------------------------------------------------------ settings

    fun setBuiltInEnabled(on: Boolean) {
        _builtInEnabled.value = on
        sp?.edit()?.putBoolean("builtin_enabled", on)?.apply()
    }

    fun upsert(entry: Entry) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list += entry
        saveEntries(list)
    }

    fun delete(id: String) = saveEntries(_entries.value.filter { it.id != id })

    private fun saveEntries(list: List<Entry>) {
        _entries.value = list
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("text", e.text).put("enabled", e.enabled)
                    .put("everyMessage", e.everyMessage).put("forJarvis", e.forJarvis)
            )
        }
        sp?.edit()?.putString("entries", arr.toString())?.apply()
    }

    // ------------------------------------------------------------------ the built-in text

    /** Termux build with the bridge on this phone: the agent runs in Termux, not on a server. */
    val runsInTermux: Boolean
        get() = BuildConfig.FLAVOR.startsWith("termux") && !AuthPreferences.isRemoteEndpoint()

    /** The built-in context for the current setup (Settings shows exactly this). [h]: the heading level of its sections. */
    fun builtInText(h: String = "##"): String = buildString {
        if (runsInTermux) {
            append("$h Where you run\n")
            append("- Android, inside Termux (Aura AI app): no root, no sudo; /tmp, /usr, /etc are not usable.\n")
            append("- Temporary files: use \$TMPDIR ($filesDir/usr/tmp), never /tmp. Install packages with `pkg install <name>`.\n")
            append("- Home is \$HOME ($filesDir/home); phone storage is /sdcard.\n\n")
        }
        append("$h Aura Unified Identity & Web/App Build Contract\n")
        append("- Identity: You are Aura, one seamless AI assistant. Never expose internal sub-agent/delegation mechanics or ask the user to manually click '+' to create a new chat.\n")
        append("- Discovery-First Build Workflow: When the user asks to build a website, portfolio, or app and key details are not yet specified (unless they explicitly say 'questions mat pucho, seedha bana do' / 'skip questions'), FIRST ask 3-5 concise targeted questions (Theme/Color Palette, Tech Stack & Multi-File Structure, Target Device, Key Sections/Features, Typography/Motion). Once confirmed, write `requirements.md` in the project directory and build strictly against it.\n")
        append("- Strict Requirements Contract:\n")
        append("  1. Theme Lock: If the user asks for a light/white glassmorphism theme, build genuine light backgrounds (#FFFFFF/#F8FAFC) with translucent light glass surfaces and dark legible text (#0F172A). NEVER silently default to dark/black neon themes.\n")
        append("  2. Multi-File & Stack Lock: If the user asks for separate HTML/CSS/JS or a React/Vite/Next project, create clean modular files (`index.html`, `styles.css`, `script.js` or framework files) — NEVER collapse everything into a single inline `index.html`.\n")
        append("  3. Mobile-First Responsive Lock: Always include `<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">`, `overflow-x: hidden`, fluid widths, and responsive breakpoints down to 360px-430px.\n")
        append("  4. Meta-Instructions vs Domain Content Guard: Phrases like 'GitHub UI skills', 'modern SaaS UI', 'Apple-style design', or 'Vercel aesthetic' refer ONLY to visual design quality and engineering polish. NEVER invent fake GitHub Repositories, Stars, Pull Request counters, or Commit Heatmaps on an unrelated domain (such as a personal portfolio, cafe, agency, or store) unless the user explicitly asked for a GitHub profile page.\n")
        append("- Verified Local Preview Server: NEVER execute `python3 index.html` or `python *.html` (Python cannot execute HTML files). Serve static projects via `python3 -m http.server 3000 --bind 127.0.0.1` (or next free port) or start Node/Vite dev servers on port `3000`, verify HTTP 200 on `http://127.0.0.1:<port>`, then open `http://localhost:<port>`.\n\n")
        append("$h Showing things in this chat (paths absolute, no spaces, each on its own line)\n")
        append("- Image: `![caption](/abs/path/pic.png)` · Video: `![caption](/abs/path/clip.mp4)` (plays in the chat)\n")
        append("- File: `[name](/abs/path/file.ext)` (the user taps to open)\n")
        append("Show images, videos and documents you create this way instead of only naming the path.")
    }.trimEnd()

    // ------------------------------------------------------------------ what goes where

    private fun active(): List<Entry> = _entries.value.filter { it.enabled && it.text.isNotBlank() }

    private fun entriesBlock(list: List<Entry>): String = list.joinToString("\n\n") { e ->
        (if (e.title.isNotBlank()) "### ${e.title.trim()}\n" else "") + e.text.trim()
    }

    /** The first-message context of a normal chat, or null when there is nothing to send. */
    fun firstMessageBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += builtInText()
        val own = active().filter { !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private val CASUAL_NON_PROJECT_REGEX = Regex(
        """^(?:hi+|hello+|hey+|hii+|heyy+|yo+|sup|hlo|namaste|salam|assalamualaikum|good\s+(?:morning|afternoon|evening|night)|how\s+are\s+you|how\s+r\s+u|kya\s+haal\s+hai|kaise\s+ho|thanks?|thank\s+you|thx|ok(?:ay)?|cool|nice|great|bye+|test|testing)[!.?\s]*$""",
        RegexOption.IGNORE_CASE
    )

    private val PROJECT_SIGNAL_REGEX = Regex(
        """\b(?:project|repo|repository|code|codebase|file|files|folder|directory|workspace|app|website|site|webpage|web|server|port|localhost|build|compile|fix|debug|error|bug|crash|refactor|implement|feature|component|screen|ui|css|html|js|ts|tsx|jsx|kt|kts|py|go|json|gradle|npm|pnpm|vite|react|next|tailwind|flutter|android|apk|git|commit|branch|backend|frontend|api|database|sql)\b""",
        RegexOption.IGNORE_CASE
    )

    internal fun isCasualNonProjectText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return CASUAL_NON_PROJECT_REGEX.matches(trimmed)
    }

    internal fun hasExplicitProjectSignal(text: String, projectName: String = ""): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || isCasualNonProjectText(trimmed)) return false
        if (projectName.isNotBlank() && trimmed.contains(projectName, ignoreCase = true)) return true
        if (trimmed.contains("/") || trimmed.contains("\\")) return true
        return PROJECT_SIGNAL_REGEX.containsMatchIn(trimmed)
    }

    /**
     * Only inject an active project banner when it belongs to THIS conversation (`sessionId == chatId`),
     * is recently active, and isn't an empty/stale first-message greeting.
     */
    internal fun shouldInjectActiveProjectContext(
        project: com.example.gemini.data.live.ActiveProjectContext,
        chatId: String?,
        hasEarlierMessages: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 20 * 60_000L
    ): Boolean {
        if (!project.isActive) return false
        if (chatId.isNullOrBlank() || project.sessionId.isBlank() || project.sessionId != chatId) return false
        if (nowMs - project.updatedAt > maxAgeMs) return false
        if (!hasEarlierMessages) {
            val hasExplicitWork = project.previewUrl.isNotBlank() ||
                (project.requirementsSummary.isNotBlank() && !isCasualNonProjectText(project.requirementsSummary))
            if (!hasExplicitWork) return false
        }
        return true
    }

    /** What every message of a normal chat carries, or null. */
    fun everyMessageBody(
        chatId: String? = null,
        hasEarlierMessages: Boolean = true
    ): String? {
        val parts = mutableListOf<String>()
        val activeProj = com.example.gemini.data.live.AuraAgentEventBus.activeProject.value
        if (shouldInjectActiveProjectContext(activeProj, chatId, hasEarlierMessages)) {
            val projectBlock = activeProj.toPromptBlock()
            if (projectBlock.isNotBlank()) parts += projectBlock.trimEnd()
        }
        val own = active().filter { it.everyMessage }
        if (own.isNotEmpty()) {
            parts += "[The user's standing instructions, sent with every message]\n" + entriesBlock(own)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Changes whenever the first-message context changes. */
    fun fingerprint(): String = Integer.toHexString((firstMessageBody() ?: "").hashCode())

    /**
     * A normal chat message with the hidden context in front. [chatId]: the chat on screen (blank for a new one);
     * [hasEarlierMessages]: the chat already has messages. Returns the prompt and, when the first-message context was
     * included, its fingerprint (record it with [markSent] once the chat id is known).
     */
    fun wrap(chatId: String?, hasEarlierMessages: Boolean, text: String): Pair<String, String?> {
        val first = firstMessageBody()
        val fp = fingerprint()
        val known = chatId?.takeIf { it.isNotBlank() }?.let { id -> synchronized(sent) { sent[id] } }
        val sendFirst = first != null && (!hasEarlierMessages || known != fp)
        val every = everyMessageBody(chatId = chatId, hasEarlierMessages = hasEarlierMessages)
        if (!sendFirst && every == null) return text to null
        val note = buildString {
            if (sendFirst) {
                append(
                    when {
                        !hasEarlierMessages -> "[Aura app context: from the app the user chats through, not written by the user. Follow it for this whole chat; it is not repeated.]"
                        known != null -> "[Aura app context, updated: it replaces the earlier copy in this chat. Follow it from now on.]"
                        else -> "[Aura app context: from the app the user chats through. Follow it from now on in this chat.]"
                    }
                ).append("\n\n").append(first)
            }
            if (every != null) {
                if (sendFirst) append("\n\n")
                append(every)
            }
        }
        if (sendFirst && !chatId.isNullOrBlank()) markSent(chatId, fp)
        return (PromptExpansion.hidden(note) + text) to (if (sendFirst) fp else null)
    }

    fun markSent(chatId: String, fp: String) {
        if (chatId.isBlank()) return
        synchronized(sent) {
            sent.remove(chatId)
            sent[chatId] = fp
            while (sent.size > MAX_REMEMBERED_CHATS) sent.remove(sent.keys.first())
        }
        persistSent()
    }

    /** The chat lost its first-message context (compacted, first message edited): send it again with the next message. */
    fun forget(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val removed = synchronized(sent) { sent.remove(chatId) } != null
        if (removed) persistSent()
    }

    private fun persistSent() {
        val o = JSONObject()
        synchronized(sent) { sent.forEach { (k, v) -> o.put(k, v) } }
        sp?.edit()?.putString("sent", o.toString())?.apply()
    }

    // ------------------------------------------------------------------ Jarvis

    private const val JARVIS_PRIORITY =
        "These are the user's notes for normal chats, shared with Jarvis. Follow them where they fit; if one conflicts with the Jarvis guide above, the Jarvis guide wins."

    /** A section of the Jarvis guide (first message of a Jarvis chat): the built-in context and the user's Jarvis-enabled first-message entries. */
    fun jarvisGuideSection(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "## AntiGem app context (for you and every chat you start)\n" + builtInText("###")
        val own = active().filter { it.forJarvis && !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n$JARVIS_PRIORITY\n\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** The user's Jarvis-enabled every-message entries, for the note in front of every Jarvis message. */
    fun jarvisEveryMessage(): String? {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return null
        return "user's standing instructions (the Jarvis rules win on conflict): " + own.joinToString(" | ") { e ->
            (if (e.title.isNotBlank()) "${e.title.trim()}: " else "") + e.text.trim().replace("\n", " ")
        }
    }

    /** Changes whenever what Jarvis' guide carries from here changes (the guide is then sent again). */
    fun jarvisFingerprint(): String = Integer.toHexString(((jarvisGuideSection() ?: "") + (jarvisEveryMessage() ?: "")).hashCode())

    /** The context for a chat Jarvis starts for a task: the built-in context and the user's Jarvis-enabled entries. */
    fun taskBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "[AntiGem app context: from the app, not the user. Follow it for this whole chat.]\n\n" + builtInText()
        val own = active().filter { it.forJarvis }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** A follow-up Jarvis sends to a task chat: the user's Jarvis-enabled every-message entries in front. */
    fun taskFollowUp(text: String): String {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return text
        return PromptExpansion.hidden("[The user's standing instructions, sent with every message]\n" + entriesBlock(own)) + text
    }
}
