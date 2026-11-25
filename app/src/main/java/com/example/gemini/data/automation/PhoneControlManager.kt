package com.example.gemini.data.automation

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class PhoneControlManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneControlManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var isFlashlightOn = false

    fun setBrightnessPercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        if (!Settings.System.canWrite(context)) {
            return "Please grant System Settings permission to allow brightness adjustment."
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
            "Screen brightness set to $clamped percent."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
            "Failed to change brightness."
        }
    }

    fun adjustBrightness(increase: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            return "Please grant System Settings permission to allow brightness adjustment."
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

    fun setVolumePercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = ((clamped / 100.0) * maxVolume).roundToInt().coerceIn(0, maxVolume)

        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
            if (clamped == 0) "Media volume muted." else "Volume set to $clamped percent."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}", e)
            "Could not change volume."
        }
    }

    fun adjustVolume(increase: Boolean): String {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = max(1, (maxVolume * 0.15).roundToInt())
        val targetVolume = (if (increase) currentVolume + step else currentVolume - step).coerceIn(0, maxVolume)

        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
            val percent = ((targetVolume / maxVolume.toFloat()) * 100).roundToInt()
            if (increase) "Volume increased to $percent percent." else "Volume decreased to $percent percent."
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume: ${e.message}", e)
            "Failed to adjust volume."
        }
    }

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraId = findTorchCameraId() ?: return "No flashlight hardware detected on this device."
            cameraManager.setTorchMode(cameraId, enabled)
            isFlashlightOn = enabled
            if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode: ${e.message}", e)
            "Unable to access the flashlight."
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
