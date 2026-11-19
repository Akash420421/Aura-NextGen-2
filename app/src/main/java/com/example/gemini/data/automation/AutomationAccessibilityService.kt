package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import kotlinx.coroutines.*
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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var lastEventAt = 0L
        private set
    @Volatile var foregroundPackage: String? = null
        private set
    @Volatile var foregroundClass: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "AutomationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
            event.className?.toString()?.let { foregroundClass = it }
            AuraScreenObserver.processAccessibilityEvent(event, getActiveRootNode())
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

    fun getActiveRootNode(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null) return active

        try {
            val focusedWin = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }
            if (focusedWin?.root != null) return focusedWin.root

            val activeWin = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
            }
            if (activeWin?.root != null) return activeWin.root
        } catch (_: Exception) {}
        return null
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
                (className.contains("imageview") && (desc.isBlank() || desc.contains("profile") || desc.contains("photo")))
    }

    /**
     * Safely clicks a contact or group name, strictly avoiding clicking the profile picture/avatar.
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
                    if (clickNodeAndTap(node)) {
                        Log.i(TAG, "Clicked contact name safely (avoided avatar): '$targetName'")
                        return true
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
