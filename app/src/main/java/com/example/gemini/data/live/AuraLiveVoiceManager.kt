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
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisMcp
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalTerminalBridge
import com.example.gemini.data.vision.AuraScreenShareBridge
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.ui.browser.BrowserSessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

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
    private val sessionTurns = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val assistantTextBuffer = StringBuilder()
    private val accumulatedToolCalls = java.util.Collections.synchronizedList(mutableListOf<ToolCall>())

    fun sendVideoFrame(jpegBytes: ByteArray) {
        webSocket?.sendVideoFrame(jpegBytes)
    }

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
        ChatContextBridge.ensureVoiceChat()

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

        // Flush any pending voice turn
        val pendingText = assistantTextBuffer.toString().trim()
        assistantTextBuffer.clear()
        val pendingTools = accumulatedToolCalls.toList()
        accumulatedToolCalls.clear()
        if (pendingText.isNotEmpty() || pendingTools.isNotEmpty()) {
            ChatContextBridge.postVoiceMessage(MessageRole.ASSISTANT, pendingText, pendingTools)
        }

        try {
            AuraScreenShareBridge.stopScreenShare()
        } catch (_: Exception) {}

        if (sessionTurns.isNotEmpty()) {
            val summary = sessionTurns.takeLast(12).joinToString("\n")
            AuraMemoryManager.rememberFact("Voice session log:\n$summary", "VOICE_LOG")
            sessionTurns.clear()
        }

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
        sb.append("You have complete authority over phone automation, internal app navigation, code writing, and terminal execution.\n\n")

        sb.append("### CRITICAL LANGUAGE DIRECTIVE:\n")
        sb.append("- You MUST ALWAYS speak in conversational Hindi or Hinglish (Hindi mixed with English words as spoken naturally in India).\n")
        sb.append("- NEVER switch to Portuguese, Spanish, or any other foreign language under ANY circumstance!\n")
        sb.append("- If you hear ambient background noise, TV sound, music, or someone speaking another language in the room, COMPLETELY IGNORE IT and continue talking ONLY to the user in Hindi/Hinglish.\n\n")

        sb.append("### IN-APP IDE & BROWSER WORKFLOW:\n")
        sb.append("- When user asks to make a website, calculator, HTML page, or project:\n")
        sb.append("  1. ALWAYS call write_project_file to save the HTML/CSS/JS file! This automatically opens the file in the app's IDE code editor tabs and switches the app view to IDE.\n")
        sb.append("  2. Call execute_terminal_command to run python3 -m http.server 8080 & or test the code in Termux.\n")
        sb.append("  3. Call open_website(url=\"http://localhost:8080\", in_app=true) to immediately show the website live in the app's built-in browser!\n")
        sb.append("  4. Call browser_inspect_page to inspect console errors. If any error appears, IMMEDIATELY call write_project_file to fix the bug (self-healing)!\n")
        sb.append("  5. You can also call jarvis_open_screen(screen=\"ide\") or jarvis_open_screen(screen=\"browser\") to switch tabs.\n\n")

        sb.append("### GITHUB & REPOSITORY WORKFLOWS IN VOICE MODE:\n")
        sb.append("- When user asks to clone a project (e.g. 'ye repo clone karo', 'kisi ka project apna banana'):\n")
        sb.append("  Call github_clone(repo_url=\"...\"). It clones into /projects and opens it in the in-app IDE tabs!\n")
        sb.append("- When user asks to push changes to GitHub: call github_push(commit_message=\"...\").\n")
        sb.append("- When user asks to pull updates or check git status: call github_pull() or github_status().\n\n")

        sb.append("### SCREEN SHARING & SEEING THE SCREEN:\n")
        sb.append("- If user asks to share screen, asks you to see what's on screen, or needs your visual assistance on the phone: IMMEDIATELY call request_screen_share().\n")
        sb.append("- Once screen sharing is active, you receive real-time screen frames.\n")
        sb.append("- When screen viewing is finished, call stop_screen_share().\n\n")

        sb.append("### STRICT ACTION & ZERO-HALLUCINATION POLICY:\n")
        sb.append("- You MUST ALWAYS execute the matching tool immediately when asked to take action. NEVER lie, pretend, or claim 'I am creating files' without actually invoking write_project_file or execute_terminal_command!\n")
        sb.append("- When user asks to send a WhatsApp message: call send_whatsapp_message. NEVER say 'Maine message send kar diya' unless the tool returns success. If it returns needs_tap, tell the user 'Maine WhatsApp open karke message type kar diya hai, kripya Send par tap kar dijiye'.\n")
        sb.append("- When user says to open WhatsApp -> call open_app(app_name=\"WhatsApp\").\n")
        sb.append("- When user says to tap a contact/person (e.g. 'Abhishek') -> call click_ui_element(element_id_or_text=\"Abhishek\").\n")
        sb.append("- When user says to bring Aura AI back to front -> call open_aura_app().\n")
        sb.append("- Keep your spoken replies concise, friendly, confident, and direct. Call tools instantly.\n\n")

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
        sessionTurns.add("Assistant: $textChunk")
        assistantTextBuffer.append(textChunk)
    }

    override fun onUserTextReceived(textChunk: String) {
        sessionTurns.add("User: $textChunk")
        AuraMemoryManager.learnFromConversation(textChunk)
        ChatContextBridge.postVoiceMessage(MessageRole.USER, textChunk)
    }

    override fun onInterrupted() {
        // User barged in: instantly kill audio playback and listen
        audioPlayer?.stopPlayback()
        _phase.value = Phase.LISTENING
        _statusText.value = "Listening..."
    }

    override fun onTurnCompleted() {
        val replyText = assistantTextBuffer.toString().trim()
        assistantTextBuffer.clear()

        val toolCallsCopy = accumulatedToolCalls.toList()
        accumulatedToolCalls.clear()

        if (replyText.isNotEmpty() || toolCallsCopy.isNotEmpty()) {
            ChatContextBridge.postVoiceMessage(MessageRole.ASSISTANT, replyText, toolCallsCopy)
        }

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

        val resolvedToolType = when (name) {
            "execute_terminal_command" -> ToolType.BASH
            "write_project_file" -> ToolType.EDIT_FILE
            "read_project_file" -> ToolType.VIEW_FILE
            else -> ToolType.UNKNOWN
        }

        val toolCallRecord = ToolCall(
            id = callId,
            name = name,
            toolType = resolvedToolType,
            command = args.toString(),
            status = "RUNNING"
        )
        accumulatedToolCalls.add(toolCallRecord)

        scope?.launch(Dispatchers.IO) {
            val result = executeTool(name, args)
            val isErr = result.optString("status") == "error"
            val finishedCall = toolCallRecord.copy(
                status = if (isErr) "FAILED" else "SUCCESS",
                output = result.toString()
            )
            val idx = accumulatedToolCalls.indexOfFirst { it.id == callId }
            if (idx >= 0) {
                accumulatedToolCalls[idx] = finishedCall
            }
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
                    val appName = args.optString("app_name", "").ifBlank { args.optString("name", "") }.trim()
                    if (appName.equals("aura", true) || appName.equals("aura ai", true) || appName.equals("antigem", true)) {
                        bringAuraToForeground(context)
                        JSONObject().put("status", "success").put("message", "Opened Aura AI and brought to foreground")
                    } else {
                        val directPackages = mapOf(
                            "whatsapp" to "com.whatsapp",
                            "whatsapp business" to "com.whatsapp.w4b",
                            "youtube" to "com.google.android.youtube",
                            "chrome" to "com.android.chrome",
                            "settings" to "com.android.settings",
                            "camera" to "com.google.android.GoogleCamera",
                            "photos" to "com.google.android.apps.photos",
                            "gallery" to "com.google.android.apps.photos",
                            "maps" to "com.google.android.apps.maps",
                            "gmail" to "com.google.android.gm",
                            "telegram" to "org.telegram.messenger",
                            "instagram" to "com.instagram.android"
                        )
                        val pkg = directPackages[appName.lowercase()]
                        if (pkg != null) {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                JSONObject().put("status", "success").put("message", "Opened $appName")
                            } else {
                                AndroidAutomation.call(context, "android_launch_app", JSONObject().apply {
                                    put("name", appName)
                                    put("package", pkg)
                                })
                            }
                        } else {
                            AndroidAutomation.call(context, "android_launch_app", JSONObject().apply {
                                put("name", appName)
                                put("package", appName)
                            })
                        }
                    }
                }

                "open_aura_app" -> {
                    bringAuraToForeground(context)
                    JSONObject().put("status", "success").put("message", "Brought Aura AI to foreground")
                }

                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient_name", "")
                    val message = args.optString("message_text", "")
                    val appNumber = args.optInt("app_number", 1)
                    val confirmed = args.optBoolean("confirmed", true)

                    sendWhatsAppMessage(context, recipient, message, appNumber, confirmed)
                }

                "click_ui_element" -> {
                    val target = args.optString("element_id_or_text", "").ifBlank { args.optString("text", "") }
                    AndroidAutomation.call(context, "android_tap", JSONObject().apply {
                        put("text", target)
                        put("element_id", target)
                    })
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    val submit = args.optBoolean("submit", false)
                    AndroidAutomation.call(context, "android_type_text", JSONObject().apply {
                        put("text", text)
                        put("submit", submit)
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
                    val url = args.optString("url", "https://google.com").trim()
                    val inApp = args.optBoolean("in_app", true)
                    if (inApp) {
                        withContext(Dispatchers.Main) {
                            try {
                                BrowserSessionManager.instance.openUrl(url, newTab = true)
                                JarvisController.vm.requestViewMode("BROWSER")
                            } catch (e: Exception) {
                                Log.w(TAG, "In-app browser failed: ${e.message}")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                        JSONObject().put("status", "success").put("message", "Opened $url in app browser")
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        JSONObject().put("status", "success").put("message", "Opened $url in external browser")
                    }
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

                // --- 4. Terminal & Coding Tools ---
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
                            put("output", res.output.ifBlank { "(executed successfully with no output)" })
                            put("duration_ms", res.durationMs)
                        }
                    }
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

                        // Directly open in IDE code editor tabs and switch view mode!
                        withContext(Dispatchers.Main) {
                            try {
                                val projectDir = targetFile.parentFile ?: baseDir
                                TermuxDaemonManager.setActiveProject(ProjectItem(name = projectDir.name, path = projectDir.absolutePath))
                                TermuxDaemonManager.openOrSelectTab(
                                    path = targetFile.absolutePath,
                                    name = targetFile.name,
                                    content = content
                                )
                                JarvisController.vm.requestViewMode("IDE")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error integrating file into IDE: ${e.message}")
                            }
                        }

                        JSONObject().apply {
                            put("status", "success")
                            put("path", targetFile.absolutePath)
                            put("bytes_written", content.length)
                            put("message", "File saved at ${targetFile.name} and opened in IDE editor")
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

                // --- 5. Screen Share Tools ---
                "request_screen_share" -> {
                    val promptResult = AuraScreenShareBridge.requestScreenShare()
                    JSONObject().put("status", "success").put("message", promptResult)
                }

                "stop_screen_share" -> {
                    AuraScreenShareBridge.stopScreenShare()
                    JSONObject().put("status", "success").put("message", "Screen share stopped.")
                }

                // --- 6. GitHub & Git Voice Tools ---
                "github_clone" -> {
                    val repoUrl = args.optString("repo_url", "").trim()
                    val folderName = args.optString("folder_name").takeIf { !it.isNullOrBlank() }
                        ?: repoUrl.substringAfterLast("/").removeSuffix(".git").ifBlank { "project" }

                    val projectsDir = File("/data/user/0/com.termux/files/home/projects")
                    if (!projectsDir.exists()) projectsDir.mkdirs()
                    val targetDir = File(projectsDir, folderName)

                    val cloneCmd = if (targetDir.exists()) {
                        "cd '${targetDir.absolutePath}' && git pull"
                    } else {
                        "git clone '$repoUrl' '${targetDir.absolutePath}'"
                    }

                    val res = LocalEnvironmentManager.executeCommand(
                        command = cloneCmd,
                        context = context,
                        workingDir = projectsDir.absolutePath,
                        timeoutSeconds = 90
                    )

                    if (targetDir.exists()) {
                        withContext(Dispatchers.Main) {
                            try {
                                TermuxDaemonManager.setActiveProject(ProjectItem(name = folderName, path = targetDir.absolutePath))
                                JarvisController.vm.requestViewMode("IDE")
                            } catch (_: Exception) {}
                        }
                    }

                    JSONObject().apply {
                        put("status", if (res.exitCode == 0) "success" else "error")
                        put("exit_code", res.exitCode)
                        put("path", targetDir.absolutePath)
                        put("output", res.output.ifBlank { "Repository cloned and opened in IDE" })
                    }
                }

                "github_pull" -> {
                    val activeProj = TermuxDaemonManager.activeProject.value
                    val projPath = args.optString("project_name").takeIf { !it.isNullOrBlank() }?.let {
                        if (it.startsWith("/")) it else "/data/user/0/com.termux/files/home/projects/$it"
                    } ?: activeProj?.path ?: "/data/user/0/com.termux/files/home/projects"

                    val res = LocalEnvironmentManager.executeCommand(
                        command = "git pull",
                        context = context,
                        workingDir = projPath,
                        timeoutSeconds = 40
                    )
                    JSONObject().apply {
                        put("status", if (res.exitCode == 0) "success" else "error")
                        put("exit_code", res.exitCode)
                        put("output", res.output.ifBlank { "Already up to date." })
                    }
                }

                "github_push" -> {
                    val commitMsg = args.optString("commit_message", "update from Aura AI").trim()
                    val activeProj = TermuxDaemonManager.activeProject.value
                    val projPath = args.optString("project_name").takeIf { !it.isNullOrBlank() }?.let {
                        if (it.startsWith("/")) it else "/data/user/0/com.termux/files/home/projects/$it"
                    } ?: activeProj?.path ?: "/data/user/0/com.termux/files/home/projects"

                    val sanitizedMsg = commitMsg.replace("\"", "\\\"")
                    val cmd = "git add -A && git commit -m \"$sanitizedMsg\" && git push"
                    val res = LocalEnvironmentManager.executeCommand(
                        command = cmd,
                        context = context,
                        workingDir = projPath,
                        timeoutSeconds = 60
                    )
                    JSONObject().apply {
                        put("status", if (res.exitCode == 0) "success" else "error")
                        put("exit_code", res.exitCode)
                        put("output", res.output.ifBlank { "Pushed changes to GitHub." })
                    }
                }

                "github_status" -> {
                    val activeProj = TermuxDaemonManager.activeProject.value
                    val projPath = args.optString("project_name").takeIf { !it.isNullOrBlank() }?.let {
                        if (it.startsWith("/")) it else "/data/user/0/com.termux/files/home/projects/$it"
                    } ?: activeProj?.path ?: "/data/user/0/com.termux/files/home/projects"

                    val res = LocalEnvironmentManager.executeCommand(
                        command = "git status --short && git branch -v",
                        context = context,
                        workingDir = projPath,
                        timeoutSeconds = 20
                    )
                    JSONObject().apply {
                        put("status", "success")
                        put("project_path", projPath)
                        put("output", res.output.ifBlank { "Clean working directory, nothing to commit." })
                    }
                }

                // --- 7. In-App Browser Inspection & Self-Healing ---
                "browser_inspect_page" -> {
                    val bsm = BrowserSessionManager.instance
                    val tab = bsm.getActiveTab()
                    val consoleLogs = bsm.getConsoleLogs(tab?.id, levelFilter = "ERROR", limit = 15)
                    val netErrors = bsm.getNetworkErrors(tab?.id, limit = 10)

                    JSONObject().apply {
                        put("status", "success")
                        put("tab_url", tab?.url ?: "No active tab")
                        put("tab_title", tab?.title ?: "")
                        put("console_errors", org.json.JSONArray().apply {
                            consoleLogs.forEach { put("[${it.level}] ${it.message} (${it.sourceId}:${it.lineNumber})") }
                        })
                        put("network_errors", org.json.JSONArray().apply {
                            netErrors.forEach { put("${it.method} ${it.url} -> HTTP ${it.statusCode} (${it.description})") }
                        })
                    }
                }

                else -> {
                    if (name.startsWith("jarvis_")) {
                        JarvisMcp.call(context, name, args)
                    } else {
                        JSONObject().put("status", "error").put("message", "Unknown tool: $name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error ($name): ${e.message}", e)
            JSONObject().put("status", "error").put("message", e.message ?: "Execution failed")
        }
    }

    private suspend fun sendWhatsAppMessage(
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
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                return JSONObject().put("status", "error").put("message", "Could not open WhatsApp: ${e2.message}")
            }
        }

        // Wait and verify if send button is clicked via Accessibility
        var clicked = false
        for (i in 0 until 14) {
            delay(250)
            val svc = AutomationAccessibilityService.instance
            val root = svc?.rootInActiveWindow
            if (root != null) {
                val sendNode = findWhatsAppSendButton(root)
                if (sendNode != null) {
                    val didClick = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (didClick) {
                        clicked = true
                        Log.d(TAG, "Tapped and verified WhatsApp send button via Accessibility!")
                        break
                    }
                }
            }
        }

        return if (clicked) {
            JSONObject().put("status", "success").put("message", "Message verified and sent to $recipient on WhatsApp.")
        } else {
            JSONObject().put("status", "needs_tap").put("message", "WhatsApp opened with text filled for $recipient, but could not auto-click Send button. User needs to tap Send.")
        }
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

    private fun bringAuraToForeground(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring Aura AI to foreground: ${e.message}", e)
        }
    }
}
