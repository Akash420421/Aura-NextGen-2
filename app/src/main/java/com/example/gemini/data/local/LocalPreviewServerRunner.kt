package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import com.example.gemini.data.live.AuraAgentEvent
import com.example.gemini.data.live.AuraAgentEventBus
import com.example.gemini.data.live.ChatContextBridge
import com.example.gemini.ui.browser.BrowserSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Verified local development server launcher for Aura website/app builds.
 *
 * - Strictly blocks invalid `python3 index.html` / `python *.html` execution attempts.
 * - Auto-detects project type (Node/Vite/Next vs static HTML/CSS/JS).
 * - Picks preferred port 3000, automatically falling back to the next free port (3001..3025).
 * - Polls http://127.0.0.1:<port> until HTTP 200 (up to 10s).
 * - Opens the verified http://localhost:<actualPort> in Aura's In-Built Browser and emits
 *   a ServerReady event on AuraAgentEventBus.
 */
object LocalPreviewServerRunner {
    private const val TAG = "LocalPreviewServer"
    const val DEFAULT_PORT = 3000
    private const val PREFERRED_PORT = DEFAULT_PORT
    private const val MAX_PORT = 3025
    private const val DEFAULT_PROJECTS_DIR = "/data/user/0/com.termux/files/home/projects"

    private val invalidPythonHtmlRegex = Regex(
        pattern = """(?i)\bpython3?\s+(?:[^\s;|&]*\.html?|["'][^"']*\.html?["'])(\s|$)""",
    )

    data class PreviewResult(
        val success: Boolean,
        val url: String = "",
        val port: Int = 0,
        val projectDir: String = "",
        val serverKind: String = "",
        val message: String
    )

    /** Returns true if a shell command tries to run an HTML file directly with python/python3. */
    fun isInvalidPythonHtmlCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("-m http.server", ignoreCase = true)) return false
        return invalidPythonHtmlRegex.containsMatchIn(trimmed)
    }

    /** Finds an available local port starting at [preferredPort] (default 3000). */
    fun findAvailablePort(preferredPort: Int = PREFERRED_PORT): Int {
        for (port in preferredPort..MAX_PORT) {
            try {
                ServerSocket(port).use { socket ->
                    socket.reuseAddress = true
                    return port
                }
            } catch (_: Exception) {
                // Port busy, try next
            }
        }
        return preferredPort
    }

    /** Polls http://127.0.0.1:[port] until HTTP 200..399 is received or [timeoutMs] elapses. */
    suspend fun awaitHttpReady(port: Int, timeoutMs: Long = 10_000L): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHttpResponding(port)) return@withContext true
            delay(350L)
        }
        false
    }

    fun isHttpResponding(port: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 800
                readTimeout = 800
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Resolves a project directory (from explicit argument, active project context, or latest folder with index.html/package.json),
     * starts the appropriate server on port 3000 (or next free port), waits for HTTP 200, and optionally opens Aura's In-Built Browser.
     */
    suspend fun startAndVerifyPreview(
        context: Context,
        projectDir: String? = null,
        preferredPort: Int = PREFERRED_PORT,
        openInBrowser: Boolean = true
    ): PreviewResult = withContext(Dispatchers.IO) {
        val resolvedDir = resolveProjectDirectory(projectDir)
            ?: return@withContext PreviewResult(
                success = false,
                message = "Could not locate a valid project folder with index.html or package.json."
            )

        val dirFile = File(resolvedDir)
        val pkgJson = File(dirFile, "package.json")
        val hasNodeProject = pkgJson.exists() && pkgJson.isFile
        val pkgContent = if (hasNodeProject) runCatching { pkgJson.readText() }.getOrDefault("") else ""
        val isVite = pkgContent.contains("vite", ignoreCase = true)
        val isNext = pkgContent.contains("next", ignoreCase = true)
        val hasDevScript = pkgContent.contains("\"dev\"")
        val hasStartScript = pkgContent.contains("\"start\"")

        val chosenPort = findAvailablePort(preferredPort)
        val escapedDir = resolvedDir.replace("'", "'\"'\"'")
        val logFile = "/data/user/0/com.termux/files/usr/tmp/aura_preview_$chosenPort.log"

        val (serverKind, launchCmd) = when {
            hasNodeProject && isVite && hasDevScript -> {
                "Vite dev server" to "nohup npm run dev -- --host 127.0.0.1 --port $chosenPort > '$logFile' 2>&1 &"
            }
            hasNodeProject && isNext && hasDevScript -> {
                "Next.js dev server" to "PORT=$chosenPort nohup npm run dev -- -H 127.0.0.1 -p $chosenPort > '$logFile' 2>&1 &"
            }
            hasNodeProject && hasDevScript -> {
                "Node dev server" to "PORT=$chosenPort HOST=127.0.0.1 nohup npm run dev > '$logFile' 2>&1 &"
            }
            hasNodeProject && hasStartScript -> {
                "Node server" to "PORT=$chosenPort HOST=127.0.0.1 nohup npm start > '$logFile' 2>&1 &"
            }
            else -> {
                "Static HTTP server" to "nohup python3 -m http.server $chosenPort --bind 127.0.0.1 --directory '$escapedDir' > '$logFile' 2>&1 &"
            }
        }

        Log.i(TAG, "Starting $serverKind in '$resolvedDir' on port $chosenPort: $launchCmd")
        val execRes = LocalEnvironmentManager.executeCommand(
            command = launchCmd,
            context = context,
            workingDir = resolvedDir,
            timeoutSeconds = 12
        )

        var ready = awaitHttpReady(chosenPort, timeoutMs = 10_000L)

        // Fallback to static HTTP server if Node dev server failed to bind (e.g. missing node_modules) and dist/ or index.html exists
        var finalKind = serverKind
        if (!ready && hasNodeProject) {
            val staticRoot = listOf(File(dirFile, "dist"), File(dirFile, "build"), dirFile)
                .firstOrNull { File(it, "index.html").exists() }
            if (staticRoot != null) {
                val fallbackDir = staticRoot.absolutePath.replace("'", "'\"'\"'")
                val fallbackCmd = "nohup python3 -m http.server $chosenPort --bind 127.0.0.1 --directory '$fallbackDir' > '$logFile' 2>&1 &"
                Log.w(TAG, "Node dev server did not respond on $chosenPort; falling back to static HTTP server on $fallbackDir")
                LocalEnvironmentManager.executeCommand(
                    command = fallbackCmd,
                    context = context,
                    workingDir = staticRoot.absolutePath,
                    timeoutSeconds = 10
                )
                ready = awaitHttpReady(chosenPort, timeoutMs = 6_000L)
                if (ready) finalKind = "Static HTTP server"
            }
        }

        val url = "http://localhost:$chosenPort"
        if (!ready) {
            val tailLog = runCatching { File(logFile).takeIf { it.exists() }?.readText()?.takeLast(300) }.getOrNull().orEmpty()
            val reason = "Local server ($serverKind) did not respond with HTTP 200 on port $chosenPort within 10s." +
                (if (tailLog.isNotBlank()) " Log: $tailLog" else if (execRes.output.isNotBlank()) " Output: ${execRes.output.take(200)}" else "")
            Log.e(TAG, reason)
            return@withContext PreviewResult(
                success = false,
                url = url,
                port = chosenPort,
                projectDir = resolvedDir,
                serverKind = finalKind,
                message = reason
            )
        }

        val projectName = dirFile.name.ifBlank { "Project" }
        AuraAgentEventBus.updateActiveProject { current ->
            current.copy(
                projectName = current.projectName.ifBlank { projectName },
                workspaceDir = current.workspaceDir.ifBlank { resolvedDir },
                previewUrl = url
            )
        }

        if (openInBrowser) {
            withContext(Dispatchers.Main) {
                BrowserSessionManager.instance.openUrl(url, newTab = true)
                ChatContextBridge.requestViewMode("BROWSER")
            }
        }

        val convId = ChatContextBridge.getCurrentConversation()?.id.orEmpty()
        AuraAgentEventBus.emit(
            AuraAgentEvent.ServerReady(
                conversationId = convId,
                url = url,
                port = chosenPort,
                projectName = projectName
            )
        )

        PreviewResult(
            success = true,
            url = url,
            port = chosenPort,
            projectDir = resolvedDir,
            serverKind = finalKind,
            message = "Verified $finalKind running at $url (HTTP 200 OK)" +
                if (openInBrowser) " and opened in Aura's In-Built Browser." else "."
        )
    }

    private fun resolveProjectDirectory(explicitDir: String?): String? {
        val candidates = mutableListOf<File>()
        if (!explicitDir.isNullOrBlank()) {
            val raw = explicitDir.trim()
            val f = if (raw.startsWith("/")) File(raw) else File(DEFAULT_PROJECTS_DIR, raw)
            candidates.add(if (f.isFile) f.parentFile ?: f else f)
        }
        val activeDir = AuraAgentEventBus.activeProject.value.workspaceDir
        if (activeDir.isNotBlank()) {
            candidates.add(File(activeDir.removePrefix("file://")))
        }
        val convWs = ChatContextBridge.getCurrentConversation()?.workspaceUri?.removePrefix("file://").orEmpty()
        if (convWs.isNotBlank()) {
            candidates.add(File(convWs))
        }

        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory && (File(dir, "index.html").exists() || File(dir, "package.json").exists())) {
                return dir.absolutePath
            }
        }
        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory) return dir.absolutePath
        }

        val base = File(DEFAULT_PROJECTS_DIR)
        if (base.exists() && base.isDirectory) {
            val recentSub = base.listFiles()
                ?.filter { it.isDirectory && (File(it, "index.html").exists() || File(it, "package.json").exists()) }
                ?.maxByOrNull { it.lastModified() }
            if (recentSub != null) return recentSub.absolutePath
            if (File(base, "index.html").exists() || File(base, "package.json").exists()) {
                return base.absolutePath
            }
        }
        return null
    }
}
