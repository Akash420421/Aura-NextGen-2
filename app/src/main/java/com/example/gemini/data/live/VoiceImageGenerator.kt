package com.example.gemini.data.live

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.util.Base64
import android.util.Log
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.remote.HubMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Direct native image generator for Aura Voice.
 * Uses Gemini REST `generateContent` image models (primary: `gemini-nano-banana-2.1`)
 * so voice requests ("mere liye ek cat ki image banao") never refuse or stall,
 * saves directly into Aura's gallery directory, and warms HubMediaResolver dimensions.
 */
object VoiceImageGenerator {
    private const val TAG = "VoiceImageGenerator"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val IMAGE_MODELS = listOf(
        "gemini-nano-banana-2.1",
        "gemini-3.1-flash-lite-image",
        "gemini-3.1-flash-image",
        "gemini-2.5-flash-image",
        "gemini-3-pro-image"
    )

    val SUPPORTED_ASPECT_RATIOS = setOf(
        "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "1:4", "4:1", "1:8", "8:1"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ExtractedImage(
        val base64Data: String,
        val mimeType: String,
        val caption: String
    )

    data class GeneratedImageResult(
        val success: Boolean,
        val filePath: String = "",
        val mimeType: String = "image/png",
        val modelUsed: String = "",
        val caption: String = "",
        val error: String = ""
    )

    fun normalizeAspectRatio(raw: String?): String {
        val cleaned = raw.orEmpty().trim()
        return if (cleaned in SUPPORTED_ASPECT_RATIOS) cleaned else "1:1"
    }

    fun buildRequestPayloadJson(
        prompt: String,
        aspectRatio: String,
        model: String,
        includeImageConfig: Boolean = true
    ): JsonObject {
        val cleanPrompt = prompt.trim()
        val ratio = normalizeAspectRatio(aspectRatio)
        return buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", cleanPrompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("IMAGE")
                    add("TEXT")
                }
                if (includeImageConfig) {
                    putJsonObject("imageConfig") {
                        put("aspectRatio", ratio)
                        if (model.startsWith("gemini-nano-banana")) {
                            put("imageSize", "1K")
                        }
                    }
                }
            }
        }
    }

    fun extractImagePayloadFromJson(rawBody: String): ExtractedImage? {
        if (rawBody.isBlank()) return null
        val root = runCatching { jsonParser.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return null
        val candidates = root["candidates"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        var caption = ""
        for (c in candidates) {
            val content = runCatching { c.jsonObject["content"]?.jsonObject }.getOrNull() ?: continue
            val parts = runCatching { content["parts"]?.jsonArray }.getOrNull() ?: continue
            for (p in parts) {
                val partObj = runCatching { p.jsonObject }.getOrNull() ?: continue
                val text = runCatching { partObj["text"]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
                if (text.isNotBlank() && caption.isBlank()) {
                    caption = text
                }
                val inline = runCatching {
                    (partObj["inlineData"] ?: partObj["inline_data"])?.jsonObject
                }.getOrNull() ?: continue
                val data = runCatching { inline["data"]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
                val mime = runCatching {
                    (inline["mimeType"] ?: inline["mime_type"])?.jsonPrimitive?.content
                }.getOrNull()?.trim().orEmpty().ifBlank { "image/png" }
                if (data.length > 32) {
                    return ExtractedImage(base64Data = data, mimeType = mime, caption = caption)
                }
            }
        }
        return null
    }

    suspend fun generateAndSave(
        context: Context,
        prompt: String,
        aspectRatio: String = "1:1"
    ): GeneratedImageResult = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            return@withContext GeneratedImageResult(false, error = "Image prompt cannot be empty.")
        }
        val apiKey = JarvisPreferences.liveApiKey.value.trim().ifBlank {
            JarvisPreferences.ttsApiKey.value.trim()
        }
        if (apiKey.isBlank()) {
            return@withContext GeneratedImageResult(false, error = "No Gemini API key configured.")
        }

        val ratio = normalizeAspectRatio(aspectRatio)
        var lastError = "Unknown image generation error"

        for (model in IMAGE_MODELS) {
            val attemptConfigs = listOf(true, false)
            for (includeConfig in attemptConfigs) {
                val payload = buildRequestPayloadJson(cleanPrompt, ratio, model, includeImageConfig = includeConfig)
                val url = "$BASE_URL/$model:generateContent"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                var retryWithoutConfig = false
                var terminalAuthFailure: GeneratedImageResult? = null
                try {
                    val resultOrNull: GeneratedImageResult? = client.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = "HTTP ${resp.code} ($model): ${bodyStr.take(240)}"
                            Log.w(TAG, "Image gen failed on $model (config=$includeConfig): $lastError")
                            if (resp.code == 400 && includeConfig && bodyStr.contains("imageConfig", ignoreCase = true)) {
                                retryWithoutConfig = true
                                return@use null
                            }
                            if (resp.code == 401 || resp.code == 403) {
                                terminalAuthFailure = GeneratedImageResult(false, error = "Gemini API key rejected (${resp.code}).")
                                return@use null
                            }
                            return@use null
                        }

                        val extracted = extractImagePayloadFromJson(bodyStr)
                        if (extracted == null) {
                            lastError = "Model $model returned no inline image data."
                            Log.w(TAG, lastError)
                            return@use null
                        }

                        val decodedBytes = Base64.decode(extracted.base64Data, Base64.DEFAULT)
                        if (decodedBytes.isEmpty()) {
                            lastError = "Decoded image bytes were empty."
                            return@use null
                        }

                        val savedFile = saveImageBytes(context, cleanPrompt, extracted.mimeType, decodedBytes)
                        if (savedFile == null) {
                            lastError = "Could not save generated image to storage."
                            return@use null
                        }

                        // Warm HubMediaResolver aspect ratio so Bug #1 scroll renders deterministically on Frame 0
                        runCatching {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                HubMediaResolver.putAspectRatio(savedFile.absolutePath, opts.outWidth, opts.outHeight)
                                HubMediaResolver.putAspectRatio("file://${savedFile.absolutePath}", opts.outWidth, opts.outHeight)
                            }
                        }
                        runCatching {
                            MediaScannerConnection.scanFile(
                                context.applicationContext,
                                arrayOf(savedFile.absolutePath),
                                arrayOf(extracted.mimeType),
                                null
                            )
                        }

                        Log.i(TAG, "Generated image via $model -> ${savedFile.absolutePath} (${decodedBytes.size} bytes)")
                        GeneratedImageResult(
                            success = true,
                            filePath = savedFile.absolutePath,
                            mimeType = extracted.mimeType,
                            modelUsed = model,
                            caption = extracted.caption.ifBlank { cleanPrompt.take(80) }
                        )
                    }
                    terminalAuthFailure?.let { return@withContext it }
                    if (resultOrNull != null) {
                        return@withContext resultOrNull
                    }
                    if (!retryWithoutConfig) {
                        break
                    }
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.w(TAG, "Image gen exception on $model: $lastError")
                    break
                }
            }
        }

        return@withContext GeneratedImageResult(false, error = lastError)
    }

    private fun saveImageBytes(
        context: Context,
        prompt: String,
        mimeType: String,
        bytes: ByteArray
    ): File? {
        val ext = when {
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "png"
        }
        val slug = prompt.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(28)
            .ifBlank { "image" }
        val fileName = "aura_${slug}_${System.currentTimeMillis()}.$ext"

        val candidateDirs = listOf(
            File("/sdcard/Pictures/AURA_Vision"),
            File(context.filesDir, "images")
        )
        for (dir in candidateDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                outFile.writeBytes(bytes)
                if (outFile.exists() && outFile.length() > 0L) {
                    return outFile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not write to ${dir.absolutePath}: ${e.message}")
            }
        }
        return null
    }
}
