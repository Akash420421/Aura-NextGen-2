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

    private var viewModel: ChatViewModel? = null

    private val _lastActiveChatTitle = MutableStateFlow("")
    val lastActiveChatTitle: StateFlow<String> = _lastActiveChatTitle.asStateFlow()

    var activeVoiceConvId: String? = null
    val requestSwitchToChatTab = MutableStateFlow(false)

    fun attach(vm: ChatViewModel) {
        viewModel = vm
        try {
            VoiceSessionStore.init(vm.getApplication())
        } catch (_: Exception) {}
        Log.i(TAG, "Attached ChatViewModel to ChatContextBridge")
    }

    fun startVoiceSessionConversation(): String {
        val vm = viewModel
        val conv = VoiceSessionStore.createSession()
        activeVoiceConvId = conv.id
        if (vm != null) {
            try {
                val prop = vm.javaClass.getDeclaredField("store")
                prop.isAccessible = true
                val store = prop.get(vm) as? com.example.gemini.data.agent.ChatSessionStore
                store?.let {
                    it.conversations.value = listOf(conv) + it.conversations.value.filter { c -> c.id != conv.id }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update memory store for voice session: ${e.message}")
            }
        }
        return conv.id
    }

    fun endVoiceSessionConversation() {
        // Voice session is permanently retained in VoiceSessionStore
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

    fun openCurrentVoiceChat(): String {
        val vm = viewModel ?: return "Chat system is not available."
        val convId = activeVoiceConvId
        return if (convId != null) {
            vm.selectConversation(convId)
            requestSwitchToChatTab.value = true
            "Switched to current voice chat on screen."
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
            vm.selectConversation(prev.id)
            requestSwitchToChatTab.value = true
            "Opened previous voice conversation '${prev.title}' on screen."
        } else {
            "No previous voice conversations found."
        }
    }

    fun getCurrentConversation(): Conversation? = viewModel?.currentConversation?.value

    fun getCurrentMessages(): List<ChatMessage> = viewModel?.messages?.value ?: emptyList()

    fun getActiveChatPromptContext(): String {
        val vm = viewModel ?: return ""
        val conv = vm.currentConversation.value
        val msgs = vm.messages.value

        if (conv == null && msgs.isEmpty()) return ""

        val title = conv?.title?.takeIf { it.isNotBlank() } ?: "Untitled Chat"
        _lastActiveChatTitle.value = title

        val sb = StringBuilder("\n## Active Chat Context on Screen:\n")
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
        sb.append("Instructions: If the user asks about previous discussion, use this context immediately!\n\n")
        return sb.toString()
    }

    fun startNewChat(): String {
        val vm = viewModel ?: return "Could not access chat system."
        return try {
            vm.startNewChat()
            _lastActiveChatTitle.value = "New Chat"
            "New chat session created on screen."
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
     * Instructs the current chat agent to create a new chat and build the project/task there.
     * Includes anti-duplicate guard to prevent prompt looping or queued message flooding.
     */
    suspend fun startNewChatTask(prompt: String): String = withContext(Dispatchers.Main) {
        val vm = viewModel ?: return@withContext "Error: App chat is not connected. Open the chat screen first."
        if (prompt.isBlank()) return@withContext "Error: Task prompt cannot be empty."

        val lowerPrompt = prompt.lowercase()
        val capabilityPhrases = listOf(
            "tum kya kya kar sakte ho", "kya kar sakte ho", "capabilities", "what can you do", "help me understand"
        )
        if (capabilityPhrases.any { lowerPrompt.contains(it) }) {
            Log.w(TAG, "Blocked capability query from executing as code task: $prompt")
            return@withContext "Ye ek question hai, koi coding task nahi. Kripya aawaz me natural jawab dein."
        }

        val now = System.currentTimeMillis()
        if (prompt.trim() == lastSentPrompt.trim() && (now - lastSentTime < 25_000L)) {
            Log.w(TAG, "Duplicate prompt blocked by guard: $prompt")
            return@withContext "Ye task already app me chal raha hai aur agent kaam kar raha hai. Aap 'status kya hai' poochh sakte hain."
        }

        // Guard against queue storm: if agent is currently streaming, don't spam sendMessage into queue
        if (vm.isStreaming.value) {
            Log.w(TAG, "Agent is currently streaming, blocking duplicate task prompt from queueing")
            return@withContext "Agent abhi ek task pe actively kaam kar raha hai. Kripya thoda wait karein ya 'status kya hai' poochhein."
        }

        lastSentPrompt = prompt.trim()
        lastSentTime = now

        try {
            ensureConversationExists(vm)
            val initialMsgCount = vm.messages.value.size

            // Instruct the current chat to create a new chat and execute the task in that new chat
            val formatted = "Ek new chat create karo aur usme ye website/task execute karo:\n\n$prompt"
            Log.i(TAG, "Sending new chat task prompt to app chat: $formatted")

            vm.sendMessage(formatted)

            // Wait up to 8s for initial acknowledgment from the agent
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 8000L) {
                val currentMsgs = vm.messages.value
                val lastMsg = currentMsgs.lastOrNull()
                if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.content.isNotBlank() && currentMsgs.size > initialMsgCount) {
                    return@withContext lastMsg.content
                }
                delay(200L)
            }

            return@withContext "Maine new chat create karke task start karne ka instruction bhej diya hai aur agent ne kaam shuru kar diya hai."
        } catch (e: Exception) {
            Log.e(TAG, "Error starting new chat task: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Checks real-time progress of active background tasks, websites, and apps.
     * Reads JarvisTasks, checks localhost web servers (8080, 3000, 5173), and checks chat stream.
     * Zero screen share required.
     */
    suspend fun checkActiveTaskStatus(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // 1. Check Jarvis background tasks
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

        // 2. Check Localhost Servers
        val commonPorts = listOf(8080, 3000, 5173, 8000)
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
                sb.append("Agent is actively writing and generating code right now.\n")
                if (lastMsg != null) {
                    sb.append("Latest text: ${lastMsg.content.takeLast(180)}\n")
                }
            } else if (lastMsg != null && sb.isEmpty()) {
                sb.append("Latest chat output: ${lastMsg.content.take(250)}\n")
            }
        }

        if (sb.isEmpty()) {
            return@withContext "No active tasks or background builds are running currently."
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
            val updateMsg = "Jo website/task banayi ja rahi hai, usme ye update karo: $instruction"
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
        if (convId != null) {
            val msg = ChatMessage(
                conversationId = convId,
                role = MessageRole.TOOL,
                content = "⚡ [$toolName]: $summary",
                viaVoice = true
            )
            VoiceSessionStore.appendMessage(convId, msg)
        }
        val vm = viewModel ?: return
        try {
            ensureConversationExists(vm)
            val currentId = vm.currentConversation.value?.id ?: convId ?: UUID.randomUUID().toString()
            val msg = ChatMessage(
                conversationId = currentId,
                role = MessageRole.TOOL,
                content = "⚡ [$toolName]: $summary",
                viaVoice = true
            )
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
                it.messages.value = it.messages.value + msg
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
