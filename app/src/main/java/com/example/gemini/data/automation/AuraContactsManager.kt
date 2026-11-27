package com.example.gemini.data.automation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages custom user-defined contacts with nicknames/aliases for WhatsApp and Phone calling.
 * Persists data to SharedPreferences in JSON format.
 */
object AuraContactsManager {

    private const val PREFS_NAME = "aura_custom_contacts"
    private const val KEY_CONTACTS = "contacts_json"

    data class ContactItem(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val number: String,
        val aliases: List<String> = emptyList(),
        val isWhatsApp: Boolean = true,
        val isPhone: Boolean = true,
        val autoReplyEnabled: Boolean = false
    )

    private val _contacts = MutableStateFlow<List<ContactItem>>(emptyList())
    val contacts: StateFlow<List<ContactItem>> = _contacts.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadContacts()
        }
    }

    private fun loadContacts() {
        val jsonStr = prefs?.getString(KEY_CONTACTS, null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ContactItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val aliasesArr = obj.optJSONArray("aliases")
                val aliases = mutableListOf<String>()
                if (aliasesArr != null) {
                    for (j in 0 until aliasesArr.length()) {
                        aliases.add(aliasesArr.getString(j))
                    }
                }
                list.add(
                    ContactItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        number = obj.getString("number"),
                        aliases = aliases,
                        isWhatsApp = obj.optBoolean("isWhatsApp", true),
                        isPhone = obj.optBoolean("isPhone", true),
                        autoReplyEnabled = obj.optBoolean("autoReplyEnabled", false)
                    )
                )
            }
            _contacts.value = list
        } catch (e: Exception) {
            android.util.Log.e("AuraContactsManager", "Failed to load contacts", e)
        }
    }

    private fun saveContacts() {
        try {
            val arr = JSONArray()
            for (item in _contacts.value) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("number", item.number)
                    put("isWhatsApp", item.isWhatsApp)
                    put("isPhone", item.isPhone)
                    put("autoReplyEnabled", item.autoReplyEnabled)
                    val aliasesArr = JSONArray()
                    item.aliases.forEach { aliasesArr.put(it) }
                    put("aliases", aliasesArr)
                }
                arr.put(obj)
            }
            prefs?.edit()?.putString(KEY_CONTACTS, arr.toString())?.apply()
        } catch (e: Exception) {
            android.util.Log.e("AuraContactsManager", "Failed to save contacts", e)
        }
    }

    fun addContact(
        name: String,
        number: String,
        aliases: List<String>,
        isWhatsApp: Boolean = true,
        isPhone: Boolean = true,
        autoReplyEnabled: Boolean = false
    ) {
        val newItem = ContactItem(
            name = name.trim(),
            number = number.trim(),
            aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() },
            isWhatsApp = isWhatsApp,
            isPhone = isPhone,
            autoReplyEnabled = autoReplyEnabled
        )
        _contacts.value = _contacts.value + newItem
        saveContacts()
    }

    fun updateContact(
        id: String,
        name: String,
        number: String,
        aliases: List<String>,
        isWhatsApp: Boolean,
        isPhone: Boolean,
        autoReplyEnabled: Boolean = false
    ) {
        _contacts.value = _contacts.value.map {
            if (it.id == id) {
                it.copy(
                    name = name.trim(),
                    number = number.trim(),
                    aliases = aliases.map { a -> a.trim() }.filter { a -> a.isNotEmpty() },
                    isWhatsApp = isWhatsApp,
                    isPhone = isPhone,
                    autoReplyEnabled = autoReplyEnabled
                )
            } else it
        }
        saveContacts()
    }

    fun deleteContact(id: String) {
        _contacts.value = _contacts.value.filter { it.id != id }
        saveContacts()
    }

    /**
     * Resolves matching contact from custom contacts by name or alias.
     */
    fun findCustomMatch(query: String): ContactItem? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return _contacts.value.firstOrNull { item ->
            item.name.lowercase() == q ||
                item.name.lowercase().contains(q) ||
                item.aliases.any { it.lowercase() == q || q.contains(it.lowercase()) }
        }
    }

    /**
     * Matches an incoming WhatsApp sender title against Aura's custom saved contacts
     * using exact/normalized comparisons to avoid false auto-reply matches.
     */
    fun findAutoReplyContactMatch(senderDisplay: String): ContactItem? {
        val normSender = WhatsAppAutoReplyHelper.normalizeSenderKey(senderDisplay)
        if (normSender.isBlank()) return null
        return _contacts.value.firstOrNull { item ->
            val normName = WhatsAppAutoReplyHelper.normalizeSenderKey(item.name)
            normName == normSender ||
                item.aliases.any { alias ->
                    val normAlias = WhatsAppAutoReplyHelper.normalizeSenderKey(alias)
                    normAlias.isNotBlank() && normAlias == normSender
                }
        }
    }
}
