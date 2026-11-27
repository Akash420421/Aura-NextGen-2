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
    private const val MAX_MESSAGES_IN_CONTEXT = 8

    var viewModel: ChatViewModel? = null
    @Volatile private var currentViewMode: String = "CHAT"
    @Volatile private var previousNonBrowserMode: String = "CHAT"

    fun requestViewMode(mode: String) {
        val upper = mode.trim().uppercase(Locale.ROOT)
        syncViewModeFromUi(upper)
        viewModel?.requestViewMode(upper)
    }

    fun syncViewModeFromUi(mode: String) {
        val upper = mode.trim().uppercase(Locale.ROOT)
        if (upper != "BROWSER") {
            previousNonBrowserMode = upper
        }
        currentViewMode = upper
    }

    fun isInBuiltBrowserOpen(): Boolean {
        return currentViewMode == "BROWSER" ||
            runCatching { com.example.gemini.ui.browser.BrowserSessionManager.instance.getActiveTab()?.isOnScreen == true }.getOrDefault(false)
    }

    fun isInBuiltBrowserActive(): Boolean {
        if (!isInBuiltBrowserOpen()) return false
        val svc = com.example.gemini.data.automation.AutomationAccessibilityService.instance
        if (svc != null && svc.isExternalAppForeground()) {
            return false
        }
        return true
    }

    private val _lastActiveChatTitle = MutableStateFlow("")
    val lastActiveChatTitle: StateFlow<String> = _lastActiveChatTitle.asStateFlow()

    var activeVoiceConvId: String? = null
    @Volatile var explicitlySelectedChatId: String? = null
    @Volatile var isVoiceLandingVisible: Boolean = false
    val requestSwitchToChatTab = MutableStateFlow(false)
    val requestCloseDrawer = MutableStateFlow(false)
    var isNewChatDraft: Boolean = false

    data class ChatSwitchResult(
        val success: Boolean,
        val message: String,
        val matchedId: String? = null,
        val matchedTitle: String? = null,
        val isPinned: Boolean = false
    )

    data class InternalNavCommand(
        val id: Long,
        val target: AppNavigationRouter.NavTarget
    )

    data class InternalNavResult(
        val success: Boolean,
        val message: String,
        val screenLabel: String = ""
    )

    private val navCommandSeq = java.util.concurrent.atomic.AtomicLong(0L)
    val internalNavCommand = MutableStateFlow<InternalNavCommand?>(null)
    val requestedDrawerTab = MutableStateFlow<String?>(null)
    @Volatile var acknowledgedNavCommandId: Long = 0L

    fun acknowledgeNavCommand(id: Long) {
        if (id > acknowledgedNavCommandId) {
            acknowledgedNavCommandId = id
        }
        if (internalNavCommand.value?.id == id) {
            internalNavCommand.value = null
        }
    }

    fun consumeDrawerTabRequest() {
        requestedDrawerTab.value = null
    }

    fun attach(vm: ChatViewModel) {
        viewModel = vm
        try {
            VoiceSessionStore.init(vm.getApplication())
        } catch (_: Exception) {}
        Log.i(TAG, "Attached ChatViewModel to ChatContextBridge")
    }

    fun startVoiceSessionConversation(): String {
        isNewChatDraft = false
        explicitlySelectedChatId = null
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
        explicitlySelectedChatId = null
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

    suspend fun recordVoiceGeneratedImage(
        prompt: String,
        filePath: String,
        caption: String = ""
    ): Unit = withContext(Dispatchers.Main) {
        val vm = viewModel
        val targetConvId = activeVoiceConvId
            ?: vm?.currentConversation?.value?.id
            ?: vm?.let { ensureConversationExists(it); vm.currentConversation.value?.id }
            ?: return@withContext

        val safeCaption = caption.ifBlank { prompt }.replace("[", "(").replace("]", ")").take(100)
        val tool = com.example.gemini.domain.model.ToolCall(
            name = "generate_image",
            toolType = com.example.gemini.domain.model.ToolType.GENERATE_IMAGE,
            command = prompt,
            status = "SUCCESS",
            output = filePath
        )
        val msg = ChatMessage(
            conversationId = targetConvId,
            role = MessageRole.ASSISTANT,
            content = "![$safeCaption]($filePath)\n\n${safeCaption}",
            toolCalls = listOf(tool),
            viaVoice = false
        )
        if (VoiceSessionStore.hasSession(targetConvId) || targetConvId == activeVoiceConvId) {
            VoiceSessionStore.appendMessage(targetConvId, msg)
        }
        if (vm != null) {
            appendMessageToStore(vm, msg)
            com.example.gemini.ui.chat.ChatFeedCache.prewarm(vm.messages.value)
        }
    }

    suspend fun openLiveChat(): String = openCurrentVoiceChat()

    suspend fun openCurrentVoiceChat(): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Chat system is not available."
        val convId = activeVoiceConvId
        if (convId != null) {
            isNewChatDraft = false
            explicitlySelectedChatId = null
            vm.selectConversation(convId)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "Switched to current live voice chat on screen."
        } else {
            "No active voice chat found."
        }
    }

    suspend fun openPreviousVoiceChat(): ChatSwitchResult {
        val vm = viewModel ?: return ChatSwitchResult(false, "Chat system is not available.")
        val all = vm.allConversations.value
        val prev = all.filter {
            it.id != activeVoiceConvId && (
                it.title.startsWith("Voice Session", ignoreCase = true) ||
                it.title.contains("Voice", ignoreCase = true)
            )
        }.sortedByDescending { it.updatedAt }.firstOrNull()
            ?: return ChatSwitchResult(false, "No previous voice conversations found.")

        val pinned = runCatching {
            com.example.gemini.data.preferences.AuraCustomizePreferences
                .getPinnedConversationIds(vm.getApplication())
                .contains(prev.id)
        }.getOrDefault(false)
        return navigateToChatAndVerify(vm, prev, pinned)
    }

    suspend fun openChatByName(chatName: String): ChatSwitchResult {
        val vm = viewModel ?: return ChatSwitchResult(false, "Chat system is not available.")
        val query = chatName.trim()
        if (query.isBlank()) return ChatSwitchResult(false, "Please specify a chat name to open.")

        val ctx = vm.getApplication<android.app.Application>()
        val pinnedIds = runCatching {
            com.example.gemini.data.preferences.AuraCustomizePreferences.getPinnedConversationIds(ctx)
        }.getOrDefault(emptySet())

        val memList = vm.allConversations.value
        val dbList = withContext(Dispatchers.IO) {
            runCatching {
                val db = com.example.gemini.data.database.ChatDatabase.getInstance(ctx)
                (db.searchSessions(query, 20) + db.searchSessions(null, 60)).distinctBy { it.id }
            }.getOrDefault(emptyList())
        }
        val dbById = dbList.associateBy { it.id }

        val mergedConvs = (memList + dbList.map { it.toConversation() }).distinctBy { it.id }
        if (mergedConvs.isEmpty()) {
            return ChatSwitchResult(false, "No conversations found in the app.")
        }

        val candidates = mergedConvs.map { c ->
            val meta = dbById[c.id]
            ChatMatcher.ChatCandidate(
                id = c.id,
                title = c.title,
                projectName = meta?.projectName.orEmpty(),
                workspaceUri = c.workspaceUri,
                summary = c.summary?.takeIf { it.isNotBlank() } ?: meta?.summary.orEmpty(),
                updatedAt = c.updatedAt,
                isPinned = c.id in pinnedIds,
                isArchived = c.archived || meta?.archived == true,
                agentName = c.agent.name
            )
        }

        val match = ChatMatcher.findBestMatch(query, candidates)
        if (match == null) {
            val topPinnedTitles = candidates.filter { it.isPinned }.take(3).map { "'${it.title}' (pinned)" }
            val sampleTitles = (topPinnedTitles + candidates.filterNot { it.isPinned }.take(4).map { "'${it.title}'" })
                .distinct().take(5).joinToString(", ")
            return ChatSwitchResult(
                false,
                "Could not find a chat matching '$query'. Available chats: $sampleTitles."
            )
        }

        val matchedConv = mergedConvs.first { it.id == match.candidate.id }
        return navigateToChatAndVerify(vm, matchedConv, match.candidate.isPinned)
    }

    private suspend fun navigateToChatAndVerify(
        vm: ChatViewModel,
        matched: Conversation,
        isPinned: Boolean
    ): ChatSwitchResult {
        val targetId = matched.id
        withContext(Dispatchers.Main) {
            isNewChatDraft = false
            explicitlySelectedChatId = targetId
            closeOverlaysForChatNavigation()
            internalNavCommand.value = InternalNavCommand(
                id = navCommandSeq.incrementAndGet(),
                target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
            )
            vm.selectConversation(targetId)
            syncActiveProjectFromConversation(matched)
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
        }

        // Wait up to 1400ms to confirm ViewModel + Compose ACTUALLY switched onto targetId & left Voice overlay
        val deadline = System.currentTimeMillis() + 1_400L
        var activeConvIdNow: String? = null
        var activeConvTitleNow: String? = null
        while (System.currentTimeMillis() < deadline) {
            val snapshot = withContext(Dispatchers.Main) {
                val c = vm.currentConversation.value
                Triple(c?.id, c?.title, currentViewMode == "CHAT" && !isVoiceLandingVisible && !requestSwitchToChatTab.value)
            }
            activeConvIdNow = snapshot.first
            activeConvTitleNow = snapshot.second
            if (activeConvIdNow == targetId && snapshot.third) {
                return ChatSwitchResult(
                    success = true,
                    message = "Opened${if (isPinned) " pinned" else ""} chat '${matched.title}' on screen.",
                    matchedId = matched.id,
                    matchedTitle = matched.title,
                    isPinned = isPinned
                )
            }
            delay(50L)
        }

        // Fallback tolerance: if backend bound targetId cleanly into ViewModel even before animation settled
        if (activeConvIdNow == targetId && currentViewMode == "CHAT") {
            return ChatSwitchResult(
                success = true,
                message = "Opened${if (isPinned) " pinned" else ""} chat '${matched.title}' on screen.",
                matchedId = matched.id,
                matchedTitle = matched.title,
                isPinned = isPinned
            )
        }

        explicitlySelectedChatId = null
        return ChatSwitchResult(
            success = false,
            message = "FAILED: Could not switch screen to '${matched.title}'; still showing '${activeConvTitleNow ?: "previous screen"}'."
        )
    }

    /**
     * Opens any internal screen, drawer tab, plugins page, or Settings section inside Aura AI
     * directly via ViewModel & Compose state flows and waits for acknowledgment.
     */
    suspend fun navigateToInternalScreen(rawTarget: String): InternalNavResult {
        val vm = viewModel ?: return InternalNavResult(false, "Aura screen controller is not ready yet.")
        val target = AppNavigationRouter.resolve(rawTarget)
            ?: return InternalNavResult(false, "Could not recognize Aura screen '$rawTarget'.")

        if (target is AppNavigationRouter.NavTarget.ViewMode) {
            withContext(Dispatchers.Main) {
                requestCloseDrawer.value = true
                if (target.mode == "CHAT") {
                    closeOverlaysForChatNavigation()
                    internalNavCommand.value = InternalNavCommand(
                        id = navCommandSeq.incrementAndGet(),
                        target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
                    )
                }
                requestViewMode(target.mode)
            }
            val deadline = System.currentTimeMillis() + 900L
            while (System.currentTimeMillis() < deadline) {
                if (currentViewMode == target.mode) {
                    return InternalNavResult(true, "Opened ${target.label}.", target.label)
                }
                delay(40L)
            }
            return if (currentViewMode == target.mode) {
                InternalNavResult(true, "Opened ${target.label}.", target.label)
            } else {
                InternalNavResult(false, "FAILED: Tried opening ${target.label}, still on $currentViewMode.")
            }
        }

        val cmdId = navCommandSeq.incrementAndGet()
        withContext(Dispatchers.Main) {
            requestViewMode("CHAT")
            internalNavCommand.value = InternalNavCommand(id = cmdId, target = target)
        }

        val deadline = System.currentTimeMillis() + 1_200L
        while (System.currentTimeMillis() < deadline) {
            if (acknowledgedNavCommandId >= cmdId) {
                return InternalNavResult(true, "Opened ${target.label} inside Aura.", target.label)
            }
            delay(40L)
        }
        return InternalNavResult(false, "FAILED: Aura UI did not acknowledge opening '${target.label}'.")
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
        val wsName = wsPath.trimEnd('/').substringAfterLast('/')
        val isGenericTitle = conv.title.isBlank() ||
            conv.title == "New Chat" ||
            conv.title == "Antigravity Chat" ||
            conv.title.startsWith("Voice Session", true)
        val cleanFallback = com.example.gemini.ui.components.ImageGenUiHelper.stripUiRatioTag(fallbackSummary).trim()
        val hasStoredSummary = !conv.summary.isNullOrBlank()
        val isImageRequest = com.example.gemini.ui.components.ImageGenUiHelper.isLikelyImagePrompt(cleanFallback) ||
            com.example.gemini.ui.components.ImageGenUiHelper.isLikelyImagePrompt(conv.title)
        val prev = AuraAgentEventBus.activeProject.value
        val sameSession = prev.sessionId == conv.id

        // Do not promote an inherited default workspace (e.g. flipkart-clone) into active project
        // context for a fresh New Chat, casual greeting ("hi"), or image-generation chat.
        if (!sameSession) {
            if (isImageRequest) {
                AuraAgentEventBus.clearActiveProject()
                return
            }
            val titleIsCasual = !isGenericTitle && com.example.gemini.data.context.GlobalContext.isCasualNonProjectText(conv.title)
            val fallbackHasSignal = com.example.gemini.data.context.GlobalContext.hasExplicitProjectSignal(cleanFallback, wsName)
            val titleHasSignal = !isGenericTitle && !titleIsCasual &&
                com.example.gemini.data.context.GlobalContext.hasExplicitProjectSignal(conv.title, wsName)
            val isFreshUnbound = isGenericTitle && !hasStoredSummary && !fallbackHasSignal
            if (isFreshUnbound || (titleIsCasual && !hasStoredSummary && !fallbackHasSignal) ||
                (cleanFallback.isNotBlank() && !hasStoredSummary && !fallbackHasSignal && !titleHasSignal)
            ) {
                AuraAgentEventBus.clearActiveProjectIfDifferentSession(conv.id)
                return
            }
        }

        val inferredName = wsName.ifBlank { if (!isGenericTitle) conv.title else "" }
        val summaryText = conv.summary?.takeIf { it.isNotBlank() }
            ?: cleanFallback.takeIf { !com.example.gemini.data.context.GlobalContext.isCasualNonProjectText(it) }?.take(280).orEmpty()
        if (inferredName.isNotBlank() || wsPath.isNotBlank() || summaryText.isNotBlank()) {
            AuraAgentEventBus.updateActiveProject { current ->
                val isSame = current.sessionId == conv.id
                if (isSame) {
                    current.copy(
                        projectName = current.projectName.ifBlank { inferredName },
                        workspaceDir = wsPath.ifBlank { current.workspaceDir },
                        requirementsSummary = summaryText.ifBlank { current.requirementsSummary }
                    )
                } else {
                    ActiveProjectContext(
                        sessionId = conv.id,
                        projectName = inferredName,
                        workspaceDir = wsPath,
                        requirementsSummary = summaryText
                    )
                }
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

    private val bridgeDbScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPersistedMetaKey: String = ""

    fun persistConversationMetadata(
        sessionId: String,
        title: String,
        projectName: String? = null,
        projectType: String? = null,
        projectPath: String? = null,
        summary: String? = null
    ) {
        val ctx = viewModel?.getApplication<android.app.Application>() ?: return
        val key = "$sessionId|$title|${projectName.orEmpty()}|${projectType.orEmpty()}|${projectPath.orEmpty()}|${summary.orEmpty()}"
        if (key == lastPersistedMetaKey) return
        lastPersistedMetaKey = key
        bridgeDbScope.launch {
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

        // 0. Active Project Context: ONLY inject when recent (<= 20 min) AND relevant to the active project chat
        // or visual design apps (never inject unrelated GTA/Flipkart projects while user is in Chrome quiz/WhatsApp/YouTube).
        val activeProj = AuraAgentEventBus.activeProject.value
        val fgPkg = runCatching {
            com.example.gemini.data.automation.AutomationAccessibilityService.instance?.foregroundPackage.orEmpty()
        }.getOrDefault("").lowercase(Locale.ROOT)
        val isDistractingExternalApp = fgPkg.contains("chrome") ||
            fgPkg.contains("whatsapp") ||
            fgPkg.contains("youtube") ||
            fgPkg.contains("instagram")
        val matchesCurrentConv = conv != null && activeProj.sessionId.isNotBlank() && activeProj.sessionId == conv.id
        if (activeProj.isRecentlyActive(20 * 60_000L) && (matchesCurrentConv || !isDistractingExternalApp)) {
            val projectBlock = activeProj.toPromptBlock()
            if (projectBlock.isNotBlank()) {
                sb.append("\n").append(projectBlock)
            }
        }

        sb.append("\n## Chat Memory Tools:\n")
        sb.append("- Do NOT invent or assume unrelated past projects/chats. If the user explicitly asks about an older chat or past project, call `list_recent_chats()` or `open_chat_by_name()`.\n")

        // 1. Active Chat Context on Screen (filtered so stale/tool/hallucinated lines never loop back)
        if (conv != null || msgs.isNotEmpty()) {
            val title = conv?.title?.takeIf { it.isNotBlank() } ?: "Untitled Chat"
            _lastActiveChatTitle.value = title

            sb.append("\n## Current Conversation Context:\n")
            sb.append("Conversation title: \"$title\".\n")

            val recent = msgs
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .filter { !HindiAsrNormalizer.isNoiseOrForeignHallucination(it.content) }
                .takeLast(MAX_MESSAGES_IN_CONTEXT)
            if (recent.isEmpty()) {
                sb.append("(Fresh conversation.)\n")
            } else {
                sb.append("Recent turns:\n")
                recent.forEach { m ->
                    val roleName = if (m.role == MessageRole.USER) "User" else "Assistant"
                    val clean = m.content.trim()
                    val text = if (clean.length > 320) clean.take(320) + "... [truncated]" else clean
                    sb.append("$roleName: $text\n")
                }
            }
            sb.append("\n")
        }

        val activePending = findActivePendingQuestion(vm)
        if (activePending?.toolCall?.questionnaire != null) {
            sb.append("\n## ACTIVE INTERACTIVE QUESTIONNAIRE CARD ON SCREEN (DO NOT CREATE A NEW CHAT!):\n")
            sb.append(ChatQuestionnaireHelper.formatPendingQuestionnairePrompt(activePending.toolCall.questionnaire))
            sb.append("\n\n")
        }

        return sb.toString()
    }

    fun createNewChat(): String = startNewChat()

    fun startNewChat(): String {
        val vm = viewModel ?: return "Could not access chat system."
        return try {
            isNewChatDraft = true
            explicitlySelectedChatId = null
            closeOverlaysForChatNavigation()
            internalNavCommand.value = InternalNavCommand(
                id = navCommandSeq.incrementAndGet(),
                target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
            )
            vm.startNewChat()
            _lastActiveChatTitle.value = "New Chat"
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            "A fresh new chat has been created on screen with welcome suggestion cards."
        } catch (e: Exception) {
            "Failed to start new chat: ${e.message}"
        }
    }

    data class QuestionActionResult(
        val success: Boolean,
        val message: String
    )

    private var lastSentPrompt: String = ""
    private var lastSentTime: Long = 0L
    private val recentTaskRecords = mutableListOf<ChatQuestionnaireHelper.RecentTaskRecord>()

    private fun findActivePendingQuestion(vm: ChatViewModel): com.example.gemini.ui.agents.PendingQuestion? {
        val conv = vm.currentConversation.value
        val direct = com.example.gemini.ui.agents.findPendingQuestion(
            conv?.agent ?: com.example.gemini.domain.model.AgentKind.AGY,
            vm.messages.value
        )
        if (direct != null) return direct
        // Also check AGY backend directly in case active screen was Voice Session
        val agyMsgs = vm.agyBackend.messages.value
        return com.example.gemini.ui.agents.findPendingQuestion(
            com.example.gemini.domain.model.AgentKind.AGY,
            agyMsgs
        )
    }

    fun getActivePendingQuestionnaire(): com.example.gemini.domain.model.ChoiceQuestionnaire? {
        val vm = viewModel ?: return null
        return findActivePendingQuestion(vm)?.toolCall?.questionnaire
    }

    suspend fun answerActiveChatQuestion(
        answer: String,
        action: String = "submit"
    ): QuestionActionResult = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext QuestionActionResult(false, "App chat is not connected.")
        var pq = findActivePendingQuestion(vm)
        if (pq == null) {
            // If active project chat is in another thread, try switching to it once
            val projSessionId = AuraAgentEventBus.activeProject.value.sessionId
            val currentId = vm.currentConversation.value?.id
            if (projSessionId.isNotBlank() && projSessionId != currentId) {
                vm.selectConversation(projSessionId)
                delay(450L)
                pq = findActivePendingQuestion(vm)
            }
        }
        if (pq == null || pq.toolCall.questionnaire == null) {
            return@withContext QuestionActionResult(false, "No pending Questionnaire card is currently open in the active chat.")
        }
        val qn = pq.toolCall.questionnaire!!
        val actLower = action.trim().lowercase(Locale.ROOT)
        if (actLower == "inspect") {
            return@withContext QuestionActionResult(
                true,
                "Active Questionnaire:\n" + ChatQuestionnaireHelper.formatPendingQuestionnairePrompt(qn)
            )
        }

        val convId = vm.currentConversation.value?.id
        if (convId != null) {
            isNewChatDraft = false
            explicitlySelectedChatId = convId
            closeOverlaysForChatNavigation()
            internalNavCommand.value = InternalNavCommand(
                id = navCommandSeq.incrementAndGet(),
                target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
            )
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
        }

        if (actLower == "cancel") {
            vm.cancelUserChoices(pq.toolCall, pq.messageId)
            delay(150L)
            return@withContext QuestionActionResult(true, "Cancelled the questionnaire '${qn.title}'.")
        }

        val built = ChatQuestionnaireHelper.buildSubmission(answer, qn, actLower.ifBlank { "submit" })
        if (!built.success) {
            return@withContext QuestionActionResult(false, built.errorMessage)
        }

        if (built.skipped) {
            vm.skipUserChoices(pq.toolCall, pq.messageId, built.responses)
            delay(150L)
            return@withContext QuestionActionResult(true, "Skipped the questionnaire '${qn.title}' so the agent can proceed.")
        }

        vm.submitUserChoices(pq.toolCall, pq.messageId, built.responses, built.summaryDisplay)
        delay(180L)
        val remaining = findActivePendingQuestion(vm)
        val verifiedCleared = remaining == null || remaining.toolCall.id != pq.toolCall.id
        val labelsStr = built.matchedLabels.joinToString(", ").ifBlank { answer }
        return@withContext if (verifiedCleared) {
            QuestionActionResult(true, "Selected & submitted '$labelsStr' on '${qn.title}'. The agent is continuing now.")
        } else {
            QuestionActionResult(true, "Submitted '$labelsStr' on '${qn.title}'.")
        }
    }

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
        recentTaskRecords.removeAll { (now - it.createdAtMs) > 300_000L }

        val activePending = findActivePendingQuestion(vm)
        when (val decision = ChatQuestionnaireHelper.evaluateTaskCreation(
            cleanPrompt = cleanPrompt,
            activeQuestionnaire = activePending?.toolCall?.questionnaire,
            recentTasks = recentTaskRecords,
            nowMs = now
        )) {
            is ChatQuestionnaireHelper.TaskCreationDecision.AnswerPendingQuestion -> {
                Log.i(TAG, "Intercepted startNewChatTask -> answering active Questionnaire instead: $cleanPrompt")
                return@withContext answerActiveChatQuestion(decision.rawAnswer, "submit").message
            }
            is ChatQuestionnaireHelper.TaskCreationDecision.ReuseExistingTask -> {
                Log.w(TAG, "Duplicate chat task blocked (${decision.reason}): reusing session ${decision.record.sessionId}")
                openChatById(decision.record.sessionId)
                return@withContext if (activePending?.toolCall?.questionnaire != null) {
                    "Ye task pehle se active chat mein chal raha hai aur ek Questionnaire waiting par hai:\n" +
                        ChatQuestionnaireHelper.formatPendingQuestionnairePrompt(activePending.toolCall.questionnaire)
                } else {
                    "Ye task pehle se active chat mein chal raha hai (duplicate chat nahi banayi). Aap 'status kya hai' poochh sakte hain ya 'send_task_update' use kar sakte hain."
                }
            }
            ChatQuestionnaireHelper.TaskCreationDecision.CreateNewTask -> Unit
        }

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
            internalNavCommand.value = InternalNavCommand(
                id = navCommandSeq.incrementAndGet(),
                target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
            )
            AuraAgentEventBus.resetQuestionDedupe()
            vm.startNewChat()
            explicitlySelectedChatId = vm.currentConversation.value?.id ?: "__NEW_CHAT_TASK__"
            requestViewMode("CHAT")
            requestSwitchToChatTab.value = true
            _lastActiveChatTitle.value = "New Chat"

            delay(350L) // Allow new conversation session to bind

            val conv = vm.currentConversation.value
            if (conv != null) {
                explicitlySelectedChatId = conv.id
                recentTaskRecords += ChatQuestionnaireHelper.RecentTaskRecord(
                    sessionId = conv.id,
                    prompt = cleanPrompt,
                    tokens = ChatQuestionnaireHelper.tokenizeTaskIntent(cleanPrompt),
                    createdAtMs = now
                )
                while (recentTaskRecords.size > 20) recentTaskRecords.removeAt(0)
            }
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

            // 2. Dispatch the exact clean user prompt directly without clearing duplicate-guard timestamps
            vm.sendMessage(cleanPrompt)

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
                internalNavCommand.value = InternalNavCommand(
                    id = navCommandSeq.incrementAndGet(),
                    target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
                )
                AuraAgentEventBus.resetQuestionDedupe()
                vm.startNewChat()
                explicitlySelectedChatId = vm.currentConversation.value?.id ?: "__NEW_CHAT_TASK__"
                requestViewMode("CHAT")
                requestSwitchToChatTab.value = true
                _lastActiveChatTitle.value = "New Chat"
                if (autoSend) {
                    delay(350L)
                    vm.currentConversation.value?.id?.let { explicitlySelectedChatId = it }
                    vm.sendMessage(text)
                    clearPromptCache()
                    "Message bhej diya gaya hai."
                } else {
                    vm.currentConversation.value?.id?.let { explicitlySelectedChatId = it }
                    "Nayi chat ready hai."
                }
            }
            "CURRENT", "ACTIVE", "VOICE", "" -> {
                val activePending = findActivePendingQuestion(vm)
                if (activePending?.toolCall?.questionnaire != null &&
                    autoSend &&
                    ChatQuestionnaireHelper.isQuestionnaireClickPhrase(text, text, activePending.toolCall.questionnaire)
                ) {
                    return@withContext answerActiveChatQuestion(text, "submit").message
                }
                isNewChatDraft = false
                closeOverlaysForChatNavigation()
                internalNavCommand.value = InternalNavCommand(
                    id = navCommandSeq.incrementAndGet(),
                    target = AppNavigationRouter.NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
                )
                ensureConversationExists(vm)
                explicitlySelectedChatId = vm.currentConversation.value?.id
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
                val switchRes = openChatByName(target)
                if (switchRes.success) {
                    if (autoSend) {
                        delay(150L)
                        vm.sendMessage(text)
                        clearPromptCache()
                        "Chat '${switchRes.matchedTitle ?: target}' open karke message bhej diya hai."
                    } else {
                        switchRes.message
                    }
                } else {
                    switchRes.message
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
    suspend fun openChatById(chatId: String): ChatSwitchResult {
        val vm = viewModel ?: return ChatSwitchResult(false, "Chat system is not available.")
        val query = chatId.trim()
        if (query.isBlank()) return ChatSwitchResult(false, "Please specify a chat ID to open.")

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

        if (matched == null) {
            return ChatSwitchResult(false, "Could not find a chat matching '$chatId'.")
        }

        val pinned = runCatching {
            com.example.gemini.data.preferences.AuraCustomizePreferences
                .getPinnedConversationIds(vm.getApplication())
                .contains(matched.id)
        }.getOrDefault(false)
        return navigateToChatAndVerify(vm, matched, pinned)
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

        // 3. Check Chat Stream State & Pending Questionnaire
        val vm = viewModel
        if (vm != null) {
            val activePending = findActivePendingQuestion(vm)
            if (activePending?.toolCall?.questionnaire != null) {
                sb.append("Active Chat Questionnaire waiting for user answer:\n")
                sb.append(ChatQuestionnaireHelper.formatPendingQuestionnairePrompt(activePending.toolCall.questionnaire))
                sb.append("\n")
            }
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

        // In-Built Browser live state
        runCatching {
            val bm = com.example.gemini.ui.browser.BrowserSessionManager.instance
            val activeTab = bm.getActiveTab()
            val isOpen = isInBuiltBrowserActive()
            sb.append("In-Built Browser View: ${if (isOpen) "OPEN (on screen)" else "HIDDEN"}\n")
            if (activeTab != null) {
                val curUrl = activeTab.webView?.url ?: activeTab.url
                sb.append("Active Browser Tab [${activeTab.id}]: \"${activeTab.title}\" | URL=${curUrl.ifBlank { "New Tab" }} | loading=${activeTab.isLoading} | totalTabs=${bm.tabs.size}\n")
            }
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
