package com.example.gemini.data.memory

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.gemini.data.live.HindiAsrNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One durable fact in Aura's long-term memory.
 *
 * Kept deliberately close to the old `AuraMemoryItem` shape so the existing
 * Settings → Jarvis → Persistent Memory UI keeps working without a rewrite.
 */
data class AuraMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis(),
    val topicKey: String = "",
    val source: String = "auto",
    val lastUsedAt: Long = 0L
) {
    companion object {
        const val CATEGORY_PREFERENCE = "PREFERENCE"
        const val CATEGORY_PROJECT = "PROJECT"
        const val CATEGORY_PERSONAL = "PERSONAL"
        const val CATEGORY_CONTACT = "CONTACT"
        const val CATEGORY_ROUTINE = "ROUTINE"
        const val CATEGORY_NOTE = "NOTE"
    }
}

/**
 * AuraMemoryV2 — automatic, persistent, cross-chat memory.
 *
 * What changed from the old JSON-only manager:
 *  - SQLite (WAL) is the source of truth, so memories survive process death, app updates and
 *    concurrent voice/text writes.
 *  - Local Hinglish/English extraction always runs. It does not depend on a Gemini API key, so
 *    memory keeps learning even when the user chats through agy / Claude / OpenCode / Codex.
 *  - Optional Gemini Flash enrichment is still used when a key is configured, but it is a bonus,
 *    never a requirement.
 *  - A rolling short-term summary (last 30 turns) is injected into every prompt, which fixes the
 *    "live mode forgets after two messages" behaviour.
 *  - The old `aura_memories.json` file is migrated once and then archived, never silently dropped.
 */
object AuraMemoryV2 {
    private const val TAG = "AuraMemoryV2"
    private const val DB_NAME = "aura_memory_v2.db"
    private const val DB_VERSION = 1
    private const val PREFS = "aura_memory_v2_prefs"
    private const val PREF_ENABLED = "enabled"
    private const val META_MIGRATED = "migrated_v1"
    private const val META_SUMMARY = "short_term_summary"
    private const val META_TURNS = "short_term_turns"
    private const val MAX_TOTAL_MEMORIES = 400
    private const val MAX_MEMORIES_IN_PROMPT = 25
    private const val MAX_CHAT_MEMORIES_IN_PROMPT = 14
    private const val MAX_SHORT_TERM_TURNS = 30
    private const val MAX_SHORT_TERM_CHARS = 1800
    private const val BASE_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val _memories = MutableStateFlow<List<AuraMemoryItem>>(emptyList())
    val memories: StateFlow<List<AuraMemoryItem>> = _memories.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _shortTermSummary = MutableStateFlow("")
    val shortTermSummary: StateFlow<String> = _shortTermSummary.asStateFlow()

    private val lock = Any()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var helper: MemoryDbHelper? = null
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    @Volatile
    private var lastExtractedTurnHash: Int = 0

    private val aiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4000, TimeUnit.MILLISECONDS)
            .readTimeout(7000, TimeUnit.MILLISECONDS)
            .writeTimeout(4000, TimeUnit.MILLISECONDS)
            .build()
    }

    // ------------------------------------------------------------------ lifecycle

    fun init(context: Context) {
        if (helper != null) return
        synchronized(lock) {
            if (helper != null) return
            val app = context.applicationContext
            appContext = app
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _enabled.value = p.getBoolean(PREF_ENABLED, true)
            val h = MemoryDbHelper(app)
            helper = h
            runCatching { h.writableDatabase }
                .onFailure { Log.e(TAG, "Could not open memory database: ${it.message}", it) }
            reloadFromDb()
            migrateOldJsonIfNeeded(app, h)
            _shortTermSummary.value = readMeta(META_SUMMARY).orEmpty()
            Log.i(TAG, "Memory V2 ready: ${_memories.value.size} long-term facts, enabled=${_enabled.value}")
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply()
    }

    private fun db(): SQLiteDatabase? = runCatching { helper?.writableDatabase }.getOrNull()

    private fun reloadFromDb() {
        val database = db() ?: return
        val list = mutableListOf<AuraMemoryItem>()
        runCatching {
            database.rawQuery(
                "SELECT id, text, category, topic_key, source, created_at, updated_at, last_used_at " +
                    "FROM memories ORDER BY updated_at DESC LIMIT $MAX_TOTAL_MEMORIES",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    list += AuraMemoryItem(
                        id = c.getString(0),
                        text = c.getString(1),
                        category = c.getString(2),
                        topicKey = c.getString(3).orEmpty(),
                        source = c.getString(4).orEmpty(),
                        timestamp = c.getLong(5),
                        lastUsedAt = c.getLong(7)
                    )
                }
            }
        }.onFailure { Log.e(TAG, "Failed to load memories: ${it.message}", it) }
        _memories.value = list.filterNot { isPollutedMemory(it.text) }
    }

    private fun migrateOldJsonIfNeeded(app: Context, h: MemoryDbHelper) {
        val database = runCatching { h.writableDatabase }.getOrNull() ?: return
        if (!_enabled.value) return
        if (readMeta(META_MIGRATED) == "1") return
        val old = File(File(app.filesDir, "memory"), "aura_memories.json")
        if (old.exists()) {
            runCatching {
                val arr = JSONArray(old.readText())
                var imported = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("text", "").trim()
                    if (text.isBlank()) continue
                    rememberFact(
                        rawInput = text,
                        categoryOverride = o.optString("category", ""),
                        topicKey = o.optString("topicKey", ""),
                        source = "migrated_v1"
                    )
                    imported++
                }
                Log.i(TAG, "Migrated $imported memories from old JSON store.")
            }.onFailure { Log.e(TAG, "Old memory migration failed: ${it.message}", it) }
            runCatching {
                val backup = File(old.parentFile, "aura_memories.json.migrated.bak")
                if (backup.exists()) backup.delete()
                old.renameTo(backup)
            }
        }
        writeMeta(META_MIGRATED, "1")
    }

    // ------------------------------------------------------------------ meta

    private fun readMeta(key: String): String? {
        val database = db() ?: return null
        return runCatching {
            database.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun writeMeta(key: String, value: String) {
        val database = db() ?: return
        runCatching {
            database.execSQL(
                "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)",
                arrayOf<Any>(key, value)
            )
        }.onFailure { Log.e(TAG, "Failed to write meta $key: ${it.message}", it) }
    }

    // ------------------------------------------------------------------ public memory API

    fun rememberFact(
        rawInput: String,
        categoryOverride: String? = null,
        topicKey: String = "",
        source: String = "auto"
    ): String {
        if (!_enabled.value) return "Memory is currently turned off."
        val clean = rawInput
            .replace(
                Regex(
                    "(?i)^(hey jarvis|jarvis|aura|please)?\\s*" +
                        "(remember that|remember to|remember|yaad rakhna ki|yaad rakhna|" +
                        "yaad rakh lena|note kar lo ki|note kar lo)\\s*"
                ),
                ""
            )
            .trim()

        if (clean.isBlank() || isPollutedMemory(clean)) {
            return "Nothing durable to save into memory."
        }

        val category = normalizeCategory(categoryOverride) ?: categorize(clean)
        val normKey = normalizeTopicKey(topicKey).ifBlank { inferTopicKey(clean, category) }
        val now = System.currentTimeMillis()
        val item = AuraMemoryItem(
            text = clean.take(320),
            category = category,
            topicKey = normKey,
            timestamp = now,
            source = source
        )

        synchronized(lock) {
            val current = _memories.value
            val duplicates = current.filter { existing ->
                existing.text.equals(clean, ignoreCase = true) ||
                    (normKey.isNotBlank() && existing.topicKey == normKey) ||
                    isNearDuplicate(existing.text, clean)
            }
            duplicates.forEach { deleteRow(it.id) }
            insertRow(item)
            _memories.value = (listOf(item) + current.filterNot { it in duplicates })
                .distinctBy { it.id }
                .take(MAX_TOTAL_MEMORIES)
        }

        Log.i(TAG, "Saved memory: [$category | key=$normKey] $clean")
        return "Got it. Saved to memory: \"$clean\"."
    }

    fun forgetMatching(query: String): Pair<Int, String> {
        if (!_enabled.value) return 0 to "Memory is currently turned off."
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return 0 to "Specify what memory you want me to forget."
        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        synchronized(lock) {
            val current = _memories.value
            val matched = current.filter { item ->
                val textLower = item.text.lowercase(Locale.ROOT)
                val keyLower = item.topicKey.lowercase(Locale.ROOT)
                textLower.contains(q) || keyLower.contains(q) ||
                    (tokens.isNotEmpty() && tokens.all { textLower.contains(it) })
            }
            matched.forEach { deleteRow(it.id) }
            _memories.value = current.filterNot { it in matched }
            return matched.size to if (matched.isNotEmpty()) {
                "Removed ${matched.size} matching memor${if (matched.size == 1) "y" else "ies"}."
            } else {
                "No stored memory matched '$query'."
            }
        }
    }

    fun recallMemories(query: String = "", limit: Int = 20): List<AuraMemoryItem> {
        val valid = _memories.value.filterNot { isPollutedMemory(it.text) }
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return valid.take(limit)
        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        return valid.sortedByDescending { item ->
            val t = item.text.lowercase(Locale.ROOT)
            if (t.contains(q)) 100 else tokens.count { t.contains(it) } * 15
        }.take(limit)
    }

    fun deleteMemory(id: String): Boolean {
        synchronized(lock) {
            val current = _memories.value
            if (current.none { it.id == id }) return false
            deleteRow(id)
            _memories.value = current.filterNot { it.id == id }
            Log.i(TAG, "Deleted memory $id")
            return true
        }
    }

    fun clearAll() {
        synchronized(lock) {
            val database = db()
            runCatching { database?.delete("memories", null, null) }
            _memories.value = emptyList()
            writeMeta(META_SUMMARY, "")
            writeMeta(META_TURNS, "[]")
            _shortTermSummary.value = ""
            Log.i(TAG, "Cleared all memories")
        }
    }

    private fun insertRow(item: AuraMemoryItem) {
        val database = db() ?: return
        runCatching {
            database.execSQL(
                "INSERT OR REPLACE INTO memories(id, text, category, topic_key, source, created_at, updated_at, last_used_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    item.id, item.text, item.category, item.topicKey, item.source,
                    item.timestamp, item.timestamp, item.lastUsedAt
                )
            )
        }.onFailure { Log.e(TAG, "Failed to save memory: ${it.message}", it) }
    }

    private fun deleteRow(id: String) {
        val database = db() ?: return
        runCatching { database.delete("memories", "id = ?", arrayOf(id)) }
    }

    // ------------------------------------------------------------------ learning

    /**
     * Fast local rule-based extraction across English & Hinglish complete turns.
     * This path never needs a network key, which is why memory now works with every chat backend.
     */
    fun learnFromConversation(cleanInput: String) {
        val clean = cleanInput.trim()
        if (clean.length < 8 || HindiAsrNormalizer.isNoiseOrForeignHallucination(clean)) return

        // 1. Explicit "remember / yaad rakhna / note kar lo"
        Regex(
            "(?i)(?:please\\s+)?(?:remember\\s+that|remember\\s+to|always\\s+remember|" +
                "yaad\\s+rakhna\\s+ki|yaad\\s+rakhna|yaad\\s+rakh\\s+lena|" +
                "note\\s+kar\\s+lo\\s+ki|note\\s+kar\\s+lo)\\s+(.+)$"
        ).find(clean)?.let { m ->
            val fact = m.groupValues[1].trim()
            if (fact.length >= 4 && !fact.startsWith("kya ", true)) rememberFact(fact)
        }

        // 2. User name
        Regex("(?i)\\b(?:my\\s+name\\s+is|call\\s+me|mera\\s+naam)\\s+([\\p{L}][\\p{L}\\s]{1,26}?)(?:\\s+hai\\b|[.,!?]|$)")
            .find(clean)?.let { m ->
                val name = m.groupValues[1].trim().split(Regex("\\s+")).joinToString(" ") { w ->
                    w.replaceFirstChar { it.uppercase() }
                }
                if (name.lowercase() !in setOf(
                        "user", "sir", "bro", "kya", "hai", "haan", "nahi", "boss", "aura", "jarvis"
                    )
                ) {
                    rememberFact("User's name is $name", AuraMemoryItem.CATEGORY_PERSONAL, "user_name")
                }
            }

        // 3. Birthday
        (Regex("(?i)\\b(?:my\\s+birthday\\s+is|born\\s+on|my\\s+bday\\s+is)\\s+(?:on\\s+)?([a-zA-Z0-9\\s,]{3,28})(?:[.,!]|$)")
            .find(clean)
            ?: Regex("(?i)\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+([a-zA-Z0-9\\s]{3,24}?)\\s*(?:ko\\s+hai|ko|hai)?(?:[.,!]|$)")
                .find(clean))?.let { m ->
            val bday = m.groupValues[1].trim()
            if (bday.length >= 3 && bday.lowercase() !in setOf("kab", "kya", "today", "aaj", "tomorrow")) {
                rememberFact("User's birthday is $bday", AuraMemoryItem.CATEGORY_PERSONAL, "user_birthday")
            }
        }

        // 4. City / location
        (Regex("(?i)\\b(?:i\\s+live\\s+in|i\\s+am\\s+from|main\\s+|mein\\s+)([a-zA-Z\\s]{2,26}?)\\s+(?:mein\\s+rehta\\s+hoon|mein\\s+rehti\\s+hoon|se\\s+hoon)(?:[.,!]|$)")
            .find(clean)
            ?: Regex("(?i)\\b(?:i\\s+live\\s+in|my\\s+city\\s+is|my\\s+hometown\\s+is|mera\\s+shehar)\\s+([a-zA-Z\\s]{2,26})(?:[.,!]|$)")
                .find(clean))?.let { m ->
            val loc = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (loc.length >= 3 && loc.lowercase() !in setOf("here", "there", "home", "india")) {
                rememberFact("User lives in $loc", AuraMemoryItem.CATEGORY_PERSONAL, "user_location")
            }
        }

        // 5. Age
        (Regex("(?i)\\b(?:i\\s+am|i'm|meri\\s+umar|meri\\s+age)\\s+(\\d{1,3})\\s*(?:years?\\s+old|saal\\s+ki|saal\\s+ka)?\\b")
            .find(clean))?.let { m ->
            val age = m.groupValues[1].toIntOrNull()
            if (age != null && age in 5..120) {
                rememberFact("User is $age years old", AuraMemoryItem.CATEGORY_PERSONAL, "user_age")
            }
        }

        // 6. Profession
        (Regex("(?i)\\b(?:i\\s+work\\s+as|i\\s+am\\s+a|main\\s+ek|main)\\s+([a-zA-Z\\s]{3,32}?)\\s+(?:hoon|kaam\\s+karta\\s+hoon|kaam\\s+karti\\s+hoon)(?:[.,!]|$)")
            .find(clean))?.let { m ->
            val job = m.groupValues[1].trim()
            if (job.length in 3..32 && job.lowercase() !in setOf("student", "user", "insaan", "aadmi")) {
                rememberFact("User works as $job", AuraMemoryItem.CATEGORY_PERSONAL, "user_profession")
            }
        }

        // 7. Coding stack
        Regex("(?i)\\b(?:i\\s+code\\s+in|my\\s+coding\\s+stack\\s+is|my\\s+tech\\s+stack\\s+is)\\s+([a-zA-Z0-9#+.\\s]{2,30})(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val stack = m.groupValues[1].trim()
                if (!isPollutedMemory("User develops with $stack")) {
                    rememberFact("User develops with $stack", AuraMemoryItem.CATEGORY_PROJECT, "coding_stack")
                }
            }

        // 8. General favourites
        Regex("(?i)\\b(?:my\\s+favou?rite|mera\\s+favou?rite|mera\\s+pasandida)\\s+([a-zA-Z\\s]{2,20})\\s+(?:is|are|hai)\\s+([\\p{L}0-9\\s]{2,36})(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val topic = m.groupValues[1].trim().lowercase(Locale.ROOT)
                val value = m.groupValues[2].trim()
                if (topic.isNotBlank() && value.isNotBlank() && !value.equals("kya", true)) {
                    rememberFact("User's favorite $topic is $value", AuraMemoryItem.CATEGORY_PREFERENCE, "favorite_$topic")
                }
            }

        // 9. Preferences / dislikes
        Regex("(?i)\\b(?:always\\s+remember\\s+i\\s+prefer|meri\\s+preference\\s+hai|mujhe\\s+hamesha|mujhe)\\s+([\\p{L}0-9\\s]{3,36}?)\\s+(?:pasand\\s+hai|prefer|chahiye\\s+hota\\s+hai)(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val pref = m.groupValues[1].trim()
                if (pref.length in 3..36) {
                    rememberFact("User prefers $pref", AuraMemoryItem.CATEGORY_PREFERENCE, "pref_${normalizeTopicKey(pref)}")
                }
            }

        Regex("(?i)\\b(?:i\\s+dislike|i\\s+hate|mujhe)\\s+([\\p{L}0-9\\s]{3,36}?)\\s*(?:nahi\\s+pasand|pasand\\s+nahi)(?:\\s+hai)?(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val disliked = m.groupValues[1].trim()
                if (disliked.length in 3..36) {
                    rememberFact("User dislikes $disliked", AuraMemoryItem.CATEGORY_PREFERENCE, "dislike_${normalizeTopicKey(disliked)}")
                }
            }

        // 10. Relationships
        (Regex("(?i)\\b([a-zA-Z]{2,20})\\s+is\\s+my\\s+(brother|sister|best\\s+friend|friend|mom|mother|dad|father|wife|husband|cousin|boss)\\b")
            .find(clean)
            ?: Regex("(?i)\\b([a-zA-Z]{2,20})\\s+mera\\s+(bhai|dost|yaar|papa|mummy|boss)\\b")
                .find(clean))?.let { m ->
            val person = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            val rel = m.groupValues[2].trim().lowercase(Locale.ROOT)
            if (person.lowercase() !in setOf("who", "he", "she", "this", "that", "kaun", "wo", "ye")) {
                rememberFact("$person is user's $rel", AuraMemoryItem.CATEGORY_CONTACT, "contact_${person.lowercase()}")
            }
        }

        // 11. Routines
        Regex("(?i)\\b(?:main\\s+roz|i\\s+usually|i\\s+always|meri\\s+routine\\s+hai)\\s+([\\p{L}0-9\\s:]{4,60}?)(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val routine = m.groupValues[1].trim()
                if (routine.length in 4..60) {
                    rememberFact("User routine: $routine", AuraMemoryItem.CATEGORY_ROUTINE, "routine_${normalizeTopicKey(routine)}")
                }
            }

        // 12. Active project / goal
        Regex("(?i)\\b(?:main\\s+)([\\p{L}0-9\\s]{3,40}?)\\s+(?:par|pe)\\s+kaam\\s+kar\\s+raha\\s+hoon(?:[.,!]|$)")
            .find(clean)?.let { m ->
                val project = m.groupValues[1].trim()
                if (project.length in 3..40) {
                    rememberFact("User is working on $project", AuraMemoryItem.CATEGORY_PROJECT, "active_project_${normalizeTopicKey(project)}")
                }
            }
    }

    internal fun shouldRunAiMemoryExtraction(userInput: String): Boolean {
        val trimmed = userInput.trim()
        if (trimmed.length < 18 || trimmed.length > 1500) return false
        if (HindiAsrNormalizer.isNoiseOrForeignHallucination(trimmed)) return false
        if (trimmed.contains("```") || trimmed.count { it == '\n' } > 16) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower in setOf(
                "hi", "hello", "hey", "ok", "okay", "haan", "theek hai", "acha", "yes", "no",
                "nahi", "bye", "thanks", "thank you"
            )
        ) return false
        val signals = listOf(
            "remember", "yaad", "mera ", "meri ", "mere ", "my ", "i am ", "i'm ", "i live",
            "i work", "i like", "i love", "i hate", "i prefer", "pasand", "birthday", "janamdin",
            "favorite", "favourite", "exam", "meeting", "routine", "subah", "hamesha", "never ",
            "always ", "bhai", "dost", "papa", "mummy", "wife", "boss", "naam", "college", "office",
            "project", "website", "deadline", "goal", "plan"
        )
        if (signals.any { lower.contains(it) }) return true
        val transientOnly = Regex(
            "(?i)^(open|close|band|kholo|chalu|scroll|swipe|volume|brightness|battery|" +
                "pause|play|resume|next|previous|like|skip|screenshot|dial|call)\\b.*"
        )
        if (transientOnly.matches(trimmed) && trimmed.split(Regex("\\s+")).size <= 8) return false
        return trimmed.split(Regex("\\s+")).size >= 6
    }

    /**
     * Runs local extraction immediately and optionally asks Gemini Flash for a richer extraction.
     * Called after a complete voice or text turn. The local path always runs.
     */
    fun processCompletedTurnAsync(
        context: Context,
        userInput: String,
        assistantResponse: String = "",
        isVoice: Boolean = true
    ) {
        init(context)
        if (!_enabled.value) return
        val cleanUser = userInput.trim()
        if (cleanUser.isBlank()) return

        learnFromConversation(cleanUser)
        appendShortTermTurn(cleanUser, assistantResponse)

        if (!shouldRunAiMemoryExtraction(cleanUser)) return

        val turnHash = "${cleanUser.lowercase(Locale.ROOT)}|${assistantResponse.take(80)}".hashCode()
        if (turnHash == lastExtractedTurnHash) return
        lastExtractedTurnHash = turnHash

        bgScope.launch {
            runCatching { extractWithGeminiFlash(cleanUser, assistantResponse, isVoice) }
                .onFailure { Log.d(TAG, "Background auto-memory skipped/failed: ${it.message}") }
        }
    }

    private fun extractWithGeminiFlash(userInput: String, assistantResponse: String, isVoice: Boolean) {
        val apiKey = com.example.gemini.data.jarvis.JarvisPreferences.liveApiKey.value.trim().ifBlank {
            com.example.gemini.data.jarvis.JarvisPreferences.ttsApiKey.value.trim()
        }
        if (apiKey.isBlank()) return

        val existingSnapshot = _memories.value.take(20).joinToString("\n") {
            "- [${it.category}|${it.topicKey}] ${it.text}"
        }.ifBlank { "(none yet)" }

        val prompt = buildString {
            append("You are an automatic long-term memory extractor for Aura AI.\n")
            append("Extract ONLY durable facts worth remembering across future conversations:\n")
            append("- Identity/personal info (name, city, profession, college, birthday, age)\n")
            append("- Persistent preferences, likes, dislikes, language/style rules, hobbies\n")
            append("- Important people/relationships (family, friends, colleagues)\n")
            append("- Deadlines/exams/appointments/important plans or explicit things to remember\n")
            append("- Daily routines or recurring habits\n")
            append("- Active projects, goals, and long-running work\n")
            append("DO NOT save greetings, one-off phone commands, temporary errors, or duplicates.\n\n")
            append("Existing stored memories:\n").append(existingSnapshot).append("\n\n")
            append("Channel: ").append(if (isVoice) "Live Voice" else "Text Chat").append('\n')
            append("User message: \"").append(userInput.take(900)).append("\"\n")
            if (assistantResponse.isNotBlank()) {
                append("Assistant reply: \"").append(assistantResponse.take(300)).append("\"\n")
            }
            append("\nRespond ONLY as valid JSON: ")
            append("{\"should_save\": boolean, \"memories\": [{\"fact\": \"concise third-person English fact\", ")
            append("\"category\": \"PERSONAL|PREFERENCE|CONTACT|ROUTINE|PROJECT|NOTE\", \"topic_key\": \"short_snake_case_key_or_empty\"}]}")
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().put("text", prompt)) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 240)
                put("responseMimeType", "application/json")
            })
        }.toString()

        for (model in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
            val req = Request.Builder()
                .url("$BASE_GEMINI_URL/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            aiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) continue
                val rawBody = resp.body?.string() ?: continue
                val json = JSONObject(rawBody)
                val contentText = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                    .orEmpty()
                if (contentText.isBlank()) return
                val cleanJson = contentText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val parsed = JSONObject(cleanJson)
                if (!parsed.optBoolean("should_save", false)) return
                val arr = parsed.optJSONArray("memories") ?: return
                for (i in 0 until arr.length().coerceAtMost(4)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val fact = obj.optString("fact", "").trim()
                    if (fact.length >= 6) {
                        rememberFact(
                            rawInput = fact,
                            categoryOverride = obj.optString("category", AuraMemoryItem.CATEGORY_NOTE),
                            topicKey = obj.optString("topic_key", ""),
                            source = "ai"
                        )
                    }
                }
                return
            }
        }
    }

    // ------------------------------------------------------------------ short-term memory

    private fun appendShortTermTurn(user: String, assistant: String) {
        if (user.isBlank()) return
        synchronized(lock) {
            val turns = runCatching { JSONArray(readMeta(META_TURNS).orEmpty().ifBlank { "[]" }) }
                .getOrDefault(JSONArray())
            turns.put(JSONObject().apply {
                put("u", user.take(500))
                put("a", assistant.take(500))
                put("t", System.currentTimeMillis())
            })
            val start = (turns.length() - MAX_SHORT_TERM_TURNS).coerceAtLeast(0)
            val kept = JSONArray()
            for (i in start until turns.length()) kept.put(turns.getJSONObject(i))
            writeMeta(META_TURNS, kept.toString())

            val summary = buildString {
                for (i in 0 until kept.length()) {
                    val o = kept.getJSONObject(i)
                    val u = o.optString("u").trim()
                    val a = o.optString("a").trim()
                    if (u.isNotBlank()) append("User: ").append(u).append('\n')
                    if (a.isNotBlank()) append("Aura: ").append(a).append('\n')
                }
            }.trim().takeLast(MAX_SHORT_TERM_CHARS)
            writeMeta(META_SUMMARY, summary)
            _shortTermSummary.value = summary
        }
    }

    fun getShortTermSummary(): String = _shortTermSummary.value.trim()

    // ------------------------------------------------------------------ prompt context

    fun getSystemPromptContext(userQuery: String = ""): String {
        if (!_enabled.value) return ""
        val list = _memories.value.filterNot { isPollutedMemory(it.text) }
        val shortTerm = _shortTermSummary.value.trim()
        if (list.isEmpty() && shortTerm.isBlank()) return ""

        val sb = StringBuilder("\n## Persistent Memory Core (user facts & preferences):\n")
        val prioritized = selectRelevantMemories(list, userQuery, MAX_MEMORIES_IN_PROMPT, includeProjects = true)
        prioritized.forEach { item ->
            sb.append("- [${item.category}] ${item.text}\n")
        }
        if (shortTerm.isNotBlank()) {
            sb.append("\n## Recent Conversation Memory (last 30 turns, compressed):\n")
            sb.append(shortTerm).append('\n')
        }
        sb.append("\nUse these for personalization and continuity. NEVER invent a memory, and NEVER drag an unrelated past project into a current phone/browser task.\n\n")
        return sb.toString()
    }

    fun getChatMemoryContext(userQuery: String = ""): String {
        if (!_enabled.value) return ""
        val list = _memories.value.filterNot { isPollutedMemory(it.text) }
        val shortTerm = _shortTermSummary.value.trim()
        if (list.isEmpty() && shortTerm.isBlank()) return ""
        val selected = selectRelevantMemories(list, userQuery, MAX_CHAT_MEMORIES_IN_PROMPT, includeProjects = false)
        return buildString {
            append("## Remembered User Facts & Preferences (Persistent Auto-Memory)\n")
            selected.forEach { item ->
                append("- [${item.category}] ${item.text}\n")
            }
            if (shortTerm.isNotBlank()) {
                append("\n## Recent Conversation Memory\n")
                append(shortTerm.take(1200)).append('\n')
            }
        }.trimEnd()
    }

    private fun selectRelevantMemories(
        list: List<AuraMemoryItem>,
        query: String,
        limit: Int,
        includeProjects: Boolean
    ): List<AuraMemoryItem> {
        val pool = if (includeProjects) list else list.filter { it.category != AuraMemoryItem.CATEGORY_PROJECT }
        if (pool.size <= limit) return pool
        val qTokens = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        return pool.sortedByDescending { item ->
            val baseCatScore = when (item.category) {
                AuraMemoryItem.CATEGORY_PERSONAL -> 40
                AuraMemoryItem.CATEGORY_PREFERENCE -> 30
                AuraMemoryItem.CATEGORY_ROUTINE -> 28
                AuraMemoryItem.CATEGORY_CONTACT -> 20
                AuraMemoryItem.CATEGORY_PROJECT -> 16
                else -> 10
            }
            val textLower = item.text.lowercase(Locale.ROOT)
            val overlapScore = qTokens.count { textLower.contains(it) } * 25
            val recencyBonus = (item.timestamp / 3_600_000L).toInt()
            baseCatScore + overlapScore + (recencyBonus % 15)
        }.take(limit)
    }

    // ------------------------------------------------------------------ helpers

    internal fun normalizeTopicKey(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)

    internal fun isNearDuplicate(a: String, b: String): Boolean {
        val la = a.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val lb = b.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (la.isBlank() || lb.isBlank()) return false
        if (la == lb || (la.length >= 10 && lb.contains(la)) || (lb.length >= 10 && la.contains(lb))) return true
        val wordsA = la.split(" ").filter { it.length > 2 }.toSet()
        val wordsB = lb.split(" ").filter { it.length > 2 }.toSet()
        if (wordsA.size < 3 || wordsB.size < 3) return false
        val inter = wordsA.intersect(wordsB).size.toFloat()
        val union = wordsA.union(wordsB).size.toFloat().coerceAtLeast(1f)
        return (inter / union) >= 0.72f
    }

    private fun isPollutedMemory(text: String): Boolean {
        if (HindiAsrNormalizer.isNoiseOrForeignHallucination(text)) return true
        val trimmed = text.trim()
        if (trimmed.length < 4) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("user develops with ")) {
            val value = lower.removePrefix("user develops with ").trim()
            if (value in setOf(
                    "chrome", "google", "youtube", "whatsapp", "option", "question", "next",
                    "browser", "this", "that", "it", "phone", "app", "hindi", "english"
                )
            ) return true
        }
        return false
    }

    private fun normalizeCategory(raw: String?): String? {
        val u = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when {
            u in setOf(
                AuraMemoryItem.CATEGORY_PERSONAL,
                AuraMemoryItem.CATEGORY_PREFERENCE,
                AuraMemoryItem.CATEGORY_PROJECT,
                AuraMemoryItem.CATEGORY_CONTACT,
                AuraMemoryItem.CATEGORY_ROUTINE,
                AuraMemoryItem.CATEGORY_NOTE
            ) -> u
            u == "REMINDER" || u == "SCHEDULE" || u == "HABIT" -> AuraMemoryItem.CATEGORY_ROUTINE
            u == "WORK" || u == "CODING" -> AuraMemoryItem.CATEGORY_PROJECT
            else -> null
        }
    }

    private fun inferTopicKey(text: String, category: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("user's name is ") || lower.contains("user's preferred name") -> "user_name"
            lower.startsWith("user's birthday is ") || lower.contains("birthday") || lower.contains("janamdin") -> "user_birthday"
            lower.startsWith("user lives in ") -> "user_location"
            lower.startsWith("user is ") && lower.contains("years old") -> "user_age"
            lower.startsWith("user's profession") || lower.startsWith("user works as") -> "user_profession"
            lower.startsWith("user's favorite song") -> "favorite_song"
            lower.startsWith("user's favorite color") -> "favorite_color"
            lower.startsWith("user's favorite food") -> "favorite_food"
            lower.startsWith("user's favorite app") -> "favorite_app"
            lower.startsWith("user's daily routine") || lower.startsWith("user routine:") -> "daily_routine"
            category == AuraMemoryItem.CATEGORY_CONTACT -> {
                val firstToken = lower.substringBefore(" is ").trim()
                if (firstToken.isNotBlank() && firstToken.length <= 24) "contact_$firstToken" else ""
            }
            else -> ""
        }
    }

    private fun categorize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("project") || lower.contains("code") || lower.contains("termux") ||
                lower.contains("kotlin") || lower.contains("react") || lower.contains("website") ||
                lower.contains("develops with") -> AuraMemoryItem.CATEGORY_PROJECT
            lower.contains("routine") || lower.contains("wake up") || lower.contains("gym") ||
                lower.contains("subah") || lower.contains("baje") || lower.contains("daily") ||
                lower.contains("schedule") || lower.contains("exam") -> AuraMemoryItem.CATEGORY_ROUTINE
            lower.contains("prefer") || lower.contains("like") || lower.contains("love") ||
                lower.contains("pasand") || lower.contains("favorite") || lower.contains("favourite") ||
                lower.contains("theme") || lower.contains("dislike") || lower.contains("hate") ->
                AuraMemoryItem.CATEGORY_PREFERENCE
            lower.contains("name") || lower.contains("birthday") || lower.contains("janamdin") ||
                lower.contains("age") || lower.contains("i am") || lower.contains("mera naam") ||
                lower.contains("live in") || lower.contains("rehta") || lower.contains("profession") ->
                AuraMemoryItem.CATEGORY_PERSONAL
            lower.contains("friend") || lower.contains("brother") || lower.contains("sister") ||
                lower.contains("mom") || lower.contains("dad") || lower.contains("bhai") ||
                lower.contains("dost") || lower.contains("wife") || lower.contains("boss") ->
                AuraMemoryItem.CATEGORY_CONTACT
            else -> AuraMemoryItem.CATEGORY_NOTE
        }
    }

    private class MemoryDbHelper(context: Context) :
        SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memories (" +
                    "id TEXT PRIMARY KEY, " +
                    "text TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "topic_key TEXT NOT NULL DEFAULT '', " +
                    "source TEXT NOT NULL DEFAULT 'auto', " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "last_used_at INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_topic ON memories(topic_key)")
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first schema. Future migrations add columns/tables here.
        }
    }
}
