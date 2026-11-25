package com.example.gemini.data.live

import android.util.Log
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ChatContextBridge: Connects active text chat session to Gemini Live Voice session.
 * 1. Feeds active conversation history to Gemini Live's system prompt.
 * 2. Persists real-time speech turns (User & Assistant) into the active chat so transcripts are saved!
 * 3. Records executed tools in chat history.
 */
object ChatContextBridge {
    private const val TAG = "ChatContextBridge"
    private const val MAX_MESSAGES_IN_CONTEXT = 15

    var viewModel: ChatViewModel? = null
    @Volatile private var currentViewMode: String = "CHAT"
    @Volatile private var previousNonBrowserMode: String = "CHAT"

    fun requestViewMode(mode: String) {
        val upper = mode.trim().uppercase(Locale.ROOT)
        if (upper != "BROWSER") {
            previousNonBrowserMode = upper
        }
        currentViewMode = upper
        viewModel?.requestViewMode(upper)
    }

    private val _lastActiveChatTitle = MutableStateFlow("")
    val lastActiveChatTitle: StateFlow<String> = _lastActiveChatTitle.asStateFlow()

    var activeVoiceConvId: String? = null
    val requestSwitchToChatTab = MutableStateFlow(false)
    val requestCloseDrawer = MutableStateFlow(false)
    var isNewChatDraft: Boolean = false

    fun attach(vm: ChatViewModel) {
        viewModel = vm
        try {
            VoiceSessionStore.init(vm.getApplication())
        } catch (_: Exception) {}
        Log.i(TAG, "Attached ChatViewModel to ChatContextBridge")
    }

    fun startVoiceSessionConversation(): String {
        isNewChatDraft = false
        val vm = viewModel
        val conv = VoiceSessionStore.createSession(reuseActiveId = activeVoiceConvId)
        activeVoiceConvId = conv.id
        if (vm != null) {
            try {
                val prop = vm.javaClass.getDeclaredField("store")
                prop.isAccessible = true
                val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore
                store?.let {
                    it.conversations.value = listOf(conv) + it.conversations.value.filter { c -> c.id != conv.id }
                    it.currentConversation.value = conv
                    val existing = VoiceSessionStore.getSession(conv.id)?.messages ?: emptyList()
                    it.messages.value = existing
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update memory store for voice session: ${e.message}")
            }
            try {
                vm.selectConversation(conv.id)
            } catch (e: Exception) {
                Log.e(TAG, "selectConversation error: ${e.message}")
            }
        }
        return conv.id
    }

    fun endVoiceSessionConversation() {
        // Voice session is permanently retained in VoiceSessionStore and ChatDatabase
        activeVoiceConvId = null
        isNewChatDraft = false
    }

    fun recordVoiceUserTurn(text: String) {
        val convId = activeVoiceConvId ?: return
        val msg = ChatMessage(
            conversationId = convId,
            role = MessageRole.USER,
            content = text,
            viaVoice = true
        )
        VoiceSessionStore.appendMessage(convId, msg)
        viewModel?.let { appendVoiceMessage(it, convId, msg) }
    }

    fun recordVoiceAssistantTurn(textChunk: String) {
        val convId = activeVoiceConvId ?: return
        VoiceSessionStore.appendAssistantText(convId, textChunk)
        val vm = viewModel ?: return
        try {
            val prop = vm.javaClass.getDeclaredField("store")
            prop.isAccessible = true
            val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore ?: return

            if (store.currentConversation.value?.id == convId) {
                val currentList = store.messages.value.toMutableList()
                val lastMsg = currentList.lastOrNull()
                if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.viaVoice) {
                    val updated = lastMsg.copy(content = lastMsg.content + textChunk)
                    currentList[currentList.size - 1] = updated
                    store.messages.value = currentList
                } else {
                    val newMsg = ChatMessage(
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = textChunk,
                        viaVoice = true
                    )
                    store.messages.value = currentList + newMsg
                }
            }
        } catch (_: Exception) {}
    }

    private fun appendVoiceMessage(vm: ChatViewModel, convId: String, msg: ChatMessage) {
        try {
            val prop = vm.javaClass.getDeclaredField("store")
            prop.isAccessible = true
            val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore ?: return
            if (store.currentConversation.value?.id == convId) {
                store.messages.value = store.messages.value + msg
            }
        } catch (_: Exception) {}
    }

    fun openLiveChat(): String = openCurrentVoiceChat()

    fun openCurrentVoiceChat(): String {
        val vm = viewModel ?: return "Chat system is not available."
        val convId = activeVoiceConvId
        return if (convId != null) {
            isNewChatDraft = false
            vm.selectConversation(convId)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "Switched to current live voice chat on screen."
        } else {
            "No active voice chat found."
        }
    }

    fun openPreviousVoiceChat(): String {
        val vm = viewModel ?: return "Chat system is not available."
        val all = vm.allConversations.value
        val prev = all.filter {
            it.id != activeVoiceConvId && (
                it.title.startsWith("Voice Session", ignoreCase = true) ||
                it.title.contains("Voice", ignoreCase = true)
            )
        }.sortedByDescending { it.updatedAt }.firstOrNull()

        return if (prev != null) {
            isNewChatDraft = false
            vm.selectConversation(prev.id)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "Opened previous voice conversation '${prev.title}' on screen."
        } else {
            "No previous voice conversations found."
        }
    }

    fun openChatByName(chatName: String): String {
        val vm = viewModel ?: return "Chat system is not available."
        val query = chatName.trim()
        if (query.isBlank()) return "Please specify a chat name to open."

        val all = vm.allConversations.value
        if (all.isEmpty()) return "No conversations found in the app."

        // 1. Exact title match (case-insensitive)
        var matched = all.firstOrNull { it.title.equals(query, ignoreCase = true) }

        // 2. Title contains query or query contains title (case-insensitive)
        if (matched == null) {
            matched = all.firstOrNull {
                it.title.contains(query, ignoreCase = true) || query.contains(it.title, ignoreCase = true)
            }
        }

        // 3. Normalized keyword match (filtering out common filler words)
        if (matched == null) {
            val stopWords = setOf("chat", "session", "kholo", "open", "wala", "wali", "dikhao", "the", "a", "an", "show")
            val queryTokens = query.lowercase().split("\\s+".toRegex()).filter { it.length >= 2 && it !in stopWords }
            if (queryTokens.isNotEmpty()) {
                matched = all.maxByOrNull { conv ->
                    val titleLower = conv.title.lowercase()
                    queryTokens.count { token -> titleLower.contains(token) }
                }?.takeIf { conv ->
                    val titleLower = conv.title.lowercase()
                    queryTokens.any { token -> titleLower.contains(token) }
                }
            }
        }

        // 4. Exact ID or prefix ID match
        if (matched == null) {
            matched = all.firstOrNull { it.id == query || it.id.startsWith(query) }
        }

        return if (matched != null) {
            isNewChatDraft = false
            closeOverlaysForChatNavigation()
            vm.selectConversation(matched.id)
            syncActiveProjectFromConversation(matched)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "Opened chat '${matched.title}' on screen."
        } else {
            val sampleTitles = all.take(4).joinToString(", ") { "'${it.title}'" }
            "Could not find a chat matching '$query'. Available recent chats: $sampleTitles."
        }
    }

    fun getCurrentConversation(): Conversation? = viewModel?.currentConversation?.value

    fun getCurrentMessages(): List<ChatMessage> = viewModel?.messages?.value ?: emptyList()

    fun clearPromptCache() {
        lastSentPrompt = ""
        lastSentTime = 0L
    }

    /**
     * Strips accidental hardcoded wrapper prefixes (e.g. "Ek new chat create karo aur...") so
     * ONLY the clean user intent reaches the agent.
     */
    fun sanitizeTaskPrompt(raw: String): String {
        var cleaned = raw.trim()
        val stalePrefixPatterns = listOf(
            Regex("""(?i)^\s*(?:ek\s+)?new\s+chat\s+(?:create|shuru|start|open)\s+kar(?:o|ke)\s*(?:aur|and|mein|,|:|-)?\s*"""),
            Regex("""(?i)^\s*(?:ek\s+)?nayi\s+chat\s+(?:banao|banake|shuru\s+karo|shuru\s+karke|open\s+karo)\s*(?:aur|and|mein|,|:|-)?\s*"""),
            Regex("""(?i)^\s*(?:create|start|open)\s+a?\s*new\s+chat\s*(?:and|then|,|:|-)?\s*"""),
            Regex("""(?i)^\s*send\s+(?:this\s+)?to\s+(?:a\s+)?new\s+chat\s*[:,-]?\s*""")
        )
        for (pattern in stalePrefixPatterns) {
            val next = pattern.replaceFirst(cleaned, "").trim()
            if (next.isNotBlank()) cleaned = next
        }
        return cleaned
    }

    private fun closeOverlaysForChatNavigation() {
        requestCloseDrawer.value = true
        if (currentViewMode == "BROWSER") {
            try {
                com.example.gemini.ui.browser.BrowserSessionManager.instance.closeActiveAndStopMedia()
            } catch (_: Exception) {}
        }
    }

    fun syncActiveProjectFromConversation(conv: Conversation?, fallbackSummary: String = "") {
        conv ?: return
        val wsPath = conv.workspaceUri.removePrefix("file://").trim()
        val inferredName = wsPath.trimEnd('/').substringAfterLast('/').ifBlank {
            if (conv.title != "New Chat" && !conv.title.startsWith("Voice Session", true)) conv.title else ""
        }
        val summaryText = conv.summary?.takeIf { it.isNotBlank() } ?: fallbackSummary.take(280)
        if (inferredName.isNotBlank() || wsPath.isNotBlank() || summaryText.isNotBlank()) {
            AuraAgentEventBus.updateActiveProject { prev ->
                val sameSession = prev.sessionId == conv.id
                prev.copy(
                    sessionId = conv.id,
                    projectName = if (sameSession && prev.projectName.isNotBlank()) prev.projectName else inferredName,
                    workspaceDir = wsPath.ifBlank { if (sameSession) prev.workspaceDir else "" },
                    requirementsSummary = summaryText.ifBlank { if (sameSession) prev.requirementsSummary else "" }
                )
            }
        }
    }

    fun persistConversationMetadata(conv: Conversation, fallbackSummary: String = "") {
        val wsPath = conv.workspaceUri.removePrefix("file://").trim()
        val inferredName = wsPath.trimEnd('/').substringAfterLast('/').ifBlank {
            if (conv.title != "New Chat" && !conv.title.startsWith("Voice Session", true)) conv.title else ""
        }
        persistConversationMetadata(
            sessionId = conv.id,
            title = conv.title,
            projectName = inferredName.takeIf { it.isNotBlank() },
            projectPath = wsPath.takeIf { it.isNotBlank() },
            summary = (conv.summary?.takeIf { it.isNotBlank() } ?: fallbackSummary.take(200)).takeIf { it.isNotBlank() }
        )
    }

    fun persistConversationMetadata(
        sessionId: String,
        title: String,
        projectName: String? = null,
        projectType: String? = null,
        projectPath: String? = null,
        summary: String? = null
    ) {
        val ctx = viewModel?.getApplication<android.app.Application>() ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                com.example.gemini.data.database.ChatDatabase.getInstance(ctx).updateSessionMetadata(
                    sessionId = sessionId,
                    title = title,
                    sessionType = "CHAT",
                    projectName = projectName,
                    projectType = projectType,
                    projectPath = projectPath,
                    summary = summary
                )
            }
        }
    }

    fun getActiveChatPromptContext(): String {
        val vm = viewModel ?: return ""
        val conv = vm.currentConversation.value
        val msgs = vm.messages.value

        val sb = StringBuilder()

        // 0. Active Project Context (Cross-Screen Memory for Voice, Vision/Screen Share, and Chat)
        val projectBlock = AuraAgentEventBus.activeProject.value.toPromptBlock()
        if (projectBlock.isNotBlank()) {
            sb.append("\n").append(projectBlock)
        }

        // 1. Dynamic Recent Chats Memory Index (Top 10 sessions — prevents hallucination about past chats/projects)
        val dbSessions = try {
            com.example.gemini.data.database.ChatDatabase.getInstance(vm.getApplication()).sessions.value
        } catch (_: Exception) { emptyList() }
        val merged = (vm.allConversations.value + dbSessions)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
            .take(10)
        if (merged.isNotEmpty()) {
            sb.append("\n## Saved Conversations & Projects Index in Memory (Top ${merged.size}):\n")
            merged.forEach { c ->
                val proj = if (c.workspaceUri.isNotBlank()) " [Project: ${c.workspaceUri.removePrefix("file://").trimEnd('/').substringAfterLast('/')}]" else ""
                val sum = if (!c.summary.isNullOrBlank()) " - ${c.summary.take(70)}" else ""
                sb.append("- ID: \"${c.id}\" | Title: \"${c.title}\"$proj$sum\n")
            }
        }

        // 2. Active Chat Context on Screen
        if (conv != null || msgs.isNotEmpty()) {
            val title = conv?.title?.takeIf { it.isNotBlank() } ?: "Untitled Chat"
            _lastActiveChatTitle.value = title

            sb.append("\n## Active Chat Context on Screen:\n")
            sb.append("The user is currently viewing the conversation: \"$title\".\n")
            sb.append("Recent messages in this chat (from oldest to newest):\n")

            val recent = msgs.takeLast(MAX_MESSAGES_IN_CONTEXT)
            if (recent.isEmpty()) {
                sb.append("(No messages in this chat yet. This is a fresh chat.)\n")
            } else {
                recent.forEach { m ->
                    val roleName = when (m.role) {
                        MessageRole.USER -> "User"
                        MessageRole.ASSISTANT -> "Assistant"
                        MessageRole.SYSTEM -> "System"
                        else -> "Assistant"
                    }
                    val text = if (m.content.length > 500) m.content.take(500) + "... [truncated]" else m.content
                    sb.append("$roleName: $text\n")
                }
            }
            sb.append("Instructions: If the user asks about previous discussion or an older project/website chat, use this memory index immediately or call list_recent_chats / open_chat_by_id!\n\n")
        }

        return sb.toString()
    }

    fun createNewChat(): String = startNewChat()

    fun startNewChat(): String {
        val vm = viewModel ?: return "Could not access chat system."
        return try {
            isNewChatDraft = true
            vm.startNewChat()
            _lastActiveChatTitle.value = "New Chat"
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "A fresh new chat has been created on screen with welcome suggestion cards."
        } catch (e: Exception) {
            "Failed to start new chat: ${e.message}"
        }
    }

    private var lastSentPrompt: String = ""
    private var lastSentTime: Long = 0L

    private fun isPortOpen(port: Int): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Natively creates a dedicated new chat thread inside Aura and dispatches the task prompt directly.
     * Prevents duplicate execution, prompt looping, or leaking wrapper strings.
     */
    suspend fun startNewChatTask(prompt: String): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Error: App chat is not connected. Open the chat screen first."
        val cleanPrompt = sanitizeTaskPrompt(prompt)
        if (cleanPrompt.isBlank()) return@withContext "Error: Task prompt cannot be empty."

        val lowerPrompt = cleanPrompt.lowercase()
        val capabilityPhrases = listOf(
            "tum kya kya kar sakte ho", "kya kar sakte ho", "capabilities", "what can you do", "help me understand"
        )
        if (capabilityPhrases.any { lowerPrompt.contains(it) }) {
            Log.w(TAG, "Blocked capability query from executing as code task: $cleanPrompt")
            return@withContext "Ye ek question hai, koi coding task nahi. Kripya aawaz me natural jawab dein."
        }

        val now = System.currentTimeMillis()
        if (cleanPrompt == lastSentPrompt && (now - lastSentTime < 25_000L)) {
            Log.w(TAG, "Duplicate prompt blocked by guard: $cleanPrompt")
            return@withContext "Ye task already chal raha hai. Aap 'status kya hai' poochh sakte hain."
        }

        // Guard against queue storm: if currently streaming, don't spam sendMessage into queue
        if (vm.isStreaming.value) {
            Log.w(TAG, "Currently streaming, blocking duplicate task prompt from queueing")
            return@withContext "Abhi ek task actively chal raha hai. Kripya thoda wait karein ya 'status kya hai' poochhein."
        }

        lastSentPrompt = cleanPrompt
        lastSentTime = now

        try {
            // 1. Natively create a fresh new chat session in Kotlin FIRST (no wrapper text injected)
            isNewChatDraft = false
            closeOverlaysForChatNavigation()
            AuraAgentEventBus.resetQuestionDedupe()
            vm.startNewChat()
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            _lastActiveChatTitle.value = "New Chat"

            delay(350L) // Allow new conversation session to bind

            val conv = vm.currentConversation.value
            val initialMsgCount = vm.messages.value.size
            Log.i(TAG, "Sending clean task prompt directly to new chat: $cleanPrompt")

            // Update active project context immediately for cross-screen continuity
            if (conv != null) {
                val inferredProj = cleanPrompt.take(48).trim()
                AuraAgentEventBus.updateActiveProject { prev ->
                    prev.copy(
                        sessionId = conv.id,
                        projectName = inferredProj,
                        requirementsSummary = cleanPrompt.take(280),
                        workspaceDir = conv.workspaceUri.removePrefix("file://")
                    )
                }
                persistConversationMetadata(
                    sessionId = conv.id,
                    title = inferredProj,
                    projectName = inferredProj,
                    projectPath = conv.workspaceUri.removePrefix("file://"),
                    summary = cleanPrompt.take(200)
                )
            }

            // 2. Dispatch the exact clean user prompt directly without any hardcoded wrapper prefixes
            vm.sendMessage(cleanPrompt)
            clearPromptCache()

            // Wait up to 6s for initial acknowledgment
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 6000L) {
                val currentMsgs = vm.messages.value
                val lastMsg = currentMsgs.lastOrNull()
                if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.content.isNotBlank() && currentMsgs.size > initialMsgCount) {
                    return@withContext lastMsg.content
                }
                delay(200L)
            }

            return@withContext "Kaam shuru kar diya hai. Jaise hi agla step ya sawaal aayega, main aapko turant update kar dunga."
        } catch (e: Exception) {
            clearPromptCache()
            Log.e(TAG, "Error starting new chat task: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Natively sends a message to chat without requiring Android accessibility or Screen Share.
     * Can send to the current chat, create a new chat, or target a specific chat by ID or title.
     */
    suspend fun sendMessageToChat(
        message: String,
        target: String = "CURRENT",
        autoSend: Boolean = true
    ): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Error: App chat is not connected."
        val text = sanitizeTaskPrompt(message)
        if (text.isBlank()) return@withContext "Error: Message cannot be empty."

        when (target.trim().uppercase()) {
            "NEW" -> {
                isNewChatDraft = false
                closeOverlaysForChatNavigation()
                AuraAgentEventBus.resetQuestionDedupe()
                vm.startNewChat()
                requestViewMode("CHAT")
                requestSwitchToChatTab.value = true
                _lastActiveChatTitle.value = "New Chat"
                if (autoSend) {
                    delay(350L)
                    vm.sendMessage(text)
                    clearPromptCache()
                    "Message bhej diya gaya hai."
                } else {
                    "Nayi chat ready hai."
                }
            }
            "CURRENT", "" -> {
                isNewChatDraft = false
                closeOverlaysForChatNavigation()
                ensureConversationExists(vm)
                requestViewMode("CHAT")
                requestSwitchToChatTab.value = true
                if (autoSend) {
                    vm.sendMessage(text)
                    clearPromptCache()
                    "Message bhej diya hai."
                } else {
                    "Switched to current chat."
                }
            }
            else -> {
                val all = vm.allConversations.value
                val query = target.trim()
                val matched = all.firstOrNull { it.id == query || it.id.startsWith(query) }
                    ?: all.firstOrNull { it.title.equals(query, ignoreCase = true) }
                    ?: all.firstOrNull { it.title.contains(query, ignoreCase = true) }
                if (matched != null) {
                    isNewChatDraft = false
                    closeOverlaysForChatNavigation()
                    vm.selectConversation(matched.id)
                    syncActiveProjectFromConversation(matched)
                    requestViewMode("CHAT")
                    requestSwitchToChatTab.value = true
                    if (autoSend) {
                        delay(350L)
                        vm.sendMessage(text)
                        clearPromptCache()
                        "Chat '${matched.title}' open karke message bhej diya hai."
                    } else {
                        "Chat '${matched.title}' open kar di hai."
                    }
                } else {
                    isNewChatDraft = false
                    closeOverlaysForChatNavigation()
                    ensureConversationExists(vm)
                    requestViewMode("CHAT")
                    requestSwitchToChatTab.value = true
                    if (autoSend) {
                        vm.sendMessage(text)
                        clearPromptCache()
                        "Message bhej diya hai."
                    } else {
                        "Switched to chat."
                    }
                }
            }
        }
    }

    /**
     * Lists recent saved conversations and projects with metadata (ID, title, project name/path, summary)
     * across both live ViewModel sessions and persistent SQLite ChatDatabase (including message content search).
     */
    suspend fun listRecentChats(query: String? = null, limit: Int = 15): String = withContext(Dispatchers.IO) {
        val vm = viewModel ?: return@withContext "Chat system is not available."
        val boundedLimit = limit.coerceIn(1, 30)
        val db = runCatching { com.example.gemini.data.database.ChatDatabase.getInstance(vm.getApplication()) }.getOrNull()
        val dbMatches = runCatching { db?.searchSessions(query, boundedLimit) ?: emptyList() }.getOrDefault(emptyList())
        val dbById = dbMatches.associateBy { it.id }

        val allMem = vm.allConversations.value
        val q = query?.trim()?.lowercase()
        val memMatches = if (!q.isNullOrBlank()) {
            allMem.filter { conv ->
                conv.title.lowercase().contains(q) ||
                conv.summary?.lowercase()?.contains(q) == true ||
                conv.workspaceUri.lowercase().contains(q) ||
                conv.id.lowercase().contains(q)
            }
        } else {
            allMem
        }

        val combinedIds = LinkedHashSet<String>()
        val combinedConvs = mutableListOf<Conversation>()
        for (c in memMatches) {
            if (combinedIds.add(c.id)) combinedConvs.add(c)
        }
        for (meta in dbMatches) {
            if (combinedIds.add(meta.id)) combinedConvs.add(meta.toConversation())
        }

        val results = combinedConvs.sortedByDescending { it.updatedAt }.take(boundedLimit)
        if (results.isEmpty()) {
            return@withContext if (query.isNullOrBlank()) "No conversations found in the app."
            else "No chats found matching '$query'."
        }

        val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        val sb = StringBuilder("Found ${results.size} recent chats:\n")
        results.forEachIndexed { idx, c ->
            val meta = dbById[c.id]
            val dateStr = timeFormat.format(Date(c.updatedAt))
            sb.append("${idx + 1}. [${c.id}] \"${c.title}\"")
            val projectLabel = meta?.projectName?.takeIf { it.isNotBlank() }
                ?: c.workspaceUri.removePrefix("file://").trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            if (!projectLabel.isNullOrBlank()) {
                sb.append(" [Project: $projectLabel]")
            }
            val summaryText = c.summary?.takeIf { it.isNotBlank() } ?: meta?.summary.orEmpty()
            if (summaryText.isNotBlank()) {
                sb.append(" - Summary: ${summaryText.take(90)}")
            }
            sb.append(" ($dateStr)\n")
        }
        sb.toString().trim()
    }

    /**
     * Opens a specific chat on screen by its exact/partial ID, title, project name, or message keyword.
     * Closes the Drawer and In-Built Browser cleanly while keeping Voice active.
     */
    suspend fun openChatById(chatId: String): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Chat system is not available."
        val query = chatId.trim()
        if (query.isBlank()) return@withContext "Please specify a chat ID to open."

        val all = vm.allConversations.value
        var matched = all.firstOrNull { it.id == query || it.id.startsWith(query) }
            ?: all.firstOrNull { it.title.equals(query, ignoreCase = true) }
            ?: all.firstOrNull { it.title.contains(query, ignoreCase = true) || it.workspaceUri.contains(query, ignoreCase = true) }

        if (matched == null) {
            val dbHit = withContext(Dispatchers.IO) {
                runCatching {
                    val db = com.example.gemini.data.database.ChatDatabase.getInstance(vm.getApplication())
                    db.getSessionMetadata(query) ?: db.searchSessions(query, 1).firstOrNull()
                }.getOrNull()
            }
            if (dbHit != null) {
                matched = dbHit.toConversation()
            }
        }

        if (matched != null) {
            isNewChatDraft = false
            closeOverlaysForChatNavigation()
            vm.selectConversation(matched.id)
            syncActiveProjectFromConversation(matched)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "Opened chat '${matched.title}' on screen."
        } else {
            "Could not find a chat matching '$chatId'."
        }
    }

    /**
     * Programmatically closes the In-Built Browser, stops WebView media/timers cleanly,
     * and returns to the previous Aura screen without triggering Android Recents or Accessibility Back.
     */
    fun closeInAppBrowser(): String {
        try {
            com.example.gemini.ui.browser.BrowserSessionManager.instance.closeActiveAndStopMedia()
        } catch (_: Exception) {}

        val returnMode = if (previousNonBrowserMode.isNotBlank() && previousNonBrowserMode != "BROWSER") {
            previousNonBrowserMode
        } else {
            "CHAT"
        }
        requestViewMode(returnMode)
        return "In-Built Browser closed cleanly."
    }

    /**
     * Checks real-time progress of active background tasks, websites, and apps.
     * Checks localhost web servers (port 3000 first, then 3001, 5173, 8080, 8000) and active chat stream.
     * Zero screen share required.
     */
    suspend fun checkActiveTaskStatus(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        val activeProj = AuraAgentEventBus.activeProject.value
        if (activeProj.isActive) {
            sb.append("Active project: \"${activeProj.projectName.ifBlank { "Current Project" }}\"")
            if (activeProj.previewUrl.isNotBlank()) sb.append(" (Preview: ${activeProj.previewUrl})")
            sb.append("\n")
        }

        // 1. Check background tasks
        val tasks = com.example.gemini.data.jarvis.JarvisTasks.tasks.value
        val activeTasks = tasks.filter { it.active }
        val recentTasks = if (activeTasks.isNotEmpty()) activeTasks else tasks.take(2)
        if (recentTasks.isNotEmpty()) {
            recentTasks.forEach { t ->
                sb.append("Task: \"${t.title}\" (${t.state.label})")
                if (t.note.isNotBlank()) sb.append(" - Progress: ${t.note}")
                if (t.result.isNotBlank()) sb.append(" - Output: ${t.result.take(150)}")
                sb.append("\n")
            }
        }

        // 2. Check Localhost Servers (port 3000 first)
        val commonPorts = listOf(3000, 3001, 5173, 8080, 8000)
        val openPorts = commonPorts.filter { isPortOpen(it) }
        if (openPorts.isNotEmpty()) {
            sb.append("Localhost servers running: ")
            sb.append(openPorts.joinToString(", ") { "http://localhost:$it" })
            sb.append("\n")
        }

        // 3. Check Chat Stream State
        val vm = viewModel
        if (vm != null) {
            val isStreaming = vm.isStreaming.value
            val msgs = vm.messages.value
            val lastMsg = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
            if (isStreaming) {
                sb.append("Currently generating and updating files right now.\n")
                if (lastMsg != null) {
                    sb.append("Latest progress: ${lastMsg.content.takeLast(180)}\n")
                }
            } else if (lastMsg != null && sb.isEmpty()) {
                sb.append("Latest output: ${lastMsg.content.take(250)}\n")
            }
        }

        if (sb.isEmpty()) {
            return@withContext "No active tasks or builds are running currently."
        }
        return@withContext sb.toString().trim()
    }

    /**
     * Sends an update, correction, or new feature request to the running project task.
     */
    suspend fun sendTaskUpdate(instruction: String): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Error: App chat is not connected."
        if (instruction.isBlank()) return@withContext "Error: Instruction cannot be empty."

        try {
            ensureConversationExists(vm)
            val updateMsg = instruction.trim()
            vm.sendMessage(updateMsg)
            return@withContext "Update task chat mein bhej diya gaya hai: $instruction"
        } catch (e: Exception) {
            Log.e(TAG, "Error sending task update: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Inspects the internal state of the whole Aura app (chats, background tasks, open servers, active project).
     */
    suspend fun inspectAppState(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val vm = viewModel
        val conv = vm?.currentConversation?.value
        val allConvs = vm?.allConversations?.value ?: emptyList()
        sb.append("Current Chat: \"${conv?.title ?: "None"}\"\n")
        sb.append("Total Chats: ${allConvs.size}\n")
        if (allConvs.isNotEmpty()) {
            sb.append("Recent Chats: ")
            sb.append(allConvs.take(4).joinToString(", ") { "\"${it.title}\"" })
            sb.append("\n")
        }

        // Active Tasks
        val tasks = com.example.gemini.data.jarvis.JarvisTasks.tasks.value
        sb.append("Running Tasks (${tasks.count { it.active }} active):\n")
        tasks.take(3).forEach { t ->
            sb.append("- ${t.title}: ${t.state.label} (${t.note.ifBlank { "working" }})\n")
        }

        // Localhost servers
        val ports = listOf(8080, 3000, 5173, 8000)
        val openPorts = ports.filter { isPortOpen(it) }
        if (openPorts.isNotEmpty()) {
            sb.append("Open Local Servers: ")
            sb.append(openPorts.joinToString(", ") { "http://localhost:$it" })
            sb.append("\n")
        }

        return@withContext sb.toString().trim()
    }

    /**
     * Executes a complex user task by injecting it directly into the app's active chat session
     * (triggering Antigravity / Claude / Codex agent with all native tools and bash capabilities).
     * Waits for the agent to finish execution, then returns the assistant's final response text.
     */
    suspend fun executeTaskInChat(prompt: String, timeoutMs: Long = 120_000L): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Error: App chat is not connected. Open the chat screen first."
        if (prompt.isBlank()) return@withContext "Error: Prompt cannot be empty."

        try {
            ensureConversationExists(vm)
            val initialMsgCount = vm.messages.value.size
            Log.i(TAG, "Injecting task into app chat (initialMsgCount=$initialMsgCount): $prompt")

            // Send the prompt into the active chat (this triggers Antigravity/Claude agent)
            vm.sendMessage(prompt)

            // 1. Wait for streaming to start (or message count to increase)
            val startWait = System.currentTimeMillis()
            while (System.currentTimeMillis() - startWait < 5000L) {
                if (vm.isStreaming.value || vm.messages.value.size > initialMsgCount) {
                    break
                }
                delay(150L)
            }

            // 2. Wait for completion (isStreaming becomes false AND we have an assistant message)
            val execWait = System.currentTimeMillis()
            var latestAssistantContent = ""
            while (System.currentTimeMillis() - execWait < timeoutMs) {
                val currentMsgs = vm.messages.value
                val lastMsg = currentMsgs.lastOrNull()
                if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.content.isNotBlank()) {
                    latestAssistantContent = lastMsg.content
                }

                if (!vm.isStreaming.value && latestAssistantContent.isNotBlank() && currentMsgs.size > initialMsgCount) {
                    delay(400L) // Settle final tokens
                    val finalMsg = vm.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.content ?: latestAssistantContent
                    Log.i(TAG, "Task completed in chat: ${finalMsg.take(120)}...")
                    return@withContext finalMsg
                }
                delay(300L)
            }

            if (latestAssistantContent.isNotBlank()) {
                return@withContext latestAssistantContent
            }
            return@withContext "Task initiated in chat. The agent is working on it."
        } catch (e: Exception) {
            Log.e(TAG, "Error executing task in chat: ${e.message}", e)
            return@withContext "Error executing task: ${e.message}"
        }
    }

    fun recordUserTurn(text: String) {
        val vm = viewModel ?: return
        if (text.isBlank()) return

        try {
            ensureConversationExists(vm)
            val convId = vm.currentConversation.value?.id ?: UUID.randomUUID().toString()
            val msg = ChatMessage(
                conversationId = convId,
                role = MessageRole.USER,
                content = text,
                viaVoice = true
            )
            appendMessageToStore(vm, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording user turn in chat: ${e.message}")
        }
    }

    fun recordAssistantTurn(textChunk: String) {
        val vm = viewModel ?: return
        if (textChunk.isBlank()) return

        try {
            ensureConversationExists(vm)
            val convId = vm.currentConversation.value?.id ?: UUID.randomUUID().toString()
            val currentList = vm.messages.value.toMutableList()

            val lastMsg = currentList.lastOrNull()
            if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.viaVoice) {
                // Append chunk to ongoing assistant message
                val updated = lastMsg.copy(content = lastMsg.content + textChunk)
                currentList[currentList.size - 1] = updated
                updateStoreMessages(vm, currentList)
            } else {
                val newMsg = ChatMessage(
                    conversationId = convId,
                    role = MessageRole.ASSISTANT,
                    content = textChunk,
                    viaVoice = true
                )
                appendMessageToStore(vm, newMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording assistant turn in chat: ${e.message}")
        }
    }

    fun recordToolExecution(toolName: String, summary: String) {
        val convId = activeVoiceConvId ?: viewModel?.currentConversation?.value?.id
        val targetId = convId ?: UUID.randomUUID().toString()
        val msg = ChatMessage(
            conversationId = targetId,
            role = MessageRole.TOOL,
            content = "⚡ [$toolName]: $summary",
            viaVoice = true
        )
        if (convId != null) {
            VoiceSessionStore.appendMessage(convId, msg)
        }
        val vm = viewModel ?: return
        try {
            ensureConversationExists(vm)
            appendMessageToStore(vm, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording tool execution in chat: ${e.message}")
        }
    }

    private fun ensureConversationExists(vm: ChatViewModel) {
        if (vm.currentConversation.value == null) {
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val conv = Conversation(
                id = UUID.randomUUID().toString(),
                title = "Voice Session • $timeStr"
            )
            try {
                val prop = vm.javaClass.getDeclaredField("store")
                prop.isAccessible = true
                val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore
                store?.let {
                    it.currentConversation.value = conv
                    it.conversations.value = listOf(conv) + it.conversations.value
                }
            } catch (_: Exception) {}
        }
    }

    private fun appendMessageToStore(vm: ChatViewModel, msg: ChatMessage) {
        try {
            val prop = vm.javaClass.getDeclaredField("store")
            prop.isAccessible = true
            val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore
            store?.let {
                if (it.currentConversation.value?.id == msg.conversationId) {
                    it.messages.value = it.messages.value + msg
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "appendMessageToStore failed: ${e.message}")
        }
    }

    private fun updateStoreMessages(vm: ChatViewModel, list: List<ChatMessage>) {
        try {
            val prop = vm.javaClass.getDeclaredField("store")
            prop.isAccessible = true
            val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore
            store?.let {
                it.messages.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateStoreMessages failed: ${e.message}")
        }
    }
}
