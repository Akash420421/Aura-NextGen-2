package com.example.gemini.data.live

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.gemini.data.automation.ActivityLauncherHelper
import com.example.gemini.data.automation.AppLauncher
import com.example.gemini.data.automation.AutomationAccessibilityService
import com.example.gemini.data.automation.BuiltInChromeEngine
import com.example.gemini.data.automation.ContactCaller
import com.example.gemini.data.automation.PhoneControlManager
import com.example.gemini.data.automation.WhatsAppMessenger
import com.example.gemini.data.automation.YouTubeController
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalTerminalBridge
import com.example.gemini.data.vision.ScreenCaptureManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * AuraLiveVoiceManager: Central coordinator for the Part-7-powered Live Voice Engine.
 * Combines:
 * 1. Gemini Live duplex WebSocket with 24 native tools (<1s instant voice response)
 * 2. 16kHz AudioRecorder with dynamic hardware AEC, AGC, and Noise Suppressor
 * 3. 24kHz AudioPlayer with USAGE_MEDIA loudspeaker routing and instant barge-in flush
 * 4. Acoustic Echo Decay Protection (AEDP) to prevent echo-triggered self-interruption loops
 * 5. Screen Share video streaming (1 FPS 768px JPEG) + on-demand screen share prompt
 * 6. Contact Calling & WhatsApp Auto-Send automation
 * 7. Real terminal execution & project code file creation
 * 8. Real-time chat transcript recording (ChatContextBridge) & persistent memory (AuraMemoryManager)
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
    private var headsetRouter: HeadsetAudioRouter? = null
    private var phoneControlManager: PhoneControlManager? = null

    // Callback invoked when AI requests screen share permission from user
    var requestScreenShareCallback: (() -> Unit)? = null

    // Acoustic Echo Decay Protection (AEDP): suppresses mic chunks for 150ms post-speaker silence
    private val lastSpeakerActiveTimestamp = AtomicLong(0L)
    private const val AEDP_POST_SPEAKER_WINDOW_MS = 150L

    fun init(context: Context) {
        appContext = context.applicationContext
        AuraMemoryManager.init(context)
        phoneControlManager = PhoneControlManager(context.applicationContext)
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
        phoneControlManager = PhoneControlManager(context.applicationContext)

        val apiKey = JarvisPreferences.liveApiKey.value.trim().ifBlank {
            JarvisPreferences.ttsApiKey.value.trim()
        }
        if (apiKey.isBlank()) {
            _statusText.value = "Enter Gemini API Key in Settings"
            Log.e(TAG, "No API key configured in JarvisPreferences")
            Toast.makeText(context, "Please enter your Gemini API Key in Settings → Jarvis → Live API", Toast.LENGTH_LONG).show()
            return
        }

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        this.scope = scope

        _phase.value = Phase.CONNECTING
        _statusText.value = "Connecting to Gemini Live..."

        // Play high-tech sci-fi wake chime
        JarvisSoundEffects.playWakeSound()

        // Initialize Headset / Audio Router
        headsetRouter = HeadsetAudioRouter(context.applicationContext).apply { start() }

        // Initialize AudioPlayer
        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    _phase.value = Phase.SPEAKING
                    _statusText.value = "Jarvis speaking..."
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                } else if (_phase.value == Phase.SPEAKING) {
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                    _phase.value = Phase.LISTENING
                    _statusText.value = "Listening..."
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_phase.value == Phase.SPEAKING) {
                    _amplitude.value = amp
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                }
            }
        ).apply { start() }

        // Initialize AudioRecorder with AEDP protection
        audioRecorder = AudioRecorder { chunk, amp, _ ->
            val now = System.currentTimeMillis()
            val isAedpActive = (now - lastSpeakerActiveTimestamp.get()) < AEDP_POST_SPEAKER_WINDOW_MS
            val isAssistantSpeaking = _phase.value == Phase.SPEAKING

            if (_phase.value == Phase.LISTENING) {
                _amplitude.value = amp
            }

            // Suppress audio while assistant speaks unless headset is plugged in, to prevent acoustic echo loop
            val hasHeadset = headsetRouter?.isHeadsetConnected() == true
            if (!isAssistantSpeaking || hasHeadset) {
                if (!isAedpActive || hasHeadset) {
                    webSocket?.sendAudioChunk(chunk)
                }
            }
        }

        val model = JarvisPreferences.liveModel.value.ifBlank { GeminiConstants.DEFAULT_MODEL }
        val voice = JarvisPreferences.liveVoice.value.ifBlank { GeminiConstants.DEFAULT_VOICE }

        val ws = GeminiLiveWebSocket(
            apiKey = apiKey,
            model = model,
            voiceName = voice,
            systemPrompt = buildSystemPrompt(),
            listener = this
        )
        ws.promptProvider = { buildSystemPrompt() }
        webSocket = ws

        AuraLiveVoiceService.start(context)
        ws.connect(scope)
    }

    fun stopSession() {
        Log.i(TAG, "Stopping live voice session...")
        JarvisSoundEffects.playStandbySound()

        appContext?.let { AuraLiveVoiceService.stop(it) }

        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.release()
        audioPlayer = null

        headsetRouter?.release()
        headsetRouter = null

        webSocket?.disconnect()
        webSocket = null

        scope?.cancel()
        scope = null

        _phase.value = Phase.IDLE
        _amplitude.value = 0f
        _statusText.value = "Tap to Start"
    }

    fun sendScreenFrame(jpegBytes: ByteArray) {
        webSocket?.sendMediaChunk(jpegBytes)
    }

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are Jarvis / Aura, an ultra-advanced real-time AI companion living inside the Aura AI Android app.\n")
        sb.append("You have full control over phone automation, apps, media playback, WhatsApp, Chrome web navigation, screen inspection, code writing, and terminal execution.\n\n")

        sb.append("### CRITICAL SPOKEN LANGUAGE DIRECTIVE:\n")
        sb.append("- You MUST ALWAYS speak in conversational Hindi or Hinglish (Hindi mixed with natural English words as spoken in India).\n")
        sb.append("- Speak warmly, confidently, and keep your replies brief and direct (1-2 crisp sentences).\n")
        sb.append("- Ignore ambient background noise or unrelated room chatter completely.\n\n")

        sb.append("### HYBRID VOICE & AGENT ARCHITECTURE:\n")
        sb.append("1. NORMAL CONVERSATIONS (HANDLED BY YOU DIRECTLY IN VOICE):\n")
        sb.append("   - Casual talk, greetings, general questions, explanations, discussions, advice, and knowledge queries:\n")
        sb.append("   - You MUST answer IMMEDIATELY and DIRECTLY in voice (Hindi/Hinglish). Do NOT delegate normal conversations.\n\n")
        sb.append("2. COMPLEX TASKS & CREATING PROJECTS (START NEW CHAT TASK):\n")
        sb.append("   - Whenever the user asks you to:\n")
        sb.append("     * Build or create an app, website, UI, game, or coding project\n")
        sb.append("     * Host a local server (e.g. localhost:8080, python3 http server)\n")
        sb.append("     * Execute terminal commands, run bash scripts, or complex multi-step missions\n")
        sb.append("   - You MUST IMMEDIATELY call `start_new_chat_task(prompt=...)`!\n")
        sb.append("   - This instructs the app to spawn a new dedicated project chat and execute the creation there while your voice session stays fast and uninterrupted.\n")
        sb.append("   - Acknowledge warmly in voice (e.g. \"Haan bilkul! Main abhi ek new chat create karke ye website banana shuru kar raha hoon.\").\n\n")
        sb.append("3. CHECKING TASK / WEBSITE STATUS (ZERO SCREEN SHARE NEEDED):\n")
        sb.append("   - Whenever the user asks about progress or status (e.g. 'Website kitni bani?', 'Status kya hai?', 'Server start hua kya?', 'Kahan tak kaam hua?'):\n")
        sb.append("   - NEVER ask for screen share! DO NOT call `request_screen_share` for status!\n")
        sb.append("   - Call `check_task_status()` immediately. It inspects running background tasks, localhost web servers (ports 8080/3000), and code progress.\n")
        sb.append("   - Report the status back in voice naturally in Hindi/Hinglish in FIRST PERSON as if you are doing it (e.g. \"Main abhi HTML aur CSS structure likh raha hoon, localhost:8080 pe server live hai!\").\n\n")
        sb.append("4. UPDATING OR MODIFYING RUNNING TASKS:\n")
        sb.append("   - When the user asks to modify the website/app (e.g. 'usme dark mode add karo', 'button ka color red karo'):\n")
        sb.append("   - Call `send_task_update(instruction=...)`.\n\n")
        sb.append("5. INSPECTING APP STATE:\n")
        sb.append("   - When the user asks what tasks, chats, or servers are running in the app, call `inspect_app_state()`.\n\n")
        sb.append("6. SCREEN SHARE:\n")
        sb.append("   - ONLY call `request_screen_share` if the user EXPLICITLY asks to share or look at their device screen (e.g. 'meri screen dekho', 'screen share chalu karo'). Never for task status.\n\n")
        sb.append("7. FIRST-PERSON PERSONAL OWNERSHIP:\n")
        sb.append("   - NEVER say: \"Antigravity ka response aaya hai\", \"App ne ye reply kiya\", or \"Chat mein ye likha hai\". You ARE Jarvis / Aura, the app is your body. Speak with complete personal ownership!\n\n")

        sb.append("### ZERO-HALLUCINATION ACTION EXECUTION:\n")
        sb.append("- When asked to open an app -> ALWAYS call open_app(app_name).\n")
        sb.append("- When asked to open a website -> ALWAYS call open_website(urls=[\"...\"]).\n")
        sb.append("- When asked to play a song/video on YouTube -> ALWAYS call search_and_play_youtube(query=\"...\"). Never just open search without playing.\n")
        sb.append("- When asked to send a WhatsApp message -> ALWAYS call send_whatsapp_message(recipient_name=\"...\", message=\"...\").\n")
        sb.append("- When asked to call someone on phone -> ALWAYS call call_contact(contact_name=\"...\").\n")
        sb.append("- When asked to call someone on WhatsApp -> ALWAYS call whatsapp_call(recipient_name=\"...\", call_type=\"voice\" or \"video\").\n")
        sb.append("- When asked to adjust volume, brightness, or flashlight -> ALWAYS call set_volume, set_brightness, or set_flashlight.\n")
        sb.append("- When asked to create a website, calculator, or code project:\n")
        sb.append("  1. Call write_project_file to save the HTML/CSS/JS file.\n")
        sb.append("  2. Call execute_terminal_command to test or launch python3 -m http.server 8080.\n")
        sb.append("  3. Call open_website to preview it for the user.\n")
        sb.append("- When asked to look at screen or share screen -> call inspect_screen or request_screen_share.\n\n")

        sb.append(AuraMemoryManager.getSystemPromptContext())
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
            "CLOSED", "IDLE", "OFFLINE" -> {
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
        // Speech is handled directly over audio channel; do not inject raw fragments into text chat
    }

    override fun onUserTextReceived(textChunk: String) {
        _lastTranscript.value = textChunk
        AuraMemoryManager.learnFromConversation(textChunk)
    }

    override fun onInterrupted() {
        audioPlayer?.flush()
        _phase.value = Phase.LISTENING
        _statusText.value = "Listening..."
    }

    override fun onTurnCompleted() {
        audioPlayer?.notifyTurnComplete()
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
            val summary = result.optString("message", result.optString("status", "executed"))
            ChatContextBridge.recordToolExecution(name, summary)
            webSocket?.sendToolResponse(callId, name, result)
        }
    }

    // --- Native Tool Execution ---

    private suspend fun executeTool(name: String, args: JSONObject): JSONObject {
        val context = appContext ?: return JSONObject().put("status", "error").put("message", "Context unavailable")
        val phoneControl = phoneControlManager ?: PhoneControlManager(context)
        Log.i(TAG, "Executing tool '$name' with args: $args")

        return try {
            when (name) {
                "start_new_chat_task" -> {
                    val prompt = args.optString("prompt", "").trim()
                    if (prompt.isBlank()) {
                        JSONObject().put("status", "error").put("message", "Task prompt cannot be empty.")
                    } else {
                        withContext(Dispatchers.Main) {
                            _phase.value = Phase.THINKING
                            _statusText.value = "Starting in new chat..."
                        }
                        val reply = ChatContextBridge.startNewChatTask(prompt)
                        JSONObject().apply {
                            put("status", "success")
                            put("result", reply)
                            put("message", reply)
                        }
                    }
                }

                "check_task_status" -> {
                    val statusSummary = ChatContextBridge.checkActiveTaskStatus()
                    JSONObject().apply {
                        put("status", "success")
                        put("summary", statusSummary)
                        put("message", statusSummary)
                    }
                }

                "send_task_update" -> {
                    val instruction = args.optString("instruction", "").trim()
                    if (instruction.isBlank()) {
                        JSONObject().put("status", "error").put("message", "Instruction cannot be empty.")
                    } else {
                        val reply = ChatContextBridge.sendTaskUpdate(instruction)
                        JSONObject().apply {
                            put("status", "success")
                            put("result", reply)
                            put("message", reply)
                        }
                    }
                }

                "inspect_app_state" -> {
                    val appState = ChatContextBridge.inspectAppState()
                    JSONObject().apply {
                        put("status", "success")
                        put("app_state", appState)
                        put("message", appState)
                    }
                }

                "delegate_task_to_app_agent", "execute_in_app_chat" -> {
                    val prompt = args.optString("prompt", "").ifBlank { args.optString("task", "") }.trim()
                    if (prompt.isBlank()) {
                        JSONObject().put("status", "error").put("message", "Task prompt cannot be empty.")
                    } else {
                        withContext(Dispatchers.Main) {
                            _phase.value = Phase.THINKING
                            _statusText.value = "Delegating to agent..."
                        }
                        val reply = ChatContextBridge.startNewChatTask(prompt)
                        JSONObject().apply {
                            put("status", "success")
                            put("result", reply)
                            put("message", reply)
                        }
                    }
                }

                "open_app" -> {
                    val appName = args.optString("app_name", "").ifBlank { args.optString("name", "") }.trim()
                    val appNumber = if (args.has("app_number")) args.optInt("app_number") else null

                    if (appName.equals("aura", true) || appName.equals("aura ai", true) || appName.equals("antigem", true)) {
                        bringAuraToForeground(context)
                        JSONObject().put("status", "success").put("message", "Opened Aura AI and brought to foreground")
                    } else {
                        val directWebUrl = phoneControl.parseTargetUrl(appName)
                        if (directWebUrl != null) {
                            phoneControl.openUrlsInChromeTabs(listOf(directWebUrl))
                            JSONObject().put("status", "success").put("message", "Opened $directWebUrl in Chrome")
                        } else {
                            val launched = AppLauncher.launchApp(context, appName, appNumber)
                            JSONObject().put("status", if (launched) "success" else "error")
                                .put("message", if (launched) "Opened $appName app." else "Could not find $appName.")
                        }
                    }
                }

                "open_website" -> {
                    val targetUrls = mutableListOf<String>()
                    val jsonUrls = args.optJSONArray("urls")
                    if (jsonUrls != null) {
                        for (i in 0 until jsonUrls.length()) {
                            val u = jsonUrls.getString(i).trim()
                            if (u.isNotEmpty()) targetUrls.add(u)
                        }
                    }
                    val query = args.optString("query", "").trim()
                    if (query.isNotEmpty()) {
                        val extracted = phoneControl.extractUrlsFromText(query)
                        for (u in extracted) {
                            if (!targetUrls.contains(u)) targetUrls.add(u)
                        }
                        if (targetUrls.isEmpty()) {
                            val single = phoneControl.parseTargetUrl(query)
                            if (single != null) targetUrls.add(single)
                        }
                    }
                    if (targetUrls.isEmpty() && query.isNotBlank()) {
                        targetUrls.add("https://www.google.com/search?q=${Uri.encode(query)}")
                    }
                    val ok = phoneControl.openUrlsInChromeTabs(targetUrls)
                    JSONObject().put("status", if (ok) "success" else "error")
                        .put("message", if (ok) "Opened ${targetUrls.joinToString(", ")} in Chrome." else "Failed to open websites.")
                }

                "search_in_chrome" -> {
                    val query = args.optString("query", "").trim()
                    val targetUrl = phoneControl.parseTargetUrl(query)
                    val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                    val ok = phoneControl.openUrlsInChromeTabs(listOf(searchUrl))
                    JSONObject().put("status", if (ok) "success" else "error")
                        .put("message", if (ok) "Searched \"$query\" in Chrome." else "Could not open Chrome.")
                }

                "search_and_play_youtube" -> {
                    val query = args.optString("query", "").trim()
                    audioPlayer?.flush()
                    val playResult = YouTubeController.searchAndPlay(context, query)
                    JSONObject().put("status", if (playResult.success) "success" else "error")
                        .put("message", playResult.message ?: "Opened YouTube.")
                }

                "search_youtube" -> {
                    val query = args.optString("query", "").trim()
                    val result = YouTubeController.searchYouTube(context, query)
                    JSONObject().put("status", if (result.success) "success" else "error")
                        .put("message", result.message ?: "Showing search results.")
                }

                "media_playback_control" -> {
                    val action = args.optString("action", "play")
                    val ok = YouTubeController.sendMediaKey(context, action)
                    JSONObject().put("status", if (ok) "success" else "error")
                        .put("message", "Media action '$action' performed.")
                }

                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient_name", "").trim()
                    val message = args.optString("message", "").trim()
                    val appNumber = if (args.has("app_number")) args.optInt("app_number") else null
                    val confirmed = args.optBoolean("confirmed", true)
                    audioPlayer?.flush()

                    val waResult = WhatsAppMessenger.sendMessage(context, recipient, message, appNumber, confirmed)
                    when (waResult) {
                        is WhatsAppMessenger.SendResult.Success -> {
                            JSONObject().put("status", "success")
                                .put("sent", true)
                                .put("message", "Message sent to ${waResult.contactName} on WhatsApp.")
                        }
                        is WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                            JSONObject().put("status", "pending_confirmation")
                                .put("message", "Please confirm sending the message to ${waResult.contactName}.")
                        }
                        is WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                            JSONObject().put("status", "multiple_apps")
                                .put("message", "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                        }
                        is WhatsAppMessenger.SendResult.ContactNotFound -> {
                            JSONObject().put("status", "not_found")
                                .put("message", "Could not find contact ${waResult.name} in phone contacts.")
                        }
                        is WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                            JSONObject().put("status", "multiple_contacts")
                                .put("message", "Multiple contacts found: ${waResult.matches.joinToString(", ")}.")
                        }
                        is WhatsAppMessenger.SendResult.MissingPermission -> {
                            JSONObject().put("status", "missing_permission")
                                .put("message", "Contacts permission not granted.")
                        }
                        is WhatsAppMessenger.SendResult.Error -> {
                            JSONObject().put("status", "error").put("message", waResult.reason)
                        }
                    }
                }

                "whatsapp_call" -> {
                    val recipient = args.optString("recipient_name", "").trim()
                    val callType = args.optString("call_type", "voice").trim()
                    val appNumber = if (args.has("app_number")) args.optInt("app_number") else null
                    audioPlayer?.flush()

                    val callResult = WhatsAppMessenger.placeCall(context, recipient, callType, appNumber, true)
                    when (callResult) {
                        is WhatsAppMessenger.CallResult.Success -> {
                            JSONObject().put("status", "success")
                                .put("message", "Placing WhatsApp $callType call to ${callResult.contactName}.")
                        }
                        is WhatsAppMessenger.CallResult.ContactNotFound -> {
                            JSONObject().put("status", "not_found")
                                .put("message", "Could not find contact ${callResult.name}.")
                        }
                        else -> {
                            JSONObject().put("status", "error")
                                .put("message", "Could not place WhatsApp call.")
                        }
                    }
                }

                "call_contact" -> {
                    val contact = args.optString("contact_name", "").trim()
                    audioPlayer?.flush()
                    val callResult = ContactCaller.callContact(context, contact)
                    when (callResult) {
                        is ContactCaller.CallResult.Success -> {
                            JSONObject().put("status", "success")
                                .put("message", "Calling ${callResult.contact.name}.")
                        }
                        is ContactCaller.CallResult.NoMatch -> {
                            JSONObject().put("status", "not_found")
                                .put("message", "Could not find contact '$contact' in contacts.")
                        }
                        is ContactCaller.CallResult.MultipleMatches -> {
                            JSONObject().put("status", "multiple_matches")
                                .put("message", "Found multiple contacts matching '$contact'.")
                        }
                        is ContactCaller.CallResult.MissingContactsPermission -> {
                            JSONObject().put("status", "missing_permission")
                                .put("message", "Contacts permission is not granted.")
                        }
                        is ContactCaller.CallResult.MissingCallPermission -> {
                            JSONObject().put("status", "missing_permission")
                                .put("message", "Phone call permission is not granted.")
                        }
                        is ContactCaller.CallResult.CallFailed -> {
                            JSONObject().put("status", "error").put("message", "Could not place phone call.")
                        }
                    }
                }

                "set_volume" -> {
                    val action = args.optString("action", "").trim()
                    val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                    val msg = when {
                        action == "set" && pct != null -> phoneControl.setVolumePercent(pct)
                        action == "increase" -> phoneControl.adjustVolume(true)
                        action == "decrease" -> phoneControl.adjustVolume(false)
                        else -> "Volume unchanged."
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_brightness" -> {
                    val action = args.optString("action", "").trim()
                    val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                    val msg = when {
                        action == "set" && pct != null -> phoneControl.setBrightnessPercent(pct)
                        action == "increase" -> phoneControl.adjustBrightness(true)
                        action == "decrease" -> phoneControl.adjustBrightness(false)
                        else -> "Brightness unchanged."
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_flashlight" -> {
                    val enabled = args.optBoolean("enabled", true)
                    val msg = phoneControl.setFlashlight(enabled)
                    JSONObject().put("status", "success").put("message", msg)
                }

                "builtin_chrome_search" -> {
                    val query = args.optString("query", "").trim()
                    val extracted = BuiltInChromeEngine.searchAndExtract(query)
                    JSONObject().put("status", "success").put("message", extracted)
                }

                "click_ui_element" -> {
                    val target = args.optString("target_name", "").ifBlank { args.optString("text", "") }.trim()
                    val x = if (args.has("x")) args.optInt("x") else null
                    val y = if (args.has("y")) args.optInt("y") else null

                    val a11y = AutomationAccessibilityService.instance
                    val clicked = if (a11y != null) {
                        if (x != null && y != null) {
                            val dm = context.resources.displayMetrics
                            val realX = (x / 1000f) * dm.widthPixels
                            val realY = (y / 1000f) * dm.heightPixels
                            a11y.clickAt(realX, realY)
                        } else {
                            a11y.clickNodeWithText(target)
                        }
                    } else false

                    JSONObject().put("status", if (clicked) "success" else "error")
                        .put("message", if (clicked) "Tapped '$target'." else "Could not tap '$target'. Ensure Accessibility Service is enabled.")
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    val a11y = AutomationAccessibilityService.instance
                    val typed = a11y?.typeText(text) ?: false
                    JSONObject().put("status", if (typed) "success" else "error")
                        .put("message", if (typed) "Typed \"$text\"." else "Could not type. No active input focus.")
                }

                "scroll_screen" -> {
                    val dir = args.optString("direction", "down")
                    val a11y = AutomationAccessibilityService.instance
                    val scrolled = a11y?.performScroll(dir) ?: false
                    JSONObject().put("status", if (scrolled) "success" else "error")
                        .put("message", if (scrolled) "Scrolled $dir." else "Scroll failed.")
                }

                "press_key" -> {
                    val key = args.optString("key", "home")
                    val a11y = AutomationAccessibilityService.instance
                    val actionCode = when (key.lowercase()) {
                        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        else -> AccessibilityService.GLOBAL_ACTION_HOME
                    }
                    val ok = a11y?.performGlobalAction(actionCode) ?: false
                    JSONObject().put("status", if (ok) "success" else "error")
                        .put("message", "Pressed $key.")
                }

                "inspect_screen" -> {
                    val a11y = AutomationAccessibilityService.instance
                    val root = a11y?.getActiveRootNode()
                    val pkg = a11y?.foregroundPackage ?: root?.packageName?.toString() ?: "unknown"
                    val elements = mutableListOf<String>()

                    fun walk(node: AccessibilityNodeInfo, depth: Int) {
                        if (depth > 6 || elements.size > 25) return
                        val text = node.text?.toString()?.trim()
                        val desc = node.contentDescription?.toString()?.trim()
                        val label = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
                        if (label != null && label.length > 1 && !elements.contains(label)) {
                            elements.add(label)
                        }
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { walk(it, depth + 1) }
                        }
                    }

                    if (root != null) walk(root, 0)
                    JSONObject().put("status", "success")
                        .put("foreground_app", pkg)
                        .put("visible_elements", JSONArray(elements))
                        .put("screen_sharing_active", ScreenCaptureManager.getInstance().isSharing.value)
                }

                "request_screen_share" -> {
                    val scm = ScreenCaptureManager.getInstance()
                    if (scm.isSharing.value) {
                        JSONObject().put("status", "success").put("message", "Screen sharing is already active.")
                    } else {
                        withContext(Dispatchers.Main) {
                            requestScreenShareCallback?.invoke()
                        }
                        JSONObject().put("status", "prompted")
                            .put("message", "Prompted user with the screen share dialog on screen.")
                    }
                }

                "open_aura_app" -> {
                    bringAuraToForeground(context)
                    JSONObject().put("status", "success").put("message", "Brought Aura AI to foreground.")
                }

                "write_project_file" -> {
                    val pathStr = args.optString("path", "").trim()
                    val content = args.optString("content", "")
                    if (pathStr.isBlank()) {
                        JSONObject().put("status", "error").put("message", "path cannot be empty")
                    } else {
                        val baseDir = File("/data/user/0/com.termux/files/home/projects")
                        if (!baseDir.exists()) baseDir.mkdirs()
                        val targetFile = if (pathStr.startsWith("/")) File(pathStr) else File(baseDir, pathStr)
                        targetFile.parentFile?.mkdirs()
                        targetFile.writeText(content, Charsets.UTF_8)
                        JSONObject().apply {
                            put("status", "success")
                            put("path", targetFile.absolutePath)
                            put("bytes_written", content.length)
                            put("message", "File saved at ${targetFile.name}")
                        }
                    }
                }

                "read_project_file" -> {
                    val pathStr = args.optString("path", "").trim()
                    val baseDir = File("/data/user/0/com.termux/files/home/projects")
                    val targetFile = if (pathStr.startsWith("/")) File(pathStr) else File(baseDir, pathStr)
                    if (!targetFile.exists()) {
                        JSONObject().put("status", "error").put("message", "File not found: ${targetFile.absolutePath}")
                    } else {
                        val text = targetFile.readText(Charsets.UTF_8)
                        JSONObject().apply {
                            put("status", "success")
                            put("path", targetFile.absolutePath)
                            put("content", if (text.length > 25000) text.take(25000) + "\n...(truncated)" else text)
                        }
                    }
                }

                "execute_terminal_command" -> {
                    val command = args.optString("command", "").trim()
                    val workDir = args.optString("working_directory").takeIf { !it.isNullOrBlank() }
                        ?: "/data/user/0/com.termux/files/home/projects"

                    if (command.isBlank()) {
                        JSONObject().put("status", "error").put("message", "command cannot be empty")
                    } else {
                        try {
                            LocalTerminalBridge.instance.sendCommand(command = command)
                        } catch (_: Exception) {}

                        val res = LocalEnvironmentManager.executeCommand(
                            command = command,
                            context = context,
                            workingDir = workDir,
                            timeoutSeconds = 40
                        )
                        JSONObject().apply {
                            put("status", if (res.exitCode == 0) "success" else "error")
                            put("exit_code", res.exitCode)
                            put("output", res.output.ifBlank { "(executed successfully)" })
                            put("duration_ms", res.durationMs)
                        }
                    }
                }

                "task_completed" -> {
                    val summary = args.optString("summary", "Task completed.")
                    JSONObject().put("status", "success").put("message", summary)
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

    private fun bringAuraToForeground(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (intent != null) {
                ActivityLauncherHelper.startActivitySafely(context, intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring Aura AI to foreground: ${e.message}")
        }
    }
}
