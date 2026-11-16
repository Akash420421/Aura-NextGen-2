package com.example.gemini.data.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class AuraMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("category", category)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): AuraMemoryItem = AuraMemoryItem(
            id = json.optString("id", UUID.randomUUID().toString()),
            text = json.optString("text", ""),
            category = json.optString("category", CATEGORY_NOTE),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )

        const val CATEGORY_PREFERENCE = "PREFERENCE"
        const val CATEGORY_PROJECT = "PROJECT"
        const val CATEGORY_PERSONAL = "PERSONAL"
        const val CATEGORY_CONTACT = "CONTACT"
        const val CATEGORY_NOTE = "NOTE"
    }
}

/**
 * AuraMemoryManager: Persistent ChatGPT & Gemini-style memory system.
 * Keeps track of user facts, project preferences, tools, and relationships.
 * Survives app restarts and new chat sessions. Injected into every Gemini Live session.
 */
object AuraMemoryManager {
    private const val TAG = "AuraMemoryManager"
    private const val MEMORY_FILE_NAME = "aura_memories.json"
    private const val MAX_MEMORIES_IN_PROMPT = 40

    private val _memories = MutableStateFlow<List<AuraMemoryItem>>(emptyList())
    val memories: StateFlow<List<AuraMemoryItem>> = _memories.asStateFlow()

    private var memoryFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        val dir = File(context.filesDir, "memory")
        if (!dir.exists()) dir.mkdirs()
        memoryFile = File(dir, MEMORY_FILE_NAME)
        loadMemories()
    }

    private fun loadMemories() {
        val file = memoryFile ?: return
        if (!file.exists()) return

        synchronized(lock) {
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val arr = JSONArray(content)
                    val list = mutableListOf<AuraMemoryItem>()
                    for (i in 0 until arr.length()) {
                        val item = AuraMemoryItem.fromJson(arr.getJSONObject(i))
                        if (item.text.isNotBlank()) {
                            list.add(item)
                        }
                    }
                    _memories.value = list
                    Log.i(TAG, "Loaded ${list.size} persistent memories.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load memories: ${e.message}", e)
            }
        }
    }

    private fun persistMemories() {
        val file = memoryFile ?: return
        synchronized(lock) {
            try {
                val arr = JSONArray()
                _memories.value.forEach { arr.put(it.toJson()) }
                file.writeText(arr.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist memories: ${e.message}", e)
            }
        }
    }

    fun rememberFact(rawInput: String, categoryOverride: String? = null): String {
        val clean = rawInput
            .replace(Regex("(?i)^(hey jarvis|jarvis|aura|please)?\\s*(remember that|remember to|remember|yaad rakhna ki|yaad rakhna)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "I didn't catch what you'd like me to remember."
        }

        val category = categoryOverride ?: categorize(clean)
        val item = AuraMemoryItem(
            text = clean,
            category = category
        )

        synchronized(lock) {
            val list = _memories.value.toMutableList()
            // Avoid exact duplicates
            list.removeAll { it.text.equals(clean, ignoreCase = true) }
            list.add(0, item)
            _memories.value = list
            persistMemories()
        }

        Log.i(TAG, "Saved memory: [$category] $clean")
        return "Got it. I have committed that to memory: \"$clean\"."
    }

    fun deleteMemory(id: String): Boolean {
        synchronized(lock) {
            val current = _memories.value
            val next = current.filterNot { it.id == id }
            if (next.size != current.size) {
                _memories.value = next
                persistMemories()
                Log.i(TAG, "Deleted memory $id")
                return true
            }
            return false
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _memories.value = emptyList()
            persistMemories()
            Log.i(TAG, "Cleared all memories")
        }
    }

    private fun categorize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("project") || lower.contains("app") || lower.contains("code") || lower.contains("termux") || lower.contains("kotlin") || lower.contains("react") || lower.contains("website") ->
                AuraMemoryItem.CATEGORY_PROJECT
            lower.contains("prefer") || lower.contains("like") || lower.contains("love") || lower.contains("pasand") || lower.contains("favorite") || lower.contains("theme") ->
                AuraMemoryItem.CATEGORY_PREFERENCE
            lower.contains("name") || lower.contains("birthday") || lower.contains("age") || lower.contains("i am") || lower.contains("mera naam") || lower.contains("live in") ->
                AuraMemoryItem.CATEGORY_PERSONAL
            lower.contains("friend") || lower.contains("brother") || lower.contains("sister") || lower.contains("mom") || lower.contains("dad") || lower.contains("bhai") || lower.contains("dost") ->
                AuraMemoryItem.CATEGORY_CONTACT
            else -> AuraMemoryItem.CATEGORY_NOTE
        }
    }

    /**
     * Automatic semantic extraction from live conversations (like ChatGPT / Gemini memory).
     */
    fun learnFromConversation(cleanInput: String) {
        val clean = cleanInput.trim()
        if (clean.length < 5) return

        val lower = clean.lowercase(Locale.ROOT)

        // 1. User Name Extraction
        val nameMatch = Regex("(?i).*(?:my name is|call me|mera naam)\\s+([a-zA-Z0-9]+)").find(clean)
        if (nameMatch != null) {
            val name = nameMatch.groupValues[1].replaceFirstChar { it.uppercase() }
            if (name.length > 1 && !listOf("user", "sir", "bro").contains(name.lowercase())) {
                rememberFact("User's name is $name", AuraMemoryItem.CATEGORY_PERSONAL)
            }
        }

        // 2. Favorite Language or Framework
        val langMatch = Regex("(?i).*(?:i code in|i build apps? with|i use|my stack is)\\s+([a-zA-Z0-9#+.]+)").find(clean)
        if (langMatch != null) {
            val stack = langMatch.groupValues[1]
            rememberFact("User develops with $stack", AuraMemoryItem.CATEGORY_PROJECT)
        }

        // 3. User Preferences (e.g. "Mujhe dark theme pasand hai", "I prefer pnpm")
        val prefMatch = Regex("(?i).*(?:i prefer|i like|mujhe pasand hai)\\s+([a-zA-Z0-9\\s]{3,30})").find(clean)
        if (prefMatch != null) {
            val pref = prefMatch.groupValues[1].trim()
            if (pref.isNotBlank() && pref.length < 40) {
                rememberFact("User preference: $pref", AuraMemoryItem.CATEGORY_PREFERENCE)
            }
        }
    }

    /**
     * Injected into Gemini Live system prompt.
     */
    fun getSystemPromptContext(): String {
        val list = _memories.value
        if (list.isEmpty()) return ""

        val sb = StringBuilder("\n## Persistent Memory Core (Facts & Preferences across all sessions):\n")
        sb.append("You remember these persistent facts about the user:\n")
        list.take(MAX_MEMORIES_IN_PROMPT).forEach { item ->
            sb.append("- [${item.category}] ${item.text}\n")
        }
        sb.append("Use these details naturally to personalize answers without having to ask the user repeatedly.\n\n")
        return sb.toString()
    }
}
