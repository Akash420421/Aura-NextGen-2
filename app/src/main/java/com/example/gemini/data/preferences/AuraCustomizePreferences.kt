package com.example.gemini.data.preferences

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AuraContact(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val relation: String = "Friend"
)

data class AuraCredential(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val account: String,
    val secret: String
)

data class AuraLocationSettings(
    val homeAddress: String = "",
    val workAddress: String = "",
    val useLiveGps: Boolean = true
)

object AuraCustomizePreferences {
    private const val PREFS_NAME = "aura_customize_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Personality
    fun getPersonality(context: Context): String {
        return getPrefs(context).getString("key_personality", "Jarvis") ?: "Jarvis"
    }

    fun setPersonality(context: Context, value: String) {
        getPrefs(context).edit().putString("key_personality", value).apply()
    }

    fun getCustomInstructions(context: Context): String {
        return getPrefs(context).getString("key_custom_instructions", "") ?: ""
    }

    fun setCustomInstructions(context: Context, instructions: String) {
        getPrefs(context).edit().putString("key_custom_instructions", instructions).apply()
    }

    // Memory
    fun getMemories(context: Context): List<String> {
        val raw = getPrefs(context).getString("key_memories", null) ?: return listOf(
            "Developer stack: Kotlin, Jetpack Compose, Termux",
            "Prefers clean, concise explanations without unnecessary boilerplate",
            "Primary timezone: Asia/Kolkata (IST)"
        )
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addMemory(context: Context, note: String) {
        if (note.isBlank()) return
        val current = getMemories(context).toMutableList()
        current.add(0, note.trim())
        saveMemories(context, current)
    }

    fun removeMemory(context: Context, index: Int) {
        val current = getMemories(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveMemories(context, current)
        }
    }

    fun clearMemories(context: Context) {
        saveMemories(context, emptyList())
    }

    private fun saveMemories(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        getPrefs(context).edit().putString("key_memories", arr.toString()).apply()
    }

    // Contacts
    fun getContacts(context: Context): List<AuraContact> {
        val raw = getPrefs(context).getString("key_contacts", null) ?: return listOf(
            AuraContact(name = "Mom", phone = "+91 98765 43210", relation = "Family"),
            AuraContact(name = "Home", phone = "+91 91234 56789", relation = "Emergency")
        )
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AuraContact>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AuraContact(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        phone = obj.optString("phone", ""),
                        relation = obj.optString("relation", "Friend")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addContact(context: Context, contact: AuraContact) {
        val current = getContacts(context).toMutableList()
        current.add(contact)
        saveContacts(context, current)
    }

    fun removeContact(context: Context, id: String) {
        val current = getContacts(context).filter { it.id != id }
        saveContacts(context, current)
    }

    private fun saveContacts(context: Context, list: List<AuraContact>) {
        val arr = JSONArray()
        list.forEach { c ->
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("phone", c.phone)
                put("relation", c.relation)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString("key_contacts", arr.toString()).apply()
    }

    // Credentials / Passwords Vault
    fun getCredentials(context: Context): List<AuraCredential> {
        val raw = getPrefs(context).getString("key_credentials", null) ?: return listOf(
            AuraCredential(title = "GitHub Token", account = "Akash420421", secret = "ghp_xxxxxxxxxxxxxxxxxxxx")
        )
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AuraCredential>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AuraCredential(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        account = obj.optString("account", ""),
                        secret = obj.optString("secret", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCredential(context: Context, cred: AuraCredential) {
        val current = getCredentials(context).toMutableList()
        current.add(cred)
        saveCredentials(context, current)
    }

    fun removeCredential(context: Context, id: String) {
        val current = getCredentials(context).filter { it.id != id }
        saveCredentials(context, current)
    }

    private fun saveCredentials(context: Context, list: List<AuraCredential>) {
        val arr = JSONArray()
        list.forEach { c ->
            val obj = JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("account", c.account)
                put("secret", c.secret)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString("key_credentials", arr.toString()).apply()
    }

    // Location
    fun getLocationSettings(context: Context): AuraLocationSettings {
        val prefs = getPrefs(context)
        return AuraLocationSettings(
            homeAddress = prefs.getString("key_loc_home", "Sector 14, Gurugram, India") ?: "",
            workAddress = prefs.getString("key_loc_work", "Cyber City, Gurugram, India") ?: "",
            useLiveGps = prefs.getBoolean("key_loc_gps", true)
        )
    }

    fun saveLocationSettings(context: Context, settings: AuraLocationSettings) {
        getPrefs(context).edit()
            .putString("key_loc_home", settings.homeAddress)
            .putString("key_loc_work", settings.workAddress)
            .putBoolean("key_loc_gps", settings.useLiveGps)
            .apply()
    }

    // Email
    fun getEmail(context: Context): String {
        return getPrefs(context).getString("key_email", "user@aura.ai") ?: "user@aura.ai"
    }

    fun setEmail(context: Context, email: String) {
        getPrefs(context).edit().putString("key_email", email).apply()
    }

    // Pinned Conversations
    fun getPinnedConversationIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet("key_pinned_conv_ids", emptySet()) ?: emptySet()
    }

    fun togglePinConversation(context: Context, conversationId: String): Boolean {
        val current = getPinnedConversationIds(context).toMutableSet()
        val isNowPinned = if (current.contains(conversationId)) {
            current.remove(conversationId)
            false
        } else {
            current.add(conversationId)
            true
        }
        getPrefs(context).edit().putStringSet("key_pinned_conv_ids", current).apply()
        return isNowPinned
    }
}
