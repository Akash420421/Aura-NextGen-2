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
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    data class ToolCallItem(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
        fun onToolCallsBatch(calls: List<ToolCallItem>) {
            for (c in calls) {
                onToolCall(c.id, c.name, c.args)
            }
        }
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(45, TimeUnit.SECONDS)
            .build()
    }

    private val client = sharedClient

    private var activeModel: String = when (model) {
        GeminiConstants.MODEL_3_8_LIVE -> GeminiConstants.MODEL_3_8_LIVE
        GeminiConstants.MODEL_3_1_FLASH -> GeminiConstants.MODEL_3_1_FLASH
        else -> if (model.isNotBlank()) model else GeminiConstants.DEFAULT_MODEL
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var setupCompatTier: Int = 0
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
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
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
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = reason.contains("API_KEY_INVALID", ignoreCase = true) ||
                                       reason.contains("API key not valid", ignoreCase = true) ||
                                       reason.contains("API key", ignoreCase = true) ||
                                       reason.contains("key invalid", ignoreCase = true)
                    if (isApiKeyError) {
                        Log.e(TAG, "Gemini rejected session due to invalid API key (code=$code, reason=$reason)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    val isQuotaError = reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                                       reason.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "Gemini rate-limited (code=$code, reason=$reason). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (code == 1008) {
                        val isModelError = reason.contains("BidiGenerateContent", ignoreCase = true) ||
                                           reason.contains("not found", ignoreCase = true) ||
                                           reason.contains("not supported", ignoreCase = true)
                        if (isModelError && activeModel != GeminiConstants.MODEL_3_8_LIVE) {
                            Log.w(TAG, "Model $activeModel rejected ($reason). Falling back to ${GeminiConstants.MODEL_3_8_LIVE}")
                            activeModel = GeminiConstants.MODEL_3_8_LIVE
                            listener.onConnectionStateChanged("RECONNECTING...")
                            scheduleReconnect()
                            return
                        }
                        if (setupCompatTier < 2 && !isApiKeyError) {
                            setupCompatTier++
                            Log.w(TAG, "Setup closed with 1008 ($reason); degrading to setupCompatTier=$setupCompatTier and reconnecting.")
                            listener.onConnectionStateChanged("RECONNECTING...")
                            scheduleReconnect()
                            return
                        }
                        listener.onError("Gemini setup rejected: $reason", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val responseBody = try { response?.body?.string() } catch (_: Exception) { null }
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: code=$code, errorMsg=$errorMsg, body=$responseBody", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = code == 400 || code == 401 || code == 403 ||
                            errorMsg.contains("400") || errorMsg.contains("403") || errorMsg.contains("401") ||
                            errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                            errorMsg.contains("API key not valid", ignoreCase = true) ||
                            (responseBody?.contains("API_KEY_INVALID", ignoreCase = true) == true) ||
                            (responseBody?.contains("API key not valid", ignoreCase = true) == true)

                    val isQuotaError = code == 429 ||
                            errorMsg.contains("429") ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            errorMsg.contains("quota", ignoreCase = true) ||
                            (responseBody?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true)

                    if (isApiKeyError) {
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    if (isQuotaError) {
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}", isFatal = true, errorCode = "CONNECTION_FAILED")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = promptProvider?.invoke() ?: systemPrompt
        val tier = setupCompatTier
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
                    put("temperature", 0.55)
                    if (tier == 0 && !activeModel.contains("3.8")) {
                        // Legacy 2.0/3.1 models benefit from zero thinking budget; 3.8 Live rejects thinking_level/budget if unsupported
                        put("thinking_config", JSONObject().apply {
                            put("thinking_budget", 0)
                        })
                    }
                })
                if (tier <= 1) {
                    put("input_audio_transcription", JSONObject())
                    put("output_audio_transcription", JSONObject())
                }
                if (tier == 0) {
                    put("realtime_input_config", JSONObject().apply {
                        put("automatic_activity_detection", JSONObject().apply {
                            put("disabled", false)
                            put("start_of_speech_sensitivity", "START_SENSITIVITY_HIGH")
                            put("end_of_speech_sensitivity", "END_SENSITIVITY_LOW")
                            put("prefix_padding_ms", 80)
                            put("silence_duration_ms", 550)
                        })
                    })
                    put("context_window_compression", JSONObject().apply {
                        put("trigger_tokens", 65536)
                        put("sliding_window", JSONObject().apply {
                            put("target_tokens", 16384)
                        })
                    })
                }
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", buildToolDeclarations())
                }))
            })
        }
        val json = setup.toString()
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live (compatTier=$tier)")
        ws.send(json)
    }

    private fun buildToolDeclarations(): JSONArray {
        return JSONArray().apply {
            // 0. start_new_chat_task
            put(JSONObject().apply {
                put("name", "start_new_chat_task")
                put("description", "Instructs the app to create ONE dedicated new chat and start a brand-new coding task/website/app build there. Provide the complete request as 'prompt'. STRICT RULE (#9): NEVER call this to answer a Questionnaire/Mode card (like 'Standard mode', 'Agent mode', 'Submit', 'Skip' — ALWAYS use answer_chat_question instead!) and NEVER call it repeatedly for a task that is already running (use check_task_status or send_task_update).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The complete task instructions to execute in the new chat.")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                })
            })

            // 0b. check_task_status
            put(JSONObject().apply {
                put("name", "check_task_status")
                put("description", "Checks the live real-time progress, files created, and localhost server status of active tasks/websites without needing screen share. Call whenever the user asks for status or progress (e.g. 'website kitni ban gayi?', 'status kya hai?', 'kahan tak kaam hua?').")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0c. send_task_update
            put(JSONObject().apply {
                put("name", "send_task_update")
                put("description", "Sends a modification, correction, or new feature to the running website/task (e.g. 'dark mode add karo', 'button color change karo').")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("instruction", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The exact update or change to apply to the running task.")
                        })
                    })
                    put("required", JSONArray().put("instruction"))
                })
            })

            // 0d. inspect_app_state
            put(JSONObject().apply {
                put("name", "inspect_app_state")
                put("description", "Inspects the whole app: lists active chats, running background tasks, and active localhost web servers.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0e. open_live_chat
            put(JSONObject().apply {
                put("name", "open_live_chat")
                put("description", "Immediately switches to the chat screen displaying the active voice session thread while keeping the live voice call active. Call when user asks 'live chat open karo', 'chat dikhao', 'open live chat', 'hamari chat kholo', 'current chat open karo', or 'live chat dikhao'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0e-2. open_current_voice_chat (backward compatibility)
            put(JSONObject().apply {
                put("name", "open_current_voice_chat")
                put("description", "Opens the active ongoing voice conversation in the chat screen so the user can see the messages. Call when user asks 'hamari chat kholo', 'current chat open karo', or 'chat dikhao'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0f. open_previous_voice_chat
            put(JSONObject().apply {
                put("name", "open_previous_voice_chat")
                put("description", "Opens the previous voice session in the chat screen. Call when user asks 'pichhli chat kholo' or 'previous chat open karo'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0f-2. create_new_chat
            put(JSONObject().apply {
                put("name", "create_new_chat")
                put("description", "Opens a fresh new chat session inside Aura app with welcome suggestion cards. Call immediately when user asks 'new chat open karo', 'nayi chat shuru karo', 'start a new chat', 'open new chat', or 'fresh chat banao'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 0f-3. open_chat_by_name
            put(JSONObject().apply {
                put("name", "open_chat_by_name")
                put("description", "Opens ANY specific chat, pinned chat, archived chat, old project chat, or renamed conversation inside Aura on screen by searching title, project, pinned status, or database metadata (closes Drawer/Settings/Voice tab cleanly and verifies the active chat switched). Call when user asks e.g. 'jo chat pin hai wo kholo', 'pinned chat open karo', 'Flipkart E-Commerce website wala chat kholo', 'Project Alpha wala chat kholo', or 'Meeting Notes chat open karo'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("chat_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The name, project, keyword, or pin reference of the chat to open (e.g. 'pinned chat', 'Flipkart E-Commerce Website Design', 'Project Alpha')")
                        })
                    })
                    put("required", JSONArray().apply { put("chat_name") })
                })
            })

            // 0f-4. open_chat_by_id
            put(JSONObject().apply {
                put("name", "open_chat_by_id")
                put("description", "Opens a conversation on screen by its exact or partial conversation ID.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("chat_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The ID of the conversation to open.")
                        })
                    })
                    put("required", JSONArray().apply { put("chat_id") })
                })
            })

            // 0f-5. list_recent_chats
            put(JSONObject().apply {
                put("name", "list_recent_chats")
                put("description", "Lists recent saved conversations and projects with metadata (ID, title, summary, project path) from Aura's memory database. Call when user asks what chats exist or asks about previous projects.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional search keyword to filter chat titles or projects.")
                        })
                        put("limit", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Maximum number of chats to return (default 10).")
                        })
                    })
                })
            })

            // 0f-6. send_message_to_chat
            put(JSONObject().apply {
                put("name", "send_message_to_chat")
                put("description", "Natively dispatches a message, prompt, or question directly into Aura AI's chat without using accessibility or requesting screen share. Supports sending to 'CURRENT' chat, a 'NEW' chat, or a specific chat ID/name.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The message or prompt to send into the chat.")
                        })
                        put("target", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Target chat: 'CURRENT' (default), 'NEW' (starts a new chat first), or a specific chat title / ID.")
                        })
                        put("auto_send", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Whether to automatically send the message (default true).")
                        })
                    })
                    put("required", JSONArray().apply { put("message") })
                })
            })

            // 0g. delegate_task_to_app_agent (alias)
            put(JSONObject().apply {
                put("name", "delegate_task_to_app_agent")
                put("description", "Delegates a task to the app's internal agent.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The task prompt.")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                })
            })

            // 1. open_app
            put(JSONObject().apply {
                put("name", "open_app")
                put("description", "Opens an app installed on the user's phone, e.g. YouTube, WhatsApp, Camera, Settings, Chrome, Instagram, Calculator. If multiple instances exist, app_number specifies 1 or 2.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The name of the app to open (e.g. \"YouTube\", \"WhatsApp\", \"Instagram\", \"Camera\").")
                        })
                        put("app_number", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional 1-based index (1 or 2) when opening a dual/cloned app instance.")
                        })
                    })
                    put("required", JSONArray().put("app_name"))
                })
            })

            // 2. open_website
            put(JSONObject().apply {
                put("name", "open_website")
                put("description", "Opens multiple website URLs in Chrome (when external=true) or Aura In-Built Browser. For opening/controlling ONE site or search inside Aura's In-Built Browser, prefer `open_in_app_browser` so DOM controls (`browser_click`, `browser_type`, `browser_scroll`) work immediately.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("urls", JSONObject().apply {
                            put("type", "ARRAY")
                            put("description", "Array of website URLs or domain names to open.")
                            put("items", JSONObject().apply { put("type", "STRING") })
                        })
                        put("url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Single website URL to open.")
                        })
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional search query or domain name.")
                        })
                        put("external", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Set to true ONLY if user explicitly commanded 'Chrome mein kholo' or 'external browser'. Defaults to false (In-Built Browser).")
                        })
                    })
                })
            })

            // 3. search_in_chrome
            put(JSONObject().apply {
                put("name", "search_in_chrome")
                put("description", "Opens the EXTERNAL Google Chrome app and searches there. ONLY call if user EXPLICITLY says 'Chrome mein search karo' or 'Chrome app mein kholo'. NEVER use for Aura's In-Built Browser (use `open_in_app_browser` or `browser_type` instead).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Query or topic to search in Google Chrome.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 4. search_and_play_youtube
            put(JSONObject().apply {
                put("name", "search_and_play_youtube")
                put("description", "Resolves a specific OR vague song/video request (e.g. 'play Tum Hi Ho', 'koi apni marzi se accha gaana laga do', 'in-built browser mein gaana chalao') directly to a video and IMMEDIATELY PLAYS IT with auto ad-skip armed. Never scrolls endlessly.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Song/video name, or user's mood/request if they asked Aura to pick a good song.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 5. search_youtube
            put(JSONObject().apply {
                put("name", "search_youtube")
                put("description", "Opens YouTube search results page WITHOUT auto-playing. Use ONLY when the user explicitly wants to browse results ('sirf search results dikhao, play mat karo'). To PLAY any song/video, ALWAYS use search_and_play_youtube instead.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The query/topic to search on YouTube.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 6. media_playback_control
            put(JSONObject().apply {
                put("name", "media_playback_control")
                put("description", "Controls active playback (in-built browser video/audio, native YouTube/Shorts/Reels, OR phone media player): play, pause, toggle, next, previous, stop, forward (+10s or seconds), rewind (-10s or seconds).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("play"); put("pause"); put("toggle"); put("next"); put("previous"); put("stop"); put("forward"); put("rewind")
                            })
                        })
                        put("seconds", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional seek seconds for forward/rewind (default 10).")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // 6b. youtube_action
            put(JSONObject().apply {
                put("name", "youtube_action")
                put("description", "Controls smart YouTube & short-video actions (native YouTube app, YouTube Shorts, Instagram Reels, OR in-built browser): skip visible Skip Ad button ('skip_ad'), play first visible video card ('play_first'), 'like', 'subscribe', 'fullscreen', 'forward' (skip +10s), 'rewind' (back -10s), 'play', 'pause', 'next' (swipes next on Shorts/Reels), 'previous'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("skip_ad"); put("play_first"); put("like"); put("subscribe"); put("fullscreen")
                                put("forward"); put("rewind"); put("play"); put("pause"); put("next"); put("previous")
                            })
                        })
                        put("seconds", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional seconds for forward/rewind (default 10).")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // 7. send_whatsapp_message
            put(JSONObject().apply {
                put("name", "send_whatsapp_message")
                put("description", "Opens WhatsApp to a contact and sends/queues a message. If multiple instances exist, app_number specifies 1 or 2.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("recipient_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Name of the contact as spoken by the user (e.g. \"Rahul\", \"Priya\", \"Dad\").")
                        })
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The text message content to send.")
                        })
                        put("app_number", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                        })
                        put("confirmed", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True if user confirmed sending.")
                        })
                    })
                    put("required", JSONArray().apply { put("recipient_name"); put("message") })
                })
            })

            // 7b. reply_whatsapp_notification (#18)
            put(JSONObject().apply {
                put("name", "reply_whatsapp_notification")
                put("description", "Replies to an incoming WhatsApp message from notification Smart Confirm queue in background using RemoteInput (fast, without opening WhatsApp), or dismisses it. Use when user confirms sending a suggested draft ('haan bhej do') or dictates a reply ('Rahul ko reply karo...').")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("sender_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Sender name to reply to (leave blank to reply to the latest pending WhatsApp notification).")
                        })
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Reply message text. Omit or leave blank to send the pre-generated suggested draft.")
                        })
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "'send' (default) to reply now, or 'dismiss' to ignore/clear the pending message.")
                        })
                    })
                })
            })

            // 7c. configure_whatsapp_auto_reply (#18)
            put(JSONObject().apply {
                put("name", "configure_whatsapp_auto_reply")
                put("description", "Checks or configures WhatsApp Notification Auto-Reply mode: Smart Confirm Mode (default, asks before sending) vs Direct Auto-Reply for Saved Contacts (replies automatically with 120s cooldown; OTP/groups still confirm first) vs Off.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("mode", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "'status' (read current mode & permission), 'smart_confirm' (ask before sending), 'direct_saved_contacts' (auto-reply to saved 1:1 contacts), or 'off'.")
                        })
                        put("ai_draft", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Optional toggle for AI contextual draft generation.")
                        })
                        put("fallback_template", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional custom fallback reply message template.")
                        })
                        put("open_permission_settings", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Set true if Notification Reader permission is missing and user asked to open Settings.")
                        })
                    })
                })
            })

            // 8. whatsapp_call
            put(JSONObject().apply {
                put("name", "whatsapp_call")
                put("description", "Initiates a WhatsApp voice call or video call to a contact (e.g. \"WhatsApp call Mom\", \"WhatsApp video call Rahul\").")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("recipient_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Name of the contact to call.")
                        })
                        put("call_type", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "\"voice\" for voice call or \"video\" for video call.")
                        })
                        put("app_number", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                        })
                    })
                    put("required", JSONArray().apply { put("recipient_name"); put("call_type") })
                })
            })

            // 9. call_contact
            put(JSONObject().apply {
                put("name", "call_contact")
                put("description", "Places a standard cellular phone call to a saved contact by spoken name, e.g. \"call mom\", \"call Rahul\", \"phone Dad\".")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contact_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The contact's name as spoken by user, e.g. \"mom\", \"Rahul\".")
                        })
                    })
                    put("required", JSONArray().put("contact_name"))
                })
            })

            // 10. set_volume (#11)
            put(JSONObject().apply {
                put("name", "set_volume")
                put("description", "Instantly controls and readback-verifies device volume via VolumeController across streams ('music'/'media', 'ring', 'alarm', 'notification', 'call', 'all'): 'set', 'increase', 'decrease', 'mute', 'unmute', or 'get'. Supports Hindi/Hinglish phrases and relative steps ('thoda badhao', 'volume 70 karo', 'awaz kam karo', 'mute karo'). Returns verified actual_percent, permission_required if DND blocks ring/notification, and confirmation message such as 'Volume 60% kar diya'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("set"); put("increase"); put("decrease"); put("mute"); put("unmute"); put("get")
                            })
                            put("description", "Action: 'set', 'increase', 'decrease', 'mute', 'unmute', or 'get'.")
                        })
                        put("percentage", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Target volume percentage (0-100) for 'set' (e.g., 100 for full/max, 50 for half).")
                        })
                        put("step_percent", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional increment/decrement step (5-50, default 15, or 8 for 'thoda') for 'increase' or 'decrease'.")
                        })
                        put("steps", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional discrete hardware volume steps (1-20) for 'increase' or 'decrease'.")
                        })
                        put("stream", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional stream: 'music' (default media), 'ring', 'alarm', 'notification', 'call', or 'all'.")
                        })
                        put("command", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional raw Hindi/Hinglish/English volume phrase if parameters were ambiguous.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // 11. set_brightness (#13)
            put(JSONObject().apply {
                put("name", "set_brightness")
                put("description", "Controls and readback-verifies screen brightness: 'set', 'increase', 'decrease', 'auto_on', 'auto_off', or 'get'. Returns verified percentage and permission_required if Modify System Settings is missing.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("set"); put("increase"); put("decrease"); put("auto_on"); put("auto_off"); put("get")
                            })
                            put("description", "Action: 'set', 'increase', 'decrease', 'auto_on', 'auto_off', or 'get'.")
                        })
                        put("percentage", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Target brightness percentage (0-100) for 'set'.")
                        })
                        put("step_percent", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional step size (5-50, default 20) for increase/decrease.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // 12. quick_web_research (plus builtin_chrome_search legacy name)
            put(JSONObject().apply {
                put("name", "builtin_chrome_search")
                put("description", "Silent factual web Q&A lookup ONLY (weather, facts, scores). NEVER call this to open a website or interact with Aura's In-Built Browser (always use open_in_app_browser, browser_find, browser_click, browser_type instead).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Factual search query for quick background summary.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 13. set_flashlight
            put(JSONObject().apply {
                put("name", "set_flashlight")
                put("description", "Turns the phone's torch/flashlight on, off, or toggles it.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True for on, false for off (omit or set toggle=true to toggle).")
                        })
                        put("toggle", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to toggle the current flashlight state.")
                        })
                    })
                })
            })

            // 13b. get_device_status (#12)
            put(JSONObject().apply {
                put("name", "get_device_status")
                put("description", "Instantly reads real-time phone hardware status: battery percentage, charging status & temperature, all volume levels (media/ring/alarm/call), ringer mode, DND, screen brightness, RAM, storage, Wi-Fi/cellular network, and flashlight state. ALWAYS call this immediately whenever asked for battery percentage or phone status.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("sections", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Comma-separated sections (fast default: 'battery,audio,system'; full: 'battery,audio,display,network,memory,storage,system').")
                        })
                    })
                })
            })

            // 13c. set_ringer_mode
            put(JSONObject().apply {
                put("name", "set_ringer_mode")
                put("description", "Switches phone ringer mode between 'normal', 'vibrate', and 'silent'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("mode", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("normal"); put("vibrate"); put("silent") })
                        })
                    })
                    put("required", JSONArray().put("mode"))
                })
            })

            // 13d. set_device_setting (#13)
            put(JSONObject().apply {
                put("name", "set_device_setting")
                put("description", "Directly changes or auto-toggles phone settings and verifies the final state: 'wifi', 'bluetooth', 'mobile_data', 'location' (GPS), 'dnd', 'airplane', 'hotspot', 'battery_saver', 'dark_mode', 'auto_rotate', 'screen_timeout', 'auto_brightness', 'flashlight', 'nfc', 'volume_panel', 'display', 'storage', 'settings'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("setting", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Target setting name: 'wifi', 'bluetooth', 'mobile_data', 'location', 'dnd', 'airplane', 'hotspot', 'battery_saver', 'dark_mode', 'auto_rotate', 'screen_timeout', 'auto_brightness', 'flashlight', 'nfc', 'volume_panel', etc.")
                        })
                        put("enabled", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to turn ON, false to turn OFF (omit to toggle).")
                        })
                        put("value", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional setting value such as screen timeout duration ('30s', '1 min', '2 min', '5 min').")
                        })
                    })
                    put("required", JSONArray().put("setting"))
                })
            })

            // 13e. system_action
            put(JSONObject().apply {
                put("name", "system_action")
                put("description", "Performs global Android system actions: open notification shade ('notifications'), control center ('quick_settings'), 'lock_screen', 'take_screenshot', 'split_screen', 'power_dialog', 'dismiss_notifications', 'recents', 'home', 'back'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("notifications"); put("quick_settings"); put("lock_screen"); put("take_screenshot")
                                put("split_screen"); put("power_dialog"); put("dismiss_notifications"); put("recents"); put("home"); put("back")
                            })
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // 13f. read_notifications
            put(JSONObject().apply {
                put("name", "read_notifications")
                put("description", "Reads recent Android notifications, pending WhatsApp messages awaiting Smart Confirm reply, and automatically extracts incoming OTP verification codes.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("limit", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Maximum notifications (default 10).")
                        })
                    })
                })
            })

            // 14. click_ui_element
            put(JSONObject().apply {
                put("name", "click_ui_element")
                put("description", "Clicks or taps a specific UI button, card, video, link, icon, or text on the current screen by label, ordinal ('dusri video', 'second item', 'pehli'), element_id from inspect_screen, or coordinates.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("target_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Text, title, label, ordinal ('dusri video', 'second post', '1st item'), or element ID like '[#2]' to click.")
                        })
                        put("element_id", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional integer ID of the element from inspect_screen (e.g. 1, 2, 3).")
                        })
                        put("is_toggle", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Whether the target is a toggle switch or checkbox.")
                        })
                        put("x", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional normalized X coordinate (0-1000).")
                        })
                        put("y", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional normalized Y coordinate (0-1000).")
                        })
                    })
                    put("required", JSONArray().put("target_name"))
                })
            })

            // 15. type_text
            put(JSONObject().apply {
                put("name", "type_text")
                put("description", "Instantly injects text into any input field, search box, or message entry bar using universal direct text injection (ACTION_SET_TEXT and clipboard-preserving paste). Works across all native apps, WebViews, Telegram, WhatsApp, Chrome, and fake search bars. Zero keyboard typing, zero screen share needed.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The text to type.")
                        })
                        put("target_input_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional label or placeholder of the target input field (e.g. 'Search', 'Message').")
                        })
                        put("submit", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Whether to submit, send message, or press enter after typing.")
                        })
                        put("clear_existing", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Whether to clear existing text before typing (default true).")
                        })
                    })
                    put("required", JSONArray().put("text"))
                })
            })

            // 16. scroll_screen
            put(JSONObject().apply {
                put("name", "scroll_screen")
                put("description", "Scrolls the active phone app screen ('down', 'up', 'left', 'right') using reliable touch-fling + Accessibility container fallback. Automatically swipes next/previous if YouTube Shorts or Instagram Reels is on screen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("direction", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Direction to scroll: 'down', 'up', 'left', or 'right'.")
                        })
                        put("distance", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Scroll distance: 'small' (~40% screen), 'medium' (~65% screen, DEFAULT), or 'large' (~85% screen). Defaults to 'medium'.")
                        })
                    })
                    put("required", JSONArray().put("direction"))
                })
            })

            // 17. press_key
            put(JSONObject().apply {
                put("name", "press_key")
                put("description", "Executes system-level navigation gestures: 'back', 'home', or 'recents'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The system action: 'back', 'home', or 'recents'.")
                        })
                    })
                    put("required", JSONArray().put("key"))
                })
            })

            // 18. inspect_screen
            put(JSONObject().apply {
                put("name", "inspect_screen")
                put("description", "Reads the foreground app, screen title, visible chat messages, text content, and accessible UI controls via Accessibility. Zero screen share needed. ALWAYS call this when asked 'what is on screen', 'kya message aaya', or before clicking/typing.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 18b. read_screen
            put(JSONObject().apply {
                put("name", "read_screen")
                put("description", "Alias for inspect_screen. Reads all visible text, messages, and UI controls on the user's active screen via Accessibility without screen share.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 19. request_screen_share
            put(JSONObject().apply {
                put("name", "request_screen_share")
                put("description", "Prompts the user with the screen share dialog so Aura can see their screen live in real-time (only for external apps or when visual design inspection is needed — NEVER needed for Aura's own chat or projects).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 20. open_aura_app
            put(JSONObject().apply {
                put("name", "open_aura_app")
                put("description", "Brings Aura AI back to the foreground from other apps.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 21. write_project_file
            put(JSONObject().apply {
                put("name", "write_project_file")
                put("description", "Writes or creates a source code or web file (HTML, CSS, JS, Python, etc.) in the project workspace.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Relative or absolute file path (e.g., 'calculator/index.html').")
                        })
                        put("content", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The full contents of the file.")
                        })
                    })
                    put("required", JSONArray().apply { put("path"); put("content") })
                })
            })

            // 22. read_project_file
            put(JSONObject().apply {
                put("name", "read_project_file")
                put("description", "Reads a source code or project file from the project directory.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The path of the file to read.")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })

            // 23. execute_terminal_command
            put(JSONObject().apply {
                put("name", "execute_terminal_command")
                put("description", "Executes a shell command in the local environment/workspace (e.g. 'git status', 'ls -la', 'npm run build'). NEVER run 'python3 index.html' or execute an HTML file with python; to serve or preview any web project use start_local_preview_server (port 3000 default).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The exact command to run.")
                        })
                        put("working_directory", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional working directory.")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })

            // 24. task_completed
            put(JSONObject().apply {
                put("name", "task_completed")
                put("description", "Call this tool after the user's requested task is confirmed complete.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("summary", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Brief summary of what was completed.")
                        })
                    })
                    put("required", JSONArray().put("summary"))
                })
            })

            // 25. close_app
            put(JSONObject().apply {
                put("name", "close_app")
                put("description", "Closes the current or named app, stops playing background audio/media, and dismisses its task card from Android Recents overview or clears all recent apps with hardware gesture and post-action verification. Returns structured status ('success', 'partial', 'error') and verified message. Never claim success if status is error or partial.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional name of the app to close or dismiss from recents, e.g. 'Chrome', 'YouTube'.")
                        })
                        put("remove_from_recents", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to swipe away the card from recent apps overview.")
                        })
                        put("clear_all_recents", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to dismiss or clear all cards from recents.")
                        })
                        put("mode", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Execution mode: 'clear_all' (cross button with swipe fallback), 'swipe_clear_all' (swipes all unlocked cards upward one-by-one without cross button), or 'close_specific_app' (swipes and removes only the target app).")
                        })
                    })
                })
            })

            // 26. open_in_app_browser (Primary)
            put(JSONObject().apply {
                put("name", "open_in_app_browser")
                put("description", "Opens ANY website, URL, shortcut (e.g. 'google flow' opens labs.google/fx/tools/flow directly, 'youtube', 'amazon', 'flipkart'), or Google search inside Aura's own In-Built Browser and waits for the page to load cleanly so you can control it with browser_read_page/browser_find/browser_click/browser_type.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Website URL, shorthand ('google flow', 'amazon', 'youtube', 'github', 'vercel'), localhost:3000, or search query.")
                        })
                        put("new_tab", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Open in a new tab (default false so existing tab is reused unless new tab is requested).")
                        })
                        put("desktop_mode", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Optional desktop view toggle.")
                        })
                    })
                    put("required", JSONArray().put("url"))
                })
            })

            // 26b-26j: Complete In-Built Browser DOM Control Suite (powered by BrowserMcp)
            put(JSONObject().apply {
                put("name", "browser_read_page")
                put("description", "Reads the current page inside Aura's In-Built Browser: returns URL, title, interactive element refs (like ref_12), full readable text ('text'), or live viewport image & section verification ('images' — checks whether product images, reviews, sponsored ads, or sign-in are actually visible in viewport).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("mode", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("interactive"); put("all"); put("text"); put("images") })
                            put("description", "'interactive' (default: clickable links/buttons/inputs with refs), 'images' (verify visible non-sponsored images & current viewport section), 'all' (full structure), or 'text' (article/plain text).")
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id; defaults to active tab.")
                        })
                    })
                })
            })

            put(JSONObject().apply {
                put("name", "browser_find")
                put("description", "Finds elements on the current In-Built Browser page by plain description (e.g. 'search box', 'login button', 'first video', 'add to cart'), returning element refs (ref_X) with domain host and sponsored tags.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Plain description of what to look for.")
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            put(JSONObject().apply {
                put("name", "browser_click")
                put("description", "Clicks ANY button, organic link, menu item, checkbox, or element inside Aura's In-Built Browser while skipping Sponsored ads and search-bar inputs automatically unless explicitly requested. Pass either 'ref' OR 'target_name' (e.g. 'Google Flow official link', 'Sign in button', 'Go button').")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("ref", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Element ref like 'ref_12'.")
                        })
                        put("target_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Plain description/text of the link or button to click when ref is unknown.")
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id.")
                        })
                    })
                })
            })

            put(JSONObject().apply {
                put("name", "browser_type")
                put("description", "Atomically clears existing text, types cleanly into ANY input field (search box, email/login input, form) inside Aura's In-Built Browser, verifies the DOM input value matches (no duplicate/partial chars), and when submit=true submits the form / clicks Go & verifies URL/page navigation! Pass submit=false when only filling an email/form field without submitting.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Exact text to type or search for.")
                        })
                        put("ref", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional element ref (e.g. 'ref_5').")
                        })
                        put("target_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Description of input field (e.g. 'search box', 'Email or phone input').")
                        })
                        put("submit", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Press Enter / submit form / click Go after typing (default true for search; pass false for email fields until user says Next/submit).")
                        })
                        put("clear_first", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Clear existing text in the input field before typing (default true to prevent partial character glitches).")
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id.")
                        })
                    })
                    put("required", JSONArray().put("text"))
                })
            })

            put(JSONObject().apply {
                put("name", "browser_scroll")
                put("description", "Scrolls the webpage inside Aura's In-Built Browser ('down', 'up', 'left', 'right') OR jumps directly to main non-sponsored images ('images'), and returns live verified viewport section + visible images report so you never misread reviews/ads as product photos.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("direction", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("down"); put("up"); put("left"); put("right"); put("images") })
                            put("description", "Scroll direction, or 'images' to jump to main non-sponsored product/article images.")
                        })
                        put("amount", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Scroll ticks 1-10 (default 5).")
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id.")
                        })
                    })
                    put("required", JSONArray().put("direction"))
                })
            })

            put(JSONObject().apply {
                put("name", "browser_navigate")
                put("description", "Navigates inside Aura's In-Built Browser tab history: 'back', 'forward', 'reload', or 'stop'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("back"); put("forward"); put("reload"); put("stop") })
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional tab_id.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            put(JSONObject().apply {
                put("name", "browser_tabs")
                put("description", "Manages tabs and view mode in Aura's In-Built Browser: 'list', 'new', 'switch', 'close', 'desktop', 'mobile'. For 'switch' or 'close', pass 'tab_name' (e.g. 'amazon', 'google', 'first', 'previous', '2') or 'tab_id' so the exact right tab is chosen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("list"); put("new"); put("switch"); put("close"); put("desktop"); put("mobile") })
                        })
                        put("tab_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Target tab_id for switch/close/desktop/mobile.")
                        })
                        put("tab_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Tab title, website/domain keyword, or position ('amazon', 'first', 'previous', 'last', '2') for switch/close.")
                        })
                        put("url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional URL when action='new'.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            put(JSONObject().apply {
                put("name", "browser_media_control")
                put("description", "Controls HTML5 video/audio or skips ads directly inside Aura's In-Built Browser: 'play', 'pause', 'toggle', 'mute', 'unmute', 'forward', 'rewind', 'skip_ad', 'next', 'fullscreen'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply {
                                put("play"); put("pause"); put("toggle"); put("mute"); put("unmute")
                                put("forward"); put("rewind"); put("skip_ad"); put("next"); put("fullscreen")
                            })
                        })
                        put("seek_seconds", JSONObject().apply {
                            put("type", "NUMBER")
                            put("description", "Optional seconds for forward/rewind (default 10).")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })

            // Canonical open_in_app_browser is declared above (#26); aliases open_inbuilt_browser/open_url route identically without schema bloat.

            // 27. close_in_app_browser
            put(JSONObject().apply {
                put("name", "close_in_app_browser")
                put("description", "Closes the ENTIRE In-Built Browser / website preview and returns to Chat. ONLY call when user explicitly commands to close/exit the whole browser ('browser band karo', 'browser close karo', 'preview band karo'). NEVER call on words like 'one', 'open', 'aur dikhao', and NEVER call when user asks to close just ONE tab (use browser_tabs action='close' instead).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 28. dial_number
            put(JSONObject().apply {
                put("name", "dial_number")
                put("description", "Opens the phone's native dialpad with the given numeric phone number pre-filled, or places the call. ALWAYS use this tool whenever the user provides a numeric phone number to dial or call (e.g. '9876543210 dial karo', 'call 1234567890', 'number dial karo'). NEVER type phone numbers into search bars.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("phone_number", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The exact numeric phone number to dial (e.g. '9876543210', '+919876543210').")
                        })
                        put("auto_call", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to directly place call, false to pre-fill dialpad for user to review and call (default false).")
                        })
                    })
                    put("required", JSONArray().apply { put("phone_number") })
                })
            })

            // 29. swipe_reel
            put(JSONObject().apply {
                put("name", "swipe_reel")
                put("description", "Performs a fast, decisive vertical flick to swipe to the next or previous video in Instagram Reels, YouTube Shorts, or TikTok feeds. Call whenever the user says 'Reel swipe karo', 'next reel', 'agla reel', 'swipe reel', or 'reel change karo'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("direction", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Direction to swipe: 'up' (default, moves to next reel) or 'down' (moves to previous reel).")
                        })
                    })
                })
            })

            // 30. start_continuous_scroll
            put(JSONObject().apply {
                put("name", "start_continuous_scroll")
                put("description", "Starts automatic continuous scrolling in the background (e.g. 'scroll karte raho', 'auto scroll chalu karo', 'keep scrolling'). Automatically stops when app switches, end of screen is reached, or stop_continuous_scroll is called.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("direction", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Direction to scroll continuously: 'down' (default) or 'up'.")
                        })
                        put("speed", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Scroll speed: 'slow', 'normal' (default), or 'fast'.")
                        })
                    })
                })
            })

            // 31. stop_continuous_scroll
            put(JSONObject().apply {
                put("name", "stop_continuous_scroll")
                put("description", "Stops background automatic continuous scrolling immediately. Call when user says 'scroll rok do', 'stop scrolling', 'ruko', 'bas', or 'stop'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 32. close_current_app
            put(JSONObject().apply {
                put("name", "close_current_app")
                put("description", "Closes the current active foreground app immediately, flings its task card away from Android Recents, and returns to the home screen. Use when user says 'app close karo', 'app band karo', or 'ye app hata do'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })

            // 33. execute_app_workflow
            put(JSONObject().apply {
                put("name", "execute_app_workflow")
                put("description", "Executes a multi-step phone automation workflow end-to-end in sequence with UI verification between each step and automatic status updates (e.g., 'WhatsApp kholo, Rahul ka contact dhundho, usko message karo Hi, uske baad WhatsApp close kar do, Instagram open karke Reels laga do'). Provide the ordered 'steps' array.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("workflow_goal", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "High-level summary of the user's multi-step command.")
                        })
                        put("steps", JSONObject().apply {
                            put("type", "ARRAY")
                            put("description", "Ordered list of automation steps to execute sequentially. Each item is an object with 'action' ('open_in_app_browser', 'browser_read_page', 'browser_find', 'browser_click', 'browser_type', 'browser_scroll', 'browser_navigate', 'browser_tabs', 'browser_media_control', 'close_in_app_browser', 'answer_chat_question', 'navigate_app_screen', 'open_chat_by_name', 'open_app', 'send_whatsapp_message', 'reply_whatsapp_notification', 'configure_whatsapp_auto_reply', 'click_ui_element', 'answer_quiz_question', 'generate_image', 'type_text', 'scroll_screen', 'swipe_reel', 'search_and_play_youtube', 'youtube_action', 'set_volume', 'set_brightness', 'set_flashlight', 'set_ringer_mode', 'set_device_setting', 'system_action', 'media_playback_control', 'get_device_status', 'close_app', 'close_current_app', 'press_key', 'wait', 'inspect_screen') and parameters ('url', 'query', 'mode', 'tab_name', 'answer', 'screen_name', 'chat_name', 'app_name', 'target_name', 'text', 'recipient_name', 'sender_name', 'message', 'direction', 'submit', 'key', 'wait_ms', 'option', 'click_next', 'prompt', 'aspect_ratio', 'percentage', 'stream', 'step_percent', 'sections', 'setting', 'enabled', 'value').")
                            put("items", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Step action type: 'open_in_app_browser', 'browser_read_page', 'browser_find', 'browser_click', 'browser_type', 'browser_scroll', 'browser_navigate', 'browser_tabs', 'browser_media_control', 'close_in_app_browser', 'answer_chat_question', 'navigate_app_screen', 'open_chat_by_name', 'open_app', 'send_whatsapp_message', 'reply_whatsapp_notification', 'configure_whatsapp_auto_reply', 'click_ui_element', 'answer_quiz_question', 'generate_image', 'type_text', 'scroll_screen', 'swipe_reel', 'search_and_play_youtube', 'youtube_action', 'set_volume', 'set_brightness', 'set_flashlight', 'set_ringer_mode', 'set_device_setting', 'system_action', 'media_playback_control', 'get_device_status', 'close_app', 'close_current_app', 'press_key', 'wait', 'inspect_screen'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target volume/brightness percentage (0-100).")
                                    })
                                    put("setting", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target setting name for 'set_device_setting' ('wifi', 'bluetooth', 'location', 'dnd', 'airplane', 'hotspot', 'battery_saver', 'dark_mode', 'auto_rotate', 'screen_timeout', etc.).")
                                    })
                                    put("enabled", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True/false toggle for 'set_device_setting' or 'set_flashlight'.")
                                    })
                                    put("value", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional setting value such as screen timeout ('30s', '2 min').")
                                    })
                                    put("stream", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Volume stream ('music', 'ring', 'alarm', 'notification', 'call', 'all') for 'set_volume'.")
                                    })
                                    put("step_percent", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Volume step percent (5-50) for increase/decrease.")
                                    })
                                    put("sections", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Sections for 'get_device_status' step (default 'battery,audio,system').")
                                    })
                                    put("url", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Website URL or shortcut for open_in_app_browser.")
                                    })
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Query for browser_find or browser_type.")
                                    })
                                    put("mode", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Mode for browser_read_page ('interactive', 'text', 'images') or browser_tabs.")
                                    })
                                    put("tab_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Tab name or index for browser_tabs switch/close.")
                                    })
                                    put("answer", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Option label/choice for answer_chat_question.")
                                    })
                                    put("screen_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Internal Aura screen/drawer/plugin/settings name for navigate_app_screen.")
                                    })
                                    put("chat_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target chat/pinned chat name for open_chat_by_name.")
                                    })
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target app name for open_app or close_app.")
                                    })
                                    put("target_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target UI element text/label for click_ui_element, target_input_name for type_text, or sub-action ('skip_ad', 'like', 'subscribe', 'fullscreen', 'play_first') for youtube_action.")
                                    })
                                    put("player_action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "YouTube sub-action for youtube_action step: 'skip_ad', 'play_first', 'like', 'subscribe', 'fullscreen', 'play', 'pause', 'next'.")
                                    })
                                    put("text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Text to directly inject for type_text.")
                                    })
                                    put("submit", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to press Enter/Search/Send after type_text.")
                                    })
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Contact or chat name for send_whatsapp_message.")
                                    })
                                    put("sender_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Sender name for reply_whatsapp_notification.")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Message content for send_whatsapp_message or reply_whatsapp_notification.")
                                    })
                                    put("direction", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Direction for scroll_screen or swipe_reel ('up', 'down').")
                                    })
                                    put("key", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "System key for press_key ('back', 'home', 'recents').")
                                    })
                                    put("wait_ms", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Milliseconds to wait for 'wait' action.")
                                    })
                                    put("seconds", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Seek seconds for forward/rewind (default 10).")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })
                    })
                    put("required", JSONArray().apply { put("steps") })
                })
            })

            // 34. start_local_preview_server (#13)
            put(JSONObject().apply {
                put("name", "start_local_preview_server")
                put("description", "Starts a verified local HTTP preview server for a web project on port 3000 (auto-detecting Vite/Next/Node vs static HTML via python3 -m http.server), waits for HTTP 200 readiness, and opens http://localhost:<port> inside Aura's In-Built Browser. ALWAYS use this instead of running 'python3 index.html' or manually guessing ports.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("project_dir", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Absolute or workspace-relative path to the project directory (defaults to active project path).")
                        })
                        put("port", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Preferred port number (defaults to 3000; auto-falls back to next free port if busy).")
                        })
                        put("open_in_browser", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Whether to automatically open the verified localhost URL in Aura's In-Built Browser (default true).")
                        })
                    })
                })
            })

            // 35. close_specific_app (#20)
            put(JSONObject().apply {
                put("name", "close_specific_app")
                put("description", "Closes ONLY a single named app by opening Android Recents, finding its card, and swiping only that card away (leaving other apps untouched). Use when user says 'WhatsApp band karo', 'YouTube close karo', 'Instagram hata do'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Name of the single app to close from Recents (e.g. 'WhatsApp', 'Instagram', 'YouTube').")
                        })
                    })
                    put("required", JSONArray().put("app_name"))
                })
            })

            // 36. clear_all_recent_apps (#20)
            put(JSONObject().apply {
                put("name", "clear_all_recent_apps")
                put("description", "Clears ALL open recent apps from Android Recents ONLY when the user explicitly asks to close all apps (e.g. 'saare apps close karo', 'clear all recent apps', 'sab apps hata do'). Never call this when the user only wants to close one app.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("use_swipe_only", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "True to swipe cards away one by one instead of tapping Clear All / Close All.")
                        })
                    })
                })
            })

            // 37. answer_quiz_question
            put(JSONObject().apply {
                put("name", "answer_quiz_question")
                put("description", "Fast, verified MCQ & quiz answer tool for Chrome and web exams: finds the requested option ('a', 'b', 'c', 'd', 'option 2', or Hindi/English text — scrolling if below fold), taps it cleanly, verifies the radio/checkbox tick is actually CHECKED, and optionally clicks 'Next' ONLY if tick verification passed so questions are never skipped.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("option", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "MCQ option to tick: 'a', 'b', 'c', 'd', 'option 2', or the option's Hindi/English text.")
                        })
                        put("click_next", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "Set true to automatically tap Next and load the next question after verifying the option tick; set false to only tick and verify the option.")
                        })
                        put("goal", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional user instruction context.")
                        })
                    })
                    put("required", JSONArray().put("option"))
                })
            })

            // 38. generate_image
            put(JSONObject().apply {
                put("name", "generate_image")
                put("description", "Generates a real AI image/artwork/photo immediately from a prompt and saves/displays it inside Aura's Chat & Images Gallery. ALWAYS call this when the user asks to generate, create, draw, or make any picture or image (e.g., 'ek cat ki image banao', 'draw a sunset', 'thumbnail banao'). NEVER refuse or claim you cannot generate images.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Detailed visual description of the image to generate (in clear English for best quality while preserving every detail the user asked for).")
                        })
                        put("aspect_ratio", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional aspect ratio: '1:1' (default), '16:9', '9:16', '4:3', '3:4'.")
                        })
                        put("show_in_chat", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "If true (default when inside Aura), immediately open/show the generated image in chat.")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                })
            })

            // 39. navigate_app_screen
            put(JSONObject().apply {
                put("name", "navigate_app_screen")
                put("description", "Natively opens or closes ANY internal screen, side drawer, drawer tab, plugins page, modal, or settings section inside Aura AI without Android Accessibility clicks. ALWAYS call this when user asks to open Side Drawer, Pinned/Archive/Project drawer tab, Custom AntiGem Plugins, Skills & Plugins, MCP, Settings (or any subsection), Customize, Images Gallery, Projects picker, Themes, Accounts, Model selector, IDE, Terminal, Notes, Files, or to close Drawer/Settings overlays.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("screen_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Internal screen/tab target name, e.g. 'Custom AntiGem Plugins', 'Skills & Plugins', 'MCP', 'Drawer', 'Drawer Pin Tab', 'Drawer Archive Tab', 'Drawer Project Tab', 'Close Drawer', 'Settings', 'Aura Voice Settings', 'Customize', 'Images Gallery', 'Projects', 'Themes', 'Accounts', 'Model Selector', 'IDE', 'Terminal', 'Notes', 'Files', 'Browser', 'Chat', 'Close Overlays'.")
                        })
                    })
                    put("required", JSONArray().put("screen_name"))
                })
            })

            // 40. answer_chat_question (#9)
            put(JSONObject().apply {
                put("name", "answer_chat_question")
                put("description", "Answers, selects options on, submits, skips, or cancels an active interactive Questionnaire / Choice Card ('Antigravity has a question', 'Standard mode', 'Agent Mode', 'Recommended', 'Submit') inside Aura's chat UI! ALWAYS call this instead of click_ui_element or start_new_chat_task whenever the user selects an option or asks to submit/skip/cancel a chat question.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("answer", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Chosen option label, number, or 'recommended' (e.g. 'Standard mode', '1', 'recommended', 'Agent mode').")
                        })
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("submit"); put("skip"); put("cancel"); put("inspect") })
                            put("description", "Action to perform on the Questionnaire card: 'submit' (default: select option + submit), 'skip', 'cancel', or 'inspect' (read options without submitting).")
                        })
                    })
                    put("required", JSONArray().put("answer"))
                })
            })

            // 41. set_alarm
            put(JSONObject().apply {
                put("name", "set_alarm")
                put("description", "Sets an alarm or countdown timer on the user's phone, or opens existing alarms. Call immediately when user says e.g. 'subah 6 baje ka alarm laga do', '6:30 AM alarm set karo', '10 minute ka timer lagao'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("set_alarm"); put("set_timer"); put("show_alarms") })
                            put("description", "'set_alarm' (default), 'set_timer', or 'show_alarms'.")
                        })
                        put("hour", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "24-hour clock hour (0-23) for set_alarm (e.g. 6 for 6 AM, 18 for 6 PM).")
                        })
                        put("minute", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Minute (0-59) for set_alarm (default 0).")
                        })
                        put("time_text", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional spoken time string e.g. '6:30 AM' or 'subah 7 baje'.")
                        })
                        put("duration_seconds", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Countdown duration in seconds for set_timer (e.g. 300 for 5 min).")
                        })
                        put("label", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional alarm or timer label (e.g. 'Gym', 'Medicine').")
                        })
                    })
                })
            })

            // 42. save_memory
            put(JSONObject().apply {
                put("name", "save_memory")
                put("description", "Saves an important personal fact, preference, date, routine, relationship, reminder, or standing rule into Aura's persistent memory across chats and restarts. Call whenever the user explicitly asks to remember something OR mentions any durable important detail worth remembering.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("fact", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Concise statement of what to remember (e.g. 'User wakes up at 6 AM for gym', 'User likes black theme').")
                        })
                        put("category", JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray().apply { put("PERSONAL"); put("PREFERENCE"); put("ROUTINE"); put("CONTACT"); put("PROJECT"); put("NOTE") })
                            put("description", "Memory category.")
                        })
                        put("topic_key", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional short snake_case key to update/merge existing facts on the same topic (e.g. 'user_name', 'wake_time', 'favorite_color').")
                        })
                    })
                    put("required", JSONArray().put("fact"))
                })
            })

            // 43. forget_memory
            put(JSONObject().apply {
                put("name", "forget_memory")
                put("description", "Deletes previously stored memories matching the given keyword or topic when the user asks to forget or remove something.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Keyword or topic to remove from persistent memory.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 44. recall_memory
            put(JSONObject().apply {
                put("name", "recall_memory")
                put("description", "Searches Aura's persistent memory for facts/preferences matching a query. Call when the user asks what you remember about a topic.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Topic or keyword to search memory for. Blank returns most recent.")
                        })
                        put("limit", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Max memories to return (default 10).")
                        })
                    })
                })
            })

            // 45. list_memories
            put(JSONObject().apply {
                put("name", "list_memories")
                put("description", "Lists all facts currently stored in Aura's persistent memory. Call when the user asks what you remember.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("limit", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Max memories to return (default 50).")
                        })
                    })
                })
            })
        }
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "") ?: json.optString("error", "Unknown error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                Log.e(TAG, "Server error: code=$errCode, message=$errMsg")

                val isApiKey = errMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                               errMsg.contains("API key not valid", ignoreCase = true) ||
                               errCode == 400 || errCode == 403
                val isQuota = errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                              errMsg.contains("quota", ignoreCase = true) ||
                              errCode == 429
                if (isApiKey) {
                    listener.onError("Invalid API Key: $errMsg", isFatal = true, errorCode = "API_KEY_INVALID")
                    listener.onConnectionStateChanged("ERROR • INVALID KEY")
                } else if (isQuota) {
                    listener.onError("API Quota Exceeded: $errMsg", isFatal = true, errorCode = "QUOTA_EXCEEDED")
                    listener.onConnectionStateChanged("ERROR • QUOTA EXCEEDED")
                } else {
                    listener.onError("Gemini Error ($errCode): $errMsg", isFatal = false)
                }
                return
            }

            if (json.has("setupComplete")) {
                Log.i(TAG, "Setup completed handshake received. Session LIVE!")
                if (!isSetupComplete.getAndSet(true)) {
                    listener.onConnectionStateChanged("LIVE")
                    startSessionRenewal()
                }
                return
            }

            if (!isSetupComplete.get() && (json.has("serverContent") || json.has("toolCall"))) {
                isSetupComplete.set(true)
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
            }

            val topToolCall = json.optJSONObject("toolCall")
            if (topToolCall != null) {
                val functionCalls = topToolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    val batch = mutableListOf<ToolCallItem>()
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val name = call.optString("name", "")
                        val id = call.optString("id", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty()) {
                            Log.i(TAG, "Tool call [${i + 1}/${functionCalls.length()}]: $name (id=$id)")
                            batch.add(ToolCallItem(id = id, name = name, args = args))
                        }
                    }
                    if (batch.isNotEmpty()) {
                        listener.onToolCallsBatch(batch)
                    }
                }
                return
            }

            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                val scToolCall = serverContent.optJSONObject("toolCall")
                if (scToolCall != null) {
                    val functionCalls = scToolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        val batch = mutableListOf<ToolCallItem>()
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val name = call.optString("name", "")
                            val id = call.optString("id", "")
                            val args = call.optJSONObject("args") ?: JSONObject()
                            if (name.isNotEmpty()) {
                                Log.i(TAG, "Tool call (serverContent) [${i + 1}/${functionCalls.length()}]: $name (id=$id)")
                                batch.add(ToolCallItem(id = id, name = name, args = args))
                            }
                        }
                        if (batch.isNotEmpty()) {
                            listener.onToolCallsBatch(batch)
                        }
                    }
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    var userText = inputTrans.optString("text", "").trim()
                    if (userText.isEmpty()) {
                        val parts = inputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                sb.append(parts.optJSONObject(p)?.optString("text", "") ?: "")
                            }
                            userText = sb.toString().trim()
                        }
                    }
                    if (userText.isNotBlank()) {
                        listener.onUserTextReceived(userText)
                    }
                }

                var hasOutputTranscriptionText = false
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    var outText = outputTrans.optString("text", "")
                    if (outText.isEmpty()) {
                        val parts = outputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                sb.append(parts.optJSONObject(p)?.optString("text", "") ?: "")
                            }
                            outText = sb.toString()
                        }
                    }
                    if (outText.isNotEmpty()) {
                        hasOutputTranscriptionText = true
                        listener.onAssistantTextReceived(outText)
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) continue

                            val text = part.optString("text", "")
                            val isThinkingLeak = text.startsWith("**") && (
                                text.contains("thinking", ignoreCase = true) ||
                                text.contains("crafting", ignoreCase = true) ||
                                text.contains("formulating", ignoreCase = true)
                            )
                            if (text.isNotEmpty() && !isThinkingLeak && !hasOutputTranscriptionText) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.length > 8) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (pcmBytes.size >= 16) {
                                        val validLen = pcmBytes.size and 1.inv()
                                        val aligned = if (validLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLen)
                                        listener.onAudioDataReceived(aligned)
                                    }
                                }
                            }
                        }
                    }
                }

                val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                 serverContent.optBoolean("generationComplete", false) ||
                                 serverContent.optBoolean("endOfTurn", false)
                if (isTurnDone) {
                    Log.d(TAG, "Server signaled turnComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val responses = JSONArray().put(JSONObject().apply {
                put("id", callId)
                put("name", functionName)
                put("response", result)
            })
            sendToolResponses(responses)
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    fun sendToolResponses(functionResponses: JSONArray) {
        try {
            val msg = JSONObject().apply {
                put("tool_response", JSONObject().apply {
                    put("function_responses", functionResponses)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent batched tool_response (${functionResponses.length()} items)")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponses failed: ${e.message}", e)
        }
    }

    /**
     * Pushes a proactive system status update into the active Gemini Live Bidi session
     * so the assistant announces live progress or completion to the user without waiting
     * for the user to ask "ab kya status hai?".
     */
    fun sendSystemStatusUpdate(statusMessage: String, turnComplete: Boolean = true) {
        if (!isConnected.get() || !isSetupComplete.get() || statusMessage.isBlank()) return
        try {
            val msg = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "[PROACTIVE_STATUS_UPDATE - Speak a brief 1-sentence Hindi/Hinglish status update to the user right now]: $statusMessage")
                        }))
                    }))
                    put("turn_complete", turnComplete)
                })
            }
            webSocket?.send(msg.toString())
            Log.i(TAG, "Sent proactive status update to Gemini Live: $statusMessage")
        } catch (e: Exception) {
            Log.e(TAG, "sendSystemStatusUpdate failed: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    private var mediaChunksSent = 0
    private val lastVideoFrameSentAt = AtomicLong(0L)

    fun sendMediaChunk(jpegBytes: ByteArray, forceImmediate: Boolean = false) {
        if (!isConnected.get() || !isSetupComplete.get() || jpegBytes.isEmpty()) return

        val now = android.os.SystemClock.elapsedRealtime()
        val minGap = if (forceImmediate) 120L else 1000L
        while (true) {
            val previous = lastVideoFrameSentAt.get()
            if (now - previous < minGap) return
            if (lastVideoFrameSentAt.compareAndSet(previous, now)) break
        }
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$base64Image\"}}}"
            webSocket?.send(json)
            mediaChunksSent++
            if (mediaChunksSent % 10 == 1) {
                Log.i(TAG, "Sent video frame #$mediaChunksSent to Gemini Live (${jpegBytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media chunk: ${e.message}")
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(500)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
