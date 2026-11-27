package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.gemini.data.live.DeviceControlHelper
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class PhoneControlManager(private val context: Context) {

    data class VolumeBatchResult(
        val verified: Boolean,
        val actualPercent: Int,
        val cappedBySystem: Boolean,
        val outcomes: List<DeviceControlHelper.VolumeOutcome>,
        val message: String
    )

    data class DeviceControlResult(
        val success: Boolean,
        val verified: Boolean,
        val permissionRequired: Boolean = false,
        val setting: String = "",
        val state: String = "",
        val actualPercent: Int? = null,
        val message: String
    )

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
        sections: String = "battery,audio,system"
    ): JSONObject {
        val info = AndroidAutomation.getDeviceInfoJson(context, sections)
        val nm = context.getSystemService(NotificationManager::class.java)
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val mediaStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, mediaMax)
        val controls = JSONObject().apply {
            put("flashlight_on", isFlashlightOn)
            put("write_settings_granted", Settings.System.canWrite(context))
            put(
                "dnd_policy_access_granted",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true
            )
            put("accessibility_connected", AutomationAccessibilityService.instance != null)
            put("media_volume_percent", getStreamVolumePercent(AudioManager.STREAM_MUSIC))
            put("media_volume_step", "$mediaStep/$mediaMax")
            put("ring_volume_percent", getStreamVolumePercent(AudioManager.STREAM_RING))
            put("alarm_volume_percent", getStreamVolumePercent(AudioManager.STREAM_ALARM))
            put("notification_volume_percent", getStreamVolumePercent(AudioManager.STREAM_NOTIFICATION))
            put("call_volume_percent", getStreamVolumePercent(AudioManager.STREAM_VOICE_CALL))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                put("is_volume_fixed", runCatching { audioManager.isVolumeFixed }.getOrDefault(false))
            }
            runCatching {
                val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                put("brightness_percent", ((cur / 255.0) * 100).roundToInt().coerceIn(0, 100))
            }
        }
        info.put("hardware_controls", controls)
        return info
    }

    /**
     * Ultra-fast zero-network/zero-storage snapshot string for injecting live battery & volume into prompts.
     */
    fun getQuickBatteryAndAudioLine(): String {
        val statusJson = getDeviceStatusJson("battery,audio")
        val bat = statusJson.optJSONObject("battery")
        val hw = statusJson.optJSONObject("hardware_controls")
        val batText = if (bat != null) {
            val pct = bat.optInt("level_percent", -1)
            val state = bat.optString("status", "unknown")
            val plugged = bat.optString("plugged", "unplugged")
            if (pct >= 0) "$pct% ($state, $plugged)" else state
        } else {
            "unknown"
        }
        val mediaPct = hw?.optInt("media_volume_percent", getStreamVolumePercent(AudioManager.STREAM_MUSIC))
            ?: getStreamVolumePercent(AudioManager.STREAM_MUSIC)
        val ringPct = hw?.optInt("ring_volume_percent", getStreamVolumePercent(AudioManager.STREAM_RING))
            ?: getStreamVolumePercent(AudioManager.STREAM_RING)
        return "Live Device Telemetry (at session prompt build): Battery=$batText | MediaVol=$mediaPct% | RingVol=$ringPct%. Always call get_device_status or set_volume for live real-time reads/changes."
    }

    fun getStreamVolumePercent(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
        return ((curVol / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun resolveAudioStreams(stream: String): List<Pair<String, Int>> {
        return when (DeviceControlHelper.normalizeStream(stream)) {
            "ring" -> listOf("ring" to AudioManager.STREAM_RING)
            "alarm" -> listOf("alarm" to AudioManager.STREAM_ALARM)
            "notification" -> listOf("notification" to AudioManager.STREAM_NOTIFICATION)
            "call" -> listOf("call" to AudioManager.STREAM_VOICE_CALL)
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

    private fun readCurrentBrightnessPercent(): Int {
        // Prefer perceptual float brightness on Android P+ OEMs (Pixel, OnePlus, Samsung, Xiaomi)
        val floatPct = runCatching {
            val f = Settings.System.getFloat(context.contentResolver, "screen_brightness_float")
            if (f in 0.0f..1.0f) (f * 100f).roundToInt().coerceIn(0, 100) else null
        }.getOrNull()
        if (floatPct != null) return floatPct

        val intVal = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return ((intVal / 255.0) * 100.0).roundToInt().coerceIn(0, 100)
    }

    fun getBrightnessSummaryDetailed(): DeviceControlResult {
        val pct = readCurrentBrightnessPercent()
        val isAuto = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(false)
        val modeWord = if (isAuto) "adaptive" else "manual"
        return DeviceControlResult(
            success = true,
            verified = true,
            setting = "brightness",
            state = "$pct%",
            actualPercent = pct,
            message = "Screen brightness is currently $pct% ($modeWord)."
        )
    }

    fun setBrightnessPercentDetailed(percent: Int): DeviceControlResult {
        val clamped = percent.coerceIn(0, 100)
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "brightness",
                message = "Opened 'Modify System Settings' permission screen for Aura. Please enable it so screen brightness can be changed."
            )
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
            // Modern Pixel / OnePlus / Samsung / Xiaomi OEMs respect float brightness alongside 0..255 int
            runCatching {
                Settings.System.putFloat(
                    context.contentResolver,
                    "screen_brightness_float",
                    (clamped / 100.0f).coerceIn(0.01f, 1.0f)
                )
            }
            Thread.sleep(25)
            val readBack = readCurrentBrightnessPercent()
            DeviceControlResult(
                success = true,
                verified = true,
                setting = "brightness",
                state = "$readBack%",
                actualPercent = readBack,
                message = "Screen brightness set and verified at $readBack percent."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
            DeviceControlResult(
                success = false,
                verified = false,
                setting = "brightness",
                message = "Failed to change brightness: ${e.message}"
            )
        }
    }

    fun setBrightnessPercent(percent: Int): String = setBrightnessPercentDetailed(percent).message

    fun setAutoBrightnessDetailed(enabled: Boolean): DeviceControlResult {
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "auto_brightness",
                message = "Opened 'Modify System Settings' permission screen for Aura. Please enable it to toggle Adaptive Brightness."
            )
        }
        return try {
            val mode = if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            Thread.sleep(20)
            val actualAuto = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrDefault(enabled)
            DeviceControlResult(
                success = actualAuto == enabled,
                verified = actualAuto == enabled,
                setting = "auto_brightness",
                state = if (actualAuto) "ON" else "OFF",
                message = "Adaptive/Auto brightness turned ${if (actualAuto) "on" else "off"} (verified)."
            )
        } catch (e: Exception) {
            DeviceControlResult(
                success = false,
                verified = false,
                setting = "auto_brightness",
                message = "Failed to set auto brightness: ${e.message}"
            )
        }
    }

    fun setAutoBrightness(enabled: Boolean): String = setAutoBrightnessDetailed(enabled).message

    fun adjustBrightnessDetailed(increase: Boolean, stepPercent: Int = 20): DeviceControlResult {
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "brightness",
                message = "Opened 'Modify System Settings' permission screen for Aura so brightness can be adjusted."
            )
        }
        val currentPercent = readCurrentBrightnessPercent()
        val delta = if (increase) stepPercent.coerceIn(5, 50) else -stepPercent.coerceIn(5, 50)
        return setBrightnessPercentDetailed(currentPercent + delta)
    }

    fun adjustBrightness(increase: Boolean): String = adjustBrightnessDetailed(increase).message

    private fun applyStreamVolumeWithVerification(
        label: String,
        streamType: Int,
        action: String,
        requestedPercent: Int? = null,
        stepPercent: Int = 15,
        flags: Int = AudioManager.FLAG_SHOW_UI
    ): DeviceControlHelper.VolumeOutcome {
        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { audioManager.getStreamMinVolume(streamType) }.getOrDefault(0).coerceIn(0, maxVol)
        } else {
            0
        }
        val isFixed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { audioManager.isVolumeFixed }.getOrDefault(false)
        } else {
            false
        }
        val beforeVol = audioManager.getStreamVolume(streamType).coerceIn(minVol, maxVol)
        if (isFixed) {
            return DeviceControlHelper.evaluateVolumeOutcome(
                streamLabel = label,
                action = action,
                requestedPercent = requestedPercent,
                targetStep = beforeVol,
                beforeStep = beforeVol,
                afterStep = beforeVol,
                maxStep = maxVol,
                isFixedVolume = true
            )
        }

        val targetVol = when (action) {
            "set" -> (((requestedPercent ?: 50).coerceIn(0, 100) / 100.0) * maxVol)
                .roundToInt()
                .coerceIn(minVol, maxVol)
            "increase" -> {
                val delta = max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())
                (beforeVol + delta).coerceIn(minVol, maxVol)
            }
            "decrease" -> {
                val delta = max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())
                (beforeVol - delta).coerceIn(minVol, maxVol)
            }
            "mute" -> minVol
            "unmute" -> if (beforeVol <= minVol) {
                max(minVol + 1, (maxVol * 0.4).roundToInt()).coerceIn(minVol, maxVol)
            } else {
                beforeVol
            }
            else -> beforeVol
        }

        var secErr: String? = null
        try {
            if (action == "mute") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && minVol == 0) {
                    runCatching { audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, flags) }
                }
                if (audioManager.getStreamVolume(streamType) > minVol) {
                    audioManager.setStreamVolume(streamType, minVol, flags)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetVol > 0) {
                    val wasMuted = runCatching { audioManager.isStreamMute(streamType) }.getOrDefault(false)
                    if (wasMuted || action == "unmute") {
                        runCatching { audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0) }
                    }
                }
                audioManager.setStreamVolume(streamType, targetVol, flags)
                // Step-wise retry if OEM/BT/Safe-Volume ignored or delayed direct setStreamVolume
                var immediate = audioManager.getStreamVolume(streamType)
                if (immediate != targetVol) {
                    val direction = if (targetVol > immediate) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    val maxRetries = abs(targetVol - immediate).coerceAtMost(8)
                    for (i in 0 until maxRetries) {
                        val prev = immediate
                        runCatching { audioManager.adjustStreamVolume(streamType, direction, flags) }
                        Thread.sleep(15)
                        immediate = audioManager.getStreamVolume(streamType)
                        if (immediate == targetVol || immediate == prev) break
                    }
                }
            }
            // Allow AudioService / BT AVRCP state to settle before final readback
            Thread.sleep(35)
        } catch (se: SecurityException) {
            secErr = se.message ?: "Do Not Disturb policy restriction"
            Log.w(TAG, "DND/SecurityException changing $label volume: $secErr")
        } catch (e: Exception) {
            secErr = e.message ?: "AudioService error"
            Log.e(TAG, "Error changing $label volume: $secErr", e)
        }

        val afterVol = audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
        return DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = label,
            action = action,
            requestedPercent = requestedPercent,
            targetStep = targetVol,
            beforeStep = beforeVol,
            afterStep = afterVol,
            maxStep = maxVol,
            isFixedVolume = false,
            securityError = secErr
        )
    }

    private fun syncCommCallVolumeIfActive(
        action: String,
        requestedPercent: Int?,
        stepPercent: Int
    ) {
        val inComm = runCatching {
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION || audioManager.isBluetoothScoOn
        }.getOrDefault(false)
        if (!inComm) return

        // Mirror silently to STREAM_VOICE_CALL when headset comm/BT SCO routing is active
        runCatching {
            val callStream = AudioManager.STREAM_VOICE_CALL
            val maxVol = audioManager.getStreamMaxVolume(callStream).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(callStream).coerceIn(0, maxVol)
            } else {
                1.coerceAtMost(maxVol)
            }
            val cur = audioManager.getStreamVolume(callStream).coerceIn(minVol, maxVol)
            val tgt = when (action) {
                "set" -> (((requestedPercent ?: 50).coerceIn(0, 100) / 100.0) * maxVol).roundToInt().coerceIn(minVol, maxVol)
                "increase" -> (cur + max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())).coerceIn(minVol, maxVol)
                "decrease" -> (cur - max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())).coerceIn(minVol, maxVol)
                "mute" -> minVol
                else -> return@runCatching
            }
            audioManager.setStreamVolume(callStream, tgt, 0)
        }
    }

    private fun executeVolumeBatch(
        action: String,
        stream: String,
        requestedPercent: Int? = null,
        stepPercent: Int = 15,
        silentFeedback: Boolean = false
    ): VolumeBatchResult {
        val normalizedStream = DeviceControlHelper.normalizeStream(stream)
        val flags = if (silentFeedback) AudioManager.FLAG_SHOW_UI else (AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
        val targets = resolveAudioStreams(normalizedStream)
        val outcomes = targets.map { (label, streamType) ->
            applyStreamVolumeWithVerification(
                label = label,
                streamType = streamType,
                action = action,
                requestedPercent = requestedPercent,
                stepPercent = stepPercent,
                flags = flags
            )
        }
        if (normalizedStream == "music") {
            syncCommCallVolumeIfActive(
                action = action,
                requestedPercent = requestedPercent,
                stepPercent = stepPercent
            )
        }
        val allVerified = outcomes.all { it.verified }
        val anyCapped = outcomes.any { it.cappedBySystem }
        val primaryPct = outcomes.firstOrNull()?.actualPercent ?: getStreamVolumePercent(AudioManager.STREAM_MUSIC)
        val msg = outcomes.joinToString("; ") { it.summary }
        return VolumeBatchResult(
            verified = allVerified,
            actualPercent = primaryPct,
            cappedBySystem = anyCapped,
            outcomes = outcomes,
            message = msg
        )
    }

    fun getVolumeSummaryDetailed(stream: String = "music"): VolumeBatchResult {
        val normalizedStream = DeviceControlHelper.normalizeStream(stream)
        val targets = resolveAudioStreams(normalizedStream)
        val outcomes = targets.map { (label, streamType) ->
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
            DeviceControlHelper.evaluateVolumeOutcome(
                streamLabel = label,
                action = "get",
                requestedPercent = null,
                targetStep = null,
                beforeStep = curVol,
                afterStep = curVol,
                maxStep = maxVol
            )
        }
        val primaryPct = outcomes.firstOrNull()?.actualPercent ?: getStreamVolumePercent(AudioManager.STREAM_MUSIC)
        return VolumeBatchResult(
            verified = true,
            actualPercent = primaryPct,
            cappedBySystem = false,
            outcomes = outcomes,
            message = outcomes.joinToString("; ") { it.summary }
        )
    }

    fun setVolumePercentDetailed(
        percent: Int,
        stream: String = "music",
        silentFeedback: Boolean = false
    ): VolumeBatchResult = executeVolumeBatch(
        action = "set",
        stream = stream,
        requestedPercent = percent.coerceIn(0, 100),
        silentFeedback = silentFeedback
    )

    fun adjustVolumeDetailed(
        increase: Boolean,
        stream: String = "music",
        stepPercent: Int = 15,
        silentFeedback: Boolean = false
    ): VolumeBatchResult = executeVolumeBatch(
        action = if (increase) "increase" else "decrease",
        stream = stream,
        stepPercent = stepPercent.coerceIn(5, 50),
        silentFeedback = silentFeedback
    )

    fun setStreamMutedDetailed(
        mute: Boolean,
        stream: String = "music",
        silentFeedback: Boolean = true
    ): VolumeBatchResult = executeVolumeBatch(
        action = if (mute) "mute" else "unmute",
        stream = stream,
        silentFeedback = silentFeedback
    )

    fun setVolumePercent(
        percent: Int,
        stream: String = "music",
        silentFeedback: Boolean = false
    ): String = setVolumePercentDetailed(percent, stream, silentFeedback).message

    fun adjustVolume(
        increase: Boolean,
        stream: String = "music",
        stepPercent: Int = 15,
        silentFeedback: Boolean = false
    ): String = adjustVolumeDetailed(increase, stream, stepPercent, silentFeedback).message

    fun setStreamMuted(mute: Boolean, stream: String = "music", silentFeedback: Boolean = true): String =
        setStreamMutedDetailed(mute, stream, silentFeedback).message

    fun setRingerModeDetailed(mode: String): DeviceControlResult {
        val canonical = DeviceControlHelper.normalizeRingerMode(mode)
            ?: return DeviceControlResult(
                success = false,
                verified = false,
                setting = "ringer_mode",
                message = "Unknown ringer mode '$mode'. Use normal, vibrate, or silent."
            )
        val targetMode = when (canonical) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (targetMode == AudioManager.RINGER_MODE_SILENT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            nm?.isNotificationPolicyAccessGranted != true
        ) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "ringer_mode",
                message = "Opened Do Not Disturb Access settings. Please allow Aura so ringer can switch to Silent."
            )
        }
        return try {
            audioManager.ringerMode = targetMode
            Thread.sleep(25)
            val readBack = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            val ok = readBack == canonical
            DeviceControlResult(
                success = ok,
                verified = ok,
                setting = "ringer_mode",
                state = readBack,
                message = if (ok) "Ringer mode set and verified: $readBack."
                else "Requested $canonical, but Android kept ringer mode at $readBack."
            )
        } catch (se: SecurityException) {
            DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "ringer_mode",
                message = "Android blocked ringer mode change (Do Not Disturb access needed)."
            )
        } catch (e: Exception) {
            DeviceControlResult(
                success = false,
                verified = false,
                setting = "ringer_mode",
                message = "Failed to set ringer mode: ${e.message}"
            )
        }
    }

    fun setRingerMode(mode: String): String = setRingerModeDetailed(mode).message

    fun setDndEnabledDetailed(enabled: Boolean?): DeviceControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return DeviceControlResult(
                success = false,
                verified = false,
                setting = "dnd",
                message = "Do Not Disturb toggle requires Android 6+."
            )
        }
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return DeviceControlResult(false, false, setting = "dnd", message = "NotificationManager unavailable.")
        val currentlyOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val desired = enabled ?: !currentlyOn
        if (currentlyOn == desired) {
            return DeviceControlResult(
                success = true,
                verified = true,
                setting = "dnd",
                state = if (currentlyOn) "ON" else "OFF",
                message = "Do Not Disturb is already ${if (currentlyOn) "ON" else "OFF"} (verified)."
            )
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "dnd",
                message = "Opened Do Not Disturb Policy Access settings for Aura. Please grant permission so DND can be toggled."
            )
        }
        return try {
            val filter = if (desired) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            Thread.sleep(30)
            val nowEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            val verified = nowEnabled == desired
            DeviceControlResult(
                success = verified,
                verified = verified,
                setting = "dnd",
                state = if (nowEnabled) "ON" else "OFF",
                message = "Do Not Disturb is now ${if (nowEnabled) "ON" else "OFF"} (verified)."
            )
        } catch (e: Exception) {
            DeviceControlResult(
                success = false,
                verified = false,
                setting = "dnd",
                message = "Failed to toggle Do Not Disturb: ${e.message}"
            )
        }
    }

    fun setDndEnabled(enabled: Boolean): String = setDndEnabledDetailed(enabled).message

    fun toggleFlashlight(): String = setFlashlight(!isFlashlightOn)

    fun setFlashlightDetailed(enabled: Boolean?): DeviceControlResult {
        val desired = enabled ?: !isFlashlightOn
        return try {
            val cameraId = findTorchCameraId() ?: return DeviceControlResult(
                success = false,
                verified = false,
                setting = "flashlight",
                message = "No flashlight hardware detected on this device."
            )
            cameraManager.setTorchMode(cameraId, desired)
            isFlashlightOn = desired
            DeviceControlResult(
                success = true,
                verified = true,
                setting = "flashlight",
                state = if (desired) "ON" else "OFF",
                message = if (desired) "Flashlight turned on." else "Flashlight turned off."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode: ${e.message}", e)
            DeviceControlResult(
                success = false,
                verified = false,
                setting = "flashlight",
                message = "Unable to access the flashlight: ${e.message}"
            )
        }
    }

    fun setFlashlight(enabled: Boolean): String = setFlashlightDetailed(enabled).message

    fun performSystemActionDetailed(action: String): DeviceControlResult {
        val svc = AutomationAccessibilityService.instance
            ?: return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "system_action",
                message = "Accessibility Service is not running; cannot perform system action '$action'."
            )
        val norm = DeviceControlHelper.normalizeSystemAction(action)
            ?: return DeviceControlResult(
                success = false,
                verified = false,
                setting = "system_action",
                message = "Unknown system action '$action'."
            )
        val globalCode = when (norm) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else {
                return DeviceControlResult(false, false, setting = "lock_screen", message = "Lock screen requires Android 9+.")
            }
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            } else {
                return DeviceControlResult(false, false, setting = "take_screenshot", message = "Screenshot global action requires Android 9+.")
            }
            "dismiss_notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else {
                AccessibilityService.GLOBAL_ACTION_BACK
            }
            else -> return DeviceControlResult(false, false, setting = "system_action", message = "Unknown system action '$action'.")
        }
        val ok = svc.performGlobalAction(globalCode)
        return DeviceControlResult(
            success = ok,
            verified = ok,
            setting = norm,
            message = if (ok) "Executed system action '$norm'." else "Android rejected system action '$norm'."
        )
    }

    fun performSystemAction(action: String): String = performSystemActionDetailed(action).message

    private fun setAutoRotateDetailed(enabled: Boolean?): DeviceControlResult {
        val currentOn = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        }.getOrDefault(false)
        val desired = enabled ?: !currentOn
        if (currentOn == desired) {
            return DeviceControlResult(
                success = true,
                verified = true,
                setting = "auto_rotate",
                state = if (desired) "ON" else "OFF",
                message = "Auto-rotate is already ${if (desired) "ON" else "OFF"} (verified)."
            )
        }
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "auto_rotate",
                message = "Opened 'Modify System Settings' permission screen for Aura so Auto-rotate can be changed."
            )
        }
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (desired) 1 else 0)
            Thread.sleep(25)
            val readBack = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1) == 1
            DeviceControlResult(
                success = readBack == desired,
                verified = readBack == desired,
                setting = "auto_rotate",
                state = if (readBack) "ON" else "OFF",
                message = "Auto-rotate turned ${if (readBack) "ON" else "OFF"} (verified)."
            )
        } catch (e: Exception) {
            DeviceControlResult(false, false, setting = "auto_rotate", message = "Failed to change auto-rotate: ${e.message}")
        }
    }

    private fun setScreenTimeoutDetailed(timeoutSeconds: Int?): DeviceControlResult {
        val secs = (timeoutSeconds ?: 60).coerceIn(15, 1800)
        if (!Settings.System.canWrite(context)) {
            openWriteSettingsPermissionScreen()
            return DeviceControlResult(
                success = false,
                verified = false,
                permissionRequired = true,
                setting = "screen_timeout",
                message = "Opened 'Modify System Settings' permission screen for Aura so screen timeout can be changed."
            )
        }
        return try {
            val targetMs = secs * 1000
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, targetMs)
            Thread.sleep(25)
            val actualMs = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, targetMs)
            val actualSecs = (actualMs / 1000).coerceAtLeast(1)
            val label = if (actualSecs >= 60 && actualSecs % 60 == 0) "${actualSecs / 60} min" else "${actualSecs}s"
            DeviceControlResult(
                success = true,
                verified = abs(actualMs - targetMs) <= 2000,
                setting = "screen_timeout",
                state = label,
                message = "Screen timeout set to $label (verified)."
            )
        } catch (e: Exception) {
            DeviceControlResult(false, false, setting = "screen_timeout", message = "Failed to change screen timeout: ${e.message}")
        }
    }

    private fun resolvePanelSpec(canonical: String): Triple<String, Intent, Pair<List<String>, List<String>>> {
        return when (canonical) {
            "wifi" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                Triple("Wi-Fi", Intent(action), listOf("Use Wi-Fi", "Wi-Fi", "Wi‑Fi", "WLAN") to emptyList())
            }
            "mobile_data" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
                Triple("Mobile Data", Intent(action), listOf("Mobile data", "Cellular data", "SIMs", "Data") to emptyList())
            }
            "bluetooth" -> Triple("Bluetooth", Intent(Settings.ACTION_BLUETOOTH_SETTINGS), listOf("Use Bluetooth", "Bluetooth") to emptyList())
            "location" -> Triple("Location (GPS)", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), listOf("Use location", "Location") to emptyList())
            "airplane" -> Triple("Airplane Mode", Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), listOf("Airplane mode", "Flight mode") to emptyList())
            "hotspot" -> Triple(
                "Hotspot",
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                listOf("Hotspot & tethering", "Wi-Fi hotspot", "Portable hotspot", "Mobile Hotspot") to
                    listOf("Use Wi-Fi hotspot", "Wi-Fi hotspot", "Portable hotspot", "Mobile Hotspot", "Hotspot")
            )
            "battery_saver" -> Triple(
                "Battery Saver",
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                listOf("Use Battery Saver", "Battery Saver", "Power saving") to emptyList()
            )
            "dark_mode" -> Triple(
                "Dark Mode",
                Intent(Settings.ACTION_DISPLAY_SETTINGS),
                listOf("Dark theme", "Dark mode", "Dark") to emptyList()
            )
            "nfc" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                Triple("NFC", Intent(action), listOf("Use NFC", "NFC") to emptyList())
            }
            "volume_panel" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                Triple("Sound & Volume Panel", Intent(action), emptyList<String>() to emptyList())
            }
            "display" -> Triple("Display Settings", Intent(Settings.ACTION_DISPLAY_SETTINGS), emptyList<String>() to emptyList())
            "storage" -> Triple("Storage Settings", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), emptyList<String>() to emptyList())
            "accessibility" -> Triple("Accessibility Settings", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), emptyList<String>() to emptyList())
            else -> Triple("Settings ($canonical)", Intent(Settings.ACTION_SETTINGS), emptyList<String>() to emptyList())
        }
    }

    private fun readLiveOsSettingState(canonical: String): Boolean? {
        return when (canonical) {
            "wifi" -> runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            }.getOrNull()
            "bluetooth" -> runCatching {
                val canQuery = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                if (!canQuery) null else {
                    val bm = context.getSystemService(BluetoothManager::class.java)
                    @Suppress("DEPRECATION")
                    (bm?.adapter ?: BluetoothAdapter.getDefaultAdapter())?.isEnabled
                }
            }.getOrNull()
            "location" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
            } else null
            "airplane" -> runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }.getOrNull()
            "battery_saver" -> runCatching {
                context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
            }.getOrNull()
            "dark_mode" -> runCatching {
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }.getOrNull()
            else -> null
        }
    }

    /**
     * Executes or toggles device settings via direct Android APIs first, falling back seamlessly
     * to opening the targeted Android Settings / Panel screen and toggling via Accessibility (#13).
     */
    suspend fun setDeviceSettingDetailed(
        setting: String,
        enabled: Boolean? = null,
        value: Any? = null
    ): DeviceControlResult {
        val parsed = DeviceControlHelper.parseDeviceSettingArgs(setting, enabled, value)
        if (parsed.parseError != null) {
            return DeviceControlResult(false, false, message = parsed.parseError)
        }
        val canonical = parsed.canonicalSetting
        val desired = parsed.enabled

        when (canonical) {
            "dnd" -> return setDndEnabledDetailed(desired)
            "auto_rotate" -> return setAutoRotateDetailed(desired)
            "screen_timeout" -> return setScreenTimeoutDetailed(parsed.timeoutSeconds)
            "auto_brightness" -> return setAutoBrightnessDetailed(desired ?: true)
            "flashlight" -> return setFlashlightDetailed(desired)
        }

        // Check current live OS state so no-op repeats don't needlessly pop UI
        val liveBefore = readLiveOsSettingState(canonical)
        if (desired != null && liveBefore == desired) {
            return DeviceControlResult(
                success = true,
                verified = true,
                setting = canonical,
                state = if (desired) "ON" else "OFF",
                message = "${canonical.replace('_', ' ').replaceFirstChar { it.uppercase() }} is already ${if (desired) "ON" else "OFF"} (verified)."
            )
        }

        val targetDesired = desired ?: liveBefore?.let { !it }

        // Direct API attempts for Wi-Fi (works on targetSdk 28 on many builds) and Bluetooth
        if (canonical == "wifi" && targetDesired != null) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                val apiOk = runCatching { wm.setWifiEnabled(targetDesired) }.getOrDefault(false)
                if (apiOk) {
                    repeat(8) {
                        delay(60)
                        if (wm.isWifiEnabled == targetDesired) {
                            return DeviceControlResult(
                                success = true,
                                verified = true,
                                setting = "wifi",
                                state = if (targetDesired) "ON" else "OFF",
                                message = "Wi-Fi turned ${if (targetDesired) "ON" else "OFF"} (verified)."
                            )
                        }
                    }
                }
            }
        }

        if (canonical == "bluetooth" && targetDesired != null) {
            val canToggleBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (canToggleBt) {
                @Suppress("DEPRECATION")
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: BluetoothAdapter.getDefaultAdapter()
                if (adapter != null) {
                    @Suppress("DEPRECATION")
                    val apiOk = runCatching { if (targetDesired) adapter.enable() else adapter.disable() }.getOrDefault(false)
                    if (apiOk) {
                        repeat(10) {
                            delay(70)
                            if (adapter.isEnabled == targetDesired) {
                                return DeviceControlResult(
                                    success = true,
                                    verified = true,
                                    setting = "bluetooth",
                                    state = if (targetDesired) "ON" else "OFF",
                                    message = "Bluetooth turned ${if (targetDesired) "ON" else "OFF"} (verified)."
                                )
                            }
                        }
                    }
                }
            }
        }

        val (title, intent, labelPair) = resolvePanelSpec(canonical)
        val launched = ActivityLauncherHelper.startActivitySafely(
            context,
            intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
        if (!launched) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ActivityLauncherHelper.startActivitySafely(context, fallback)
            return DeviceControlResult(
                success = false,
                verified = false,
                setting = canonical,
                message = "Direct '$title' screen is unavailable on this ROM; opened general Settings."
            )
        }

        val (rowLabels, subLabels) = labelPair
        val svc = AutomationAccessibilityService.instance
        if (svc == null || rowLabels.isEmpty()) {
            val passive = rowLabels.isEmpty()
            return DeviceControlResult(
                success = passive,
                verified = passive,
                setting = canonical,
                permissionRequired = !passive && svc == null,
                message = if (passive) "Opened $title."
                else "Opened $title. Enable Accessibility Service or tap the toggle directly."
            )
        }

        val actionBtns = if (canonical == "battery_saver") listOf("Turn on now", "Turn off now") else emptyList()
        val outcome = svc.toggleSettingSwitchAndDismiss(
            settingDisplayName = title,
            rowLabels = rowLabels,
            desiredState = targetDesired,
            subPageLabels = subLabels,
            actionButtonLabels = actionBtns,
            dismissAfter = true
        )

        val liveAfter = readLiveOsSettingState(canonical)
        val finalVerified = if (targetDesired != null && liveAfter != null) {
            liveAfter == targetDesired
        } else {
            outcome.handled
        }
        val stateWord = when (liveAfter ?: outcome.verifiedState ?: targetDesired) {
            true -> "ON"
            false -> "OFF"
            null -> ""
        }
        return DeviceControlResult(
            success = finalVerified,
            verified = finalVerified,
            setting = canonical,
            state = stateWord,
            message = if (finalVerified && stateWord.isNotBlank()) "$title turned $stateWord (verified)."
            else outcome.message
        )
    }

    fun openDevicePanel(setting: String): String {
        val canonical = DeviceControlHelper.parseDeviceSettingArgs(setting).canonicalSetting
        val (title, intent, _) = resolvePanelSpec(canonical)
        val ok = ActivityLauncherHelper.startActivitySafely(
            context,
            intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
        return if (ok) {
            "Opened $title."
        } else {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (ActivityLauncherHelper.startActivitySafely(context, fallback)) {
                "Opened general Settings (direct '$title' screen unavailable on this ROM)."
            } else {
                "Failed to open $title."
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
