package com.example.gemini.data.live

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin helper for device volume argument normalization, readback verification,
 * and live battery percentage computation & formatting (#11 & #12).
 */
object DeviceControlHelper {

    data class ParsedVolumeCommand(
        val action: String,       // "set", "increase", "decrease", "mute", "unmute", "get"
        val stream: String,       // "music", "ring", "alarm", "notification", "call", "all"
        val percentage: Int?,     // 0..100 for "set"
        val stepPercent: Int,     // 5..50 for "increase"/"decrease"
        val parseError: String? = null
    )

    data class VolumeOutcome(
        val verified: Boolean,
        val streamLabel: String,
        val actualPercent: Int,
        val actualStep: Int,
        val maxStep: Int,
        val cappedBySystem: Boolean = false,
        val errorReason: String? = null,
        val summary: String
    )

    private val NON_DIGITS = Regex("[^0-9.-]+")

    fun parsePercentageAny(raw: Any?): Int? {
        return when (raw) {
            null -> null
            is Number -> raw.toDouble().roundToInt().coerceIn(0, 100)
            is String -> {
                val trimmed = raw.trim().lowercase(Locale.ROOT)
                when {
                    trimmed.isBlank() -> null
                    trimmed in setOf("full", "max", "maximum", "100", "100%", "pura", "poora") -> 100
                    trimmed in setOf("half", "medium", "mid", "aadha", "adha") -> 50
                    trimmed in setOf("low", "minimum", "min", "zero", "0", "0%", "mute") -> 0
                    else -> {
                        val cleaned = trimmed.replace(NON_DIGITS, "")
                        cleaned.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
                    }
                }
            }
            else -> null
        }
    }

    fun normalizeStream(rawStream: String?): String {
        val s = rawStream?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            s.contains("ring") -> "ring"
            s.contains("alarm") -> "alarm"
            s.contains("notif") -> "notification"
            s.contains("call") || s.contains("voice") || s.contains("incall") || s.contains("earpiece") -> "call"
            s == "all" || s.contains("sab") || s.contains("every") -> "all"
            else -> "music"
        }
    }

    fun parseVolumeArgs(
        rawAction: String?,
        rawPercentage: Any?,
        rawStream: String?,
        rawStepPercent: Any? = null
    ): ParsedVolumeCommand {
        val stream = normalizeStream(rawStream)
        var pct = parsePercentageAny(rawPercentage)
        val stepPct = (parsePercentageAny(rawStepPercent) ?: 15).coerceIn(5, 50)
        val actLower = HindiAsrNormalizer.normalize(rawAction.orEmpty())
            .trim()
            .lowercase(Locale.ROOT)

        val resolvedAction = when {
            actLower in setOf("set", "put", "karo", "set_to", "exact") -> "set"
            actLower in setOf("full", "max", "maximum") -> {
                pct = pct ?: 100
                "set"
            }
            actLower in setOf("half", "medium", "mid") -> {
                pct = pct ?: 50
                "set"
            }
            actLower in setOf("min", "minimum", "zero") -> {
                pct = pct ?: 0
                "set"
            }
            actLower in setOf("increase", "up", "raise", "higher", "loud", "louder", "badhao", "zyada", "jyada", "tez") -> "increase"
            actLower in setOf("decrease", "down", "lower", "quieter", "quiet", "slow", "kam", "ghatao", "dheema", "dhima") -> "decrease"
            actLower in setOf("mute", "silent", "silence", "band", "zero_sound") -> "mute"
            actLower in setOf("unmute", "sound_on", "un_mute", "chalu") -> "unmute"
            actLower in setOf("get", "check", "status", "read", "current", "kitna") -> "get"
            actLower.isBlank() && pct != null -> "set"
            else -> ""
        }

        if (resolvedAction.isBlank()) {
            return ParsedVolumeCommand(
                action = "",
                stream = stream,
                percentage = pct,
                stepPercent = stepPct,
                parseError = "Unrecognized volume action '${rawAction.orEmpty()}'. Use set, increase, decrease, mute, unmute, or get."
            )
        }
        if (resolvedAction == "set" && pct == null) {
            return ParsedVolumeCommand(
                action = "set",
                stream = stream,
                percentage = null,
                stepPercent = stepPct,
                parseError = "Missing target percentage (0-100) for volume set."
            )
        }

        return ParsedVolumeCommand(
            action = resolvedAction,
            stream = stream,
            percentage = pct,
            stepPercent = stepPct
        )
    }

    fun evaluateVolumeOutcome(
        streamLabel: String,
        action: String,
        requestedPercent: Int?,
        targetStep: Int?,
        beforeStep: Int,
        afterStep: Int,
        maxStep: Int,
        isFixedVolume: Boolean = false,
        securityError: String? = null
    ): VolumeOutcome {
        val safeMax = maxStep.coerceAtLeast(1)
        val actualPct = ((afterStep / safeMax.toFloat()) * 100f).roundToInt().coerceIn(0, 100)

        if (isFixedVolume) {
            return VolumeOutcome(
                verified = false,
                streamLabel = streamLabel,
                actualPercent = actualPct,
                actualStep = afterStep,
                maxStep = safeMax,
                cappedBySystem = true,
                errorReason = "Device audio output is fixed-volume (HDMI/Cast/Line-Out).",
                summary = "$streamLabel is fixed-volume and remains at $actualPct%."
            )
        }

        if (!securityError.isNullOrBlank()) {
            return VolumeOutcome(
                verified = false,
                streamLabel = streamLabel,
                actualPercent = actualPct,
                actualStep = afterStep,
                maxStep = safeMax,
                cappedBySystem = true,
                errorReason = "Blocked by Do Not Disturb: $securityError",
                summary = "$streamLabel blocked by Do Not Disturb (remains $actualPct%)."
            )
        }

        return when (action) {
            "set" -> {
                val tgt = targetStep ?: 0
                val diff = abs(afterStep - tgt)
                if (diff > 1 && beforeStep == afterStep) {
                    VolumeOutcome(
                        verified = false,
                        streamLabel = streamLabel,
                        actualPercent = actualPct,
                        actualStep = afterStep,
                        maxStep = safeMax,
                        cappedBySystem = true,
                        errorReason = "Android/OEM Safe Volume or system policy kept $streamLabel at $actualPct% instead of ${requestedPercent ?: actualPct}%.",
                        summary = "$streamLabel remained at $actualPct% (capped below requested ${requestedPercent ?: actualPct}%)."
                    )
                } else if (diff > 1 && afterStep < tgt) {
                    VolumeOutcome(
                        verified = false,
                        streamLabel = streamLabel,
                        actualPercent = actualPct,
                        actualStep = afterStep,
                        maxStep = safeMax,
                        cappedBySystem = true,
                        errorReason = "Android Safe Media Volume capped $streamLabel at $actualPct% ($afterStep/$safeMax).",
                        summary = "$streamLabel raised to $actualPct% (system capped below ${requestedPercent ?: actualPct}%)."
                    )
                } else {
                    VolumeOutcome(
                        verified = true,
                        streamLabel = streamLabel,
                        actualPercent = actualPct,
                        actualStep = afterStep,
                        maxStep = safeMax,
                        summary = "$streamLabel set to $actualPct% ($afterStep/$safeMax verified)."
                    )
                }
            }
            "increase" -> {
                val blocked = beforeStep < safeMax && afterStep <= beforeStep
                VolumeOutcome(
                    verified = !blocked,
                    streamLabel = streamLabel,
                    actualPercent = actualPct,
                    actualStep = afterStep,
                    maxStep = safeMax,
                    cappedBySystem = blocked,
                    errorReason = if (blocked) "System/Safe Volume prevented raising $streamLabel past $actualPct%." else null,
                    summary = if (blocked) "$streamLabel could not increase past $actualPct%." else "$streamLabel increased to $actualPct% ($afterStep/$safeMax verified)."
                )
            }
            "decrease" -> {
                val blocked = beforeStep > 0 && afterStep >= beforeStep
                VolumeOutcome(
                    verified = !blocked,
                    streamLabel = streamLabel,
                    actualPercent = actualPct,
                    actualStep = afterStep,
                    maxStep = safeMax,
                    cappedBySystem = blocked,
                    errorReason = if (blocked) "System prevented lowering $streamLabel below $actualPct%." else null,
                    summary = if (blocked) "$streamLabel remained at $actualPct%." else "$streamLabel decreased to $actualPct% ($afterStep/$safeMax verified)."
                )
            }
            "mute" -> {
                val mutedOk = afterStep == 0
                VolumeOutcome(
                    verified = mutedOk,
                    streamLabel = streamLabel,
                    actualPercent = actualPct,
                    actualStep = afterStep,
                    maxStep = safeMax,
                    summary = if (mutedOk) "$streamLabel muted (0%)." else "$streamLabel is now $actualPct%."
                )
            }
            "unmute" -> {
                val ok = afterStep > 0
                VolumeOutcome(
                    verified = ok,
                    streamLabel = streamLabel,
                    actualPercent = actualPct,
                    actualStep = afterStep,
                    maxStep = safeMax,
                    summary = "$streamLabel unmuted ($actualPct%)."
                )
            }
            else -> VolumeOutcome(
                verified = true,
                streamLabel = streamLabel,
                actualPercent = actualPct,
                actualStep = afterStep,
                maxStep = safeMax,
                summary = "$streamLabel is currently at $actualPct% ($afterStep/$safeMax)."
            )
        }
    }

    /**
     * Prefers live kernel capacity from BatteryManager (0..100) and falls back to rounded intent ratio.
     */
    fun computeBatteryPercent(bmCapacity: Int, intentLevel: Int, intentScale: Int): Int {
        if (bmCapacity in 0..100) return bmCapacity
        if (intentLevel >= 0 && intentScale > 0) {
            return ((intentLevel * 100.0) / intentScale.toDouble()).roundToInt().coerceIn(0, 100)
        }
        return -1
    }

    fun formatHumanDeviceSummaryFromValues(
        batteryPercent: Int? = null,
        batteryStatus: String? = null,
        batteryPlugged: String? = null,
        temperatureC: Double? = null,
        chargeRemainingMin: Int? = null,
        mediaVolumePercent: Int? = null,
        ringVolumePercent: Int? = null,
        alarmVolumePercent: Int? = null,
        callVolumePercent: Int? = null,
        brightnessPercent: Int? = null,
        flashlightOn: Boolean? = null
    ): String {
        val parts = mutableListOf<String>()
        if (batteryPercent != null && batteryPercent >= 0) {
            val state = batteryStatus ?: "unknown"
            val plugged = batteryPlugged ?: "unplugged"
            val temp = if (temperatureC != null) ", ${temperatureC}°C" else ""
            val rem = if (chargeRemainingMin != null) ", $chargeRemainingMin min to full" else ""
            parts += "Battery: $batteryPercent% ($state, $plugged$temp$rem)"
        }
        if (mediaVolumePercent != null) {
            parts += "Volumes: Media $mediaVolumePercent%, Ring ${ringVolumePercent ?: 0}%, Alarm ${alarmVolumePercent ?: 0}%, Call ${callVolumePercent ?: 0}%"
        }
        if (brightnessPercent != null) {
            parts += "Brightness: $brightnessPercent%"
        }
        if (flashlightOn != null) {
            parts += "Flashlight: ${if (flashlightOn) "ON" else "OFF"}"
        }
        return parts.joinToString(" | ")
    }

    fun formatHumanDeviceSummary(statusJson: JSONObject): String {
        val bat = statusJson.optJSONObject("battery")
        val hw = statusJson.optJSONObject("hardware_controls")
        return formatHumanDeviceSummaryFromValues(
            batteryPercent = bat?.optInt("level_percent", -1)?.takeIf { it >= 0 },
            batteryStatus = bat?.optString("status", "unknown"),
            batteryPlugged = bat?.optString("plugged", "unplugged"),
            temperatureC = if (bat != null && bat.has("temperature_c")) bat.optDouble("temperature_c") else null,
            chargeRemainingMin = if (bat != null && bat.has("charge_time_remaining_min")) bat.optInt("charge_time_remaining_min") else null,
            mediaVolumePercent = if (hw != null) hw.optInt("media_volume_percent") else null,
            ringVolumePercent = if (hw != null) hw.optInt("ring_volume_percent") else null,
            alarmVolumePercent = if (hw != null) hw.optInt("alarm_volume_percent") else null,
            callVolumePercent = if (hw != null) hw.optInt("call_volume_percent") else null,
            brightnessPercent = if (hw != null && hw.has("brightness_percent")) hw.optInt("brightness_percent") else null,
            flashlightOn = if (hw != null) hw.optBoolean("flashlight_on") else null
        ).ifBlank { statusJson.toString() }
    }

    // -------------------------------------------------------------------------
    // #13 & #14: Pure-Kotlin Device Control & Short-Video Feed Helpers
    // -------------------------------------------------------------------------

    data class ParsedBrightnessCommand(
        val action: String,       // "set", "increase", "decrease", "auto_on", "auto_off", "get"
        val percentage: Int?,     // 0..100
        val stepPercent: Int,     // 5..50
        val parseError: String? = null
    )

    data class ParsedDeviceSettingCommand(
        val canonicalSetting: String,
        val enabled: Boolean?,
        val timeoutSeconds: Int? = null,
        val parseError: String? = null
    )

    fun parseBooleanAny(raw: Any?): Boolean? {
        return when (raw) {
            null -> null
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> {
                val s = HindiAsrNormalizer.normalize(raw).trim().lowercase(Locale.ROOT)
                when {
                    s in setOf("true", "1", "on", "yes", "enable", "enabled", "chalu", "start", "open", "active") -> true
                    s in setOf("false", "0", "off", "no", "disable", "disabled", "band", "stop", "close", "inactive") -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    fun parseTimeoutSecondsAny(raw: Any?): Int? {
        if (raw == null) return null
        if (raw is Number) {
            val v = raw.toDouble().roundToInt()
            // Treat tiny numbers >= 10 as seconds, or numbers > 3000 as ms
            val secs = if (v > 3000) v / 1000 else v
            return secs.coerceIn(15, 1800)
        }
        val s = raw.toString().trim().lowercase(Locale.ROOT)
        if (s.isBlank()) return null
        val num = Regex("""(\d+(?:\.\d+)?)""").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val secs = when {
            s.contains("hour") || s.contains("hr") || s.contains("ghant") -> (num * 3600.0).roundToInt()
            s.contains("min") || s.endsWith("m") -> (num * 60.0).roundToInt()
            s.contains("ms") -> (num / 1000.0).roundToInt()
            else -> num.roundToInt()
        }
        return secs.coerceIn(15, 1800)
    }

    fun parseBrightnessArgs(
        rawAction: String?,
        rawPercentage: Any?,
        rawStepPercent: Any? = null,
        rawEnabled: Any? = null
    ): ParsedBrightnessCommand {
        var pct = parsePercentageAny(rawPercentage)
        val stepPct = (parsePercentageAny(rawStepPercent) ?: 20).coerceIn(5, 50)
        val actLower = HindiAsrNormalizer.normalize(rawAction.orEmpty())
            .trim()
            .lowercase(Locale.ROOT)
        val boolEnabled = parseBooleanAny(rawEnabled)

        val resolvedAction = when {
            actLower in setOf("set", "put", "karo", "exact") -> "set"
            actLower in setOf("full", "max", "maximum") -> {
                pct = pct ?: 100
                "set"
            }
            actLower in setOf("half", "medium", "mid") -> {
                pct = pct ?: 50
                "set"
            }
            actLower in setOf("low", "min", "minimum", "dim") -> {
                pct = pct ?: 15
                "set"
            }
            actLower in setOf("increase", "up", "raise", "higher", "bright", "brighter", "badhao", "zyada", "jyada", "tez") -> "increase"
            actLower in setOf("decrease", "down", "lower", "darker", "kam", "ghatao", "dheema", "dhima") -> "decrease"
            actLower in setOf("auto_on", "adaptive_on") -> "auto_on"
            actLower in setOf("auto_off", "adaptive_off", "manual") -> "auto_off"
            actLower in setOf("auto", "adaptive") -> if (boolEnabled == false) "auto_off" else "auto_on"
            actLower in setOf("get", "check", "status", "read", "current", "kitna") -> "get"
            actLower.isBlank() && pct != null -> "set"
            else -> ""
        }

        if (resolvedAction.isBlank()) {
            return ParsedBrightnessCommand(
                action = "",
                percentage = pct,
                stepPercent = stepPct,
                parseError = "Unrecognized brightness action '${rawAction.orEmpty()}'. Use set, increase, decrease, auto_on, auto_off, or get."
            )
        }
        if (resolvedAction == "set" && pct == null) {
            return ParsedBrightnessCommand(
                action = "set",
                percentage = null,
                stepPercent = stepPct,
                parseError = "Missing target brightness percentage (0-100)."
            )
        }
        return ParsedBrightnessCommand(resolvedAction, pct, stepPct)
    }

    fun normalizeRingerMode(rawMode: String?): String? {
        val norm = HindiAsrNormalizer.normalize(rawMode.orEmpty())
            .trim()
            .lowercase(Locale.ROOT)
        return when {
            norm in setOf("silent", "mute", "silence", "chup", "shant", "awaz_band", "awaaz_band") -> "silent"
            norm in setOf("vibrate", "vibration", "haptic") -> "vibrate"
            norm in setOf("normal", "ring", "ringtone", "sound", "loud", "general", "unmute") -> "normal"
            else -> null
        }
    }

    fun parseDeviceSettingArgs(
        rawSetting: String?,
        rawEnabled: Any? = null,
        rawValue: Any? = null
    ): ParsedDeviceSettingCommand {
        val norm = HindiAsrNormalizer.normalize(rawSetting.orEmpty())
            .trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
        if (norm.isBlank()) {
            return ParsedDeviceSettingCommand("", null, null, "Setting name cannot be blank.")
        }
        val canonical = when {
            norm in setOf("wifi", "wi_fi", "wlan") -> "wifi"
            norm in setOf("bluetooth", "bt") -> "bluetooth"
            norm in setOf("location", "gps") -> "location"
            norm in setOf("dnd", "do_not_disturb", "disturb") -> "dnd"
            norm in setOf("airplane", "airplane_mode", "flight", "flight_mode") -> "airplane"
            norm in setOf("hotspot", "wifi_hotspot", "tether", "tethering") -> "hotspot"
            norm in setOf("mobile_data", "data", "cellular", "internet", "net") -> "mobile_data"
            norm in setOf("battery_saver", "battery", "power_save", "power_saving", "low_power") -> "battery_saver"
            norm in setOf("dark_mode", "night_mode", "dark_theme", "dark") -> "dark_mode"
            norm in setOf("auto_rotate", "rotation", "rotate", "screen_rotation", "autorotate") -> "auto_rotate"
            norm in setOf("screen_timeout", "timeout", "sleep_timeout", "display_timeout") -> "screen_timeout"
            norm in setOf("auto_brightness", "adaptive_brightness") -> "auto_brightness"
            norm in setOf("flashlight", "torch") -> "flashlight"
            norm in setOf("nfc") -> "nfc"
            norm in setOf("volume_panel", "volume", "sound") -> "volume_panel"
            norm in setOf("display", "brightness") -> "display"
            norm in setOf("storage") -> "storage"
            norm in setOf("accessibility") -> "accessibility"
            else -> norm
        }
        val enabled = parseBooleanAny(rawEnabled) ?: parseBooleanAny(rawValue)
        val timeoutSecs = if (canonical == "screen_timeout") parseTimeoutSecondsAny(rawValue ?: rawEnabled) else null
        return ParsedDeviceSettingCommand(
            canonicalSetting = canonical,
            enabled = enabled,
            timeoutSeconds = timeoutSecs
        )
    }

    fun normalizeSystemAction(rawAction: String?): String? {
        val norm = HindiAsrNormalizer.normalize(rawAction.orEmpty())
            .trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
        return when {
            norm in setOf("back", "piche", "peeche") -> "back"
            norm in setOf("home", "home_screen") -> "home"
            norm in setOf("recents", "recent_apps", "recent", "overview") -> "recents"
            norm in setOf("notifications", "notification_shade", "open_notifications", "notification_panel", "shade") -> "notifications"
            norm in setOf("quick_settings", "control_center", "tiles", "quick_panel") -> "quick_settings"
            norm in setOf("power", "power_dialog", "power_menu") -> "power_dialog"
            norm in setOf("split_screen", "split") -> "split_screen"
            norm in setOf("lock", "lock_screen", "screen_lock") -> "lock_screen"
            norm in setOf("screenshot", "take_screenshot", "ss", "screen_shot", "capture") -> "take_screenshot"
            norm in setOf("dismiss_notifications", "close_notifications", "clear_shade") -> "dismiss_notifications"
            else -> null
        }
    }

    /**
     * Accurately identifies active short-video player feeds (Reels, YouTube Shorts, TikTok/Moj/Josh)
     * without misclassifying Instagram profile/Likes grids or YouTube Home search lists.
     */
    fun isShortVideoScreen(
        packageName: String?,
        viewIds: Collection<String>,
        labels: Collection<String>,
        descriptions: Collection<String>
    ): Boolean {
        val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
        if (pkg.isBlank()) return false

        if (pkg.contains("musically") || pkg.contains("tiktok") ||
            pkg.contains("sharechat.moj") || pkg.contains("eterno.josh")
        ) {
            return true
        }

        val combinedMeta = (labels.asSequence() + descriptions.asSequence())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toList()
        val idsLower = viewIds.map { it.lowercase(Locale.ROOT) }

        if (pkg.contains("instagram")) {
            val isGridOrProfile = combinedMeta.any {
                it.contains("posts you've liked") ||
                    it.contains("liked posts") ||
                    it.contains("liked reels") ||
                    it == "select" ||
                    it.contains("edit profile")
            }
            if (isGridOrProfile) return false
            val hasClipsId = idsLower.any { it.contains("clips") || it.contains("reel") }
            val hasReelMeta = combinedMeta.any {
                it.contains("original audio") ||
                    it.contains("watch again") ||
                    it.contains("replay reel") ||
                    it.contains("audio is muted") ||
                    it.contains("tap to mute")
            }
            return hasClipsId || hasReelMeta
        }

        if (pkg.contains("youtube")) {
            val hasShortsId = idsLower.any {
                it.contains("reel") || it.contains("shorts_player") || it.contains("shorts_video")
            }
            val hasShortsMeta = combinedMeta.any {
                it.contains("shorts remix") ||
                    it.contains("dislike this video") ||
                    it.contains("use this sound") ||
                    it.contains("remix this short")
            }
            return hasShortsId || hasShortsMeta
        }

        return false
    }
}
