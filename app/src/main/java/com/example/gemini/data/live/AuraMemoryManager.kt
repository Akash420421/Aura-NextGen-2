package com.example.gemini.data.live

import android.content.Context
import android.util.Log
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

data class AuraMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis(),
    val topicKey: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("category", category)
        put("timestamp", timestamp)
        if (topicKey.isNotBlank()) put("topicKey", topicKey)
    }

    companion object {
        fun fromJson(json: JSONObject): AuraMemoryItem = AuraMemoryItem(
            id = json.optString("id", UUID.randomUUID().toString()),
            text = json.optString("text", ""),
            category = json.optString("category", CATEGORY_NOTE),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            topicKey = json.optString("topicKey", "")
        )

        const val CATEGORY_PREFERENCE = "PREFERENCE"
        const val CATEGORY_PROJECT = "PROJECT"
        const val CATEGORY_PERSONAL = "PERSONAL"
        const val CATEGORY_CONTACT = "CONTACT"
        const val CATEGORY_ROUTINE = "ROUTINE"
        const val CATEGORY_NOTE = "NOTE"
    }
}

/**
 * AuraMemoryManager: Persistent ChatGPT & Gemini-style automatic memory system.
 * Tracks durable user facts, preferences, relationships, routines, reminders, and projects.
 * Runs local Hinglish/English rules + background semantic Gemini extraction across Voice & Text Chat.
 */
object AuraMemoryManager {
    private const val TAG = "AuraMemoryManager"
    private const val MEMORY_FILE_NAME = "aura_memories.json"
    private const val MAX_MEMORIES_IN_PROMPT = 25
    private const val MAX_CHAT_MEMORIES_IN_PROMPT = 12
    private const val MAX_TOTAL_MEMORIES = 200
    private const val BASE_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val _memories = MutableStateFlow<List<AuraMemoryItem>>(emptyList())
    val memories: StateFlow<List<AuraMemoryItem>> = _memories.asStateFlow()

    private var memoryFile: File? = null
    private val lock = Any()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastExtractedTurnHash: Int = 0

    private val aiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4000, TimeUnit.MILLISECONDS)
            .readTimeout(7000, TimeUnit.MILLISECONDS)
            .writeTimeout(4000, TimeUnit.MILLISECONDS)
            .build()
    }

    fun init(context: Context) {
        if (memoryFile != null) return
        synchronized(lock) {
            if (memoryFile != null) return
            val dir = File(context.applicationContext.filesDir, "memory")
            if (!dir.exists()) dir.mkdirs()
            memoryFile = File(dir, MEMORY_FILE_NAME)
            loadMemories()
        }
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
                    val filtered = list.filterNot { isPollutedMemory(it.text) }
                    _memories.value = filtered
                    Log.i(TAG, "Loaded ${filtered.size} persistent memories.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load memories: ${e.message}", e)
            }
        }
    }

    private fun isPollutedMemory(text: String): Boolean {
        if (HindiAsrNormalizer.isNoiseOrForeignHallucination(text)) return true
        val trimmed = text.trim()
        if (trimmed.length < 4) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("user develops with ")) {
            val valPart = lower.removePrefix("user develops with ").trim()
            if (valPart in setOf("chrome", "google", "youtube", "whatsapp", "option", "question", "next", "browser", "this", "that", "it", "phone", "app", "hindi", "english")) {
                return true
            }
        }
        return false
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

    internal fun normalizeTopicKey(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)

    internal fun isNearDuplicate(a: String, b: String): Boolean {
        val la = a.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val lb = b.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        if (la.isBlank() || lb.isBlank()) return false
        if (la == lb || (la.length >= 10 && lb.contains(la)) || (lb.length >= 10 && la.contains(lb))) return true
        val wordsA = la.split(" ").filter { it.length > 2 }.toSet()
        val wordsB = lb.split(" ").filter { it.length > 2 }.toSet()
        if (wordsA.size < 3 || wordsB.size < 3) return false
        val inter = wordsA.intersect(wordsB).size.toFloat()
        val union = wordsA.union(wordsB).size.toFloat().coerceAtLeast(1f)
        return (inter / union) >= 0.72f
    }

    fun rememberFact(
        rawInput: String,
        categoryOverride: String? = null,
        topicKey: String = ""
    ): String {
        val clean = rawInput
            .replace(Regex("(?i)^(hey jarvis|jarvis|aura|please)?\\s*(remember that|remember to|remember|yaad rakhna ki|yaad rakhna|note kar lo ki|note kar lo)\\s*"), "")
            .trim()

        if (clean.isBlank() || isPollutedMemory(clean)) {
            return "Nothing durable to save into memory."
        }

        val category = normalizeCategory(categoryOverride) ?: categorize(clean)
        val normKey = normalizeTopicKey(topicKey).ifBlank { inferTopicKey(clean, category) }
        val item = AuraMemoryItem(
            text = clean.take(280),
            category = category,
            topicKey = normKey
        )

        synchronized(lock) {
            val list = _memories.value.toMutableList()
            list.removeAll { existing ->
                existing.text.equals(clean, ignoreCase = true) ||
                    (normKey.isNotBlank() && existing.topicKey == normKey) ||
                    isNearDuplicate(existing.text, clean)
            }
            list.add(0, item)
            _memories.value = list.take(MAX_TOTAL_MEMORIES)
            persistMemories()
        }

        Log.i(TAG, "Saved memory: [$category | key=$normKey] $clean")
        return "Got it. Saved to memory: \"$clean\"."
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
            lower.startsWith("user's profession") || lower.startsWith("user works as") -> "user_profession"
            lower.startsWith("user's favorite song") -> "favorite_song"
            lower.startsWith("user's favorite color") -> "favorite_color"
            lower.startsWith("user's favorite food") -> "favorite_food"
            lower.startsWith("user's favorite app") -> "favorite_app"
            lower.startsWith("user's daily routine") -> "daily_routine"
            category == AuraMemoryItem.CATEGORY_CONTACT -> {
                val firstToken = lower.substringBefore(" is ").trim()
                if (firstToken.isNotBlank() && firstToken.length <= 24) "contact_$firstToken" else ""
            }
            else -> ""
        }
    }

    fun forgetMatching(query: String): Pair<Int, String> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return 0 to "Specify what memory you want me to forget."
        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        synchronized(lock) {
            val current = _memories.value
            val remaining = current.filterNot { item ->
                val textLower = item.text.lowercase(Locale.ROOT)
                val keyLower = item.topicKey.lowercase(Locale.ROOT)
                textLower.contains(q) || keyLower.contains(q) ||
                    (tokens.isNotEmpty() && tokens.all { textLower.contains(it) })
            }
            val removed = current.size - remaining.size
            if (removed > 0) {
                _memories.value = remaining
                persistMemories()
                Log.i(TAG, "Forgot $removed memory item(s) matching '$query'")
            }
            return removed to if (removed > 0) {
                "Removed $removed matching memor${if (removed == 1) "y" else "ies"}."
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
            val score = if (t.contains(q)) 100 else tokens.count { t.contains(it) } * 15
            score
        }.take(limit)
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
            lower.contains("project") || lower.contains("code") || lower.contains("termux") || lower.contains("kotlin") || lower.contains("react") || lower.contains("website") || lower.contains("develops with") ->
                AuraMemoryItem.CATEGORY_PROJECT
            lower.contains("routine") || lower.contains("wake up") || lower.contains("gym") || lower.contains("subah") || lower.contains("baje") || lower.contains("daily") || lower.contains("schedule") || lower.contains("exam") ->
                AuraMemoryItem.CATEGORY_ROUTINE
            lower.contains("prefer") || lower.contains("like") || lower.contains("love") || lower.contains("pasand") || lower.contains("favorite") || lower.contains("favourite") || lower.contains("theme") || lower.contains("dislike") || lower.contains("hate") ->
                AuraMemoryItem.CATEGORY_PREFERENCE
            lower.contains("name") || lower.contains("birthday") || lower.contains("janamdin") || lower.contains("age") || lower.contains("i am") || lower.contains("mera naam") || lower.contains("live in") || lower.contains("rehta") || lower.contains("profession") ->
                AuraMemoryItem.CATEGORY_PERSONAL
            lower.contains("friend") || lower.contains("brother") || lower.contains("sister") || lower.contains("mom") || lower.contains("dad") || lower.contains("bhai") || lower.contains("dost") || lower.contains("wife") || lower.contains("boss") ->
                AuraMemoryItem.CATEGORY_CONTACT
            else -> AuraMemoryItem.CATEGORY_NOTE
        }
    }

    /**
     * Local fast rule-based extraction across English & Hinglish complete turns.
     */
    fun learnFromConversation(cleanInput: String) {
        val clean = cleanInput.trim()
        if (clean.length < 8 || HindiAsrNormalizer.isNoiseOrForeignHallucination(clean)) return

        // 1. Explicit "remember / yaad rakhna / note kar lo"
        Regex("(?i)(?:please\\s+)?(?:remember\\s+that|remember\\s+to|always\\s+remember|yaad\\s+rakhna\\s+ki|yaad\\s+rakhna|yaad\\s+rakh\\s+lena|note\\s+kar\\s+lo\\s+ki|note\\s+kar\\s+lo)\\s+(.+)$")
            .find(clean)?.let { m ->
                val fact = m.groupValues[1].trim()
                if (fact.length >= 4 && !fact.startsWith("kya ", true)) {
                    rememberFact(fact)
                }
            }

        // 2. User Name (English & Hinglish, multi-word allowed up to 28 chars)
        Regex("(?i)\\b(?:my\\s+name\\s+is|call\\s+me|mera\\s+naam)\\s+([\\p{L}][\\p{L}\\s]{1,26}?)(?:\\s+hai\\b|[.,!?]|$)").find(clean)?.let { m ->
            val name = m.groupValues[1].trim().split(Regex("\\s+")).joinToString(" ") { w ->
                w.replaceFirstChar { it.uppercase() }
            }
            if (name.lowercase() !in setOf("user", "sir", "bro", "kya", "hai", "haan", "nahi", "boss", "aura", "jarvis")) {
                rememberFact("User's name is $name", AuraMemoryItem.CATEGORY_PERSONAL, topicKey = "user_name")
            }
        }

        // 3. Birthday
        (Regex("(?i)\\b(?:my\\s+birthday\\s+is|born\\s+on|my\\s+bday\\s+is)\\s+(?:on\\s+)?([a-zA-Z0-9\\s,]{3,28})(?:[.,!]|$)").find(clean)
            ?: Regex("(?i)\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+([a-zA-Z0-9\\s]{3,24}?)\\s*(?:ko\\s+hai|ko|hai)?(?:[.,!]|$)").find(clean))?.let { m ->
            val bday = m.groupValues[1].trim()
            if (bday.length >= 3 && bday.lowercase() !in setOf("kab", "kya", "today", "aaj", "tomorrow")) {
                rememberFact("User's birthday is $bday", AuraMemoryItem.CATEGORY_PERSONAL, topicKey = "user_birthday")
            }
        }

        // 4. City / Location
        Regex("(?i)\\b(?:i\\s+live\\s+in|i\\s+am\\s+from|main\\s+|mein\\s+)([a-zA-Z\\s]{2,26}?)\\s+(?:mein\\s+rehta\\s+hoon|mein\\s+rehti\\s+hoon|se\\s+hoon)(?:[.,!]|$)").find(clean)
            ?: Regex("(?i)\\b(?:i\\s+live\\s+in|my\\s+city\\s+is|my\\s+hometown\\s+is|mera\\s+shehar)\\s+([a-zA-Z\\s]{2,26})(?:[.,!]|$)").find(clean)?.let { m ->
                val loc = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (loc.length >= 3 && loc.lowercase() !in setOf("here", "there", "home", "india")) {
                    rememberFact("User lives in $loc", AuraMemoryItem.CATEGORY_PERSONAL, topicKey = "user_location")
                }
            }

        // 5. Coding Stack
        Regex("(?i)\\b(?:i\\s+code\\s+in|my\\s+coding\\s+stack\\s+is|my\\s+tech\\s+stack\\s+is)\\s+([a-zA-Z0-9#+.\\s]{2,30})(?:[.,!]|$)").find(clean)?.let { m ->
            val stack = m.groupValues[1].trim()
            if (!isPollutedMemory("User develops with $stack")) {
                rememberFact("User develops with $stack", AuraMemoryItem.CATEGORY_PROJECT, topicKey = "coding_stack")
            }
        }

        // 6. General Favorites / Likes / Dislikes / Preferences
        Regex("(?i)\\b(?:my\\s+favou?rite|mera\\s+favou?rite|mera\\s+pasandida)\\s+([a-zA-Z\\s]{2,20})\\s+(?:is|are|hai)\\s+([\\p{L}0-9\\s]{2,36})(?:[.,!]|$)").find(clean)?.let { m ->
            val topic = m.groupValues[1].trim().lowercase(Locale.ROOT)
            val valStr = m.groupValues[2].trim()
            if (topic.isNotBlank() && valStr.isNotBlank() && !valStr.equals("kya", true)) {
                rememberFact("User's favorite $topic is $valStr", AuraMemoryItem.CATEGORY_PREFERENCE, topicKey = "favorite_$topic")
            }
        }

        Regex("(?i)\\b(?:always\\s+remember\\s+i\\s+prefer|meri\\s+preference\\s+hai|mujhe\\s+hamesha|mujhe)\\s+([\\p{L}0-9\\s]{3,36}?)\\s+(?:pasand\\s+hai|prefer|chahiye\\s+hota\\s+hai)(?:[.,!]|$)").find(clean)?.let { m ->
            val pref = m.groupValues[1].trim()
            if (pref.length in 3..36) {
                rememberFact("User prefers $pref", AuraMemoryItem.CATEGORY_PREFERENCE, topicKey = "pref_${normalizeTopicKey(pref)}")
            }
        }

        Regex("(?i)\\b(?:i\\s+dislike|i\\s+hate|mujhe)\\s+([\\p{L}0-9\\s]{3,36}?)\\s*(?:nahi\\s+pasand|pasand\\s+nahi)(?:\\s+hai)?(?:[.,!]|$)").find(clean)?.let { m ->
            val disliked = m.groupValues[1].trim()
            if (disliked.length in 3..36) {
                rememberFact("User dislikes $disliked", AuraMemoryItem.CATEGORY_PREFERENCE, topicKey = "dislike_${normalizeTopicKey(disliked)}")
            }
        }

        // 7. Relationships
        (Regex("(?i)\\b([a-zA-Z]{2,20})\\s+is\\s+my\\s+(brother|sister|best\\s+friend|friend|mom|mother|dad|father|wife|husband|cousin|boss)\\b").find(clean)
            ?: Regex("(?i)\\b([a-zA-Z]{2,20})\\s+mera\\s+(bhai|dost|yaar|papa|mummy|boss)\\b").find(clean))?.let { m ->
            val person = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            val rel = m.groupValues[2].trim().lowercase(Locale.ROOT)
            if (person.lowercase() !in setOf("who", "he", "she", "this", "that", "kaun", "wo", "ye")) {
                rememberFact("$person is user's $rel", AuraMemoryItem.CATEGORY_CONTACT, topicKey = "contact_${person.lowercase()}")
            }
        }
    }

    internal fun shouldRunAiMemoryExtraction(userInput: String): Boolean {
        val trimmed = userInput.trim()
        if (trimmed.length < 18 || trimmed.length > 1500) return false
        if (HindiAsrNormalizer.isNoiseOrForeignHallucination(trimmed)) return false
        if (trimmed.contains("```") || trimmed.count { it == '\n' } > 16) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        // Skip greetings and short acknowledgements
        if (lower in setOf("hi", "hello", "hey", "ok", "okay", "haan", "theek hai", "acha", "yes", "no", "nahi", "bye", "thanks", "thank you")) {
            return false
        }
        // Always run if user explicitly asks to remember or shares personal / rule / plan markers
        val explicitMemorySignals = listOf(
            "remember", "yaad", "mera ", "meri ", "mere ", "my ", "i am ", "i'm ", "i live",
            "i work", "i like", "i love", "i hate", "i prefer", "pasand", "birthday", "janamdin",
            "favorite", "favourite", "exam", "meeting", "routine", "subah", "hamesha", "never ",
            "always ", "bhai", "dost", "papa", "mummy", "wife", "boss", "naam", "college", "office"
        )
        if (explicitMemorySignals.any { lower.contains(it) }) return true
        // Skip pure transient imperative device/navigation commands
        val transientOnly = Regex("(?i)^(open|close|band|kholo|chalu|scroll|swipe|volume|brightness|battery|pause|play|resume|next|previous|like|skip|screenshot|dial|call)\\b.*")
        if (transientOnly.matches(trimmed) && trimmed.split(Regex("\\s+")).size <= 8) return false
        return trimmed.split(Regex("\\s+")).size >= 6
    }

    /**
     * Runs local extraction immediately and fires a background Gemini Flash extractor on
     * complete Voice or Text Chat turns so important user facts, preferences, dates, and rules
     * are saved automatically without waiting for explicit "remember that" keywords.
     */
    fun processCompletedTurnAsync(
        context: Context,
        userInput: String,
        assistantResponse: String = "",
        isVoice: Boolean = true
    ) {
        init(context)
        val cleanUser = userInput.trim()
        if (cleanUser.isBlank()) return

        learnFromConversation(cleanUser)
        if (!shouldRunAiMemoryExtraction(cleanUser)) return

        val turnHash = "${cleanUser.lowercase(Locale.ROOT)}|${assistantResponse.take(80)}".hashCode()
        if (turnHash == lastExtractedTurnHash) return
        lastExtractedTurnHash = turnHash

        bgScope.launch {
            runCatching {
                extractWithGeminiFlash(cleanUser, assistantResponse, isVoice)
            }.onFailure { err ->
                Log.d(TAG, "Background auto-memory skipped/failed: ${err.message}")
            }
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
            append("You are an automatic long-term memory extractor for Aura AI (like ChatGPT/Gemini Memory).\n")
            append("Extract ONLY durable, important facts worth remembering across future conversations:\n")
            append("- Identity/personal info (name, city, profession, college, birthday, age)\n")
            append("- Persistent preferences, likes, dislikes, language/style rules, hobbies\n")
            append("- Important people/relationships (family, friends, colleagues)\n")
            append("- Upcoming deadlines/exams/appointments/important plans or explicit things the user asked to remember\n")
            append("- Daily routines or recurring habits\n")
            append("DO NOT save: greetings, one-off phone commands (scroll, volume, play song), temporary coding errors, or duplicates of already stored memories unless updating/correcting an old fact.\n\n")
            append("Existing stored memories:\n").append(existingSnapshot).append("\n\n")
            append("Channel: ").append(if (isVoice) "Live Voice" else "Text Chat").append('\n')
            append("User message: \"").append(userInput.take(900)).append("\"\n")
            if (assistantResponse.isNotBlank()) {
                append("Assistant reply: \"").append(assistantResponse.take(300)).append("\"\n")
            }
            append("\nRespond ONLY as valid JSON: ")
            append("{\"should_save\": boolean, \"memories\": [{\"fact\": \"concise third-person English fact\", \"category\": \"PERSONAL|PREFERENCE|CONTACT|ROUTINE|PROJECT|NOTE\", \"topic_key\": \"short_snake_case_key_or_empty\"}]}")
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().put("text", prompt)) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 220)
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
                val cleanJson = contentText
                    .removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
                val parsed = JSONObject(cleanJson)
                if (!parsed.optBoolean("should_save", false)) return
                val arr = parsed.optJSONArray("memories") ?: return
                for (i in 0 until arr.length().coerceAtMost(4)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val fact = obj.optString("fact", "").trim()
                    val cat = obj.optString("category", AuraMemoryItem.CATEGORY_NOTE)
                    val tKey = obj.optString("topic_key", "")
                    if (fact.length >= 6) {
                        rememberFact(fact, categoryOverride = cat, topicKey = tKey)
                    }
                }
                return
            }
        }
    }

    /**
     * Injected into Gemini Live system prompt.
     */
    fun getSystemPromptContext(userQuery: String = ""): String {
        val list = _memories.value.filterNot { isPollutedMemory(it.text) }
        if (list.isEmpty()) return ""

        val prioritized = selectRelevantMemories(list, userQuery, MAX_MEMORIES_IN_PROMPT, includeProjects = true)
        val sb = StringBuilder("\n## Persistent Memory Core (Background User Facts & Preferences):\n")
        prioritized.forEach { item ->
            sb.append("- [${item.category}] ${item.text}\n")
        }
        sb.append("Use these strictly for personalization and answering about user's saved facts; NEVER drag past projects into unrelated phone tasks or quizzes.\n\n")
        return sb.toString()
    }

    /**
     * Concise memory block for normal Text Chat prompts (excludes unrelated coding project noise).
     */
    fun getChatMemoryContext(userQuery: String = ""): String {
        val list = _memories.value.filterNot { isPollutedMemory(it.text) }
        if (list.isEmpty()) return ""
        val selected = selectRelevantMemories(list, userQuery, MAX_CHAT_MEMORIES_IN_PROMPT, includeProjects = false)
        if (selected.isEmpty()) return ""
        return buildString {
            append("## Remembered User Facts & Preferences (Persistent Auto-Memory)\n")
            selected.forEach { item ->
                append("- [${item.category}] ${item.text}\n")
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
                else -> 10
            }
            val textLower = item.text.lowercase(Locale.ROOT)
            val overlapScore = qTokens.count { textLower.contains(it) } * 25
            val recencyBonus = (item.timestamp / 3_600_000L).toInt()
            baseCatScore + overlapScore + (recencyBonus % 15)
        }.take(limit)
    }
}
