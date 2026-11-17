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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
            event.className?.toString()?.let { foregroundClass = it }
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
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
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

    fun performScroll(direction: String): Boolean {
        val root = getActiveRootNode() ?: return false
        val action = when (direction.lowercase(Locale.ROOT)) {
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return root.performAction(action)
    }

    fun scheduleWhatsAppAutoSend() {
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = getActiveRootNode()
                if (root != null) {
                    val sendNode = findWhatsAppSendButton(root)
                    if (sendNode != null) {
                        clickNodeAndTap(sendNode)
                        Log.d(TAG, "Tapped WhatsApp send button via Accessibility on attempt $attempts!")
                        return
                    }
                }
                attempts++
                if (attempts < 14) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun findWhatsAppSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

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
