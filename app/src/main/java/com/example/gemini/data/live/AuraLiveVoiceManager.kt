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
import com.example.gemini.data.vision.VisualTaskOrchestrator
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

    enum class Phase { IDLE, CONNECTING, IDLE_READY, LISTENING, THINKING, SPEAKING, WORKING, MUTED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _statusText = MutableStateFlow("Tap to Start")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _spokenSentence = MutableStateFlow("")
    val spokenSentence: StateFlow<String> = _spokenSentence.asStateFlow()

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
    private val lastUserSpeechTimestamp = AtomicLong(0L)
    private val speechStartTimestamp = AtomicLong(0L)
    private const val VAD_SPEECH_DEBOUNCE_MS = 120L
    private const val VAD_SILENCE_HYSTERESIS_MS = 500L

    fun init(context: Context) {
        appContext = context.applicationContext
        AuraMemoryManager.init(context)
        VoiceSessionStore.init(context)
        phoneControlManager = PhoneControlManager(context.applicationContext)
    }

    fun isLive(): Boolean = _phase.value != Phase.IDLE

    fun toggleMute() {
        if (_phase.value == Phase.MUTED) {
            unmute()
        } else if (_phase.value != Phase.IDLE && _phase.value != Phase.CONNECTING) {
            mute()
        }
    }

    fun mute() {
        if (isLive() && _phase.value != Phase.MUTED) {
            _phase.value = Phase.MUTED
            _statusText.value = "Muted"
            _amplitude.value = 0f
            audioPlayer?.flush()
        }
    }

    fun unmute() {
        if (_phase.value == Phase.MUTED) {
            _phase.value = Phase.IDLE_READY
            _statusText.value = "Speak to ask"
        }
    }

    fun handleActionTap(context: Context) {
        if (!isLive()) {
            startSession(context)
        } else {
            toggleMute()
        }
    }

    fun handleActionDoubleTap(context: Context) {
        if (isLive()) {
            stopSession()
            Toast.makeText(context, "Voice session ended", Toast.LENGTH_SHORT).show()
        }
    }

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
        _spokenSentence.value = ""
        ChatContextBridge.startVoiceSessionConversation()

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
                    _statusText.value = "Speaking..."
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                } else if (_phase.value == Phase.SPEAKING) {
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                    _phase.value = Phase.IDLE_READY
                    _statusText.value = "Speak to ask"
                    _amplitude.value = 0f
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_phase.value == Phase.SPEAKING) {
                    _amplitude.value = amp
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                }
            }
        ).apply { start() }

        // Initialize AudioRecorder with AEDP protection, debounce, hysteresis & mute support
        audioRecorder = AudioRecorder { chunk, amp, _ ->
            val now = System.currentTimeMillis()
            val isAedpActive = (now - lastSpeakerActiveTimestamp.get()) < AEDP_POST_SPEAKER_WINDOW_MS
            val isAssistantSpeaking = _phase.value == Phase.SPEAKING
            val isMuted = _phase.value == Phase.MUTED
            val currentPhase = _phase.value

            val isVoiceDetected = amp >= 0.08f
            if (!isMuted && !isAssistantSpeaking && !isAedpActive && currentPhase != Phase.WORKING && currentPhase != Phase.CONNECTING) {
                if (isVoiceDetected) {
                    lastUserSpeechTimestamp.set(now)
                    if (currentPhase == Phase.IDLE_READY || currentPhase == Phase.THINKING) {
                        val start = speechStartTimestamp.get()
                        if (start == 0L) {
                            speechStartTimestamp.set(now)
                        } else if (now - start >= VAD_SPEECH_DEBOUNCE_MS) {
                            _phase.value = Phase.LISTENING
                            _statusText.value = "Listening..."
                            _amplitude.value = amp
                        }
                    } else if (currentPhase == Phase.LISTENING) {
                        _amplitude.value = amp
                    }
                } else {
                    speechStartTimestamp.set(0L)
                    if (currentPhase == Phase.LISTENING) {
                        if (now - lastUserSpeechTimestamp.get() >= VAD_SILENCE_HYSTERESIS_MS) {
                            _phase.value = Phase.THINKING
                            _statusText.value = "Thinking..."
                            _amplitude.value = 0f
                        } else {
                            _amplitude.value = 0f
                        }
                    } else {
                        _amplitude.value = 0f
                    }
                }
            } else {
                speechStartTimestamp.set(0L)
                if (!isAssistantSpeaking) {
                    _amplitude.value = 0f
                }
            }

            // Suppress audio when muted or while assistant speaks (unless headset is connected)
            if (!isMuted && (!isAssistantSpeaking || headsetRouter?.isHeadsetConnected() == true)) {
                if (!isAedpActive || headsetRouter?.isHeadsetConnected() == true) {
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
        _spokenSentence.value = ""
        ChatContextBridge.endVoiceSessionConversation()
    }

    fun sendScreenFrame(jpegBytes: ByteArray) {
        webSocket?.sendMediaChunk(jpegBytes)
    }

    fun sendImmediateVideoFrame(jpegBytes: ByteArray) {
        sendScreenFrame(jpegBytes)
    }

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are Jarvis / Aura, an ultra-advanced real-time AI companion living inside the Aura AI Android app.\n")
        sb.append("You have full control over phone automation, apps, media playback, WhatsApp, Chrome web navigation, screen inspection, code writing, and terminal execution.\n\n")

        sb.append("### CRITICAL SPOKEN LANGUAGE DIRECTIVE:\n")
        sb.append("- You MUST ALWAYS speak in conversational Hindi or Hinglish (Hindi mixed with natural English words as spoken in India).\n")
        sb.append("- Speak warmly, confidently, and keep your replies brief and direct (1-2 crisp sentences).\n")
        sb.append("- Ignore ambient background noise or unrelated room chatter completely.\n\n")

        sb.append("### ACCESSIBILITY-FIRST POLICY (ZERO SCREEN SHARE NEEDED):\n")
        sb.append("- NEVER ask the user for Screen Share to read text, messages, chat bubbles, buttons, menus, notifications, or type into text boxes!\n")
        sb.append("- The phone accessibility engine (`inspect_screen`) has complete 100% real-time access to foreground app screens, messages, and UI elements without needing screen share.\n")
        sb.append("- When the user asks: 'screen par kya hai?', 'kya message aaya hai?', 'maine kya message bheja?', 'screen read karo', 'chat mein kya likha hai?' or asks about ANY on-screen content:\n")
        sb.append("  -> YOU MUST IMMEDIATELY CALL `inspect_screen()`!\n")
        sb.append("- ZERO GUESSING RULE: NEVER answer what is on screen or what message was sent from previous conversation memory or guesswork! ALWAYS call `inspect_screen()`, read the live UI elements, and report what is actually on screen. If the text is genuinely not exposed by the app, say so honestly.\n")
        sb.append("- When the user asks you to write, inject, or type text in ANY app (Telegram, WhatsApp, Chrome, Notes, Search, etc.): IMMEDIATELY call `type_text(text=\"...\", submit=true/false)`! DO NOT ask permissions! DO NOT ask for screen share! DO NOT try to type via virtual keyboard keys! Text is injected directly, instantly, and cleanly into the target input field or search bar.\n")
        sb.append("- When the user asks to click, open, or scroll: ALWAYS call `click_ui_element`, `open_app`, or `scroll_screen` directly without asking for screen share!\n")
        sb.append("- SCREEN SHARE ONLY AS LAST RESORT: ONLY call `request_screen_share` if:\n")
        sb.append("  1) The user EXPLICITLY commands 'screen share karo' or 'start screen share', OR\n")
        sb.append("  2) The user asks about purely visual media (photos, camera feed, drawings, colors) with zero accessible text, OR\n")
        sb.append("  3) `inspect_screen` explicitly returned that the screen is blank/inaccessible.\n\n")

        sb.append("### CLOSING APPS & RECENTS (3 MODES):\n")
        sb.append("- When asked to close an app or dismiss from recents (e.g. 'Chrome close karo', 'app band karo', 'recents se hatao'): ALWAYS call `close_app(app_name=\"...\", remove_from_recents=true)`.\n")
        sb.append("- When asked to clear recents via swipe or one-by-one (e.g. 'swipe karke hatao', 'ek ek karke hata do'): ALWAYS call `close_app(clear_all_recents=true, mode=\"swipe_clear_all\")`.\n")
        sb.append("- When asked to clear all recents in general (e.g. 'sab apps hata do', 'recents clear all karo'): ALWAYS call `close_app(clear_all_recents=true, mode=\"clear_all\")`.\n\n")

        sb.append("### MANDATORY ACTION TRUTHFULNESS & VERIFICATION (NEVER LIE OR FALSELY CONFIRM):\n")
        sb.append("- NEVER tell the user an action succeeded if the tool returned status 'error', 'partial', or status 'FAILED'!\n")
        sb.append("- If `close_app` returns status 'error' or 'FAILED' (e.g. apps could not be cleared or cards are still visible), truthfully tell the user in Hindi/Hinglish that the apps could not be cleared (e.g. 'Recents open ho gaya lekin apps clear nahi ho paaye, screen par abhi bhi cards dikh rahe hain'). NEVER claim 'Saare apps remove kar diye' or 'Chrome close ho gaya' when it failed!\n")
        sb.append("- If `close_app` returns 'partial' (e.g. locked apps remain), inform the user honestly that locked apps ki wajah se kuch apps nahi hate.\n")
        sb.append("- Report EXACTLY what happened based on the tool's verified result message!\n\n")

        sb.append("### IN-BUILT BROWSER VS EXTERNAL CHROME (STRICT DIRECTIVE):\n")
        sb.append("- Aura has its own full-featured In-Built Web Browser (`open_in_app_browser`).\n")
        sb.append("- Whenever the user asks to open YouTube, visit a website, browse a webpage, or view a link (e.g. 'in-built browser mein YouTube open karo', 'open youtube in browser', 'google kholo', 'github open karo', 'website open karo', 'search on browser'):\n")
        sb.append("  -> ALWAYS call `open_in_app_browser(url=...)`! It opens the page inside Aura's In-Built Browser immediately.\n")
        sb.append("- Launch external Google Chrome ONLY IF the user explicitly says 'Chrome mein kholo' or 'external browser mein open karo'. Never hijack default web browsing to Chrome!\n\n")

        sb.append("### OPENING SYSTEM APPS & DIALER:\n")
        sb.append("- When asked to open phone dialer, phone app, or calling app (e.g. 'phone dialer kholo', 'calling app open karo', 'phone open karo'): ALWAYS call `open_app(app_name=\"phone\")` or `call_contact(contact_name=\"...\")`! DO NOT open PhonePe! DO NOT open Chrome!\n")
        sb.append("- When asked to open calculator, camera, settings, or gallery: call `open_app(app_name=\"...\")`.\n\n")

        sb.append("### HYBRID VOICE & AGENT ARCHITECTURE:\n")
        sb.append("1. NORMAL CONVERSATIONS & CAPABILITY QUESTIONS (ANSWER DIRECTLY IN VOICE):\n")
        sb.append("   - Casual talk, greetings, general questions, explanations, discussions, advice, and asking what you can do:\n")
        sb.append("   - You MUST answer IMMEDIATELY and DIRECTLY in voice (Hindi/Hinglish).\n")
        sb.append("   - When asked what you can do (e.g. 'tum kya kya kar sakte ho?', 'kya kar sakte ho?'): Answer naturally in voice describing phone automation, playing music, web search, and coding capabilities. DO NOT CALL ANY TOOLS! DO NOT START BUILDING OR CREATING ANYTHING!\n")
        sb.append("   - NEVER boast or mention internal app features! NEVER say: 'main new chat shuru kar sakta hoon', 'main new chat create karta hoon', or 'task delegate karta hoon'. All internal workflows are completely SILENT to the user!\n\n")

        sb.append("2. COMPLEX CODING & PROJECT CREATION (ONLY WHEN EXPLICITLY ORDERED):\n")
        sb.append("   - STRICT CONFIRMATION RULE: ONLY call `start_new_chat_task(prompt=...)` if the user EXPLICITLY and UNAMBIGUOUSLY commands you to build, create, or code something right now (e.g. 'ek website banao', 'landing page create karo', 'python script likho')!\n")
        sb.append("   - NEVER call this tool when answering questions about what you can do, or during greetings, or on ambient audio.\n")
        sb.append("   - Speak a brief confirmation in first-person (e.g. 'Haan bilkul sir, main abhi ye website banana shuru karta hoon.'). Do NOT mention new chats.\n")
        sb.append("   - NEVER re-send or repeat a task.\n\n")

        sb.append("3. CHECKING TASK / WEBSITE STATUS (ZERO SCREEN SHARE NEEDED):\n")
        sb.append("   - Whenever the user asks about progress or status (e.g. 'Website kitni bani?', 'Status kya hai?', 'Server start hua kya?', 'Kahan tak kaam hua?'):\n")
        sb.append("   - NEVER ask for screen share! DO NOT call `request_screen_share` for status!\n")
        sb.append("   - Call `check_task_status()` immediately. It inspects running background tasks, localhost web servers (ports 8080/3000), and code progress.\n")
        sb.append("   - Report the status back in voice naturally in Hindi/Hinglish in FIRST PERSON as if you are doing it.\n\n")

        sb.append("4. OPENING VOICE CHATS & CREATING NEW CHATS ON SCREEN:\n")
        sb.append("   - When the user asks: 'new chat open karo', 'nayi chat shuru karo', 'start a new chat', 'open new chat', 'ek nayi chat banao', 'clear chat and start new' -> Call `create_new_chat()`. NEVER tell the user to manually click the new chat button or that you cannot open a new chat. You have direct control over Aura's chat interface!\n")
        sb.append("   - When the user asks: 'live chat open karo', 'chat dikhao', 'open live chat', 'hamari chat kholo', 'current chat open karo', 'jo baat chal rahi hai wo chat dikhao', 'live chat dikhao' -> Call `open_live_chat()` (or `open_current_voice_chat()`).\n")
        sb.append("   - When the user asks: 'pichhli chat kholo', 'previous chat open karo' -> Call `open_previous_voice_chat()`.\n\n")

        sb.append("5. UPDATING OR MODIFYING RUNNING TASKS:\n")
        sb.append("   - When the user asks to modify the website/app (e.g. 'usme dark mode add karo', 'button ka color red karo'):\n")
        sb.append("   - Call `send_task_update(instruction=...)`.\n\n")

        sb.append("6. INSPECTING APP STATE:\n")
        sb.append("   - When the user asks what tasks, chats, or servers are running in the app, call `inspect_app_state()`.\n\n")

        sb.append("7. SCREEN SHARE:\n")
        sb.append("   - ONLY call `request_screen_share` if the user EXPLICITLY asks to share or look at their device screen (e.g. 'meri screen dekho', 'screen share chalu karo'). Never for task status.\n\n")

        sb.append("8. FIRST-PERSON PERSONAL OWNERSHIP:\n")
        sb.append("   - NEVER say: 'Antigravity ka response aaya hai', 'App ne ye reply kiya', or 'Chat mein ye likha hai'. You ARE Jarvis / Aura, the app is your body. Speak with complete personal ownership!\n\n")

        sb.append("### ZERO-HALLUCINATION ACTION EXECUTION:\n")
        sb.append("- When asked to open an app -> ALWAYS call open_app(app_name).\n")
        sb.append("- When asked to open a website or browse -> ALWAYS call open_in_app_browser(url=\"...\").\n")
        sb.append("- When asked to play a song/video on YouTube -> ALWAYS call search_and_play_youtube(query=\"...\"). Never just open search without playing.\n")
        sb.append("- When asked to send a WhatsApp message to a contact -> ALWAYS call send_whatsapp_message(recipient_name=\"...\", message=\"...\").\n")
        sb.append("- When asked to call someone on phone -> ALWAYS call call_contact(contact_name=\"...\").\n")
        sb.append("- When asked to call someone on WhatsApp -> ALWAYS call whatsapp_call(recipient_name=\"...\", call_type=\"voice\" or \"video\").\n")
        sb.append("- When asked to adjust volume, brightness, or flashlight -> ALWAYS call set_volume, set_brightness, or set_flashlight.\n")
        sb.append("- When asked to scroll (e.g. 'scroll karo', 'neeche scroll karo', 'upar karo') -> ALWAYS call scroll_screen(direction=\"down\" or \"up\").\n")
        sb.append("- When asked to tap/click an item, video, link, or card by ordinal (e.g. 'dusri wali video chalao', 'second video', 'pehli wali', 'third item') -> ALWAYS call click_ui_element(target_name=\"dusri video\"). You can also call inspect_screen() first to see numbered items [#1], [#2] and click the exact card or title.\n")
        sb.append("- When asked to go back or home -> ALWAYS call press_key(key=\"back\" or \"home\").\n")
        sb.append("- When asked to type on the active screen -> ALWAYS call type_text(text=\"...\", submit=true).\n")
        sb.append("- When asked to create a website, calculator, or code project:\n")
        sb.append("  1. Call write_project_file to save the HTML/CSS/JS file.\n")
        sb.append("  2. Call execute_terminal_command to test or launch python3 -m http.server 8080.\n")
        sb.append("  3. Call open_website to preview it for the user.\n")
        sb.append("- When asked to look at screen or what is on screen -> ALWAYS call inspect_screen().\n\n")

        sb.append(AuraMemoryManager.getSystemPromptContext())
        sb.append(ChatContextBridge.getActiveChatPromptContext())

        return sb.toString()
    }

    // --- GeminiLiveWebSocket.Listener Implementation ---

    override fun onConnectionStateChanged(status: String) {
        Log.d(TAG, "Live status: $status")
        when (status) {
            "LIVE" -> {
                _phase.value = Phase.IDLE_READY
                _statusText.value = "Speak to ask"
                scope?.let { audioRecorder?.start(it) }
            }
            "CONFIGURING...", "CONNECTING..." -> {
                _phase.value = Phase.CONNECTING
                _statusText.value = "Connecting..."
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
        _spokenSentence.value = (_spokenSentence.value + textChunk).trimStart()
        ChatContextBridge.recordVoiceAssistantTurn(textChunk)
    }

    override fun onUserTextReceived(textChunk: String) {
        _lastTranscript.value = textChunk
        _spokenSentence.value = ""
        if (!isNoiseOrForeignHallucination(textChunk)) {
            ChatContextBridge.recordVoiceUserTurn(textChunk)
            AuraMemoryManager.learnFromConversation(textChunk)
        }
    }

    private fun isNoiseOrForeignHallucination(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return true
        if (t.contains("¿") || t.contains("¡")) return true
        val lower = t.lowercase()
        val foreignGlitches = listOf("yo calla", "pero bien", "esto qué es", "qué ha pasado", "habla", "gracias por ver")
        return foreignGlitches.any { lower.contains(it) }
    }

    override fun onInterrupted() {
        audioPlayer?.flush()
        _spokenSentence.value = ""
        _phase.value = Phase.LISTENING
        _statusText.value = "Listening..."
    }

    override fun onTurnCompleted() {
        audioPlayer?.notifyTurnComplete()
        if (_phase.value != Phase.SPEAKING && _phase.value != Phase.MUTED && _phase.value != Phase.WORKING) {
            _phase.value = Phase.IDLE_READY
            _statusText.value = "Speak to ask"
            _amplitude.value = 0f
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
        _phase.value = Phase.WORKING
        _statusText.value = "Working..."

        scope?.launch(Dispatchers.IO) {
            val result = executeTool(name, args)
            val summary = result.optString("message", result.optString("status", "executed"))
            ChatContextBridge.recordToolExecution(name, summary)
            webSocket?.sendToolResponse(callId, name, result)

            withContext(Dispatchers.Main) {
                if (_phase.value == Phase.WORKING) {
                    _phase.value = Phase.THINKING
                    _statusText.value = "Thinking..."
                }
            }
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

                "create_new_chat" -> {
                    val msg = ChatContextBridge.createNewChat()
                    JSONObject().apply {
                        put("status", "success")
                        put("message", msg)
                    }
                }

                "open_current_voice_chat", "open_live_chat" -> {
                    val msg = ChatContextBridge.openLiveChat()
                    JSONObject().apply {
                        put("status", "success")
                        put("message", msg)
                    }
                }

                "open_previous_voice_chat" -> {
                    val msg = ChatContextBridge.openPreviousVoiceChat()
                    JSONObject().apply {
                        put("status", "success")
                        put("message", msg)
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
                        val launched = AppLauncher.launchApp(context, appName, appNumber)
                        if (launched) {
                            JSONObject().put("status", "success").put("message", "Opened $appName app.")
                        } else {
                            val directWebUrl = phoneControl.parseTargetUrl(appName)
                            if (directWebUrl != null) {
                                phoneControl.openUrlsInChromeTabs(listOf(directWebUrl))
                                JSONObject().put("status", "success").put("message", "Opened $directWebUrl in Chrome")
                            } else {
                                JSONObject().put("status", "error").put("message", "Could not find $appName.")
                            }
                        }
                    }
                }

                "close_app" -> {
                    val appName = args.optString("app_name", "").ifBlank { null }
                    val removeFromRecents = args.optBoolean("remove_from_recents", false)
                    val clearAllRecents = args.optBoolean("clear_all_recents", false)
                    val mode = args.optString("mode", "").ifBlank {
                        if (clearAllRecents) "clear_all"
                        else if (appName != null) "close_specific_app"
                        else "clear_all"
                    }

                    val svc = AutomationAccessibilityService.instance
                    svc?.pauseMediaPlayback()

                    if (clearAllRecents || removeFromRecents || appName != null || mode.isNotBlank()) {
                        val result = svc?.executeRecentsAction(appName, clearAll = clearAllRecents || mode.contains("clear"), mode = mode)
                            ?: AutomationAccessibilityService.RecentsActionResult(
                                status = "FAILED",
                                message = "Accessibility service is not running."
                            )
                        val statusStr = when (result.status) {
                            "SUCCESS" -> "success"
                            "PARTIAL" -> "partial"
                            else -> "error"
                        }
                        JSONObject().apply {
                            put("status", statusStr)
                            put("recents_status", result.status)
                            put("message", result.message)
                            put("cleared_count", result.clearedCount)
                            put("remaining_cards", result.remainingCardsCount)
                        }
                    } else {
                        val ok = svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
                        JSONObject().apply {
                            put("status", if (ok) "success" else "error")
                            put("message", if (ok) "Closed app and returned home." else "Failed to return home.")
                        }
                    }
                }

                "open_in_app_browser", "open_inbuilt_browser", "open_url", "browse" -> {
                    val rawUrl = args.optString("url", "").ifBlank {
                        args.optString("query", "").ifBlank {
                            args.optJSONArray("urls")?.optString(0, "") ?: ""
                        }
                    }.trim()
                    val normalizedUrl = com.example.gemini.ui.browser.BrowserSessionManager.formatUrl(rawUrl)
                    withContext(Dispatchers.Main) {
                        val browserSession = com.example.gemini.ui.browser.BrowserSessionManager.instance
                        browserSession.openUrl(normalizedUrl, newTab = true)
                        ChatContextBridge.requestViewMode("BROWSER")
                        bringAuraToForeground(context)
                    }
                    JSONObject().apply {
                        put("status", "SUCCESS")
                        put("message", "Opened $normalizedUrl in Aura In-Built Browser.")
                        put("url", normalizedUrl)
                        put("target", "IN_APP_BROWSER")
                    }
                }

                "open_website" -> {
                    val externalRequested = args.optBoolean("external", false)
                    val query = args.optString("query", "").trim()
                    val isChromeExplicit = externalRequested ||
                        query.contains("chrome", ignoreCase = true) ||
                        query.contains("external", ignoreCase = true)

                    val targetUrls = mutableListOf<String>()
                    val jsonUrls = args.optJSONArray("urls")
                    if (jsonUrls != null) {
                        for (i in 0 until jsonUrls.length()) {
                            val u = jsonUrls.optString(i, "").trim()
                            if (u.isNotEmpty()) targetUrls.add(u)
                        }
                    }
                    val singleUrl = args.optString("url", "").trim()
                    if (singleUrl.isNotBlank() && !targetUrls.contains(singleUrl)) {
                        targetUrls.add(singleUrl)
                    }
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
                        targetUrls.add(query)
                    }

                    if (!isChromeExplicit) {
                        // Default strictly to In-Built Browser!
                        val firstUrl = targetUrls.firstOrNull() ?: "https://m.youtube.com"
                        val normalized = com.example.gemini.ui.browser.BrowserSessionManager.formatUrl(firstUrl)
                        withContext(Dispatchers.Main) {
                            val browserSession = com.example.gemini.ui.browser.BrowserSessionManager.instance
                            browserSession.openUrl(normalized, newTab = true)
                            ChatContextBridge.requestViewMode("BROWSER")
                            bringAuraToForeground(context)
                        }
                        JSONObject().apply {
                            put("status", "SUCCESS")
                            put("message", "Opened $normalized in Aura In-Built Browser.")
                            put("url", normalized)
                            put("target", "IN_APP_BROWSER")
                        }
                    } else {
                        val ok = phoneControl.openUrlsInChromeTabs(targetUrls)
                        JSONObject().put("status", if (ok) "SUCCESS" else "FAILED")
                            .put("message", if (ok) "Opened ${targetUrls.joinToString(", ")} in Google Chrome." else "Failed to open websites in Chrome.")
                    }
                }

                "search_in_chrome" -> {
                    val query = args.optString("query", "").trim()
                    val targetUrl = phoneControl.parseTargetUrl(query)
                    val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                    val ok = phoneControl.openUrlsInChromeTabs(listOf(searchUrl))
                    JSONObject().put("status", if (ok) "SUCCESS" else "FAILED")
                        .put("message", if (ok) "Searched \"$query\" in Chrome." else "Could not open Chrome.")
                }

                "search_and_play_youtube" -> {
                    val query = args.optString("query", "").trim()
                    val inBrowser = query.contains("inbuilt", true) || query.contains("in-built", true) ||
                        query.contains("browser", true) || query.contains("app ke", true) || query.contains("internal", true)
                    if (inBrowser) {
                        val cleanQ = YouTubeController.cleanQuery(query)
                        val ytUrl = "https://m.youtube.com/results?search_query=${Uri.encode(cleanQ)}"
                        withContext(Dispatchers.Main) {
                            com.example.gemini.ui.browser.BrowserSessionManager.instance.openUrl(ytUrl, newTab = true)
                            ChatContextBridge.requestViewMode("BROWSER")
                            bringAuraToForeground(context)
                        }
                        JSONObject().put("status", "SUCCESS").put("message", "Playing \"$cleanQ\" in Aura In-Built Browser.")
                    } else {
                        audioPlayer?.flush()
                        val playResult = YouTubeController.searchAndPlay(context, query)
                        JSONObject().put("status", if (playResult.success) "SUCCESS" else "FAILED")
                            .put("message", playResult.message ?: "Opened YouTube.")
                    }
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
                    val elementId = if (args.has("element_id")) args.optInt("element_id") else null
                    val x = if (args.has("x")) args.optInt("x") else null
                    val y = if (args.has("y")) args.optInt("y") else null
                    val isToggle = args.optBoolean("is_toggle", false)
                    val description = args.optString("description", "").ifBlank { null }
                    val goal = args.optString("goal", "").ifBlank { target }

                    val res = VisualTaskOrchestrator.getInstance().handleClick(
                        targetName = target,
                        elementId = elementId,
                        description = description,
                        isToggle = isToggle,
                        x = x,
                        y = y,
                        taskGoal = goal
                    )

                    JSONObject().put("status", if (res.success) "success" else "error")
                        .put("message", res.message)
                        .put("verified", res.verified ?: false)
                        .put("requires_confirmation", res.requiresUserConfirmation)
                        .apply {
                            if (res.currentStateSummary.isNotBlank()) {
                                put("current_state", res.currentStateSummary)
                            }
                        }
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    val targetInput = args.optString("target_input_name", "").ifBlank { null }
                    val submit = args.optBoolean("submit", false)
                    val clearExisting = args.optBoolean("clear_existing", true)
                    val x = if (args.has("x")) args.optInt("x") else null
                    val y = if (args.has("y")) args.optInt("y") else null

                    val res = VisualTaskOrchestrator.getInstance().handleType(
                        text = text,
                        targetInputName = targetInput,
                        submit = submit,
                        clearExisting = clearExisting,
                        x = x,
                        y = y
                    )

                    JSONObject().put("status", if (res.success) "SUCCESS" else "FAILED")
                        .put("message", res.message)
                        .put("verified", res.verified ?: false)
                        .apply {
                            if (res.currentStateSummary.isNotBlank()) {
                                put("current_state", res.currentStateSummary)
                            }
                        }
                }

                "scroll_screen" -> {
                    val dir = args.optString("direction", "down")
                    val dist = args.optString("distance", "small")
                    val res = VisualTaskOrchestrator.getInstance().handleScroll(direction = dir, distance = dist)

                    JSONObject().put("status", if (res.success) "success" else "error")
                        .put("message", res.message)
                        .put("verified", res.verified ?: false)
                        .apply {
                            if (res.currentStateSummary.isNotBlank()) {
                                put("current_state", res.currentStateSummary)
                            }
                        }
                }

                "press_key" -> {
                    val key = args.optString("key", "home")
                    val res = VisualTaskOrchestrator.getInstance().handleKey(key)

                    JSONObject().put("status", if (res.success) "success" else "error")
                        .put("message", res.message)
                        .apply {
                            if (res.currentStateSummary.isNotBlank()) {
                                put("current_state", res.currentStateSummary)
                            }
                        }
                }

                "inspect_screen", "read_screen" -> {
                    val orchestrator = VisualTaskOrchestrator.getInstance()
                    val inspection = orchestrator.inspectScreen(waitForContent = true)
                    val a11y = AutomationAccessibilityService.instance
                    val pkg = a11y?.foregroundPackage ?: a11y?.getActiveRootNode()?.packageName?.toString() ?: "unknown"
                    JSONObject().put("status", "success")
                        .put("foreground_app", pkg)
                        .put("screen_summary", inspection)
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
