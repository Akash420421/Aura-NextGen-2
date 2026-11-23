package com.example.gemini.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import kotlinx.coroutines.withContext

/**
 * ChatDatabase: Android SQLite single-source-of-truth database for Aura chat & voice sessions.
 * Persists session metadata and full conversation history across app restarts.
 */
class ChatDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "ChatDatabase"
        private const val DATABASE_NAME = "aura_chat.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_SESSIONS = "sessions"
        private const val COL_SESSION_ID = "id"
        private const val COL_SESSION_TITLE = "title"
        private const val COL_SESSION_TYPE = "session_type"
        private const val COL_SESSION_CREATED_AT = "created_at"
        private const val COL_SESSION_UPDATED_AT = "updated_at"
        private const val COL_SESSION_ARCHIVED = "archived"

        private const val TABLE_MESSAGES = "messages"
        private const val COL_MSG_ID = "id"
        private const val COL_MSG_CONV_ID = "conversation_id"
        private const val COL_MSG_ROLE = "role"
        private const val COL_MSG_CONTENT = "content"
        private const val COL_MSG_VIA_VOICE = "via_voice"
        private const val COL_MSG_CREATED_AT = "created_at"
        private const val COL_MSG_UPDATED_AT = "updated_at"

        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return instance ?: synchronized(this) {
                instance ?: ChatDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessions = MutableStateFlow<List<Conversation>>(emptyList())
    val sessions: StateFlow<List<Conversation>> = _sessions.asStateFlow()

    init {
        reloadSessions()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_SESSION_TITLE TEXT NOT NULL,
                $COL_SESSION_TYPE TEXT NOT NULL DEFAULT 'VOICE',
                $COL_SESSION_CREATED_AT INTEGER NOT NULL,
                $COL_SESSION_UPDATED_AT INTEGER NOT NULL,
                $COL_SESSION_ARCHIVED INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_CONV_ID TEXT NOT NULL,
                $COL_MSG_ROLE TEXT NOT NULL,
                $COL_MSG_CONTENT TEXT NOT NULL,
                $COL_MSG_VIA_VOICE INTEGER NOT NULL DEFAULT 1,
                $COL_MSG_CREATED_AT INTEGER NOT NULL,
                $COL_MSG_UPDATED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv_id ON $TABLE_MESSAGES($COL_MSG_CONV_ID);")
        Log.i(TAG, "ChatDatabase initialized schema successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations if needed
    }

    fun reloadSessions() {
        dbScope.launch {
            val list = queryAllSessionsInternal()
            _sessions.value = list
        }
    }

    suspend fun getAllSessions(): List<Conversation> = withContext(Dispatchers.IO) {
        queryAllSessionsInternal()
    }

    private fun queryAllSessionsInternal(): List<Conversation> {
        val list = mutableListOf<Conversation>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_SESSION_UPDATED_AT DESC"
        )
        cursor.use { c ->
            val idIdx = c.getColumnIndex(COL_SESSION_ID)
            val titleIdx = c.getColumnIndex(COL_SESSION_TITLE)
            val createdIdx = c.getColumnIndex(COL_SESSION_CREATED_AT)
            val updatedIdx = c.getColumnIndex(COL_SESSION_UPDATED_AT)
            val archivedIdx = c.getColumnIndex(COL_SESSION_ARCHIVED)

            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val title = c.getString(titleIdx)
                val createdAt = c.getLong(createdIdx)
                val updatedAt = c.getLong(updatedIdx)
                val archived = c.getInt(archivedIdx) == 1

                list.add(
                    Conversation(
                        id = id,
                        title = title,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        archived = archived
                    )
                )
            }
        }
        return list
    }

    suspend fun hasSession(id: String): Boolean = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT 1 FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID = ? LIMIT 1", arrayOf(id))
        cursor.use { it.moveToFirst() }
    }

    suspend fun getSession(id: String): Conversation? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_ID = ?",
            arrayOf(id),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val title = c.getString(c.getColumnIndexOrThrow(COL_SESSION_TITLE))
                val createdAt = c.getLong(c.getColumnIndexOrThrow(COL_SESSION_CREATED_AT))
                val updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_SESSION_UPDATED_AT))
                val archived = c.getInt(c.getColumnIndexOrThrow(COL_SESSION_ARCHIVED)) == 1
                return@withContext Conversation(
                    id = id,
                    title = title,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    archived = archived
                )
            }
        }
        null
    }

    suspend fun upsertSession(conv: Conversation, sessionType: String = "VOICE") = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_ID, conv.id)
            put(COL_SESSION_TITLE, conv.title)
            put(COL_SESSION_TYPE, sessionType)
            put(COL_SESSION_CREATED_AT, conv.createdAt)
            put(COL_SESSION_UPDATED_AT, conv.updatedAt)
            put(COL_SESSION_ARCHIVED, if (conv.archived) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        reloadSessions()
    }

    suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_UPDATED_AT, timestamp)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_ID = ?", arrayOf(sessionId))
        reloadSessions()
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "$COL_MSG_CONV_ID = ?", arrayOf(sessionId))
        db.delete(TABLE_SESSIONS, "$COL_SESSION_ID = ?", arrayOf(sessionId))
        reloadSessions()
    }

    suspend fun getMessages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COL_MSG_CONV_ID = ?",
            arrayOf(conversationId),
            null,
            null,
            "$COL_MSG_CREATED_AT ASC"
        )
        cursor.use { c ->
            val idIdx = c.getColumnIndex(COL_MSG_ID)
            val convIdIdx = c.getColumnIndex(COL_MSG_CONV_ID)
            val roleIdx = c.getColumnIndex(COL_MSG_ROLE)
            val contentIdx = c.getColumnIndex(COL_MSG_CONTENT)
            val viaVoiceIdx = c.getColumnIndex(COL_MSG_VIA_VOICE)
            val createdIdx = c.getColumnIndex(COL_MSG_CREATED_AT)

            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val convId = c.getString(convIdIdx)
                val roleStr = c.getString(roleIdx)
                val content = c.getString(contentIdx)
                val viaVoice = c.getInt(viaVoiceIdx) == 1
                val createdAt = c.getLong(createdIdx)

                val role = if (roleStr.equals("user", ignoreCase = true)) MessageRole.USER else MessageRole.ASSISTANT
                list.add(
                    ChatMessage(
                        id = id,
                        conversationId = convId,
                        role = role,
                        content = content,
                        viaVoice = viaVoice,
                        createdAt = createdAt
                    )
                )
            }
        }
        list
    }

    suspend fun upsertMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        if (message.content.isBlank()) return@withContext
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_MSG_CONV_ID, message.conversationId)
            put(COL_MSG_ROLE, if (message.role == MessageRole.USER) "user" else "assistant")
            put(COL_MSG_CONTENT, message.content)
            put(COL_MSG_VIA_VOICE, if (message.viaVoice) 1 else 0)
            put(COL_MSG_CREATED_AT, message.createdAt)
            put(COL_MSG_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        // Keep session updated timestamp fresh so it floats to the top of drawer
        val sessionValues = ContentValues().apply {
            put(COL_SESSION_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_SESSIONS, sessionValues, "$COL_SESSION_ID = ?", arrayOf(message.conversationId))
        reloadSessions()
    }
}
