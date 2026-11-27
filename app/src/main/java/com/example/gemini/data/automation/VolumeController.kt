package com.example.gemini.data.automation

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.example.gemini.data.live.DeviceControlHelper
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dedicated, service-safe controller for Android system & media volume.
 *
 * Handles:
 * - Safe acquisition of AudioManager via applicationContext (independent of foreground Activity)
 * - Clamping cleanly between stream min (0 / API 28+ min) and getStreamMaxVolume()
 * - AudioManager.FLAG_SHOW_UI so the Android volume slider visibly updates
 * - Previous non-zero level memory for mute()/unmute() restoration
 * - Separate SecurityException & DND policy handling (with ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS guidance)
 * - Mirroring to STREAM_VOICE_CALL when Live Voice / BT SCO / API 31+ communicationDevice is active
 * - Clear Hindi/Hinglish spoken/text confirmation ("Volume 60% kar diya") or actionable errors
 */
class VolumeController(context: Context) {

    data class VolumeBatchResult(
        val verified: Boolean,
        val actualPercent: Int,
        val cappedBySystem: Boolean,
        val permissionRequired: Boolean = false,
        val outcomes: List<DeviceControlHelper.VolumeOutcome>,
        val message: String
    )

    companion object {
        private const val TAG = "VolumeController"
        private const val SETTLE_DELAY_MS = 25L
        private const val RETRY_STEP_DELAY_MS = 15L

        // Shared across instances so mute() -> unmute() restores previous volume even if service recreates helper
        private val lastNonZeroStepByStream = ConcurrentHashMap<Int, Int>()
    }

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCurrentVolumePercent(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
            ((curVol / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException reading volume for stream=$streamType: ${se.message}")
            0
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading volume for stream=$streamType: ${e.message}", e)
            0
        }
    }

    fun hasNotificationPolicyAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            notificationManager?.isNotificationPolicyAccessGranted == true
        } catch (e: Exception) {
            Log.w(TAG, "Could not query NotificationPolicyAccess: ${e.message}")
            false
        }
    }

    fun openNotificationPolicySettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            mainHandler.post {
                ActivityLauncherHelper.startActivitySafely(appContext, intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening DND Notification Policy Settings: ${e.message}", e)
            false
        }
    }

    fun increaseVolume(
        steps: Int = 1,
        streamType: Int = AudioManager.STREAM_MUSIC,
        silentFeedback: Boolean = false
    ): DeviceControlHelper.VolumeOutcome {
        return applyStreamChange(
            label = streamLabelFor(streamType),
            streamType = streamType,
            action = "increase",
            steps = steps.coerceIn(1, 20),
            stepPercent = null,
            silentFeedback = silentFeedback
        )
    }

    fun decreaseVolume(
        steps: Int = 1,
        streamType: Int = AudioManager.STREAM_MUSIC,
        silentFeedback: Boolean = false
    ): DeviceControlHelper.VolumeOutcome {
        return applyStreamChange(
            label = streamLabelFor(streamType),
            streamType = streamType,
            action = "decrease",
            steps = steps.coerceIn(1, 20),
            stepPercent = null,
            silentFeedback = silentFeedback
        )
    }

    fun setVolumePercent(
        percent: Int,
        streamType: Int = AudioManager.STREAM_MUSIC,
        silentFeedback: Boolean = false
    ): DeviceControlHelper.VolumeOutcome {
        return applyStreamChange(
            label = streamLabelFor(streamType),
            streamType = streamType,
            action = "set",
            requestedPercent = percent.coerceIn(0, 100),
            silentFeedback = silentFeedback
        )
    }

    fun mute(
        streamType: Int = AudioManager.STREAM_MUSIC,
        silentFeedback: Boolean = true
    ): DeviceControlHelper.VolumeOutcome {
        return applyStreamChange(
            label = streamLabelFor(streamType),
            streamType = streamType,
            action = "mute",
            silentFeedback = silentFeedback
        )
    }

    fun unmute(
        streamType: Int = AudioManager.STREAM_MUSIC,
        silentFeedback: Boolean = false
    ): DeviceControlHelper.VolumeOutcome {
        return applyStreamChange(
            label = streamLabelFor(streamType),
            streamType = streamType,
            action = "unmute",
            silentFeedback = silentFeedback
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
        val primaryPct = outcomes.firstOrNull()?.actualPercent ?: getCurrentVolumePercent(AudioManager.STREAM_MUSIC)
        val message = if (outcomes.size == 1 && normalizedStream == "music") {
            "Abhi volume ${primaryPct}% hai."
        } else {
            outcomes.joinToString("; ") { "${streamDisplayTitle(it.streamLabel)} ${it.actualPercent}% hai" }
        }
        return VolumeBatchResult(
            verified = true,
            actualPercent = primaryPct,
            cappedBySystem = false,
            permissionRequired = false,
            outcomes = outcomes,
            message = message
        )
    }

    fun executeNaturalCommand(
        commandText: String,
        defaultStream: String = "music",
        silentFeedback: Boolean = false
    ): VolumeBatchResult? {
        Log.d(TAG, "parseNaturalVolumeCommand input='$commandText'")
        val parsed = DeviceControlHelper.parseNaturalVolumeCommand(commandText, defaultStream)
        if (parsed == null) {
            Log.d(TAG, "No standalone volume command matched for '$commandText'")
            return null
        }
        Log.i(
            TAG,
            "Command detected -> action=${parsed.action}, stream=${parsed.stream}, pct=${parsed.percentage}, stepPct=${parsed.stepPercent}, steps=${parsed.steps}"
        )
        return if (parsed.action == "get") {
            getVolumeSummaryDetailed(parsed.stream)
        } else {
            executeVolumeBatch(
                action = parsed.action,
                stream = parsed.stream,
                requestedPercent = parsed.percentage,
                stepPercent = parsed.stepPercent,
                steps = parsed.steps,
                silentFeedback = silentFeedback
            )
        }
    }

    fun executeVolumeBatch(
        action: String,
        stream: String = "music",
        requestedPercent: Int? = null,
        stepPercent: Int = 15,
        steps: Int? = null,
        silentFeedback: Boolean = false
    ): VolumeBatchResult {
        val normalizedStream = DeviceControlHelper.normalizeStream(stream)
        val targets = resolveAudioStreams(normalizedStream)
        var anyPermissionRequired = false

        Log.i(
            TAG,
            "executeVolumeBatch action=$action stream=$normalizedStream reqPct=$requestedPercent stepPct=$stepPercent steps=$steps silent=$silentFeedback"
        )

        val outcomes = targets.map { (label, streamType) ->
            val outcome = applyStreamChange(
                label = label,
                streamType = streamType,
                action = action,
                requestedPercent = requestedPercent,
                stepPercent = if (steps == null) stepPercent else null,
                steps = steps ?: 1,
                silentFeedback = silentFeedback
            )
            if (outcome.errorReason?.contains("Do Not Disturb", ignoreCase = true) == true && !hasNotificationPolicyAccess()) {
                anyPermissionRequired = true
            }
            outcome
        }

        if (normalizedStream == "music" && outcomes.any { it.verified }) {
            syncCommCallVolumeIfActive(
                action = action,
                requestedPercent = requestedPercent,
                stepPercent = stepPercent,
                steps = steps
            )
        }

        val allVerified = outcomes.all { it.verified }
        val anyCapped = outcomes.any { it.cappedBySystem }
        val primaryPct = outcomes.firstOrNull()?.actualPercent ?: getCurrentVolumePercent(AudioManager.STREAM_MUSIC)
        val userMessage = buildSpokenTextResponse(
            action = action,
            normalizedStream = normalizedStream,
            outcomes = outcomes,
            primaryPercent = primaryPct,
            permissionRequired = anyPermissionRequired
        )

        Log.i(
            TAG,
            "executeVolumeBatch done -> verified=$allVerified actual=$primaryPct% capped=$anyCapped permReq=$anyPermissionRequired msg='$userMessage'"
        )

        return VolumeBatchResult(
            verified = allVerified,
            actualPercent = primaryPct,
            cappedBySystem = anyCapped,
            permissionRequired = anyPermissionRequired,
            outcomes = outcomes,
            message = userMessage
        )
    }

    private fun applyStreamChange(
        label: String,
        streamType: Int,
        action: String,
        requestedPercent: Int? = null,
        stepPercent: Int? = 15,
        steps: Int = 1,
        silentFeedback: Boolean = false
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
        if (beforeVol > minVol) {
            lastNonZeroStepByStream[streamType] = beforeVol
        }

        if (isFixed) {
            Log.w(TAG, "Stream $label is fixed-volume (current=$beforeVol/$maxVol)")
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

        // Guard DND-protected streams before attempting AudioManager write so we give clear permission guidance
        if (isDndSensitiveStream(streamType) && isDndBlockingNonMediaChanges() && !hasNotificationPolicyAccess()) {
            Log.w(TAG, "DND active without ACCESS_NOTIFICATION_POLICY for $label")
            openNotificationPolicySettings()
            return DeviceControlHelper.evaluateVolumeOutcome(
                streamLabel = label,
                action = action,
                requestedPercent = requestedPercent,
                targetStep = beforeVol,
                beforeStep = beforeVol,
                afterStep = beforeVol,
                maxStep = maxVol,
                securityError = "Do Not Disturb access grant nahi hai (Notification Policy Settings khol di hai)"
            )
        }

        val targetVol = when (action) {
            "set" -> (((requestedPercent ?: 50).coerceIn(0, 100) / 100.0) * maxVol)
                .roundToInt()
                .coerceIn(minVol, maxVol)
            "increase" -> {
                val delta = if (stepPercent != null) {
                    max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())
                } else {
                    steps.coerceIn(1, maxVol)
                }
                (beforeVol + delta).coerceIn(minVol, maxVol)
            }
            "decrease" -> {
                val delta = if (stepPercent != null) {
                    max(1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())
                } else {
                    steps.coerceIn(1, maxVol)
                }
                (beforeVol - delta).coerceIn(minVol, maxVol)
            }
            "mute" -> minVol
            "unmute" -> {
                val saved = lastNonZeroStepByStream[streamType]
                when {
                    beforeVol > minVol -> beforeVol
                    saved != null && saved > minVol -> saved.coerceIn(minVol + 1, maxVol)
                    else -> max(minVol + 1, (maxVol * 0.5).roundToInt()).coerceIn(minVol, maxVol)
                }
            }
            else -> beforeVol
        }

        val flags = if (silentFeedback) {
            AudioManager.FLAG_SHOW_UI
        } else {
            AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
        }

        Log.d(
            TAG,
            "applyStreamChange($label, stream=$streamType, action=$action): before=$beforeVol/$maxVol -> target=$targetVol/$maxVol (flags=$flags)"
        )

        var secErr: String? = null
        try {
            if (action == "mute") {
                audioManager.setStreamVolume(streamType, minVol, flags)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && minVol == 0 && audioManager.getStreamVolume(streamType) > 0) {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, flags)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetVol > minVol) {
                    val wasMuted = runCatching { audioManager.isStreamMute(streamType) }.getOrDefault(false)
                    if (wasMuted || action == "unmute") {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                audioManager.setStreamVolume(streamType, targetVol, flags)

                // Retry step-wise if OEM Safe-Volume or BT AVRCP delayed direct setStreamVolume
                var immediate = audioManager.getStreamVolume(streamType)
                if (immediate != targetVol) {
                    val direction = if (targetVol > immediate) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    val maxRetries = abs(targetVol - immediate).coerceAtMost(8)
                    val onMain = Looper.myLooper() == Looper.getMainLooper()
                    for (i in 0 until maxRetries) {
                        val prev = immediate
                        audioManager.adjustStreamVolume(streamType, direction, flags)
                        if (!onMain) Thread.sleep(RETRY_STEP_DELAY_MS)
                        immediate = audioManager.getStreamVolume(streamType)
                        if (immediate == targetVol || immediate == prev) break
                    }
                }
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Thread.sleep(SETTLE_DELAY_MS)
            }
        } catch (se: SecurityException) {
            secErr = se.message ?: "Do Not Disturb policy restriction"
            Log.w(TAG, "SecurityException changing $label volume: $secErr", se)
            if (isDndSensitiveStream(streamType) && !hasNotificationPolicyAccess()) {
                openNotificationPolicySettings()
            }
        } catch (e: Exception) {
            secErr = e.message ?: "AudioService error"
            Log.e(TAG, "Exception changing $label volume: $secErr", e)
        }

        val afterVol = runCatching {
            audioManager.getStreamVolume(streamType).coerceIn(0, maxVol)
        }.getOrDefault(beforeVol)
        if (afterVol > minVol) {
            lastNonZeroStepByStream[streamType] = afterVol
        }

        Log.i(TAG, "applyStreamChange($label) readback=$afterVol/$maxVol (before=$beforeVol, target=$targetVol, secErr=$secErr)")

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
        stepPercent: Int,
        steps: Int?
    ) {
        val hasCommDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice != null }.getOrDefault(false)
        } else {
            false
        }
        val inComm = runCatching {
            @Suppress("DEPRECATION")
            val scoOn = audioManager.isBluetoothScoOn
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audioManager.mode == AudioManager.MODE_IN_CALL ||
                scoOn ||
                hasCommDevice
        }.getOrDefault(false)
        if (!inComm) return

        try {
            val callStream = AudioManager.STREAM_VOICE_CALL
            val maxVol = audioManager.getStreamMaxVolume(callStream).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { audioManager.getStreamMinVolume(callStream) }.getOrDefault(1).coerceIn(0, maxVol)
            } else {
                1.coerceAtMost(maxVol)
            }
            val cur = audioManager.getStreamVolume(callStream).coerceIn(minVol, maxVol)
            val delta = max(steps ?: 1, (maxVol * (stepPercent.coerceIn(5, 50) / 100.0)).roundToInt())
            val tgt = when (action) {
                "set" -> (((requestedPercent ?: 50).coerceIn(0, 100) / 100.0) * maxVol).roundToInt().coerceIn(minVol, maxVol)
                "increase" -> (cur + delta).coerceIn(minVol, maxVol)
                "decrease" -> (cur - delta).coerceIn(minVol, maxVol)
                "mute" -> minVol
                "unmute" -> max(minVol + 1, (maxVol * 0.5).roundToInt()).coerceIn(minVol, maxVol)
                else -> return
            }
            audioManager.setStreamVolume(callStream, tgt, 0)
            val actualCall = audioManager.getStreamVolume(callStream).coerceIn(minVol, maxVol)
            Log.d(TAG, "Synced STREAM_VOICE_CALL (hasCommDevice=$hasCommDevice): req=$tgt actual=$actualCall/$maxVol")
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException syncing STREAM_VOICE_CALL: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed syncing STREAM_VOICE_CALL: ${e.message}")
        }
    }

    private fun isDndSensitiveStream(streamType: Int): Boolean {
        return streamType == AudioManager.STREAM_RING ||
            streamType == AudioManager.STREAM_NOTIFICATION ||
            streamType == AudioManager.STREAM_SYSTEM
    }

    private fun isDndBlockingNonMediaChanges(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (_: Exception) {
            false
        }
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

    private fun streamLabelFor(streamType: Int): String = when (streamType) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_VOICE_CALL -> "call"
        else -> "media"
    }

    private fun streamDisplayTitle(label: String): String = when (label) {
        "ring" -> "Ringtone volume"
        "alarm" -> "Alarm volume"
        "notification" -> "Notification volume"
        "call" -> "Call volume"
        else -> "Volume"
    }

    private fun buildSpokenTextResponse(
        action: String,
        normalizedStream: String,
        outcomes: List<DeviceControlHelper.VolumeOutcome>,
        primaryPercent: Int,
        permissionRequired: Boolean
    ): String {
        val failed = outcomes.firstOrNull { !it.verified }
        if (failed != null) {
            if (permissionRequired) {
                return "Do Not Disturb (DND) ki wajah se ${streamDisplayTitle(failed.streamLabel)} change nahi ho paaya. Notification Policy Settings khol di hai — kripya permission allow karein."
            }
            return failed.errorReason ?: failed.summary
        }

        if (outcomes.size > 1) {
            return "Sabhi volumes update kar diye (Media ${primaryPercent}%)."
        }

        val streamTitle = if (normalizedStream == "music") "Volume" else streamDisplayTitle(outcomes.firstOrNull()?.streamLabel ?: "media")
        return when (action) {
            "mute" -> "$streamTitle mute kar diya (0%)."
            "unmute" -> "$streamTitle unmute kar diya (${primaryPercent}%)."
            "get" -> "$streamTitle abhi ${primaryPercent}% hai."
            else -> "$streamTitle ${primaryPercent}% kar diya."
        }
    }
}
