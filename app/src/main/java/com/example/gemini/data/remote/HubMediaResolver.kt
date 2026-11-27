package com.example.gemini.data.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-RAM caching and resolver for media & documents produced by AGY Hub / Cortex
 * (such as Termux host paths, images, or remote files). Avoids disk writes.
 */
object HubMediaResolver {
    private const val TAG = "HubMediaResolver"
    private const val FAILED_CACHE_TTL_MS = 60_000L
    private val IMAGE_EXT_REGEX = Regex(""".*\.(png|jpe?g|webp|gif|svg|bmp|heic)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    private val imageRamCache = ConcurrentHashMap<String, String>()          // normalizedUri -> data:image/xxx;base64,...
    private val imageBytesCache = ConcurrentHashMap<String, ByteArray>()     // normalizedUri -> ByteArray
    private val imageAspectRatioCache = ConcurrentHashMap<String, Float>()   // normalizedUri -> width / height
    private val failedMediaCache = ConcurrentHashMap<String, Long>()         // normalizedUri -> failed timestamp
    private val documentRamCache = ConcurrentHashMap<String, String>()       // normalizedUri -> content string
    private val downloadMutex = Mutex()

    fun normalizeKey(uriOrPath: String): String {
        return uriOrPath.trim().removePrefix("file://")
    }

    /**
     * True only when [raw] looks like an image data URI, content URI, HTTP(S) image URL,
     * or single-line local image path — never multi-line command stdout or plain prose.
     */
    fun isRenderableImageSource(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return true
        if (trimmed.contains('\n') || trimmed.contains('\r') || trimmed.length > 2048) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return true
        return (trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) &&
            trimmed.matches(IMAGE_EXT_REGEX)
    }

    private fun isLikelyHubMediaPath(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return true
        if (trimmed.contains('\n') || trimmed.contains('\r') || trimmed.length > 2048) return false
        return trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true)
    }

    fun hasFailedRecently(uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        val key = normalizeKey(uriOrPath)
        val ts = failedMediaCache[key] ?: return false
        if (System.currentTimeMillis() - ts > FAILED_CACHE_TTL_MS) {
            failedMediaCache.remove(key, ts)
            return false
        }
        return true
    }

    fun markFailed(uriOrPath: String) {
        if (uriOrPath.isBlank()) return
        failedMediaCache[normalizeKey(uriOrPath)] = System.currentTimeMillis()
    }

    fun clearFailed(uriOrPath: String) {
        if (uriOrPath.isBlank()) return
        failedMediaCache.remove(normalizeKey(uriOrPath))
    }

    fun putAspectRatio(uriOrPath: String, width: Int, height: Int) {
        if (uriOrPath.isBlank() || width <= 0 || height <= 0) return
        val ratio = (width.toFloat() / height.toFloat()).coerceIn(0.4f, 3.0f)
        imageAspectRatioCache[normalizeKey(uriOrPath)] = ratio
    }

    fun getCachedAspectRatio(uriOrPath: String): Float? {
        if (uriOrPath.isBlank()) return null
        return imageAspectRatioCache[normalizeKey(uriOrPath)]
    }

    /**
     * Synchronously returns the cached aspect ratio, or peeks local file / in-RAM bytes bounds
     * without allocating pixel memory so LazyColumn containers know exact image height on frame 0.
     */
    fun getOrPeekAspectRatio(context: Context, uriOrPath: String): Float? {
        if (uriOrPath.isBlank()) return null
        val key = normalizeKey(uriOrPath)
        imageAspectRatioCache[key]?.let { return it }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = imageBytesCache[key]
        if (bytes != null && bytes.isNotEmpty()) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val ratio = (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.4f, 3.0f)
                imageAspectRatioCache[key] = ratio
                return ratio
            }
        }

        val trimmed = uriOrPath.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val b64 = trimmed.substringAfter("base64,", "")
            if (b64.isNotBlank()) {
                runCatching {
                    val decoded = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decoded, 0, decoded.size, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    val ratio = (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.4f, 3.0f)
                    imageAspectRatioCache[key] = ratio
                    return ratio
                }
            }
        }

        val localFile = File(key)
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            localFile.isFile && localFile.canRead()
        ) {
            runCatching { BitmapFactory.decodeFile(localFile.absolutePath, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val ratio = (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.4f, 3.0f)
                imageAspectRatioCache[key] = ratio
                return ratio
            }
        }
        return null
    }

    fun getCachedDocument(path: String): String? = documentRamCache[normalizeKey(path)]

    fun putCachedDocument(path: String, content: String) {
        documentRamCache[normalizeKey(path)] = content
    }

    fun invalidateDocument(path: String) {
        documentRamCache.remove(normalizeKey(path))
    }

    fun getImageBytes(uriOrPath: String): ByteArray? {
        val key = normalizeKey(uriOrPath)
        return imageBytesCache[key]
    }

    /** A file served by the IDE bridge (Claude chat attachments): needs the bridge's auth header, so Coil cannot load it. */
    fun isBridgeFileUrl(uri: String): Boolean =
        uri.startsWith(com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/claude/attachments/")

    /** Downloads a bridge file with the authenticated client (null when it is gone or the bridge is offline). */
    suspend fun fetchBridgeFile(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            AgyBridgeService.instance.client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** Saves a bridge file into the app cache (to open it with another app or the file viewer), straight to disk. */
    suspend fun downloadBridgeFile(context: Context, url: String, name: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "claude-attachments").apply { mkdirs() }
        val target = File(dir, name.substringAfterLast('/').ifBlank { "attachment" })
        if (streamToFile(url, target) { }) {
            com.example.gemini.data.files.CacheJanitor.trimAll(context)
            target
        } else null
    }

    /** How to play or read a media file without copying it first: a file or link the app opens itself, or a bridge stream. */
    data class MediaStream(val uri: android.net.Uri, val headers: Map<String, String>, val viaBridge: Boolean)

    /** The security headers every bridge request needs (also for players that fetch the stream themselves). */
    fun bridgeHeaders(): Map<String, String> = buildMap {
        put(com.example.gemini.data.preferences.AuthPreferences.currentFramedHeader, "true")
        com.example.gemini.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> put(n, v) }
    }

    private fun bridgeRawUrl(path: String): String =
        com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/') +
            "/api/fs/raw?path=" + java.net.URLEncoder.encode(path, "UTF-8")

    private fun localPathOf(source: String): String =
        if (source.startsWith("file://")) runCatching { android.net.Uri.parse(source).path }.getOrNull() ?: source.removePrefix("file://")
        else source

    /**
     * Where to play [source] from. A web link, `content://` uri or a file this phone can read is used directly. Anything
     * else is a file only the bridge sees (Server mode, or a Linux distro in local mode): it is streamed from
     * `/api/fs/raw`, which supports seeking and never loads the whole file into the bridge's memory.
     */
    fun mediaStreamFor(source: String): MediaStream {
        val s = source.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val fixed = com.example.gemini.data.preferences.AuthPreferences.rebaseBridgeUrl(s)
            if (!com.example.gemini.data.preferences.AuthPreferences.isBridgeUrl(fixed)) return MediaStream(android.net.Uri.parse(s), emptyMap(), false)
            // /api/file/read loads the whole file into the bridge's memory; /api/fs/raw streams it
            val u = android.net.Uri.parse(fixed)
            val streamed = if (u.path?.endsWith("/api/file/read") == true) u.getQueryParameter("path")?.let(::bridgeRawUrl) else null
            return MediaStream(android.net.Uri.parse(streamed ?: fixed), bridgeHeaders(), true)
        }
        if (s.startsWith("content://")) return MediaStream(android.net.Uri.parse(s), emptyMap(), false)
        val path = localPathOf(s)
        if (runCatching { File(path).let { it.isFile && it.canRead() } }.getOrDefault(false)) {
            return MediaStream(android.net.Uri.fromFile(File(path)), emptyMap(), false)
        }
        return MediaStream(android.net.Uri.parse(bridgeRawUrl(path)), bridgeHeaders(), true)
    }

    /** A readable file name for [source] (a path, a link, or a bridge link carrying `?path=`). */
    fun displayNameOf(source: String): String {
        val s = source.trim()
        val inner = runCatching { android.net.Uri.parse(s).getQueryParameter("path") }.getOrNull()
        val raw = (inner ?: s).substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).ifBlank { "video" }
    }

    /** Copies [url] from the bridge into [target] in chunks (no whole-file buffer); false on any failure. */
    private suspend fun streamToFile(url: String, target: File, onProgress: (Float?) -> Unit): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        try {
            val client = com.example.gemini.data.files.FileExplorerApi.transferClient
            client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength()
                val ctx = kotlin.coroutines.coroutineContext
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            ctx.ensureActive() // the player was closed: stop downloading
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            if (part.length() <= 0) return false
            if (target.exists()) target.delete()
            return part.renameTo(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bridge download failed: ${e.message}")
            return false
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /**
     * Where the bridge serves [source] from, or null when the app can use it directly: a normal link, a `content://`
     * uri, or (local mode) a file on this phone. A link to the bridge needs its security header, and in Server mode a
     * plain path such as `/home/me/clip.mp4` is a file on the SERVER, so it is read through the bridge as well.
     */
    fun bridgeUrlFor(source: String): String? {
        val s = source.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val fixed = com.example.gemini.data.preferences.AuthPreferences.rebaseBridgeUrl(s)
            return fixed.takeIf { com.example.gemini.data.preferences.AuthPreferences.isBridgeUrl(it) }
        }
        if (s.startsWith("content://")) return null
        if (!com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode) return null
        val path = s.removePrefix("file://")
        // a file that exists on this phone (for example a video you attached) is always used directly
        if (runCatching { File(path).canRead() }.getOrDefault(false)) return null
        val base = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        return "$base/api/file/read?path=" + java.net.URLEncoder.encode(path, "UTF-8")
    }

    private fun mediaCacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "bridge-media").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val ext = java.net.URLDecoder.decode(url, "UTF-8").substringBefore('#').substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "bin"
        return File(dir, "$hash.$ext")
    }

    /** The file if this bridge media was downloaded before (null otherwise). */
    fun cachedBridgeMedia(context: Context, url: String): File? =
        mediaCacheFile(context, url).takeIf { it.exists() && it.length() > 0 }?.also { com.example.gemini.data.files.CacheJanitor.touch(it) }

    /**
     * Copies a bridge file (video) into the app cache, chunk by chunk, trying [url] then each of [alternatives] (an older
     * bridge may lack the streaming endpoint). Reused next time; the cache is trimmed afterwards. Null on failure.
     * One download at a time, so two players asking for the same video never write the same file.
     */
    suspend fun downloadBridgeMedia(
        context: Context,
        url: String,
        alternatives: List<String> = emptyList(),
        onProgress: (Float?) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            cachedBridgeMedia(context, url)?.let { return@withLock it }
            val target = mediaCacheFile(context, url)
            val ok = (listOf(url) + alternatives).distinct().any { streamToFile(it, target, onProgress) }
            com.example.gemini.data.files.CacheJanitor.trimAll(context, force = true)
            target.takeIf { ok && it.exists() }
        }
    }

    fun clearRamCache() {
        imageRamCache.clear()
        imageBytesCache.clear()
        imageAspectRatioCache.clear()
        failedMediaCache.clear()
        documentRamCache.clear()
    }

    /** Returns a snapshot copy of all in-RAM cached image data URIs. */
    fun getAllCachedImages(): Map<String, String> = HashMap(imageRamCache)

    /**
     * Checks if the URI is already local or already cached in RAM memory.
     * Note: remote HTTP(S) URLs do not require Hub resolution, so they also return true here;
     * use [isInMemoryOrLocalDisk] to check whether the image bytes/file are immediately local.
     */
    fun isLocalOrCached(context: Context, uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (isBridgeFileUrl(uriOrPath)) return imageRamCache.containsKey(normalizeKey(uriOrPath))
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return true
        }
        val key = normalizeKey(uriOrPath)
        if (imageBytesCache.containsKey(key) || imageRamCache.containsKey(key) || documentRamCache.containsKey(key)) {
            return true
        }
        val directFile = File(key)
        return directFile.isFile && directFile.canRead()
    }

    /**
     * True when the media bytes/file are already on local storage or decoded in RAM
     * (never true for unverified remote paths or non-existent files).
     */
    fun isInMemoryOrLocalDisk(uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (uriOrPath.startsWith("data:image/", ignoreCase = true)) return true
        val key = normalizeKey(uriOrPath)
        if (imageBytesCache.containsKey(key) || imageRamCache[key]?.startsWith("data:") == true) return true
        if (uriOrPath.startsWith("http://", ignoreCase = true) || uriOrPath.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val directFile = File(key)
        return directFile.isFile && directFile.canRead()
    }

    /**
     * True only when [uriOrPath] still needs asynchronous Hub/Bridge resolution and has not
     * recently failed resolution.
     */
    fun needsAsyncResolution(context: Context, uriOrPath: String): Boolean {
        if (!isLikelyHubMediaPath(uriOrPath)) return false
        if (hasFailedRecently(uriOrPath)) return false
        return !isLocalOrCached(context, uriOrPath)
    }

    /**
     * Synchronously returns the in-RAM cached data URI or local URI if present,
     * otherwise returns the original URI.
     */
    fun getResolvedUriSync(context: Context, uriOrPath: String): String {
        if (uriOrPath.isBlank()) return ""
        if (isBridgeFileUrl(uriOrPath)) return imageRamCache[normalizeKey(uriOrPath)] ?: ""
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return uriOrPath
        }
        val key = normalizeKey(uriOrPath)
        imageRamCache[key]?.let { return it }

        val directFile = File(key)
        if (directFile.isFile && directFile.canRead()) {
            val res = "file://${directFile.absolutePath}"
            imageRamCache[key] = res
            return res
        }

        return uriOrPath
    }

    /**
     * Asynchronously downloads file data into in-RAM cache from daemon LanguageServerService/ReadFile.
     * Returns in-memory data:image/... URI with zero disk writes.
     */
    suspend fun resolveMediaUri(
        context: Context,
        rawUri: String,
        agyHubClient: AgyHubClient = AgyHubClient(),
        hubUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentHubUrl
    ): String = withContext(Dispatchers.IO) {
        if (!isLikelyHubMediaPath(rawUri)) return@withContext ""
        if (hasFailedRecently(rawUri)) return@withContext ""

        if (isBridgeFileUrl(rawUri)) {
            val key = normalizeKey(rawUri)
            imageRamCache[key]?.let { return@withContext it }
            val bytes = fetchBridgeFile(rawUri)
            if (bytes == null) {
                markFailed(rawUri)
                return@withContext ""
            }
            clearFailed(rawUri)
            val mime = java.net.URLConnection.guessContentTypeFromName(rawUri.substringAfterLast("%2F")) ?: "image/jpeg"
            imageBytesCache[key] = bytes
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                putAspectRatio(key, opts.outWidth, opts.outHeight)
            }
            return@withContext "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}".also { imageRamCache[key] = it }
        }
        if (rawUri.startsWith("data:image/") || rawUri.startsWith("http://") || rawUri.startsWith("https://") || rawUri.startsWith("content://")) {
            return@withContext rawUri
        }

        val key = normalizeKey(rawUri)
        imageRamCache[key]?.let { return@withContext it }

        val directFile = File(key)
        if (directFile.isFile && directFile.canRead()) {
            val localUri = "file://${directFile.absolutePath}"
            imageRamCache[key] = localUri
            clearFailed(rawUri)
            return@withContext localUri
        }

        downloadMutex.withLock {
            imageRamCache[key]?.let { return@withContext it }
            if (hasFailedRecently(rawUri)) return@withLock ""

            try {
                val formattedUri = "file://$key"
                Log.d("ANTI_MEDIA", "HubMediaResolver: Fetching $formattedUri from AGY Hub ($hubUrl)")
                val res = agyHubClient.readFileAsBase64(formattedUri, hubUrl)
                res.onSuccess { base64Data ->
                    if (base64Data.isNotBlank()) {
                        val ext = key.substringAfterLast('.', "jpg").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
                        val dataUri = "data:$mime;base64,$base64Data"
                        try {
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            imageBytesCache[key] = bytes
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                putAspectRatio(key, opts.outWidth, opts.outHeight)
                            }
                        } catch (_: Exception) {}
                        imageRamCache[key] = dataUri
                        documentRamCache[key] = base64Data
                        clearFailed(rawUri)
                        Log.d("ANTI_MEDIA", "HubMediaResolver: Cached in RAM for $rawUri (len=${dataUri.length})")
                        return@withContext dataUri
                    }
                }.onFailure { err ->
                    Log.w("ANTI_MEDIA", "HubMediaResolver: Failed to read file from hub: $rawUri: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e("ANTI_MEDIA", "HubMediaResolver: Error resolving media $rawUri: ${e.message}")
            }
            markFailed(rawUri)
        }

        return@withContext ""
    }
}

