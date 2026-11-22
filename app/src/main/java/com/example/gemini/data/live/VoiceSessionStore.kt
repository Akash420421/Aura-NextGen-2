package com.example.gemini.data.live

import android.content.Context
import android.util.Log
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * High-durability local persistence store for Aura Live Voice sessions.
 * Saves full conversation turn histories (User speech, Assistant speech, and Tool executions)
 * to persistent JSON files in app storage.
 * Sessions appear in Drawer -> Voice, survive app restarts, and open cleanly in ChatScreen.
 */
@Serializable
data class PersistedVoiceSession(
    val conversation: Conversation,
    val messages: List<ChatMessage> = emptyList()
)

object VoiceSessionStore {
    private const val TAG = "VoiceSessionStore"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var storageDir: File? = null
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val activeSessions = mutableMapOf<String, PersistedVoiceSession>()

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "voice_sessions")
        if (!dir.exists()) dir.mkdirs()
        storageDir = dir
        loadAllSessions()
    }

    private fun loadAllSessions() {
        scope.launch {
            val dir = storageDir ?: return@launch
            val files = dir.listFiles { f -> f.extension == "json" } ?: return@launch
            val list = mutableListOf<Conversation>()
            synchronized(activeSessions) {
                activeSessions.clear()
                for (file in files) {
                    try {
                        val content = file.readText()
                        val session = json.decodeFromString<PersistedVoiceSession>(content)
                        activeSessions[session.conversation.id] = session
                        list.add(session.conversation)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load voice session from ${file.name}: ${e.message}")
                    }
                }
            }
            _conversations.value = list.sortedByDescending { it.updatedAt }
            Log.i(TAG, "Loaded ${_conversations.value.size} persisted voice sessions.")
        }
    }

    fun createSession(title: String? = null): Conversation {
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val convId = UUID.randomUUID().toString()
        val conv = Conversation(
            id = convId,
            title = title ?: "Voice Session • $timeStr",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val session = PersistedVoiceSession(conversation = conv, messages = emptyList())
        synchronized(activeSessions) {
            activeSessions[convId] = session
        }
        _conversations.value = listOf(conv) + _conversations.value.filter { it.id != convId }
        saveSessionToDisk(session)
        return conv
    }

    fun hasSession(id: String): Boolean {
        synchronized(activeSessions) {
            return activeSessions.containsKey(id)
        }
    }

    fun getSession(id: String): PersistedVoiceSession? {
        synchronized(activeSessions) {
            return activeSessions[id]
        }
    }

    fun appendMessage(conversationId: String, message: ChatMessage) {
        val updatedSession: PersistedVoiceSession
        synchronized(activeSessions) {
            val existing = activeSessions[conversationId]
            if (existing == null) {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                val conv = Conversation(id = conversationId, title = "Voice Session • $timeStr")
                val session = PersistedVoiceSession(conv, listOf(message))
                activeSessions[conversationId] = session
                updatedSession = session
            } else {
                val updatedConv = existing.conversation.copy(updatedAt = System.currentTimeMillis())
                val session = PersistedVoiceSession(updatedConv, existing.messages + message)
                activeSessions[conversationId] = session
                updatedSession = session
            }
        }
        _conversations.value = listOf(updatedSession.conversation) + _conversations.value.filter { it.id != conversationId }
        saveSessionToDisk(updatedSession)
    }

    fun appendAssistantText(conversationId: String, textChunk: String) {
        val updatedSession: PersistedVoiceSession
        synchronized(activeSessions) {
            val existing = activeSessions[conversationId]
            if (existing == null) {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                val conv = Conversation(id = conversationId, title = "Voice Session • $timeStr")
                val msg = ChatMessage(conversationId = conversationId, role = MessageRole.ASSISTANT, content = textChunk, viaVoice = true)
                val session = PersistedVoiceSession(conv, listOf(msg))
                activeSessions[conversationId] = session
                updatedSession = session
            } else {
                val msgs = existing.messages.toMutableList()
                val last = msgs.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.viaVoice) {
                    msgs[msgs.size - 1] = last.copy(content = last.content + textChunk)
                } else {
                    msgs.add(ChatMessage(conversationId = conversationId, role = MessageRole.ASSISTANT, content = textChunk, viaVoice = true))
                }
                val updatedConv = existing.conversation.copy(updatedAt = System.currentTimeMillis())
                val session = PersistedVoiceSession(updatedConv, msgs)
                activeSessions[conversationId] = session
                updatedSession = session
            }
        }
        saveSessionToDisk(updatedSession)
    }

    fun deleteSession(id: String) {
        synchronized(activeSessions) {
            activeSessions.remove(id)
        }
        _conversations.value = _conversations.value.filter { it.id != id }
        scope.launch {
            val dir = storageDir ?: return@launch
            File(dir, "$id.json").delete()
        }
    }

    private fun saveSessionToDisk(session: PersistedVoiceSession) {
        scope.launch {
            val dir = storageDir ?: return@launch
            try {
                val file = File(dir, "${session.conversation.id}.json")
                file.writeText(json.encodeToString(session))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist voice session: ${e.message}")
            }
        }
    }
}
