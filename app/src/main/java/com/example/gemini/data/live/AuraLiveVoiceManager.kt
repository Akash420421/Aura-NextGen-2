package com.example.gemini.data.live

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.gemini.data.automation.AndroidAutomation
import com.example.gemini.data.automation.AutomationAccessibilityService
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisMcp
import com.example.gemini.data.jarvis.JarvisPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * AuraLiveVoiceManager: Central coordinator for the Next-Gen Live Voice Jarvis Engine.
 * Combines:
 * 1. Gemini Live WebSocket (bidirectional duplex streaming)
 * 2. 16kHz AudioRecorder (AEC + AGC + Noise Suppression)
 * 3. 24kHz AudioPlayer (Loudspeaker USAGE_MEDIA + Jitter buffer + Instant barge-in)
 * 4. ChatGPT / Gemini style Persistent Memory (AuraMemoryManager)
 * 5. Active Chat Continuity (ChatContextBridge)
 * 6. In-App Control (JarvisMcp) + OS Automation (AndroidAutomation / WhatsApp)
 */
object AuraLiveVoiceManager : GeminiLiveWebSocket.Listener {
    private const val TAG = "AuraLiveVoiceManager"

    enum class Phase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _statusText = MutableStateFlow("Tap to Start")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var webSocket: GeminiLiveWebSocket? = null
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        AuraMemoryManager.init(context)
    }

    fun isLive(): Boolean = _phase.value != Phase.IDLE

    fun toggleSession(context: Context) {
        if (isLive()) {
            stopSession()
        } else {
            startSession(context)
        }
    }

    fun startSession(context: Context) {
        if (isLive()) return
        appContext = context.applicationContext
        AuraMemoryManager.init(context)

        val apiKey = JarvisPreferences.liveApiKey.value.trim()
        if (apiKey.isBlank()) {
            _statusText.value = "Please enter Gemini Live API Key in Settings"
            Log.e(TAG, "No API key configured")
            return
        }

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        this.scope = scope

        _phase.value = Phase.CONNECTING
        _statusText.value = "Connecting to Gemini Live..."

        // Initialize AudioPlayer
        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    _phase.value = Phase.SPEAKING
                    _statusText.value = "Jarvis speaking..."
                } else if (_phase.value == Phase.SPEAKING) {
                    _phase.value = Phase.LISTENING
                    _statusText.value = "Listening..."
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_phase.value == Phase.SPEAKING) {
                    _amplitude.value = amp
                }
            }
        ).apply { start() }

        // Initialize AudioRecorder
        audioRecorder = AudioRecorder { chunk, amp ->
            if (_phase.value == Phase.LISTENING) {
                _amplitude.value = amp
            }
            webSocket?.sendAudioChunk(chunk)
        }

        val model = JarvisPreferences.liveModel.value.ifBlank { "models/gemini-3.8-live" }
        val voice = JarvisPreferences.liveVoice.value.ifBlank { "Puck" }

        val ws = GeminiLiveWebSocket(
            apiKey = apiKey,
            model = model,
            voiceName = voice,
            systemPrompt = buildComprehensiveSystemPrompt(),
            listener = this
        )
        ws.promptProvider = { buildComprehensiveSystemPrompt() }
        webSocket = ws

        AuraLiveVoiceService.start(context)
        ws.connect(scope)
    }

    fun stopSession() {
        Log.i(TAG, "Stopping live voice session...")
        appContext?.let { AuraLiveVoiceService.stop(it) }

        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.release()
        audioPlayer = null

        webSocket?.disconnect()
        webSocket = null

        scope?.cancel()
        scope = null

        _phase.value = Phase.IDLE
        _amplitude.value = 0f
        _statusText.value = "Tap to Start"
    }

    private fun buildComprehensiveSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are Jarvis / Aura, an ultra-advanced real-time AI companion living inside the Aura AI Android app.\n")
        sb.append("You have two major domains of authority:\n")
        sb.append("1. FULL INTERNAL APP CONTROL: You can switch screens, open files/projects in the code editor, create chats, and change models.\n")
        sb.append("2. ZERO-ERROR PHONE AUTOMATION: You can control the user's phone, send WhatsApp messages, tap UI elements, type text with direct injection, and launch apps.\n")
        sb.append("Speak in a natural, witty, concise, human-like voice (English, Hindi, or Hinglish depending on what the user speaks).\n")
        sb.append("Keep spoken answers brief and punchy. Never recite long code verbally; instead use jarvis_open_screen or send code to the chat screen.\n\n")

        // Ingest Persistent Memory Core
        sb.append(AuraMemoryManager.getSystemPromptContext())

        // Ingest Active Chat Continuity Context
        sb.append(ChatContextBridge.getActiveChatPromptContext())

        return sb.toString()
    }

    // --- GeminiLiveWebSocket.Listener Implementation ---

    override fun onConnectionStateChanged(status: String) {
        Log.d(TAG, "Live status: $status")
        when (status) {
            "LIVE" -> {
                _phase.value = Phase.LISTENING
                _statusText.value = "Listening..."
                scope?.let { audioRecorder?.start(it) }
            }
            "CONFIGURING...", "CONNECTING..." -> {
                _phase.value = Phase.CONNECTING
                _statusText.value = status
            }
            "CLOSED", "IDLE" -> {
                if (_phase.value != Phase.IDLE) {
                    _phase.value = Phase.IDLE
                    _statusText.value = "Tap to Start"
                }
            }
            else -> {
                _statusText.value = status
            }
        }
    }

    override fun onAudioDataReceived(pcmData: ByteArray) {
        audioPlayer?.enqueueAudio(pcmData)
    }

    override fun onAssistantTextReceived(textChunk: String) {
        _lastTranscript.value = textChunk
    }

    override fun onUserTextReceived(textChunk: String) {
        // Trigger automatic memory learning on what user says
        AuraMemoryManager.learnFromConversation(textChunk)
    }

    override fun onInterrupted() {
        // User barged in: instantly kill audio playback and listen
        audioPlayer?.stopPlayback()
        _phase.value = Phase.LISTENING
        _statusText.value = "Listening..."
    }

    override fun onTurnCompleted() {
        if (_phase.value != Phase.SPEAKING) {
            _phase.value = Phase.LISTENING
            _statusText.value = "Listening..."
        }
    }

    override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
        Log.e(TAG, "Gemini Live error: $message (fatal=$isFatal, code=$errorCode)")
        _statusText.value = message
        if (isFatal) {
            stopSession()
        }
    }

    override fun onToolCall(callId: String, name: String, args: JSONObject) {
        _phase.value = Phase.THINKING
        _statusText.value = "Executing $name..."

        scope?.launch(Dispatchers.IO) {
            val result = executeTool(name, args)
            webSocket?.sendToolResponse(callId, name, result)
        }
    }

    // --- Tool Execution Router ---

    private suspend fun executeTool(name: String, args: JSONObject): JSONObject {
        val context = appContext ?: return JSONObject().put("status", "error").put("message", "Context unavailable")
        Log.i(TAG, "Executing tool '$name' with args: $args")

        return try {
            when (name) {
                // --- 1. In-App Tools ---
                "jarvis_open_screen" -> {
                    val screen = args.optString("screen", "chat")
                    withContext(Dispatchers.Main) {
                        JarvisController.vm.requestViewMode(screen.uppercase())
                    }
                    JSONObject().put("status", "success").put("message", "Switched to screen $screen")
                }

                "voice_new_chat" -> {
                    val result = withContext(Dispatchers.Main) {
                        ChatContextBridge.startNewChat()
                    }
                    JSONObject().put("status", "success").put("message", result)
                }

                "jarvis_ide_list_projects" -> {
                    JarvisMcp.call(context, "jarvis_ide_list_projects", args)
                }

                "jarvis_ide_open_project" -> {
                    JarvisMcp.call(context, "jarvis_ide_open_project", args)
                }

                "jarvis_ide_open_file" -> {
                    JarvisMcp.call(context, "jarvis_ide_open_file", args)
                }

                "jarvis_set_model" -> {
                    JarvisMcp.call(context, "jarvis_set_model", args)
                }

                // --- 2. Memory Tools ---
                "memory_save" -> {
                    val fact = args.optString("fact", "")
                    val category = args.optString("category").takeIf { !it.isNullOrBlank() }
                    val msg = AuraMemoryManager.rememberFact(fact, category)
                    JSONObject().put("status", "success").put("message", msg)
                }

                "memory_delete" -> {
                    val idOrKey = args.optString("id_or_keyword", "")
                    val deleted = AuraMemoryManager.deleteMemory(idOrKey)
                    JSONObject().put("status", if (deleted) "success" else "not_found")
                }

                "memory_recall" -> {
                    val query = args.optString("query", "").lowercase()
                    val matches = AuraMemoryManager.memories.value.filter {
                        it.text.lowercase().contains(query) || it.category.lowercase().contains(query)
                    }
                    JSONObject().put("status", "success").put("count", matches.size)
                        .put("results", org.json.JSONArray().apply {
                            matches.forEach { put(it.text) }
                        })
                }

                // --- 3. OS Automation Tools ---
                "open_app" -> {
                    val appName = args.optString("app_name", "")
                    val res = AndroidAutomation.call(context, "android_launch_app", JSONObject().apply {
                        put("app", appName)
                    })
                    res
                }

                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient_name", "")
                    val message = args.optString("message_text", "")
                    val appNumber = args.optInt("app_number", 1)
                    val confirmed = args.optBoolean("confirmed", true)

                    sendWhatsAppMessage(context, recipient, message, appNumber, confirmed)
                }

                "click_ui_element" -> {
                    val target = args.optString("element_id_or_text", "")
                    AndroidAutomation.call(context, "android_tap", JSONObject().apply {
                        put("text", target)
                    })
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    AndroidAutomation.call(context, "android_type_text", JSONObject().apply {
                        put("text", text)
                    })
                }

                "scroll_screen" -> {
                    val dir = args.optString("direction", "down")
                    AndroidAutomation.call(context, "android_scroll", JSONObject().apply {
                        put("direction", dir)
                    })
                }

                "press_key" -> {
                    val key = args.optString("key_name", "home")
                    AndroidAutomation.call(context, "android_global_action", JSONObject().apply {
                        put("action", key)
                    })
                }

                "open_website" -> {
                    val url = args.optString("url", "https://google.com")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    JSONObject().put("status", "success").put("message", "Opened $url")
                }

                "search_in_chrome" -> {
                    val q = args.optString("query", "")
                    val url = "https://www.google.com/search?q=" + Uri.encode(q)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    JSONObject().put("status", "success").put("message", "Searched for $q")
                }

                "inspect_screen" -> {
                    AndroidAutomation.call(context, "android_get_ui_tree", JSONObject())
                }

                else -> {
                    JSONObject().put("status", "error").put("message", "Unknown tool: $name")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error ($name): ${e.message}", e)
            JSONObject().put("status", "error").put("message", e.message ?: "Execution failed")
        }
    }

    private fun sendWhatsAppMessage(
        context: Context,
        recipient: String,
        message: String,
        appNumber: Int,
        confirmed: Boolean
    ): JSONObject {
        // Resolve contact phone number
        val digits = resolveContactPhoneNumber(context, recipient)
        if (digits.isNullOrBlank()) {
            return JSONObject().put("status", "error").put("message", "Could not find contact '$recipient' in your phone contacts.")
        }

        var formattedNumber = digits.filter { it.isDigit() }
        if (formattedNumber.length == 10) {
            formattedNumber = "91$formattedNumber"
        } else if (formattedNumber.startsWith("0") && formattedNumber.length == 11) {
            formattedNumber = "91" + formattedNumber.substring(1)
        }

        val encoded = Uri.encode(message)
        val deepLink = "https://api.whatsapp.com/send?phone=$formattedNumber&text=$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setPackage(if (appNumber == 2) "com.whatsapp.w4b" else "com.whatsapp")
        }

        try {
            context.startActivity(intent)
            scheduleWhatsAppAutoSend()
            return JSONObject().put("status", "success").put("message", "Message sent to $recipient on WhatsApp.")
        } catch (e: Exception) {
            return try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallbackIntent)
                scheduleWhatsAppAutoSend()
                JSONObject().put("status", "success").put("message", "Message sent via WhatsApp.")
            } catch (e2: Exception) {
                JSONObject().put("status", "error").put("message", "Could not open WhatsApp: ${e2.message}")
            }
        }
    }

    private fun scheduleWhatsAppAutoSend() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val svc = AutomationAccessibilityService.instance
                val root = svc?.rootInActiveWindow
                if (root != null) {
                    val sendNode = findWhatsAppSendButton(root)
                    if (sendNode != null) {
                        sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Tapped WhatsApp send button via Accessibility!")
                        return
                    }
                }
                attempts++
                if (attempts < 12) {
                    handler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun findWhatsAppSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc == "send" || desc == "send message" || text == "send" || viewId.endsWith(":id/send")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWhatsAppSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun resolveContactPhoneNumber(context: Context, name: String): String? {
        val digitsOnly = name.filter { it.isDigit() }
        if (digitsOnly.length >= 10) {
            return digitsOnly
        }

        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "READ_CONTACTS permission not granted. Cannot look up contact '$name'.")
            return null
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    return cursor.getString(numberIndex)
                }
            }
        }
        return null
    }
}
