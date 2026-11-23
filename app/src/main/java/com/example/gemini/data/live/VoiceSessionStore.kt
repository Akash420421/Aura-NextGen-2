package com.example.gemini.data.live

import android.content.Context
import android.util.Log
import com.example.gemini.data.database.ChatDatabase
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * Backed by ChatDatabase (Room/SQLite) and JSON file backups.
 * All user and assistant speech turns are saved permanently in real time.
 * Sessions appear in Drawer -> Voice, survive app restarts, and sync live to ChatScreen.
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
    private var chatDb: ChatDatabase? = null
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val activeSessions = mutableMapOf<String, PersistedVoiceSession>()

    fun init(context: Context) {
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, "voice_sessions")
        if (!dir.exists()) dir.mkdirs()
        storageDir = dir

        val db = ChatDatabase.getInstance(appContext)
        chatDb = db

        // Observe reactive database sessions
        scope.launch {
            db.sessions.collectLatest { dbList ->
                val voiceOnly = dbList.filter {
                    it.title.startsWith("Voice Session", ignoreCase = true) ||
                    it.title.contains("Voice", ignoreCase = true)
                }
                _conversations.value = voiceOnly.sortedByDescending { it.updatedAt }
            }
        }

        // Migrate legacy JSON files into SQLite database if missing
        scope.launch {
            migrateLegacyJsonFiles(db, dir)
        }
    }

    private suspend fun migrateLegacyJsonFiles(db: ChatDatabase, dir: File) {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        for (file in files) {
            try {
                val content = file.readText()
                val session = json.decodeFromString<PersistedVoiceSession>(content)
                val conv = session.conversation
                synchronized(activeSessions) {
                    if (!activeSessions.containsKey(conv.id)) {
                        activeSessions[conv.id] = session
                    }
                }
                if (!db.hasSession(conv.id)) {
                    db.upsertSession(conv, "VOICE")
                    for (msg in session.messages) {
                        db.upsertMessage(msg)
                    }
                    Log.i(TAG, "Migrated voice session ${conv.id} to SQLite ChatDatabase.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed migration for ${file.name}: ${e.message}")
            }
        }
    }

    fun createSession(title: String? = null, reuseActiveId: String? = null): Conversation {
        if (!reuseActiveId.isNullOrBlank()) {
            synchronized(activeSessions) {
                activeSessions[reuseActiveId]?.let { return it.conversation }
            }
        }

        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val convId = reuseActiveId ?: UUID.randomUUID().toString()
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

        scope.launch {
            chatDb?.upsertSession(conv, "VOICE")
            saveSessionToDisk(session)
        }
        return conv
    }

    fun hasSession(id: String): Boolean {
        synchronized(activeSessions) {
            if (activeSessions.containsKey(id)) return true
        }
        return _conversations.value.any { it.id == id }
    }

    fun getSession(id: String): PersistedVoiceSession? {
        synchronized(activeSessions) {
            activeSessions[id]?.let { return it }
        }
        // If not in memory, query SQLite synchronously or launch cache load
        val db = chatDb ?: return null
        return try {
            val conv = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.getSession(id)
            } ?: return null
            val msgs = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.getMessages(id)
            }
            val session = PersistedVoiceSession(conversation = conv, messages = msgs)
            synchronized(activeSessions) {
                activeSessions[id] = session
            }
            session
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching session $id from database: ${e.message}")
            null
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

        scope.launch {
            chatDb?.upsertMessage(message)
            saveSessionToDisk(updatedSession)
        }
    }

    fun appendAssistantText(conversationId: String, textChunk: String) {
        if (textChunk.isEmpty()) return
        val updatedSession: PersistedVoiceSession
        val targetMessage: ChatMessage
        synchronized(activeSessions) {
            val existing = activeSessions[conversationId]
            if (existing == null) {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                val conv = Conversation(id = conversationId, title = "Voice Session • $timeStr")
                val msg = ChatMessage(conversationId = conversationId, role = MessageRole.ASSISTANT, content = textChunk, viaVoice = true)
                val session = PersistedVoiceSession(conv, listOf(msg))
                activeSessions[conversationId] = session
                updatedSession = session
                targetMessage = msg
            } else {
                val msgs = existing.messages.toMutableList()
                val last = msgs.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.viaVoice) {
                    val updated = last.copy(content = last.content + textChunk)
                    msgs[msgs.size - 1] = updated
                    targetMessage = updated
                } else {
                    val newMsg = ChatMessage(conversationId = conversationId, role = MessageRole.ASSISTANT, content = textChunk, viaVoice = true)
                    msgs.add(newMsg)
                    targetMessage = newMsg
                }
                val updatedConv = existing.conversation.copy(updatedAt = System.currentTimeMillis())
                val session = PersistedVoiceSession(updatedConv, msgs)
                activeSessions[conversationId] = session
                updatedSession = session
            }
        }
        _conversations.value = listOf(updatedSession.conversation) + _conversations.value.filter { it.id != conversationId }

        scope.launch {
            chatDb?.upsertMessage(targetMessage)
            saveSessionToDisk(updatedSession)
        }
    }

    fun deleteSession(id: String) {
        synchronized(activeSessions) {
            activeSessions.remove(id)
        }
        _conversations.value = _conversations.value.filter { it.id != id }
        scope.launch {
            chatDb?.deleteSession(id)
            val dir = storageDir ?: return@launch
            File(dir, "$id.json").delete()
        }
    }

    private fun saveSessionToDisk(session: PersistedVoiceSession) {
        val dir = storageDir ?: return
        try {
            val file = File(dir, "${session.conversation.id}.json")
            file.writeText(json.encodeToString(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist voice session to disk: ${e.message}")
        }
    }
}
