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
        private const val DATABASE_VERSION = 2

        private const val TABLE_SESSIONS = "sessions"
        private const val COL_SESSION_ID = "id"
        private const val COL_SESSION_TITLE = "title"
        private const val COL_SESSION_TYPE = "session_type"
        private const val COL_SESSION_CREATED_AT = "created_at"
        private const val COL_SESSION_UPDATED_AT = "updated_at"
        private const val COL_SESSION_ARCHIVED = "archived"
        private const val COL_SESSION_PROJECT_NAME = "project_name"
        private const val COL_SESSION_PROJECT_TYPE = "project_type"
        private const val COL_SESSION_PROJECT_PATH = "project_path"
        private const val COL_SESSION_SUMMARY = "summary"
        private const val COL_SESSION_LAST_ACTIVE_AT = "last_active_at"

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

    data class SessionMetadata(
        val id: String,
        val title: String,
        val sessionType: String = "VOICE",
        val projectName: String = "",
        val projectType: String = "",
        val projectPath: String = "",
        val summary: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val lastActiveAt: Long = updatedAt,
        val archived: Boolean = false
    ) {
        fun toConversation(): Conversation = Conversation(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            archived = archived,
            summary = summary.ifBlank { null },
            workspaceUri = projectPath
        )
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
                $COL_SESSION_ARCHIVED INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_PROJECT_NAME TEXT NOT NULL DEFAULT '',
                $COL_SESSION_PROJECT_TYPE TEXT NOT NULL DEFAULT '',
                $COL_SESSION_PROJECT_PATH TEXT NOT NULL DEFAULT '',
                $COL_SESSION_SUMMARY TEXT NOT NULL DEFAULT '',
                $COL_SESSION_LAST_ACTIVE_AT INTEGER NOT NULL DEFAULT 0
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON $TABLE_SESSIONS($COL_SESSION_LAST_ACTIVE_AT DESC);")
        Log.i(TAG, "ChatDatabase initialized schema v$DATABASE_VERSION successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val migrations = listOf(
                "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_PROJECT_NAME TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_PROJECT_TYPE TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_PROJECT_PATH TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_SUMMARY TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_LAST_ACTIVE_AT INTEGER NOT NULL DEFAULT 0",
                "UPDATE $TABLE_SESSIONS SET $COL_SESSION_LAST_ACTIVE_AT = $COL_SESSION_UPDATED_AT WHERE $COL_SESSION_LAST_ACTIVE_AT = 0",
                "CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON $TABLE_SESSIONS($COL_SESSION_LAST_ACTIVE_AT DESC)"
            )
            for (sql in migrations) {
                runCatching { db.execSQL(sql) }.onFailure { e ->
                    Log.w(TAG, "Migration step skipped/already applied ($sql): ${e.message}")
                }
            }
            Log.i(TAG, "ChatDatabase migrated cleanly from v$oldVersion to v$newVersion.")
        }
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

    private fun readMetadataFromCursor(c: android.database.Cursor): SessionMetadata {
        fun safeString(col: String): String {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getString(idx).orEmpty() else ""
        }
        fun safeLong(col: String, fallback: Long = 0L): Long {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else fallback
        }
        fun safeInt(col: String, fallback: Int = 0): Int {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else fallback
        }
        val updatedAt = safeLong(COL_SESSION_UPDATED_AT, System.currentTimeMillis())
        val lastActiveAt = safeLong(COL_SESSION_LAST_ACTIVE_AT, updatedAt).let { if (it <= 0L) updatedAt else it }
        return SessionMetadata(
            id = safeString(COL_SESSION_ID),
            title = safeString(COL_SESSION_TITLE).ifBlank { "Untitled Chat" },
            sessionType = safeString(COL_SESSION_TYPE).ifBlank { "VOICE" },
            projectName = safeString(COL_SESSION_PROJECT_NAME),
            projectType = safeString(COL_SESSION_PROJECT_TYPE),
            projectPath = safeString(COL_SESSION_PROJECT_PATH),
            summary = safeString(COL_SESSION_SUMMARY),
            createdAt = safeLong(COL_SESSION_CREATED_AT, updatedAt),
            updatedAt = updatedAt,
            lastActiveAt = lastActiveAt,
            archived = safeInt(COL_SESSION_ARCHIVED, 0) == 1
        )
    }

    private fun queryAllSessionsInternal(): List<Conversation> {
        return queryAllMetadataInternal().map { it.toConversation() }
    }

    private fun queryAllMetadataInternal(limit: Int? = null): List<SessionMetadata> {
        val list = mutableListOf<SessionMetadata>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "MAX($COL_SESSION_LAST_ACTIVE_AT, $COL_SESSION_UPDATED_AT) DESC",
            limit?.toString()
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(readMetadataFromCursor(c))
            }
        }
        return list
    }

    suspend fun getRecentSessionsWithMetadata(limit: Int = 15): List<SessionMetadata> = withContext(Dispatchers.IO) {
        queryAllMetadataInternal(limit.coerceIn(1, 50))
    }

    /**
     * Searches saved sessions by title, project_name, project_type, project_path, summary,
     * and full message text content.
     */
    suspend fun searchSessions(query: String?, limit: Int = 15): List<SessionMetadata> = withContext(Dispatchers.IO) {
        val bounded = limit.coerceIn(1, 50)
        val clean = query?.trim().orEmpty()
        if (clean.isBlank()) {
            return@withContext queryAllMetadataInternal(bounded)
        }
        val like = "%$clean%"
        val db = readableDatabase
        val list = mutableListOf<SessionMetadata>()
        val sql = """
            SELECT DISTINCT s.* FROM $TABLE_SESSIONS s
            LEFT JOIN $TABLE_MESSAGES m ON s.$COL_SESSION_ID = m.$COL_MSG_CONV_ID
            WHERE s.$COL_SESSION_TITLE LIKE ? COLLATE NOCASE
               OR s.$COL_SESSION_PROJECT_NAME LIKE ? COLLATE NOCASE
               OR s.$COL_SESSION_PROJECT_TYPE LIKE ? COLLATE NOCASE
               OR s.$COL_SESSION_PROJECT_PATH LIKE ? COLLATE NOCASE
               OR s.$COL_SESSION_SUMMARY LIKE ? COLLATE NOCASE
               OR m.$COL_MSG_CONTENT LIKE ? COLLATE NOCASE
            ORDER BY MAX(s.$COL_SESSION_LAST_ACTIVE_AT, s.$COL_SESSION_UPDATED_AT) DESC
            LIMIT ?
        """.trimIndent()
        val cursor = db.rawQuery(sql, arrayOf(like, like, like, like, like, like, bounded.toString()))
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(readMetadataFromCursor(c))
            }
        }
        list
    }

    suspend fun getSessionMetadata(id: String): SessionMetadata? = withContext(Dispatchers.IO) {
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
            if (c.moveToFirst()) readMetadataFromCursor(c) else null
        }
    }

    suspend fun hasSession(id: String): Boolean = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT 1 FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID = ? LIMIT 1", arrayOf(id))
        cursor.use { it.moveToFirst() }
    }

    suspend fun getSession(id: String): Conversation? = getSessionMetadata(id)?.toConversation()

    suspend fun upsertSession(conv: Conversation, sessionType: String = "VOICE") = withContext(Dispatchers.IO) {
        val existing = getSessionMetadata(conv.id)
        val db = writableDatabase
        val inferredPath = conv.workspaceUri.ifBlank { existing?.projectPath.orEmpty() }
        val inferredProjectName = existing?.projectName?.takeIf { it.isNotBlank() }
            ?: inferredPath.removePrefix("file://").trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: ""
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_SESSION_ID, conv.id)
            put(COL_SESSION_TITLE, conv.title)
            put(COL_SESSION_TYPE, existing?.sessionType ?: sessionType)
            put(COL_SESSION_CREATED_AT, conv.createdAt)
            put(COL_SESSION_UPDATED_AT, conv.updatedAt)
            put(COL_SESSION_ARCHIVED, if (conv.archived) 1 else 0)
            put(COL_SESSION_PROJECT_NAME, inferredProjectName)
            put(COL_SESSION_PROJECT_TYPE, existing?.projectType.orEmpty())
            put(COL_SESSION_PROJECT_PATH, inferredPath)
            put(COL_SESSION_SUMMARY, conv.summary?.takeIf { it.isNotBlank() } ?: existing?.summary.orEmpty())
            put(COL_SESSION_LAST_ACTIVE_AT, maxOf(conv.updatedAt, existing?.lastActiveAt ?: 0L, now))
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        reloadSessions()
    }

    suspend fun updateSessionMetadata(
        sessionId: String,
        title: String? = null,
        sessionType: String = "CHAT",
        projectName: String? = null,
        projectType: String? = null,
        projectPath: String? = null,
        summary: String? = null,
        lastActiveAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        val existing = getSessionMetadata(sessionId)
        val db = writableDatabase
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: existing?.title ?: "Untitled Chat"
        val resolvedPath = projectPath?.takeIf { it.isNotBlank() } ?: existing?.projectPath.orEmpty()
        val resolvedProjectName = projectName?.takeIf { it.isNotBlank() }
            ?: existing?.projectName?.takeIf { it.isNotBlank() }
            ?: resolvedPath.removePrefix("file://").trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: ""
        val values = ContentValues().apply {
            put(COL_SESSION_ID, sessionId)
            put(COL_SESSION_TITLE, resolvedTitle)
            put(COL_SESSION_TYPE, existing?.sessionType ?: sessionType)
            put(COL_SESSION_CREATED_AT, existing?.createdAt ?: lastActiveAt)
            put(COL_SESSION_UPDATED_AT, maxOf(existing?.updatedAt ?: 0L, lastActiveAt))
            put(COL_SESSION_ARCHIVED, if (existing?.archived == true) 1 else 0)
            put(COL_SESSION_PROJECT_NAME, resolvedProjectName)
            put(COL_SESSION_PROJECT_TYPE, projectType?.takeIf { it.isNotBlank() } ?: existing?.projectType.orEmpty())
            put(COL_SESSION_PROJECT_PATH, resolvedPath)
            put(COL_SESSION_SUMMARY, summary?.takeIf { it.isNotBlank() } ?: existing?.summary.orEmpty())
            put(COL_SESSION_LAST_ACTIVE_AT, lastActiveAt)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        reloadSessions()
    }

    suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_UPDATED_AT, timestamp)
            put(COL_SESSION_LAST_ACTIVE_AT, timestamp)
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
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_MSG_CONV_ID, message.conversationId)
            put(COL_MSG_ROLE, if (message.role == MessageRole.USER) "user" else "assistant")
            put(COL_MSG_CONTENT, message.content)
            put(COL_MSG_VIA_VOICE, if (message.viaVoice) 1 else 0)
            put(COL_MSG_CREATED_AT, message.createdAt)
            put(COL_MSG_UPDATED_AT, now)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        // Keep session updated timestamp fresh so it floats to the top of drawer
        val sessionValues = ContentValues().apply {
            put(COL_SESSION_UPDATED_AT, now)
            put(COL_SESSION_LAST_ACTIVE_AT, now)
        }
        db.update(TABLE_SESSIONS, sessionValues, "$COL_SESSION_ID = ?", arrayOf(message.conversationId))
        reloadSessions()
    }
}
