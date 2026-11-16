package com.example.gemini.data.live

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val SESSION_RENEWAL_MS = 9 * 60 * 1000L // 9 minutes

        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(45, TimeUnit.SECONDS)
            .build()
    }

    private val client = sharedClient
    private var activeModel: String = if (model.isNotBlank()) model else "models/gemini-3.8-live"

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    var promptProvider: (() -> String)? = null

    fun isSessionReady(): Boolean = isConnected.get() && isSetupComplete.get()

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API Key is empty. Aborting connection.")
            listener.onError("Gemini API Key is missing. Please configure it in Settings.", isFatal = true, errorCode = "API_KEY_MISSING")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "$WS_BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live. Sending BidiSetup...")
                    isConnected.set(true)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CONFIGURING...")
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CLOSING")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CLOSED")
                    scheduleReconnectIfNeeded()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    listener.onError("Connection error: ${t.message ?: "Network failure"}", isFatal = false)
                    listener.onConnectionStateChanged("FAILED")
                    scheduleReconnectIfNeeded()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate WebSocket connection: ${e.message}", e)
            listener.onError("Failed to initiate connection: ${e.message}", isFatal = true)
            listener.onConnectionStateChanged("ERROR")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = promptProvider?.invoke() ?: systemPrompt
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", activeModel)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", effectivePrompt)))
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.6)
                })
                put("input_audio_transcription", JSONObject())
                put("output_audio_transcription", JSONObject())
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", buildFunctionDeclarations())
                }))
            })
        }

        try {
            ws.send(setup.toString())
            Log.d(TAG, "Setup message sent to Gemini Live")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
            listener.onError("Failed to send setup message: ${e.message}", isFatal = true)
        }
    }

    private fun buildFunctionDeclarations(): JSONArray = JSONArray().apply {
        // --- 1. In-App Control Tools ---
        put(tool("jarvis_open_screen", "Switch to an in-app screen.", listOf("screen"),
            "screen" to prop("string", "Target screen: chat, terminal, browser, settings, ide")))

        put(tool("voice_new_chat", "Starts a brand new clean chat session on screen while keeping voice conversation going.", emptyList(),
            "topic" to prop("string", "Optional topic or title for the new chat")))

        put(tool("jarvis_ide_list_projects", "List existing coding projects in Termux / local storage.", emptyList()))

        put(tool("jarvis_ide_open_project", "Open a project directory in the in-app IDE.", listOf("path"),
            "path" to prop("string", "Absolute or relative project path")))

        put(tool("jarvis_ide_open_file", "Open a specific file in the in-app Code Editor.", listOf("path"),
            "path" to prop("string", "File path to open")))

        put(tool("jarvis_set_model", "Change the active AI model for text chat (e.g. Gemini, Claude, GPT).", listOf("model"),
            "model" to prop("string", "Model name or ID")))

        // --- 2. Persistent Memory Tools (ChatGPT / Gemini style) ---
        put(tool("memory_save", "Permanently save a user fact, preference, stack detail or note into long-term memory.", listOf("fact"),
            "fact" to prop("string", "The exact fact or preference to remember"),
            "category" to prop("string", "Optional category: PREFERENCE, PROJECT, PERSONAL, CONTACT, NOTE")))

        put(tool("memory_recall", "Search through saved permanent memories.", listOf("query"),
            "query" to prop("string", "Search keyword or topic")))

        put(tool("memory_delete", "Delete a specific remembered item by its ID or keyword.", listOf("id_or_keyword"),
            "id_or_keyword" to prop("string", "ID or keyword of the memory to forget")))

        // --- 3. Phone OS Automation Tools (from Part 7 & AndroidAutomation) ---
        put(tool("open_app", "Opens an app installed on the user's phone (e.g. WhatsApp, YouTube, Settings, Camera).", listOf("app_name"),
            "app_name" to prop("string", "Name of the app to launch"),
            "app_number" to prop("integer", "Optional 1 or 2 for cloned/dual apps")))

        put(tool("send_whatsapp_message", "Automates sending a WhatsApp message to a contact hands-free.", listOf("recipient_name", "message_text"),
            "recipient_name" to prop("string", "Contact name as stored in user's phone contacts"),
            "message_text" to prop("string", "Exact text message to send"),
            "app_number" to prop("integer", "1 for WhatsApp, 2 for WhatsApp Business / Cloned"),
            "confirmed" to prop("boolean", "Set true to send immediately, false if asking confirmation first")))

        put(tool("click_ui_element", "Taps or clicks an element on the screen using Accessibility.", listOf("element_id_or_text"),
            "element_id_or_text" to prop("string", "Text or resource ID of the button/item to tap")))

        put(tool("type_text", "Directly injects text into the focused input field using ACTION_SET_TEXT without opening the keyboard.", listOf("text"),
            "text" to prop("string", "Text to inject")))

        put(tool("scroll_screen", "Scrolls the screen in a specified direction.", listOf("direction"),
            "direction" to prop("string", "down, up, left, or right")))

        put(tool("press_key", "Simulates pressing a system navigation key.", listOf("key_name"),
            "key_name" to prop("string", "home, back, recents, or enter")))

        put(tool("open_website", "Opens a website URL in the in-app browser or Chrome.", listOf("url"),
            "url" to prop("string", "URL to open (e.g. https://google.com)")))

        put(tool("search_in_chrome", "Searches the web via Google Chrome or the in-app browser.", listOf("query"),
            "query" to prop("string", "Search query")))

        put(tool("inspect_screen", "Reads the current UI node tree of the screen to observe elements.", emptyList(),
            "purpose" to prop("string", "What you are looking for on the screen")))
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val p = JSONObject()
                props.forEach { p.put(it.first, it.second) }
                put("properties", p)
                if (required.isNotEmpty()) {
                    val req = JSONArray()
                    required.forEach { req.put(it) }
                    put("required", req)
                }
            })
        }

    private fun prop(type: String, description: String): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Setup complete ack
            if (json.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setupComplete received!")
                isSetupComplete.set(true)
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewalTimer()
                return
            }

            // Server content (Audio, Text, Interruption, TurnComplete)
            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")

                if (sc.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled interruption (barge-in)")
                    listener.onInterrupted()
                }

                if (sc.has("modelTurn")) {
                    val mt = sc.getJSONObject("modelTurn")
                    val parts = mt.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // Audio chunk (24kHz PCM)
                        if (part.has("inlineData")) {
                            val id = part.getJSONObject("inlineData")
                            val dataBase64 = id.optString("data")
                            if (dataBase64.isNotBlank()) {
                                val pcmBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                                listener.onAudioDataReceived(pcmBytes)
                            }
                        }

                        // Text transcript
                        if (part.has("text")) {
                            val textChunk = part.optString("text")
                            if (textChunk.isNotBlank()) {
                                listener.onAssistantTextReceived(textChunk)
                            }
                        }
                    }
                }

                if (sc.optBoolean("turnComplete", false)) {
                    listener.onTurnCompleted()
                }
            }

            // Tool Call (Function Calling)
            if (json.has("toolCall")) {
                val tc = json.getJSONObject("toolCall")
                val fcs = tc.optJSONArray("functionCalls") ?: JSONArray()
                for (i in 0 until fcs.length()) {
                    val fc = fcs.getJSONObject(i)
                    val callId = fc.optString("id", "")
                    val name = fc.optString("name", "")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    Log.d(TAG, "Tool call received: $name ($callId) with args: $args")
                    listener.onToolCall(callId, name, args)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming Gemini Live message: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("tool_response", JSONObject().apply {
                    put("function_responses", JSONArray().put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", result)
                    }))
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent tool_response for $functionName: $result")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    private fun startSessionRenewalTimer() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(SESSION_RENEWAL_MS)
            if (isConnected.get() && !isManuallyStopped.get()) {
                Log.i(TAG, "9-minute session renewal: seamlessly refreshing WebSocket...")
                reconnect()
            }
        }
    }

    private fun scheduleReconnectIfNeeded() {
        if (isManuallyStopped.get()) return
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(2000)
            if (!isConnected.get() && !isManuallyStopped.get()) {
                Log.i(TAG, "Attempting WebSocket auto-reconnect...")
                initiateConnection()
            }
        }
    }

    fun reconnect() {
        disconnect()
        initiateConnection()
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        }
        webSocket = null
        isConnected.set(false)
        isSetupComplete.set(false)
        listener.onConnectionStateChanged("IDLE")
    }
}
