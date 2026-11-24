package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.example.gemini.data.automation.agent.ScreenSignatureCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The system binds this once the user turns on "AntiGem Android Automation" in Accessibility settings.
 */
class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationAccessibility"

        @Volatile var instance: AutomationAccessibilityService? = null
            private set

        data class ExtractedOtp(val code: String, val timestamp: Long, val senderPkg: String)
        @Volatile
        var latestExtractedOtp: ExtractedOtp? = null

        fun getRecentOtp(): String? {
            val otp = latestExtractedOtp ?: return null
            if (System.currentTimeMillis() - otp.timestamp < 120_000L) { // valid for 2 minutes
                return otp.code
            }
            return null
        }

        data class UiChangeSnapshot(val revision: Long = 0L, val timestamp: Long = 0L)
        private val _uiChanges = MutableStateFlow(UiChangeSnapshot())
        val uiChanges: StateFlow<UiChangeSnapshot> = _uiChanges.asStateFlow()

        private var lastUiEventTimestamp = 0L
        fun recordUiEvent() {
            val now = System.currentTimeMillis()
            if (now - lastUiEventTimestamp >= 300L) {
                lastUiEventTimestamp = now
                _uiChanges.value = UiChangeSnapshot(_uiChanges.value.revision + 1, now)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var lastEventAt = 0L
        private set
    @Volatile var foregroundPackage: String? = null
        private set
    @Volatile var foregroundClass: String? = null
        private set
    @Volatile var lastTypeVerified: Boolean = false

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "AutomationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = System.currentTimeMillis()
        recordUiEvent()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
            event.className?.toString()?.let { foregroundClass = it }
            AuraScreenObserver.processAccessibilityEvent(event, getActiveRootNode())
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
            AuraScreenObserver.scheduleContentCheck(event.packageName?.toString())
        } else if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try {
                val text = event.text?.joinToString(" ") ?: ""
                val pkg = event.packageName?.toString() ?: ""
                val lower = text.lowercase(Locale.ROOT)
                if (lower.contains("otp") || lower.contains("code") || lower.contains("verification") || lower.contains("pin")) {
                    val matcher = java.util.regex.Pattern.compile("\\b(\\d{4,8})\\b").matcher(text)
                    if (matcher.find()) {
                        val code = matcher.group(1)
                        if (code != null) {
                            latestExtractedOtp = ExtractedOtp(code, System.currentTimeMillis(), pkg)
                            Log.i(TAG, "Captured OTP from notification ($pkg): $code")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun getForegroundAppRootNode(): AccessibilityNodeInfo? {
        try {
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            // 1. Focused non-Aura application window
            val focusedNonAura = appWindows.firstOrNull { win ->
                val r = win.root
                win.isFocused && r != null && r.packageName?.toString() != packageName
            }
            if (focusedNonAura?.root != null) return focusedNonAura.root

            // 2. Active non-Aura application window
            val activeNonAura = appWindows.firstOrNull { win ->
                val r = win.root
                win.isActive && r != null && r.packageName?.toString() != packageName
            }
            if (activeNonAura?.root != null) return activeNonAura.root

            // 3. Any non-Aura application window with a valid root
            val anyNonAura = appWindows.firstOrNull { win ->
                val r = win.root
                r != null && r.packageName?.toString() != packageName
            }
            if (anyNonAura?.root != null) return anyNonAura.root
        } catch (_: Exception) {}

        // 4. rootInActiveWindow if valid and not Aura (or if Aura is the only foreground app)
        val active = rootInActiveWindow
        if (active != null) return active

        // 5. Any window root
        try {
            for (win in windows) {
                win.root?.let { return it }
            }
        } catch (_: Exception) {}

        return null
    }

    fun getActiveRootNode(): AccessibilityNodeInfo? {
        return getForegroundAppRootNode()
    }

    fun clickAt(x: Float, y: Float, durationMs: Long = 100L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapAtPercentage(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (xPercent / 100f) * dm.widthPixels
        val y = (yPercent / 100f) * dm.heightPixels
        return clickAt(x, y)
    }

    fun clickNodeAndTap(node: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Direct action click on the node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // Strategy 2: Traverse parents up to 4 levels for clickable wrapper
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        // Strategy 3: Center bounds precision gesture tap
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            return clickAt(cx, cy, durationMs = 80L)
        }
        return false
    }

    /**
     * Identifies if a node is an avatar/profile picture or logo so we do NOT click it.
     */
    fun isProfileAvatarOrIcon(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        return desc.contains("profile photo") || desc.contains("profile picture") ||
                desc.contains("avatar") || desc.contains("photo") ||
                viewId.contains("avatar") || viewId.contains("profile_picture") ||
                viewId.contains("contact_photo") || viewId.contains("picture") ||
                viewId.contains("badge") || viewId.contains("contact_badge") ||
                (className.contains("imageview") && (desc.isBlank() || desc.contains("profile") || desc.contains("photo")))
    }

    /**
     * Safely clicks a contact or group name, strictly avoiding clicking the profile picture/avatar
     * by filtering out avatar nodes and biasing the coordinate tap to the right-center text area (X = 60%).
     */
    fun clickContactOrGroupName(targetName: String): Boolean {
        val root = getActiveRootNode() ?: return false
        val cleanTarget = targetName.trim().lowercase(Locale.ROOT)

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // Strictly skip any profile avatar or image node
            if (!isProfileAvatarOrIcon(node)) {
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

                if (text.contains(cleanTarget) || desc.contains(cleanTarget)) {
                    // Try direct ACTION_CLICK first
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "Clicked contact name via ACTION_CLICK: '$targetName'")
                        return true
                    }
                    val parent = node.parent
                    if (parent != null && parent.isClickable && !isProfileAvatarOrIcon(parent) && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "Clicked contact name parent via ACTION_CLICK: '$targetName'")
                        return true
                    }

                    // Coordinate fallback: land on the text portion (60% width), safely avoiding the left avatar
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val cx = bounds.left + bounds.width() * 0.60f
                        val cy = bounds.exactCenterY()
                        if (clickAt(cx, cy, durationMs = 80L)) {
                            Log.i(TAG, "Clicked contact name safely at ($cx, $cy) (avoided avatar): '$targetName'")
                            return true
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    fun clickNodeWithText(targetText: String): Boolean {
        val root = getActiveRootNode() ?: return false
        val cleanTarget = targetText.trim().lowercase(Locale.ROOT)

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            if (text.contains(cleanTarget) || desc.contains(cleanTarget)) {
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked node matching '$targetText' ($text / $desc)")
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    fun typeText(textToType: String): Boolean {
        val root = getActiveRootNode() ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Robust Multi-Tier Scroll:
     * 1. Traverses the hierarchy to find the actual scrollable container (RecyclerView, ScrollView, ListView).
     * 2. Executes ACTION_SCROLL_FORWARD / BACKWARD on the scrollable container.
     * 3. Seamlessly falls back to hardware-accelerated smooth gesture swipe so scrolling never fails.
     */
    fun getAppRootNode(): AccessibilityNodeInfo? {
        return getActiveRootNode()
    }

    fun isKeyboardVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    fun performClickAtRect(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val root = getActiveRootNode()
        if (root?.packageName?.toString() == "com.android.chrome" && clickAt(cx, cy)) return true
        var node = root?.let { findNodeCoveringRect(it, rect) }
        while (node != null) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && node.isClickable && bounds.contains(cx.toInt(), cy.toInt())) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            node = node.parent
        }
        Log.i(TAG, "Using coordinate fallback at ($cx, $cy) [bounds: $rect]")
        return clickAt(cx, cy)
    }

    fun findNodeCoveringRect(root: AccessibilityNodeInfo, targetRect: Rect): AccessibilityNodeInfo? {
        val temp = Rect()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.getBoundsInScreen(temp)
            if (Rect.intersects(temp, targetRect)) {
                for (i in (node.childCount - 1) downTo 0) {
                    val child = node.getChild(i) ?: continue
                    val deep = search(child)
                    if (deep != null) return deep
                }
                return node
            }
            return null
        }
        return search(root)
    }

    fun findNodeWithInputFocus(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findNodeWithInputFocus(child)
            if (res != null) return res
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun isUrlBarNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val vid = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        return vid.contains("url_bar") || vid.contains("omnibox") ||
               vid.contains("location_bar") || vid.contains("search_box_text")
    }

    fun performSmartScroll(direction: String, distance: String = "medium"): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val deltaY = when (distance.lowercase(Locale.ROOT)) {
            "short", "small" -> height * 0.35f
            "long", "large" -> height * 0.80f
            else -> height * 0.65f // "medium" covers ~65% screen height
        }
        val durationMs = when (distance.lowercase(Locale.ROOT)) {
            "short", "small" -> 280L
            "long", "large" -> 380L
            else -> 320L
        }

        val (startX, startY, endX, endY) = when (direction.lowercase(Locale.ROOT)) {
            "down" -> listOf(width * 0.5f, height * 0.78f, width * 0.5f, (height * 0.78f - deltaY).coerceAtLeast(height * 0.12f))
            "up" -> listOf(width * 0.5f, height * 0.22f, width * 0.5f, (height * 0.22f + deltaY).coerceAtMost(height * 0.88f))
            "left" -> listOf(width * 0.80f, height * 0.5f, width * 0.20f, height * 0.5f)
            "right" -> listOf(width * 0.20f, height * 0.5f, width * 0.80f, height * 0.5f)
            else -> listOf(width * 0.5f, height * 0.78f, width * 0.5f, (height * 0.78f - deltaY).coerceAtLeast(height * 0.12f))
        }

        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.i(TAG, "Dispatched smart scroll $direction gesture (distance=$distance, deltaY=$deltaY, duration=${durationMs}ms): $dispatched")
            return dispatched
        } catch (e: Exception) {
            Log.e(TAG, "Scroll gesture failed: ${e.message}")
        }
        return false
    }

    /**
     * Dedicated high-velocity vertical flick gesture for short-video feeds (Instagram Reels, YouTube Shorts, etc.).
     * Operates strictly in the center column (X=50%) between Y=78% and Y=18% (200ms flick), staying safely
     * above Android's bottom gesture navigation threshold (Y=88%+) to prevent accidental home navigation.
     */
    fun performReelSwipe(upward: Boolean = true): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val startX = width * 0.5f
        val endX = width * 0.5f
        val (startY, endY) = if (upward) {
            Pair(height * 0.78f, height * 0.18f)
        } else {
            Pair(height * 0.22f, height * 0.82f)
        }

        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 200L))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.i(TAG, "performReelSwipe (upward=$upward, startY=$startY -> endY=$endY): $dispatched")
            dispatched
        } catch (e: Exception) {
            Log.e(TAG, "performReelSwipe failed: ${e.message}", e)
            false
        }
    }

    // Continuous auto-scroll engine
    @Volatile
    private var continuousScrollJob: Job? = null
    val isContinuousScrolling: Boolean get() = continuousScrollJob?.isActive == true

    fun startContinuousScroll(
        direction: String = "down",
        speed: String = "normal",
        onStopped: ((reason: String) -> Unit)? = null
    ): Boolean {
        stopContinuousScroll("Starting new continuous scroll session")
        val initialPkg = getActiveRootNode()?.packageName?.toString() ?: ""

        val scrollIntervalMs = when (speed.lowercase(Locale.ROOT)) {
            "slow" -> 2200L
            "fast" -> 900L
            else -> 1500L
        }
        val scrollDistance = when (speed.lowercase(Locale.ROOT)) {
            "slow" -> "small"
            "fast" -> "large"
            else -> "medium"
        }

        continuousScrollJob = CoroutineScope(Dispatchers.IO).launch {
            var lastScreenSignature = ""
            var duplicateCount = 0
            var totalScrolls = 0
            val maxScrolls = 40

            try {
                while (isActive && totalScrolls < maxScrolls) {
                    val currentPkg = getActiveRootNode()?.packageName?.toString() ?: ""
                    if (initialPkg.isNotEmpty() && currentPkg != initialPkg && !currentPkg.contains("systemui") && !currentPkg.contains("launcher")) {
                        Log.i(TAG, "Continuous scroll auto-stopped: App switched from $initialPkg to $currentPkg")
                        onStopped?.invoke("App switched to $currentPkg")
                        break
                    }

                    val ok = performSmartScroll(direction, scrollDistance)
                    if (!ok) {
                        Log.w(TAG, "Continuous scroll gesture dispatch failed, stopping.")
                        break
                    }
                    totalScrolls++

                    delay(scrollIntervalMs)

                    val root = getActiveRootNode()
                    val sig = root?.let { ScreenSignatureCalculator.computeSignature(it).toString() } ?: ""
                    if (sig.isNotEmpty() && sig == lastScreenSignature) {
                        duplicateCount++
                        if (duplicateCount >= 2) {
                            Log.i(TAG, "Continuous scroll auto-stopped: End of screen reached")
                            onStopped?.invoke("End of screen reached")
                            break
                        }
                    } else {
                        duplicateCount = 0
                        lastScreenSignature = sig
                    }
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "Continuous scroll cancelled normally")
            } catch (e: Exception) {
                Log.e(TAG, "Continuous scroll loop error: ${e.message}")
            } finally {
                continuousScrollJob = null
            }
        }
        return true
    }

    fun stopContinuousScroll(reason: String = "user_stopped"): Boolean {
        val wasActive = continuousScrollJob?.isActive == true
        continuousScrollJob?.cancel()
        continuousScrollJob = null
        Log.i(TAG, "Continuous scroll stopped (wasActive=$wasActive, reason=$reason)")
        return wasActive
    }

    data class DirectInjectionResult(
        val status: String,    // "SUCCESS" or "FAILED"
        val method: String,    // "SET_TEXT" or "CLIPBOARD_PASTE" or "NONE"
        val submitted: Boolean,
        val reason: String
    ) {
        val isSuccess: Boolean get() = status == "SUCCESS"
    }

    suspend fun directInjectText(
        textToType: String,
        targetRect: Rect? = null,
        targetInputName: String? = null,
        submit: Boolean = false,
        clearExisting: Boolean = true
    ): DirectInjectionResult {
        lastTypeVerified = false
        if (textToType.isEmpty()) {
            Log.w(TAG, "directInjectText: refused empty text")
            return DirectInjectionResult(status = "FAILED", method = "NONE", submitted = false, reason = "Empty text to inject")
        }

        Log.i(TAG, "directInjectText starting: len=${textToType.length}, targetRect=${targetRect != null}, targetInput='$targetInputName', submit=$submit, clearExisting=$clearExisting")

        fun getAppRoots(): List<AccessibilityNodeInfo> {
            val myPackage = packageName
            val appWindows = try {
                windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .mapNotNull { runCatching { it.root }.getOrNull() }
                    .filter { it.packageName?.toString() != myPackage }
            } catch (_: Exception) { emptyList() }
            if (appWindows.isNotEmpty()) return appWindows
            val all = getAllRootNodes().filter { it.packageName?.toString() != myPackage }
            if (all.isNotEmpty()) return all
            return listOfNotNull(getActiveRootNode())
        }

        // ==========================================
        // STAGE 1: Smart Target Resolution & Two-Step Focus (Handles Fake Search Bars)
        // ==========================================
        var roots = getAppRoots()

        fun findCandidate(): AccessibilityNodeInfo? {
            // 1. Explicit target rect
            if (targetRect != null && targetRect.width() > 0) {
                for (r in roots) {
                    var node = findNodeCoveringRect(r, targetRect)
                    while (node != null) {
                        if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) return node
                        node = node.parent
                    }
                }
            }
            // 2. Explicit target name/hint
            if (!targetInputName.isNullOrBlank()) {
                val clean = targetInputName.lowercase(Locale.ROOT).trim()
                for (r in roots) {
                    val queue = ArrayDeque<AccessibilityNodeInfo>()
                    queue.add(r)
                    while (!queue.isEmpty()) {
                        val n = queue.removeFirst()
                        if (n.isEditable || n.className?.toString()?.contains("EditText", true) == true) {
                            val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                            val t = n.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty() else ""
                            val id = n.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                            if (d.contains(clean) || t.contains(clean) || h.contains(clean) || id.contains(clean)) return n
                        }
                        for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                    }
                }
            }
            // 3. Current input focus
            for (r in roots) {
                r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable || it.className?.toString()?.contains("EditText", true) == true) return it }
                findNodeWithInputFocus(r)?.let { if (it.isEditable || it.className?.toString()?.contains("EditText", true) == true) return it }
            }
            // 4. Any visible editable node
            for (r in roots) {
                findFirstEditableNode(r)?.let { return it }
            }
            return null
        }

        var candidate = findCandidate()

        // Handle Fake Search Bars (e.g. YouTube, Play Store, Amazon, Swiggy, Chrome New Tab)
        if (candidate == null) {
            Log.i(TAG, "No editable field immediately found. Scanning for fake search bar / placeholder container...")
            val placeholder = findFakeSearchBarPlaceholder(roots, targetInputName)
            if (placeholder != null) {
                val b = Rect()
                placeholder.getBoundsInScreen(b)
                Log.i(TAG, "Found fake search bar placeholder (${placeholder.text} / ${placeholder.contentDescription} / ${placeholder.viewIdResourceName}). Tapping to activate real input...")
                val clicked = placeholder.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked && b.width() > 0) {
                    clickAt(b.exactCenterX(), b.exactCenterY(), 80L)
                }
                delay(300) // Wait for search view / real editor to animate in
                roots = getAppRoots()
                candidate = findCandidate()
            }
        }

        // If targetRect was provided but no candidate found, tap targetRect center once to focus
        if (candidate == null && targetRect != null && targetRect.width() > 0) {
            clickAt(targetRect.exactCenterX(), targetRect.exactCenterY(), 80L)
            delay(280)
            roots = getAppRoots()
            candidate = findCandidate()
        }

        if (candidate == null) {
            Log.w(TAG, "directInjectText: FAILED - No editable input target found across any window")
            return DirectInjectionResult(
                status = "FAILED",
                method = "NONE",
                submitted = false,
                reason = "No editable input target found on screen"
            )
        }

        // Two-step focus activation
        candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val b = Rect()
            candidate.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0) {
                clickAt(b.exactCenterX(), b.exactCenterY(), 80L)
            }
        }
        delay(250) // Wait for focus & IME to settle

        // Re-query true focused input node (in case tapping wrapper focused inner child)
        roots = getAppRoots()
        val focusedNode = roots.firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        val targetNode = focusedNode ?: candidate

        // ==========================================
        // STAGE 2: Clean Existing Text Handling
        // ==========================================
        val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetNode.isShowingHintText || (targetNode.text != null && targetNode.hintText != null && targetNode.text?.toString() == targetNode.hintText?.toString())
        } else {
            false
        }
        val realExistingText = if (isHint) "" else targetNode.text?.toString().orEmpty()
        val existingLength = realExistingText.length

        if (clearExisting && existingLength > 0) {
            try {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                })
            } catch (_: Exception) {}
        }

        val textToSet = if (clearExisting) textToType else (realExistingText + textToType)

        // ==========================================
        // STAGE 3: Multi-Tier Pure Direct Injection Fallback Chain
        // ==========================================
        var injectionMethod = "NONE"
        var injectionVerified = false

        // Tier 1: ACTION_SET_TEXT
        try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
            }
            val tier1Ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (tier1Ok) {
                delay(120)
                targetNode.refresh()
                val readback = targetNode.text?.toString().orEmpty()
                if (readback.contains(textToType) || (clearExisting && readback.trim() == textToType.trim())) {
                    injectionVerified = true
                    injectionMethod = "SET_TEXT"
                    Log.i(TAG, "directInjectText: Tier 1 (ACTION_SET_TEXT) succeeded and verified.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "directInjectText: Tier 1 exception: ${e.message}")
        }

        // Tier 2: Safe Clipboard-Preserving ACTION_PASTE (Universal Compatibility)
        if (!injectionVerified) {
            Log.i(TAG, "directInjectText: Tier 1 unverified, falling back to Tier 2 (Clipboard ACTION_PASTE)")
            var previousClip: ClipData? = null
            var clipboard: ClipboardManager? = null

            try {
                withContext(Dispatchers.Main.immediate) {
                    clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    previousClip = runCatching { clipboard?.primaryClip }.getOrNull()
                    clipboard?.setPrimaryClip(ClipData.newPlainText("AuraDirectInjection", textToType))
                }

                if (clearExisting && existingLength > 0) {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLength)
                    })
                }

                var pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                        targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)

                if (!pasted) {
                    // Tap once to ensure focus and retry paste
                    val b = Rect()
                    targetNode.getBoundsInScreen(b)
                    if (b.width() > 0) clickAt(b.exactCenterX(), b.exactCenterY(), 60L)
                    delay(120)
                    pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                            targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)
                }

                delay(180)
                targetNode.refresh()
                val readback = targetNode.text?.toString().orEmpty()
                if (pasted || readback.contains(textToType)) {
                    injectionVerified = true
                    injectionMethod = "CLIPBOARD_PASTE"
                    Log.i(TAG, "directInjectText: Tier 2 (ACTION_PASTE) succeeded (readback len=${readback.length}).")
                }
            } catch (e: Exception) {
                Log.w(TAG, "directInjectText: Tier 2 exception: ${e.message}")
            } finally {
                // Restore user's previous clipboard content asynchronously after 800ms
                CoroutineScope(Dispatchers.Main).launch {
                    delay(800L)
                    try {
                        if (previousClip != null) {
                            clipboard?.setPrimaryClip(previousClip)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard?.clearPrimaryClip()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Tier 3: Child / Descendant Recovery (if wrapper node)
        if (!injectionVerified && targetNode.childCount > 0) {
            Log.i(TAG, "directInjectText: Searching child descendants for inner editable...")
            val inner = findFirstEditableNode(targetNode) ?: targetNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inner != null && inner != targetNode) {
                try {
                    val innerOk = inner.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
                    })
                    if (innerOk) {
                        delay(120)
                        inner.refresh()
                        if (inner.text?.toString().orEmpty().contains(textToType)) {
                            injectionVerified = true
                            injectionMethod = "SET_TEXT"
                            Log.i(TAG, "directInjectText: Tier 3 inner child SET_TEXT succeeded.")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // ==========================================
        // STAGE 4: Optional Direct Submit (Zero Keyboard Tapping)
        // ==========================================
        var submitted = false
        if (submit && injectionVerified) {
            delay(200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                val sendBtn = findSendOrSubmitButtonAcrossRoots()
                if (sendBtn != null) {
                    val clickOk = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clickOk) {
                        val b = Rect()
                        sendBtn.getBoundsInScreen(b)
                        if (b.width() > 0) clickAt(b.exactCenterX(), b.exactCenterY(), 60L)
                    }
                    submitted = true
                    Log.i(TAG, "directInjectText: Clicked Send / Submit button directly.")
                }
            }
            delay(350)
        }

        // ==========================================
        // STAGE 5: Honest Verification & Structured Result
        // ==========================================
        lastTypeVerified = injectionVerified
        val finalStatus = if (injectionVerified) "SUCCESS" else "FAILED"
        val reason = if (injectionVerified) {
            if (submitted) "Text injected via $injectionMethod and submitted" else "Text injected via $injectionMethod"
        } else {
            "Target input field did not accept direct text injection"
        }

        Log.i(TAG, "directInjectText result: status=$finalStatus, method=$injectionMethod, submitted=$submitted, reason='$reason'")

        return DirectInjectionResult(
            status = finalStatus,
            method = injectionMethod,
            submitted = submitted,
            reason = reason
        )
    }

    suspend fun smartTypeText(
        textToType: String,
        targetRect: Rect? = null,
        submit: Boolean = false,
        clearExisting: Boolean = true
    ): Boolean {
        val result = directInjectText(
            textToType = textToType,
            targetRect = targetRect,
            targetInputName = null,
            submit = submit,
            clearExisting = clearExisting
        )
        return result.isSuccess
    }

    private fun findFakeSearchBarPlaceholder(roots: List<AccessibilityNodeInfo>, hintOrLabel: String?): AccessibilityNodeInfo? {
        val keywords = listOf("search", "find", "query", "ask", "type", "enter", "search here", "khojo")
        val cleanHint = hintOrLabel?.lowercase(Locale.ROOT)?.trim().orEmpty()

        for (r in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(r)
            while (!queue.isEmpty()) {
                val node = queue.removeFirst()
                val isClickable = node.isClickable || node.parent?.isClickable == true
                if (isClickable && !node.isEditable && node.className?.toString()?.contains("EditText", true) != true) {
                    val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()

                    val matchesHint = cleanHint.isNotEmpty() && (desc.contains(cleanHint) || text.contains(cleanHint) || id.contains(cleanHint))
                    val matchesKeyword = keywords.any { kw -> desc.contains(kw) || text.contains(kw) || id.contains(kw) }

                    val isMic = desc.contains("voice") || desc.contains("mic") || id.contains("voice")

                    if (!isMic && (matchesHint || matchesKeyword)) {
                        return if (node.isClickable) node else (node.parent?.takeIf { it.isClickable } ?: node)
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    fun findSendOrSubmitButtonAcrossRoots(): AccessibilityNodeInfo? {
        val roots = getAllRootNodes().filter { it.packageName != packageName }
        for (r in roots) {
            val btn = findSendOrSubmitButton(r)
            if (btn != null) return btn
        }
        return null
    }

    private fun findSendOrSubmitButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        val isMic = desc.contains("voice") || desc.contains("mic") || desc.contains("record") || id.contains("voice_note")
        if (isMic) return null

        val isSendOrSubmit = desc == "send" || desc == "send message" || desc == "search" || desc == "go" ||
                desc == "submit" || desc == "done" || desc == "post" || desc.contains("bhejo") ||
                text == "send" || text == "search" || text == "go" || text == "submit" || text == "done" || text == "post" ||
                id.endsWith(":id/send") || id.contains("send_button") || id.contains("btn_send") ||
                id.contains("compose_send") || id.contains("search_button") || id.contains("btn_search")

        if (isSendOrSubmit && (node.isClickable || node.parent?.isClickable == true || node.isVisibleToUser)) {
            return if (node.isClickable) node else (node.parent?.takeIf { it.isClickable } ?: node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendOrSubmitButton(child)
            if (found != null) return found
        }
        return null
    }

    fun findSendButtonAcrossRoots(): AccessibilityNodeInfo? = findSendOrSubmitButtonAcrossRoots()

    fun performSystemGesture(gesture: String): Boolean {
        val clean = gesture.lowercase(Locale.ROOT).trim()
        return when (clean) {
            "home" -> {
                var ok = performGlobalAction(GLOBAL_ACTION_HOME)
                if (!ok) {
                    try {
                        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                            addCategory(android.content.Intent.CATEGORY_HOME)
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        ok = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Home intent fallback failed: ${e.message}")
                    }
                }
                ok
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    fun pauseMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
            Log.i(TAG, "pauseMediaPlayback: dispatched KEYCODE_MEDIA_PAUSE")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause media playback: ${e.message}")
        }
    }

    data class RecentsActionResult(
        val status: String, // "SUCCESS", "PARTIAL", "FAILED"
        val message: String,
        val clearedCount: Int = 0,
        val remainingCardsCount: Int = 0
    ) {
        val isSuccess: Boolean get() = status == "SUCCESS"
    }

    data class VisibleTaskCard(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val title: String,
        val isLocked: Boolean,
        val isAura: Boolean
    )

    fun getAllRootNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (_: Exception) {}
        try {
            for (window in windows) {
                try {
                    window.root?.let { r ->
                        if (!roots.any { it == r }) {
                            roots.add(r)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) {
            try {
                getActiveRootNode()?.let { roots.add(it) }
            } catch (_: Exception) {}
        }
        return roots
    }

    private fun collectAllNodes(
        node: AccessibilityNodeInfo?,
        list: MutableList<AccessibilityNodeInfo>,
        maxNodes: Int = 500,
        depth: Int = 0
    ) {
        if (node == null || list.size >= maxNodes || depth > 30) return
        list.add(node)
        for (i in 0 until node.childCount) {
            if (list.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list, maxNodes, depth + 1)
        }
    }

    private fun isRecentsOrLauncherOnScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        if (roots.isEmpty()) return false
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            collectAllNodes(root, allNodes, maxNodes = 120)
        }
        for (node in allNodes) {
            val pkg = node.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val txt = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            if (pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("recents") || pkg.contains("quickstep")) {
                if (id.contains("recents") || id.contains("task") || id.contains("clear") || id.contains("snapshot") ||
                    txt.contains("clear") || desc.contains("clear") || txt.contains("no recent") || desc.contains("no recent")) {
                    return true
                }
            }
        }
        for (root in roots) {
            val pkg = root.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("recents") || pkg.contains("quickstep")) {
                return true
            }
        }
        return false
    }

    private suspend fun openRecentsAndWait(): Boolean {
        val opened = performGlobalAction(GLOBAL_ACTION_RECENTS)
        if (!opened) {
            Log.w(TAG, "GLOBAL_ACTION_RECENTS refused by system")
            return false
        }

        // Poll every 150ms up to 1500ms for Recents window to appear
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1500) {
            delay(150)
            val roots = getAllRootNodes()
            if (isRecentsOrLauncherOnScreen(roots)) {
                break
            }
        }
        // Wait 300ms settling delay for task card entry animations to finish
        delay(300)
        return true
    }

    private fun findClearAllButtonAcrossWindows(): AccessibilityNodeInfo? {
        val roots = getAllRootNodes()
        if (roots.isEmpty()) return null

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val tier1Ids = listOf(
            "clear_all", "clearanimview", "clear_button", "btn_clear", "dismiss_all",
            "clean_all", "recents_clear", "clear_recents", "close_all", "button_clear",
            "clearall", "clear_tasks", "clear_all_button", "clear_all_recents", "recent_clear",
            "recents_clear_all", "dismiss_task", "button_clear_all"
        )

        val tier2Phrases = listOf(
            "clear all", "close all", "dismiss all", "remove all",
            "clear recent", "clean all", "clear memory"
        )

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            collectAllNodes(root, allNodes, maxNodes = 500)
        }

        // Tier 1 — Resource ID Match (case-insensitive substring)
        for (node in allNodes) {
            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            if (viewId.isNotEmpty() && tier1Ids.any { viewId.contains(it) }) {
                Log.i(TAG, "findClearAllButton: Tier 1 match on viewId: $viewId")
                return if (node.isClickable) node else (node.parent?.takeIf { it.isClickable } ?: node)
            }
        }

        // Tier 2 — Text or ContentDescription Match (case-insensitive)
        for (node in allNodes) {
            val text = node.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val matched = tier2Phrases.any { text.contains(it) || desc.contains(it) } ||
                    text == "clear" || desc == "clear" || text == "clean" || desc == "clean"
            if (matched) {
                Log.i(TAG, "findClearAllButton: Tier 2 match on text='$text', desc='$desc'")
                return if (node.isClickable) node else (node.parent?.takeIf { it.isClickable } ?: node)
            }
        }

        // Tier 3 — Geometric Fallback for Icon-Only Circular X Button
        // Bottom-center area:
        // centerX: 35%..65% of screen width
        // centerY: 80%..96% of screen height
        // Bounds: width & height < 25% of screen width, >= 24dp
        val minX = (w * 0.35f).toInt()
        val maxX = (w * 0.65f).toInt()
        val minY = (h * 0.80f).toInt()
        val maxY = (h * 0.96f).toInt()
        val maxDim = (w * 0.25f).toInt()
        val minDim = (24 * dm.density).toInt()

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val targetCx = w / 2
        val targetCy = (h * 0.88f).toInt()

        for (node in allNodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.centerX() in minX..maxX && rect.centerY() in minY..maxY &&
                rect.width() in minDim..maxDim && rect.height() in minDim..maxDim) {
                val pkg = node.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
                val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                val isLauncherOrSys = pkg.contains("launcher") || pkg.contains("systemui") ||
                        pkg.contains("recents") || pkg.contains("overview") || pkg.isEmpty()
                val isInteractive = node.isClickable || node.parent?.isClickable == true ||
                        cls.contains("image") || cls.contains("button") || cls.contains("view")

                if (isLauncherOrSys && isInteractive) {
                    candidates.add(node to rect)
                }
            }
        }

        if (candidates.isNotEmpty()) {
            val best = candidates.minByOrNull { (_, rect) ->
                val dx = rect.centerX() - targetCx
                val dy = rect.centerY() - targetCy
                dx * dx + dy * dy
            }
            if (best != null) {
                Log.i(TAG, "findClearAllButton: Tier 3 geometric match at ${best.second}")
                return best.first
            }
        }

        return null
    }

    private suspend fun clickClearAllButton(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()

        var clicked = false
        if (node.isClickable) {
            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (node.parent?.isClickable == true) {
            clicked = node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        delay(500)
        // If ACTION_CLICK returned false or recents cards are still present after 500ms, use gesture tap
        if (!clicked || isRecentsWithCardsStillOpen()) {
            Log.i(TAG, "clickClearAllButton: Falling back to 60ms gesture tap at ($cx, $cy)")
            clickAt(cx, cy, durationMs = 60L)
            delay(500)
        }
    }

    fun findVisibleTaskCards(): List<VisibleTaskCard> {
        val roots = getAllRootNodes()
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            collectAllNodes(root, allNodes, maxNodes = 500)
        }

        val cards = mutableListOf<VisibleTaskCard>()
        val seenBounds = mutableSetOf<String>()

        for (node in allNodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val nw = rect.width()
            val nh = rect.height()
            val cy = rect.centerY()

            val isCardSized = nw > w * 0.35f && nh > h * 0.18f && cy in (h * 0.15f).toInt()..(h * 0.82f).toInt()
            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val isTaskId = viewId.contains("task_view") || viewId.contains("snapshot") ||
                    viewId.contains("taskview") || viewId.contains("card") || viewId.contains("recents_view")

            if (isCardSized || isTaskId) {
                val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (seenBounds.add(key)) {
                    val cardSubNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodes(node, cardSubNodes, maxNodes = 50)

                    var cardTitle = ""
                    var isLocked = false
                    var isAura = false

                    for (sub in cardSubNodes) {
                        val text = sub.text?.toString() ?: ""
                        val desc = sub.contentDescription?.toString() ?: ""
                        val subId = sub.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                        if (cardTitle.isBlank() && text.isNotBlank() && text.length < 40 && !text.contains("clear", true)) {
                            cardTitle = text
                        } else if (cardTitle.isBlank() && desc.isNotBlank() && desc.length < 40 && !desc.contains("clear", true)) {
                            cardTitle = desc
                        }

                        if (desc.contains("lock", true) || desc.contains("padlock", true) ||
                            subId.contains("lock") || subId.contains("padlock")) {
                            isLocked = true
                        }

                        val pkg = sub.packageName?.toString() ?: ""
                        if (pkg == packageName || text.contains("aura", true) || desc.contains("aura", true)) {
                            isAura = true
                        }
                    }

                    val nodePkg = node.packageName?.toString() ?: ""
                    if (nodePkg == packageName) isAura = true

                    cards.add(VisibleTaskCard(node, rect, cardTitle, isLocked, isAura))
                }
            }
        }

        return cards
    }

    private fun isRecentsWithCardsStillOpen(): Boolean {
        val roots = getAllRootNodes()
        if (roots.isEmpty()) return false

        for (root in roots) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, allNodes, maxNodes = 200)
            for (node in allNodes) {
                val t = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (t.contains("no recent") || d.contains("no recent") ||
                    t.contains("no items") || d.contains("no items") ||
                    t.contains("no apps") || d.contains("no apps")) {
                    return false
                }
            }
        }

        val cards = findVisibleTaskCards()
        return cards.isNotEmpty()
    }

    private fun findNodeMatchingTextAcrossRoots(cleanQuery: String): AccessibilityNodeInfo? {
        val roots = getAllRootNodes()
        for (root in roots) {
            val match = findNodeMatchingText(root, cleanQuery)
            if (match != null) return match
        }
        return null
    }

    private fun findNodeMatchingText(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        node ?: return null
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val clean = query.lowercase(Locale.ROOT).trim()

        if (text.contains(clean) || desc.contains(clean)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatchingText(child, query)
            if (found != null) return found
        }
        return null
    }

    suspend fun executeRecentsAction(
        appName: String? = null,
        clearAll: Boolean = false,
        mode: String = "clear_all" // "clear_all", "swipe_clear_all", "close_specific_app", "close_current_app"
    ): RecentsActionResult {
        val foregroundPkgBefore = getActiveRootNode()?.packageName?.toString() ?: ""
        val opened = openRecentsAndWait()
        if (!opened) {
            return RecentsActionResult("FAILED", "Could not open Android Recents screen.")
        }

        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val isCloseCurrent = mode == "close_current_app"
        val isSwipeAllOnly = mode == "swipe_clear_all"
        val isSingleApp = (!appName.isNullOrBlank() || mode == "close_specific_app") && !isCloseCurrent

        if (isCloseCurrent) {
            Log.i(TAG, "executeRecentsAction: Running close_current_app (targetPackage: $foregroundPkgBefore)")
            val cards = findVisibleTaskCards()
            val targetCard = cards.firstOrNull { it.node.packageName?.toString() == foregroundPkgBefore && !it.isAura }
                ?: cards.firstOrNull { !it.isAura }

            if (targetCard != null) {
                dismissCardUpward(targetCard.node, targetCard.bounds, w, h)
                delay(400)
                // Verify post-swipe: check if app accidentally reopened
                val currentPkg = getActiveRootNode()?.packageName?.toString() ?: ""
                if (foregroundPkgBefore.isNotBlank() && currentPkg == foregroundPkgBefore) {
                    Log.w(TAG, "App $foregroundPkgBefore was accidentally reopened during recents dismissal! Retrying with steeper upward flick...")
                    openRecentsAndWait()
                    val steeperPath = Path().apply {
                        moveTo(w * 0.5f, h * 0.68f)
                        lineTo(w * 0.5f, h * 0.05f)
                    }
                    gesture(steeperPath, 130L)
                    delay(350)
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                return RecentsActionResult("SUCCESS", "Closed foreground app ($foregroundPkgBefore) and returned home.")
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return RecentsActionResult("SUCCESS", "Closed foreground app and returned home.")
            }
        }

        if (isSwipeAllOnly) {
            // Mode 1: swipe_clear_all (No cross button attempt, swipe all cards upward one-by-one)
            Log.i(TAG, "executeRecentsAction: Running swipe_clear_all mode")
            swipeAllUnlockedCardsUpward(w, h, maxSwipes = 12)
            return verifyRecentsState(wasClearAll = true)
        }

        if (isSingleApp) {
            // Mode 2: close_specific_app (Single app swipe removal)
            val targetName = appName ?: "app"
            Log.i(TAG, "executeRecentsAction: Running close_specific_app for '$targetName'")
            closeSpecificAppFromRecents(targetName, w, h)
            return verifyRecentsState(targetAppName = targetName, wasClearAll = false)
        }

        // Mode 3: clear_all (Smart Clear with Cross button and automatic Swipe fallback)
        if (clearAll) {
            val clearButton = findClearAllButtonAcrossWindows()
            if (clearButton != null) {
                Log.i(TAG, "Clear All button found, clicking...")
                clickClearAllButton(clearButton)
                val verification = verifyRecentsState(wasClearAll = true)
                if (verification.status == "SUCCESS" || verification.status == "PARTIAL") {
                    return verification
                }
                Log.w(TAG, "Clear button clicked but cards still present; falling back to upward swipe...")
            }

            // Swipe Fallback: Swipe visible unlocked cards upward one-by-one
            swipeAllUnlockedCardsUpward(w, h, maxSwipes = 12)
            return verifyRecentsState(wasClearAll = true)
        }

        // Generic close top recent app
        val topCard = findVisibleTaskCards().firstOrNull { !it.isAura }
        if (topCard != null) {
            dismissCardUpward(topCard.node, topCard.bounds, w, h)
            delay(450)
        }
        return verifyRecentsState(wasClearAll = false)
    }

    private suspend fun swipeAllUnlockedCardsUpward(w: Float, h: Float, maxSwipes: Int = 12) {
        var consecutiveUnchanged = 0
        var lastCardSignature = ""

        for (attempt in 0 until maxSwipes) {
            if (!isRecentsWithCardsStillOpen()) break

            val visibleCards = findVisibleTaskCards()
            if (visibleCards.isEmpty()) break

            // Check if top card is Aura's own card
            val topCard = visibleCards.firstOrNull()
            if (topCard != null && topCard.isAura) {
                Log.i(TAG, "Aura app card is centered in recents. Swiping horizontally to preserve active session.")
                // Swipe horizontally to shift Aura away
                val shiftPath = Path().apply {
                    moveTo(w * 0.25f, h * 0.50f)
                    lineTo(w * 0.75f, h * 0.50f)
                }
                gesture(shiftPath, 280L)
                delay(350)
                continue
            }

            val targetCard = visibleCards.firstOrNull { !it.isLocked && !it.isAura }
                ?: visibleCards.firstOrNull { !it.isLocked }
                ?: break // Only locked cards remaining!

            val cardSig = "${targetCard.title}_${targetCard.bounds}"
            if (cardSig == lastCardSignature) {
                consecutiveUnchanged++
                if (consecutiveUnchanged >= 2) {
                    Log.w(TAG, "Card remained unchanged after 2 swipe attempts; breaking swipe loop.")
                    break
                }
            } else {
                consecutiveUnchanged = 0
                lastCardSignature = cardSig
            }

            dismissCardUpward(targetCard.node, targetCard.bounds, w, h)
            delay(450)
        }
    }

    private suspend fun closeSpecificAppFromRecents(appName: String, w: Float, h: Float) {
        val cleanTarget = appName.trim().lowercase(Locale.ROOT)
        var targetNode = findNodeMatchingTextAcrossRoots(cleanTarget)
        var horizontalSwipes = 0

        while (horizontalSwipes < 8) {
            if (targetNode != null) {
                val rect = Rect()
                targetNode.getBoundsInScreen(rect)
                val cx = rect.centerX().toFloat()
                if (cx in (w * 0.25f)..(w * 0.75f)) {
                    break
                } else if (cx < w * 0.25f) {
                    val path = Path().apply {
                        moveTo(w * 0.25f, h * 0.50f)
                        lineTo(w * 0.75f, h * 0.50f)
                    }
                    gesture(path, 280L)
                } else {
                    val path = Path().apply {
                        moveTo(w * 0.75f, h * 0.50f)
                        lineTo(w * 0.25f, h * 0.50f)
                    }
                    gesture(path, 280L)
                }
            } else {
                val path = Path().apply {
                    if (horizontalSwipes < 4) {
                        moveTo(w * 0.80f, h * 0.50f)
                        lineTo(w * 0.20f, h * 0.50f)
                    } else {
                        moveTo(w * 0.20f, h * 0.50f)
                        lineTo(w * 0.80f, h * 0.50f)
                    }
                }
                gesture(path, 280L)
            }
            delay(350)
            targetNode = findNodeMatchingTextAcrossRoots(cleanTarget)
            horizontalSwipes++
        }

        val cardNode = targetNode ?: findVisibleTaskCards().firstOrNull { it.title.lowercase(Locale.ROOT).contains(cleanTarget) }?.node
        if (cardNode != null) {
            val rect = Rect()
            cardNode.getBoundsInScreen(rect)
            dismissCardUpward(cardNode, rect, w, h)
            delay(450)
        } else {
            val visible = findVisibleTaskCards().firstOrNull { !it.isAura }
            if (visible != null) {
                dismissCardUpward(visible.node, visible.bounds, w, h)
                delay(450)
            }
        }
    }

    private suspend fun dismissCardUpward(node: AccessibilityNodeInfo, bounds: Rect, w: Float, h: Float) {
        val startX = bounds.centerX().toFloat().coerceIn(w * 0.35f, w * 0.65f)
        val startY = h * 0.62f
        val endY = h * 0.08f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        // Decisive high-velocity upward fling (150ms) to ensure the Android launcher dismisses the task card
        gesture(path, 150L)
    }

    private suspend fun verifyRecentsState(targetAppName: String? = null, wasClearAll: Boolean = false): RecentsActionResult {
        delay(600)

        val roots = getAllRootNodes()
        if (roots.isEmpty()) {
            return RecentsActionResult("SUCCESS", "Recents closed and screen settled.")
        }

        var hasEmptyRecentsText = false
        for (root in roots) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, allNodes, maxNodes = 200)
            for (node in allNodes) {
                val t = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (t.contains("no recent") || d.contains("no recent") ||
                    t.contains("no items") || d.contains("no items") ||
                    t.contains("no apps") || d.contains("no apps")) {
                    hasEmptyRecentsText = true
                    break
                }
            }
            if (hasEmptyRecentsText) break
        }

        val cards = findVisibleTaskCards()
        val cardsStillOpen = isRecentsWithCardsStillOpen()

        if (wasClearAll) {
            if (hasEmptyRecentsText || !cardsStillOpen || cards.isEmpty()) {
                return RecentsActionResult("SUCCESS", "All recent apps cleared successfully.")
            }
            val lockedCards = cards.filter { it.isLocked }
            if (lockedCards.isNotEmpty()) {
                return RecentsActionResult(
                    status = "PARTIAL",
                    message = "Cleared unlocked apps, but ${lockedCards.size} locked app(s) remain in recents.",
                    remainingCardsCount = cards.size
                )
            }
            return RecentsActionResult(
                status = "FAILED",
                message = "Recents opened, but apps could not be cleared. Cards are still visible on screen.",
                remainingCardsCount = cards.size
            )
        }

        if (!targetAppName.isNullOrBlank()) {
            val cleanTarget = targetAppName.trim().lowercase(Locale.ROOT)
            val matchFound = cards.any { it.title.lowercase(Locale.ROOT).contains(cleanTarget) } ||
                    findNodeMatchingTextAcrossRoots(cleanTarget) != null

            return if (!matchFound) {
                RecentsActionResult("SUCCESS", "Closed $targetAppName and removed from recents.")
            } else {
                RecentsActionResult(
                    status = "FAILED",
                    message = "Failed to close $targetAppName; card is still visible in recents.",
                    remainingCardsCount = cards.size
                )
            }
        }

        return if (!cardsStillOpen) {
            RecentsActionResult("SUCCESS", "Recents closed successfully.")
        } else {
            RecentsActionResult("FAILED", "Action completed but cards are still visible.", remainingCardsCount = cards.size)
        }
    }

    suspend fun dismissAppFromRecents(appName: String? = null, clearAll: Boolean = false): Boolean {
        return executeRecentsAction(appName, clearAll).isSuccess
    }

    fun performScroll(direction: String): Boolean {
        val root = getActiveRootNode() ?: return false
        val cleanDir = direction.lowercase(Locale.ROOT)

        // Tier 1: Search for actual scrollable container
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null) {
            val action = if (cleanDir == "up") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val ok = scrollableNode.performAction(action)
            if (ok) {
                Log.d(TAG, "performScroll succeeded via accessibility action on scrollable node ($cleanDir)")
                return true
            }
        }

        // Tier 2: Seamless hardware gesture swipe fallback (never fails)
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val distance = h * 0.40f

        val path = Path()
        if (cleanDir == "down") {
            path.moveTo(cx, cy + distance / 2)
            path.lineTo(cx, cy - distance / 2)
        } else {
            path.moveTo(cx, cy - distance / 2)
            path.lineTo(cx, cy + distance / 2)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val gestureDispatched = dispatchGesture(gesture, null, null)
        Log.d(TAG, "performScroll gesture swipe dispatched ($cleanDir): $gestureDispatched")
        return gestureDispatched
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (cls.contains("scroll") || cls.contains("recycler") || cls.contains("listview") || cls.contains("viewpager")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * WhatsApp message sending:
     * - Exactly 500ms delay after window opens to ensure text is fully attached in the input field.
     * - Direct Enter key event (ACTION_IME_ENTER / keyevent 66).
     * - Verified Send button check (strictly ensuring it is NOT the mic/voice note button).
     * - Zero blind retries to prevent accidental audio recording.
     */
    fun scheduleWhatsAppAutoSend() {
        mainHandler.postDelayed({
            val root = getActiveRootNode() ?: return@postDelayed
            val inputField = findEditableInputField(root)

            var sent = false
            // Step 1: Direct Enter injection on the focused editable text field
            if (inputField != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sent = inputField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (sent) {
                    Log.i(TAG, "Direct ACTION_IME_ENTER executed on WhatsApp input field.")
                }
            }

            // Step 2: If Enter was not handled, find verified Send button (with strict anti-mic check)
            if (!sent) {
                val sendNode = findVerifiedWhatsAppSendButton(root)
                if (sendNode != null) {
                    clickNodeAndTap(sendNode)
                    Log.i(TAG, "Clicked verified WhatsApp Send button.")
                    sent = true
                }
            }

            // Step 3: ADB keyevent 66 fallback if available
            if (!sent) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val adbDev = com.example.gemini.data.adb.AdbConnectManager.ensureConnected()
                        if (adbDev != null) {
                            com.example.gemini.data.adb.AdbConnectManager.deviceShell(adbDev, "input keyevent 66")
                            Log.i(TAG, "Dispatched ADB keyevent 66 (ENTER) for WhatsApp send.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ADB send fallback failed", e)
                    }
                }
            }
            // Finished. NO BLIND RETRIES so mic recording button is never triggered.
        }, 500)
    }

    private fun findEditableInputField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        if (viewId.endsWith(":id/entry") || viewId.contains("message_text")) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableInputField(child)
            if (found != null) return found
        }
        return null
    }

    private fun findVerifiedWhatsAppSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        // STRICT ANTI-MIC CHECK: Never click if it is a voice note / mic button!
        val isMic = desc.contains("voice") || desc.contains("mic") || desc.contains("record") ||
                desc.contains("audio") || viewId.contains("voice_note")
        if (isMic) return null

        val isSend = desc == "send" || desc == "send message" || text == "send" || viewId.endsWith(":id/send")
        if (isSend) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findVerifiedWhatsAppSendButton(child)
            if (found != null) return found
        }
        return null
    }

    fun scheduleWhatsAppCallAutoTap(callType: String) {
        val isVideo = callType.equals("video", ignoreCase = true)
        val keyword = if (isVideo) "video call" else "voice call"

        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = getActiveRootNode()
                if (root != null) {
                    val callButton = findWhatsAppCallButton(root, keyword)
                    if (callButton != null) {
                        clickNodeAndTap(callButton)
                        Log.d(TAG, "Tapped WhatsApp $callType call button via Accessibility on attempt $attempts!")
                        return
                    }
                }
                attempts++
                if (attempts < 12) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun findWhatsAppCallButton(node: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (desc.contains(keyword) || desc.contains("call")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWhatsAppCallButton(child, keyword)
            if (found != null) return found
        }
        return null
    }

    fun armYouTubePlay() {
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = getActiveRootNode()
                if (root != null) {
                    val videoViewIds = listOf(
                        "com.google.android.youtube:id/thumbnail",
                        "com.google.android.youtube:id/video_info",
                        "com.google.android.youtube:id/title",
                        "com.google.android.youtube:id/compact_renderer"
                    )
                    for (id in videoViewIds) {
                        val nodes = root.findAccessibilityNodeInfosByViewId(id)
                        if (nodes.isNotEmpty()) {
                            clickNodeAndTap(nodes[0])
                            Log.d(TAG, "Tapped first YouTube video via Accessibility on attempt $attempts!")
                            return
                        }
                    }
                }
                attempts++
                if (attempts < 12) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }, 800)
    }

    suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000))
        val dispatched = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    suspend fun awaitIdle(quietMs: Long = 350, maxMs: Long = 2_000) {
        val start = System.currentTimeMillis()
        delay(quietMs)
        while (System.currentTimeMillis() - lastEventAt < quietMs && System.currentTimeMillis() - start < maxMs) delay(80)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshot(): Result<Bitmap> {
        repeat(3) { attempt ->
            val result = suspendCancellableCoroutine<Result<Bitmap>> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (cont.isActive) cont.resume(bitmap?.let { Result.success(it) } ?: Result.failure(IllegalStateException("empty screenshot")))
                    }
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(Result.failure(ScreenshotError(errorCode)))
                    }
                })
            }
            val error = result.exceptionOrNull()
            if (error is ScreenshotError && error.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < 2) delay(1_100)
            else return result
        }
        return Result.failure(IllegalStateException("screenshot failed"))
    }

    class ScreenshotError(val code: Int) : Exception(
        when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "the app on screen blocks screenshots (secure window)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshots taken too fast"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no screenshot access"
            else -> "screenshot error $code"
        }
    )
}
