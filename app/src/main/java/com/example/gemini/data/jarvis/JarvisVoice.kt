package com.example.gemini.data.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

/**
 * Jarvis' voice: queues what is to be said and plays it with the device's text-to-speech or an online voice
 * (Google Cloud Text-to-Speech, or any OpenAI-compatible speech API). A failing online voice falls back to the device.
 */
object JarvisVoice {
    private const val TAG = "JarvisVoice"
    /** Longest piece handed to a voice engine at once (the device engine refuses more than about 4000). */
    private const val CHUNK = 2500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context
    private var inited = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** 0..1, a lively pulse while speaking (the speech engines do not expose the real signal). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _lastSpoken = MutableStateFlow("")
    val lastSpoken: StateFlow<String> = _lastSpoken.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private class Utterance(val text: String, val done: CompletableDeferred<Unit>)

    private val queue = Channel<Utterance>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private var pulse: Job? = null
    @Volatile private var current: Job? = null

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---- Android TTS
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    private val ttsWaiters = HashMap<String, CompletableDeferred<Unit>>()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        if (inited) return
        inited = true
        app = context.applicationContext
        consumer = scope.launch {
            for (u in queue) {
                try {
                    _speaking.value = true
                    startPulse()
                    val job = launch { playOne(u.text) }
                    current = job
                    job.join()
                } catch (e: Exception) {
                    Log.w(TAG, "speak failed: ${e.message}")
                } finally {
                    current = null
                    u.done.complete(Unit)
                    if (pending.decrementAndGet() <= 0) {
                        pending.set(0)
                        _speaking.value = false
                        stopPulse()
                        abandonFocus()
                    }
                }
            }
        }
    }

    /** Queues [text]; with [wait] the call returns when it has been said. */
    suspend fun speak(text: String, wait: Boolean = true) {
        val clean = clean(text)
        if (clean.isBlank()) return
        _lastSpoken.value = clean
        val done = CompletableDeferred<Unit>()
        pending.incrementAndGet()
        queue.trySend(Utterance(clean, done))
        if (wait) done.await()
    }

    /** Stops speaking at once and drops what is queued (the user spoke over Jarvis, or said stop). */
    fun stop() {
        while (true) {
            val u = queue.tryReceive().getOrNull() ?: break
            pending.decrementAndGet()
            u.done.complete(Unit)
        }
        current?.cancel()
        runCatching { tts?.stop() }
        runCatching { player?.stop() }
        _speaking.value = false
        stopPulse()
    }

    private fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code omitted ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("https?://\\S+"), " link ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun startPulse() {
        if (pulse?.isActive == true) return
        pulse = scope.launch {
            var t = 0.0
            while (isActive) {
                t += 0.35
                _level.value = (0.35 + 0.45 * abs(sin(t)) + 0.2 * abs(sin(t * 2.7))).toFloat().coerceIn(0f, 1f)
                delay(60)
            }
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        _level.value = 0f
    }

    // ------------------------------------------------------------------ playback

    private suspend fun playOne(text: String) {
        requestFocus()
        for (chunk in chunks(text)) {
            val engine = JarvisPreferences.ttsEngine.value
            val ok = when (engine) {
                "google_cloud", "openai" -> playOnline(engine, chunk)
                else -> false
            }
            if (!ok) speakAndroid(chunk)
        }
    }

    /**
     * Pieces of at most [CHUNK] characters, cut at sentence ends (a "sentence" longer than that is cut at spaces): the
     * device engine refuses anything longer than its limit (about 4000) without any callback.
     */
    private fun chunks(text: String): List<String> {
        if (text.length <= CHUNK) return listOf(text)
        val pieces = text.split(Regex("(?<=[.!?])\\s+")).flatMap { s ->
            if (s.length <= CHUNK) listOf(s) else s.split(' ').fold(mutableListOf(StringBuilder())) { acc, w ->
                if (acc.last().length + w.length + 1 > CHUNK && acc.last().isNotEmpty()) acc += StringBuilder()
                acc.last().append(w.take(CHUNK)).append(' ')
                acc
            }.map { it.toString() }
        }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in pieces) {
            if (sb.length + s.length > CHUNK && sb.isNotEmpty()) { out += sb.toString().trim(); sb.clear() }
            sb.append(s).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    // ---- device voice

    private suspend fun ensureTts(): TextToSpeech? {
        tts?.let { if (ttsReady?.isCompleted == true) return it }
        val ready = ttsReady ?: CompletableDeferred<Boolean>().also { d ->
            ttsReady = d
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(app) { status -> d.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        val ok = ready.await()
        val engine = tts
        if (!ok || engine == null) {
            _lastError.value = "The device's text-to-speech is not available."
            ttsReady = null
            tts = null
            return null
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishUtterance(utteranceId) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { finishUtterance(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { finishUtterance(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finishUtterance(utteranceId) }
        })
        return engine
    }

    private fun finishUtterance(id: String?) {
        val d = synchronized(ttsWaiters) { ttsWaiters.remove(id) }
        d?.complete(Unit)
    }

    private suspend fun speakAndroid(text: String) {
        val engine = ensureTts() ?: return
        withContext(Dispatchers.Main) {
            engine.setSpeechRate(JarvisPreferences.ttsRate.value.coerceIn(0.4f, 2.5f))
            engine.setPitch(JarvisPreferences.ttsPitch.value.coerceIn(0.4f, 2f))
            val voiceName = JarvisPreferences.ttsVoice.value
            val lang = JarvisPreferences.ttsLanguage.value
            val chosen = if (voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == voiceName } else null
            if (chosen != null) engine.voice = chosen
            else if (lang.isNotBlank()) engine.language = Locale.forLanguageTag(lang)
        }
        val id = "jarvis-" + System.nanoTime()
        val done = CompletableDeferred<Unit>()
        synchronized(ttsWaiters) { ttsWaiters[id] = done }
        try {
            val queued = withContext(Dispatchers.Main) { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }
            // refused (too long, engine gone): no callback ever comes, so do not wait for one
            if (queued != TextToSpeech.SUCCESS) {
                Log.w(TAG, "device voice refused ${text.length} chars ($queued)")
                // most likely the engine died: the next piece starts a fresh one
                withContext(Dispatchers.Main) { runCatching { engine.shutdown() } }
                tts = null; ttsReady = null
                return
            }
            // the "done" callback can also go missing (the engine restarted, another app took it): also watch whether
            // it is still talking, and give up after far longer than the text could take
            val started = System.currentTimeMillis()
            val cap = 20_000L + (text.length * 140L / JarvisPreferences.ttsRate.value.coerceIn(0.4f, 2.5f)).toLong()
            var silentFor = 0
            while (true) {
                if (withTimeoutOrNull(1000) { done.await() } != null) break
                val talking = runCatching { engine.isSpeaking }.getOrDefault(false)
                silentFor = if (talking) 0 else silentFor + 1
                val elapsed = System.currentTimeMillis() - started
                if (silentFor >= 3 && elapsed > 4000) { Log.w(TAG, "device voice ended without telling"); break }
                if (elapsed > cap) { Log.w(TAG, "device voice timed out"); runCatching { engine.stop() }; break }
            }
        } finally {
            synchronized(ttsWaiters) { ttsWaiters.remove(id) }
        }
    }

    /** Voices installed on the device, for the settings screen. */
    suspend fun deviceVoices(): List<Pair<String, String>> {
        val engine = ensureTts() ?: return emptyList()
        // off the main thread: asking the engine for its voices is a slow call into the TTS service (Google's engine
        // has hundreds), and on the main thread it froze whatever was on screen (the Jarvis settings page) meanwhile
        return withContext(Dispatchers.Default) {
            runCatching { engine.voices.orEmpty().map { it.name to "${it.locale.displayName} · ${it.name}" } }
                .getOrDefault(emptyList())
                .sortedBy { it.second }
        }
    }

    // ---- online voices

    private suspend fun playOnline(engine: String, text: String): Boolean {
        val key = JarvisPreferences.ttsApiKey.value.trim().ifBlank {
            JarvisPreferences.liveApiKey.value.trim()
        }

        return try {
            val bytes = withContext(Dispatchers.IO) {
                when {
                    engine == "edge" -> edgeSpeech(text)
                    engine == "gemini" -> {
                        if (key.isNotBlank()) geminiSpeech(text, key) ?: edgeSpeech(text)
                        else edgeSpeech(text)
                    }
                    engine == "google_cloud" -> {
                        // If user entered a Google AI Studio Gemini API key (AIzaSy...), use Gemini Speech!
                        if (key.startsWith("AIzaSy")) {
                            geminiSpeech(text, key) ?: edgeSpeech(text)
                        } else {
                            try {
                                googleCloud(text, key)
                            } catch (e: Exception) {
                                val msg = e.message.orEmpty()
                                if (msg.contains("401") || msg.contains("API keys are not supported")) {
                                    // Fallback seamlessly to Gemini Speech with the same key
                                    geminiSpeech(text, key) ?: edgeSpeech(text)
                                } else throw e
                            }
                        }
                    }
                    engine == "openai" -> openAiSpeech(text, key)
                    else -> {
                        if (key.isNotBlank()) geminiSpeech(text, key) ?: edgeSpeech(text)
                        else edgeSpeech(text)
                    }
                }
            }
            if (bytes == null || bytes.isEmpty()) return false
            _lastError.value = null
            playBytes(bytes)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "online voice failed: ${e.message}")
            _lastError.value = "Online voice failed (${e.message}); used the device voice."
            false
        }
    }

    private fun geminiSpeech(text: String, key: String): ByteArray? {
        val voiceName = JarvisPreferences.liveVoice.value.ifBlank {
            JarvisPreferences.ttsVoice.value.ifBlank { "Puck" }
        }
        val cleanVoice = when (voiceName.lowercase()) {
            "puck" -> "Puck"
            "aoede" -> "Aoede"
            "charon" -> "Charon"
            "fenrir" -> "Fenrir"
            "kore" -> "Kore"
            "leda" -> "Leda"
            else -> "Puck"
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Read this aloud clearly, naturally and expressively:\n\n$text")
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", cleanVoice)
                        })
                    })
                })
            })
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = JSONObject(s.ifBlank { "{}" }).optJSONObject("error")?.optString("message").orEmpty()
                error("Gemini Speech HTTP ${resp.code} $errMsg")
            }
            val json = JSONObject(s)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val dataBase64 = inlineData.optString("data")
                    val mimeType = inlineData.optString("mimeType")
                    if (dataBase64.isNotBlank()) {
                        val rawAudio = Base64.decode(dataBase64, Base64.DEFAULT)
                        return if (mimeType.contains("wav", ignoreCase = true) ||
                            (rawAudio.size > 4 && rawAudio[0] == 'R'.code.toByte() && rawAudio[1] == 'I'.code.toByte())) {
                            rawAudio
                        } else {
                            wrapPcmToWav(rawAudio, 24000, 1)
                        }
                    }
                }
            }
            return null
        }
    }

    private fun wrapPcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 2).toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        return header + pcmData
    }

    private fun edgeSpeech(text: String): ByteArray? {
        val lang = JarvisPreferences.ttsLanguage.value.ifBlank { "hi-IN" }
        val isHindi = lang.startsWith("hi", ignoreCase = true) || text.any { it.code in 0x0900..0x097F }
        val voice = if (isHindi) "hi-IN-SwaraNeural" else "en-US-JennyNeural"
        val rate = JarvisPreferences.ttsRate.value
        val ratePercent = "${((rate - 1f) * 100).toInt():+d}%"
        val pitchPercent = "${((JarvisPreferences.ttsPitch.value - 1f) * 50).toInt():+d}Hz"
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'><voice name='$voice'><prosody pitch='$pitchPercent' rate='$ratePercent'>$escaped</prosody></voice></speak>"
        val reqId = UUID.randomUUID().toString().replace("-", "")

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val req = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .build()

        val output = ByteArrayOutputStream()
        val done = CompletableDeferred<Boolean>()

        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                val cfg = "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(cfg)
                val msg = "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLen
                    if (data.size > audioStart) {
                        synchronized(output) {
                            output.write(data, audioStart, data.size - audioStart)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    done.complete(true)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                done.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(true)
            }
        })

        try {
            val success = kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(12000L) { done.await() } ?: false
            }
            if (success) {
                val bytes = synchronized(output) { output.toByteArray() }
                if (bytes.isNotEmpty()) return bytes
            }
        } catch (_: Exception) {
        } finally {
            try { ws.close(1000, null) } catch (_: Exception) {}
        }
        return null
    }

    private fun googleCloud(text: String, key: String): ByteArray? {
        val lang = JarvisPreferences.ttsLanguage.value.ifBlank { "en-US" }
        val voice = JarvisPreferences.ttsVoice.value
        val body = JSONObject().apply {
            put("input", JSONObject().put("text", text))
            put("voice", JSONObject().apply {
                put("languageCode", lang)
                if (voice.isNotBlank()) put("name", voice)
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", "MP3")
                put("speakingRate", JarvisPreferences.ttsRate.value.toDouble().coerceIn(0.25, 4.0))
                put("pitch", ((JarvisPreferences.ttsPitch.value - 1f) * 10.0).coerceIn(-20.0, 20.0))
            })
        }.toString()
        val req = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${JSONObject(s.ifBlank { "{}" }).optJSONObject("error")?.optString("message").orEmpty()}")
            val audio = JSONObject(s).optString("audioContent")
            return if (audio.isBlank()) null else Base64.decode(audio, Base64.DEFAULT)
        }
    }

    private fun openAiSpeech(text: String, key: String): ByteArray? {
        val base = JarvisPreferences.ttsBaseUrl.value.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        val body = JSONObject().apply {
            put("model", JarvisPreferences.ttsModel.value.ifBlank { "tts-1" })
            put("input", text)
            put("voice", JarvisPreferences.ttsVoice.value.ifBlank { "alloy" })
            put("speed", JarvisPreferences.ttsRate.value.toDouble().coerceIn(0.25, 4.0))
            put("response_format", "mp3")
        }.toString()
        val req = Request.Builder()
            .url("$base/audio/speech")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.body?.string().orEmpty().take(160)}")
            return resp.body?.bytes()
        }
    }

    private suspend fun playBytes(bytes: ByteArray) {
        val file = File(app.cacheDir, "jarvis_tts_${System.nanoTime()}.mp3")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setOnCompletionListener { runCatching { mp.release() }; if (player === mp) player = null; if (cont.isActive) cont.resume(Unit) }
                // a player that stops without "completed" (audio route changed, decoder hiccup) must not leave Jarvis
                // "speaking" forever: once it played and then stays silent for 3 s it counts as finished
                scope.launch {
                    var played = false
                    var silentFor = 0
                    var waited = 0
                    while (cont.isActive) {
                        delay(1000)
                        val playing = runCatching { player === mp && mp.isPlaying }.getOrDefault(false)
                        if (playing) { played = true; silentFor = 0 } else if (played) silentFor++ else waited++
                        if ((played && silentFor >= 3) || (!played && (player !== mp || waited >= 20))) {
                            runCatching { mp.release() }; if (player === mp) player = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                mp.setOnErrorListener { _, _, _ -> runCatching { mp.release() }; if (player === mp) player = null; if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() }; if (player === mp) player = null }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { it.start() }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    runCatching { mp.release() }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } finally {
            file.delete()
        }
    }

    // ---- audio focus (other audio ducks while Jarvis talks)

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
