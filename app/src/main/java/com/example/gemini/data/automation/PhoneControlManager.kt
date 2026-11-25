package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class PhoneControlManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneControlManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile
    private var isFlashlightOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            isFlashlightOn = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            isFlashlightOn = false
        }
    }

    init {
        runCatching {
            cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    /**
     * Returns comprehensive live phone telemetry (battery %, charging/temp/voltage, RAM, storage,
     * network, display brightness, all audio stream levels, ringer/DND, flashlight, permissions).
     * Works immediately without requiring Accessibility Service.
     */
    fun getDeviceStatusJson(
        sections: String = "battery,audio,display,network,memory,storage,system"
    ): JSONObject {
        val info = AndroidAutomation.getDeviceInfoJson(context, sections)
        val nm = context.getSystemService(NotificationManager::class.java)
        val controls = JSONObject().apply {
            put("flashlight_on", isFlashlightOn)
            put("write_settings_granted", Settings.System.canWrite(context))
            put(
                "dnd_policy_access_granted",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true
            )
            put("accessibility_connected", AutomationAccessibilityService.instance != null)
            put("media_volume_percent", getStreamVolumePercent(AudioManager.STREAM_MUSIC))
            put("ring_volume_percent", getStreamVolumePercent(AudioManager.STREAM_RING))
            put("alarm_volume_percent", getStreamVolumePercent(AudioManager.STREAM_ALARM))
            put("notification_volume_percent", getStreamVolumePercent(AudioManager.STREAM_NOTIFICATION))
            put("call_volume_percent", getStreamVolumePercent(AudioManager.STREAM_VOICE_CALL))
            runCatching {
                val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                put("brightness_percent", ((cur / 255.0) * 100).roundToInt().coerceIn(0, 100))
            }
        }
        info.put("hardware_controls", controls)
        return info
    }

    fun getStreamVolumePercent(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
        return ((curVol / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun resolveAudioStreams(stream: String): List<Pair<String, Int>> {
        return when (stream.trim().lowercase(Locale.ROOT)) {
            "ring", "ringer", "ringtone", "call_ring" -> listOf("ring" to AudioManager.STREAM_RING)
            "alarm", "alarms" -> listOf("alarm" to AudioManager.STREAM_ALARM)
            "notification", "notifications", "notif" -> listOf("notification" to AudioManager.STREAM_NOTIFICATION)
            "call", "voice", "voice_call", "incall" -> listOf("call" to AudioManager.STREAM_VOICE_CALL)
            "all" -> listOf(
                "media" to AudioManager.STREAM_MUSIC,
                "ring" to AudioManager.STREAM_RING,
                "alarm" to AudioManager.STREAM_ALARM,
                "notification" to AudioManager.STREAM_NOTIFICATION
            )
            else -> listOf("media" to AudioManager.STREAM_MUSIC)
        }
    }

    private fun openWriteSettingsPermissionScreen() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityLauncherHelper.startActivitySafely(context, intent)
    }

    fun setBrightnessPercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return "Opened 'Modify System Settings' permission screen for Aura. Please grant permission so brightness can be changed."
        }

        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            val brightnessValue = ((clamped / 100.0) * 255).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            // Modern Pixel / OnePlus / Samsung OEMs respect float brightness alongside 0..255 int
            runCatching {
                Settings.System.putFloat(
                    context.contentResolver,
                    "screen_brightness_float",
                    (clamped / 100.0f).coerceIn(0.01f, 1.0f)
                )
            }
            val readBack = runCatching {
                val actualInt = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                ((actualInt / 255.0) * 100).roundToInt().coerceIn(0, 100)
            }.getOrDefault(clamped)
            "Screen brightness set and verified at $readBack percent."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
            "Failed to change brightness: ${e.message}"
        }
    }

    fun setAutoBrightness(enabled: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return "Opened 'Modify System Settings' permission screen for Aura. Please enable it to toggle Adaptive Brightness."
        }
        return try {
            val mode = if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            "Adaptive/Auto brightness turned ${if (enabled) "on" else "off"}."
        } catch (e: Exception) {
            "Failed to set auto brightness: ${e.message}"
        }
    }

    fun adjustBrightness(increase: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return "Opened 'Modify System Settings' permission screen for Aura so brightness can be adjusted."
        }

        val currentVal = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            128
        }
        val currentPercent = ((currentVal / 255.0) * 100).roundToInt()
        val delta = if (increase) 20 else -20
        return setBrightnessPercent(currentPercent + delta)
    }

    fun setVolumePercent(
        percent: Int,
        stream: String = "music",
        silentFeedback: Boolean = false
    ): String {
        val clamped = percent.coerceIn(0, 100)
        val flags = if (silentFeedback) AudioManager.FLAG_SHOW_UI else (AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
        val targets = resolveAudioStreams(stream)
        val reports = mutableListOf<String>()

        for ((label, streamType) in targets) {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val targetVol = ((clamped / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
            try {
                audioManager.setStreamVolume(streamType, targetVol, flags)
                val actualVol = audioManager.getStreamVolume(streamType)
                val actualPct = ((actualVol / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                reports.add("$label=$actualPct% ($actualVol/$maxVol)")
            } catch (se: SecurityException) {
                Log.w(TAG, "DND blocked changing $label volume: ${se.message}")
                val actualPct = getStreamVolumePercent(streamType)
                reports.add("$label blocked by Do Not Disturb (remains $actualPct%)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting $label volume: ${e.message}", e)
                reports.add("$label error: ${e.message}")
            }
        }
        return if (reports.size == 1 && reports[0].startsWith("media=")) {
            val actualPct = getStreamVolumePercent(AudioManager.STREAM_MUSIC)
            if (actualPct == 0) "Media volume muted (0%, readback verified)." else "Media volume set to $actualPct% (readback verified)."
        } else {
            "Volume updated: ${reports.joinToString(", ")}."
        }
    }

    fun adjustVolume(
        increase: Boolean,
        stream: String = "music",
        stepPercent: Int = 15,
        silentFeedback: Boolean = false
    ): String {
        val flags = if (silentFeedback) AudioManager.FLAG_SHOW_UI else (AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
        val targets = resolveAudioStreams(stream)
        val fraction = (stepPercent.coerceIn(5, 50) / 100.0)
        val reports = mutableListOf<String>()

        for ((label, streamType) in targets) {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val currentVol = audioManager.getStreamVolume(streamType)
            val step = max(1, (maxVol * fraction).roundToInt())
            val targetVol = (if (increase) currentVol + step else currentVol - step).coerceIn(0, maxVol)
            try {
                audioManager.setStreamVolume(streamType, targetVol, flags)
                val actualVol = audioManager.getStreamVolume(streamType)
                val actualPct = ((actualVol / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                reports.add("$label=$actualPct% ($actualVol/$maxVol)")
            } catch (se: SecurityException) {
                reports.add("$label blocked by Do Not Disturb")
            } catch (e: Exception) {
                reports.add("$label failed: ${e.message}")
            }
        }
        return if (reports.size == 1 && reports[0].startsWith("media=")) {
            val pct = getStreamVolumePercent(AudioManager.STREAM_MUSIC)
            val verb = if (increase) "increased" else "decreased"
            "Media volume $verb to $pct% (readback verified)."
        } else {
            "Volume adjusted: ${reports.joinToString(", ")}."
        }
    }

    fun setStreamMuted(mute: Boolean, stream: String = "music", silentFeedback: Boolean = true): String {
        val flags = if (silentFeedback) AudioManager.FLAG_SHOW_UI else (AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
        val targets = resolveAudioStreams(stream)
        val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val reports = mutableListOf<String>()

        for ((label, streamType) in targets) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(streamType, direction, flags)
                } else {
                    if (mute) audioManager.setStreamVolume(streamType, 0, flags)
                }
                val pct = getStreamVolumePercent(streamType)
                reports.add("$label=$pct%")
            } catch (se: SecurityException) {
                reports.add("$label blocked by Do Not Disturb")
            } catch (e: Exception) {
                reports.add("$label failed: ${e.message}")
            }
        }
        return "${if (mute) "Muted" else "Unmuted"}: ${reports.joinToString(", ")}."
    }

    fun setRingerMode(mode: String): String {
        val normalized = mode.trim().lowercase(Locale.ROOT)
        val targetMode = when (normalized) {
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
            "normal", "sound", "ring", "unmute" -> AudioManager.RINGER_MODE_NORMAL
            else -> return "Unknown ringer mode '$mode'. Use normal, vibrate, or silent."
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (targetMode == AudioManager.RINGER_MODE_SILENT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !nm.isNotificationPolicyAccessGranted
        ) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            return "Opened Do Not Disturb Access settings so Aura can switch ringer to Silent."
        }
        return try {
            audioManager.ringerMode = targetMode
            val readBack = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            "Ringer mode set and verified: $readBack."
        } catch (se: SecurityException) {
            "Android blocked ringer mode change (Do Not Disturb access needed)."
        } catch (e: Exception) {
            "Failed to set ringer mode: ${e.message}"
        }
    }

    fun setDndEnabled(enabled: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "Do Not Disturb toggle requires Android 6+."
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            return "Opened Do Not Disturb Policy Access settings for Aura so DND can be toggled."
        }
        return try {
            val filter = if (enabled) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            val nowEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            "Do Not Disturb is now ${if (nowEnabled) "ON" else "OFF"} (verified)."
        } catch (e: Exception) {
            "Failed to toggle Do Not Disturb: ${e.message}"
        }
    }

    fun toggleFlashlight(): String = setFlashlight(!isFlashlightOn)

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraId = findTorchCameraId() ?: return "No flashlight hardware detected on this device."
            cameraManager.setTorchMode(cameraId, enabled)
            isFlashlightOn = enabled
            if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode: ${e.message}", e)
            "Unable to access the flashlight: ${e.message}"
        }
    }

    fun performSystemAction(action: String): String {
        val svc = AutomationAccessibilityService.instance
            ?: return "Accessibility Service is not running; cannot perform system action '$action'."
        val norm = action.trim().lowercase(Locale.ROOT)
        val globalCode = when (norm) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade", "open_notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "control_center", "tiles" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power", "power_dialog", "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock", "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else {
                return "Lock screen requires Android 9+."
            }
            "screenshot", "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            } else {
                return "Screenshot global action requires Android 9+."
            }
            "dismiss_notifications", "close_notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else {
                AccessibilityService.GLOBAL_ACTION_BACK
            }
            else -> return "Unknown system action '$action'."
        }
        val ok = svc.performGlobalAction(globalCode)
        return if (ok) "Executed system action '$norm'." else "Android rejected system action '$norm'."
    }

    fun openDevicePanel(setting: String): String {
        val norm = setting.trim().lowercase(Locale.ROOT)
        val panelOrIntent: Pair<String, Intent> = when (norm) {
            "wifi", "wi-fi" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                "Wi-Fi Panel" to Intent(action)
            }
            "internet", "mobile_data", "data", "cellular" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
                "Internet & Connectivity Panel" to Intent(action)
            }
            "bluetooth", "bt" -> "Bluetooth Settings" to Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "volume_panel", "volume", "sound" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                "Sound & Volume Panel" to Intent(action)
            }
            "nfc" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                "NFC Panel" to Intent(action)
            }
            "location", "gps" -> "Location Settings" to Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane", "flight_mode" -> "Airplane Mode / Network Settings" to Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "battery", "battery_saver" -> "Battery Saver Settings" to Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "display", "brightness", "dark_mode" -> "Display Settings" to Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "hotspot", "tethering" -> "Hotspot & Tethering Settings" to Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "storage" -> "Storage Settings" to Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "accessibility" -> "Accessibility Settings" to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications", "dnd" -> "Notification / DND Settings" to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "settings" -> "Device Settings" to Intent(Settings.ACTION_SETTINGS)
            else -> "Settings ($norm)" to Intent(Settings.ACTION_SETTINGS)
        }
        val intent = panelOrIntent.second.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val ok = ActivityLauncherHelper.startActivitySafely(context, intent)
        return if (ok) {
            "Opened ${panelOrIntent.first}."
        } else {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (ActivityLauncherHelper.startActivitySafely(context, fallback)) {
                "Opened general Settings (direct '${panelOrIntent.first}' screen unavailable on this ROM)."
            } else {
                "Failed to open ${panelOrIntent.first}."
            }
        }
    }

    private fun findTorchCameraId(): String? {
        return try {
            val ids = cameraManager.cameraIdList
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (flashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    return id
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun parseTargetUrl(query: String): String? {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed

        // Exclude Android package names so they are never opened as broken web URLs in Chrome
        if (trimmed.startsWith("com.") || trimmed.startsWith("org.") || trimmed.startsWith("net.") ||
            trimmed.endsWith(".dialer") || trimmed.endsWith(".android") || trimmed.contains(".android.")) {
            return null
        }

        val validTlds = listOf("com", "org", "net", "io", "dev", "ai", "app", "in", "co", "gov", "edu", "me", "xyz", "online", "tech", "site")
        if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.contains("@")) {
            val tld = trimmed.substringAfterLast('.').lowercase(Locale.ROOT)
            if (validTlds.contains(tld)) {
                return "https://$trimmed"
            }
        }
        return null
    }

    fun extractUrlsFromText(text: String): List<String> {
        val urls = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        for (w in words) {
            val clean = w.trim(',', '.', ';', '(', ')')
            if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                urls.add(clean)
            } else if (clean.contains(".") && !clean.contains("@") && clean.length > 4) {
                val tld = clean.substringAfterLast('.').lowercase(Locale.ROOT)
                if (listOf("com", "org", "net", "io", "dev", "ai", "app", "in", "co").contains(tld)) {
                    urls.add("https://$clean")
                }
            }
        }
        return urls
    }

    suspend fun openUrlsInChromeTabs(urls: List<String>): Boolean {
        if (urls.isEmpty()) return false
        var anyLaunched = false

        for (rawUrl in urls) {
            val urlString = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
            val uri = Uri.parse(urlString)
            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
            }

            val launched = ActivityLauncherHelper.startActivitySafely(context, chromeIntent)
            if (launched) {
                anyLaunched = true
            } else {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, fallbackIntent)) {
                    anyLaunched = true
                }
            }
            delay(150)
        }
        return anyLaunched
    }

    fun dialNumber(context: Context, phoneNumber: String, autoCall: Boolean = false): Result<String> {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        if (cleanNumber.isBlank()) {
            return Result.failure(IllegalArgumentException("Phone number cannot be empty."))
        }

        val hasCallPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val action = if (autoCall && hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val telUri = Uri.parse("tel:${Uri.encode(cleanNumber)}")
        val intent = Intent(action, telUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (launched) {
                Result.success(cleanNumber)
            } else {
                val fallback = Intent(Intent.ACTION_DIAL, telUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, fallback)) {
                    Result.success(cleanNumber)
                } else {
                    Result.failure(Exception("Could not launch Phone dialer."))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dialNumber failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun searchYouTubeNative(context: Context, query: String): Boolean {
        val encoded = Uri.encode(query.trim())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
        if (launched) return true

        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityLauncherHelper.startActivitySafely(context, fallback)
    }
}
