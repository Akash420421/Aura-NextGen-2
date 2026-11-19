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

    fun attach(vm: ChatViewModel) {
        viewModel = vm
        Log.i(TAG, "Attached ChatViewModel to ChatContextBridge")
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
        val vm = viewModel ?: return
        try {
            ensureConversationExists(vm)
            val convId = vm.currentConversation.value?.id ?: UUID.randomUUID().toString()
            val msg = ChatMessage(
                conversationId = convId,
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
