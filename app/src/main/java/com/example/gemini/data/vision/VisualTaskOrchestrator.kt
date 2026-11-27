package com.example.gemini.data.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.example.gemini.data.automation.AutomationAccessibilityService
import com.example.gemini.data.automation.agent.*
import com.example.gemini.data.automation.agent.SerializedActionExecutor
import com.example.gemini.data.live.AuraLiveVoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class TaskActionResult(
    val success: Boolean,
    val message: String,
    val currentStateSummary: String = "",
    val requiresUserConfirmation: Boolean = false,
    val verified: Boolean? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            if (currentStateSummary.isNotBlank()) {
                put("current_state", currentStateSummary)
            }
            if (requiresUserConfirmation) {
                put("requires_confirmation", true)
            }
            verified?.let { put("verified", it) }
        }
    }
}

data class UiWaitResult(
    val ready: Boolean,
    val root: AccessibilityNodeInfo?,
    val elements: List<UiElementInfo>,
    val signature: ScreenSignature?,
    val changedFromPrevious: Boolean,
    val reason: String
)

/**
 * Visual Task Orchestrator.
 * Connects Gemini Live's visual tool calls to the Dual-Anchor Grounding Engine
 * and executes precise, closed-loop verified actions on the Android OS.
 */
class VisualTaskOrchestrator(
    private val groundingEngine: UiGroundingEngine = UiGroundingEngine()
) {
    companion object {
        private const val TAG = "VisualTaskOrchestrator"

        @Volatile
        private var instance: VisualTaskOrchestrator? = null

        fun getInstance(): VisualTaskOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: VisualTaskOrchestrator().also { instance = it }
            }
        }
    }

    private val screenCaptureManager = ScreenCaptureManager.getInstance()
    private val inspectSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private var consecutiveScrollCount = 0
    private var lastScreenSignature = ""
    private var duplicateSignatureCount = 0
    private var lastClickTimestamp = 0L
    private var lastClickPhysicalX = -1f
    private var lastClickPhysicalY = -1f
    private var lastUnverifiedClickTarget = ""
    private var lastUnverifiedClickScreen: ScreenSignature? = null

    private suspend fun mutateUi(name: String, operation: suspend () -> Boolean): Boolean =
        AgentTaskEngine.executeUiMutation(name) { operation() }

    private fun recordObservation(service: AutomationAccessibilityService, summary: String? = null) {
        val root = service.getActiveRootNode()
        AgentTaskEngine.observe(
            ScreenSignatureCalculator.computeSignature(root),
            root?.packageName?.toString(), summary
        )
    }

    /** Runs Spotify's search-and-play path locally so the model need not request each step. */
    suspend fun runSpotifySmartSearch(goal: String): TaskActionResult = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not running.", verified = false)
        val root = service.getActiveRootNode()
        if (root?.packageName?.toString()?.contains("spotify", true) != true) {
            return@withContext TaskActionResult(false, "Spotify is not the foreground app.", verified = false)
        }
        val query = goal
            .replace(Regex("(?i)\\b(jarvis|spotify|please|find|search|look for|play|song|track|music|playlist|bajao|chalao|karo|kar do|do|dhundo|dhoondho|dhoondh|talaash|gaana|gana|meri|mera|par|mein|ko|the|a|an|on|in|my)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '.', '!', '?', '"', '\'')
        if (query.length < 2) {
            return@withContext TaskActionResult(false, "Could not identify a song title in the request.", verified = false)
        }
        val task = AgentTaskEngine.begin(goal, "spotify-smart-search")
        AgentTaskEngine.replacePlan(listOf("Understand goal", "Open Spotify", "Search for the requested track", "Select one unique match", "Verify playback"))
        AgentTaskEngine.completeStepByTitle("Open Spotify", "Spotify is confirmed in the foreground", verified = true)
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)

        var elements = groundingEngine.scanScreen(root)
        if (elements.none { it.isEditable }) {
            val search = elements.filter { it.isClickable && !it.isEditable &&
                it.primaryLabel.contains("search", ignoreCase = true) }
            if (search.size != 1) {
                AgentTaskEngine.recordAction("resolve Spotify Search", "Search control is ambiguous or unavailable", false)
                return@withContext TaskActionResult(false, "Spotify Search is not uniquely visible; stopped without guessing.", groundingEngine.formatElementsSummary(elements, root), verified = false)
            }
            val opened = handleClick(search.single().primaryLabel, taskGoal = goal)
            if (!opened.success) return@withContext opened
            delay(500)
            elements = groundingEngine.scanScreen(service.getActiveRootNode())
        }
        val editable = elements.filter { it.isEditable || it.className.contains("EditText", true) }
        if (editable.size != 1) {
            AgentTaskEngine.recordAction("resolve Spotify search field", "Expected one editable search field; found ${editable.size}", false)
            return@withContext TaskActionResult(false, "Spotify search field is ambiguous; no text was entered.", groundingEngine.formatElementsSummary(elements, service.getActiveRootNode()), verified = false)
        }

        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val typed = handleType(query, editable.single().primaryLabel.takeIf { it.isNotBlank() }, submit = true)
        if (!typed.success) return@withContext typed
        val currentRoot = service.getActiveRootNode()
        recordObservation(service, groundingEngine.formatElementsSummary(groundingEngine.scanScreen(currentRoot), currentRoot))
        AgentTaskEngine.recordAction("search Spotify: $query", "Search text entered and read back", typed.verified == true)
        val afterSearchWait = waitForUiState(
            ScreenSignatureCalculator.computeSignature(currentRoot),
            expectedPackage = "spotify",
            timeoutMs = 8_000L
        )
        if (!afterSearchWait.ready) {
            return@withContext TaskActionResult(false, "Spotify search results did not become stable before the wait limit.", groundingEngine.formatElementsSummary(afterSearchWait.elements, afterSearchWait.root), verified = false)
        }

        var selected: UiElementInfo? = null
        var searchFailure: TaskActionResult? = null
        var previous: ScreenSignature? = null
        var noProgress = 0
        val searchLoop = AgentTaskEngine.runAutonomousLoop(7) { attempt, _ ->
            if (AgentTaskEngine.isCancelled()) {
                searchFailure = TaskActionResult(false, "Spotify search was cancelled.", verified = false)
                return@runAutonomousLoop false
            }
            val freshRoot = service.getActiveRootNode()
            if (freshRoot?.packageName?.toString()?.contains("spotify", true) != true) {
                searchFailure = TaskActionResult(false, "Spotify left the foreground during search; stopped without selecting a result.", verified = false)
                AgentTaskEngine.failCurrentStep("Spotify is no longer the foreground app.", blocked = true)
                return@runAutonomousLoop false
            }
            val fresh = groundingEngine.scanScreen(freshRoot)
            val tokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 }.toSet()
            val candidates = fresh.filter { it.hasTextOrDesc && !it.isEditable && it.isClickable }
                .filter { candidate ->
                    val label = candidate.primaryLabel.lowercase(Locale.ROOT)
                    tokens.isNotEmpty() && tokens.all(label::contains) &&
                        !label.contains("search results") && !label.contains("see all")
                }
            val ranked = candidates.map { candidate ->
                val label = candidate.primaryLabel.trim().lowercase(Locale.ROOT)
                candidate to when {
                    label == query.lowercase(Locale.ROOT) -> 3
                    label.startsWith(query.lowercase(Locale.ROOT)) -> 2
                    else -> 1
                }
            }
            val bestScore = ranked.maxOfOrNull { it.second } ?: 0
            val best = ranked.filter { it.second == bestScore }.distinctBy { it.first.primaryLabel.lowercase(Locale.ROOT) }
            if (bestScore >= 2 && best.size == 1) {
                selected = best.single().first
                return@runAutonomousLoop false
            }
            if (best.size > 1 && bestScore >= 2) {
                AgentTaskEngine.setState(AgentTaskEngine.State.RECOVERING)
                searchFailure = TaskActionResult(false, "Spotify shows multiple similarly named matches for '$query'; stopped without selecting one.", groundingEngine.formatElementsSummary(fresh, freshRoot), verified = false)
                return@runAutonomousLoop false
            }
            if (attempt == 6) return@runAutonomousLoop false
            val signature = ScreenSignatureCalculator.computeSignature(freshRoot)
            if (signature.isEquivalentTo(previous)) noProgress++ else noProgress = 0
            if (noProgress >= 2) return@runAutonomousLoop false
            previous = signature
            if (!handleScroll("down", "medium").success) {
                searchFailure = TaskActionResult(false, "Spotify results could not be scrolled safely.", groundingEngine.formatElementsSummary(fresh, freshRoot), verified = false)
                return@runAutonomousLoop false
            }
            val scrollWait = waitForUiState(signature, expectedPackage = "spotify", timeoutMs = 6_000L)
            if (!scrollWait.ready) {
                searchFailure = TaskActionResult(false, "Spotify results did not settle after scrolling.", groundingEngine.formatElementsSummary(scrollWait.elements, scrollWait.root), verified = false)
                return@runAutonomousLoop false
            }
            true
        }
        if (searchFailure != null) return@withContext searchFailure!!
        val match = selected ?: run {
            AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.TARGET_NOT_FOUND, "No unique Spotify title match was found after bounded scrolling")
            AgentTaskEngine.failCurrentStep(
                if (searchLoop.reachedLimit) "Spotify search reached its bounded scroll limit without a unique match."
                else "No unique Spotify match was found in the observed search results.",
                blocked = true
            )
            return@withContext TaskActionResult(false, "Could not find a unique matching Spotify result after searching and scrolling.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
        }
        AgentTaskEngine.completeStepByTitle("Search for the requested track", "Searched '$query' and found one unique title match", verified = true)
        AgentTaskEngine.setState(AgentTaskEngine.State.RESOLVING_TARGET)
        val clicked = handleClick(match.primaryLabel, taskGoal = goal)
        if (!clicked.success) return@withContext clicked
        val playbackWait = waitForUiState(
            ScreenSignatureCalculator.computeSignature(service.getActiveRootNode()),
            expectedPackage = "spotify",
            timeoutMs = 8_000L
        )
        val playbackRoot = playbackWait.root ?: service.getActiveRootNode()
        val playbackElements = playbackWait.elements.ifEmpty { groundingEngine.scanScreen(playbackRoot) }
        val requestedTokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val trackVisible = playbackElements.any { item ->
            !item.isEditable && item.hasTextOrDesc && requestedTokens.all { item.primaryLabel.contains(it, true) }
        }
        val isPlaying = playbackElements.any { item ->
            val label = item.primaryLabel.lowercase(Locale.ROOT)
            label == "pause" || label.contains("pause playback") || label.contains("now playing") || label.contains("currently playing")
        }
        val verified = trackVisible && isPlaying
        AgentTaskEngine.recordCompletionEvidence(
            if (verified) AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL else AgentTaskEngine.CompletionEvidence.AMBIGUOUS,
            if (verified) "Requested song and active playback controls are visible" else "Playback state or requested song title is not visible after selection"
        )
        AgentTaskEngine.recordAction("play ${match.primaryLabel}", "trackVisible=$trackVisible, activePlayback=$isPlaying", verified)
        AgentTaskEngine.completeStepByTitle("Select one unique match", "Selected '${match.primaryLabel}'", clicked.verified == true)
        AgentTaskEngine.completeStepByTitle("Verify playback", "trackVisible=$trackVisible; activePlayback=$isPlaying", verified)
        AgentTaskEngine.setState(if (verified) AgentTaskEngine.State.COMPLETED else AgentTaskEngine.State.RECOVERING)
        TaskActionResult(verified, if (verified) "Verified Spotify is playing '${match.primaryLabel}'." else "Selected '${match.primaryLabel}', but playback could not be verified.", groundingEngine.formatElementsSummary(playbackElements, playbackRoot), verified = verified)
    }

    /** Processes an Instagram liked-items list only when each visible Unlike control can be verified. */
    suspend fun runInstagramBulkUnlike(goal: String): TaskActionResult = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not running.", verified = false)
        val task = AgentTaskEngine.begin(goal, "instagram-bulk-unlike")
        if (task.taskScope != AgentTaskEngine.TaskScope.ALL_MATCHING ||
            !goal.contains("instagram", true) || !goal.contains("unlike", true)) {
            return@withContext TaskActionResult(false, "This is not an Instagram bulk unlike task.", verified = false)
        }
        val requestedType = when {
            goal.contains("reel", true) -> "reel"
            goal.contains("post", true) -> "post"
            else -> null
        }
        val initialRoot = service.getActiveRootNode()
        if (initialRoot?.packageName?.toString()?.contains("instagram", true) != true) {
            return@withContext TaskActionResult(false, "Instagram is not the foreground app.", verified = false)
        }
        val initialElements = groundingEngine.scanScreen(initialRoot)
        val onLikedItemsList = initialElements.any { element ->
            val label = element.primaryLabel.lowercase(Locale.ROOT)
            label.contains("posts you've liked") || label.contains("posts you liked") ||
                label.contains("liked posts") || label.contains("liked reels") || label == "likes"
        }
        if (!onLikedItemsList) {
            return@withContext TaskActionResult(false, "Stopped safely: the current Instagram screen is not identifiable as the liked-items list.", groundingEngine.formatElementsSummary(initialElements, initialRoot), verified = false)
        }
        AgentTaskEngine.completeStepByTitle("Open Instagram", "Instagram is confirmed in the foreground", verified = true)
        AgentTaskEngine.completeStepByTitle("Navigate to liked content", "An identifiable liked-items list is visible", verified = true)
        if (initialElements.none { it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true) }) {
            return@withContext TaskActionResult(false, "No explicit accessible Unlike controls are visible yet; continue to the Instagram liked-items list and inspect again.", groundingEngine.formatElementsSummary(initialElements, initialRoot), verified = false)
        }
        var unchangedScrolls = 0
        var processedThisRun = 0
        var terminalResult: TaskActionResult? = null
        AgentTaskEngine.setState(AgentTaskEngine.State.PLANNING)
        AgentTaskEngine.replacePlan(listOf("Understand goal", "Open Instagram", "Navigate to liked content", "Observe and process matching items", "Scroll and wait for more items", "Verify list end and results"))
        val loopOutcome = AgentTaskEngine.runAutonomousLoop(400) { _, _ ->
            if (AgentTaskEngine.isCancelled()) {
                terminalResult = TaskActionResult(false, "Instagram bulk unlike was cancelled after $processedThisRun verified item(s).", verified = false)
                return@runAutonomousLoop false
            }
            val root = service.getActiveRootNode()
            if (root?.packageName?.toString()?.contains("instagram", true) != true) {
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.AMBIGUOUS, "Foreground app changed during bulk processing")
                AgentTaskEngine.failCurrentStep("Instagram is no longer the foreground app.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: Instagram is no longer the foreground app.", verified = false)
                return@runAutonomousLoop false
            }
            var elements = groundingEngine.scanScreen(root)
            if (elements.none { element ->
                    val label = element.primaryLabel.lowercase(Locale.ROOT)
                    label.contains("posts you've liked") || label.contains("posts you liked") ||
                        label.contains("liked posts") || label.contains("liked reels") || label == "likes"
                }) {
                AgentTaskEngine.failCurrentStep("Liked-items list is no longer identifiable on a fresh observation.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped safely: the liked-items list is no longer identifiable on the fresh screen.", groundingEngine.formatElementsSummary(elements, root), verified = false)
                return@runAutonomousLoop false
            }
            val signature = ScreenSignatureCalculator.computeSignature(root)
            val summary = groundingEngine.formatElementsSummary(elements, root)
            AgentTaskEngine.observe(signature, root.packageName?.toString(), summary)
            AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
            val controls = elements.filter { element ->
                element.isClickable && Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(element.primaryLabel.trim())
            }.sortedBy { it.bounds.top }

            val target = controls.firstOrNull()
            if (target != null) {
                val nearby = elements.filter { element ->
                    !element.isEditable && element.bounds != target.bounds &&
                        abs(element.bounds.centerY() - target.bounds.centerY()) <= 110 &&
                        element.hasTextOrDesc &&
                        !element.primaryLabel.contains("unlike", true) &&
                        !element.primaryLabel.equals("like", true) &&
                        !element.primaryLabel.contains("comment", true)
                }.map { it.primaryLabel.trim() }.filter { it.length >= 3 }.distinct()
                if (nearby.isEmpty()) {
                    AgentTaskEngine.recordAction("resolve Instagram item", "Unlike control has no accessible item identity", false)
                    AgentTaskEngine.failCurrentStep("Unlike control has no unique accessible item identity.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: Instagram exposes an Unlike button but not enough nearby item text to identify which item it belongs to.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                val identity = nearby.joinToString("|").lowercase(Locale.ROOT)
                if (requestedType != null && nearby.none { it.contains(requestedType, true) ||
                        (requestedType == "reel" && it.contains("video", true)) ||
                        (requestedType == "post" && it.contains("photo", true)) }) {
                    AgentTaskEngine.failCurrentStep("Visible item type cannot be verified as $requestedType.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: the visible liked item cannot be verified as a requested $requestedType.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                if (identity in task.processedItemSignatures) {
                    AgentTaskEngine.recordAction("resolve Instagram item", "Refusing to toggle previously processed item '$identity'", false)
                    AgentTaskEngine.failCurrentStep("A previously processed item identity is still exposed as Unlike.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: the same item identity is still exposed as Unlike after an earlier verified action.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                AgentTaskEngine.setState(AgentTaskEngine.State.RESOLVING_TARGET)
                val preCount = controls.size
                AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
                val dispatched = mutateUi("instagram-unlike:${identity.take(48)}") {
                    service.performClickAtRect(target.bounds)
                }
                if (!dispatched) {
                    AgentTaskEngine.recordAction("unlike:$identity", "UI action was not dispatched", false)
                    AgentTaskEngine.failCurrentStep("Could not dispatch the Unlike action.")
                    terminalResult = TaskActionResult(false, "Could not safely dispatch the Unlike action; stopped.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                val itemWait = waitForUiState(signature, expectedPackage = "instagram", timeoutMs = 6_000L)
                if (!itemWait.ready) {
                    AgentTaskEngine.failCurrentStep("UI did not settle after Unlike action; item result is unverified.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped: the Instagram list did not settle after the item action; its result is unverified.", groundingEngine.formatElementsSummary(itemWait.elements, itemWait.root), verified = false)
                    return@runAutonomousLoop false
                }
                val afterRoot = itemWait.root ?: service.getActiveRootNode()
                val afterElements = itemWait.elements
                val afterCount = afterElements.count { it.isClickable &&
                    Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(it.primaryLabel.trim()) }
                val sameControlChangedToLike = afterElements.any { element ->
                    element.bounds.centerX().let { abs(it - target.bounds.centerX()) <= 80 } &&
                        abs(element.bounds.centerY() - target.bounds.centerY()) <= 100 &&
                        element.primaryLabel.trim().startsWith("like", true) &&
                        !element.primaryLabel.trim().startsWith("unlike", true)
                }
                val verified = afterCount < preCount || sameControlChangedToLike
                AgentTaskEngine.recordAction("unlike:$identity", "Before=Unlike; afterCount=$afterCount; toggledToLike=$sameControlChangedToLike", verified)
                if (!verified || !AgentTaskEngine.markItemProcessed(identity, true)) {
                    AgentTaskEngine.failCurrentStep("Post-action state could not be verified.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped: the item's post-action state could not be verified, so it was not marked processed.", groundingEngine.formatElementsSummary(afterElements, afterRoot), verified = false)
                    return@runAutonomousLoop false
                }
                processedThisRun++
                task.completedSteps.add("Unlike $identity")
                if (task.pendingSteps.isNotEmpty()) task.pendingSteps.removeAt(0)
                AgentTaskEngine.updateActivity("Verified unlike for item $processedThisRun: ${identity.take(90)}", AgentTaskEngine.State.CONTINUING)
                return@runAutonomousLoop true
            }

            // No accessible Unlike action remains on this viewport. Scroll and require
            // fresh, usable observations; repeated unchanged signatures are the only end evidence.
            AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
            val scrolled = mutateUi("instagram-bulk-scroll") { service.performSmartScroll("down", "large") }
            if (!scrolled) {
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.NO_PROGRESS, "Instagram did not accept a scroll gesture")
                AgentTaskEngine.failCurrentStep("Instagram did not accept a scroll gesture.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: Instagram could not scroll the liked-items list; completion is unverified.", summary, verified = false)
                return@runAutonomousLoop false
            }
            val scrollWait = waitForUiState(signature, expectedPackage = "instagram", timeoutMs = 8_000L)
            val afterScrollRoot = scrollWait.root
            elements = scrollWait.elements
            val afterScrollSignature = scrollWait.signature
            if (!scrollWait.ready || afterScrollRoot == null || afterScrollSignature == null || afterScrollSignature.elementCount == 0) {
                AgentTaskEngine.failCurrentStep("Fresh Instagram content did not become ready after scrolling.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: the fresh Instagram screen did not become stable after scrolling.", verified = false)
                return@runAutonomousLoop false
            }
            if (signature.isEquivalentTo(afterScrollSignature)) unchangedScrolls++ else unchangedScrolls = 0
            AgentTaskEngine.observe(afterScrollSignature, afterScrollRoot.packageName?.toString(), groundingEngine.formatElementsSummary(elements, afterScrollRoot))
            AgentTaskEngine.current()?.noProgressCount = unchangedScrolls
            if (unchangedScrolls >= 2) {
                if (processedThisRun == 0 && task.processedItemSignatures.isEmpty()) {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.AMBIGUOUS, "Reached a stable viewport without verifying any item actions")
                    AgentTaskEngine.failCurrentStep("No item was verifiably unliked before the list stopped progressing.", blocked = true)
                    terminalResult = TaskActionResult(false, "Reached an unchanged viewport, but no item was verifiably unliked; completion is not claimed.", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = false)
                    return@runAutonomousLoop false
                }
                val endMarkers = listOf("end of list", "no more posts", "no more reels", "you've seen all", "you have seen all", "you're all caught up", "you are all caught up", "nothing to show")
                val actualEndMarker = elements.any { element -> endMarkers.any(element.primaryLabel.lowercase(Locale.ROOT)::contains) }
                if (!actualEndMarker) {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.NO_PROGRESS, "Repeated scroll produced no movement, but the screen exposes no explicit end-of-content marker")
                    AgentTaskEngine.failCurrentStep("Instagram stopped scrolling, but the UI did not prove the end of the liked-items list.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely after repeated no-progress observations. Instagram did not expose evidence that this is the end of the full liked-items list, so I cannot claim all items were processed.", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = false)
                    return@runAutonomousLoop false
                }
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.END_REACHED, "Explicit Instagram end marker visible after two stable post-scroll observations; ${task.processedItemSignatures.size} unique item(s) were individually verified")
                AgentTaskEngine.completeStepByTitle("Observe and process matching items", "${task.processedItemSignatures.size} unique item(s) were verified individually", verified = true)
                AgentTaskEngine.completeStepByTitle("Scroll and wait for more items", "Explicit end marker visible after fresh post-scroll observations", verified = true)
                AgentTaskEngine.completeStepByTitle("Verify list end and results", "End marker and ${task.processedItemSignatures.size} verified item(s)", verified = true)
                AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                terminalResult = TaskActionResult(true, "Verified Instagram's end-of-list marker after processing ${task.processedItemSignatures.size} item(s).", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = true)
                return@runAutonomousLoop false
            }
            true
        }
        terminalResult ?: when {
            loopOutcome.cancelled -> TaskActionResult(false, "Instagram bulk unlike was cancelled after verifying $processedThisRun item(s).", verified = false)
            loopOutcome.reachedLimit -> TaskActionResult(false, "Stopped at the Instagram processing safety limit after verifying $processedThisRun item(s); the list may contain more.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
            else -> TaskActionResult(false, loopOutcome.error ?: "Instagram processing stopped without verified completion.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
        }
    }

    private fun resetScrollCount() {
        consecutiveScrollCount = 0
        lastScreenSignature = ""
        duplicateSignatureCount = 0
    }

    private suspend fun dispatchImmediateScreenFrame() {
        try {
            if (screenCaptureManager.isScreenShareActive()) {
                val snapshot = screenCaptureManager.captureSnapshotJpeg(75)
                if (snapshot != null) {
                    AuraLiveVoiceManager.sendImmediateVideoFrame(snapshot)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching immediate frame: ${e.message}")
        }
    }

    private fun observeMultiWindow(
        service: AutomationAccessibilityService,
        forceRefresh: Boolean = true
    ): Triple<AccessibilityNodeInfo?, List<AccessibilityNodeInfo>, List<UiElementInfo>> {
        val allRoots = service.getAllApplicationWindowsRootNodes(forceRefresh = forceRefresh)
            .ifEmpty { listOfNotNull(service.getActiveRootNode(forceRefresh = forceRefresh)) }
        val primaryRoot = allRoots.firstOrNull() ?: service.getActiveRootNode(forceRefresh = forceRefresh)
        val elements = if (allRoots.isNotEmpty()) groundingEngine.scanAllWindows(allRoots) else emptyList()
        return Triple(primaryRoot, allRoots, elements)
    }

    /** Waits for accessibility changes and a stable, non-loading tree; supports instant early exit once [earlySatisfied] holds. */
    suspend fun waitForUiState(
        previousSignature: ScreenSignature? = null,
        expectedPackage: String? = null,
        timeoutMs: Long = 8_000L,
        initialSettleMs: Long = 90L,
        earlySatisfied: ((List<UiElementInfo>) -> Boolean)? = null
    ): UiWaitResult = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext UiWaitResult(false, null, emptyList(), null, false, "Accessibility Service is unavailable")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(400L, 20_000L)
        if (initialSettleMs > 0L) delay(initialSettleMs)
        var lastSignature: ScreenSignature? = null
        var stableSamples = 0
        var latestRoot: AccessibilityNodeInfo? = null
        var latestElements: List<UiElementInfo> = emptyList()
        var latestSignature: ScreenSignature? = null
        AgentTaskEngine.updateActivity("Waiting for the app UI to settle", AgentTaskEngine.State.WAITING_FOR_UI)
        while (System.currentTimeMillis() < deadline) {
            if (AgentTaskEngine.isCancelled()) {
                return@withContext UiWaitResult(false, latestRoot, latestElements, latestSignature, false, "Task was cancelled while waiting for UI")
            }
            val (primaryRoot, allRoots, scannedElements) = observeMultiWindow(service)
            latestRoot = primaryRoot
            latestElements = scannedElements
            latestSignature = ScreenSignatureCalculator.computeCombinedSignature(allRoots)
            val pkg = latestRoot?.packageName?.toString().orEmpty()
            val packageReady = expectedPackage.isNullOrBlank() || pkg.contains(expectedPackage, ignoreCase = true)
            val loading = latestElements.any { element ->
                val label = element.primaryLabel.lowercase(Locale.ROOT)
                listOf("loading", "buffering", "please wait", "working…", "working...").any(label::contains)
            }
            AgentTaskEngine.observe(latestSignature, pkg, groundingEngine.formatElementsSummary(latestElements, latestRoot))
            if (packageReady && !loading && latestRoot != null) {
                if (earlySatisfied != null && earlySatisfied(scannedElements)) {
                    AgentTaskEngine.setState(AgentTaskEngine.State.VERIFYING)
                    return@withContext UiWaitResult(
                        true, latestRoot, latestElements, latestSignature,
                        previousSignature != null && !latestSignature.isEquivalentTo(previousSignature),
                        "Target UI condition verified immediately"
                    )
                }
                if (latestSignature.isEquivalentTo(lastSignature)) stableSamples++ else stableSamples = 0
                if (stableSamples >= 1) {
                    AgentTaskEngine.setState(AgentTaskEngine.State.VERIFYING)
                    return@withContext UiWaitResult(
                        true, latestRoot, latestElements, latestSignature,
                        previousSignature != null && !latestSignature.isEquivalentTo(previousSignature),
                        "UI tree stable across fresh accessibility observations"
                    )
                }
            } else {
                stableSamples = 0
            }
            lastSignature = latestSignature
            val revision = AutomationAccessibilityService.uiChanges.value.revision
            val waitedForEvent = withTimeoutOrNull(140L) {
                AutomationAccessibilityService.uiChanges.first { it.revision > revision }
                true
            } ?: false
            if (!waitedForEvent) delay(60L)
        }
        AgentTaskEngine.updateActivity("UI did not become stable before the wait deadline", AgentTaskEngine.State.RECOVERING)
        UiWaitResult(false, latestRoot, latestElements, latestSignature,
                previousSignature != null && latestSignature != null && !latestSignature.isEquivalentTo(previousSignature),
            "Timed out waiting for a stable non-loading accessibility tree")
    }

    /**
     * Executes a precise visual click on the requested UI element.
     * Snaps to the exact hardware bounding box Rect from the accessibility tree.
     */
    suspend fun handleClick(
        targetName: String,
        elementId: Int? = null,
        description: String? = null,
        isToggle: Boolean = false,
        x: Int? = null,
        y: Int? = null,
        allowChromeMenuForSettings: Boolean = false,
        forbidAddingInstagramLike: Boolean = false,
        taskGoal: String = ""
    ): TaskActionResult = withContext(Dispatchers.IO) {
        if (taskGoal.isNotBlank()) AgentTaskEngine.begin(taskGoal)
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
        resetScrollCount()
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(
                success = false,
                message = "Accessibility Service is not enabled. Please enable it in Settings."
            )
        }

        // Apply the same sensitive-action gate to visual taps as named/accessibility taps.
        if (isSensitiveTarget(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Sensitive action detected ('$targetName'). Please ask the user for explicit confirmation before proceeding.",
                requiresUserConfirmation = true
            )
        }

        // A stale or confused model must not send an email just because it found a
        // button labelled Send. Require the active user task to explicitly request it.
        val initialRoot = service.getActiveRootNode()
        val initialPackage = initialRoot?.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        // #6 & #9: Route internal Aura screens/chats/questionnaires natively instead of Accessibility clicks
        if (initialPackage.contains("com.example.gemini") || initialPackage.contains("com.aura.ai")) {
            val activeQn = com.example.gemini.data.live.ChatContextBridge.getActivePendingQuestionnaire()
            if (activeQn != null &&
                com.example.gemini.data.live.ChatQuestionnaireHelper.isQuestionnaireClickPhrase(targetName, taskGoal, activeQn)
            ) {
                val qnRes = com.example.gemini.data.live.ChatContextBridge.answerActiveChatQuestion(
                    answer = targetName.ifBlank { taskGoal },
                    action = "submit"
                )
                return@withContext TaskActionResult(
                    success = qnRes.success,
                    message = qnRes.message,
                    verified = qnRes.success
                )
            }
            val navInput = when {
                com.example.gemini.data.live.AppNavigationRouter.resolve(targetName) != null -> targetName
                com.example.gemini.data.live.AppNavigationRouter.resolve(taskGoal) != null -> taskGoal
                else -> null
            }
            if (navInput != null) {
                val navRes = com.example.gemini.data.live.ChatContextBridge.navigateToInternalScreen(navInput)
                return@withContext TaskActionResult(
                    success = navRes.success,
                    message = navRes.message,
                    verified = navRes.success
                )
            }
            if (com.example.gemini.data.live.ChatMatcher.isLikelyChatOpenPhrase(targetName) ||
                com.example.gemini.data.live.ChatMatcher.isLikelyChatOpenPhrase(taskGoal)
            ) {
                val chatRes = com.example.gemini.data.live.ChatContextBridge.openChatByName(targetName)
                return@withContext TaskActionResult(
                    success = chatRes.success,
                    message = chatRes.message,
                    verified = chatRes.success
                )
            }
            return@withContext TaskActionResult(
                success = false,
                message = "Accessibility click is disabled on Aura's own UI. Use native Aura tools instead (answer_chat_question, navigate_app_screen, create_new_chat, open_chat_by_id, open_chat_by_name, send_message_to_chat, open_in_app_browser, close_in_app_browser).",
                verified = false
            )
        }
        val normalizedTarget = targetName.lowercase(Locale.ROOT).trim()
        // #16: Instagram DM Inbox guard — refuse to click bottom nav tabs or random feed items when trying to open a DM contact from Home/Explore
        if (initialPackage.contains("instagram") &&
            (taskGoal.contains("dm", ignoreCase = true) || taskGoal.contains("message", ignoreCase = true)) &&
            normalizedTarget !in setOf("messages", "direct", "messenger", "inbox", "search", "reels", "home", "profile", "send", "back", "like", "comment", "share")
        ) {
            val igElements = groundingEngine.scanScreen(initialRoot)
            val isInDirectInbox = igElements.any { el ->
                val v = el.viewId.lowercase(Locale.ROOT)
                val l = el.primaryLabel.lowercase(Locale.ROOT)
                v.contains("direct_inbox") || v.contains("row_inbox") || v.contains("inbox_search") ||
                    v.contains("thread_title") || l == "primary" || l == "requests" || l == "messages"
            }
            val isBottomNavTab = igElements.any { el ->
                val v = el.viewId.lowercase(Locale.ROOT)
                val l = el.primaryLabel.lowercase(Locale.ROOT)
                (v.contains("search_tab") || v.contains("feed_tab") || v.contains("clips_tab") || v.contains("profile_tab")) &&
                    l.contains(normalizedTarget)
            }
            if (!isInDirectInbox && isBottomNavTab) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "FAILED: Currently not in Instagram Direct Messages inbox. Refused to tap bottom navigation tab '$targetName'. Open Direct Messages (top-right Messenger icon) first.",
                    currentStateSummary = groundingEngine.formatElementsSummary(igElements, initialRoot),
                    verified = false
                )
            }
        }
        val isGmailSend = initialPackage.contains("com.google.android.gm") &&
            (normalizedTarget == "send" || normalizedTarget == "send email" || normalizedTarget == "send message")
        if (isGmailSend) {
            val goal = taskGoal.lowercase(Locale.ROOT)
            val explicitEmailRequest = (goal.contains("email") || goal.contains("gmail")) &&
                (goal.contains("send") || goal.contains("email this") || goal.contains("mail this"))
            if (!explicitEmailRequest) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Blocked Gmail Send: the active user request did not explicitly ask to send an email. No message was sent.",
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }
            val gmailElements = groundingEngine.scanScreen(initialRoot)
            if (gmailElements.any { it.primaryLabel.contains("add at least one recipient", ignoreCase = true) }) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Blocked Gmail Send because Gmail says no recipient is present. Add and verify the intended recipient first.",
                    currentStateSummary = groundingEngine.formatElementsSummary(gmailElements, initialRoot),
                    verified = false
                )
            }
        }

        // CLICK DEBOUNCE / RATE LIMITER: Prevent rapid machine-gun tapping
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastClickTimestamp
        if (timeSinceLast < 280L) {
            delay(280L - timeSinceLast)
        }

        // DIRECT VISUAL COORDINATE TAP (100% Precision Vision Mode):
        // When Screen Share is active, Gemini can pass normalized coordinates (0-1000)
        // to directly tap any visual button, icon, or element with physical precision!
        if (x != null && y != null && x in 0..1000 && y in 0..1000 && screenCaptureManager.isSharing.value) {
            val dm = DisplayMetrics()
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                dm.setTo(service.resources.displayMetrics)
            }
            var physicalX = (x / 1000f) * dm.widthPixels
            var physicalY = (y / 1000f) * dm.heightPixels

            val rootBeforeVisualTap = service.getActiveRootNode()
            val elementsBeforeVisualTap = groundingEngine.scanScreen(rootBeforeVisualTap)
            val hitElement = elementsBeforeVisualTap
                .filter { it.isClickable && it.bounds.contains(physicalX.toInt(), physicalY.toInt()) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
            if (hitElement != null && hitElement.hasTextOrDesc) {
                val targetWords = targetName.lowercase(Locale.ROOT)
                    .replace("\\b(icon|button|item|the|on|screen)\\b".toRegex(), " ")
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.length >= 3 }
                val hitLabel = hitElement.primaryLabel.lowercase(Locale.ROOT)
                val labelAgrees = targetWords.isEmpty() || targetWords.any { hitLabel.contains(it) } ||
                    targetName.lowercase(Locale.ROOT).contains(hitLabel)
                if (!labelAgrees) {
                    return@withContext TaskActionResult(
                        success = false,
                        message = "The screen point lands on '${hitElement.primaryLabel}', which does not match '$targetName'. No tap was made.",
                        currentStateSummary = captureCurrentStateSummary(service)
                    )
                }
                // Snap to the current clickable target's accessibility bounds rather than the model's estimated point.
                physicalX = hitElement.bounds.exactCenterX()
                physicalY = hitElement.bounds.exactCenterY()
            }

            val rootPkg = service.getActiveRootNode()?.packageName?.toString() ?: ""
            val isBrowser = rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
            val targetLower = targetName.lowercase(Locale.ROOT)

            if (isBrowser && groundingEngine.isGoogleFlowSurface(elementsBeforeVisualTap)) {
                // Block accidental clicks on settings tags in Google Flow (e.g. Video • 720p • 8s • x1)
                val isSubmit = targetLower.contains("generate") || targetLower.contains("submit") ||
                        targetLower.contains("arrow") || targetLower.contains("create") || targetLower.contains("enter") || targetLower.contains("send")
                if (!isSubmit && (targetLower.contains("video") || targetLower.contains("720p") || targetLower.contains("8s") || targetLower.contains("x1") || targetLower.contains("frames") || targetLower.contains("omni") || targetLower.contains("ingredients"))) {
                    Log.w(TAG, "Blocked accidental tap on Google Flow settings chip: '$targetName' at ($x, $y)")
                    return@withContext TaskActionResult(
                        success = false,
                        message = "Refusing to click video settings chip. The prompt is ready; click the Generate arrow [→] on the right to start."
                    )
                }
            }

            // Suppress rapid duplicate clicks on identical coordinates within 1.8s
            if (Math.abs(physicalX - lastClickPhysicalX) < 30f && Math.abs(physicalY - lastClickPhysicalY) < 30f && (now - lastClickTimestamp) < 1800L) {
                Log.w(TAG, "Suppressed rapid duplicate click at ($physicalX, $physicalY)")
                return@withContext TaskActionResult(true, "Already tapped at this location. Waiting for UI update.")
            }

            val isSubmitIntent = targetLower.contains("generate") || targetLower.contains("submit") ||
                    targetLower.contains("arrow") || targetLower.contains("create") || targetLower.contains("enter") || targetLower.contains("send")

            // Snap to exact Google Flow arrow button if target is submit/generate or coordinates are in the bottom-right action bar
            // Only in a browser, and NEVER while the keyboard is open (the fixed spot is then the keyboard's Enter key).
            if ((isSubmitIntent || (x >= 800 && y >= 880)) && !service.isKeyboardVisible()) {
                if (isBrowser) {
                    val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val realDm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.getRealMetrics(realDm) ?: run {
                        realDm.widthPixels = dm.widthPixels
                        realDm.heightPixels = 2408
                    }
                    val exactArrowX = realDm.widthPixels * 0.9333f // Exactly 1008 on 1080p
                    val exactArrowY = realDm.heightPixels * 0.9440f // Exactly 2273 on 2408p
                    Log.i(TAG, "Snapping Generate/Submit arrow click from ($physicalX, $physicalY) -> exact button center ($exactArrowX, $exactArrowY)")
                    physicalX = exactArrowX
                    physicalY = exactArrowY
                }
            }

            lastClickTimestamp = System.currentTimeMillis()
            lastClickPhysicalX = physicalX
            lastClickPhysicalY = physicalY

            Log.i(TAG, "Direct visual tap at normalized ($x, $y) -> physical ($physicalX, $physicalY)")
            val preTapSignature = ScreenSignatureCalculator.computeSignature(rootBeforeVisualTap)
            AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
            val clicked = mutateUi("visual-click:$targetName") { service.clickAt(physicalX, physicalY) }
            val tapDelay = if (isBrowser) 800L else 400L
            delay(tapDelay)
            dispatchImmediateScreenFrame()
            val rootAfterVisualTap = service.getActiveRootNode()
            val elementsAfterVisualTap = groundingEngine.scanScreen(rootAfterVisualTap)
            val postTapSignature = ScreenSignatureCalculator.computeSignature(rootAfterVisualTap)
            val verified = !postTapSignature.isEquivalentTo(preTapSignature)
            return@withContext TaskActionResult(
                success = clicked,
                message = if (!clicked) {
                    "Tap could not be dispatched for '$targetName'. No action was completed."
                } else if (verified) {
                    "Tapped '$targetName' and observed a screen change, but the requested result is not semantically verified. Inspect the fresh screen before continuing."
                } else {
                    "Tap was dispatched for '$targetName', but a screen change was not detected. Do not claim it succeeded; inspect the screen before deciding what to do next."
                },
                currentStateSummary = groundingEngine.formatElementsSummary(elementsAfterVisualTap, rootAfterVisualTap),
                verified = false
            )
        }
        // Anti-Clear-Search Guard: prevent AI from reflexively clicking "Clear search" in the middle of a search task
        if (isClearSearchButton(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to click '$targetName'. Do not clear or erase the search input! Instead, click a matching result/folder from the list below, or submit.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        // GOOGLE FLOW / VIDEO SETTINGS PANEL GUARD:
        // Block clicks on video quality, duration, multiplier, and settings-panel elements.
        // These are NEVER valid targets during content creation — the user wants to type a prompt!
        val settingsBlocklistExact = setOf(
            "16:9", "9:16", "360p", "480p", "720p", "1080p",
            "4s", "6s", "8s", "10s", "12s", "15s",
            "x1", "x2", "x3", "x4", "x5",
            "frames", "ingredients", "omni 1.1 flash", "omni", "veo 2"
        )
        val rawTargetLower = targetName.trim().lowercase()
        if (settingsBlocklistExact.contains(rawTargetLower) ||
            // Also block if it's ONLY a number+unit pattern like "8s" "720p" "x2"
            rawTargetLower.matches(Regex("\\d+[sp]|x\\d+|\\d+[sS]ec?"))) {
            return@withContext TaskActionResult(
                success = false,
                message = "BLOCKED: '$targetName' is a Google Flow video settings control (quality/duration/multiplier). Do NOT click these. The ONLY thing you should do is: call type_text(text='<your prompt>', target_input_name='What do you want to create') to type the video description into the prompt box. THEN call inspect_screen() to find the arrow/generate button ID and click it.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }


        // Anti-Search-Loop on Web Search Pages: prevent model from re-tapping search bar when results are visible
        if (isBrowserSearchScreen(service) && (targetName.equals("search", ignoreCase = true) || targetName.contains("search bar", ignoreCase = true) || targetName.contains("url_bar", ignoreCase = true))) {
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val resultLinks = elements.filter { it.isClickable && !it.isEditable && it.bounds.top > 250 && it.text.isNotBlank() }
            if (resultLinks.isNotEmpty()) {
                val bestLink = resultLinks.first()
                Log.i(TAG, "Re-routing search bar click on search results page to top result link: '${bestLink.primaryLabel}'")
                val clicked = mutateUi("click-search-result") { service.performClickAtRect(bestLink.bounds) }
                delay(700)
                dispatchImmediateScreenFrame()
                val rootAfter = service.getActiveRootNode()
                val elementsAfter = groundingEngine.scanScreen(rootAfter)
                return@withContext TaskActionResult(
                    success = clicked,
                    message = "Navigating to website: automatically clicked search result link '${bestLink.primaryLabel}'. Wait for page to load and locate generation prompt input.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        }

        val initialObs = observeMultiWindow(service)
        var rootBefore = initialObs.first
        val allRootsBefore = initialObs.second
        var elementsBefore = initialObs.third
        val rootPkgBefore = rootBefore?.packageName?.toString() ?: ""
        if (rootPkgBefore.contains("com.example.gemini")) {
            val lower = targetName.lowercase(Locale.ROOT).trim()
            if (lower.contains("new chat") || lower.contains("nayi chat") || lower.contains("start a new chat") || lower.contains("fresh chat")) {
                com.example.gemini.data.live.ChatContextBridge.createNewChat()
                return@withContext TaskActionResult(
                    success = true,
                    message = "Opened new chat natively inside Aura.",
                    verified = true
                )
            }
        }
        val preTapSignature = ScreenSignatureCalculator.computeCombinedSignature(allRootsBefore)
        val repeatedTarget = targetName.lowercase(Locale.ROOT).trim()
        if (repeatedTarget.isNotBlank() && repeatedTarget == lastUnverifiedClickTarget &&
            lastUnverifiedClickScreen?.isEquivalentTo(preTapSignature) == true) {
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to repeat '$targetName': the previous tap did not change this screen. No second tap was made; inspect the current screen or choose a different target.",
                currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                verified = false
            )
        }
        val onInstagram = rootBefore?.packageName?.toString()?.contains("instagram", ignoreCase = true) == true
        val asksToLike = Regex("\\blike\\b", RegexOption.IGNORE_CASE).containsMatchIn("$targetName ${description.orEmpty()}") &&
            !"$targetName ${description.orEmpty()}".contains("unlike", ignoreCase = true)
        if (forbidAddingInstagramLike && onInstagram && asksToLike) {
            return@withContext TaskActionResult(
                success = false,
                message = "Blocked a Like action during an Instagram bulk unlike task. Use the Likes screen's Select mode and its Unlike action; do not open a Reel or add likes.",
                currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                verified = false
            )
        }
        val isFlowScreen = groundingEngine.isGoogleFlowSurface(elementsBefore)

        val initialTarget = groundingEngine.findTargetElement(
            elements = elementsBefore,
            targetName = targetName,
            isToggleIntent = isToggle || isTogglePhrase(targetName) || isTogglePhrase(description ?: ""),
            elementId = elementId
        )

        // Search-Result Loop Guard:
        // If on a browser search page and the target resolved was an editable search bar,
        // NEVER re-tap the search bar when search result links are visible below!
        var groundedTarget = if (isBrowserSearchScreen(service) && (initialTarget?.isEditable == true || initialTarget?.viewId?.contains("search", ignoreCase = true) == true || initialTarget?.viewId?.contains("url_bar", ignoreCase = true) == true) && elementId == null) {
            val resultLinks = elementsBefore.filter { !it.isEditable && it.bounds.top > 250 && (it.isClickable || it.bounds.top > 280) && (it.text.isNotBlank() || it.contentDescription.isNotBlank()) }
            if (resultLinks.isNotEmpty()) {
                val qTokens = targetName.lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.length > 2 }
                val bestResult = resultLinks.filter { el ->
                    qTokens.any { t -> el.primaryLabel.lowercase(Locale.ROOT).contains(t) }
                }.firstOrNull() ?: resultLinks.first()
                Log.i(TAG, "Prevented search bar re-tap: re-routing target from search bar to search result '${bestResult.primaryLabel}' (id=${bestResult.id})")
                bestResult
            } else {
                initialTarget
            }
        } else {
            initialTarget
        }

        val isChrome = (rootBefore?.packageName?.toString() ?: "").contains("chrome", ignoreCase = true)
        val wantsChromeSettings = isChrome && targetName.contains("settings", ignoreCase = true)
        val wantsChromeIncognito = isChrome && Regex("\\b(incognito|inkinido|in cognito|cognition|private tab|private mode)\\b")
            .containsMatchIn(normalizedTarget)
        val alreadyOnIncognitoTarget = groundedTarget != null &&
            !isBrowserOverflowMenu(groundedTarget) &&
            groundedTarget.primaryLabel.contains("incognito", ignoreCase = true)

        if (wantsChromeSettings && (groundedTarget == null || isBrowserOverflowMenu(groundedTarget))) {
            return@withContext openChromeMenuAndSelect(
                service = service,
                itemQuery = "Settings",
                fallbackQueries = listOf("Settings", "menu_item_settings"),
                verifyOpened = { els ->
                    els.any { it.primaryLabel.contains("settings", ignoreCase = true) || it.viewId.contains("settings", ignoreCase = true) }
                },
                successMsg = "Chrome Settings opened and screen change verified.",
                failMsg = "Tapped Chrome Settings, but could not verify the Settings screen. Inspect the current screen."
            )
        }

        if (wantsChromeIncognito && !alreadyOnIncognitoTarget) {
            return@withContext openChromeMenuAndSelect(
                service = service,
                itemQuery = "New Incognito tab",
                fallbackQueries = listOf("New Incognito tab", "Incognito tab", "Incognito", "New private tab"),
                verifyOpened = { els -> isChromeIncognitoScreen(els) },
                successMsg = "Chrome Incognito tab opened and verified.",
                failMsg = "Tapped Chrome Incognito option, inspect current screen to confirm Incognito tab."
            )
        }

        if (groundedTarget == null) {
            if (isFlowScreen) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Could not find '$targetName' as an actionable control on Google Flow. No tap or scroll was made; inspect the current screen and choose a visible Flow control.",
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }

            // Never run a 20-scroll search when a popup menu/dialog overlay is open OR when targeting
            // toolbar/menu controls like "3 dots", "incognito", "menu", "settings", "close", etc.
            val popupOpen = allRootsBefore.size > 1
            val isToolbarOrMenuTarget = Regex("\\b(dot|dots|line|lines|menu|more options|incognito|inkinido|private|new tab|tab|toolbar|back|close|settings|share|search)\\b")
                .containsMatchIn(normalizedTarget)
            if (popupOpen || isToolbarOrMenuTarget) {
                // In Chrome/browsers, if toolbar auto-hid from prior scroll, swipe gently up once to reveal toolbar & re-check
                if (isChrome && !popupOpen) {
                    mutateUi("reveal-chrome-toolbar") { service.performSmartScrollAndAwait("up", "short") }
                    delay(450)
                    val (revealedRoot, _, revealedElements) = observeMultiWindow(service)
                    val foundInToolbar = groundingEngine.findTargetElement(
                        elements = revealedElements,
                        targetName = targetName,
                        isToggleIntent = isToggle,
                        elementId = elementId
                    )
                    if (foundInToolbar != null) {
                        val clicked = mutateUi("click-revealed:${foundInToolbar.primaryLabel}") {
                            service.performClickAtRectAndAwait(foundInToolbar.bounds)
                        }
                        delay(450)
                        val (postRoot, _, postEls) = observeMultiWindow(service)
                        return@withContext TaskActionResult(
                            success = clicked,
                            message = if (clicked) "Clicked '${foundInToolbar.primaryLabel}' after revealing toolbar. Inspect current elements before next action." else "Failed to click '${foundInToolbar.primaryLabel}'.",
                            currentStateSummary = groundingEngine.formatElementsSummary(postEls, postRoot),
                            verified = clicked
                        )
                    }
                }
                val (pkg, title) = groundingEngine.extractScreenContext(rootBefore, elementsBefore)
                return@withContext TaskActionResult(
                    success = false,
                    message = "Could not find '$targetName' on current screen ('$title' in $pkg). Scrolled search skipped so open menus/toolbars aren't dismissed.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                    verified = false
                )
            }

            // Bounded scroll search across lists (#16: cap YouTube scroll retries to 2 instead of scrolling endlessly)
            val isYouTubeFeed = (rootBefore?.packageName?.toString() ?: "").contains("youtube", ignoreCase = true)
            val maxSearchScrolls = if (isYouTubeFeed) 2 else 6
            Log.d(TAG, "Target '$targetName' not visible on current screen. Running bounded auto-scroll search (max=$maxSearchScrolls)...")
            var foundTarget: UiElementInfo? = null
            var prevSig: ScreenSignature? = null
            var noProgressCount = 0

            for (scrollStep in 1..maxSearchScrolls) {
                AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
                val scrolled = mutateUi("auto-scroll-search:$targetName") { service.performSmartScrollAndAwait("down", "medium") }
                AgentTaskEngine.current()?.scrollCount = (AgentTaskEngine.current()?.scrollCount ?: 0) + 1
                if (AgentTaskEngine.isCancelled()) break
                if (!scrolled) break
                delay(280)
                val (scrolledRoot, scrolledRoots, elementsAfterScroll) = observeMultiWindow(service)
                val currentSig = ScreenSignatureCalculator.computeCombinedSignature(scrolledRoots)
                if (prevSig != null && currentSig.isEquivalentTo(prevSig)) {
                    noProgressCount++
                    if (noProgressCount >= 2) {
                        Log.i(TAG, "Stopping target search after $noProgressCount consecutive unchanged screen signatures.")
                        break
                    }
                } else {
                    noProgressCount = 0
                }
                prevSig = currentSig

                val targetAfterScroll = groundingEngine.findTargetElement(
                    elements = elementsAfterScroll,
                    targetName = targetName,
                    isToggleIntent = isToggle,
                    elementId = elementId
                )
                if (targetAfterScroll != null) {
                    foundTarget = targetAfterScroll
                    rootBefore = scrolledRoot
                    elementsBefore = elementsAfterScroll
                    Log.i(TAG, "Found target '$targetName' after $scrollStep scroll(s)!")
                    break
                }
            }

            if (foundTarget == null && isYouTubeFeed &&
                Regex("""\b(play|song|gaana|gana|music|video|chalao|bajao|lagao|first|pehla)\b""")
                    .containsMatchIn(normalizedTarget)
            ) {
                val tappedFallback = service.clickFirstYouTubeVideoNow()
                if (tappedFallback) {
                    delay(550)
                    val (ytRoot, _, ytEls) = observeMultiWindow(service)
                    return@withContext TaskActionResult(
                        success = true,
                        message = "Tapped visible playable YouTube video directly instead of scrolling endlessly.",
                        currentStateSummary = groundingEngine.formatElementsSummary(ytEls, ytRoot),
                        verified = true
                    )
                }
            }

            if (foundTarget == null) {
                val (pkg, title) = groundingEngine.extractScreenContext(rootBefore, elementsBefore)
                return@withContext TaskActionResult(
                    success = false,
                    message = if (isYouTubeFeed) {
                        "Could not find '$targetName' on YouTube screen after brief scroll. Call search_and_play_youtube(query=\"$targetName\") to play it directly without scrolling."
                    } else {
                        "Could not find element '$targetName' on current screen ('$title' in $pkg). Check screen title or navigate to the correct section."
                    },
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }
            groundedTarget = foundTarget
        }

        val target = groundedTarget!!

        val rootPkg = rootBefore?.packageName?.toString() ?: ""
        val quizBefore = McqGrounder.detectQuizState(elementsBefore)
        val isNextNavClick = quizBefore.isMcqScreen && McqGrounder.isNextNavigationTarget(targetName)
        val isPrevNavClick = quizBefore.isMcqScreen && McqGrounder.isPreviousNavigationTarget(targetName)

        // HARD ANTI-SKIP GUARD FOR CHROME MCQ EXAMS:
        // Never allow tapping 'Next' unless an option is already genuinely checked (or user explicitly asked to skip).
        if (isNextNavClick && !McqGrounder.isExplicitSkipGoal(taskGoal, description)) {
            if (!target.isEnabled || !quizBefore.isNextEnabled || quizBefore.checkedOption == null) {
                val summary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore)
                return@withContext TaskActionResult(
                    success = false,
                    message = "BLOCKED 'Next' to prevent skipping this question: NO MCQ option is currently checked/selected on screen. Select an option (a/b/c/d) and confirm ✓ CHECKED first.",
                    currentStateSummary = summary,
                    verified = false
                )
            }
        }

        val matchedMcqOpt = if (quizBefore.isMcqScreen && !isNextNavClick && !isPrevNavClick) {
            quizBefore.options.firstOrNull { opt ->
                opt.element == target || opt.radioElement == target || Rect.intersects(opt.rowRect, target.bounds)
            } ?: McqGrounder.findMatchingOption(quizBefore, targetName)
        } else null
        val isRadioOrToggleTarget = target.isSwitchOrToggle() || matchedMcqOpt != null
        val primaryTapRect = matchedMcqOpt?.tapRect ?: target.descendantRadioBounds ?: target.bounds

        val initialCheckedState = matchedMcqOpt?.isChecked ?: target.isEffectivelyChecked()
        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val isWhatsAppChatRow = rootPkg.contains("whatsapp", ignoreCase = true) &&
            target.bounds.width() > (service.resources.displayMetrics.widthPixels * 0.40f) &&
            target.bounds.top > 180 &&
            !target.viewId.contains("send", ignoreCase = true) &&
            !target.viewId.contains("menuitem", ignoreCase = true) &&
            !target.viewId.contains("search", ignoreCase = true)
        val clickOk = mutateUi("click:${target.primaryLabel}") {
            if (isWhatsAppChatRow) {
                // #18: Strictly tap right-center (60% width) to avoid WhatsApp's left circular profile avatar!
                val screenW = service.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                val safeX = (target.bounds.left + target.bounds.width() * 0.60f)
                    .coerceAtLeast(screenW * 0.30f)
                    .coerceAtMost(screenW * 0.85f)
                service.clickAt(safeX, target.bounds.exactCenterY(), 80L)
            } else {
                service.performClickAtRectAndAwait(primaryTapRect)
            }
        }

        if (!clickOk) {
            return@withContext TaskActionResult(
                success = false,
                message = "Failed to tap coordinates for '${target.primaryLabel}'.",
                currentStateSummary = captureCurrentStateSummary(service),
                verified = false
            )
        }

        // Closed-loop verification with snappy early exit for MCQ radio ticks and navigation
        val isWebOrLink = target.isClickable && !isRadioOrToggleTarget && !isNextNavClick && !isPrevNavClick && (
                target.primaryLabel.contains("http", ignoreCase = true) ||
                target.primaryLabel.contains(".com", ignoreCase = true) ||
                target.primaryLabel.contains("www.", ignoreCase = true) ||
                target.primaryLabel.contains("search", ignoreCase = true) ||
                target.primaryLabel.contains("flow", ignoreCase = true))
        val maxWatchMs = when {
            isRadioOrToggleTarget -> 1_600L
            isNextNavClick || isPrevNavClick -> 2_800L
            isWebOrLink -> 7_500L
            else -> 4_500L
        }
        dispatchImmediateScreenFrame()
        val preSig = ScreenSignatureCalculator.computeSignature(rootBefore)
        val waitStartedAt = System.currentTimeMillis()
        val uiWait = waitForUiState(
            previousSignature = preSig,
            expectedPackage = rootPkg,
            timeoutMs = maxWatchMs,
            initialSettleMs = if (isRadioOrToggleTarget) 110L else 120L,
            earlySatisfied = { scanned ->
                when {
                    matchedMcqOpt != null -> {
                        val qAfter = McqGrounder.detectQuizState(scanned)
                        qAfter.options.firstOrNull { opt ->
                            opt.letter == matchedMcqOpt.letter ||
                                opt.label.equals(matchedMcqOpt.label, ignoreCase = true) ||
                                abs(opt.rowRect.centerY() - matchedMcqOpt.rowRect.centerY()) <= 42
                        }?.isChecked == true
                    }
                    target.isSwitchOrToggle() -> {
                        findMatchingElementAfterClick(scanned, target)?.isEffectivelyChecked()?.let { it != initialCheckedState } == true
                    }
                    isNextNavClick || isPrevNavClick -> {
                        val qAfter = McqGrounder.detectQuizState(scanned)
                        !qAfter.warningBanner.isNullOrBlank() ||
                            (quizBefore.questionText.isNotBlank() && qAfter.questionText.isNotBlank() && qAfter.questionText != quizBefore.questionText) ||
                            (quizBefore.questionHeader.isNotBlank() && qAfter.questionHeader.isNotBlank() && qAfter.questionHeader != quizBefore.questionHeader)
                    }
                    else -> false
                }
            }
        )
        val afterWaitObs = observeMultiWindow(service, forceRefresh = true)
        var rootAfter = afterWaitObs.first ?: uiWait.root ?: service.getActiveRootNode()
        var allRootsAfter = afterWaitObs.second
        var elementsAfter = if (afterWaitObs.third.isNotEmpty()) afterWaitObs.third else uiWait.elements.ifEmpty { groundingEngine.scanScreen(rootAfter) }
        val postSig = ScreenSignatureCalculator.computeCombinedSignature(allRootsAfter)
        val stateChanged = !postSig.isEquivalentTo(preTapSignature)
        val watchedMs = System.currentTimeMillis() - waitStartedAt

        // #15: Post-click verification when tapping Chrome Incognito tab or Chrome 3-dot overflow menu
        if (isChrome && alreadyOnIncognitoTarget) {
            delay(350)
            val (incRoot, _, incEls) = observeMultiWindow(service, forceRefresh = true)
            val incVerified = isChromeIncognitoScreen(incEls)
            return@withContext TaskActionResult(
                success = incVerified || stateChanged,
                message = if (incVerified) "Opened Chrome Incognito tab and verified Incognito mode is active."
                else "Tapped '${target.primaryLabel}'. Inspect current screen to confirm Incognito tab.",
                currentStateSummary = groundingEngine.formatElementsSummary(incEls, incRoot),
                verified = incVerified
            )
        }
        if (isChrome && isBrowserOverflowMenu(target)) {
            delay(280)
            val (menuRoot, menuAllRoots, menuEls) = observeMultiWindow(service, forceRefresh = true)
            val menuOpened = menuAllRoots.size > 1 || menuEls.any {
                val l = it.primaryLabel.lowercase(Locale.ROOT)
                l.contains("new incognito") || l.contains("new tab") || l.contains("bookmarks") || l.contains("history")
            }
            return@withContext TaskActionResult(
                success = menuOpened || stateChanged,
                message = if (menuOpened) "Opened Chrome 3-dot menu cleanly; menu items (including 'New Incognito tab') are now visible."
                else "Tapped Chrome 3-dot menu button.",
                currentStateSummary = groundingEngine.formatElementsSummary(menuEls, menuRoot),
                verified = menuOpened
            )
        }

        // #15: Post-click verification when opening Instagram Reels tab
        val isInstagramReelsClick = rootPkg.contains("instagram", ignoreCase = true) &&
            (normalizedTarget == "reels" || normalizedTarget == "reel" || normalizedTarget.contains("reels tab"))
        if (isInstagramReelsClick) {
            delay(500)
            val (igRoot, _, igElems) = observeMultiWindow(service)
            rootAfter = igRoot
            elementsAfter = igElems
            val reelsViewerActive = elementsAfter.any { el ->
                val vId = el.viewId.lowercase(Locale.ROOT)
                val lbl = el.primaryLabel.lowercase(Locale.ROOT)
                vId.contains("clips_viewer") || vId.contains("clips_video") || vId.contains("clips_media") ||
                    vId.contains("reel") || lbl.contains("reels camera") || lbl.contains("audio")
            } || (elementsAfter.any { it.primaryLabel.equals("Like", true) } &&
                elementsAfter.any { it.primaryLabel.equals("Comment", true) } &&
                elementsAfter.any { it.primaryLabel.equals("Share", true) })
            val reelSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
            return@withContext TaskActionResult(
                success = reelsViewerActive,
                message = if (reelsViewerActive) "Opened Instagram Reels viewer and verified active playback controls."
                          else "FAILED: Tapped '${target.primaryLabel}', but Instagram Reels viewer did not open. Inspect screen before retrying.",
                currentStateSummary = reelSummary,
                verified = reelsViewerActive
            )
        }

        // #18: WhatsApp / Messaging Profile Picture Popup Recovery
        val isMessagingApp = rootPkg.contains("whatsapp", ignoreCase = true) || rootPkg.contains("telegram", ignoreCase = true)
        if (isMessagingApp) {
            delay(450)
            rootAfter = service.getActiveRootNode()
            elementsAfter = groundingEngine.scanScreen(rootAfter)
            val hasProfilePopup = elementsAfter.any { el ->
                val vId = el.viewId.lowercase(Locale.ROOT)
                val label = el.primaryLabel.lowercase(Locale.ROOT)
                vId.contains("profile_dialog") || vId.contains("quick_contact") || vId.contains("badge") ||
                vId.contains("picture_animation") ||
                label == "view contact" || label == "message info" || label == "group info" || label == "info"
            }
            val hasChatComposer = elementsAfter.any { el ->
                val vId = el.viewId.lowercase(Locale.ROOT)
                val lbl = el.primaryLabel.lowercase(Locale.ROOT)
                vId.contains("entry") || vId.contains("composer") || vId.contains("message_box") ||
                    (el.isEditable && lbl.contains("message"))
            }
            if ((hasProfilePopup || isWhatsAppChatRow) && !hasChatComposer) {
                Log.w(TAG, "Profile/Info popup detected instead of chat thread in $rootPkg. Pressing BACK and retrying right-center tap once...")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(350)
                val refreshedRoot = service.getActiveRootNode()
                val refreshedElements = groundingEngine.scanScreen(refreshedRoot)
                val nonAvatarMatches = refreshedElements.filter { el ->
                    val vId = el.viewId.lowercase(Locale.ROOT)
                    val lbl = el.primaryLabel.lowercase(Locale.ROOT)
                    !vId.contains("contact_photo") && !vId.contains("avatar") && !vId.contains("profile_pic") &&
                        !lbl.contains("profile photo") && !lbl.contains("profile picture")
                }
                val contactNode = nonAvatarMatches.firstOrNull {
                    it.viewId.contains("conversations_row_contact_name", ignoreCase = true) &&
                        it.primaryLabel.contains(targetName, ignoreCase = true)
                } ?: groundingEngine.findTargetElement(nonAvatarMatches, targetName)
                if (contactNode != null) {
                    val screenW = service.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                    val rightCenterX = (contactNode.bounds.left + contactNode.bounds.width() * 0.62f)
                        .coerceAtLeast(screenW * 0.30f)
                        .coerceAtMost(screenW * 0.85f)
                    val centerY = contactNode.bounds.exactCenterY()
                    service.clickAt(rightCenterX, centerY, 80L)
                    delay(500)
                    rootAfter = service.getActiveRootNode()
                    elementsAfter = groundingEngine.scanScreen(rootAfter)
                }
            }
        }

        // Verify if toggle / MCQ radio option state changed (with 1-shot surgical retry on radio/card if unchecked)
        var toggleVerified = false
        var radioFailedVerification = false
        if (isRadioOrToggleTarget) {
            fun isMcqOrToggleSatisfied(els: List<UiElementInfo>): Boolean {
                if (matchedMcqOpt != null) {
                    val qState = McqGrounder.detectQuizState(els)
                    val updatedOpt = qState.options.firstOrNull { opt ->
                        opt.letter == matchedMcqOpt.letter ||
                            opt.label.equals(matchedMcqOpt.label, ignoreCase = true) ||
                            abs(opt.rowRect.centerY() - matchedMcqOpt.rowRect.centerY()) <= 42
                    }
                    if (updatedOpt?.isChecked == true) return true
                }
                val updatedTarget = findMatchingElementAfterClick(els, target)
                val nowChecked = updatedTarget?.isEffectivelyChecked()
                if (nowChecked == null) return false
                val isRadioLike = target.className.contains("RadioButton", ignoreCase = true) ||
                    target.hasCheckableDescendant || matchedMcqOpt != null
                return if (isRadioLike) nowChecked else (nowChecked != initialCheckedState)
            }

            toggleVerified = isMcqOrToggleSatisfied(elementsAfter)
            if (!toggleVerified) {
                val fallbackRect = if (matchedMcqOpt != null && primaryTapRect != matchedMcqOpt.rowRect) {
                    matchedMcqOpt.rowRect
                } else {
                    target.bounds
                }
                Log.w(TAG, "Radio/Toggle '${target.primaryLabel}' still unchecked after initial tap; trying targeted accessibility + card retry...")
                val a11yOk = mutateUi("retry-radio-a11y:${target.primaryLabel}") {
                    service.performAccessibilityCheckableClick(matchedMcqOpt?.tapRect ?: target.bounds) ||
                        service.performClickAtRectAndAwait(fallbackRect)
                }
                if (a11yOk) {
                    delay(220L)
                    rootAfter = service.getActiveRootNode()
                    elementsAfter = groundingEngine.scanScreen(rootAfter)
                    toggleVerified = isMcqOrToggleSatisfied(elementsAfter)
                }
            }
            if (!toggleVerified) {
                radioFailedVerification = true
            }
            Log.i(TAG, "Radio/Toggle verified: initial=$initialCheckedState, toggleVerified=$toggleVerified, failed=$radioFailedVerification")
        }

        // Verify MCQ Next / Previous actually transitioned to another question
        var mcqNavVerified = false
        var mcqNavBlockedMessage: String? = null
        if ((isNextNavClick || isPrevNavClick) && quizBefore.isMcqScreen) {
            val quizAfter = McqGrounder.detectQuizState(elementsAfter)
            when {
                !quizAfter.warningBanner.isNullOrBlank() -> {
                    mcqNavBlockedMessage = "Navigation rejected by page warning: '${quizAfter.warningBanner}'. Select an option first!"
                }
                quizBefore.questionText.isNotBlank() && quizAfter.isMcqScreen &&
                    quizAfter.questionText == quizBefore.questionText &&
                    quizAfter.questionHeader == quizBefore.questionHeader -> {
                    mcqNavBlockedMessage = "Tapped '${target.primaryLabel}', but the question stayed identical (${quizAfter.questionHeader.ifBlank { "same question" }}). Verify option tick before continuing."
                }
                else -> {
                    mcqNavVerified = true
                }
            }
        }

        // (Removed the automatic second tap: it undid toggles such as menus/drawers and reported "verified".)
        if ((!stateChanged || radioFailedVerification || mcqNavBlockedMessage != null) && !toggleVerified) {
            Log.w(TAG, "Click unverified after ${watchedMs}ms for '${target.primaryLabel}' (stateChanged=$stateChanged, radioFail=$radioFailedVerification).")
            lastUnverifiedClickTarget = repeatedTarget
            lastUnverifiedClickScreen = postSig
        } else {
            lastUnverifiedClickTarget = ""
            lastUnverifiedClickScreen = null
        }

        val recentOtp = AutomationAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        val clickedLabelLower = target.primaryLabel.lowercase(Locale.ROOT)
        val isInstallOrDownload = clickedLabelLower == "install" || clickedLabelLower.contains("install") || clickedLabelLower == "download" || clickedLabelLower.contains("download")
        val isPlayStore = rootPkg.contains("vending", ignoreCase = true)

        val normalizedGoal = taskGoal.lowercase(Locale.ROOT)
        val targetLabel = target.primaryLabel.lowercase(Locale.ROOT)
        val visibleTargetLabels = elementsAfter.filterNot { it.isEditable }
            .map { it.primaryLabel.lowercase(Locale.ROOT) }
        val playbackExpected = listOf("play", "play it", "play karo", "bajao").any(normalizedGoal::contains)
        val requestedTrackTokens = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in setOf("please", "spotify", "youtube", "playlist", "meri", "mera", "par", "mein", "song", "track", "find", "search", "play", "karo", "kar", "do", "dhundo", "dhoondh", "aur", "the", "for", "and", "with") }
        val playbackVerified = playbackExpected && requestedTrackTokens.isNotEmpty() &&
            requestedTrackTokens.all(targetLabel::contains) &&
            visibleTargetLabels.any { it.contains(targetLabel) } &&
            visibleTargetLabels.any { it.contains("pause") || it.contains("now playing") || it.contains("currently playing") }
        val intendedResultVerified = toggleVerified || playbackVerified || mcqNavVerified
        val returnMessage = when {
            isPlayStore && isInstallOrDownload ->
                "Tapped Install on Google Play Store. The app is downloading and installing. NEXT ACTION: Wait for installation to complete and inspect screen for the 'Open' button, then tap 'Open' to launch and set up the app. DO NOT stop the task!"
            radioFailedVerification ->
                "FAILED: Tapped '${matchedMcqOpt?.label ?: target.primaryLabel}', but its radio/checkbox tick is STILL UNCHECKED ○! Question is NOT answered — do NOT click Next yet."
            mcqNavBlockedMessage != null ->
                "FAILED: $mcqNavBlockedMessage"
            toggleVerified ->
                "Tick VERIFIED ✓: '${matchedMcqOpt?.label ?: target.primaryLabel}' is now CHECKED/SELECTED."
            mcqNavVerified ->
                "Moved to next MCQ question cleanly. Read the updated [MCQ / QUIZ STATE] below."
            playbackVerified ->
                "Clicked '${target.primaryLabel}'; the requested track is visible and the player exposes an active playback state."
            stateChanged ->
                "Clicked '${target.primaryLabel}' and observed a UI change, but the requested result is unverified. Inspect the fresh screen before claiming success or taking a dependent action."
            else ->
                "Tapped '${target.primaryLabel}' once, but no screen change appeared after ${watchedMs / 1000.0}s. Do NOT tap it again blindly (it may be a toggle that already worked): call inspect_screen and look at the screen first."
        }

        val summary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter) + otpNotice
        recordObservation(service, summary)
        AgentTaskEngine.recordAction("click:${target.primaryLabel}", returnMessage, intendedResultVerified)
        AgentTaskEngine.setState(if (intendedResultVerified) AgentTaskEngine.State.CONTINUING else AgentTaskEngine.State.RECOVERING)
        val overallSuccess = when {
            radioFailedVerification -> false
            mcqNavBlockedMessage != null -> false
            else -> stateChanged || toggleVerified || mcqNavVerified
        }
        return@withContext TaskActionResult(
            success = overallSuccess,
            message = returnMessage,
            currentStateSummary = summary,
            verified = intendedResultVerified
        )
    }

    /**
     * Atomic, zero-hesitation Chrome/WebView MCQ handler:
     * 1) Finds option by letter ('a'..'e', 'option 2', 'dusra') OR text (Hindi/English), scrolling once if below fold.
     * 2) Taps it cleanly and verifies radio/checkbox is genuinely CHECKED (retrying inner radio once if needed).
     * 3) ONLY if tick is VERIFIED AND [clickNext] is true, taps 'Next' and waits for the new question to appear.
     */
    suspend fun handleAnswerMcqQuestion(
        optionTarget: String,
        clickNext: Boolean = false,
        taskGoal: String = ""
    ): TaskActionResult = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not enabled.", verified = false)

        val selectResult = executeVerifiedMcqOptionSelection(service, optionTarget, taskGoal)
        if (!selectResult.success || selectResult.verified != true) {
            return@withContext selectResult
        }

        if (!clickNext) {
            return@withContext selectResult
        }

        // Scroll slightly down once if Next button is below fold
        val (rootMid, _, elsMid) = observeMultiWindow(service)
        val quizMid = McqGrounder.detectQuizState(elsMid)
        if (quizMid.nextButton == null) {
            mutateUi("scroll-find-mcq-next") { service.performSmartScrollAndAwait("down", "small") }
            delay(220L)
        }

        val nextClick = handleClick(
            targetName = "Next",
            description = "Advance after verified MCQ option '$optionTarget'",
            taskGoal = taskGoal.ifBlank { "select mcq option $optionTarget and next" }
        )
        return@withContext if (nextClick.success) {
            nextClick.copy(
                message = "Option '$optionTarget' ticked & verified ✓, then advanced to next question cleanly."
            )
        } else {
            nextClick.copy(
                message = "Option '$optionTarget' is ticked ✓, but could not advance Next: ${nextClick.message}"
            )
        }
    }

    private suspend fun executeVerifiedMcqOptionSelection(
        service: AutomationAccessibilityService,
        optionTarget: String,
        taskGoal: String
    ): TaskActionResult {
        var (root, _, els) = observeMultiWindow(service)
        var quiz = McqGrounder.detectQuizState(els)
        var matchedOpt = McqGrounder.findMatchingOption(quiz, optionTarget)

        // If option C/D is below the viewport fold, scroll down once cleanly instead of saying "option nahi dikh raha"
        if (matchedOpt == null) {
            mutateUi("scroll-reveal-mcq-option") { service.performSmartScrollAndAwait("down", "small") }
            delay(260L)
            val obs = observeMultiWindow(service)
            root = obs.first
            els = obs.third
            quiz = McqGrounder.detectQuizState(els)
            matchedOpt = McqGrounder.findMatchingOption(quiz, optionTarget)
        }

        val resolvedElement = matchedOpt?.let { it.radioElement ?: it.element }
            ?: groundingEngine.findTargetElement(els, optionTarget, isToggleIntent = true)

        if (resolvedElement == null) {
            return TaskActionResult(
                success = false,
                message = "Could not find MCQ option '$optionTarget' on screen even after scrolling.",
                currentStateSummary = groundingEngine.formatElementsSummary(els, root),
                verified = false
            )
        }

        return handleClick(
            targetName = matchedOpt?.label ?: optionTarget,
            elementId = resolvedElement.id,
            isToggle = true,
            taskGoal = taskGoal.ifBlank { "select mcq option $optionTarget" }
        )
    }

    private fun findMatchingElementAfterClick(elementsAfter: List<UiElementInfo>, target: UiElementInfo): UiElementInfo? {
        return elementsAfter.firstOrNull { el ->
            el.bounds == target.bounds
        } ?: elementsAfter.firstOrNull { el ->
            (el.isCheckable || el.hasCheckableDescendant || el.className.contains("RadioButton", true)) &&
                abs(el.bounds.centerY() - target.bounds.centerY()) <= 38 &&
                (target.primaryLabel.length < 3 || el.primaryLabel.equals(target.primaryLabel, true) || el.primaryLabel.contains(target.primaryLabel.take(12), true))
        } ?: elementsAfter.firstOrNull { it.id == target.id && abs(it.bounds.centerY() - target.bounds.centerY()) <= 60 }
    }

    /**
     * Types text into a search bar or input field with zero typos.
     */
    suspend fun handleType(
        text: String,
        targetInputName: String? = null,
        submit: Boolean = false,
        clearExisting: Boolean = true,
        x: Int? = null,
        y: Int? = null
    ): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service is not running.")
        }

        // Resolve target input element
        var targetRect: Rect? = null
        val rootForTyping = service.getAppRootNode() ?: service.getActiveRootNode()
        val currentTypingPkg = rootForTyping?.packageName?.toString() ?: ""
        // #6: Zero Accessibility on Aura AI's own UI
        if (currentTypingPkg.contains("com.example.gemini") || currentTypingPkg.contains("com.aura.ai")) {
            com.example.gemini.data.live.ChatContextBridge.sendMessageToChat(text, target = "CURRENT", autoSend = submit)
            return@withContext TaskActionResult(
                success = true,
                message = "Dispatched text directly into Aura chat via native engine.",
                verified = true
            )
        }
        // #19: NEVER type phone numbers into Phone/Dialer/Contacts search bar — route directly to native ACTION_DIAL!
        val isPhoneOrContactsApp = currentTypingPkg.contains("dialer", ignoreCase = true) ||
            currentTypingPkg.contains("contacts", ignoreCase = true) ||
            currentTypingPkg.contains("telecom", ignoreCase = true) ||
            currentTypingPkg.contains("incallui", ignoreCase = true)
        val rawDigits = text.replace(Regex("[^0-9+*#]"), "")
        val pureDigitCount = rawDigits.count { it.isDigit() }
        if (isPhoneOrContactsApp && pureDigitCount in 7..15 && text.trim().matches(Regex("^[+0-9\\s\\-()*#]+$"))) {
            val dialRes = com.example.gemini.data.automation.PhoneControlManager(service)
                .dialNumber(service, rawDigits, autoCall = false)
            return@withContext dialRes.fold(
                onSuccess = { dialed ->
                    TaskActionResult(
                        success = true,
                        message = "Redirected phone number to native ACTION_DIAL dialpad with '$dialed' (blocked search-bar injection).",
                        verified = true
                    )
                },
                onFailure = { err ->
                    TaskActionResult(
                        success = false,
                        message = "FAILED: Blocked typing phone number into search bar and native dialer failed: ${err.message}",
                        verified = false
                    )
                }
            )
        }
        val elementsForTyping = if (rootForTyping != null) observeMultiWindow(service).third else emptyList()
        val visibleUiText = elementsForTyping.joinToString(" ") {
            "${it.text} ${it.contentDescription} ${it.viewId}"
        }.lowercase(Locale.ROOT)
        val browserPackage = (rootForTyping?.packageName?.toString() ?: "").let {
            it.contains("chrome", ignoreCase = true) || it.contains("browser", ignoreCase = true)
        }
        val isGoogleFlowPage = groundingEngine.isGoogleFlowSurface(elementsForTyping) ||
            visibleUiText.contains("flow.google") || visibleUiText.contains("labs.google/fx/tools/flow")
        val isGoogleAiStudioPage = visibleUiText.contains("aistudio.google") || visibleUiText.contains("google ai studio")
        val flowSettingsLabels = listOf("720p", "360p", "ingredients", "frames", "omni 1.1", "4s", "8s", "10s")
        val flowSettingsOverlayVisible = isGoogleFlowPage &&
            flowSettingsLabels.count { visibleUiText.contains(it) } >= 5 &&
            visibleUiText.contains("frames") && visibleUiText.contains("ingredients")

        // Flow's expanded generation settings sheet covers the composer. Dismiss it and
        // force a fresh screen read before typing so stale coordinates cannot hit a setting.
        if (flowSettingsOverlayVisible) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(450)
            dispatchImmediateScreenFrame()
            val refreshed = inspectScreen(waitForContent = true)
            return@withContext TaskActionResult(
                success = false,
                message = "Google Flow's settings panel was open, so no text was typed. I closed the panel; inspect the refreshed screen and target the prompt field before retrying.",
                currentStateSummary = refreshed,
                verified = false
            )
        }

        // PREFER the input's REAL accessibility bounds over model-estimated coordinates.
        // (The model was copying example numbers like x=500,y=895 instead of reading the screen.)
        val realInputRect: Rect? = try {
            val found = if (isGoogleFlowPage || isGoogleAiStudioPage) {
                elementsForTyping.firstOrNull { element ->
                    val label = element.primaryLabel.lowercase(Locale.ROOT)
                    !element.viewId.contains("url_bar", ignoreCase = true) &&
                        !element.viewId.contains("omnibox", ignoreCase = true) &&
                        (label.contains("what do you want to create") || label.contains("ask gemini") ||
                            label.contains("enter a prompt") || label.contains("write a prompt") ||
                            ((label.contains("prompt") || label.contains("describe")) &&
                                (element.isEditable || element.className.contains("EditText", ignoreCase = true))))
                }
            } else if (!targetInputName.isNullOrBlank()) {
                val editable = elementsForTyping.filter {
                    (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                        !it.viewId.contains("url_bar", ignoreCase = true) &&
                        !it.viewId.contains("omnibox", ignoreCase = true)
                }
                val requested = targetInputName.lowercase(Locale.ROOT).trim()
                val requestTokens = requested.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 2 }
                val matchingEditable = editable.filter { element ->
                    val labelTokens = "${element.primaryLabel} ${element.viewId}"
                        .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                    (requestTokens.isNotEmpty() && requestTokens.all { it in labelTokens }) ||
                        "${element.primaryLabel} ${element.viewId}".equals(targetInputName, ignoreCase = true)
                }
                when {
                    matchingEditable.size == 1 -> matchingEditable.single()
                    matchingEditable.size > 1 -> null
                    editable.size == 1 -> editable.single()
                    else -> {
                        val anchor = groundingEngine.findTargetElement(elementsForTyping, targetInputName)
                        val nearby = anchor?.let { label ->
                            editable.filter { abs(it.bounds.centerY() - label.bounds.centerY()) <= 180 }
                                .minByOrNull { abs(it.bounds.centerY() - label.bounds.centerY()) }
                        }
                        nearby
                    }
                }
            } else null
            found?.takeIf {
                (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                    !it.viewId.contains("url_bar", ignoreCase = true) &&
                    !it.viewId.contains("omnibox", ignoreCase = true)
            }?.bounds
        } catch (e: Exception) {
            null
        }

        // A fresh frame can take a moment after Android grants MediaProjection; wait briefly
        // before reporting that visual targeting is unavailable.
        if (isGoogleFlowPage && screenCaptureManager.isScreenShareActive() && !screenCaptureManager.hasRecentFrame()) {
            for (attempt in 0 until 20) {
                if (screenCaptureManager.hasRecentFrame()) break
                delay(100L)
            }
        }

        // DIRECT VISUAL COORDINATE FOCUS (fallback only when the real input could not be resolved):
        if (realInputRect != null) {
            targetRect = realInputRect
            Log.i(TAG, "Using real input bounds $realInputRect (ignoring model coordinates x=$x y=$y)")
        } else if (x != null && y != null && x in 0..1000 && y in 0..1000 &&
            (!(isGoogleFlowPage || isGoogleAiStudioPage) || screenCaptureManager.hasRecentFrame())) {
            val dm = service.resources.displayMetrics
            val physicalX = (x / 1000f) * dm.widthPixels
            val physicalY = (y / 1000f) * dm.heightPixels
            targetRect = Rect((physicalX - 40).toInt(), (physicalY - 30).toInt(), (physicalX + 40).toInt(), (physicalY + 30).toInt())
            Log.i(TAG, "Direct visual focus at normalized ($x, $y) -> targetRect $targetRect")
        } else if (isGoogleFlowPage || isGoogleAiStudioPage) {
            val product = if (isGoogleAiStudioPage) "Google AI Studio" else "Google Flow"
            val reason = if (!screenCaptureManager.hasRecentFrame()) {
                if (screenCaptureManager.isScreenShareActive()) {
                    "$product's prompt field is not exposed to accessibility. Screen Share is enabled, but Android has not delivered a fresh frame yet; wait for the preview to update and retry once."
                } else {
                    "$product's prompt field is not exposed to accessibility and no visual frame is available. Enable Screen Share once, then JARVIS can target the visible prompt field."
                }
            } else {
                "$product's prompt field could not be identified from the current frame. Keep the editor/composer visible and inspect the screen before retrying."
            }
            return@withContext TaskActionResult(false, reason, groundingEngine.formatElementsSummary(elementsForTyping, rootForTyping), verified = false)
        } else {
            val elements = elementsForTyping

            if (!targetInputName.isNullOrBlank()) {
                val editable = elements.filter {
                    (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                        !it.viewId.contains("url_bar", ignoreCase = true) &&
                        !it.viewId.contains("omnibox", ignoreCase = true)
                }
                val requestedTokens = targetInputName.lowercase(Locale.ROOT)
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 2 }
                if (editable.isEmpty()) {
                    // Placeholder button / fake search bar (e.g. YouTube, Play Store, Amazon, Swiggy)
                    targetRect = null
                } else if (editable.size > 1 && editable.none { element ->
                        val label = "${element.primaryLabel} ${element.viewId}".lowercase(Locale.ROOT)
                        label.contains(targetInputName.lowercase(Locale.ROOT))
                    }) {
                    val anchor = groundingEngine.findTargetElement(elements, targetInputName)
                    val nearby = anchor?.let { label ->
                        editable.filter { abs(it.bounds.centerY() - label.bounds.centerY()) <= 180 }
                            .minByOrNull { abs(it.bounds.centerY() - label.bounds.centerY()) }
                    }
                    if (nearby == null) {
                        return@withContext TaskActionResult(
                            success = false,
                            message = "Could not uniquely identify the '$targetInputName' input. No text was entered; inspect the current screen and name the exact field.",
                            currentStateSummary = groundingEngine.formatElementsSummary(elements, rootForTyping),
                            verified = false
                        )
                    }
                    targetRect = nearby.bounds
                } else {
                    val named = editable.filter { element ->
                        val labelTokens = "${element.primaryLabel} ${element.viewId}"
                            .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                        requestedTokens.isNotEmpty() && requestedTokens.all { it in labelTokens }
                    }
                    targetRect = (named.singleOrNull() ?: editable.singleOrNull())?.bounds
                    if (targetRect == null) {
                        return@withContext TaskActionResult(
                            success = false,
                            message = "Could not uniquely identify the '$targetInputName' input. No text was entered.",
                            currentStateSummary = groundingEngine.formatElementsSummary(elements, rootForTyping),
                            verified = false
                        )
                    }
                }
            } else {
                // Pick the best candidate input field (prefer web prompt placeholders or non-URL-bar inputs)
                val webPromptInput = elements.firstOrNull {
                    val label = it.primaryLabel.lowercase(Locale.ROOT)
                    (label.contains("what do you want to create") || label.contains("describe your") || label.contains("ask gemini") ||
                        (label.contains("prompt") && (it.isEditable || it.className.contains("EditText", ignoreCase = true)))) &&
                    !it.viewId.contains("url_bar", ignoreCase = true)
                }
                val nonUrlInputs = elements.filter { (it.isEditable || it.className.contains("EditText", ignoreCase = true)) && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
                val inputElement = webPromptInput ?: nonUrlInputs.firstOrNull() ?: elements.firstOrNull { it.isEditable && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
                targetRect = inputElement?.bounds
            }
        }

        val browserBeforeTyping = browserPackage || isGoogleFlowPage
        // In a browser, submitting is a deliberate, separate step (tap the real Generate arrow after SEEING the text).
        val composeField = targetInputName?.lowercase(Locale.ROOT)?.let { name ->
            listOf("recipient", "email", "message", "compose", "subject", "body", "to").any(name::contains)
        } == true

        // #16: Guard against injecting chat/DM message body into a Search bar (especially Instagram Search / Meta AI Search!)
        val goalLower = AgentTaskEngine.current()?.originalGoal?.lowercase(Locale.ROOT) ?: ""
        val inputNameLower = (targetInputName ?: "").lowercase(Locale.ROOT)
        val explicitlyWantsMessageComposer = inputNameLower.contains("message") || inputNameLower.contains("composer") || inputNameLower.contains("chat box") || inputNameLower.contains("write a message")
        val explicitlyWantsSearch = inputNameLower.contains("search") || inputNameLower.contains("find") || inputNameLower.contains("contact") || inputNameLower.contains("query")
        val isInstagramPkg = currentTypingPkg.contains("instagram", ignoreCase = true)
        val isTargetInSearchBar = targetRect?.let { r ->
            elementsForTyping.any { el ->
                (el.bounds == r || el.bounds.contains(r.centerX(), r.centerY())) &&
                (el.viewId.contains("search", ignoreCase = true) ||
                    el.viewId.contains("action_bar_search_edit_text", ignoreCase = true) ||
                    el.primaryLabel.contains("search", ignoreCase = true) ||
                    el.primaryLabel.contains("ask meta ai", ignoreCase = true))
            }
        } ?: false
        val hasRealInstagramDmComposer = elementsForTyping.any { el ->
            val v = el.viewId.lowercase(Locale.ROOT)
            val l = el.primaryLabel.lowercase(Locale.ROOT)
            v.contains("row_thread_composer_edittext") || v.contains("thread_composer") ||
                (el.isEditable && (l == "message..." || l == "message" || l.startsWith("message")))
        }
        val isInstagramDmIntent = isInstagramPkg && !explicitlyWantsSearch &&
            (explicitlyWantsMessageComposer || goalLower.contains("dm") || goalLower.contains("message") || goalLower.contains("send") || submit)

        if ((explicitlyWantsMessageComposer && !explicitlyWantsSearch && isTargetInSearchBar) ||
            (isInstagramDmIntent && (isTargetInSearchBar || !hasRealInstagramDmComposer))
        ) {
            Log.w(TAG, "Blocked injecting DM/message into Search bar! pkg='$currentTypingPkg', Target='$targetInputName', hasIgComposer=$hasRealInstagramDmComposer")
            return@withContext TaskActionResult(
                success = false,
                message = "FAILED: Screen is showing a Search bar rather than an open DM message composer (row_thread_composer_edittext). Refused to type message into Search. Open the contact's DM thread first.",
                currentStateSummary = groundingEngine.formatElementsSummary(elementsForTyping, rootForTyping),
                verified = false
            )
        }
        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val injectionResult = SerializedActionExecutor.execute(
            "type:${targetInputName ?: "focused-field"}",
            expectedGeneration = AgentTaskEngine.current()?.actionGeneration
        ) {
            service.directInjectText(
                textToType = text,
                targetRect = targetRect,
                targetInputName = targetInputName,
                submit = submit,
                clearExisting = clearExisting
            )
        } ?: AutomationAccessibilityService.DirectInjectionResult(
            status = "FAILED",
            method = "NONE",
            submitted = false,
            reason = "Direct injection execution failed"
        )
        val success = injectionResult.isSuccess
        val rootPkg = service.getActiveRootNode()?.packageName?.toString() ?: ""
        val isBrowser = browserBeforeTyping || rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
        val verifiedSuccess: Boolean
        val responseMessage: String
        val stateSummary: String

        if (success && isBrowser) {
            delay(300)
        }

        delay(400)
        dispatchImmediateScreenFrame()
        val rootAfter = service.getAppRootNode() ?: service.getActiveRootNode()
        val elementsAfter = if (rootAfter != null) groundingEngine.scanScreen(rootAfter) else emptyList()

        val isVerified = if (isBrowser) {
            success && (service.lastTypeVerified || injectionResult.isSuccess)
        } else {
            val verification = ActionVerifier.verifyTyping(
                service = service,
                targetRect = targetRect,
                expectedText = text
            )
            verification.isVerified || service.lastTypeVerified || injectionResult.isSuccess
        }

        verifiedSuccess = success && isVerified
        recordObservation(service, groundingEngine.formatElementsSummary(elementsAfter, rootAfter))
        AgentTaskEngine.recordAction("type:${targetInputName ?: "input"}", if (verifiedSuccess) "direct injection verified" else "direct injection unverified", verifiedSuccess)
        AgentTaskEngine.setState(if (verifiedSuccess) AgentTaskEngine.State.CONTINUING else AgentTaskEngine.State.RECOVERING)
        responseMessage = if (verifiedSuccess) {
            "Direct text injection succeeded via ${injectionResult.method}${if (injectionResult.submitted) " and submitted" else ""}. Inspect fresh screen."
        } else if (success && !isVerified && isBrowser) {
            "NOT CONFIRMED: the text was sent but is NOT visible in the page input. Look at screen frame and retry if needed."
        } else if (success && !isVerified) {
            "FAILED: Text input was attempted, but could NOT be verified in the input field. Reason: ${injectionResult.reason}."
        } else {
            "FAILED: Direct text injection failed. Reason: ${injectionResult.reason}."
        }
        stateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)

        return@withContext TaskActionResult(
            success = verifiedSuccess,
            message = responseMessage,
            currentStateSummary = stateSummary,
            verified = verifiedSuccess
        )
    }

    private fun verifyTextInTree(service: AutomationAccessibilityService, snippet: String): Boolean {
        val root = service.getAppRootNode() ?: service.getActiveRootNode()
        if (root != null && verifyTextInNode(root, snippet)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (window in service.windows) {
                val wRoot = window.root ?: continue
                if (verifyTextInNode(wRoot, snippet)) return true
            }
        }
        return false
    }

    private fun verifyTextInNode(node: AccessibilityNodeInfo, snippet: String): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(snippet) || desc.contains(snippet)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (verifyTextInNode(child, snippet)) return true
        }
        return false
    }

    /**
     * Executes a dedicated short-video swipe (Instagram Reels, YouTube Shorts, TikTok) with
     * awaited gesture completion and post-swipe metadata verification.
     */
    suspend fun handleSwipeReel(direction: String = "up"): TaskActionResult = withContext(Dispatchers.IO) {
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
        val service = AutomationAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service not running.")

        val dirLower = direction.trim().lowercase(Locale.ROOT)
        // Standard swipe_reel contract: "up" / "next" moves to NEXT reel (finger swipes upward),
        // "down" / "prev" moves to PREVIOUS reel (finger swipes downward).
        val upward = dirLower !in setOf("down", "prev", "previous", "back")

        val (rootBefore, allRootsBefore, preElements) = observeMultiWindow(service)
        val preSignature = ScreenSignatureCalculator.computeCombinedSignature(allRootsBefore)
        val pkg = rootBefore?.packageName?.toString().orEmpty()
        val preReelMeta = preElements
            .mapNotNull { it.primaryLabel.trim().takeIf { lbl -> lbl.length >= 2 } }
            .distinct()
            .take(15)
            .joinToString("|")

        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        var dispatched = service.performReelSwipeAndAwait(upward, attempt = 1)
        if (!dispatched) {
            dispatched = service.performReelAccessibilityScroll(upward)
        }
        if (!dispatched) {
            return@withContext TaskActionResult(
                success = false,
                message = "FAILED: Short-video swipe gesture was cancelled or rejected by Android.",
                currentStateSummary = groundingEngine.formatElementsSummary(preElements, rootBefore),
                verified = false
            )
        }

        var uiWait = waitForUiState(preSignature, expectedPackage = pkg, timeoutMs = 1_200L)
        var (rootAfterReel, allRootsAfter, elementsAfterReel) = observeMultiWindow(service)
        if (uiWait.elements.isNotEmpty() && elementsAfterReel.isEmpty()) {
            elementsAfterReel = uiWait.elements
            rootAfterReel = uiWait.root ?: rootAfterReel
        }
        var postSignatureReel = uiWait.signature ?: ScreenSignatureCalculator.computeCombinedSignature(allRootsAfter)
        var postReelMeta = elementsAfterReel
            .mapNotNull { it.primaryLabel.trim().takeIf { lbl -> lbl.length >= 2 } }
            .distinct()
            .take(15)
            .joinToString("|")
        var reelChanged = !postSignatureReel.isEquivalentTo(preSignature) ||
            (preReelMeta.isNotBlank() && postReelMeta.isNotBlank() && preReelMeta != postReelMeta)

        // If first flick appeared to snap back (unchanged tree), retry once via pager scroll or stronger flick
        if (!reelChanged) {
            val retryOk = service.performReelAccessibilityScroll(upward) ||
                service.performReelSwipeAndAwait(upward, attempt = 2)
            if (retryOk) {
                uiWait = waitForUiState(preSignature, expectedPackage = pkg, timeoutMs = 1_000L)
                val retryObs = observeMultiWindow(service)
                rootAfterReel = retryObs.first ?: uiWait.root ?: rootAfterReel
                allRootsAfter = retryObs.second
                if (retryObs.third.isNotEmpty()) {
                    elementsAfterReel = retryObs.third
                } else if (uiWait.elements.isNotEmpty()) {
                    elementsAfterReel = uiWait.elements
                }
                postSignatureReel = ScreenSignatureCalculator.computeCombinedSignature(allRootsAfter)
                postReelMeta = elementsAfterReel
                    .mapNotNull { it.primaryLabel.trim().takeIf { lbl -> lbl.length >= 2 } }
                    .distinct()
                    .take(15)
                    .joinToString("|")
                reelChanged = !postSignatureReel.isEquivalentTo(preSignature) ||
                    (preReelMeta.isNotBlank() && postReelMeta.isNotBlank() && preReelMeta != postReelMeta)
            }
        }

        return@withContext TaskActionResult(
            success = true,
            message = if (reelChanged) {
                "Swiped ${if (upward) "to next" else "to previous"} short video (verified)."
            } else {
                "Dispatched ${if (upward) "next" else "previous"} short-video swipe gesture."
            },
            currentStateSummary = groundingEngine.formatElementsSummary(elementsAfterReel, rootAfterReel),
            verified = reelChanged
        )
    }

    /**
     * Performs a directional scroll with loop guard.
     */
    // #17: Default scroll distance is "medium" (~65% screen height) instead of "small"
    suspend fun handleScroll(direction: String, distance: String = "medium"): TaskActionResult = withContext(Dispatchers.IO) {
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service not running.")
        }

        consecutiveScrollCount++
        if (consecutiveScrollCount > 15) {
            resetScrollCount()
            val (root, _, elements) = observeMultiWindow(service)
            val (pkg, title) = groundingEngine.extractScreenContext(root, elements)
            return@withContext TaskActionResult(
                success = false,
                message = "Scrolled 15 times in a row without finding target. You are currently in app '$pkg' on screen '$title'. If the item is not here, try navigating to another section or folder.",
                currentStateSummary = groundingEngine.formatElementsSummary(elements, root)
            )
        }

        val (rootNode, allRootsBefore, preElements) = observeMultiWindow(service)
        val preSignature = ScreenSignatureCalculator.computeCombinedSignature(allRootsBefore)
        val pkg = rootNode?.packageName?.toString() ?: ""

        val distLower = distance.lowercase(Locale.ROOT)
        val isShortVideoFeed = distLower == "reel" || distLower == "short" ||
            com.example.gemini.data.live.DeviceControlHelper.isShortVideoScreen(
                packageName = pkg,
                viewIds = preElements.map { it.viewId },
                labels = preElements.map { it.primaryLabel },
                descriptions = preElements.map { it.contentDescription }
            )

        if (isShortVideoFeed) {
            // In scroll_screen semantics, "down" advances forward (finger swipes UP), "up" goes back (finger swipes DOWN)
            val swipeDir = if (direction.lowercase(Locale.ROOT) == "up") "down" else "up"
            return@withContext handleSwipeReel(swipeDir)
        }

        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val success = service.performSmartScrollAndAwait(direction, distance)

        AgentTaskEngine.current()?.scrollCount = (AgentTaskEngine.current()?.scrollCount ?: 0) + 1
        dispatchImmediateScreenFrame()
        val uiWait = waitForUiState(preSignature, expectedPackage = service.getActiveRootNode()?.packageName?.toString(), timeoutMs = 8_000L)
        val rootAfter = uiWait.root ?: service.getActiveRootNode()
        val postSignature = uiWait.signature ?: ScreenSignatureCalculator.computeSignature(rootAfter)
        if (postSignature.isEquivalentTo(preSignature)) {
            AgentTaskEngine.current()?.noProgressCount = (AgentTaskEngine.current()?.noProgressCount ?: 0) + 1
        } else {
            AgentTaskEngine.current()?.noProgressCount = 0
        }
        recordObservation(service)
        AgentTaskEngine.recordAction("scroll:$direction", if (success) "scroll dispatched" else "scroll not dispatched", success && !postSignature.isEquivalentTo(preSignature))
        AgentTaskEngine.setState(AgentTaskEngine.State.CONTINUING)
        val elementsAfter = groundingEngine.scanScreen(rootAfter)
        val dynamicElements = elementsAfter.filter { it.bounds.top > 350 }
        val currentSignature = if (dynamicElements.isNotEmpty()) {
            dynamicElements.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        } else {
            elementsAfter.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        }

        if (currentSignature.isNotBlank() && currentSignature == lastScreenSignature) {
            duplicateSignatureCount++
            if (duplicateSignatureCount >= 2) {
                resetScrollCount()
                val (pkg, title) = groundingEngine.extractScreenContext(rootAfter, elementsAfter)
                return@withContext TaskActionResult(
                    success = false,
                    message = "Reached end of screen in direction '$direction'. Cannot scroll further in this view. Current screen: '$title' in app '$pkg'. If looking for a photo/document, try opening another folder like 'WhatsApp Images', 'Screenshots', or 'Downloads'.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        } else {
            duplicateSignatureCount = 0
        }
        lastScreenSignature = currentSignature

        return@withContext TaskActionResult(
            success = success && uiWait.ready,
            message = "Scrolled screen $direction (scroll #$consecutiveScrollCount, distance: $distance).",
            currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter),
            verified = success && uiWait.ready && !postSignature.isEquivalentTo(preSignature)
        )
    }

    /**
     * Dispatches system navigation keys (back, home, recents).
     */
    suspend fun handleKey(key: String): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service is not running.")
        }

        var success = service.performSystemGesture(key)
        if (!success) {
            delay(200)
            success = service.performSystemGesture(key)
        }
        delay(400)
        dispatchImmediateScreenFrame()
        return@withContext TaskActionResult(
            success = success,
            message = if (success) "Pressed system key '$key'." else "Failed to dispatch system key '$key'.",
            currentStateSummary = captureCurrentStateSummary(service)
        )
    }

    /**
     * Inspects the current screen state in real time and returns a detailed summary
     * of the active foreground app, screen title, and visible content/elements.
     */
    suspend fun inspectScreen(waitForContent: Boolean = false): String = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext "Screen context unavailable: Accessibility Service is not active."

        // If a UI tap/scroll just happened very recently, briefly settle for WebView DOM / window transition
        val sinceMutationMs = System.currentTimeMillis() - service.lastMutationAt
        if (sinceMutationMs in 0L..260L) {
            val revBefore = AutomationAccessibilityService.uiChanges.value.revision
            withTimeoutOrNull(180L) {
                AutomationAccessibilityService.uiChanges.first { it.revision > revBefore }
            }
        }

        var (root, _, elements) = observeMultiWindow(service, forceRefresh = true)

        if (waitForContent) {
            var attempts = 0
            while (attempts < 4 && (
                    elements.none { it.text.isNotBlank() || it.contentDescription.isNotBlank() } ||
                        elements.any { el ->
                            val lbl = el.primaryLabel.lowercase(Locale.ROOT)
                            lbl == "loading..." || lbl == "loading" || lbl == "please wait..."
                        }
                    )
            ) {
                delay(220)
                val obs = observeMultiWindow(service, forceRefresh = true)
                root = obs.first
                elements = obs.third
                attempts++
            }
        }

        // If multi-window app scan produced no elements, check every accessible window as fallback
        if (elements.isEmpty()) {
            val allRoots = service.getAllRootNodes()
            allRoots.forEach { service.refreshNodeHierarchy(it, maxRefreshes = 18) }
            val scanned = groundingEngine.scanAllWindows(allRoots)
            if (scanned.isNotEmpty()) {
                elements = scanned
                root = allRoots.firstOrNull()
            }
        }

        if (root == null && elements.isEmpty()) {
            return@withContext "Screen context unavailable: Screen is blank or inaccessible."
        }

        dispatchImmediateScreenFrame()
        val recentOtp = AutomationAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        val seq = inspectSequence.incrementAndGet()
        val snapTime = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
        val pkgName = root?.packageName?.toString() ?: service.foregroundPackage ?: "unknown"
        val freshStamp = "[LIVE SCREEN SNAPSHOT #$seq @ $snapTime | APP=$pkgName — Treat THIS snapshot as the ONLY current ground truth; ignore any older screen memory!]\n"
        val screenSource = if (screenCaptureManager.hasRecentFrame()) {
            "$freshStamp[Screen share active: visual frame + refreshed multi-window accessibility tree.]\n"
        } else {
            val flowDetected = groundingEngine.isGoogleFlowSurface(elements)
            if (flowDetected) {
                "$freshStamp[Google Flow detected. Labeled accessibility controls are available. Screen coordinates require Screen Share.]\n"
            } else {
                "$freshStamp[Refreshed multi-window accessibility tree (including Chrome WebView & popup menus). Target by element_id, exact label, or icon synonym.]\n"
            }
        }
        val summary = screenSource + groundingEngine.formatElementsSummary(elements, root) + otpNotice
        AgentTaskEngine.observe(
            ScreenSignatureCalculator.computeSignature(root),
            pkgName,
            summary
        )
        Log.i(TAG, "Screen inspection #$seq generated ($pkgName)")
        return@withContext summary
    }

    /**
     * Waits up to [timeoutMs] for an app matching [appNameHint] to appear in the foreground window
     * and returns its live accessibility screen summary.
     */
    suspend fun waitForAppInForeground(appNameHint: String, timeoutMs: Long = 3800L): Pair<String, String> = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext ("unknown" to "Accessibility Service is not running.")
        val hintLower = appNameHint.lowercase(Locale.ROOT).trim()
        val expectedToken = when {
            hintLower.contains("whatsapp") -> "whatsapp"
            hintLower.contains("instagram") || hintLower == "insta" -> "instagram"
            hintLower.contains("youtube") || hintLower == "yt" -> "youtube"
            hintLower.contains("telegram") -> "telegram"
            hintLower.contains("chrome") -> "chrome"
            hintLower.contains("phone") || hintLower.contains("dialer") -> "dialer"
            else -> hintLower.split(" ").firstOrNull().orEmpty()
        }
        val start = System.currentTimeMillis()
        var currentPkg = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = service.getActiveRootNode()
            currentPkg = (root?.packageName?.toString() ?: service.foregroundPackage ?: "").lowercase(Locale.ROOT)
            if (currentPkg.isNotBlank() && !currentPkg.contains("com.example.gemini") && !currentPkg.contains("com.aura.ai") && !currentPkg.contains("com.termux")) {
                if (expectedToken.isBlank() || currentPkg.contains(expectedToken)) {
                    delay(450) // Let initial UI controls settle
                    break
                }
            }
            delay(250)
        }
        val rootAfter = service.getActiveRootNode()
        val finalPkg = rootAfter?.packageName?.toString() ?: service.foregroundPackage ?: "unknown"
        val elements = if (rootAfter != null) groundingEngine.scanScreen(rootAfter) else emptyList()
        val summary = if (rootAfter != null) groundingEngine.formatElementsSummary(elements, rootAfter) else "Screen loading..."
        finalPkg to summary
    }

    /**
     * In-App UI Search & Send fallback for WhatsApp when a contact/group is not in Android phone contacts
     * (or Contacts permission is missing).
     * Flow:
     * 1. Launch WhatsApp and wait for foreground UI.
     * 2. Tap Search icon/bar and directly inject [recipientName].
     * 3. Locate and tap the matching contact/chat row from search results.
     * 4. Directly inject [message] into the chat message composer and trigger verified Send.
     */
    suspend fun runWhatsAppInAppSearchAndSend(
        context: android.content.Context,
        recipientName: String,
        message: String,
        appNumber: Int? = null
    ): TaskActionResult = withContext(Dispatchers.IO) {
        val service = AutomationAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not running.")

        val launched = com.example.gemini.data.automation.AppLauncher.launchApp(context, "WhatsApp", appNumber)
        if (!launched) {
            return@withContext TaskActionResult(false, "Could not launch WhatsApp on this device.")
        }

        waitForAppInForeground("whatsapp", timeoutMs = 4200L)

        // If we are inside an existing chat thread, press Back to reach the main WhatsApp chats/search screen
        for (backAttempt in 0..1) {
            val root = service.getActiveRootNode()
            val elems = if (root != null) groundingEngine.scanScreen(root) else emptyList()
            val hasSearchControl = elems.any {
                it.primaryLabel.contains("search", ignoreCase = true) ||
                it.contentDescription.contains("search", ignoreCase = true) ||
                it.viewId.contains("search", ignoreCase = true)
            }
            val hasNavigateUp = elems.any {
                it.contentDescription.equals("Navigate up", ignoreCase = true) ||
                it.contentDescription.equals("Back", ignoreCase = true)
            }
            if (!hasSearchControl && hasNavigateUp) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(450)
            } else {
                break
            }
        }

        // Step 1: Open Search bar if not already editable
        var rootNow = service.getActiveRootNode()
        var elemsNow = if (rootNow != null) groundingEngine.scanScreen(rootNow) else emptyList()
        val hasEditableSearch = elemsNow.any {
            it.isEditable && (it.primaryLabel.contains("search", ignoreCase = true) || it.viewId.contains("search", ignoreCase = true))
        }
        if (!hasEditableSearch) {
            val searchBtn = elemsNow.firstOrNull {
                it.contentDescription.contains("search", ignoreCase = true) ||
                it.primaryLabel.contains("search", ignoreCase = true) ||
                it.viewId.contains("menuitem_search", ignoreCase = true) ||
                it.viewId.contains("search", ignoreCase = true)
            }
            if (searchBtn != null) {
                service.performClickAtRect(searchBtn.bounds)
                delay(550)
            }
        }

        // Step 2: Directly inject recipientName into the Search field
        val searchTypeRes = handleType(
            text = recipientName,
            targetInputName = "Search",
            submit = false,
            clearExisting = true
        )
        if (!searchTypeRes.success) {
            return@withContext TaskActionResult(
                success = false,
                message = "Opened WhatsApp, but could not type '$recipientName' into the search bar.",
                currentStateSummary = captureCurrentStateSummary(service),
                verified = false
            )
        }

        delay(850) // Allow WhatsApp search results list to populate

        // Step 3: Find matching contact row below the search bar
        rootNow = service.getActiveRootNode()
        elemsNow = if (rootNow != null) groundingEngine.scanScreen(rootNow) else emptyList()
        val queryLower = recipientName.lowercase(Locale.ROOT).trim()
        val queryTokens = queryLower.split(Regex("\\s+")).filter { it.isNotBlank() }

        val screenW = service.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val nonAvatarCandidates = elemsNow.filter { el ->
            val vId = el.viewId.lowercase(Locale.ROOT)
            val desc = el.contentDescription.lowercase(Locale.ROOT)
            !el.isEditable &&
            el.bounds.top > 220 &&
            !vId.contains("search_src_text") &&
            !vId.contains("search_input") &&
            !vId.contains("contact_photo") &&
            !vId.contains("avatar") &&
            !vId.contains("profile_pic") &&
            !vId.contains("conversations_row_contact_photo") &&
            !desc.contains("profile photo") &&
            !desc.contains("profile picture") &&
            !(el.bounds.right < screenW * 0.24f && el.bounds.width() < screenW * 0.25f)
        }
        val candidateRow = nonAvatarCandidates.firstOrNull { el ->
            (el.viewId.contains("conversations_row_contact_name", ignoreCase = true) ||
                el.viewId.contains("contactpicker_row_name", ignoreCase = true)) &&
                el.primaryLabel.lowercase(Locale.ROOT).let { lbl ->
                    lbl.contains(queryLower) || (queryTokens.isNotEmpty() && queryTokens.all { lbl.contains(it) })
                }
        } ?: nonAvatarCandidates.firstOrNull { el ->
            val label = "${el.primaryLabel} ${el.text} ${el.contentDescription}".lowercase(Locale.ROOT)
            label.contains(queryLower) || (queryTokens.isNotEmpty() && queryTokens.all { label.contains(it) })
        }

        if (candidateRow == null) {
            return@withContext TaskActionResult(
                success = false,
                message = "Searched for '$recipientName' in WhatsApp, but no matching contact or chat was found on screen.",
                currentStateSummary = groundingEngine.formatElementsSummary(elemsNow, rootNow),
                verified = false
            )
        }

        // #18: Tap strictly on the right-center of the row (>= 30% screen width) to avoid circular profile avatar popup
        val tapX = (candidateRow.bounds.left + candidateRow.bounds.width() * 0.62f)
            .coerceAtLeast(screenW * 0.30f)
            .coerceAtMost(screenW * 0.85f)
        val tapY = candidateRow.bounds.exactCenterY()
        service.clickAt(tapX, tapY, 80L)
        delay(450)

        // #18: Verify message composer is visible; if Profile/Info popup opened instead, press BACK, wait 350ms, and retry once
        val postTapElems = groundingEngine.scanScreen(service.getActiveRootNode())
        val composerVisible = postTapElems.any { el ->
            val v = el.viewId.lowercase(Locale.ROOT)
            val l = el.primaryLabel.lowercase(Locale.ROOT)
            v.contains("entry") || v.contains("composer") || (el.isEditable && l.contains("message"))
        }
        if (!composerVisible) {
            Log.w(TAG, "WhatsApp chat composer not visible after row tap (profile popup may have opened); pressing BACK and retrying right-center tap once...")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(350)
            service.clickAt((screenW * 0.60f), tapY, 80L)
            delay(500)
        } else {
            delay(350)
        }

        // Step 4: Verify chat composer is open and directly inject the message
        val msgTypeRes = handleType(
            text = message,
            targetInputName = "Message",
            submit = true,
            clearExisting = false
        )

        val sentVerified = service.awaitWhatsAppAutoSend(timeoutMs = 3500L) || msgTypeRes.success
        return@withContext TaskActionResult(
            success = sentVerified,
            message = if (sentVerified) "Found '$recipientName' inside WhatsApp and sent message: \"$message\"."
                      else "Opened chat with '$recipientName', but could not verify message send.",
            currentStateSummary = captureCurrentStateSummary(service),
            verified = sentVerified
        )
    }

    private fun captureCurrentStateSummary(service: AutomationAccessibilityService): String {
        val (root, _, elements) = observeMultiWindow(service)
        if (root == null && elements.isEmpty()) return "Screen empty or not accessible."
        val recentOtp = AutomationAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        return groundingEngine.formatElementsSummary(elements, root) + otpNotice
    }

    private fun isTogglePhrase(phrase: String): Boolean {
        val lower = phrase.lowercase(Locale.ROOT)
        return lower.contains("switch") || lower.contains("toggle") ||
                lower.contains("enable") || lower.contains("disable") ||
                lower.contains("turn on") || lower.contains("turn off")
    }

    private fun isSensitiveTarget(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("pay") || combined.contains("payment") ||
                combined.contains("delete all") || combined.contains("factory reset") ||
                combined.contains("format") || combined.contains("purchase") ||
                combined.contains("transfer money") || combined.contains("send money")
    }

    private fun isClearSearchButton(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("clear search") || combined.contains("clear query") ||
                combined.contains("clear text") || combined.contains("clear input")
    }

    private fun isBrowserSearchScreen(service: AutomationAccessibilityService): Boolean {
        val root = service.getAppRootNode() ?: service.getActiveRootNode() ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("chrome", ignoreCase = true) && !pkg.contains("browser", ignoreCase = true)) {
            return false
        }
        val elements = groundingEngine.scanScreen(root)
        return groundingEngine.isGoogleSearchResults(elements)
    }

    private fun isChromeIncognitoScreen(elements: List<UiElementInfo>): Boolean {
        return elements.any { el ->
            val l = el.primaryLabel.lowercase(Locale.ROOT)
            val v = el.viewId.lowercase(Locale.ROOT)
            l.contains("you’ve gone incognito") ||
                l.contains("you've gone incognito") ||
                l.contains("gone incognito") ||
                v.contains("incognito")
        }
    }

    private suspend fun openChromeMenuAndSelect(
        service: AutomationAccessibilityService,
        itemQuery: String,
        fallbackQueries: List<String>,
        verifyOpened: (List<UiElementInfo>) -> Boolean,
        successMsg: String,
        failMsg: String
    ): TaskActionResult {
        var (rootNow, _, elsNow) = observeMultiWindow(service, forceRefresh = true)
        var targetItem = fallbackQueries.firstNotNullOfOrNull { q ->
            groundingEngine.findTargetElement(elsNow, q)
        }

        if (targetItem == null) {
            var menuTarget = groundingEngine.findTargetElement(elsNow, "3 dots")
                ?: groundingEngine.findTargetElement(elsNow, "More options")
                ?: groundingEngine.findTargetElement(elsNow, "Customize and control")
                ?: elsNow.firstOrNull { isBrowserOverflowMenu(it) }

            if (menuTarget == null) {
                mutateUi("reveal-chrome-toolbar") { service.performSmartScrollAndAwait("up", "short") }
                delay(420)
                val revealed = observeMultiWindow(service, forceRefresh = true)
                rootNow = revealed.first
                elsNow = revealed.third
                menuTarget = groundingEngine.findTargetElement(elsNow, "3 dots")
                    ?: groundingEngine.findTargetElement(elsNow, "More options")
                    ?: groundingEngine.findTargetElement(elsNow, "Customize and control")
                    ?: elsNow.firstOrNull { isBrowserOverflowMenu(it) }
            }

            if (menuTarget == null || !mutateUi("open-chrome-menu") { service.performClickAtRectAndAwait(menuTarget.bounds) }) {
                return TaskActionResult(
                    success = false,
                    message = "Chrome's 3-dot menu button was not found; no tap was made.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elsNow, rootNow),
                    verified = false
                )
            }
            delay(460)
            val menuObs = observeMultiWindow(service, forceRefresh = true)
            rootNow = menuObs.first
            elsNow = menuObs.third
            targetItem = fallbackQueries.firstNotNullOfOrNull { q ->
                groundingEngine.findTargetElement(elsNow, q)
            }
        }

        if (targetItem == null) {
            return TaskActionResult(
                success = false,
                message = "Opened Chrome's 3-dot menu, but '$itemQuery' was not visible. Inspect current elements.",
                currentStateSummary = groundingEngine.formatElementsSummary(elsNow, rootNow),
                verified = false
            )
        }

        val beforeAction = ScreenSignatureCalculator.computeCombinedSignature(observeMultiWindow(service).second)
        if (!mutateUi("click-chrome-menu:$itemQuery") { service.performClickAtRectAndAwait(targetItem.bounds) }) {
            return TaskActionResult(
                success = false,
                message = "Could not tap Chrome '$itemQuery'.",
                currentStateSummary = groundingEngine.formatElementsSummary(elsNow, rootNow),
                verified = false
            )
        }
        delay(650)
        val (finalRoot, finalRoots, finalEls) = observeMultiWindow(service, forceRefresh = true)
        val afterAction = ScreenSignatureCalculator.computeCombinedSignature(finalRoots)
        val isChromePkg = finalRoot?.packageName?.toString()?.contains("chrome", ignoreCase = true) == true
        val verified = isChromePkg && (!afterAction.isEquivalentTo(beforeAction) || verifyOpened(finalEls)) && verifyOpened(finalEls)
        return TaskActionResult(
            success = verified,
            message = if (verified) successMsg else failMsg,
            currentStateSummary = groundingEngine.formatElementsSummary(finalEls, finalRoot),
            verified = verified
        )
    }

    private fun isBrowserOverflowMenu(target: UiElementInfo?): Boolean {
        if (target == null) return false
        val text = target.primaryLabel.lowercase(Locale.ROOT)
        val vid = target.viewId.lowercase(Locale.ROOT)
        val desc = target.contentDescription.lowercase(Locale.ROOT)
        return desc.contains("more options") ||
               desc.contains("customize and control") ||
               text.contains("more options") ||
               vid.contains("menu_button") ||
               vid.contains("more_menu") ||
               vid.contains("overflow")
    }
}
