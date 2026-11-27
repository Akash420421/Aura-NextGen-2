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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.example.gemini.data.automation.agent.ScreenSignatureCalculator
import com.example.gemini.data.live.DeviceControlHelper
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
        private const val MAX_RECENT_NOTIFICATIONS = 20

        @Volatile var instance: AutomationAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackage = MutableStateFlow<String?>(null)
        val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

        data class ExtractedOtp(val code: String, val timestamp: Long, val senderPkg: String)
        @Volatile
        var latestExtractedOtp: ExtractedOtp? = null

        data class CapturedNotification(
            val packageName: String,
            val text: String,
            val timestamp: Long
        )

        private val notificationLock = Any()
        private val recentNotifications = ArrayDeque<CapturedNotification>()

        fun recordNotification(packageName: String, text: String) {
            val cleaned = text.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty()) return
            synchronized(notificationLock) {
                if (recentNotifications.size >= MAX_RECENT_NOTIFICATIONS) {
                    recentNotifications.removeFirst()
                }
                recentNotifications.addLast(
                    CapturedNotification(
                        packageName = packageName,
                        text = cleaned.take(500),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        fun getRecentNotifications(limit: Int = 10, maxAgeMs: Long = 300_000L): List<CapturedNotification> {
            val cutoff = System.currentTimeMillis() - maxAgeMs.coerceAtLeast(1_000L)
            synchronized(notificationLock) {
                return recentNotifications
                    .filter { it.timestamp >= cutoff }
                    .takeLast(limit.coerceIn(1, MAX_RECENT_NOTIFICATIONS))
                    .reversed()
            }
        }

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
        fun recordUiEvent(immediate: Boolean = false) {
            val now = System.currentTimeMillis()
            val minGap = if (immediate) 0L else 120L
            if (now - lastUiEventTimestamp >= minGap) {
                lastUiEventTimestamp = now
                _uiChanges.value = UiChangeSnapshot(_uiChanges.value.revision + 1, now)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var adSkipWatcherJob: Job? = null
    @Volatile var autoSkipYouTubeAdsEnabled: Boolean = true
    @Volatile private var lastEventAdCheckAtMs = 0L
    @Volatile private var lastAdSkipClickAtMs = 0L
    private val adSkipScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var lastEventAt = 0L
        private set
    @Volatile var lastMutationAt = 0L
        private set
    @Volatile var foregroundPackage: String? = null
        private set
    @Volatile var foregroundClass: String? = null
        private set
    @Volatile var lastTypeVerified: Boolean = false

    fun markUiMutation() {
        lastMutationAt = System.currentTimeMillis()
    }

    override fun onServiceConnected() {
        instance = this
        _isConnected.value = true
        Log.i(TAG, "AutomationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = System.currentTimeMillis()
        val isStructuralChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        recordUiEvent(immediate = isStructuralChange)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let {
                    foregroundPackage = it
                    _activePackage.value = it
                }
                event.className?.toString()?.let { foregroundClass = it }
                AuraScreenObserver.processAccessibilityEvent(event)
                maybeTriggerEventYouTubeAdSkip(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.packageName?.toString()?.let {
                    foregroundPackage = it
                    _activePackage.value = it
                }
                AuraScreenObserver.scheduleContentCheck(event.packageName?.toString())
                maybeTriggerEventYouTubeAdSkip(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                try {
                    val text = event.text?.joinToString(" ") { it?.toString().orEmpty() }?.trim().orEmpty()
                    val pkg = event.packageName?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        recordNotification(pkg, text)
                        val code = AccessibilityGestureMath.extractOtpCode(text)
                        if (code != null) {
                            latestExtractedOtp = ExtractedOtp(code, System.currentTimeMillis(), pkg)
                            Log.i(TAG, "Captured OTP from notification ($pkg)")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onInterrupt() {}

    private fun releaseServiceResources() {
        stopContinuousScroll("service_disconnected")
        adSkipWatcherJob?.cancel()
        adSkipWatcherJob = null
        mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
            _isConnected.value = false
            _activePackage.value = null
        }
    }

    override fun onDestroy() {
        releaseServiceResources()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseServiceResources()
        return super.onUnbind(intent)
    }

    /**
     * Forces Android's AccessibilityNodeInfo cache to sync with live Chrome/WebView and
     * virtual containers so stale text from previous questions/pages is never returned.
     */
    fun refreshNodeHierarchy(root: AccessibilityNodeInfo?, maxRefreshes: Int = 24) {
        if (root == null) return
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxRefreshes || depth > 9) return
            val cls = node.className?.toString().orEmpty()
            val shouldRefresh = depth <= 1 ||
                node.isScrollable ||
                cls.contains("WebView", ignoreCase = true) ||
                cls.contains("RecyclerView", ignoreCase = true) ||
                cls.contains("ViewPager", ignoreCase = true) ||
                cls.contains("ScrollView", ignoreCase = true)
            if (shouldRefresh) {
                runCatching { node.refresh() }
                count++
            }
            if (depth < 9 && count < maxRefreshes && node.childCount > 0) {
                val limit = node.childCount.coerceAtMost(24)
                for (i in 0 until limit) {
                    val child = node.getChild(i) ?: continue
                    if (child.childCount > 0 || child.isScrollable ||
                        child.className?.toString()?.contains("WebView", true) == true
                    ) {
                        walk(child, depth + 1)
                    }
                    if (count >= maxRefreshes) break
                }
            }
        }
        runCatching { walk(root, 0) }
    }

    fun getForegroundAppRootNode(forceRefresh: Boolean = false): AccessibilityNodeInfo? {
        var chosen: AccessibilityNodeInfo? = null
        try {
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val focusedNonAura = appWindows.firstOrNull { win ->
                val r = win.root
                win.isFocused && r != null && r.packageName?.toString() != packageName
            }
            chosen = focusedNonAura?.root
                ?: appWindows.firstOrNull { win ->
                    val r = win.root
                    win.isActive && r != null && r.packageName?.toString() != packageName
                }?.root
                ?: appWindows.firstOrNull { win ->
                    val r = win.root
                    r != null && r.packageName?.toString() != packageName
                }?.root
        } catch (_: Exception) {}

        if (chosen == null) {
            chosen = rootInActiveWindow
        }
        if (chosen == null) {
            try {
                for (win in windows) {
                    win.root?.let { chosen = it; return@let }
                    if (chosen != null) break
                }
            } catch (_: Exception) {}
        }

        if (forceRefresh && chosen != null) {
            refreshNodeHierarchy(chosen, maxRefreshes = 20)
        }
        return chosen
    }

    fun getActiveRootNode(forceRefresh: Boolean = false): AccessibilityNodeInfo? {
        return getForegroundAppRootNode(forceRefresh)
    }

    private fun isOwnAuraPackage(pkg: String): Boolean {
        return pkg == packageName || pkg == "com.example.gemini" || pkg == "com.aura.ai"
    }

    /**
     * Returns true when a third-party/external Android app (e.g. Instagram, YouTube, Chrome, Settings)
     * is currently focused on screen rather than Aura itself.
     */
    fun isExternalAppForeground(): Boolean {
        try {
            val appWindows = windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
            val focusedOrActive = appWindows
                .filter { it.isFocused || it.isActive }
                .sortedByDescending { (if (it.isFocused) 100 else 0) + (if (it.isActive) 50 else 0) + it.layer }
            for (win in focusedOrActive) {
                val pkg = runCatching { win.root?.packageName?.toString() }.getOrNull().orEmpty()
                if (pkg.isBlank()) continue
                if (isOwnAuraPackage(pkg)) return false
                if (pkg != "com.android.systemui") return true
            }
        } catch (_: Exception) {}
        val fg = foregroundPackage.orEmpty()
        return fg.isNotBlank() && !isOwnAuraPackage(fg) && fg != "com.android.systemui"
    }

    /**
     * Returns all active non-Aura application window roots (main window + PopupWindow menus,
     * context sheets, dialogs) ordered top-most / focused / active first so popup menus
     * like Chrome's "New Incognito tab" are never missed.
     */
    fun getAllApplicationWindowsRootNodes(forceRefresh: Boolean = false): List<AccessibilityNodeInfo> {
        val myPackage = packageName
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val ordered = windows
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .sortedByDescending { win ->
                    val layerScore = win.layer * 10
                    val focusScore = if (win.isFocused) 100_000 else 0
                    val activeScore = if (win.isActive) 50_000 else 0
                    val typeScore = if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) 25_000 else 0
                    focusScore + activeScore + typeScore + layerScore
                }
            for (win in ordered) {
                val root = runCatching { win.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == myPackage || pkg == "com.android.systemui") {
                    if (pkg == myPackage) continue
                }
                if (!roots.contains(root)) {
                    if (forceRefresh) {
                        refreshNodeHierarchy(root, maxRefreshes = 18)
                    }
                    roots.add(root)
                }
            }
        } catch (_: Exception) {}

        getForegroundAppRootNode(forceRefresh)?.let { active ->
            if (!roots.contains(active)) {
                roots.add(0, active)
            }
        }
        return roots
    }

    fun clickAt(x: Float, y: Float, durationMs: Long = 100L): Boolean {
        markUiMutation()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, mainHandler)
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
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat().coerceAtLeast(1f)

        val preferredMatches = mutableListOf<AccessibilityNodeInfo>()
        val fallbackMatches = mutableListOf<AccessibilityNodeInfo>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // Strictly skip any profile avatar or image node
            if (!isProfileAvatarOrIcon(node)) {
                val resId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

                if (text.contains(cleanTarget) || desc.contains(cleanTarget)) {
                    val isWhatsAppContactTitle = resId.contains("conversations_row_contact_name") ||
                        resId.contains("contactpicker_row_name") ||
                        resId.contains("row_contact_name")
                    if (isWhatsAppContactTitle) {
                        preferredMatches.add(node)
                    } else {
                        fallbackMatches.add(node)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val orderedCandidates = preferredMatches + fallbackMatches
        for (node in orderedCandidates) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Skip tiny or left-edge avatar-only bounds (< 22% screen width)
            if (bounds.right < screenW * 0.22f && bounds.width() < screenW * 0.25f) continue

            // Prefer tapping the text/right-center portion of the chat row (strictly avoiding left 25% avatar)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val safeLeft = maxOf(bounds.left.toFloat(), screenW * 0.28f)
                val cx = (bounds.left + bounds.width() * 0.60f).coerceAtLeast(safeLeft).coerceAtMost(screenW * 0.85f)
                val cy = bounds.exactCenterY()
                if (clickAt(cx, cy, durationMs = 80L)) {
                    Log.i(TAG, "Clicked contact name safely at ($cx, $cy) (avoided left avatar): '$targetName'")
                    return true
                }
            }

            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked contact name via ACTION_CLICK: '$targetName'")
                return true
            }
            val parent = node.parent
            if (parent != null && parent.isClickable && !isProfileAvatarOrIcon(parent) && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked contact name parent via ACTION_CLICK: '$targetName'")
                return true
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

    private fun isNodeInsideWebView(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 14) {
            if (cur.className?.toString()?.contains("WebView", ignoreCase = true) == true) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun tryNativeNodeClickAtRect(rect: Rect, cx: Float, cy: Float): Pair<Boolean, Boolean> {
        val root = getActiveRootNode()
        val inChrome = root?.packageName?.toString()?.contains("chrome", ignoreCase = true) == true
        val searchRoots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(root) }
        var coveredByWebView = false

        for (searchRoot in searchRoots) {
            var node = findNodeCoveringRect(searchRoot, rect)
            if (inChrome && isNodeInsideWebView(node)) {
                coveredByWebView = true
                continue
            }
            while (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && node.isClickable && bounds.contains(cx.toInt(), cy.toInt())) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true to coveredByWebView
                    }
                    break
                }
                node = node.parent
            }
        }
        return false to coveredByWebView
    }

    fun performClickAtRect(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val (nativeClicked, _) = tryNativeNodeClickAtRect(rect, cx, cy)
        if (nativeClicked) return true
        Log.i(TAG, "Using coordinate fallback at ($cx, $cy) [bounds: $rect]")
        return clickAt(cx, cy)
    }

    /**
     * Awaits completion of the actual click / gesture dispatch so subsequent verification
     * never races an in-flight Android gesture stroke.
     */
    suspend fun performClickAtRectAndAwait(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val (nativeClicked, _) = tryNativeNodeClickAtRect(rect, cx, cy)
        if (nativeClicked) return true
        return gesture(Path().apply { moveTo(cx, cy) }, 65L)
    }

    /**
     * Triggers native accessibility ACTION_CLICK on a checkable or clickable node covering [rect].
     * Used as a targeted retry in Chrome/WebView when coordinate tap alone did not toggle a radio/checkbox.
     */
    fun performAccessibilityCheckableClick(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.centerX()
        val cy = rect.centerY()
        val searchRoots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        for (searchRoot in searchRoots) {
            var node = findNodeCoveringRect(searchRoot, rect)
            while (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && (node.isCheckable || node.isClickable) && bounds.contains(cx, cy)) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                node = node.parent
            }
        }
        return false
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

    private fun getRealScreenDimensions(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width().toFloat() to bounds.height().toFloat()
            }
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm.setTo(resources.displayMetrics)
        }
        return dm.widthPixels.toFloat().coerceAtLeast(1f) to dm.heightPixels.toFloat().coerceAtLeast(1f)
    }

    private fun buildGestureFromPlan(
        plan: AccessibilityGestureMath.SwipeMotionPlan,
        totalDurationMs: Long
    ): GestureDescription {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val engagePath = Path().apply {
                moveTo(plan.start.x, plan.start.y)
                lineTo(plan.engageEnd.x, plan.engageEnd.y)
            }
            val flingPath = Path().apply {
                moveTo(plan.engageEnd.x, plan.engageEnd.y)
                lineTo(plan.flingEnd.x, plan.flingEnd.y)
            }
            val stage1 = GestureDescription.StrokeDescription(
                engagePath,
                0L,
                plan.engageDurationMs.coerceIn(30L, 250L),
                true
            )
            val stage2 = stage1.continueStroke(
                flingPath,
                0L,
                plan.flingDurationMs.coerceIn(60L, 600L),
                false
            )
            GestureDescription.Builder().addStroke(stage2).build()
        } else {
            val path = Path().apply {
                moveTo(plan.start.x, plan.start.y)
                lineTo(plan.flingEnd.x, plan.flingEnd.y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, totalDurationMs.coerceIn(80L, 800L))
            GestureDescription.Builder().addStroke(stroke).build()
        }
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean = try {
        dispatchGesture(gesture, null, mainHandler)
    } catch (e: Exception) {
        Log.e(TAG, "Gesture dispatch failed: ${e.message}", e)
        false
    }

    private suspend fun dispatchGestureAndAwait(gesture: GestureDescription, totalDurationMs: Long): Boolean {
        val safeDur = totalDurationMs.coerceIn(50L, 60_000L)
        return withTimeoutOrNull((safeDur + 1_800L).coerceAtLeast(2_200L)) {
            suspendCancellableCoroutine { cont ->
                markUiMutation()
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, mainHandler)
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private fun buildSmartScrollGesture(direction: String, distance: String): Pair<GestureDescription, Long> {
        val (width, height) = getRealScreenDimensions()
        val (plan, totalMs) = AccessibilityGestureMath.computeSafeScrollPoints(
            direction = direction,
            distance = distance,
            screenWidth = width.toInt(),
            screenHeight = height.toInt()
        )
        return buildGestureFromPlan(plan, totalMs) to totalMs
    }

    private fun buildReelSwipeMotion(upward: Boolean, attempt: Int = 1): Pair<GestureDescription, Long> {
        val (width, height) = getRealScreenDimensions()
        val plan = AccessibilityGestureMath.computeReelSwipeMotion(
            upward = upward,
            attempt = attempt,
            screenWidth = width.toInt(),
            screenHeight = height.toInt()
        )
        val totalMs = plan.engageDurationMs + plan.flingDurationMs
        return buildGestureFromPlan(plan, totalMs) to totalMs
    }

    fun isCurrentlyShortVideoFeed(root: AccessibilityNodeInfo? = getActiveRootNode()): Boolean {
        val r = root ?: return false
        val pkg = r.packageName?.toString().orEmpty()
        val viewIds = mutableListOf<String>()
        val labels = mutableListOf<String>()
        val descs = mutableListOf<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(r)
        var scanned = 0
        while (q.isNotEmpty() && scanned < 150) {
            val node = q.removeFirst()
            scanned++
            node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { viewIds.add(it) }
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { descs.add(it) }
            for (i in 0 until node.childCount.coerceAtMost(24)) {
                node.getChild(i)?.let { q.add(it) }
            }
        }
        return DeviceControlHelper.isShortVideoScreen(pkg, viewIds, labels, descs)
    }

    fun performReelAccessibilityScroll(upward: Boolean = true): Boolean {
        val root = getActiveRootNode(forceRefresh = true) ?: return false
        val (width, height) = getRealScreenDimensions()
        val rect = Rect()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var scanned = 0
        while (q.isNotEmpty() && scanned < 160) {
            val node = q.removeFirst()
            scanned++
            val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
            val isPagerOrList = cls.contains("viewpager") || cls.contains("recyclerview") || node.isScrollable
            if (isPagerOrList && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                // Must cover most of active screen width and height so hidden background tabs/carousels are skipped
                if (rect.width() >= width * 0.55f && rect.height() >= height * 0.45f) {
                    val primaryAction = if (upward) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    if (node.performAction(primaryAction)) {
                        Log.i(TAG, "performReelAccessibilityScroll(upward=$upward) succeeded on $cls")
                        return true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val dirAction = if (upward) {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        } else {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        }
                        if (node.performAction(dirAction)) {
                            Log.i(TAG, "performReelAccessibilityScroll directional (upward=$upward) succeeded on $cls")
                            return true
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { q.add(it) }
            }
        }
        return false
    }

    fun performBestVisibleAccessibilityScroll(direction: String): Boolean {
        val root = getActiveRootNode(forceRefresh = true) ?: return false
        val (width, height) = getRealScreenDimensions()
        val rect = Rect()
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var scanned = 0
        val wantHorizontal = direction.equals("left", true) || direction.equals("right", true)
        while (q.isNotEmpty() && scanned < 180) {
            val node = q.removeFirst()
            scanned++
            if (node.isVisibleToUser && node.isScrollable) {
                node.getBoundsInScreen(rect)
                val area = rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
                val bigEnough = if (wantHorizontal) {
                    rect.width() >= width * 0.35f && rect.height() >= 80
                } else {
                    rect.height() >= height * 0.25f && rect.width() >= width * 0.40f
                }
                if (bigEnough) {
                    candidates.add(area to node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { q.add(it) }
            }
        }
        val dirLower = direction.lowercase(Locale.ROOT)
        for ((_, node) in candidates.sortedByDescending { it.first }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val directionalId = when (dirLower) {
                    "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
                if (node.performAction(directionalId)) {
                    Log.i(TAG, "performBestVisibleAccessibilityScroll($direction) succeeded via directional action")
                    return true
                }
            }
            val stdAction = when (dirLower) {
                "up", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (node.performAction(stdAction)) {
                Log.i(TAG, "performBestVisibleAccessibilityScroll($direction) succeeded via std action")
                return true
            }
        }
        return false
    }

    fun performSmartScroll(direction: String, distance: String = "medium"): Boolean {
        val (gesture, durationMs) = buildSmartScrollGesture(direction, distance)
        val dispatched = dispatchGestureSync(gesture)
        Log.i(TAG, "Dispatched 2-stage smart scroll $direction (distance=$distance, duration=${durationMs}ms): $dispatched")
        return dispatched || performBestVisibleAccessibilityScroll(direction)
    }

    suspend fun performSmartScrollAndAwait(direction: String, distance: String = "medium"): Boolean {
        val (gesture, durationMs) = buildSmartScrollGesture(direction, distance)
        val completed = dispatchGestureAndAwait(gesture, durationMs)
        Log.i(TAG, "Awaited 2-stage smart scroll $direction (distance=$distance, duration=${durationMs}ms): completed=$completed")
        if (completed) return true
        return performBestVisibleAccessibilityScroll(direction)
    }

    /**
     * Dedicated short-video flick gesture for Instagram Reels / YouTube Shorts / TikTok.
     */
    fun performReelSwipe(upward: Boolean = true, attempt: Int = 1): Boolean {
        val (gesture, durationMs) = buildReelSwipeMotion(upward, attempt)
        val dispatched = dispatchGestureSync(gesture)
        Log.i(TAG, "performReelSwipe (upward=$upward, attempt=$attempt, ${durationMs}ms): dispatched=$dispatched")
        return dispatched || performReelAccessibilityScroll(upward)
    }

    suspend fun performReelSwipeAndAwait(upward: Boolean = true, attempt: Int = 1): Boolean {
        val (gesture, durationMs) = buildReelSwipeMotion(upward, attempt)
        val completed = dispatchGestureAndAwait(gesture, durationMs)
        Log.i(TAG, "performReelSwipeAndAwait (upward=$upward, attempt=$attempt, ${durationMs}ms): completed=$completed")
        return if (completed) true else performReelAccessibilityScroll(upward)
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
            "slow" -> 2400L
            "fast" -> 1000L
            else -> 1600L
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
                    val activeRoot = getActiveRootNode()
                    val currentPkg = activeRoot?.packageName?.toString() ?: ""
                    if (initialPkg.isNotEmpty() && currentPkg != initialPkg && !currentPkg.contains("systemui") && !currentPkg.contains("launcher")) {
                        Log.i(TAG, "Continuous scroll auto-stopped: App switched from $initialPkg to $currentPkg")
                        onStopped?.invoke("App switched to $currentPkg")
                        break
                    }

                    val inShortFeed = isCurrentlyShortVideoFeed(activeRoot)
                    val ok = if (inShortFeed) {
                        val upward = !direction.equals("up", ignoreCase = true)
                        performReelSwipe(upward = upward)
                    } else {
                        performSmartScroll(direction, scrollDistance)
                    }
                    if (!ok) {
                        Log.w(TAG, "Continuous scroll gesture dispatch failed, stopping.")
                        break
                    }
                    totalScrolls++

                    delay(scrollIntervalMs)

                    // Skip premature static-signature abort during fullscreen SurfaceView Reels/Shorts
                    if (!inShortFeed) {
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
        // Wait 520ms settling delay for task card entry animations to finish cleanly (#20)
        delay(520)
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
            val cardsBefore = findVisibleTaskCards()
            val targetCard = cardsBefore.firstOrNull { it.node.packageName?.toString() == foregroundPkgBefore && !it.isAura }
                ?: cardsBefore.firstOrNull { !it.isAura }

            if (targetCard != null) {
                val targetTitleBefore = targetCard.title
                dismissCardUpward(targetCard.node, targetCard.bounds, w, h)
                delay(450)
                // Verify post-swipe: check if app accidentally reopened or card is still present (#20)
                val currentPkg = getActiveRootNode()?.packageName?.toString() ?: ""
                val stillPresentAfterFirst = findVisibleTaskCards().any {
                    !it.isAura && (
                        (foregroundPkgBefore.isNotBlank() && it.node.packageName?.toString() == foregroundPkgBefore) ||
                        (targetTitleBefore.isNotBlank() && it.title.equals(targetTitleBefore, ignoreCase = true))
                    )
                }
                if ((foregroundPkgBefore.isNotBlank() && currentPkg == foregroundPkgBefore) || stillPresentAfterFirst) {
                    Log.w(TAG, "Target card '$targetTitleBefore' ($foregroundPkgBefore) still present/reopened; retrying steeper upward fling...")
                    if (foregroundPkgBefore.isNotBlank() && currentPkg == foregroundPkgBefore) {
                        openRecentsAndWait()
                    }
                    val steeperPath = Path().apply {
                        moveTo(w * 0.50f, h * 0.68f)
                        lineTo(w * 0.50f, h * 0.05f)
                    }
                    gesture(steeperPath, 130L)
                    delay(450)
                }
                val stillPresentFinal = findVisibleTaskCards().any {
                    !it.isAura && targetTitleBefore.isNotBlank() && it.title.equals(targetTitleBefore, ignoreCase = true)
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                return if (!stillPresentFinal) {
                    RecentsActionResult("SUCCESS", "Closed foreground app (${targetTitleBefore.ifBlank { foregroundPkgBefore }}) and verified dismissal from Recents.", clearedCount = 1)
                } else {
                    RecentsActionResult("FAILED", "Could not verify dismissal of ${targetTitleBefore.ifBlank { foregroundPkgBefore }} from Recents; card may still be present.", remainingCardsCount = 1)
                }
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return RecentsActionResult("FAILED", "No dismissible foreground app card found in Recents (returned home safely).")
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

        val cardNode = targetNode ?: findVisibleTaskCards().firstOrNull { !it.isAura && it.title.lowercase(Locale.ROOT).contains(cleanTarget) }?.node
        if (cardNode != null) {
            val rect = Rect()
            cardNode.getBoundsInScreen(rect)
            dismissCardUpward(cardNode, rect, w, h)
            delay(450)
        } else {
            // #20: Never swipe away an unrelated app if the specifically requested app wasn't found!
            Log.w(TAG, "closeSpecificAppFromRecents: '$appName' card not found in Recents; skipping unrelated cards.")
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
        // Decisive high-velocity upward fling (155ms) so launcher dismisses task card without opening it (#20)
        gesture(path, 155L)
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
        val gestureDispatched = dispatchGesture(gesture, null, mainHandler)
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

    /**
     * Synchronously waits for WhatsApp's chat composer to open, triggers the verified Send button
     * (or ACTION_IME_ENTER), and waits for the message to dispatch before returning.
     * Essential for multi-step workflows (e.g., "WhatsApp kholo, Rahul ko message karo Hi, aur WhatsApp close kar do").
     */
    suspend fun awaitWhatsAppAutoSend(timeoutMs: Long = 5500L): Boolean = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        delay(650) // Allow WhatsApp activity transition to settle
        var sent = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = getActiveRootNode()
            val pkg = root?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (root != null && pkg.contains("whatsapp")) {
                val sendNode = findVerifiedWhatsAppSendButton(root)
                if (sendNode != null) {
                    clickNodeAndTap(sendNode)
                    Log.i(TAG, "awaitWhatsAppAutoSend: Clicked verified WhatsApp Send button.")
                    sent = true
                    break
                }
                val inputField = findEditableInputField(root)
                if (inputField != null && !inputField.text.isNullOrBlank()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val enterOk = inputField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        if (enterOk) {
                            delay(250)
                            val refreshedRoot = getActiveRootNode()
                            val refreshedSend = if (refreshedRoot != null) findVerifiedWhatsAppSendButton(refreshedRoot) else null
                            if (refreshedSend != null) {
                                clickNodeAndTap(refreshedSend)
                            }
                            Log.i(TAG, "awaitWhatsAppAutoSend: Dispatched ACTION_IME_ENTER on WhatsApp input field.")
                            sent = true
                            break
                        }
                    }
                }
            }
            delay(250)
        }
        if (sent) {
            delay(650) // Let WhatsApp commit the outgoing message bubble before any subsequent close_app step
        }
        sent
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

    private fun collectDescendantText(node: AccessibilityNodeInfo, maxDepth: Int = 4): String {
        val parts = ArrayList<String>(6)
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= 8 || depth > maxDepth) return
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            for (i in 0 until n.childCount.coerceAtMost(10)) {
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(node, 0)
        return parts.distinct().joinToString(" ")
    }

    private fun isYouTubePlayerOpen(roots: List<AccessibilityNodeInfo>): Boolean {
        val playerIds = listOf(
            "com.google.android.youtube:id/watch_player",
            "com.google.android.youtube:id/player_view",
            "com.google.android.youtube:id/player_fragment_container"
        )
        return roots.any { r ->
            playerIds.any { id ->
                r.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
            }
        }
    }

    fun armYouTubePlay() {
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
                val inYouTube = roots.any { it.packageName?.toString()?.contains("youtube", ignoreCase = true) == true }
                if (inYouTube && isYouTubePlayerOpen(roots)) {
                    Log.d(TAG, "YouTube player already open; stopping armYouTubePlay.")
                    return
                }
                if (clickFirstYouTubeVideoNow()) {
                    Log.d(TAG, "Tapped first YouTube video via Accessibility on attempt $attempts!")
                    return
                }
                attempts++
                // If Sponsored banners fill the top fold, scroll down gently once on attempt 4 (#16)
                if (inYouTube && attempts == 4) {
                    performSmartScroll("down", "small")
                }
                if (attempts < 12) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }, 800)
    }

    fun clickFirstYouTubeVideoNow(): Boolean {
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        val (_, height) = getRealScreenDimensions()
        val ytRoots = roots.filter { it.packageName?.toString()?.contains("youtube", ignoreCase = true) == true }
            .ifEmpty { roots }

        val videoViewIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_info",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/compact_renderer"
        )
        for (root in ytRoots) {
            for (id in videoViewIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val valid = nodes.firstOrNull { node ->
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    if (!node.isVisibleToUser) return@firstOrNull false
                    val meta = collectDescendantText(node)
                    YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                        viewId = id,
                        text = node.text?.toString().orEmpty(),
                        contentDescription = meta,
                        topPx = r.top,
                        heightPx = r.height(),
                        screenHeightPx = height.toInt()
                    )
                }
                if (valid != null && clickNodeAndTap(valid)) return true
            }

            // Modern Litho YouTube UI often places card metadata in child nodes under a blank clickable card
            val lithoNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, lithoNodes, maxNodes = 700)
            val rect = Rect()
            val candidate = lithoNodes.filter { n ->
                if (!n.isVisibleToUser || !n.isClickable) return@filter false
                n.getBoundsInScreen(rect)
                val meta = collectDescendantText(n)
                YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                    viewId = n.viewIdResourceName.orEmpty(),
                    text = n.text?.toString().orEmpty(),
                    contentDescription = meta,
                    topPx = rect.top,
                    heightPx = rect.height(),
                    screenHeightPx = height.toInt()
                )
            }.minByOrNull { n ->
                n.getBoundsInScreen(rect)
                rect.top
            }
            if (candidate != null && clickNodeAndTap(candidate)) return true
        }
        return false
    }

    private fun maybeTriggerEventYouTubeAdSkip(eventPkg: String?) {
        if (!autoSkipYouTubeAdsEnabled || eventPkg.isNullOrBlank()) return
        val isYtOrChrome = eventPkg.contains("youtube", ignoreCase = true) ||
            eventPkg.contains("chrome", ignoreCase = true)
        if (!isYtOrChrome) return

        val now = System.currentTimeMillis()
        if (now - lastAdSkipClickAtMs < 1_200L || now - lastEventAdCheckAtMs < 420L) return
        lastEventAdCheckAtMs = now

        adSkipScope.launch {
            runCatching {
                if (eventPkg.contains("chrome", ignoreCase = true)) {
                    val urlHint = getActiveRootNode()
                        ?.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                        ?.firstOrNull()
                        ?.text
                        ?.toString()
                        .orEmpty()
                    if (!urlHint.contains("youtube", ignoreCase = true) && !urlHint.contains("youtu.be", ignoreCase = true)) {
                        return@runCatching
                    }
                }
                if (skipYouTubeAd()) {
                    lastAdSkipClickAtMs = System.currentTimeMillis()
                    Log.i(TAG, "Event-driven Auto Ad-Skip clicked Skip Ad in $eventPkg!")
                }
            }
        }
    }

    fun armYouTubeAdSkipWatcher(durationMs: Long = 90_000L) {
        adSkipWatcherJob?.cancel()
        adSkipWatcherJob = adSkipScope.launch {
            val deadline = System.currentTimeMillis() + durationMs.coerceIn(5_000L, 180_000L)
            try {
                delay(700L)
                while (isActive && System.currentTimeMillis() < deadline) {
                    val pkg = foregroundPackage ?: getActiveRootNode()?.packageName?.toString().orEmpty()
                    val isYtContext = pkg.contains("youtube", ignoreCase = true) ||
                        (pkg.contains("chrome", ignoreCase = true) && getActiveRootNode()
                            ?.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                            ?.firstOrNull()?.text?.toString()?.contains("youtube", ignoreCase = true) == true)
                    if (isYtContext && skipYouTubeAd()) {
                        lastAdSkipClickAtMs = System.currentTimeMillis()
                        Log.i(TAG, "Auto Ad-Skip Watcher clicked YouTube Skip Ad button!")
                        delay(2_200L) // Fast re-check in case of double pre-roll ad
                        continue
                    }
                    delay(550L)
                }
            } catch (_: CancellationException) {
            } finally {
                adSkipWatcherJob = null
            }
        }
    }

    fun skipYouTubeAd(): Boolean {
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        val skipIds = listOf(
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_text",
            "com.google.android.youtube:id/skip_ad_button_container",
            "com.google.android.youtube:id/modern_skip_ad_text",
            "com.google.android.youtube:id/modern_skip_ad_button",
            "com.google.android.youtube:id/ad_skip_button",
            "com.google.android.youtube:id/skip_button",
            "app.revanced.android.youtube:id/skip_ad_button"
        )
        val bounds = Rect()
        for (root in roots) {
            for (id in skipIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val target = nodes.firstOrNull { n ->
                    n.getBoundsInScreen(bounds)
                    val ownOrChildText = n.text?.toString().orEmpty().ifBlank { collectDescendantText(n, 2) }
                    val ownOrChildDesc = n.contentDescription?.toString().orEmpty()
                    YouTubeSearchHelper.isSkippableAdButton(
                        viewId = id,
                        text = ownOrChildText,
                        contentDescription = ownOrChildDesc,
                        isVisible = n.isVisibleToUser,
                        isEnabled = n.isEnabled,
                        widthPx = bounds.width(),
                        heightPx = bounds.height()
                    )
                }
                if (target != null && clickNodeAndTap(target)) return true
            }
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, nodes, maxNodes = 650)
            val skipNode = nodes.firstOrNull { n ->
                n.getBoundsInScreen(bounds)
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                val vid = n.viewIdResourceName.orEmpty()
                YouTubeSearchHelper.isSkippableAdButton(
                    viewId = vid,
                    text = t,
                    contentDescription = d,
                    isVisible = n.isVisibleToUser,
                    isEnabled = n.isEnabled,
                    widthPx = bounds.width(),
                    heightPx = bounds.height()
                )
            }
            if (skipNode != null && clickNodeAndTap(skipNode)) return true
        }
        return false
    }

    fun likeYouTubeVideo(): Boolean {
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        val likeIds = listOf(
            "com.google.android.youtube:id/like_button",
            "com.google.android.youtube:id/reel_like_button",
            "com.google.android.youtube:id/segmented_like_button",
            "com.instagram.android:id/row_feed_button_like",
            "com.instagram.android:id/like_button",
            "com.instagram.android:id/clips_like_button"
        )
        for (root in roots) {
            for (id in likeIds) {
                val n = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
                if (n != null && clickNodeAndTap(n)) return true
            }
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, nodes, maxNodes = 360)
            val n = nodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val t = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                (d.contains("like this video") || d.startsWith("like ") || d == "like" || t == "like" || t.contains("पसंद")) &&
                    !d.contains("dislike") && !t.contains("dislike") && !d.contains("नापसंद")
            }
            if (n != null && clickNodeAndTap(n)) return true
        }
        return false
    }

    suspend fun likeCurrentVideoOrReel(): Pair<Boolean, String> {
        val root = getActiveRootNode(forceRefresh = true)
        val pkg = root?.packageName?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val roots = getAllApplicationWindowsRootNodes(forceRefresh = true).ifEmpty { listOfNotNull(root) }

        val likeIds = listOf(
            "com.google.android.youtube:id/like_button",
            "com.google.android.youtube:id/reel_like_button",
            "com.google.android.youtube:id/segmented_like_button",
            "com.instagram.android:id/row_feed_button_like",
            "com.instagram.android:id/like_button",
            "com.instagram.android:id/clips_like_button"
        )
        for (r in roots) {
            for (id in likeIds) {
                val n = r.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser } ?: continue
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                if (n.isSelected || d.contains("unlike") || d.contains("liked")) {
                    return true to "This video/reel is already liked."
                }
                if (clickNodeAndTap(n)) return true to "Liked the video."
            }
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(r, nodes, maxNodes = 420)
            val n = nodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val t = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                ((d.contains("like") || t == "like" || t.contains("पसंद")) &&
                    !d.contains("dislike") && !t.contains("dislike") && !d.contains("likely") &&
                    !d.contains("liked posts"))
            }
            if (n != null) {
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                if (n.isSelected || d.contains("unlike") || d.contains("liked")) {
                    return true to "This video/reel is already liked."
                }
                if (clickNodeAndTap(n)) return true to "Liked the video."
            }
        }

        // Fallback: on Instagram Reels / TikTok / Moj, double-tap the uncluttered middle-left canvas;
        // on regular YouTube watch screen, tap player once to wake controls then retry like button.
        val (w, h) = getRealScreenDimensions()
        if (pkg.contains("instagram") || pkg.contains("musically") || pkg.contains("tiktok") || isCurrentlyShortVideoFeed(root)) {
            if (pkg.contains("youtube")) {
                // Shorts: tap right-hand action bar area? No — try once on player then scan
                clickAt(w * 0.45f, h * 0.35f)
                delay(320L)
                if (likeYouTubeVideo()) return true to "Liked the YouTube Short."
            } else {
                val dt = doubleTapAt(w * 0.42f, h * 0.44f, tapDurationMs = 45L, gapMs = 85L)
                return dt to if (dt) "Double-tapped to like the reel." else "Failed to double-tap reel."
            }
        } else if (pkg.contains("youtube")) {
            clickAt(w * 0.50f, h * 0.24f)
            delay(360L)
            if (likeYouTubeVideo()) return true to "Liked the YouTube video."
        }
        return false to "Could not find the Like button on screen."
    }

    /**
     * Seeks native YouTube playback forward or backward in 10-second steps using visible player seek
     * buttons or native double-tap (left/right side of the video player bounds).
     */
    suspend fun seekYouTubeVideo(forward: Boolean, seconds: Int = 10): Pair<Boolean, String> {
        val root = getActiveRootNode(forceRefresh = true)
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(root) }
        val stepCount = ((seconds.coerceIn(5, 60) + 5) / 10).coerceIn(1, 6)
        val targetSeconds = stepCount * 10

        // 1. If YouTube overlay already shows explicit 10s forward/rewind buttons, click them stepCount times
        val targetKeywords = if (forward) {
            listOf("fast forward", "forward 10", "skip forward", "10 seconds forward")
        } else {
            listOf("rewind", "backward 10", "skip backward", "10 seconds back")
        }
        for (r in roots) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(r, nodes, maxNodes = 260)
            val btn = nodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val t = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                targetKeywords.any { kw -> d.contains(kw) || t.contains(kw) }
            }
            if (btn != null) {
                repeat(stepCount) {
                    clickNodeAndTap(btn)
                    delay(140L)
                }
                return true to "${if (forward) "Fast-forwarded" else "Rewound"} ${targetSeconds}s on YouTube."
            }
        }

        // 2. Locate player frame bounds dynamically; fall back to portrait top watch canvas
        val (w, h) = getRealScreenDimensions()
        val playerIds = listOf(
            "com.google.android.youtube:id/watch_player",
            "com.google.android.youtube:id/player_view",
            "com.google.android.youtube:id/player_fragment_container",
            "com.google.android.youtube:id/youtube_player_view",
            "com.google.android.youtube:id/next_gen_watch_layout"
        )
        var playerBounds: Rect? = null
        val rect = Rect()
        for (r in roots) {
            for (pid in playerIds) {
                val pNode = r.findAccessibilityNodeInfosByViewId(pid).firstOrNull { it.isVisibleToUser }
                if (pNode != null) {
                    pNode.getBoundsInScreen(rect)
                    if (rect.width() >= w * 0.5f && rect.height() >= 140) {
                        playerBounds = Rect(rect)
                        break
                    }
                }
            }
            if (playerBounds != null) break
        }
        val box = playerBounds ?: Rect(
            0,
            (h * 0.08f).toInt(),
            w.toInt(),
            (if (w > h) h * 0.85f else h * 0.34f).toInt()
        )
        // YouTube player height cap if next_gen_watch_layout matched whole screen in portrait
        val effectiveBottom = if (w < h && box.height() > h * 0.55f) (box.top + h * 0.30f).toInt() else box.bottom
        val tapY = ((box.top + effectiveBottom) / 2f).coerceIn(h * 0.14f, h * 0.82f)
        val tapX = if (forward) {
            (box.left + box.width() * 0.78f).coerceIn(w * 0.55f, w * 0.90f)
        } else {
            (box.left + box.width() * 0.22f).coerceIn(w * 0.10f, w * 0.45f)
        }

        // 2 taps = +10s, each additional tap adds another +10s in YouTube
        val totalTaps = 1 + stepCount
        val safePt = AccessibilityGestureMath.clampPoint(tapX, tapY, w.toInt(), h.toInt(), marginPx = 16f)
        var okCount = 0
        repeat(totalTaps) { idx ->
            val path = Path().apply { moveTo(safePt.x, safePt.y) }
            if (gesture(path, 45L)) okCount++
            if (idx < totalTaps - 1) delay(85L)
        }
        val ok = okCount >= 2
        return ok to if (ok) {
            "${if (forward) "Skipped forward" else "Rewound"} ${targetSeconds}s in YouTube player."
        } else {
            "Failed to dispatch seek taps to YouTube player."
        }
    }

    /**
     * Toggles Play / Pause directly via visible player controls or safe canvas tap (for Shorts/Reels).
     */
    suspend fun toggleShortVideoPlayPause(): Boolean {
        val root = getActiveRootNode(forceRefresh = true)
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(root) }
        val playPauseIds = listOf(
            "com.google.android.youtube:id/player_control_play_pause_replay_button",
            "com.google.android.youtube:id/pause_play_button"
        )
        for (r in roots) {
            for (id in playPauseIds) {
                val n = r.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
                if (n != null && clickNodeAndTap(n)) return true
            }
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(r, nodes, maxNodes = 260)
            val btn = nodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                d == "pause video" || d == "play video" || d == "pause" || d == "play"
            }
            if (btn != null && clickNodeAndTap(btn)) return true
        }
        val (w, h) = getRealScreenDimensions()
        val tapY = if (isCurrentlyShortVideoFeed(root)) h * 0.45f else h * 0.24f
        return gesture(Path().apply { moveTo(w * 0.44f, tapY) }, 50L)
    }

    fun subscribeYouTubeChannel(): Boolean {
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        for (root in roots) {
            val subNode = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/subscribe_button")
                .firstOrNull { it.isVisibleToUser }
                ?: run {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodes(root, nodes, maxNodes = 260)
                    nodes.firstOrNull { n ->
                        n.isVisibleToUser && (
                            n.text?.toString()?.equals("Subscribe", true) == true ||
                                n.contentDescription?.toString()?.lowercase(Locale.ROOT)?.startsWith("subscribe to ") == true
                            )
                    }
                }
            if (subNode != null && clickNodeAndTap(subNode)) return true
        }
        return false
    }

    fun toggleYouTubeFullscreen(): Boolean {
        val roots = getAllApplicationWindowsRootNodes().ifEmpty { listOfNotNull(getActiveRootNode()) }
        for (root in roots) {
            val fsNode = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/fullscreen_button")
                .firstOrNull { it.isVisibleToUser }
                ?: run {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodes(root, nodes, maxNodes = 260)
                    nodes.firstOrNull { n ->
                        val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                        n.isVisibleToUser && (d.contains("enter fullscreen") || d.contains("exit fullscreen") || d.contains("full screen"))
                    }
                }
            if (fsNode != null && clickNodeAndTap(fsNode)) return true
        }
        return false
    }

    suspend fun gesture(path: Path, durationMs: Long): Boolean {
        val safeDur = durationMs.coerceIn(1, 60_000)
        return withTimeoutOrNull((safeDur + 1_800L).coerceAtLeast(2_200L)) {
            suspendCancellableCoroutine { cont ->
                markUiMutation()
                val stroke = GestureDescription.StrokeDescription(path, 0, safeDur)
                val dispatched = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, mainHandler)
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    suspend fun multiStrokeGesture(strokes: List<GestureDescription.StrokeDescription>): Boolean {
        if (strokes.isEmpty()) return false
        return withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                val builder = GestureDescription.Builder()
                for (stroke in strokes) {
                    builder.addStroke(stroke)
                }
                val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, mainHandler)
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    /**
     * Dispatches two consecutive verified taps at ([x], [y]) separated by [gapMs].
     */
    suspend fun doubleTapAt(
        x: Float,
        y: Float,
        tapDurationMs: Long = 50L,
        gapMs: Long = 90L
    ): Boolean {
        val dm = resources.displayMetrics
        val safe = AccessibilityGestureMath.clampPoint(x, y, dm.widthPixels, dm.heightPixels)
        val firstPath = Path().apply { moveTo(safe.x, safe.y) }
        if (!gesture(firstPath, tapDurationMs.coerceIn(20L, 250L))) return false
        delay(gapMs.coerceIn(40L, 300L))
        val secondPath = Path().apply { moveTo(safe.x, safe.y) }
        return gesture(secondPath, tapDurationMs.coerceIn(20L, 250L))
    }

    /**
     * Performs a two-finger pinch gesture (zoom in or zoom out) centered around ([centerX], [centerY]).
     */
    suspend fun pinchGesture(
        centerX: Float? = null,
        centerY: Float? = null,
        zoomIn: Boolean = true,
        scale: Float = 0.45f,
        durationMs: Long = 400L
    ): Boolean {
        val dm = resources.displayMetrics
        val cx = centerX ?: (dm.widthPixels / 2f)
        val cy = centerY ?: (dm.heightPixels / 2f)
        val vectors = AccessibilityGestureMath.computePinchVectors(
            centerX = cx,
            centerY = cy,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            zoomIn = zoomIn,
            scale = scale
        )
        val dur = durationMs.coerceIn(100L, 3_000L)
        val path1 = Path().apply {
            moveTo(vectors.finger1Start.x, vectors.finger1Start.y)
            lineTo(vectors.finger1End.x, vectors.finger1End.y)
        }
        val path2 = Path().apply {
            moveTo(vectors.finger2Start.x, vectors.finger2Start.y)
            lineTo(vectors.finger2End.x, vectors.finger2End.y)
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, dur)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0L, dur)
        return multiStrokeGesture(listOf(stroke1, stroke2))
    }

    /**
     * Performs a long-press-and-drag gesture from ([startX], [startY]) to ([endX], [endY]).
     * On Android 8+ (API 26+), uses continued strokes so the finger genuinely holds before dragging.
     */
    suspend fun dragGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdMs: Long = 350L,
        moveDurationMs: Long = 450L
    ): Boolean {
        val dm = resources.displayMetrics
        val start = AccessibilityGestureMath.clampPoint(startX, startY, dm.widthPixels, dm.heightPixels)
        val end = AccessibilityGestureMath.clampPoint(endX, endY, dm.widthPixels, dm.heightPixels)
        val clampedHold = holdMs.coerceIn(0L, 3_000L)
        val clampedMove = moveDurationMs.coerceIn(80L, 5_000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && clampedHold > 0L) {
            val holdPath = Path().apply { moveTo(start.x, start.y) }
            val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, clampedHold, true)
            if (!multiStrokeGesture(listOf(holdStroke))) return false
            val dragPath = Path().apply {
                moveTo(start.x, start.y)
                lineTo(end.x, end.y)
            }
            val moveStroke = holdStroke.continueStroke(dragPath, 0L, clampedMove, false)
            return multiStrokeGesture(listOf(moveStroke))
        }

        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        return gesture(path, (clampedHold + clampedMove).coerceIn(120L, 8_000L))
    }

    /**
     * Dispatches a single continuous multi-waypoint path gesture across [points].
     */
    suspend fun multiPointGesture(
        points: List<Pair<Float, Float>>,
        durationMs: Long = 500L
    ): Boolean {
        if (points.size < 2) return false
        val dm = resources.displayMetrics
        val clamped = points.map { (x, y) ->
            AccessibilityGestureMath.clampPoint(x, y, dm.widthPixels, dm.heightPixels)
        }
        val path = Path().apply {
            moveTo(clamped[0].x, clamped[0].y)
            for (i in 1 until clamped.size) {
                lineTo(clamped[i].x, clamped[i].y)
            }
        }
        return gesture(path, durationMs.coerceIn(60L, 10_000L))
    }

    data class EditableFieldSnapshot(
        val label: String,
        val value: String,
        val hint: String,
        val isFocused: Boolean,
        val isPassword: Boolean,
        val bounds: Rect
    )

    data class ScreenContentSnapshot(
        val packageName: String,
        val activityClass: String,
        val readableLines: List<String>,
        val editableFields: List<EditableFieldSnapshot>,
        val interactiveLabels: List<String>,
        val isKeyboardVisible: Boolean
    ) {
        fun toFormattedString(): String {
            val out = StringBuilder()
            out.append("App: ").append(packageName.ifBlank { "unknown" })
            if (activityClass.isNotBlank()) out.append(" (").append(activityClass.substringAfterLast('.')).append(')')
            out.append(" | Keyboard: ").append(if (isKeyboardVisible) "open" else "closed").append('\n')

            if (readableLines.isNotEmpty()) {
                out.append("\nScreen Content:\n")
                for (line in readableLines) {
                    out.append("- ").append(line).append('\n')
                }
            } else {
                out.append("\nScreen Content: (no readable text nodes found; try android_screenshot)\n")
            }

            if (editableFields.isNotEmpty()) {
                out.append("\nEditable Fields:\n")
                for (field in editableFields) {
                    val valueShown = if (field.isPassword && field.value.isNotEmpty()) "••••" else field.value
                    out.append("- ").append(field.label.ifBlank { "Text field" })
                    if (valueShown.isNotEmpty()) out.append(" = \"").append(valueShown.take(120)).append('"')
                    if (field.hint.isNotEmpty()) out.append(" (hint: \"").append(field.hint.take(60)).append("\")")
                    if (field.isFocused) out.append(" [focused]")
                    out.append(" @").append(field.bounds.centerX()).append(',').append(field.bounds.centerY()).append('\n')
                }
            }

            if (interactiveLabels.isNotEmpty()) {
                out.append("\nKey Actions: ").append(interactiveLabels.joinToString(" | ")).append('\n')
            }

            return out.toString().trim()
        }
    }

    /**
     * Reads structured, deduplicated screen content in top-to-bottom visual reading order,
     * masking password inputs and summarizing editable fields and key interactive controls.
     */
    fun readScreenContent(
        includeAllWindows: Boolean = false,
        maxBlocks: Int = 250
    ): ScreenContentSnapshot {
        val roots = if (includeAllWindows) {
            getAllRootNodes()
        } else {
            listOfNotNull(getForegroundAppRootNode() ?: rootInActiveWindow)
        }

        val activeRoot = roots.firstOrNull()
        val pkg = activeRoot?.packageName?.toString() ?: foregroundPackage.orEmpty()
        val act = foregroundClass.orEmpty()

        val rawBlocks = mutableListOf<AccessibilityGestureMath.RawTextBlock>()
        val editableFields = mutableListOf<EditableFieldSnapshot>()
        val interactiveSet = LinkedHashSet<String>()
        var visited = 0
        val maxNodes = (maxBlocks * 4).coerceIn(200, 2000)

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes || depth > 35) return
            visited++
            if (!node.isVisibleToUser) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else ""
            val isHeading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.isHeading else false
            val isPassword = node.isPassword
            val isEditable = node.isEditable || node.className?.toString()?.contains("EditText", true) == true

            val displayText = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }

            if (displayText.isNotBlank() && bounds.width() > 0 && bounds.height() > 0 && rawBlocks.size < maxBlocks) {
                rawBlocks.add(
                    AccessibilityGestureMath.RawTextBlock(
                        text = displayText.take(300),
                        top = bounds.top,
                        left = bounds.left,
                        isPassword = isPassword,
                        isHeading = isHeading,
                        isEditable = isEditable
                    )
                )
            }

            if (isEditable && editableFields.size < 25) {
                val resName = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                val label = desc.ifBlank { hint.ifBlank { resName } }
                editableFields.add(
                    EditableFieldSnapshot(
                        label = label,
                        value = text,
                        hint = hint,
                        isFocused = node.isFocused,
                        isPassword = isPassword,
                        bounds = Rect(bounds)
                    )
                )
            }

            if ((node.isClickable || node.isCheckable) && !isEditable && displayText.isNotBlank() && interactiveSet.size < 40) {
                val stateTag = if (node.isCheckable) (if (node.isChecked) " [ON]" else " [OFF]") else ""
                interactiveSet.add(displayText.take(60) + stateTag)
            }

            for (i in 0 until node.childCount) {
                if (visited >= maxNodes) break
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        for (root in roots) {
            walk(root, 0)
        }

        val orderedLines = AccessibilityGestureMath.organizeReadableBlocks(rawBlocks)
        return ScreenContentSnapshot(
            packageName = pkg,
            activityClass = act,
            readableLines = orderedLines.take(maxBlocks),
            editableFields = editableFields,
            interactiveLabels = interactiveSet.toList(),
            isKeyboardVisible = isKeyboardVisible()
        )
    }

    data class ScrollFindResult(
        val found: Boolean,
        val tapped: Boolean,
        val scrollsPerformed: Int,
        val matchedLabel: String = "",
        val bounds: Rect? = null,
        val reason: String
    )

    /**
     * Scrolls in [direction] until a visible node matching [query] (by text, description, hint, or resource id)
     * appears, or until the screen stops changing / [maxScrolls] is reached. Optionally taps the match.
     */
    suspend fun scrollToFind(
        query: String,
        direction: String = "down",
        maxScrolls: Int = 8,
        tapWhenFound: Boolean = false
    ): ScrollFindResult {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) {
            return ScrollFindResult(false, false, 0, reason = "Search query cannot be empty.")
        }

        fun findMatch(root: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo, String>? {
            root ?: return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 600) {
                val node = queue.removeFirst()
                visited++
                if (!node.isVisibleToUser) continue
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
                val resId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                if (text.lowercase(Locale.ROOT).contains(cleanQuery) ||
                    desc.lowercase(Locale.ROOT).contains(cleanQuery) ||
                    hint.lowercase(Locale.ROOT).contains(cleanQuery) ||
                    resId.lowercase(Locale.ROOT).contains(cleanQuery)
                ) {
                    return node to (text.ifBlank { desc.ifBlank { hint.ifBlank { resId } } })
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }

        val boundedScrolls = maxScrolls.coerceIn(0, 20)
        var previousSig: com.example.gemini.data.automation.agent.ScreenSignature? = null
        var unchangedCount = 0

        for (step in 0..boundedScrolls) {
            val root = getForegroundAppRootNode() ?: rootInActiveWindow
            val hit = findMatch(root)
            if (hit != null) {
                val (node, label) = hit
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val tapped = if (tapWhenFound) {
                    val ok = clickNodeAndTap(node)
                    if (ok) awaitIdle()
                    ok
                } else false
                return ScrollFindResult(
                    found = true,
                    tapped = tapped,
                    scrollsPerformed = step,
                    matchedLabel = label,
                    bounds = rect,
                    reason = if (tapped) "Found and tapped '$label' after $step scroll(s)."
                    else "Found '$label' after $step scroll(s)."
                )
            }

            if (step == boundedScrolls) break

            val currentSig = ScreenSignatureCalculator.computeSignature(root)
            if (previousSig != null && currentSig.isEquivalentTo(previousSig)) {
                unchangedCount++
                if (unchangedCount >= 2) {
                    return ScrollFindResult(
                        found = false,
                        tapped = false,
                        scrollsPerformed = step,
                        reason = "Reached end of scrollable content after $step scroll(s) without finding '$query'."
                    )
                }
            } else {
                unchangedCount = 0
                previousSig = currentSig
            }

            val scrolled = performSmartScroll(direction, "medium")
            if (!scrolled) {
                return ScrollFindResult(
                    found = false,
                    tapped = false,
                    scrollsPerformed = step,
                    reason = "Scroll gesture was rejected at step $step."
                )
            }
            awaitIdle(quietMs = 450, maxMs = 2_000)
        }

        return ScrollFindResult(
            found = false,
            tapped = false,
            scrollsPerformed = boundedScrolls,
            reason = "'$query' not found after $boundedScrolls scroll(s)."
        )
    }

    /**
     * Executes a semantic accessibility action on [node].
     */
    fun performNodeAction(node: AccessibilityNodeInfo, action: String, arg: String? = null): Boolean {
        return when (action.trim().lowercase(Locale.ROOT)) {
            "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "focus" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "clear_focus" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            "select" -> node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "paste" -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "cut" -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "expand" -> node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
            "collapse" -> node.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)
            "dismiss" -> node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
            "clear_text" -> node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            )
            "set_progress" -> {
                val value = arg?.toFloatOrNull() ?: return false
                node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
                )
            }
            else -> false
        }
    }

    suspend fun readClipboardText(): String? = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            clip.getItemAt(0)?.coerceToText(this@AutomationAccessibilityService)?.toString()
        }.getOrNull()
    }

    suspend fun writeClipboardText(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching false
            clipboard.setPrimaryClip(ClipData.newPlainText("AntiGemAutomation", text))
            true
        }.getOrDefault(false)
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

    data class SettingToggleOutcome(
        val handled: Boolean,
        val verifiedState: Boolean? = null,
        val toggled: Boolean = false,
        val message: String
    )

    private data class MatchedSettingTarget(
        val labelText: String,
        val labelNode: AccessibilityNodeInfo,
        val rowNode: AccessibilityNodeInfo,
        val switchNode: AccessibilityNodeInfo?,
        val actionButtonImpliedState: Boolean? = null
    )

    private fun findMatchedSettingTarget(
        roots: List<AccessibilityNodeInfo>,
        rowLabels: List<String>,
        actionButtonLabels: List<String> = emptyList()
    ): MatchedSettingTarget? {
        val candidates = mutableListOf<Triple<Int, AccessibilityNodeInfo, String>>()
        val lblRect = Rect()
        val swRect = Rect()

        for (root in roots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var visited = 0
            while (q.isNotEmpty() && visited < 400) {
                val node = q.removeFirst()
                visited++
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val combined = text.ifBlank { desc }
                if (combined.isNotBlank()) {
                    val lower = combined.lowercase(Locale.ROOT)
                    rowLabels.forEachIndexed { idx, want ->
                        val wantLow = want.lowercase(Locale.ROOT)
                        if (lower == wantLow) {
                            candidates.add(Triple(idx * 2, node, combined))
                        } else if (lower.contains(wantLow)) {
                            candidates.add(Triple(idx * 2 + 1, node, combined))
                        }
                    }
                    actionButtonLabels.forEachIndexed { idx, want ->
                        val wantLow = want.lowercase(Locale.ROOT)
                        if (lower == wantLow || lower.contains(wantLow)) {
                            candidates.add(Triple(100 + idx, node, combined))
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { q.add(it) }
                }
            }
        }

        val sorted = candidates.sortedBy { it.first }
        for ((score, lblNode, lblText) in sorted) {
            if (score >= 100) {
                val impliedOn = lblText.lowercase(Locale.ROOT).contains("turn off") ||
                    lblText.lowercase(Locale.ROOT).contains("disable")
                return MatchedSettingTarget(
                    labelText = lblText,
                    labelNode = lblNode,
                    rowNode = lblNode,
                    switchNode = null,
                    actionButtonImpliedState = impliedOn
                )
            }
            if (lblNode.isCheckable) {
                return MatchedSettingTarget(lblText, lblNode, lblNode, lblNode)
            }
            lblNode.getBoundsInScreen(lblRect)
            var anc: AccessibilityNodeInfo? = lblNode.parent
            var depth = 0
            while (anc != null && depth < 4) {
                val switchInRow = findCheckableInSubtree(anc, lblRect.centerY(), swRect)
                if (switchInRow != null) {
                    return MatchedSettingTarget(lblText, lblNode, anc, switchInRow)
                }
                if (anc.isCheckable) {
                    return MatchedSettingTarget(lblText, lblNode, anc, anc)
                }
                anc = anc.parent
                depth++
            }
        }

        // Second pass: clickable submenu item without switch (e.g. "Wi-Fi hotspot")
        val firstNonSwitch = sorted.firstOrNull { it.first < 100 }
        if (firstNonSwitch != null) {
            val (_, lblNode, lblText) = firstNonSwitch
            var clickable: AccessibilityNodeInfo = lblNode
            var anc = lblNode.parent
            var depth = 0
            while (anc != null && depth < 4 && !clickable.isClickable) {
                if (anc.isClickable) clickable = anc
                anc = anc.parent
                depth++
            }
            return MatchedSettingTarget(lblText, lblNode, clickable, null)
        }
        return null
    }

    private fun findCheckableInSubtree(
        container: AccessibilityNodeInfo,
        labelCenterY: Int,
        tmpRect: Rect
    ): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(container)
        var n = 0
        while (q.isNotEmpty() && n < 24) {
            val cur = q.removeFirst()
            n++
            val cls = cur.className?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (cur.isCheckable || cls.contains("switch") || cls.contains("toggle") || cls.contains("checkbox")) {
                cur.getBoundsInScreen(tmpRect)
                if (kotlin.math.abs(tmpRect.centerY() - labelCenterY) <= 180) {
                    return cur
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { q.add(it) }
            }
        }
        return null
    }

    private fun hasClickableConfirmButton(root: AccessibilityNodeInfo): Boolean {
        val confirmTerms = setOf("turn off", "turn on", "agree", "allow", "enable", "ok")
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 60) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (node.isClickable && t in confirmTerms) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    private suspend fun clickConfirmDialogIfPresent(): Boolean {
        val confirmTerms = listOf("turn off", "turn on", "agree", "allow", "enable", "ok")
        val roots = getAllApplicationWindowsRootNodes(forceRefresh = true)
        for (r in roots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var n = 0
            while (q.isNotEmpty() && n < 80) {
                val node = q.removeFirst()
                n++
                val t = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (node.isVisibleToUser && node.isClickable && t in confirmTerms) {
                    if (clickNodeAndTap(node)) return true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
        }
        return false
    }

    private suspend fun dismissOpenedSettingsSurface(maxBacks: Int) {
        val roots = getAllApplicationWindowsRootNodes(forceRefresh = true)
        for (r in roots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var n = 0
            while (q.isNotEmpty() && n < 80) {
                val node = q.removeFirst()
                n++
                val t = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val d = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (node.isVisibleToUser && node.isClickable && (t == "done" || d == "done")) {
                    clickNodeAndTap(node)
                    return
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
        }
        repeat(maxBacks) {
            val pkg = getActiveRootNode()?.packageName?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (!pkg.contains("settings") && !pkg.contains("systemui")) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(280)
        }
    }

    /**
     * Waits for an opened Android Settings screen / Panel bottom sheet, locates [rowLabels],
     * verifies or toggles the Switch into [desiredState], confirms dialogs if prompted,
     * and cleanly dismisses the Settings panel/page (#13).
     */
    suspend fun toggleSettingSwitchAndDismiss(
        settingDisplayName: String,
        rowLabels: List<String>,
        desiredState: Boolean?,
        subPageLabels: List<String> = emptyList(),
        actionButtonLabels: List<String> = emptyList(),
        dismissAfter: Boolean = true
    ): SettingToggleOutcome {
        var roots = emptyList<AccessibilityNodeInfo>()
        var target: MatchedSettingTarget? = null
        val deadline = System.currentTimeMillis() + 2_400L

        while (System.currentTimeMillis() < deadline) {
            roots = getAllApplicationWindowsRootNodes(forceRefresh = true)
            val inSettings = roots.any {
                val p = it.packageName?.toString()?.lowercase(Locale.ROOT).orEmpty()
                p.contains("settings") || p == "com.android.systemui"
            }
            if (inSettings) {
                target = findMatchedSettingTarget(roots, rowLabels, actionButtonLabels)
                if (target != null) break
            }
            delay(130)
        }

        if (target == null) {
            performSmartScrollAndAwait("down", "medium")
            delay(360)
            roots = getAllApplicationWindowsRootNodes(forceRefresh = true)
            target = findMatchedSettingTarget(roots, rowLabels, actionButtonLabels)
        }

        if (target == null) {
            return SettingToggleOutcome(
                handled = false,
                message = "Opened $settingDisplayName screen, but could not locate '${rowLabels.firstOrNull().orEmpty()}' toggle automatically."
            )
        }

        var usedLevels = 1
        // Navigate once into nested submenu (e.g. Wireless Settings -> "Wi-Fi hotspot" -> Switch page)
        if (target.switchNode == null && target.actionButtonImpliedState == null && subPageLabels.isNotEmpty()) {
            val clickedMenu = target.rowNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                run {
                    val b = Rect(); target.rowNode.getBoundsInScreen(b); performClickAtRectAndAwait(b)
                }
            if (clickedMenu) {
                usedLevels = 2
                delay(520)
                roots = getAllApplicationWindowsRootNodes(forceRefresh = true)
                target = findMatchedSettingTarget(roots, subPageLabels + rowLabels, actionButtonLabels)
                    ?: return SettingToggleOutcome(
                        handled = false,
                        message = "Opened $settingDisplayName submenu, but switch control was not exposed."
                    )
            }
        }

        val currentState: Boolean? = target.switchNode?.isChecked ?: target.actionButtonImpliedState
        if (desiredState != null && currentState == desiredState) {
            if (dismissAfter) dismissOpenedSettingsSurface(usedLevels)
            return SettingToggleOutcome(
                handled = true,
                verifiedState = currentState,
                toggled = false,
                message = "$settingDisplayName is already ${if (currentState) "ON" else "OFF"} (verified)."
            )
        }

        val clickNode = target.switchNode ?: target.rowNode
        val b = Rect()
        clickNode.getBoundsInScreen(b)
        val clicked = (clickNode.isClickable && clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
            (target.rowNode.isClickable && target.rowNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
            performClickAtRectAndAwait(b)
        if (!clicked) {
            return SettingToggleOutcome(
                handled = false,
                message = "Could not tap $settingDisplayName toggle on Settings screen."
            )
        }

        delay(420)
        if (clickConfirmDialogIfPresent()) {
            delay(400)
        }

        val afterRoots = getAllApplicationWindowsRootNodes(forceRefresh = true)
        val afterTarget = findMatchedSettingTarget(afterRoots, subPageLabels + rowLabels, actionButtonLabels)
        val afterState = afterTarget?.switchNode?.isChecked ?: afterTarget?.actionButtonImpliedState
        val verified = when {
            desiredState != null && afterState != null -> afterState == desiredState
            currentState != null && afterState != null -> afterState != currentState
            else -> true
        }

        if (dismissAfter) {
            dismissOpenedSettingsSurface(usedLevels)
        }

        val stateWord = when (afterState ?: desiredState) {
            true -> "ON"
            false -> "OFF"
            null -> "toggled"
        }
        return SettingToggleOutcome(
            handled = verified,
            verifiedState = afterState ?: desiredState,
            toggled = true,
            message = if (verified) "$settingDisplayName turned $stateWord (verified)."
            else "$settingDisplayName toggle was tapped, current verified state is ${if (afterState == true) "ON" else "OFF"}."
        )
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
