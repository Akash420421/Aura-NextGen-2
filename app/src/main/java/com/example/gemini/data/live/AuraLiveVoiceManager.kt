package com.example.gemini.data.live

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.gemini.data.agent.ChatCompletionMonitor
import com.example.gemini.data.automation.ActivityLauncherHelper
import com.example.gemini.data.automation.AndroidAutomation
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
import com.example.gemini.ui.browser.BrowserSessionManager
import com.example.gemini.ui.browser.mcp.BrowserMcp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Serializes all tool calls so multi-step app automation runs strictly step-by-step
    private val toolExecutionMutex = Mutex()
    private var proactiveStatusJob: Job? = null
    private var completionMonitorJob: Job? = null
    private var eventBusMonitorJob: Job? = null
    private var lastAnnouncedStatusHash: Int = 0
    private val lastProactiveUpdateAt = AtomicLong(0L)

    // Callback invoked when AI requests screen share permission from user
    var requestScreenShareCallback: (() -> Unit)? = null

    // Acoustic Echo Decay Protection (AEDP) & Smart Near-Field Barge-In / TV Noise Filtering
    private val lastSpeakerActiveTimestamp = AtomicLong(0L)
    private const val AEDP_POST_SPEAKER_WINDOW_MS = 120L
    private val lastUserSpeechTimestamp = AtomicLong(0L)
    private val speechStartTimestamp = AtomicLong(0L)
    private val bargeInStartTimestamp = AtomicLong(0L)
    private const val VAD_SPEECH_DEBOUNCE_MS = 100L
    private const val VAD_BARGE_IN_DEBOUNCE_MS = 140L
    private const val VAD_SILENCE_HYSTERESIS_MS = 500L
    @Volatile
    private var ambientNoiseFloor: Float = 0.025f
    @Volatile
    private var lastSpeakerPlaybackAmp: Float = 0f

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
                    lastSpeakerPlaybackAmp = 0f
                    _phase.value = Phase.IDLE_READY
                    _statusText.value = "Speak to ask"
                    _amplitude.value = 0f
                }
            },
            onPlaybackAmplitude = { amp ->
                lastSpeakerPlaybackAmp = amp
                if (_phase.value == Phase.SPEAKING) {
                    _amplitude.value = amp
                    lastSpeakerActiveTimestamp.set(System.currentTimeMillis())
                }
            }
        ).apply { start() }

        // Initialize AudioRecorder with Smart Barge-In, Near-Field Human Speech Detection & Background TV Noise Rejection
        ambientNoiseFloor = 0.025f
        audioRecorder = AudioRecorder { chunk, amp, freq ->
            val now = System.currentTimeMillis()
            val isAedpActive = (now - lastSpeakerActiveTimestamp.get()) < AEDP_POST_SPEAKER_WINDOW_MS
            val isAssistantSpeaking = _phase.value == Phase.SPEAKING
            val isMuted = _phase.value == Phase.MUTED
            val currentPhase = _phase.value
            val hasHeadset = headsetRouter?.isHeadsetConnected() == true

            // Adaptive ambient noise floor tracks steady background TV / fan / room noise
            if (!isAssistantSpeaking && amp < ambientNoiseFloor * 1.7f) {
                ambientNoiseFloor = (ambientNoiseFloor * 0.96f + amp * 0.04f).coerceIn(0.015f, 0.095f)
            }

            // Human near-field speech profile: strong vocal mid-band (300Hz-3400Hz) relative to bass rumble,
            // and amplitude clearly above the rolling ambient TV/room noise floor.
            val speechThreshold = maxOf(0.062f, ambientNoiseFloor * 2.15f)
            val hasHumanVocalMidBand = freq.mid >= (freq.bass * 0.48f) && (freq.pitchHz in 75f..1400f || freq.mid >= 0.05f)
            val isNearFieldHumanSpeech = amp >= speechThreshold && hasHumanVocalMidBand

            if (isMuted) {
                speechStartTimestamp.set(0L)
                bargeInStartTimestamp.set(0L)
                _amplitude.value = 0f
                return@AudioRecorder
            }

            // 1. INSTANT BARGE-IN WHILE ASSISTANT IS SPEAKING ("bich mein tokne par ruk jaye aur hamari baat sune")
            if (isAssistantSpeaking) {
                // On loudspeaker, require stronger near-field vocal energy above speaker playback echo
                val bargeInThreshold = if (hasHeadset) {
                    speechThreshold
                } else {
                    maxOf(0.145f, lastSpeakerPlaybackAmp * 0.62f, ambientNoiseFloor * 2.8f)
                }
                val isBargeInVoice = amp >= bargeInThreshold && hasHumanVocalMidBand
                if (isBargeInVoice) {
                    val bStart = bargeInStartTimestamp.get()
                    if (bStart == 0L) {
                        bargeInStartTimestamp.set(now)
                    } else if (now - bStart >= VAD_BARGE_IN_DEBOUNCE_MS) {
                        Log.i(TAG, "Near-field user barge-in detected (amp=$amp, mid=${freq.mid}). Interrupting assistant playback!")
                        bargeInStartTimestamp.set(0L)
                        lastUserSpeechTimestamp.set(now)
                        audioPlayer?.flush()
                        _spokenSentence.value = ""
                        _phase.value = Phase.LISTENING
                        _statusText.value = "Listening..."
                        _amplitude.value = amp
                    }
                    // Stream barge-in speech chunks to Gemini Live so it hears the first words of the interruption
                    webSocket?.sendAudioChunk(chunk)
                } else {
                    bargeInStartTimestamp.set(0L)
                    if (hasHeadset) {
                        webSocket?.sendAudioChunk(chunk)
                    }
                }
                return@AudioRecorder
            }

            bargeInStartTimestamp.set(0L)

            // 2. NORMAL LISTENING & BACKGROUND TV NOISE FILTERING
            if (!isAedpActive && currentPhase != Phase.CONNECTING) {
                if (isNearFieldHumanSpeech) {
                    lastUserSpeechTimestamp.set(now)
                    if (currentPhase == Phase.IDLE_READY || currentPhase == Phase.THINKING || currentPhase == Phase.WORKING) {
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
                    } else if (currentPhase != Phase.WORKING) {
                        _amplitude.value = 0f
                    }
                }
            } else {
                speechStartTimestamp.set(0L)
                if (!isAssistantSpeaking) {
                    _amplitude.value = 0f
                }
            }

            // Stream audio to Gemini Live when user is actively speaking or during post-speech tail (1.4s),
            // or attenuate far-field TV background chatter to near-silence so Gemini's server VAD is never confused by TV audio.
            val withinSpeechHangover = (now - lastUserSpeechTimestamp.get()) < 1400L
            if (!isAedpActive || hasHeadset) {
                if (isNearFieldHumanSpeech || withinSpeechHangover || currentPhase == Phase.LISTENING) {
                    webSocket?.sendAudioChunk(chunk)
                } else {
                    // Send a clean silent PCM frame so Gemini Live's server-side VAD clock advances cleanly without hearing background TV chatter
                    val silentChunk = ByteArray(chunk.size)
                    webSocket?.sendAudioChunk(silentChunk)
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

        startCompletionMonitor(scope)
        AuraLiveVoiceService.start(context)
        ws.connect(scope)
    }

    fun stopSession() {
        Log.i(TAG, "Stopping live voice session...")
        JarvisSoundEffects.playStandbySound()

        proactiveStatusJob?.cancel()
        proactiveStatusJob = null
        completionMonitorJob?.cancel()
        completionMonitorJob = null
        eventBusMonitorJob?.cancel()
        eventBusMonitorJob = null

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
        sb.append("You are Aura, a single unified real-time AI companion living inside the Aura AI Android app.\n")
        sb.append("You have full direct control over Aura's chats, coding workspace, In-Built Browser, phone automation, apps, media playback, WhatsApp, screen inspection, and verified local preview servers.\n")
        sb.append("IDENTITY LOCK: NEVER refer to yourself as 'Jarvis', 'sub-agent', 'chat agent', 'background agent', or 'Antigravity' as a separate entity. You ARE Aura — always speak in first person ('Maine...', 'Main abhi...').\n")
        sb.append("BRAND RECOGNITION: In Hindi/Hinglish speech, users call this app 'Aura', 'Aura AI', or 'Aura Pro' (which ASR may occasionally mishear as 'aur app', 'ora', 'aora', 'oura', 'aurya pro'). Understand those as Aura / Aura Pro when used as an app name, while keeping normal Hindi 'aur' ('and'/'more') untouched.\n\n")

        sb.append("### CRITICAL SPOKEN LANGUAGE DIRECTIVE:\n")
        sb.append("- You MUST ALWAYS speak in conversational Hindi or Hinglish (Hindi mixed with natural English words as spoken in India).\n")
        sb.append("- Speak warmly, confidently, and keep your replies brief and direct (1-2 crisp sentences).\n")
        sb.append("- Ignore ambient background noise, live TV dialogue, news broadcasts, or unrelated room chatter completely. Only respond to direct near-field user requests.\n")
        sb.append("- If the user interrupts you while you are speaking (barge-in), immediately stop your previous topic, listen to their new instruction, and respond to what they just said.\n\n")

        sb.append("### MULTI-STEP AUTONOMOUS APP CONTROL & SEQUENTIAL WORKFLOWS:\n")
        sb.append("- When the user gives a multi-step command (e.g. 'WhatsApp kholo, Rahul ka contact dhundho, usko message karo Hi, uske baad WhatsApp close kar do, Instagram open karke Reels laga do, aur jab main swipe kahun toh reel swipe kar do'):\n")
        sb.append("  -> YOU MUST EXECUTE EVERY SINGLE STEP IN SEQUENCE WITHOUT STOPPING AFTER STEP 1!\n")
        sb.append("  -> You can either call `execute_app_workflow(workflow_goal=\"...\", steps=[...])` to run the entire multi-step pipeline in one verified sequence, OR chain the individual tools (`send_whatsapp_message`, `close_app`, `open_app`, `click_ui_element`) step-by-step until every part of the user's instruction is finished.\n")
        sb.append("  -> For WhatsApp messaging: calling `send_whatsapp_message(recipient_name=\"Rahul\", message=\"Hi\")` automatically opens WhatsApp, finds Rahul (via contacts or WhatsApp in-app UI search), injects 'Hi', and taps the verified Send button! Then you can immediately call `close_app(app_name=\"WhatsApp\", remove_from_recents=true)`, then `open_app(app_name=\"Instagram\")`, then `click_ui_element(target_name=\"Reels\")`!\n")
        sb.append("  -> Whenever the user says 'swipe', 'swipe karo', 'next', 'agla', 'reel swipe karo', or 'upar karo' while on Reels/Shorts: IMMEDIATELY call `swipe_reel(direction=\"up\")`!\n")
        sb.append("- PROACTIVE STATUS UPDATES: Whenever you receive a `[PROACTIVE_STATUS_UPDATE]` or finish a step in a multi-step workflow, immediately speak a short, natural 1-sentence Hindi/Hinglish status update to the user so they never have to ask 'ab kya status hai?'.\n\n")

        sb.append("### ACCESSIBILITY-FIRST POLICY & UNIVERSAL DIRECT TEXT INJECTION (ZERO SCREEN SHARE NEEDED):\n")
        sb.append("- NEVER ask the user for Screen Share to read text, messages, chat bubbles, buttons, menus, notifications, or type into text boxes!\n")
        sb.append("- The phone accessibility engine (`inspect_screen`) has complete 100% real-time access to foreground app screens, messages, and UI elements without needing screen share.\n")
        sb.append("- When the user asks: 'screen par kya hai?', 'kya message aaya hai?', 'maine kya message bheja?', 'screen read karo', 'chat mein kya likha hai?' or asks about ANY on-screen content:\n")
        sb.append("  -> YOU MUST IMMEDIATELY CALL `inspect_screen()`!\n")
        sb.append("- ZERO GUESSING RULE: NEVER answer what is on screen or what message was sent from previous conversation memory or guesswork! ALWAYS call `inspect_screen()`, read the live UI elements, and report what is actually on screen. If the text is genuinely not exposed by the app, say so honestly.\n")
        sb.append("- UNIVERSAL DIRECT INJECTION: When the user asks you to write, search, inject, or type text in ANY app (WhatsApp, Instagram, Telegram, Chrome, Notes, Search bars, etc.): IMMEDIATELY call `type_text(text=\"...\", target_input_name=\"...\", submit=true/false)`! Text is injected directly into the accessibility node without virtual keyboard typing hassles.\n")
        sb.append("- When the user asks to click, open, or scroll: ALWAYS call `click_ui_element`, `open_app`, or `scroll_screen` directly without asking for screen share!\n")
        sb.append("- SCREEN SHARE ONLY AS LAST RESORT: ONLY call `request_screen_share` if:\n")
        sb.append("  1) The user EXPLICITLY commands 'screen share karo' or 'start screen share', OR\n")
        sb.append("  2) The user asks about purely visual media (photos, camera feed, drawings, colors) with zero accessible text, OR\n")
        sb.append("  3) `inspect_screen` explicitly returned that the screen is blank/inaccessible.\n\n")

        sb.append("### CLOSING APPS & RECENTS (STRICT SINGLE-APP VS CLEAR-ALL SEPARATION):\n")
        sb.append("- Close ONLY the current foreground app ('app close karo', 'app band karo', 'ye app hata do'): ALWAYS call `close_current_app()`.\n")
        sb.append("- Close ONLY a single specific named app ('WhatsApp close karo', 'YouTube band karo', 'Chrome hata do'): ALWAYS call `close_specific_app(app_name=\"...\")` (or `close_app(app_name=\"...\", remove_from_recents=true, mode=\"close_specific_app\")`). NEVER clear all apps when only one app was named!\n")
        sb.append("- Clear ALL recent apps ONLY when user explicitly says 'saare apps close karo', 'sab apps hata do', 'clear all recent apps': call `clear_all_recent_apps(use_swipe_only=false)` (or `use_swipe_only=true` if user says 'swipe karke ek ek hatao').\n\n")

        sb.append("### MANDATORY ACTION TRUTHFULNESS & VERIFICATION (NEVER LIE OR FALSELY CONFIRM):\n")
        sb.append("- NEVER tell the user an action succeeded if the tool returned status 'error', 'partial', or status 'FAILED'!\n")
        sb.append("- If a reel swipe or scroll did not advance the screen, truthfully tell the user in Hindi/Hinglish (e.g. 'Reel advance nahi ho paayi, screen abhi bhi usi reel par hai'). NEVER claim success if it didn't change!\n")
        sb.append("- If `close_app` or `close_current_app` returns status 'error' or 'FAILED' (e.g. apps could not be cleared or cards are still visible), truthfully tell the user in Hindi/Hinglish that the apps could not be cleared (e.g. 'Recents open ho gaya lekin apps clear nahi ho paaye, screen par abhi bhi cards dikh rahe hain'). NEVER claim 'Saare apps remove kar diye' or 'Chrome close ho gaya' when it failed!\n")
        sb.append("- If `close_app` returns 'partial' (e.g. locked apps remain), inform the user honestly that locked apps ki wajah se kuch apps nahi hate.\n")
        sb.append("- Report EXACTLY what happened based on the tool's verified result message!\n\n")

        sb.append("### NATIVE APPS VS IN-BUILT BROWSER (COMPLETE CONTROL RULES):\n")
        sb.append("- When the user asks to open native phone apps (YouTube, WhatsApp, Instagram, Camera, Settings, Phone, Chrome, Pinterest): call `open_app(app_name=\"...\")`.\n")
        sb.append("- When the user asks to browse a website, open a URL, search Google in-app, or use the In-Built Browser -> call `open_in_app_browser(url=...)`.\n")
        sb.append("- COMPLETE IN-BUILT BROWSER DOM CONTROL: Once inside Aura's In-Built Browser, you control EVERY webpage element cleanly without Screen Share or refusals!\n")
        sb.append("  -> Read links/buttons/inputs on page: `browser_read_page(mode=\"interactive\")` or article text: `browser_read_page(mode=\"text\")`.\n")
        sb.append("  -> Search for a specific link/button/video: `browser_find(query=\"...\")`.\n")
        sb.append("  -> Click any link/button/menu inside the webpage: `browser_click(target_name=\"...\")` or `browser_click(ref=\"ref_...\")`.\n")
        sb.append("  -> Type & submit into webpage search bars/forms: `browser_type(text=\"...\", target_name=\"...\", submit=true)`.\n")
        sb.append("  -> Scroll webpage: `browser_scroll(direction=\"down\"|\"up\")`, navigate: `browser_navigate(action=\"back\"|\"forward\"|\"reload\")`, manage tabs/desktop view: `browser_tabs(...)`, control web audio/video/skip ad: `browser_media_control(action=...)`.\n")
        sb.append("  -> NEVER refuse clicks, typing, scrolling, or reading inside Aura's In-Built Browser! NEVER call `builtin_chrome_search` when the user wants to control or see the In-Built Browser.\n")
        sb.append("- Close In-Built Browser or web preview ('browser band karo', 'browser close karo', 'preview band karo'): ALWAYS call `close_in_app_browser()`!\n\n")

        sb.append("### YOUTUBE SONGS, SHORTS & AUTO AD SKIP:\n")
        sb.append("- Play ANY song/video (named like 'Tum Hi Ho lagao' OR vague like 'apni marzi se koi accha gaana laga do', 'best song bajao', or 'in-built browser mein song lagao'): ALWAYS call `search_and_play_youtube(query=\"...\")`! It resolves the direct video immediately with zero endless scrolling and arms automatic ad-skip.\n")
        sb.append("- Skip YouTube ads / like / subscribe / fullscreen / play first video on feed: call `youtube_action(action=\"skip_ad\"|\"like\"|\"subscribe\"|\"fullscreen\"|\"play_first\"|\"pause\"|\"play\"|\"next\")`! Notice `skip_ad` works in both the native YouTube app AND Aura's In-Built Browser.\n\n")

        sb.append("### ADVANCE DEVICE CONTROL (INSTANT HARDWARE & SYSTEM SETTINGS):\n")
        sb.append("- Check battery percentage, charging status, temperature, volume levels, Wi-Fi/network, RAM, storage, brightness, or DND -> call `get_device_status()` IMMEDIATELY.\n")
        sb.append("- Change volume ('volume kam karo', 'media volume 50% karo', 'ringtone full karo', 'alarm volume kam karo', 'mute karo') -> call `set_volume(action=..., stream=..., percentage=...)`.\n")
        sb.append("- Switch ringer ('silent karo', 'vibrate mode lagao', 'ring mode on karo') -> call `set_ringer_mode(mode=\"silent\"|\"vibrate\"|\"normal\")`.\n")
        sb.append("- Brightness ('brightness badhao', '50% karo', 'auto brightness on karo') -> call `set_brightness(...)`.\n")
        sb.append("- Flashlight/torch ('torch on/off/toggle') -> call `set_flashlight(...)`.\n")
        sb.append("- Wi-Fi / Bluetooth / DND / Location / Airplane / Hotspot / Battery Saver / Dark Mode -> call `set_device_setting(setting=..., enabled=...)` (DND toggles directly; radios open the native Android quick panel so user/automation can switch cleanly).\n")
        sb.append("- Notification panel, Quick Settings / Control Center, Lock screen, Screenshot, Split screen -> call `system_action(action=\"notifications\"|\"quick_settings\"|\"lock_screen\"|\"take_screenshot\"|\"split_screen\")`.\n")
        sb.append("- Check recent notifications or OTP codes -> call `read_notifications()`.\n\n")

        sb.append("### UNIVERSAL CLICKING & EXTERNAL CHROME POPUP MENUS (ZERO REFUSALS ON REAL APPS):\n")
        sb.append("- On Chrome or any external Android app: menus like '3 dots' / '3 line' / 'More options', popup options like 'New Incognito tab', toolbar buttons, and list cards are ALL clickable via `click_ui_element(target_name=...)`! Call `inspect_screen()` after clicking a menu icon so you see the opened popup items clearly.\n\n")

        sb.append("### PHONE CALLS & NUMERIC DIALING (NATIVE ACTION_DIAL ONLY):\n")
        sb.append("- When asked to dial or call a numeric phone number (e.g. '9876543210 dial karo', 'call 1234567890', Hindi spoken digits like 'nau aath saat cheh paanch...'): convert spoken Hindi/English digits cleanly into the exact 10-digit / E.164 phone number, ALWAYS call `dial_number(phone_number=\"...\", auto_call=false)` (or `auto_call=true` only if explicitly told to place the call immediately), and read the dialed number back cleanly. NEVER type phone numbers into Phone/Contacts search bars!\n")
        sb.append("- When asked to call a saved contact by name (e.g. 'call Rahul', 'mom ko phone karo'): Call `call_contact(contact_name=\"...\")`.\n\n")

        sb.append("### SHORT VIDEO FEEDS (INSTAGRAM REELS, SHORTS, TIKTOK):\n")
        sb.append("- When asked to tap the Reels tab or button (e.g. 'Reel wala button dabao', 'Reels open karo', 'Reels laga do', 'Reels tab'): ALWAYS call `click_ui_element(target_name=\"Reels\")` targeting the bottom navigation Reels tab.\n")
        sb.append("- When asked to swipe or advance a reel (e.g. 'Reel swipe karo', 'swipe', 'next reel', 'agla reel', 'swipe reel'): ALWAYS call `swipe_reel(direction=\"up\")`!\n\n")

        sb.append("### CONTINUOUS AUTO-SCROLL:\n")
        sb.append("- When asked to auto-scroll (e.g. 'scroll karte raho', 'auto scroll chalu karo', 'keep scrolling'): Call `start_continuous_scroll(direction=\"down\")`.\n")
        sb.append("- When asked to stop scrolling (e.g. 'scroll rok do', 'stop scrolling', 'ruko', 'bas'): Call `stop_continuous_scroll()`.\n\n")

        sb.append("### NATIVE APP & CHAT CONTROL (NEVER USE ACCESSIBILITY OR SCREEN SHARE ON AURA):\n")
        sb.append("- You have 100% direct internal programmatic control over Aura AI's own interface!\n")
        sb.append("- To send a message or task to chat: call `send_message_to_chat(message=\"...\", target=\"CURRENT\" | \"NEW\" | \"<chat_id>\")`.\n")
        sb.append("- To open a new chat: call `create_new_chat()` or `send_message_to_chat(..., target=\"NEW\")`.\n")
        sb.append("- To list recent chats or remember past projects: call `list_recent_chats()`.\n")
        sb.append("- To open any chat by ID or name: call `open_chat_by_id(chat_id=\"...\")` or `open_chat_by_name(chat_name=\"...\")`.\n")
        sb.append("- To close the In-Built Browser / web preview: call `close_in_app_browser()`.\n")
        sb.append("- For Aura's Chat/Drawer UI, NEVER use Accessibility or Screen Share (use `send_message_to_chat`, `create_new_chat`, `open_chat_by_name`). For Aura's In-Built Browser, use native DOM tools (`browser_read_page`, `browser_find`, `browser_click`, `browser_type`, `browser_scroll`, `browser_media_control`)! Android Accessibility tools (`click_ui_element`, `type_text`, `scroll_screen`, `inspect_screen`) are for third-party Android apps (Chrome, YouTube, Instagram, WhatsApp, Settings, etc.).\n\n")

        sb.append("### OPENING SYSTEM APPS & DIALER:\n")
        sb.append("- When asked to open phone dialer, phone app, or calling app (e.g. 'phone dialer kholo', 'calling app open karo', 'phone open karo'): ALWAYS call `open_app(app_name=\"phone\")` or `call_contact(contact_name=\"...\")`! DO NOT open PhonePe! DO NOT open Chrome!\n")
        sb.append("- When asked to open calculator, camera, settings, or gallery: call `open_app(app_name=\"...\")`.\n\n")

        sb.append("### HYBRID VOICE & AGENT ARCHITECTURE:\n")
        sb.append("1. NORMAL CONVERSATIONS & CAPABILITY QUESTIONS (ANSWER DIRECTLY IN VOICE):\n")
        sb.append("   - Casual talk, greetings, general questions, explanations, discussions, advice, and asking what you can do:\n")
        sb.append("   - You MUST answer IMMEDIATELY and DIRECTLY in voice (Hindi/Hinglish).\n")
        sb.append("   - When asked what you can do (e.g. 'tum kya kya kar sakte ho?', 'kya kar sakte ho?'): Answer naturally in voice describing phone automation, playing music, web search, and coding capabilities. DO NOT CALL ANY TOOLS! DO NOT START BUILDING OR CREATING ANYTHING!\n")
        sb.append("   - NEVER boast or mention internal app features! NEVER say: 'main new chat shuru kar sakta hoon', 'main new chat create karta hoon', or 'task delegate karta hoon'. All internal workflows are completely SILENT to the user!\n\n")

        sb.append("2. DISCOVERY-FIRST PROJECT PLANNING & STRICT REQUIREMENTS CONTRACT (#11 & #12):\n")
        sb.append("   - STRICT CONFIRMATION RULE: ONLY start coding when the user explicitly commands you to build/create/code something.\n")
        sb.append("   - DISCOVERY FIRST (#11): If the request is a non-trivial or under-specified website/app/landing page (e.g. 'ek landing page banao', 'portfolio banao', 'startup website banao') AND key details (brand/business name, theme/colors, target audience, core sections) are missing, ask 2–4 short, high-signal clarifying questions in Hindi/Hinglish FIRST (unless user says 'khud decide kar lo' / 'default bana do'). Once answers are collected, summarize the plan in 1–2 lines, save/include a clean `requirements.md` contract, and start building via `start_new_chat_task(prompt=...)` with ONLY the clean technical specification (NEVER include 'Ek new chat create karo...' meta-wrappers!).\n")
        sb.append("   - STRICT CONTRACT (#12):\n")
        sb.append("     * Theme Lock: If user requested light/white/minimal theme, NEVER switch to dark mode.\n")
        sb.append("     * Multi-File Lock: If user asked for separate files (HTML + CSS + JS or modular React/Next components), NEVER dump everything into one file.\n")
        sb.append("     * Mobile-First Responsive Lock: Always enforce viewport meta, fluid grids, no horizontal overflow, and 44px+ touch targets.\n")
        sb.append("     * Meta-Instructions vs Domain Content: If user references external design/UI skills, inspiration URLs, or GitHub UI-skills repos, treat them strictly as STYLING/CRAFT RULES — NEVER copy repo names, skill docs, or GitHub URLs into the website's brand name or copy!\n")
        sb.append("   - Speak a brief first-person confirmation (e.g. 'Haan bilkul, main abhi banana shuru kar raha hoon aur aapko saath-saath update deta rahunga.').\n")
        sb.append("   - NEVER re-send or repeat a task.\n\n")

        sb.append("3. CHECKING TASK / WEBSITE STATUS & CROSS-SCREEN PROJECT AWARENESS (#8, #13, #14):\n")
        sb.append("   - Whenever the user asks about progress or status (e.g. 'Website kitni bani?', 'Status kya hai?', 'Server start hua kya?'):\n")
        sb.append("   - NEVER ask for screen share! Call `check_task_status()` immediately and report back in first-person Hindi/Hinglish.\n")
        sb.append("   - CROSS-SCREEN CONTINUITY (#14): You automatically share `ActiveProjectContext` (active project name, path, theme, stack, and preview URL) even when the user switches to Pinterest, Dribbble, Chrome, or other apps! If the user says 'ye design dekh kar hamari website mein apply karo' or 'hamari site par ye color lagao', ALWAYS bind the visual inspiration directly to the active project listed in `[ACTIVE PROJECT CONTEXT]` — NEVER ask 'kaunsi website?' if an active project exists!\n\n")

        sb.append("4. OPENING VOICE CHATS & CREATING NEW CHATS ON SCREEN (#7):\n")
        sb.append("   - 'new chat open karo' / 'nayi chat shuru karo' -> Call `create_new_chat()`.\n")
        sb.append("   - Open a specific past/renamed chat ('Project Alpha wala chat kholo', 'Gym website wala chat kholo') -> Call `open_chat_by_name(chat_name=\"...\")` or `list_recent_chats(query=\"...\")` then `open_chat_by_id(chat_id=\"...\")`.\n")
        sb.append("   - 'live chat open karo' / 'chat dikhao' -> Call `open_live_chat()`.\n")
        sb.append("   - 'pichhli chat kholo' -> Call `open_previous_voice_chat()`.\n\n")

        sb.append("5. UPDATING OR MODIFYING RUNNING TASKS:\n")
        sb.append("   - When the user asks to modify the active website/app -> Call `send_task_update(instruction=...)`.\n\n")

        sb.append("6. INSPECTING APP STATE:\n")
        sb.append("   - When the user asks what tasks, chats, or servers are running in Aura -> Call `inspect_app_state()`.\n\n")

        sb.append("7. SCREEN SHARE:\n")
        sb.append("   - ONLY call `request_screen_share` if the user explicitly asks to see visual designs on an external app (Pinterest, Dribbble, Chrome) or explicitly says 'screen share chalu karo'. NEVER for Aura's own chat or task status.\n\n")

        sb.append("8. FIRST-PERSON PERSONAL OWNERSHIP (#8):\n")
        sb.append("   - NEVER say: 'Antigravity ka response aaya hai', 'Agent ne bola', 'Delegating to agent', or 'Chat mein ye likha hai'. You ARE Aura. Speak with complete first-person ownership!\n\n")

        sb.append("### ZERO-HALLUCINATION ACTION EXECUTION:\n")
        sb.append("- Open native app -> `open_app(app_name)`.\n")
        sb.append("- Open website / search in Aura In-Built Browser -> `open_in_app_browser(url=\"...\")`.\n")
        sb.append("- Control Aura In-Built Browser -> `browser_read_page`, `browser_find`, `browser_click`, `browser_type`, `browser_scroll`, `browser_navigate`, `browser_tabs`, `browser_media_control`, `close_in_app_browser`.\n")
        sb.append("- Play song/video on YouTube -> `search_and_play_youtube(query=\"...\")`; Skip Ad / Like / Subscribe / Play Visible -> `youtube_action(action=\"skip_ad\"|...)`.\n")
        sb.append("- Send WhatsApp message -> `send_whatsapp_message(recipient_name=\"...\", message=\"...\")`.\n")
        sb.append("- Call saved contact -> `call_contact(contact_name=\"...\")`; Dial numeric number -> `dial_number(phone_number=\"...\")`.\n")
        sb.append("- Device Telemetry & Control -> `get_device_status()`, `set_volume`, `set_ringer_mode`, `set_brightness`, `set_flashlight`, `set_device_setting`, `system_action`, `read_notifications`.\n")
        sb.append("- Scroll / Short-Video Swipe -> `scroll_screen(direction=\"down\"|\"up\")`, `swipe_reel(direction=\"up\"|\"down\")`.\n")
        sb.append("- Tap / Type in external apps (Chrome, Settings, IG, etc.) -> `click_ui_element(target_name=\"...\")`, `type_text(text=\"...\", submit=true)`, `inspect_screen()`.\n")
        sb.append("- System keys -> `press_key(key=\"back\"|\"home\"|\"recents\")`.\n")
        sb.append("- Local Web Preview (#13): NEVER run `python3 index.html` or `python <file>.html`! To preview ANY web project, ALWAYS call `start_local_preview_server(project_dir=\"...\", port=3000, open_in_browser=true)`.\n\n")

        sb.append(AuraMemoryManager.getSystemPromptContext())
        sb.append(ChatContextBridge.getActiveChatPromptContext())

        return sb.toString()
    }

    /**
     * Subscribes to ChatCompletionMonitor events so whenever a delegated coding/chat task finishes,
     * Gemini Live immediately announces completion to the user without waiting for them to ask.
     */
    private fun startCompletionMonitor(sessionScope: CoroutineScope) {
        completionMonitorJob?.cancel()
        completionMonitorJob = sessionScope.launch(Dispatchers.IO) {
            ChatCompletionMonitor.events.collect { event ->
                if (!isLive()) return@collect
                proactiveStatusJob?.cancel()
                val latestStatus = ChatContextBridge.checkActiveTaskStatus()
                val statusMsg = "Your build/task '${event.title}' is now COMPLETE! Latest summary: ${latestStatus.take(240)}. Proactively inform the user in first-person Hindi/Hinglish (1 concise sentence) that their work is ready."
                waitUntilSafeToAnnounce()
                webSocket?.sendSystemStatusUpdate(statusMsg, turnComplete = true)
            }
        }
        // Structured AuraAgentEventBus listener for proactive voice announcements (#8, #13)
        eventBusMonitorJob?.cancel()
        eventBusMonitorJob = sessionScope.launch(Dispatchers.IO) {
            AuraAgentEventBus.events.collect { ev ->
                if (!isLive()) return@collect
                val promptUpdate = when (ev) {
                    is AuraAgentEvent.QuestionAsked -> {
                        val optsText = if (ev.options.isNotEmpty()) " Options: ${ev.options.joinToString(", ")}." else ""
                        "You need user input to proceed: '${ev.promptSummary}'.$optsText Ask the user this question naturally in first-person Hindi/Hinglish (1-2 short sentences)."
                    }
                    is AuraAgentEvent.ServerReady -> {
                        "Your local preview server is live and verified on ${ev.url} (port ${ev.port}). Inform the user in 1 concise first-person Hindi/Hinglish sentence that the live preview is ready on port ${ev.port}."
                    }
                    is AuraAgentEvent.BuildCompleted -> {
                        proactiveStatusJob?.cancel()
                        "Your project '${ev.title}' finished cleanly: ${ev.summary.take(180)}. Announce this in 1 concise first-person Hindi/Hinglish sentence."
                    }
                    is AuraAgentEvent.StepMilestone -> {
                        "Milestone reached: ${ev.summary.take(160)}. Give a crisp 1-sentence first-person Hindi/Hinglish update."
                    }
                    is AuraAgentEvent.RealError -> {
                        "A real execution error occurred (${ev.errorTitle}): ${ev.errorMessage.take(180)}. Briefly inform the user honestly in first-person Hindi/Hinglish and suggest the fix."
                    }
                }
                waitUntilSafeToAnnounce()
                webSocket?.sendSystemStatusUpdate(promptUpdate, turnComplete = true)
            }
        }
    }

    /**
     * Starts a periodic proactive status reporter while a background coding/website task is running
     * so the assistant continuously updates the user every ~14 seconds until completion.
     */
    private fun startProactiveTaskProgressMonitor() {
        proactiveStatusJob?.cancel()
        val currentScope = scope ?: return
        lastAnnouncedStatusHash = 0
        proactiveStatusJob = currentScope.launch(Dispatchers.IO) {
            var checks = 0
            while (isActive && isLive() && checks < 25) {
                delay(14_000L)
                checks++
                val summary = ChatContextBridge.checkActiveTaskStatus()
                val hash = summary.hashCode()
                if (hash != lastAnnouncedStatusHash) {
                    lastAnnouncedStatusHash = hash
                    waitUntilSafeToAnnounce()
                    webSocket?.sendSystemStatusUpdate(
                        "Live task progress update (#$checks): $summary. Give the user a brief 1-sentence progress update in Hindi/Hinglish.",
                        turnComplete = true
                    )
                }
                val isDone = summary.contains("Completed", ignoreCase = true) ||
                             summary.contains("No active background task", ignoreCase = true) ||
                             summary.contains("finished", ignoreCase = true)
                if (isDone) break
            }
        }
    }

    private suspend fun waitUntilSafeToAnnounce() {
        var waited = 0L
        while (waited < 4000L && (_phase.value == Phase.LISTENING || _phase.value == Phase.SPEAKING || toolExecutionMutex.isLocked)) {
            delay(250L)
            waited += 250L
        }
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
        val normalized = AuraBrandAliasNormalizer.normalize(textChunk)
        _lastTranscript.value = normalized
        _spokenSentence.value = ""
        // Instant voice stop hook for continuous auto-scroll (#17)
        val svc = AutomationAccessibilityService.instance
        if (svc != null && svc.isContinuousScrolling) {
            val lower = normalized.lowercase(Locale.ROOT)
            if (lower.contains("ruko") || lower.contains("stop") || lower.contains("bas") ||
                lower.contains("rok do") || lower.contains("roko") || lower.contains("band karo")
            ) {
                svc.stopContinuousScroll("user_voice_stop")
            }
        }
        if (!isNoiseOrForeignHallucination(normalized)) {
            ChatContextBridge.recordVoiceUserTurn(normalized)
            AuraMemoryManager.learnFromConversation(normalized)
        }
    }

    private fun isNoiseOrForeignHallucination(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return true
        if (t.contains("¿") || t.contains("¡")) return true
        val lower = t.lowercase()
        val foreignGlitches = listOf("yo calla", "pero bien", "esto qué es", "qué ha pasado", "habla", "gracias por ver", "subtítulos por", "amara.org")
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
        onToolCallsBatch(listOf(GeminiLiveWebSocket.ToolCallItem(id = callId, name = name, args = args)))
    }

    private val FAST_DEVICE_TOOLS = setOf(
        "get_device_status", "set_volume", "set_brightness", "set_flashlight",
        "set_ringer_mode", "read_notifications", "system_action", "set_device_setting",
        "media_playback_control", "close_in_app_browser"
    )

    override fun onToolCallsBatch(calls: List<GeminiLiveWebSocket.ToolCallItem>) {
        if (calls.isEmpty()) return
        val allFast = calls.all { it.name in FAST_DEVICE_TOOLS }
        if (!allFast) {
            _phase.value = Phase.WORKING
            _statusText.value = "Working (${calls.first().name})..."
        }

        scope?.launch(Dispatchers.IO) {
            val runBatch: suspend () -> Unit = {
                val responsesArray = JSONArray()
                for ((index, call) in calls.withIndex()) {
                    if (!allFast) {
                        withContext(Dispatchers.Main) {
                            _phase.value = Phase.WORKING
                            _statusText.value = if (calls.size > 1) {
                                "Step ${index + 1}/${calls.size}: ${call.name}..."
                            } else {
                                "Executing ${call.name}..."
                            }
                        }
                    }
                    val result = executeTool(call.name, call.args)
                    val summary = result.optString("message", result.optString("status", "executed"))
                    ChatContextBridge.recordToolExecution(call.name, summary)
                    responsesArray.put(JSONObject().apply {
                        put("id", call.id)
                        put("name", call.name)
                        put("response", result)
                    })
                    if (!allFast && index < calls.size - 1 && call.name !in setOf("inspect_screen", "read_screen", "browser_read_page", "browser_find")) {
                        delay(350L)
                    }
                }
                webSocket?.sendToolResponses(responsesArray)

                withContext(Dispatchers.Main) {
                    if (_phase.value == Phase.WORKING) {
                        _phase.value = Phase.THINKING
                        _statusText.value = "Thinking..."
                    }
                }
            }

            if (allFast) {
                runBatch()
            } else {
                toolExecutionMutex.withLock { runBatch() }
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
                        startProactiveTaskProgressMonitor()
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

                "open_chat_by_name", "open_chat" -> {
                    val name = args.optString("chat_name").ifBlank { args.optString("name").ifBlank { args.optString("title").ifBlank { args.optString("id") } } }.trim()
                    val msg = ChatContextBridge.openChatByName(name)
                    JSONObject().apply {
                        put("status", if (msg.startsWith("Could not find")) "not_found" else "success")
                        put("message", msg)
                    }
                }

                "open_chat_by_id" -> {
                    val chatId = args.optString("chat_id", "").ifBlank { args.optString("id", "") }.trim()
                    val msg = ChatContextBridge.openChatById(chatId)
                    JSONObject().apply {
                        put("status", if (msg.startsWith("Could not find")) "not_found" else "success")
                        put("message", msg)
                    }
                }

                "list_recent_chats" -> {
                    val query = args.optString("query", "").ifBlank { null }
                    val limit = args.optInt("limit", 10)
                    val result = ChatContextBridge.listRecentChats(query, limit)
                    JSONObject().apply {
                        put("status", "success")
                        put("chats", result)
                        put("message", result)
                    }
                }

                "send_message_to_chat" -> {
                    val message = args.optString("message", "").ifBlank { args.optString("text", "") }.trim()
                    val target = args.optString("target", "CURRENT").ifBlank { "CURRENT" }
                    val autoSend = args.optBoolean("auto_send", true)
                    if (message.isBlank()) {
                        JSONObject().put("status", "error").put("message", "Message cannot be empty.")
                    } else {
                        val reply = ChatContextBridge.sendMessageToChat(message, target, autoSend)
                        JSONObject().apply {
                            put("status", "success")
                            put("message", reply)
                        }
                    }
                }

                "close_in_app_browser", "close_browser", "close_inbuilt_browser", "close_preview" -> {
                    val msg = ChatContextBridge.closeInAppBrowser()
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
                        startProactiveTaskProgressMonitor()
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
                            _statusText.value = "Working on it..."
                        }
                        val reply = ChatContextBridge.startNewChatTask(prompt)
                        startProactiveTaskProgressMonitor()
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

                    if (AuraBrandAliasNormalizer.isAuraAppAlias(appName) || appName.equals("antigem", true)) {
                        bringAuraToForeground(context)
                        JSONObject().put("status", "success").put("message", "Opened Aura AI and brought to foreground")
                    } else if (appName.equals("youtube", true) || appName.equals("yt", true)) {
                        // #21: Always launch native YouTube app first via packageManager; NEVER auto-search "Trending music"
                        val ytIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        val launched = if (ytIntent != null) {
                            ActivityLauncherHelper.startActivitySafely(context, ytIntent)
                        } else {
                            AppLauncher.launchApp(context, "youtube", appNumber)
                        }
                        if (launched) {
                            val (fgPkg, screenSummary) = VisualTaskOrchestrator.getInstance().waitForAppInForeground("youtube", timeoutMs = 3500L)
                            JSONObject().put("status", "success")
                                .put("message", "Opened native YouTube app.")
                                .put("foreground_app", fgPkg)
                                .put("current_state", screenSummary)
                        } else {
                            // Only fall back to Aura In-Built Browser if YouTube app is genuinely not installed
                            withContext(Dispatchers.Main) {
                                com.example.gemini.ui.browser.BrowserSessionManager.instance.openUrl("https://m.youtube.com", newTab = true)
                                ChatContextBridge.requestViewMode("BROWSER")
                                bringAuraToForeground(context)
                            }
                            JSONObject().put("status", "success")
                                .put("message", "YouTube app is not installed; opened YouTube inside Aura In-Built Browser.")
                        }
                    } else {
                        val launched = AppLauncher.launchApp(context, appName, appNumber)
                        if (launched) {
                            val (fgPkg, screenSummary) = VisualTaskOrchestrator.getInstance().waitForAppInForeground(appName, timeoutMs = 3500L)
                            JSONObject().put("status", "success")
                                .put("message", "Opened $appName app and verified it is on screen.")
                                .put("foreground_app", fgPkg)
                                .put("current_state", screenSummary)
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

                "close_current_app" -> {
                    val svc = AutomationAccessibilityService.instance
                    svc?.pauseMediaPlayback()
                    val result = svc?.executeRecentsAction(mode = "close_current_app")
                        ?: AutomationAccessibilityService.RecentsActionResult("FAILED", "Accessibility service is not running.")
                    JSONObject().apply {
                        put("status", if (result.isSuccess) "success" else "error")
                        put("message", result.message)
                    }
                }

                "close_specific_app" -> {
                    val appName = args.optString("app_name", "").ifBlank { args.optString("name", "") }.trim()
                    if (appName.isBlank()) {
                        JSONObject().put("status", "error").put("message", "app_name is required for close_specific_app.")
                    } else if (appName.lowercase(Locale.ROOT).let { it == "browser" || it.contains("in-built browser") || it.contains("inbuilt browser") || it == "preview" }) {
                        val msg = ChatContextBridge.closeInAppBrowser()
                        JSONObject().put("status", "success").put("message", msg)
                    } else {
                        val svc = AutomationAccessibilityService.instance
                        svc?.pauseMediaPlayback()
                        val result = svc?.executeRecentsAction(appName = appName, clearAll = false, mode = "close_specific_app")
                            ?: AutomationAccessibilityService.RecentsActionResult("FAILED", "Accessibility service is not running.")
                        JSONObject().apply {
                            put("status", if (result.isSuccess) "success" else if (result.status == "PARTIAL") "partial" else "error")
                            put("recents_status", result.status)
                            put("message", result.message)
                            put("cleared_count", result.clearedCount)
                            put("remaining_cards", result.remainingCardsCount)
                        }
                    }
                }

                "clear_all_recent_apps" -> {
                    val useSwipeOnly = args.optBoolean("use_swipe_only", false)
                    val svc = AutomationAccessibilityService.instance
                    svc?.pauseMediaPlayback()
                    val mode = if (useSwipeOnly) "swipe_clear_all" else "clear_all"
                    val result = svc?.executeRecentsAction(appName = null, clearAll = true, mode = mode)
                        ?: AutomationAccessibilityService.RecentsActionResult("FAILED", "Accessibility service is not running.")
                    JSONObject().apply {
                        put("status", if (result.isSuccess) "success" else if (result.status == "PARTIAL") "partial" else "error")
                        put("recents_status", result.status)
                        put("message", result.message)
                        put("cleared_count", result.clearedCount)
                        put("remaining_cards", result.remainingCardsCount)
                    }
                }

                "close_app" -> {
                    val appName = args.optString("app_name", "").ifBlank { null }
                    val appLower = appName?.lowercase(java.util.Locale.ROOT)?.trim()
                    if (appLower != null && (appLower == "browser" || appLower.contains("in-built browser") || appLower.contains("inbuilt browser") || appLower == "preview" || appLower.contains("web preview"))) {
                        val msg = ChatContextBridge.closeInAppBrowser()
                        return JSONObject().apply {
                            put("status", "success")
                            put("message", msg)
                        }
                    }
                    val removeFromRecents = args.optBoolean("remove_from_recents", false)
                    val clearAllRecents = args.optBoolean("clear_all_recents", false)
                    val mode = args.optString("mode", "").ifBlank {
                        when {
                            clearAllRecents -> "clear_all"
                            appName != null -> "close_specific_app"
                            else -> "close_current_app"
                        }
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
                    val normalizedUrl = BrowserSessionManager.formatUrl(rawUrl)
                    val openNewTab = if (args.has("new_tab")) args.optBoolean("new_tab", false) else false
                    val desktopOpt = if (args.has("desktop_mode")) args.optBoolean("desktop_mode") else null
                    val browserSession = BrowserSessionManager.instance
                    val tab = withContext(Dispatchers.Main) {
                        val t = browserSession.openUrl(normalizedUrl, newTab = openNewTab, desktopMode = desktopOpt)
                        ChatContextBridge.requestViewMode("BROWSER")
                        bringAuraToForeground(context)
                        t
                    }
                    val loaded = browserSession.waitForPageLoad(tab.id, timeoutMs = 7_500L)
                    val (finalUrl, title, errDesc) = withContext(Dispatchers.Main) {
                        Triple(tab.webView?.url ?: tab.url, tab.title, tab.lastError?.description)
                    }
                    if (!errDesc.isNullOrBlank()) {
                        JSONObject().apply {
                            put("status", "error")
                            put("url", finalUrl)
                            put("tab_id", tab.id)
                            put("message", "In-Built Browser hit page error loading $finalUrl: $errDesc")
                        }
                    } else {
                        JSONObject().apply {
                            put("status", "SUCCESS")
                            put("tab_id", tab.id)
                            put("url", finalUrl)
                            put("title", title)
                            put("loaded", loaded)
                            put("target", "IN_APP_BROWSER")
                            put("message", "Opened \"$title\" ($finalUrl) in Aura In-Built Browser${if (loaded) "." else " (still finishing load)."} Use browser_read_page, browser_find, browser_click, or browser_type to control elements.")
                        }
                    }
                }

                "browser_read_page" -> {
                    ensureBrowserOpen(context)
                    val tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                    val mode = args.optString("mode", "interactive").lowercase(Locale.ROOT)
                    if (mode == "text") {
                        val mcpArgs = JSONObject().apply {
                            if (tabId != null) put("tab_id", tabId)
                            put("max_chars", 14_000)
                        }
                        val (ok, txt) = callBrowserMcpText(context, "browser_get_page_text", mcpArgs)
                        JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                    } else {
                        val mcpArgs = JSONObject().apply {
                            if (tabId != null) put("tab_id", tabId)
                            put("filter", if (mode == "all") "all" else "interactive")
                            put("max_chars", 14_000)
                        }
                        val (ok, txt) = callBrowserMcpText(context, "browser_read_page", mcpArgs)
                        JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                    }
                }

                "browser_find" -> {
                    ensureBrowserOpen(context)
                    val query = args.optString("query", "").trim()
                    if (query.isBlank()) {
                        JSONObject().put("status", "error").put("message", "query cannot be empty for browser_find.")
                    } else {
                        val mcpArgs = JSONObject().put("query", query).apply {
                            args.optString("tab_id").takeIf { it.isNotBlank() }?.let { put("tab_id", it) }
                        }
                        val (ok, txt) = callBrowserMcpText(context, "browser_find", mcpArgs)
                        JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                    }
                }

                "browser_click" -> {
                    ensureBrowserOpen(context)
                    performBrowserClick(
                        context = context,
                        refArg = args.optString("ref").takeIf { it.isNotBlank() },
                        targetName = args.optString("target_name").ifBlank { args.optString("query") }.takeIf { it.isNotBlank() },
                        tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                    )
                }

                "browser_type" -> {
                    ensureBrowserOpen(context)
                    performBrowserType(
                        context = context,
                        text = args.optString("text", ""),
                        refArg = args.optString("ref").takeIf { it.isNotBlank() },
                        targetName = args.optString("target_name").ifBlank { args.optString("query") }.takeIf { it.isNotBlank() },
                        submit = args.optBoolean("submit", true),
                        tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                    )
                }

                "browser_scroll" -> {
                    ensureBrowserOpen(context)
                    val dir = args.optString("direction", "down").lowercase(Locale.ROOT)
                    val amount = args.optInt("amount", 5).coerceIn(1, 10)
                    val mcpArgs = JSONObject()
                        .put("action", "scroll")
                        .put("scroll_direction", dir)
                        .put("scroll_amount", amount)
                        .apply {
                            args.optString("tab_id").takeIf { it.isNotBlank() }?.let { put("tab_id", it) }
                        }
                    val (ok, txt) = callBrowserMcpText(context, "browser_computer", mcpArgs)
                    JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                }

                "browser_navigate" -> {
                    ensureBrowserOpen(context)
                    val action = args.optString("action", "back").lowercase(Locale.ROOT)
                    val tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                    when (action) {
                        "back", "forward", "reload" -> {
                            val mcpArgs = JSONObject().put("url", action).apply {
                                if (tabId != null) put("tab_id", tabId)
                            }
                            val (ok, txt) = callBrowserMcpText(context, "browser_open_url", mcpArgs)
                            JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                        }
                        "stop" -> {
                            withContext(Dispatchers.Main) {
                                BrowserSessionManager.instance.getTab(tabId)?.webView?.stopLoading()
                            }
                            JSONObject().put("status", "success").put("message", "Stopped page loading.")
                        }
                        else -> JSONObject().put("status", "error").put("message", "Unknown browser_navigate action '$action'.")
                    }
                }

                "browser_tabs" -> {
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                    val bm = BrowserSessionManager.instance
                    when (action) {
                        "list" -> {
                            val (_, txt) = callBrowserMcpText(context, "browser_list_tabs", JSONObject())
                            JSONObject().put("status", "success").put("message", txt)
                        }
                        "new" -> {
                            val rawUrl = args.optString("url", "").trim()
                            val url = if (rawUrl.isNotBlank()) BrowserSessionManager.formatUrl(rawUrl) else ""
                            val tab = withContext(Dispatchers.Main) {
                                val t = bm.addNewTab(url, activate = true)
                                ChatContextBridge.requestViewMode("BROWSER")
                                bringAuraToForeground(context)
                                t
                            }
                            if (url.isNotBlank()) bm.waitForPageLoad(tab.id, 7_000L)
                            JSONObject().put("status", "success").put("tab_id", tab.id).put("message", "Opened new browser tab [${tab.id}]${if (url.isNotBlank()) " at $url" else ""}.")
                        }
                        "switch" -> {
                            ensureBrowserOpen(context)
                            val mcpArgs = JSONObject().put("tab_id", tabId ?: bm.getActiveTab()?.id.orEmpty())
                            val (ok, txt) = callBrowserMcpText(context, "browser_switch_tab", mcpArgs)
                            JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                        }
                        "close" -> {
                            val mcpArgs = JSONObject().put("tab_id", tabId ?: bm.getActiveTab()?.id.orEmpty())
                            val (ok, txt) = callBrowserMcpText(context, "browser_close_tab", mcpArgs)
                            JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                        }
                        "desktop", "mobile" -> {
                            ensureBrowserOpen(context)
                            val mcpArgs = JSONObject().put("mode", action).apply {
                                if (tabId != null) put("tab_id", tabId)
                            }
                            val (ok, txt) = callBrowserMcpText(context, "browser_set_view_mode", mcpArgs)
                            JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                        }
                        else -> JSONObject().put("status", "error").put("message", "Unknown browser_tabs action '$action'.")
                    }
                }

                "browser_media_control" -> {
                    val action = args.optString("action", "toggle").lowercase(Locale.ROOT)
                    val seekSec = if (args.has("seek_seconds")) args.optDouble("seek_seconds", 10.0) else null
                    val msg = BrowserSessionManager.instance.controlWebVideo(action, seekSec)
                    val ok = !msg.startsWith("Failed", true) && !msg.startsWith("No ", true)
                    JSONObject().put("status", if (ok) "success" else "error").put("message", msg)
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
                        val firstUrl = targetUrls.firstOrNull() ?: "https://m.youtube.com"
                        val normalized = BrowserSessionManager.formatUrl(firstUrl)
                        val browserSession = BrowserSessionManager.instance
                        val tab = withContext(Dispatchers.Main) {
                            val t = browserSession.openUrl(normalized, newTab = false)
                            ChatContextBridge.requestViewMode("BROWSER")
                            bringAuraToForeground(context)
                            t
                        }
                        val loaded = browserSession.waitForPageLoad(tab.id, 7_500L)
                        val finalUrl = withContext(Dispatchers.Main) { tab.webView?.url ?: tab.url }
                        JSONObject().apply {
                            put("status", "SUCCESS")
                            put("tab_id", tab.id)
                            put("loaded", loaded)
                            put("message", "Opened $finalUrl in Aura In-Built Browser.")
                            put("url", finalUrl)
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
                    audioPlayer?.flush()
                    val playResult = YouTubeController.searchAndPlay(context, query)
                    JSONObject().put("status", if (playResult.success) "SUCCESS" else "FAILED")
                        .put("title", playResult.title.orEmpty())
                        .put("message", playResult.message ?: "Opened YouTube.")
                }

                "search_youtube" -> {
                    val query = args.optString("query", "").trim()
                    val result = YouTubeController.searchYouTube(context, query)
                    JSONObject().put("status", if (result.success) "success" else "error")
                        .put("message", result.message ?: "Showing search results.")
                }

                "media_playback_control" -> {
                    val action = args.optString("action", "toggle")
                    if (ChatContextBridge.isInBuiltBrowserActive()) {
                        val msg = BrowserSessionManager.instance.controlWebVideo(action)
                        val ok = !msg.startsWith("Failed", true) && !msg.startsWith("No ", true)
                        JSONObject().put("status", if (ok) "success" else "error").put("message", msg)
                    } else {
                        val ok = YouTubeController.sendMediaKey(context, action)
                        JSONObject().put("status", if (ok) "success" else "error")
                            .put("message", "Media action '$action' performed.")
                    }
                }

                "youtube_action" -> {
                    val action = args.optString("action", "skip_ad")
                    val res = YouTubeController.performPlayerAction(context, action)
                    JSONObject().put("status", if (res.success) "success" else "error")
                        .put("message", res.message ?: "YouTube action '$action' attempted.")
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
                            // Synchronously wait for WhatsApp's Send button tap to complete before returning
                            val sentVerified = AutomationAccessibilityService.instance?.awaitWhatsAppAutoSend(timeoutMs = 5000L) ?: true
                            JSONObject().put("status", "success")
                                .put("sent", sentVerified)
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
                        is WhatsAppMessenger.SendResult.ContactNotFound,
                        is WhatsAppMessenger.SendResult.MissingPermission,
                        is WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                            // Fallback: Open WhatsApp, search for recipient in WhatsApp's UI via Direct Injection, open chat, and send message!
                            Log.i(TAG, "Contact '$recipient' not resolved via phone contacts ($waResult). Running WhatsApp In-App UI Search & Send...")
                            val fallbackRes = VisualTaskOrchestrator.getInstance().runWhatsAppInAppSearchAndSend(
                                context = context,
                                recipientName = recipient,
                                message = message,
                                appNumber = appNumber
                            )
                            JSONObject().put("status", if (fallbackRes.success) "success" else "error")
                                .put("sent", fallbackRes.success)
                                .put("message", fallbackRes.message)
                                .put("current_state", fallbackRes.currentStateSummary)
                        }
                        is WhatsAppMessenger.SendResult.Error -> {
                            JSONObject().put("status", "error").put("message", waResult.reason)
                        }
                    }
                }

                "execute_app_workflow" -> {
                    val workflowGoal = args.optString("workflow_goal", "Multi-step app control workflow")
                    val stepsArray = args.optJSONArray("steps") ?: JSONArray()
                    if (stepsArray.length() == 0) {
                        return JSONObject().put("status", "error").put("message", "No steps provided in execute_app_workflow.")
                    }

                    val stepLogs = JSONArray()
                    var allSucceeded = true
                    var lastStateSummary = ""

                    for (i in 0 until stepsArray.length()) {
                        val stepObj = stepsArray.optJSONObject(i) ?: continue
                        val action = stepObj.optString("action", "").trim().lowercase(Locale.ROOT)
                        withContext(Dispatchers.Main) {
                            _phase.value = Phase.WORKING
                            _statusText.value = "Workflow ${i + 1}/${stepsArray.length()}: $action..."
                        }

                        val stepResult: JSONObject = when (action) {
                            "open_app" -> executeTool("open_app", stepObj)
                            "send_whatsapp_message", "send_whatsapp" -> executeTool("send_whatsapp_message", stepObj)
                            "click_ui_element", "click" -> executeTool("click_ui_element", stepObj)
                            "type_text", "type" -> {
                                val typeArgs = JSONObject(stepObj.toString()).apply {
                                    if (!has("target_input_name") && has("target_name")) {
                                        put("target_input_name", optString("target_name"))
                                    }
                                }
                                executeTool("type_text", typeArgs)
                            }
                            "scroll_screen", "scroll" -> executeTool("scroll_screen", stepObj)
                            "swipe_reel" -> executeTool("swipe_reel", stepObj)
                            "youtube_action" -> {
                                val subAction = stepObj.optString("player_action", "")
                                    .ifBlank { stepObj.optString("youtube_action", "") }
                                    .ifBlank { stepObj.optString("target_name", "") }
                                    .ifBlank { stepObj.optString("text", "") }
                                    .ifBlank { "skip_ad" }
                                executeTool("youtube_action", JSONObject().put("action", subAction))
                            }
                            "close_app" -> {
                                val closeArgs = JSONObject(stepObj.toString()).apply {
                                    if (!has("remove_from_recents")) put("remove_from_recents", true)
                                }
                                executeTool("close_app", closeArgs)
                            }
                            "close_current_app" -> executeTool("close_current_app", stepObj)
                            "press_key" -> executeTool("press_key", stepObj)
                            "inspect_screen", "read_screen" -> executeTool("inspect_screen", stepObj)
                            "wait" -> {
                                val waitMs = stepObj.optLong("wait_ms", 1000L).coerceIn(200L, 8000L)
                                delay(waitMs)
                                JSONObject().put("status", "success").put("message", "Waited ${waitMs}ms.")
                            }
                            else -> JSONObject().put("status", "error").put("message", "Unsupported workflow step action: $action")
                        }

                        val statusStr = stepResult.optString("status", "").lowercase(Locale.ROOT)
                        val stepOk = statusStr == "success" || statusStr == "partial"
                        val stepState = stepResult.optString("current_state", stepResult.optString("screen_summary", ""))
                        if (stepState.isNotBlank()) {
                            lastStateSummary = stepState
                        }

                        stepLogs.put(JSONObject().apply {
                            put("step", i + 1)
                            put("action", action)
                            put("status", stepResult.optString("status", "unknown"))
                            put("message", stepResult.optString("message", ""))
                        })

                        if (!stepOk) {
                            allSucceeded = false
                            // Abort early on failure so subsequent steps don't tap blindly on the wrong screen
                            break
                        }

                        if (i < stepsArray.length() - 1) {
                            delay(450L)
                        }
                    }

                    if (lastStateSummary.isBlank()) {
                        lastStateSummary = VisualTaskOrchestrator.getInstance().inspectScreen(waitForContent = false)
                    }

                    val anySucceeded = stepLogs.length() > 1 || allSucceeded
                    JSONObject().apply {
                        put("status", when {
                            allSucceeded -> "success"
                            anySucceeded -> "partial"
                            else -> "error"
                        })
                        put("workflow_goal", workflowGoal)
                        put("completed_steps", stepLogs.length())
                        put("step_results", stepLogs)
                        put("current_state", lastStateSummary)
                        put(
                            "message",
                            if (allSucceeded) "Executed all ${stepLogs.length()} workflow steps for '$workflowGoal'."
                            else "Workflow stopped at step ${stepLogs.length()} of ${stepsArray.length()} because that step failed. Inspect current_state to continue cleanly."
                        )
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
                    val contact = args.optString("contact_name", "").ifBlank { args.optString("name", "") }.trim()
                    audioPlayer?.flush()
                    val digitsOnly = contact.replace(Regex("[^0-9+]"), "")
                    if (digitsOnly.length >= 5) {
                        val res = phoneControl.dialNumber(context, digitsOnly, autoCall = false)
                        res.fold(
                            onSuccess = { dialed ->
                                JSONObject().put("status", "success")
                                    .put("dialed_number", dialed)
                                    .put("message", "Opened dialer with $dialed.")
                            },
                            onFailure = { err ->
                                JSONObject().put("status", "error")
                                    .put("message", "Failed to dial number: ${err.message}")
                            }
                        )
                    } else {
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
                }

                "dial_number" -> {
                    val rawNum = args.optString("phone_number", "").ifBlank { args.optString("number", "") }.trim()
                    val autoCall = args.optBoolean("auto_call", false)
                    val res = phoneControl.dialNumber(context, rawNum, autoCall)
                    res.fold(
                        onSuccess = { dialed ->
                            JSONObject().put("status", "success")
                                .put("dialed_number", dialed)
                                .put("message", "Opened phone dialer with $dialed.")
                        },
                        onFailure = { err ->
                            JSONObject().put("status", "error")
                                .put("message", "Failed to dial number: ${err.message}")
                        }
                    )
                }

                "swipe_reel" -> {
                    val dir = args.optString("direction", "up").trim().lowercase(Locale.ROOT)
                    val res = VisualTaskOrchestrator.getInstance().handleSwipeReel(direction = dir)
                    JSONObject().put("status", if (res.success) "success" else "error")
                        .put("message", res.message)
                        .put("verified", res.verified ?: false)
                        .apply {
                            if (res.currentStateSummary.isNotBlank()) {
                                put("current_state", res.currentStateSummary)
                            }
                        }
                }

                "start_continuous_scroll" -> {
                    val dir = args.optString("direction", "down").trim()
                    val speed = args.optString("speed", "normal").trim()
                    val svc = AutomationAccessibilityService.instance
                    if (svc != null) {
                        val started = svc.startContinuousScroll(direction = dir, speed = speed)
                        JSONObject().put("status", if (started) "success" else "error")
                            .put("message", if (started) "Started continuous scroll ($dir, speed: $speed)." else "Could not start continuous scroll.")
                    } else {
                        JSONObject().put("status", "error").put("message", "Accessibility service is not running.")
                    }
                }

                "stop_continuous_scroll" -> {
                    val svc = AutomationAccessibilityService.instance
                    val stopped = svc?.stopContinuousScroll("user_stopped") ?: false
                    JSONObject().put("status", "success")
                        .put("message", if (stopped) "Continuous scrolling stopped." else "Continuous scroll was not active.")
                }

                "get_device_status" -> {
                    val sections = args.optString("sections", "battery,audio,display,network,memory,storage,system").ifBlank {
                        "battery,audio,display,network,memory,storage,system"
                    }
                    val statusJson = phoneControl.getDeviceStatusJson(sections)
                    JSONObject().apply {
                        put("status", "success")
                        put("device_status", statusJson)
                        put("message", statusJson.toString())
                    }
                }

                "set_volume" -> {
                    val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
                    val stream = args.optString("stream", "music").ifBlank { "music" }
                    val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                    val voiceActive = webSocket != null
                    val msg = when {
                        action == "get" -> {
                            val current = phoneControl.getStreamVolumePercent()
                            "Current media volume is $current%."
                        }
                        action == "set" && pct != null -> phoneControl.setVolumePercent(pct, stream = stream, silentFeedback = voiceActive)
                        action == "increase" -> phoneControl.adjustVolume(true, stream = stream, silentFeedback = voiceActive)
                        action == "decrease" -> phoneControl.adjustVolume(false, stream = stream, silentFeedback = voiceActive)
                        action == "mute" -> phoneControl.setStreamMuted(true, stream = stream, silentFeedback = true)
                        action == "unmute" -> phoneControl.setStreamMuted(false, stream = stream, silentFeedback = voiceActive)
                        else -> "Volume unchanged."
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_ringer_mode" -> {
                    val mode = args.optString("mode", "normal")
                    val msg = phoneControl.setRingerMode(mode)
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_brightness" -> {
                    val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
                    val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                    val msg = when {
                        action == "set" && pct != null -> phoneControl.setBrightnessPercent(pct)
                        action == "increase" -> phoneControl.adjustBrightness(true)
                        action == "decrease" -> phoneControl.adjustBrightness(false)
                        action == "auto_on" -> phoneControl.setAutoBrightness(true)
                        action == "auto_off" -> phoneControl.setAutoBrightness(false)
                        else -> "Brightness unchanged."
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_flashlight" -> {
                    val msg = if (args.optBoolean("toggle", false) || !args.has("enabled")) {
                        phoneControl.toggleFlashlight()
                    } else {
                        phoneControl.setFlashlight(args.optBoolean("enabled", true))
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "set_device_setting" -> {
                    val setting = args.optString("setting", "settings").trim().lowercase(Locale.ROOT)
                    val msg = if (setting == "dnd" || setting == "do_not_disturb") {
                        phoneControl.setDndEnabled(args.optBoolean("enabled", true))
                    } else {
                        phoneControl.openDevicePanel(setting)
                    }
                    JSONObject().put("status", "success").put("message", msg)
                }

                "system_action" -> {
                    val action = args.optString("action", "")
                    val msg = phoneControl.performSystemAction(action)
                    val ok = msg.startsWith("Executed")
                    JSONObject().put("status", if (ok) "success" else "error").put("message", msg)
                }

                "read_notifications" -> {
                    val limit = args.optInt("limit", 10)
                    val json = AndroidAutomation.readRecentNotificationsJson(limit = limit)
                    JSONObject().apply {
                        put("status", "success")
                        put("notifications_data", json)
                        put("message", json.toString())
                    }
                }

                "builtin_chrome_search", "quick_web_research" -> {
                    val query = args.optString("query", "").trim()
                    val extracted = BuiltInChromeEngine.searchAndExtract(query)
                    JSONObject().put("status", "success").put("message", extracted)
                }

                "click_ui_element" -> {
                    val fgPkg = AutomationAccessibilityService.instance?.foregroundPackage.orEmpty()
                    if (ChatContextBridge.isInBuiltBrowserActive()) {
                        performBrowserClick(
                            context = context,
                            refArg = args.optString("ref").takeIf { it.isNotBlank() },
                            targetName = args.optString("target_name", "").ifBlank { args.optString("text", "") }.takeIf { it.isNotBlank() },
                            tabId = args.optString("tab_id").takeIf { it.isNotBlank() }
                        )
                    } else if (isAuraPackage(fgPkg, context.packageName)) {
                        JSONObject().put("status", "error")
                            .put("message", "Accessibility click is disabled on Aura's own chat UI. Use native Aura tools (create_new_chat, open_chat_by_id, open_chat_by_name, send_message_to_chat, open_in_app_browser, close_in_app_browser).")
                    } else {
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
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    val targetInput = args.optString("target_input_name", "").ifBlank { null }
                    val submit = args.optBoolean("submit", false)
                    val clearExisting = args.optBoolean("clear_existing", true)
                    val x = if (args.has("x")) args.optInt("x") else null
                    val y = if (args.has("y")) args.optInt("y") else null

                    val fgPkg = AutomationAccessibilityService.instance?.foregroundPackage.orEmpty()
                    if (ChatContextBridge.isInBuiltBrowserActive()) {
                        performBrowserType(
                            context = context,
                            text = text,
                            refArg = args.optString("ref").takeIf { it.isNotBlank() },
                            targetName = targetInput,
                            submit = submit,
                            tabId = null
                        )
                    } else if (isAuraPackage(fgPkg, context.packageName)) {
                        // #6: Route natively to Aura's ChatContextBridge — NEVER use Accessibility on Aura chat UI!
                        val reply = ChatContextBridge.sendMessageToChat(text, target = "CURRENT", autoSend = submit)
                        JSONObject().put("status", "SUCCESS")
                            .put("message", reply)
                            .put("verified", true)
                    } else {
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
                }

                "scroll_screen" -> {
                    if (ChatContextBridge.isInBuiltBrowserActive()) {
                        val dir = args.optString("direction", "down").lowercase(Locale.ROOT)
                        val dist = args.optString("distance", "medium").lowercase(Locale.ROOT)
                        val amount = when (dist) {
                            "small", "short" -> 3
                            "large", "long" -> 8
                            else -> 5
                        }
                        val (ok, txt) = callBrowserMcpText(
                            context,
                            "browser_computer",
                            JSONObject().put("action", "scroll").put("scroll_direction", dir).put("scroll_amount", amount)
                        )
                        JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                    } else {
                        val dir = args.optString("direction", "down")
                        val dist = args.optString("distance", "medium")
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
                }

                "press_key" -> {
                    val key = args.optString("key", "home")
                    if (ChatContextBridge.isInBuiltBrowserActive() && key.equals("back", true)) {
                        val (ok, txt) = callBrowserMcpText(context, "browser_open_url", JSONObject().put("url", "back"))
                        JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
                    } else {
                        val res = VisualTaskOrchestrator.getInstance().handleKey(key)
                        JSONObject().put("status", if (res.success) "success" else "error")
                            .put("message", res.message)
                            .apply {
                                if (res.currentStateSummary.isNotBlank()) {
                                    put("current_state", res.currentStateSummary)
                                }
                            }
                    }
                }

                "inspect_screen", "read_screen" -> {
                    val a11y = AutomationAccessibilityService.instance
                    val pkg = a11y?.foregroundPackage ?: a11y?.getActiveRootNode()?.packageName?.toString() ?: "unknown"
                    if (ChatContextBridge.isInBuiltBrowserActive()) {
                        val appState = ChatContextBridge.inspectAppState()
                        val (ok, domTree) = callBrowserMcpText(
                            context,
                            "browser_read_page",
                            JSONObject().put("filter", "interactive").put("max_chars", 12_000)
                        )
                        val combined = "$appState\n\n--- ACTIVE IN-BUILT BROWSER PAGE ---\n${if (ok) domTree else "Could not read page: $domTree"}"
                        JSONObject().put("status", "success")
                            .put("foreground_app", "Aura In-Built Browser")
                            .put("screen_summary", combined)
                            .put("screen_sharing_active", ScreenCaptureManager.getInstance().isSharing.value)
                    } else if (isAuraPackage(pkg, context.packageName)) {
                        val appState = ChatContextBridge.inspectAppState()
                        JSONObject().put("status", "success")
                            .put("foreground_app", pkg)
                            .put("screen_summary", appState)
                            .put("screen_sharing_active", ScreenCaptureManager.getInstance().isSharing.value)
                    } else {
                        val orchestrator = VisualTaskOrchestrator.getInstance()
                        val inspection = orchestrator.inspectScreen(waitForContent = true)
                        JSONObject().put("status", "success")
                            .put("foreground_app", pkg)
                            .put("screen_summary", inspection)
                            .put("screen_sharing_active", ScreenCaptureManager.getInstance().isSharing.value)
                    }
                }

                "request_screen_share" -> {
                    val scm = ScreenCaptureManager.getInstance()
                    if (scm.isSharing.value) {
                        JSONObject().put("status", "success").put("message", "Screen sharing is already active.")
                    } else {
                        val fgPkg = AutomationAccessibilityService.instance?.foregroundPackage.orEmpty()
                        if (isAuraPackage(fgPkg, context.packageName)) {
                            JSONObject().put("status", "error")
                                .put("message", "Screen share is not needed while Aura AI is in the foreground. Use inspect_app_state() or check_task_status() for direct visibility.")
                        } else {
                            withContext(Dispatchers.Main) {
                                requestScreenShareCallback?.invoke()
                            }
                            JSONObject().put("status", "prompted")
                                .put("message", "Prompted user with the screen share dialog on screen.")
                        }
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

                "start_local_preview_server" -> {
                    val rawDir = args.optString("project_dir", "").trim()
                    val preferredPort = args.optInt("port", com.example.gemini.data.local.LocalPreviewServerRunner.DEFAULT_PORT)
                    val openInBrowser = args.optBoolean("open_in_browser", true)
                    val resolvedDir = rawDir.ifBlank {
                        AuraAgentEventBus.activeProject.value.workspaceDir.ifBlank {
                            "/data/user/0/com.termux/files/home/projects"
                        }
                    }
                    val serverRes = com.example.gemini.data.local.LocalPreviewServerRunner.startAndVerifyPreview(
                        context = context,
                        projectDir = resolvedDir,
                        preferredPort = preferredPort,
                        openInBrowser = openInBrowser
                    )
                    JSONObject().apply {
                        put("status", if (serverRes.success) "success" else "error")
                        put("url", serverRes.url)
                        put("port", serverRes.port)
                        put("server_type", serverRes.serverKind)
                        put("message", serverRes.message)
                    }
                }

                "execute_terminal_command" -> {
                    val command = args.optString("command", "").trim()
                    val workDir = args.optString("working_directory").takeIf { !it.isNullOrBlank() }
                        ?: "/data/user/0/com.termux/files/home/projects"

                    if (command.isBlank()) {
                        JSONObject().put("status", "error").put("message", "command cannot be empty")
                    } else if (com.example.gemini.data.local.LocalPreviewServerRunner.isInvalidPythonHtmlCommand(command)) {
                        // #13: Auto-block invalid `python3 index.html` and start verified Port 3000 HTTP preview server instead!
                        val htmlToken = command.split(Regex("\\s+")).firstOrNull {
                            it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
                        }
                        val targetDir = if (!htmlToken.isNullOrBlank()) {
                            val f = if (htmlToken.startsWith("/")) File(htmlToken) else File(workDir, htmlToken)
                            f.parentFile?.absolutePath ?: workDir
                        } else {
                            workDir
                        }
                        val serverRes = com.example.gemini.data.local.LocalPreviewServerRunner.startAndVerifyPreview(
                            context = context,
                            projectDir = targetDir,
                            preferredPort = com.example.gemini.data.local.LocalPreviewServerRunner.DEFAULT_PORT,
                            openInBrowser = true
                        )
                        JSONObject().apply {
                            put("status", if (serverRes.success) "success" else "error")
                            put("url", serverRes.url)
                            put("port", serverRes.port)
                            put("message", "Intercepted invalid python HTML execution and launched verified HTTP server: ${serverRes.message}")
                        }
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

    private suspend fun ensureBrowserOpen(context: Context) {
        if (!ChatContextBridge.isInBuiltBrowserActive()) {
            withContext(Dispatchers.Main) {
                ChatContextBridge.requestViewMode("BROWSER")
                bringAuraToForeground(context)
            }
            delay(180L)
        }
    }

    private suspend fun callBrowserMcpText(
        context: Context,
        toolName: String,
        args: JSONObject
    ): Pair<Boolean, String> {
        val result = BrowserMcp.call(context, toolName, args)
        val isErr = result.optBoolean("isError", false)
        val arr = result.optJSONArray("content")
        val sb = StringBuilder()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") {
                    val t = item.optString("text")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                }
            }
        }
        return !isErr to sb.toString().ifBlank { if (isErr) "Browser tool failed." else "Done." }
    }

    private suspend fun resolveBrowserRef(
        context: Context,
        query: String,
        tabId: String?
    ): Pair<String?, String> {
        val findArgs = JSONObject().put("query", query).apply {
            if (tabId != null) put("tab_id", tabId)
        }
        val (ok, txt) = callBrowserMcpText(context, "browser_find", findArgs)
        val ref = if (ok) Regex("""\b(ref_\d+)\b""").find(txt)?.groupValues?.get(1) else null
        return ref to txt
    }

    private suspend fun performBrowserClick(
        context: Context,
        refArg: String?,
        targetName: String?,
        tabId: String?
    ): JSONObject {
        var ref = refArg?.trim()?.takeIf { it.isNotBlank() }
        var discoveryNote = ""
        if (ref == null) {
            val query = targetName?.trim().orEmpty()
            if (query.isBlank()) {
                return JSONObject().put("status", "error").put("message", "Give either 'ref' or 'target_name' to click in the browser.")
            }
            val (foundRef, findTxt) = resolveBrowserRef(context, query, tabId)
            ref = foundRef
            discoveryNote = findTxt
        }
        if (ref == null) {
            val (_, pageTxt) = callBrowserMcpText(
                context,
                "browser_read_page",
                JSONObject().put("filter", "interactive").put("max_chars", 5000).apply {
                    if (tabId != null) put("tab_id", tabId)
                }
            )
            return JSONObject().apply {
                put("status", "error")
                put("message", "Could not find element '$targetName' on current browser page. Search output: $discoveryNote")
                put("current_state", pageTxt.take(4000))
            }
        }
        val clickArgs = JSONObject()
            .put("action", "left_click")
            .put("ref", ref)
            .put("action_summary", "Clicking ${targetName ?: ref}")
            .apply { if (tabId != null) put("tab_id", tabId) }
        val (ok, txt) = callBrowserMcpText(context, "browser_computer", clickArgs)
        return JSONObject().put("status", if (ok) "success" else "error").put("message", txt)
    }

    private suspend fun performBrowserType(
        context: Context,
        text: String,
        refArg: String?,
        targetName: String?,
        submit: Boolean,
        tabId: String?
    ): JSONObject {
        if (text.isEmpty()) {
            return JSONObject().put("status", "error").put("message", "text cannot be empty.")
        }
        var ref = refArg?.trim()?.takeIf { it.isNotBlank() }
        if (ref == null) {
            val searchQuery = targetName?.takeIf { it.isNotBlank() } ?: "search box input field textarea"
            ref = resolveBrowserRef(context, searchQuery, tabId).first
                ?: resolveBrowserRef(context, "input", tabId).first
        }
        if (ref == null) {
            val (_, pageTxt) = callBrowserMcpText(
                context,
                "browser_read_page",
                JSONObject().put("filter", "interactive").put("max_chars", 5000).apply {
                    if (tabId != null) put("tab_id", tabId)
                }
            )
            return JSONObject().apply {
                put("status", "error")
                put("message", "Could not find a text input box on current browser page.")
                put("current_state", pageTxt.take(4000))
            }
        }

        val formArgs = JSONObject()
            .put("ref", ref)
            .put("value", text)
            .put("action_summary", "Typing into ${targetName ?: ref}")
            .apply { if (tabId != null) put("tab_id", tabId) }
        val (setOk, setMsg) = callBrowserMcpText(context, "browser_form_input", formArgs)
        if (!setOk) {
            // Fallback: focus element via click then send real DevTools/WebView keyboard text
            callBrowserMcpText(
                context,
                "browser_computer",
                JSONObject().put("action", "left_click").put("ref", ref).apply { if (tabId != null) put("tab_id", tabId) }
            )
            val typedText = if (submit) "$text\n" else text
            val (typeOk, typeMsg) = callBrowserMcpText(
                context,
                "browser_computer",
                JSONObject().put("action", "type").put("text", typedText).apply { if (tabId != null) put("tab_id", tabId) }
            )
            if (submit && typeOk) {
                BrowserSessionManager.instance.waitForPageLoad(tabId, 4_000L)
            }
            return JSONObject().put("status", if (typeOk) "SUCCESS" else "FAILED")
                .put("verified", typeOk)
                .put("message", typeMsg)
        }

        if (submit) {
            val enterArgs = JSONObject()
                .put("action", "key")
                .put("text", "Enter")
                .put("action_summary", "Submitting")
                .apply { if (tabId != null) put("tab_id", tabId) }
            val (enterOk, enterMsg) = callBrowserMcpText(context, "browser_computer", enterArgs)
            BrowserSessionManager.instance.waitForPageLoad(tabId, 4_500L)
            val tab = BrowserSessionManager.instance.getTab(tabId)
            val finalUrl = withContext(Dispatchers.Main) { tab?.webView?.url ?: tab?.url.orEmpty() }
            return JSONObject().put("status", "SUCCESS")
                .put("verified", enterOk)
                .put("message", "Typed \"$text\" into browser input ($ref) and pressed Enter. Current page: $finalUrl. $enterMsg")
        }

        return JSONObject().put("status", "SUCCESS")
            .put("verified", true)
            .put("message", "Typed \"$text\" into browser input ($ref). $setMsg")
    }

    private fun isAuraPackage(pkg: String, ownPkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg == ownPkg || pkg == "com.example.gemini" || pkg == "com.aura.ai"
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
