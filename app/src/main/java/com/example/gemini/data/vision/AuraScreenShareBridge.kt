package com.example.gemini.data.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.example.gemini.data.live.AuraLiveVoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AuraScreenShareBridge: Global coordinator for screen share capture.
 * Provides:
 * 1. AI trigger: AI can call `request_screen_share` to pop up Android's native screen recording permission dialog.
 * 2. Permanent Screen Share Button in Chat and Voice Call UI.
 * 3. Video frame piping into GeminiLiveWebSocket.
 */
object AuraScreenShareBridge {
    private const val TAG = "AuraScreenShareBridge"

    var requestLauncher: (() -> Unit)? = null

    val isSharing: StateFlow<Boolean>
        get() = ScreenCaptureManager.getInstance().isSharing

    fun requestScreenShare(): String {
        Log.i(TAG, "Requesting screen share permission...")
        val launcher = requestLauncher
        return if (launcher != null) {
            launcher.invoke()
            "Screen share prompt triggered on user's screen. If user approves, you will receive real-time video frames."
        } else {
            "Screen share launcher not ready yet. Please try again in a moment."
        }
    }

    fun stopScreenShare() {
        Log.i(TAG, "Stopping screen share...")
        ScreenCaptureManager.getInstance().stopCapture()
    }

    fun toggleScreenShare() {
        if (ScreenCaptureManager.getInstance().isSharing.value) {
            stopScreenShare()
        } else {
            requestScreenShare()
        }
    }

    fun onScreenCaptureResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager != null) {
                try {
                    val projection = projectionManager.getMediaProjection(resultCode, data)
                    if (projection != null) {
                        val metrics = context.resources.displayMetrics
                        val started = ScreenCaptureManager.getInstance().startCapture(projection, metrics) { frameJpeg ->
                            // Stream real-time JPEG frame to Gemini Live
                            AuraLiveVoiceManager.sendVideoFrame(frameJpeg)
                        }
                        if (started) {
                            Log.i(TAG, "Screen sharing successfully started and piping to Gemini Live")
                        } else {
                            Log.e(TAG, "Failed to start ScreenCaptureManager")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating screen capture projection: ${e.message}", e)
                }
            }
        } else {
            Log.w(TAG, "Screen share permission was denied by user")
        }
    }
}
