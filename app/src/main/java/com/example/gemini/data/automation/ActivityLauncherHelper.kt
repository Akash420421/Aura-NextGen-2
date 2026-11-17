package com.example.gemini.data.automation

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Universal helper to safely launch activities from background services
 * across Android 10, 11, 12, 13, 14, 15 without getting blocked by Android's
 * Background Activity Launch (BAL) restrictions.
 */
object ActivityLauncherHelper {

    private const val TAG = "ActivityLauncherHelper"
    private const val LAUNCHER_CHANNEL_ID = "aura_activity_launcher"
    private const val LAUNCHER_NOTIFICATION_ID = 8899

    fun startActivitySafely(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 1. If AutomationAccessibilityService is running, use it directly.
        // Android explicitly exempts AccessibilityService from BAL restrictions.
        val a11y = AutomationAccessibilityService.instance
        if (a11y != null) {
            try {
                a11y.startActivity(intent)
                Log.d(TAG, "Launched activity via AccessibilityService: ${intent.component ?: intent.`package` ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "AccessibilityService.startActivity failed: ${e.message}")
            }
        }

        // 2. If SYSTEM_ALERT_WINDOW (overlay permission) is granted:
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.d(TAG, "Launched activity via Overlay exemption: ${intent.component ?: intent.`package` ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Overlay-exempt context.startActivity failed: ${e.message}")
            }
        }

        // 3. PendingIntent approach with background activity start allowance (Android 14+ / API 34)
        try {
            val requestCode = (System.currentTimeMillis() % 10000).toInt()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(context, requestCode, intent, flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            Log.d(TAG, "Launched activity via PendingIntent: ${intent.component ?: intent.`package` ?: intent.action}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send failed: ${e.message}")
        }

        // 4. Context fallback
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct startActivity fallback failed: ${e.message}")
            false
        }
    }
}
