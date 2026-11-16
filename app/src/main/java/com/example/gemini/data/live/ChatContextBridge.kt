package com.example.gemini.data.live

import android.util.Log
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ChatContextBridge: Connects active text chat session to Gemini Live Voice session.
 * Allows Gemini Live to:
 * 1. Understand recent conversation messages and code discussed on screen.
 * 2. Start a new clean chat on voice command ("New chat se continue karte hain").
 * 3. Send text or results directly to the active chat screen so the user can read code/output.
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

    /**
     * Extracts active conversation context for Gemini Live's system prompt.
     */
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
                }
                // Truncate very long code snippets to keep context focused
                val text = if (m.content.length > 500) m.content.take(500) + "... [truncated]" else m.content
                sb.append("$roleName: $text\n")
            }
        }
        sb.append("Instructions: If the user asks about 'this', 'the code', 'the error', or previous discussion, use this context immediately!\n\n")
        return sb.toString()
    }

    /**
     * Creates a new chat on voice command.
     */
    fun startNewChat(): String {
        val vm = viewModel ?: return "Could not access chat system."
        try {
            vm.startNewChat()
            _lastActiveChatTitle.value = "New Chat"
            Log.i(TAG, "Started new chat from voice command")
            return "New chat session created on screen."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start new chat: ${e.message}", e)
            return "Failed to start new chat: ${e.message}"
        }
    }

    /**
     * Sends a message into the active chat so it shows on screen.
     */
    fun sendTextToChat(text: String) {
        val vm = viewModel ?: return
        try {
            vm.sendMessage(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text to chat: ${e.message}", e)
        }
    }
}
