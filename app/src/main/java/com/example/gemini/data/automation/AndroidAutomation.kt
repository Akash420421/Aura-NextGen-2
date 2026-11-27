package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.live.DeviceControlHelper
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * android_automation_mcp: lets the model see and drive the phone through the accessibility service,
 * with a few adb-only extras (keys, install, uninstall, force stop, shell). Served by AndroidLocalBridgeServer at /android.
 */
object AndroidAutomation {

    const val INSTRUCTIONS = "Controls this Android phone via the Accessibility Service. Look first: android_read_screen gives clean readable text & form fields; " +
        "android_get_ui_tree lists on-screen elements with ids like e12 and their centers; android_screenshot shows what it looks like. " +
        "Then act on element ids, visible text, or screen-pixel coordinates (android_tap, android_double_tap, android_type_text, android_scroll, android_scroll_to_text, " +
        "android_swipe, android_drag, android_pinch, android_multi_path_gesture, android_node_action, android_clipboard, android_recent_notifications). " +
        "Ids are only valid for the latest tree, so read it again after the screen changes. " +
        "For anything the tools don't cover, use android_adb_shell (or adb in the terminal). Ask the user before anything destructive or " +
        "outward-facing: uninstalling, deleting, sending messages, payments, changing security settings. " +
        "If a tool says a permission or ADB is missing, tell the user exactly what to do and stop."

    private const val ADB_NEEDED = "This needs ADB. Ask the user to open AntiGem → Settings → ADB Connect and connect " +
        "(pair once, with Wireless debugging on), then try again."

    /** Elements of the latest android_get_ui_tree, by id. */
    private val elements = ConcurrentHashMap<String, AccessibilityNodeInfo>()

    // ---------------- readiness ----------------

    private fun component(context: Context) = ComponentName(context, AutomationAccessibilityService::class.java)

    fun isServiceRunning(): Boolean = AutomationAccessibilityService.instance != null

    fun isServiceEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val c = component(context)
        return enabled.split(':').any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
    }

    fun canTakeScreenshots(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Why the MCP can't be used yet, or null when it can. */
    fun notReadyMessage(context: Context): String? = when {
        isServiceRunning() -> null
        isServiceEnabledInSettings(context) -> "Android Automation's accessibility service is turned on but not running. " +
            "Ask the user to turn \"AntiGem Android Automation\" off and on again in Android Settings → Accessibility, then reconnect this MCP server."
        else -> "Android Automation is not set up. Ask the user to open AntiGem → Settings → Android Automation and grant the permissions there " +
            "(the Accessibility service; on Android 13+ they may first need App info → ⋮ → Allow restricted settings). Then reconnect this MCP server."
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Turns the accessibility service on through adb, which also works when Android blocks it as a restricted setting. */
    suspend fun enableViaAdb(context: Context): Result<Unit> {
        val device = AdbConnectManager.ensureConnected() ?: return Result.failure(IllegalStateException("ADB is not connected"))
        val name = component(context).flattenToString()
        val current = AdbConnectManager.deviceShell(device, "settings get secure enabled_accessibility_services").output.trim()
        val services = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (services.none { it.equals(name, true) }) services += name
        AdbConnectManager.deviceShell(device, "settings put secure enabled_accessibility_services ${services.joinToString(":")}")
        AdbConnectManager.deviceShell(device, "settings put secure accessibility_enabled 1")
        repeat(20) {
            if (isServiceRunning()) return Result.success(Unit)
            delay(250)
        }
        return if (isServiceEnabledInSettings(context)) Result.success(Unit) else Result.failure(IllegalStateException("Android didn't accept the change"))
    }

    // ---------------- tool list ----------------

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val elementId = "element_id" to prop("string", "Element id from the latest android_get_ui_tree, e.g. e12")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("android_get_status", "Phone state: screen size, app in front, screen on/locked, and whether screenshots and ADB are available."))
        put(tool("android_get_device_info",
            "Device details: battery (level, charging, temperature, health), device and Android build, RAM, storage, CPU, display, " +
                "network, audio volumes and ringer mode, brightness, airplane/Do Not Disturb/location state, locale, time zone and uptime.",
            emptyList(),
            "sections" to prop("string", "Comma-separated subset: battery, device, memory, storage, display, network, audio, system (default: all)")
        ))
        put(tool("android_read_screen",
            "Read clean, structured visible screen text in top-to-bottom reading order, plus editable input fields (passwords masked) and key interactive actions. " +
                "Prefer this for reading articles, messages, emails, or page summaries; use android_get_ui_tree when you need element ids.",
            emptyList(),
            "all_windows" to prop("boolean", "Include all interactive windows (dialogs, keyboard, split screen). Default false"),
            "max_blocks" to prop("integer", "Maximum readable text blocks to return (default 250)")
        ))
        put(tool("android_get_ui_tree",
            "List the on-screen elements (text, description, resource id, type, center point, bounds, and clickable/editable/scrollable/checked flags), " +
                "each with an id like e12 for the other tools. Ids are replaced on every call.",
            emptyList(),
            "filter" to prop("string", "Only list elements whose text, description, hint or resource id contains this (case-insensitive)"),
            "all_windows" to prop("boolean", "Include every window (status bar, keyboard, dialogs, other split-screen app), not just the active one. Default false"),
            "max_elements" to prop("integer", "Maximum elements to list (default 300)")
        ))
        put(tool("android_screenshot",
            "Screenshot of the whole screen as an image. Black if the app blocks screenshots. Tap coordinates are screen pixels; the result says how to convert if the image was scaled.",
            emptyList(),
            "max_width" to prop("integer", "Scale down to at most this width in pixels (default 1080)"),
            "quality" to prop("integer", "JPEG quality 10-100 (default 70)")
        ))
        put(tool("android_tap", "Tap (or long-press) an element by id, by visible text/description, or at a point in screen pixels.", emptyList(),
            elementId,
            "text" to prop("string", "Visible text or content description to locate and tap when element_id is omitted"),
            "x" to prop("number", "Screen x in pixels, when no element_id or text"),
            "y" to prop("number", "Screen y in pixels, when no element_id or text"),
            "long_press" to prop("boolean", "Long-press instead of tap")
        ))
        put(tool("android_double_tap", "Double-tap an element by id, visible text, or screen-pixel coordinates (useful for liking posts, zooming, or video seek).", emptyList(),
            elementId,
            "text" to prop("string", "Visible text or content description to double-tap"),
            "x" to prop("number", "Screen x in pixels"),
            "y" to prop("number", "Screen y in pixels"),
            "gap_ms" to prop("integer", "Delay between the two taps in milliseconds (default 90)")
        ))
        put(tool("android_type_text", "Type into a text field: the given element, or the focused field. Replaces its text unless append is true.",
            listOf("text"),
            "text" to prop("string", "Text to type"),
            elementId,
            "append" to prop("boolean", "Add to the existing text instead of replacing it"),
            "submit" to prop("boolean", "Press the keyboard's enter/search/send action afterwards")
        ))
        put(tool("android_scroll", "Scroll a list or page. direction is where to move the view: down shows content further down.",
            listOf("direction"),
            "direction" to prop("string", "Which way to scroll", listOf("up", "down", "left", "right")),
            elementId.first to prop("string", "Scrollable element (or a child of one) from the latest tree; omit to swipe across the middle of the screen"),
            "distance" to prop("number", "When swiping: fraction of the screen to move, 0.1-0.9 (default 0.5)")
        ))
        put(tool("android_scroll_to_text", "Scroll repeatedly until text, description, hint, or resource id appears on screen (or end of list is reached).",
            listOf("text"),
            "text" to prop("string", "Text, content description, hint, or resource id to find"),
            "direction" to prop("string", "Scroll direction (default down)", listOf("down", "up", "left", "right")),
            "max_scrolls" to prop("integer", "Maximum scroll gestures to attempt (default 8, max 20)"),
            "tap_when_found" to prop("boolean", "Automatically tap the matched element once found (default false)")
        ))
        put(tool("android_swipe", "Swipe from one point to another in screen pixels (drag, pull to refresh, sliders).",
            listOf("x1", "y1", "x2", "y2"),
            "x1" to prop("number", "Start x"), "y1" to prop("number", "Start y"),
            "x2" to prop("number", "End x"), "y2" to prop("number", "End y"),
            "duration_ms" to prop("integer", "How long the swipe takes (default 300; longer = slower drag)")
        ))
        put(tool("android_drag", "Long-press and drag from a start element/point to a target element/point (reordering items, moving icons, drag-and-drop).",
            emptyList(),
            "from_element_id" to prop("string", "Start element id from the latest UI tree"),
            "to_element_id" to prop("string", "Destination element id from the latest UI tree"),
            "x1" to prop("number", "Start x in pixels (if from_element_id omitted)"),
            "y1" to prop("number", "Start y in pixels (if from_element_id omitted)"),
            "x2" to prop("number", "End x in pixels (if to_element_id omitted)"),
            "y2" to prop("number", "End y in pixels (if to_element_id omitted)"),
            "hold_ms" to prop("integer", "Hold duration before dragging in ms (default 350)"),
            "duration_ms" to prop("integer", "Drag motion duration in ms (default 450)")
        ))
        put(tool("android_pinch", "Two-finger pinch gesture to zoom in or zoom out on maps, photos, web pages, or documents.",
            listOf("direction"),
            "direction" to prop("string", "Zoom direction: 'in' spreads fingers apart to zoom in, 'out' pinches fingers together to zoom out", listOf("in", "out")),
            "x" to prop("number", "Center x in pixels (default screen center)"),
            "y" to prop("number", "Center y in pixels (default screen center)"),
            "scale" to prop("number", "Pinch span as fraction of screen width, 0.15-0.85 (default 0.45)"),
            "duration_ms" to prop("integer", "Gesture duration in ms (default 400)")
        ))
        put(tool("android_multi_path_gesture", "Trace a continuous single-finger gesture through 2 or more waypoints (curved swipes, gestures, drawing).",
            listOf("points"),
            "points" to JSONObject().apply {
                put("type", "array")
                put("description", "Ordered list of {x, y} screen-pixel waypoints (at least 2)")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("x", prop("number", "Screen x in pixels"))
                        put("y", prop("number", "Screen y in pixels"))
                    })
                    put("required", JSONArray(listOf("x", "y")))
                })
            },
            "duration_ms" to prop("integer", "Total gesture duration in ms (default 500)")
        ))
        put(tool("android_node_action", "Perform a semantic accessibility action on an element from the latest UI tree (copy, paste, cut, select, clear_text, expand, collapse, dismiss, focus, clear_focus, set_progress).",
            listOf("element_id", "action"),
            elementId,
            "action" to prop("string", "Semantic node action", listOf("click", "long_click", "focus", "clear_focus", "select", "copy", "paste", "cut", "clear_text", "expand", "collapse", "dismiss", "set_progress")),
            "value" to prop("string", "Optional numeric value when action is set_progress")
        ))
        put(tool("android_clipboard", "Read or write plain text on the Android clipboard for cross-app copy/paste automation.",
            listOf("action"),
            "action" to prop("string", "Clipboard operation", listOf("read", "write")),
            "text" to prop("string", "Text to copy to clipboard when action is write")
        ))
        put(tool("android_recent_notifications", "List recent notifications and extracted OTP codes observed by the Accessibility Service.",
            emptyList(),
            "limit" to prop("integer", "Maximum notifications to return (default 10, max 20)"),
            "max_age_seconds" to prop("integer", "Only include notifications newer than this many seconds (default 300)")
        ))
        put(tool("android_global_action", "System navigation: back, home, recent apps, notification shade, quick settings, power menu, lock screen, split screen, take_screenshot, dismiss_notifications.",
            listOf("action"),
            "action" to prop("string", "Action", listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "split_screen", "take_screenshot", "dismiss_notifications"))
        ))
        put(tool("android_clear_recent_apps", "Clears all recent/minimized apps from the Android Recents overview with multi-OEM button matching, gesture fallbacks, and real post-action verification."))
        put(tool("android_close_recent_app", "Closes a specific app from Android Recents overview by finding its card, centering it, and dismissing it with post-action verification.",
            listOf("app_name"),
            "app_name" to prop("string", "Name of the app to dismiss from recents, e.g. Chrome, Camera, YouTube")
        ))
        put(tool("android_list_apps", "List installed apps that can be opened, as label and package name.", emptyList(),
            "filter" to prop("string", "Only apps whose label or package contains this")
        ))
        put(tool("android_launch_app", "Open an app by package name or by its label.", emptyList(),
            "package" to prop("string", "Package name, e.g. com.android.settings"),
            "name" to prop("string", "App label as shown in the launcher, e.g. Settings")
        ))
        put(tool("android_open_intent", "Start an activity with an intent: open a link or deep link (https:, tel:, geo:, market:…) or a settings page (action android.settings.WIFI_SETTINGS).",
            emptyList(),
            "action" to prop("string", "Intent action (default android.intent.action.VIEW)"),
            "uri" to prop("string", "Data URI"),
            "package" to prop("string", "Only open in this app")
        ))
        put(tool("android_wait_for", "Wait until text appears (or disappears) on screen, or, without text, until the screen stops changing.", emptyList(),
            "text" to prop("string", "Text or description to wait for"),
            "gone" to prop("boolean", "Wait for the text to disappear instead"),
            "timeout_ms" to prop("integer", "Give up after this long (default 5000, max 30000)")
        ))
        put(tool("android_press_key", "Press a hardware or keyboard key, e.g. ENTER, DEL, VOLUME_UP, POWER, MEDIA_PLAY_PAUSE, or a key code number. BACK, HOME and APP_SWITCH work without ADB; other keys need ADB.",
            listOf("key"),
            "key" to prop("string", "Key name (KEYCODE_ prefix optional) or key code")
        ))
        put(tool("android_install_apk", "Install an APK file from the terminal's storage. Needs ADB.", listOf("path"),
            "path" to prop("string", "APK path, absolute or relative to the terminal home")
        ))
        put(tool("android_uninstall_app", "Uninstall an app. With ADB it's removed directly; without, Android's uninstall dialog opens and you must tap OK. Always ask the user first.",
            listOf("package"),
            "package" to prop("string", "Package name"),
            "keep_data" to prop("boolean", "Keep the app's data and cache (ADB only)")
        ))
        put(tool("android_force_stop_app", "Force-stop an app. Needs ADB.", listOf("package"),
            "package" to prop("string", "Package name")
        ))
        put(tool("android_adb_shell", "Run a command in `adb shell` on this phone (shell user: pm, am, settings, dumpsys, input, cmd, svc, wm…). Needs ADB.",
            listOf("command"),
            "command" to prop("string", "Shell command"),
            "timeout_seconds" to prop("integer", "Default 30, max 300")
        ))
    }

    // ---------------- results ----------------

    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)
    private fun staleElement(id: String) = error("Element $id isn't in the latest UI tree (the screen changed). Call android_get_ui_tree again.")

    // ---------------- calls ----------------

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject {
        notReadyMessage(context)?.let { return error(it) }
        val svc = AutomationAccessibilityService.instance ?: return error(notReadyMessage(context) ?: "Accessibility service not running")
        return runCatching {
            when (name) {
                "android_get_status" -> status(context, svc)
                "android_get_device_info" -> text(deviceInfo(context, args.optString("sections")).toString(2))
                "android_read_screen" -> text(svc.readScreenContent(
                    includeAllWindows = args.optBoolean("all_windows", false),
                    maxBlocks = args.optInt("max_blocks", 250).coerceIn(10, 1000)
                ).toFormattedString())
                "android_get_ui_tree" -> text(uiTree(svc, args.optString("filter").ifBlank { null }, args.optBoolean("all_windows"), args.optInt("max_elements", 300).coerceIn(10, 2000)))
                "android_screenshot" -> screenshot(context, svc, args.optInt("max_width", 1080).coerceIn(200, 4000), args.optInt("quality", 70).coerceIn(10, 100))
                "android_tap" -> tap(svc, args)
                "android_double_tap" -> doubleTap(svc, args)
                "android_type_text" -> typeText(svc, args)
                "android_scroll" -> scroll(context, svc, args)
                "android_scroll_to_text" -> scrollToText(svc, args)
                "android_swipe" -> {
                    val path = Path().apply {
                        moveTo(args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat())
                        lineTo(args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat())
                    }
                    if (!svc.gesture(path, args.optLong("duration_ms", 300))) return error("The swipe was cancelled (the screen may be off or locked).")
                    svc.awaitIdle()
                    text("Swiped. ${foreground(svc)}")
                }
                "android_drag" -> drag(svc, args)
                "android_pinch" -> pinch(svc, args)
                "android_multi_path_gesture" -> multiPathGesture(svc, args)
                "android_node_action" -> nodeAction(svc, args)
                "android_clipboard" -> clipboardAction(svc, args)
                "android_recent_notifications" -> recentNotifications(args)
                "android_global_action" -> globalAction(svc, args.getString("action"))
                "android_clear_recent_apps" -> {
                    val mode = args.optString("mode", "clear_all")
                    val res = svc.executeRecentsAction(clearAll = true, mode = mode)
                    if (res.status == "SUCCESS") text("Success: ${res.message}")
                    else if (res.status == "PARTIAL") text("Partial: ${res.message} (${res.remainingCardsCount} cards remain)")
                    else error("Failed: ${res.message}")
                }
                "android_close_recent_app" -> {
                    val app = args.getString("app_name")
                    val res = svc.executeRecentsAction(appName = app, clearAll = false)
                    if (res.status == "SUCCESS") text("Success: ${res.message}")
                    else error("Failed: ${res.message}")
                }
                "android_list_apps" -> listApps(context, args.optString("filter"))
                "android_launch_app" -> launchApp(context, svc, args)
                "android_open_intent" -> openIntent(svc, args)
                "android_wait_for" -> waitFor(svc, args)
                "android_press_key" -> pressKey(svc, args.getString("key"))
                "android_install_apk" -> withAdb { d ->
                    val out = AdbConnectManager.runAdb(d, "install -r ${AdbConnectManager.shellQuote(args.getString("path"))}", 240).output.trim()
                    if (out.contains("Success")) text("Installed.\n$out") else error(out.ifBlank { "adb install failed" })
                }
                "android_uninstall_app" -> uninstall(context, svc, args)
                "android_force_stop_app" -> withAdb { d ->
                    val pkg = packageArg(args)
                    AdbConnectManager.deviceShell(d, "am force-stop $pkg")
                    text("Force-stopped $pkg.")
                }
                "android_adb_shell" -> withAdb { d ->
                    val seconds = args.optInt("timeout_seconds", 30).coerceIn(1, 300)
                    val result = AdbConnectManager.deviceShell(d, args.getString("command"), seconds)
                    val out = result.output.ifBlank { "(no output)" }.let { if (it.length > 60_000) it.take(60_000) + "\n…(truncated)" else it }
                    if (result.exitCode == 0) text(out) else error("Exit code ${result.exitCode}\n$out")
                }
                else -> error("Unknown tool $name")
            }
        }.getOrElse { error("$name failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private suspend fun withAdb(block: suspend (AdbConnectManager.Device) -> JSONObject): JSONObject {
        val device = AdbConnectManager.ensureConnected() ?: return error(ADB_NEEDED)
        return block(device)
    }

    private fun packageArg(args: JSONObject): String {
        val pkg = args.getString("package").trim()
        require(Regex("^[A-Za-z0-9_.]+$").matches(pkg)) { "invalid package name \"$pkg\"" }
        return pkg
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun foreground(svc: AutomationAccessibilityService): String {
        val pkg = svc.getForegroundAppRootNode()?.packageName ?: svc.rootInActiveWindow?.packageName ?: svc.foregroundPackage
        return "In front: ${pkg ?: "unknown"}"
    }

    private fun status(context: Context, svc: AutomationAccessibilityService): JSONObject {
        val (w, h) = screenSize(context)
        val adb = AdbConnectManager.state.value.connectedDevice
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return text(JSONObject().apply {
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("screen_px", "${w}x$h")
            put("screen_on", power.isInteractive)
            put("locked", keyguard.isKeyguardLocked)
            put("keyboard_visible", svc.isKeyboardVisible())
            put("foreground_package", svc.getForegroundAppRootNode()?.packageName ?: svc.rootInActiveWindow?.packageName ?: svc.foregroundPackage ?: JSONObject.NULL)
            put("foreground_activity", svc.foregroundClass ?: JSONObject.NULL)
            put("screenshots", if (canTakeScreenshots()) "yes" else if (adb != null) "via ADB" else "no (needs Android 11+ or ADB)")
            put("adb", adb?.serial ?: "not connected (adb-only tools will fail)")
        }.toString(2))
    }

    // ---------------- device info ----------------

    fun getDeviceInfoJson(
        context: Context,
        sections: String = "battery,device,memory,storage,display,network,audio,system"
    ): JSONObject = deviceInfo(context, sections)

    fun readRecentNotificationsJson(limit: Int = 10, maxAgeSeconds: Long = 1800L): JSONObject {
        val maxItems = limit.coerceIn(1, 20)
        val fromRing = AutomationAccessibilityService.getRecentNotifications(maxItems, maxAgeSeconds.coerceIn(5L, 7_200L) * 1_000L)
        val fromActive: List<AutomationAccessibilityService.Companion.CapturedNotification> = try {
            AuraNotificationListenerService.instance?.getActiveSnapshot(maxItems).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        val seen = LinkedHashSet<String>()
        val merged = mutableListOf<AutomationAccessibilityService.Companion.CapturedNotification>()
        for (item in (fromRing + fromActive).sortedByDescending { it.timestamp }) {
            val sig = "${item.packageName}|${item.text.lowercase()}"
            if (seen.add(sig)) {
                merged.add(item)
                if (merged.size >= maxItems) break
            }
        }
        val otp = AutomationAccessibilityService.getRecentOtp()
        return JSONObject().apply {
            if (otp != null) put("recent_otp", otp)
            put("listener_connected", AuraNotificationListenerService.instance != null)
            put("notifications", JSONArray().apply {
                for (item in merged) {
                    put(JSONObject().apply {
                        put("package", item.packageName)
                        put("text", item.text)
                        put("age_seconds", ((System.currentTimeMillis() - item.timestamp) / 1000L).coerceAtLeast(0L))
                    })
                }
            })
        }
    }

    private fun deviceInfo(context: Context, sections: String): JSONObject {
        val wanted = sections.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun want(name: String) = wanted.isEmpty() || name in wanted
        val gb = 1024.0 * 1024 * 1024
        fun gib(bytes: Long) = "%.2f GB".format(bytes / gb)
        val info = JSONObject()

        if (want("battery")) info.put("battery", JSONObject().apply {
            val intent = runCatching {
                context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            val bmCap = runCatching {
                bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }.getOrDefault(-1)
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val rawPct = DeviceControlHelper.computeBatteryPercent(bmCap, level, scale)
            put("level_percent", rawPct.coerceIn(0, 100))
            val intentStatus = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pluggedCode = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isChargingNow = runCatching { bm?.isCharging == true }.getOrDefault(false) ||
                pluggedCode != 0 ||
                intentStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING
            put("is_charging", isChargingNow)
            put("status", when (intentStatus) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> if (isChargingNow) "charging" else "discharging"
            })
            put("plugged", when (pluggedCode) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "unplugged"
                else -> "other"
            })
            put("health", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            })
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { put("temperature_c", it / 10.0) }
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { put("voltage_mv", it) }
            intent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY)?.let { put("technology", it) }
            runCatching { bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
                .getOrNull()
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
                ?.let { put("current_now_ua", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { bm?.computeChargeTimeRemaining() }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { put("charge_time_remaining_min", it / 60_000) }
            }
            put("power_save_mode", context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true)
        })

        if (want("device")) info.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("build", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
        })

        if (want("memory")) info.put("memory", JSONObject().apply {
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            put("ram_total", gib(mi.totalMem))
            put("ram_available", gib(mi.availMem))
            put("ram_used_percent", ((mi.totalMem - mi.availMem) * 100 / mi.totalMem.coerceAtLeast(1)).toInt())
            put("low_memory", mi.lowMemory)
        })

        if (want("storage")) info.put("storage", JSONObject().apply {
            fun stat(dir: java.io.File) = runCatching { android.os.StatFs(dir.path) }.getOrNull()?.let {
                JSONObject().put("total", gib(it.totalBytes)).put("free", gib(it.availableBytes))
                    .put("used_percent", ((it.totalBytes - it.availableBytes) * 100 / it.totalBytes.coerceAtLeast(1)).toInt())
            }
            stat(android.os.Environment.getDataDirectory())?.let { put("internal", it) }
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, dir -> stat(dir)?.let { put("sd_card_${i + 1}", it) } }
        })

        if (want("display")) info.put("display", JSONObject().apply {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            put("resolution_px", "${metrics.widthPixels}x${metrics.heightPixels}")
            put("density_dpi", metrics.densityDpi)
            put("refresh_rate_hz", display.refreshRate.toInt())
            put("rotation_degrees", display.rotation * 90)
            put("font_scale", context.resources.configuration.fontScale)
            put("dark_mode", (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()?.let { put("brightness_0_255", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrNull()?.let { put("auto_brightness", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.let { put("screen_timeout_s", it / 1000) }
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        })

        if (want("network")) info.put("network", JSONObject().apply {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.getNetworkCapabilities(cm.activeNetwork) else null
            put("connected", caps != null)
            if (caps != null) {
                put("type", when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    else -> "other"
                })
                put("vpn", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                put("internet_validated", caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("metered", cm.isActiveNetworkMetered)
                put("down_kbps", caps.linkDownstreamBandwidthKbps)
                put("up_kbps", caps.linkUpstreamBandwidthKbps)
            }
            put("ip_addresses", JSONArray(runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { nif -> nif.inetAddresses.toList().filter { !it.isLinkLocalAddress }.map { "${nif.name}: ${it.hostAddress}" } }
            }.getOrDefault(emptyList())))
            put("airplane_mode", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            put("wireless_debugging", Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1)
        })

        if (want("audio")) info.put("audio", JSONObject().apply {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            put("ringer_mode", when (am.ringerMode) {
                android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            listOf(
                "media" to android.media.AudioManager.STREAM_MUSIC, "ring" to android.media.AudioManager.STREAM_RING,
                "notification" to android.media.AudioManager.STREAM_NOTIFICATION, "alarm" to android.media.AudioManager.STREAM_ALARM,
                "call" to android.media.AudioManager.STREAM_VOICE_CALL
            ).forEach { (label, stream) -> put("${label}_volume", "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}") }
            put("music_playing", am.isMusicActive)
            put("headphones_connected", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                } else am.isWiredHeadsetOn)
        })

        if (want("system")) info.put("system", JSONObject().apply {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            put("do_not_disturb", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL else false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                put("location_enabled", context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled)
            put("developer_options", Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
            put("locale", java.util.Locale.getDefault().toLanguageTag())
            put("time_zone", java.util.TimeZone.getDefault().id)
            put("local_time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
            put("uptime", android.os.SystemClock.elapsedRealtime().let { "%dd %02dh %02dm".format(it / 86_400_000, it / 3_600_000 % 24, it / 60_000 % 60) })
            put("adb", AdbConnectManager.state.value.connectedDevice?.serial ?: "not connected")
        })

        if (info.length() == 0) info.put("error", "No known sections in \"$sections\". Use battery, device, memory, storage, display, network, audio, system.")
        return info
    }

    // ---------------- UI tree ----------------

    private fun uiTree(svc: AutomationAccessibilityService, filter: String?, allWindows: Boolean, max: Int): String {
        elements.clear()
        val roots = if (allWindows) svc.windows.mapNotNull { w -> w.root?.let { "${w.title ?: it.packageName} (${windowType(w.type)})" to it } }
        else listOfNotNull((svc.getForegroundAppRootNode() ?: svc.rootInActiveWindow)?.let { "${it.packageName}" to it })
        if (roots.isEmpty()) return "No window content available (the screen may be off, or showing a secure lock screen)."
        val out = StringBuilder()
        var count = 0
        var truncated = false
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
            val resId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable
            val matches = filter == null || listOf(label, desc, hint, resId).any { it.contains(filter, ignoreCase = true) }
            var childDepth = depth
            if ((actionable || label.isNotEmpty() || desc.isNotEmpty()) && matches) {
                if (count >= max) { truncated = true; return }
                val id = "e${++count}"
                elements[id] = node
                node.getBoundsInScreen(rect)
                out.append("  ".repeat(depth)).append('[').append(id).append("] ").append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (label.isNotEmpty()) out.append(" \"").append(label.take(120).replace('\n', ' ')).append('"')
                if (desc.isNotEmpty() && desc != label) out.append(" desc=\"").append(desc.take(120).replace('\n', ' ')).append('"')
                if (hint.isNotEmpty() && hint != label) out.append(" hint=\"").append(hint.take(60)).append('"')
                if (resId.isNotEmpty()) out.append(" id=").append(resId)
                out.append(" @").append(rect.centerX()).append(',').append(rect.centerY())
                out.append(" [").append(rect.left).append(',').append(rect.top).append(',').append(rect.right).append(',').append(rect.bottom).append(']')
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isLongClickable) add("long-clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isSelected) add("selected")
                    if (node.isFocused) add("focused")
                    if (!node.isEnabled) add("disabled")
                    if (node.isPassword) add("password")
                }
                if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(","))
                out.append('\n')
                if (filter == null) childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                if (truncated) return
                node.getChild(i)?.let { walk(it, childDepth) }
            }
        }

        roots.forEach { (title, root) ->
            out.append("Window: ").append(title).append('\n')
            walk(root, 1)
        }
        if (count == 0) out.append(if (filter != null) "No elements match \"$filter\".\n" else "No readable elements; try android_screenshot.\n")
        if (truncated) out.append("…more elements not shown; use filter or max_elements.\n")
        return out.toString()
    }

    private fun windowType(type: Int) = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "other"
    }

    private fun element(args: JSONObject): Pair<String?, AccessibilityNodeInfo?> {
        val id = args.optString("element_id").trim().ifBlank { null } ?: return null to null
        return id to elements[id]?.takeIf { it.refresh() }
    }

    // ---------------- actions ----------------

    private suspend fun tapAt(svc: AutomationAccessibilityService, x: Float, y: Float, long: Boolean): Boolean =
        svc.gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)

    private fun findNodeByText(svc: AutomationAccessibilityService, target: String): AccessibilityNodeInfo? {
        val roots = svc.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
        val lowerTarget = target.lowercase().trim()

        for (root in roots) {
            val systemMatches = root.findAccessibilityNodeInfosByText(target)
            val visibleDirect = systemMatches.firstOrNull { it.isVisibleToUser && !svc.isProfileAvatarOrIcon(it) }
            if (visibleDirect != null) return visibleDirect

            val found = searchNodeRecursively(svc, root, lowerTarget)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeRecursively(svc: AutomationAccessibilityService, node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        // Strictly avoid profile pictures, avatars, and logos when selecting contacts or groups
        if (svc.isProfileAvatarOrIcon(node)) return null

        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((nodeText.contains(target) || nodeDesc.contains(target)) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursively(svc, child, target)
            if (found != null) return found
        }
        return null
    }

    private fun findAnyEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAnyEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        // Anti-Mic Guard: never treat voice recording / mic button as Send!
        val isMic = desc.contains("voice") || desc.contains("mic") || desc.contains("record") || id.contains("voice_note")
        if (isMic) return null

        if ((desc == "send" || desc == "send message" || text == "send" || id.endsWith(":id/send")) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private suspend fun tap(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val long = args.optBoolean("long_press")
        val (id, node) = element(args)
        val what: String
        if (id != null) {
            node ?: return staleElement(id)
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val target = generateSequence(node) { it.parent }.firstOrNull { if (long) it.isLongClickable else it.isClickable }
            if (target?.performAction(action) != true) {
                // Not clickable through accessibility (custom views, games): touch its center instead
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (!tapAt(svc, r.exactCenterX(), r.exactCenterY(), long)) return error("Couldn't tap $id (the screen may be off or locked).")
            }
            what = id
        } else if (args.has("text") || args.has("element_id_or_text") || args.has("label")) {
            val query = (args.optString("text").ifBlank { args.optString("element_id_or_text").ifBlank { args.optString("label") } }).trim()
            if (query.isBlank()) return error("Give element_id, text, or x and y.")
            val matchedNode = findNodeByText(svc, query)
                ?: return error("Could not find any element matching '$query' on the screen.")
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val clickable = generateSequence(matchedNode) { it.parent }.firstOrNull { if (long) it.isLongClickable else it.isClickable }
            if (clickable?.performAction(action) != true) {
                val r = Rect().also { matchedNode.getBoundsInScreen(it) }
                if (!tapAt(svc, r.exactCenterX(), r.exactCenterY(), long)) return error("Couldn't tap '$query' (the screen may be off or locked).")
            }
            what = "'$query'"
        } else {
            if (!args.has("x") || !args.has("y")) return error("Give element_id, text, or x and y.")
            val x = args.getDouble("x").toFloat()
            val y = args.getDouble("y").toFloat()
            if (!tapAt(svc, x, y, long)) return error("The tap was cancelled (the screen may be off or locked).")
            what = "${x.toInt()},${y.toInt()}"
        }
        svc.awaitIdle()
        return text("${if (long) "Long-pressed" else "Tapped"} $what. ${foreground(svc)}")
    }

    private suspend fun typeText(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val (id, node) = element(args)
        if (id != null && node == null) return staleElement(id)
        val text = args.getString("text")
        val submit = args.optBoolean("submit", false)
        val append = args.optBoolean("append", false)
        val clearExisting = !append

        val bounds = android.graphics.Rect()
        if (node != null) {
            node.getBoundsInScreen(bounds)
        }

        val res = svc.directInjectText(
            textToType = text,
            targetRect = if (bounds.width() > 0 && bounds.height() > 0) bounds else null,
            targetInputName = id,
            submit = submit,
            clearExisting = clearExisting
        )

        svc.awaitIdle()
        return if (res.isSuccess) {
            val note = if (res.submitted) " (submitted)" else ""
            text("Direct text injection succeeded via ${res.method}$note. ${foreground(svc)}")
        } else {
            error("Direct text injection failed: ${res.reason}. ${foreground(svc)}")
        }
    }

    private suspend fun scroll(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val direction = args.getString("direction").lowercase()
        val (id, node) = element(args)
        if (id != null) {
            node ?: return staleElement(id)
            val scrollable = generateSequence(node) { it.parent }.firstOrNull { it.isScrollable }
            if (scrollable != null) {
                val specific = when (direction) {
                    "up" -> AccessibilityAction.ACTION_SCROLL_UP
                    "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                    "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                    else -> AccessibilityAction.ACTION_SCROLL_RIGHT
                }
                val generic = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val ok = scrollable.performAction(specific.id) || scrollable.performAction(generic)
                if (ok) {
                    svc.awaitIdle()
                    return text("Scrolled $direction.")
                }
            }
            // Fallback: swipe over the node bounds if direct action didn't scroll
            val r = Rect().also { node.getBoundsInScreen(it) }
            val ncx = r.exactCenterX()
            val ncy = r.exactCenterY()
            val span = (r.height() * 0.4f).coerceAtLeast(150f)
            val (ndx, ndy) = when (direction) {
                "down" -> 0f to -span
                "up" -> 0f to span
                "right" -> -span to 0f
                else -> span to 0f
            }
            val nodePath = Path().apply { moveTo(ncx - ndx, ncy - ndy); lineTo(ncx + ndx, ncy + ndy) }
            if (svc.gesture(nodePath, 350)) {
                svc.awaitIdle()
                return text("Scrolled $direction.")
            }
        }
        val (w, h) = screenSize(context)
        val d = args.optDouble("distance", 0.5).coerceIn(0.1, 0.9).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Swipe opposite to the direction of travel: to see content below, the finger moves up
        val (dx, dy) = when (direction) {
            "down" -> 0f to -h * d / 2
            "up" -> 0f to h * d / 2
            "right" -> -w * d / 2 to 0f
            else -> w * d / 2 to 0f
        }
        val path = Path().apply { moveTo(cx - dx, cy - dy); lineTo(cx + dx, cy + dy) }
        if (!svc.gesture(path, 350)) return error("The swipe was cancelled (the screen may be off or locked).")
        svc.awaitIdle()
        return text("Scrolled $direction.")
    }

    private suspend fun doubleTap(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val gapMs = args.optLong("gap_ms", 90L).coerceIn(40L, 300L)
        val (id, node) = element(args)
        val (x, y, what) = when {
            id != null -> {
                node ?: return staleElement(id)
                val r = Rect().also { node.getBoundsInScreen(it) }
                Triple(r.exactCenterX(), r.exactCenterY(), id)
            }
            args.has("text") || args.has("label") -> {
                val query = args.optString("text").ifBlank { args.optString("label") }.trim()
                if (query.isBlank()) return error("Give element_id, text, or x and y.")
                val matched = findNodeByText(svc, query)
                    ?: return error("Could not find any element matching '$query' on the screen.")
                val r = Rect().also { matched.getBoundsInScreen(it) }
                Triple(r.exactCenterX(), r.exactCenterY(), "'$query'")
            }
            args.has("x") && args.has("y") -> {
                val px = args.getDouble("x").toFloat()
                val py = args.getDouble("y").toFloat()
                Triple(px, py, "${px.toInt()},${py.toInt()}")
            }
            else -> return error("Give element_id, text, or x and y.")
        }
        if (!svc.doubleTapAt(x, y, gapMs = gapMs)) {
            return error("Double-tap was cancelled (the screen may be off or locked).")
        }
        svc.awaitIdle()
        return text("Double-tapped $what. ${foreground(svc)}")
    }

    private suspend fun scrollToText(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val query = args.getString("text").trim()
        val direction = args.optString("direction", "down").lowercase()
        val maxScrolls = args.optInt("max_scrolls", 8).coerceIn(0, 20)
        val tapWhenFound = args.optBoolean("tap_when_found", false)
        val res = svc.scrollToFind(query, direction, maxScrolls, tapWhenFound)
        return if (res.found) {
            val coords = res.bounds?.let { " @${it.centerX()},${it.centerY()} [${it.left},${it.top},${it.right},${it.bottom}]" }.orEmpty()
            text("${res.reason}$coords. ${foreground(svc)}")
        } else {
            error("${res.reason} ${foreground(svc)}")
        }
    }

    private suspend fun drag(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val fromId = args.optString("from_element_id").trim().ifBlank { null }
        val toId = args.optString("to_element_id").trim().ifBlank { null }
        val (x1, y1) = if (fromId != null) {
            val n = elements[fromId]?.takeIf { it.refresh() } ?: return staleElement(fromId)
            val r = Rect().also { n.getBoundsInScreen(it) }
            r.exactCenterX() to r.exactCenterY()
        } else {
            if (!args.has("x1") || !args.has("y1")) return error("Give from_element_id or x1 and y1.")
            args.getDouble("x1").toFloat() to args.getDouble("y1").toFloat()
        }
        val (x2, y2) = if (toId != null) {
            val n = elements[toId]?.takeIf { it.refresh() } ?: return staleElement(toId)
            val r = Rect().also { n.getBoundsInScreen(it) }
            r.exactCenterX() to r.exactCenterY()
        } else {
            if (!args.has("x2") || !args.has("y2")) return error("Give to_element_id or x2 and y2.")
            args.getDouble("x2").toFloat() to args.getDouble("y2").toFloat()
        }
        val holdMs = args.optLong("hold_ms", 350L)
        val durationMs = args.optLong("duration_ms", 450L)
        if (!svc.dragGesture(x1, y1, x2, y2, holdMs, durationMs)) {
            return error("The drag gesture was cancelled (the screen may be off or locked).")
        }
        svc.awaitIdle()
        return text("Dragged from (${x1.toInt()},${y1.toInt()}) to (${x2.toInt()},${y2.toInt()}). ${foreground(svc)}")
    }

    private suspend fun pinch(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val direction = args.getString("direction").trim().lowercase()
        val zoomIn = when (direction) {
            "in", "zoom_in", "expand" -> true
            "out", "zoom_out", "shrink" -> false
            else -> return error("direction must be 'in' or 'out'.")
        }
        val cx = if (args.has("x")) args.getDouble("x").toFloat() else null
        val cy = if (args.has("y")) args.getDouble("y").toFloat() else null
        val scale = args.optDouble("scale", 0.45).toFloat()
        val durationMs = args.optLong("duration_ms", 400L)
        if (!svc.pinchGesture(cx, cy, zoomIn, scale, durationMs)) {
            return error("The pinch gesture was cancelled (the screen may be off or locked).")
        }
        svc.awaitIdle()
        return text("Pinched ${if (zoomIn) "in (zoomed in)" else "out (zoomed out)"}. ${foreground(svc)}")
    }

    private suspend fun multiPathGesture(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val arr = args.optJSONArray("points") ?: return error("points array is required.")
        if (arr.length() < 2) return error("points must contain at least 2 waypoints.")
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until arr.length()) {
            val pt = arr.optJSONObject(i) ?: return error("Invalid waypoint at index $i.")
            pts.add(pt.getDouble("x").toFloat() to pt.getDouble("y").toFloat())
        }
        val durationMs = args.optLong("duration_ms", 500L)
        if (!svc.multiPointGesture(pts, durationMs)) {
            return error("The multi-point gesture was cancelled (the screen may be off or locked).")
        }
        svc.awaitIdle()
        return text("Executed path gesture across ${pts.size} points. ${foreground(svc)}")
    }

    private suspend fun nodeAction(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val (id, node) = element(args)
        if (id == null) return error("element_id is required.")
        if (node == null) return staleElement(id)
        val action = args.getString("action").trim()
        val value = args.optString("value").ifBlank { null }
        val ok = svc.performNodeAction(node, action, value)
        if (!ok) return error("Element $id rejected action '$action'.")
        svc.awaitIdle()
        return text("Performed '$action' on $id. ${foreground(svc)}")
    }

    private suspend fun clipboardAction(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        return when (args.getString("action").trim().lowercase()) {
            "read" -> {
                val clip = svc.readClipboardText()
                if (clip == null) text("Clipboard is empty or unavailable in the background.")
                else text(clip)
            }
            "write" -> {
                val content = args.optString("text", "")
                if (svc.writeClipboardText(content)) text("Copied ${content.length} character(s) to clipboard.")
                else error("Failed to write to clipboard.")
            }
            else -> error("action must be 'read' or 'write'.")
        }
    }

    private fun recentNotifications(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val maxAgeSec = args.optLong("max_age_seconds", 300L).coerceIn(5L, 3_600L)
        return text(readRecentNotificationsJson(limit, maxAgeSec).toString(2))
    }

    private suspend fun globalAction(svc: AutomationAccessibilityService, action: String): JSONObject {
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else return error("lock_screen needs Android 9+")
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                else return error("take_screenshot needs Android 9+")
            "dismiss_notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
                else return error("dismiss_notifications needs Android 12+")
            else -> return error("Unknown action $action")
        }
        if (!svc.performGlobalAction(code)) return error("Android refused $action.")
        svc.awaitIdle()
        return text("Done: $action. ${foreground(svc)}")
    }

    private fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun listApps(context: Context, filter: String): JSONObject {
        val apps = launchableApps(context).filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
        if (apps.isEmpty()) return text("No apps match \"$filter\".")
        return text(apps.take(500).joinToString("\n") { "${it.first} — ${it.second}" })
    }

    private suspend fun launchApp(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val name = args.optString("name").trim().ifBlank { args.optString("app").trim() }
        val rawPkg = args.optString("package").trim()

        if (name.equals("aura", true) || name.equals("aura ai", true) || name.equals("antigem", true)) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (intent != null) {
                svc.startActivity(intent)
                svc.awaitIdle(500, 2_000)
                return text("Opened Aura AI. ${foreground(svc)}")
            }
        }

        val directPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "camera" to "com.google.android.GoogleCamera",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android"
        )

        val pkg = rawPkg.ifBlank {
            directPackages[name.lowercase()] ?: run {
                if (name.isBlank()) return error("Give package or name.")
                val apps = launchableApps(context)
                (apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.contains(name, true) })?.second
                    ?: return error("No app called \"$name\". Use android_list_apps to see what's installed.")
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return error("$pkg isn't installed or can't be opened.")
        // Started from the accessibility service, which Android lets open activities from the background
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        svc.awaitIdle(500, 4_000)
        return text("Opened $pkg. ${foreground(svc)}")
    }

    private suspend fun openIntent(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val intent = Intent(args.optString("action").ifBlank { Intent.ACTION_VIEW })
        args.optString("uri").ifBlank { null }?.let { intent.data = Uri.parse(it) }
        args.optString("package").ifBlank { null }?.let { intent.setPackage(it) }
        if (intent.action == Intent.ACTION_VIEW && intent.data == null) return error("ACTION_VIEW needs a uri.")
        return try {
            svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            svc.awaitIdle(500, 4_000)
            text("Started. ${foreground(svc)}")
        } catch (e: android.content.ActivityNotFoundException) {
            error("No app can handle that intent.")
        }
    }

    private suspend fun waitFor(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val timeout = args.optLong("timeout_ms", 5_000).coerceIn(100, 30_000)
        val wanted = args.optString("text").ifBlank { null }
        if (wanted == null) {
            svc.awaitIdle(500, timeout)
            return text("The screen is idle. ${foreground(svc)}")
        }
        val gone = args.optBoolean("gone")
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val found = svc.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
                .any { root -> root.findAccessibilityNodeInfosByText(wanted).any { it.isVisibleToUser } }
            if (found != gone) return text(if (gone) "\"$wanted\" is gone." else "\"$wanted\" is on screen.")
            delay(300)
        }
        return error("Timed out after ${timeout}ms: \"$wanted\" ${if (gone) "is still on screen" else "never appeared"}.")
    }

    private suspend fun pressKey(svc: AutomationAccessibilityService, key: String): JSONObject {
        val name = key.trim().uppercase().removePrefix("KEYCODE_")
        if (!Regex("^[A-Z0-9_]+$").matches(name)) return error("Invalid key \"$key\".")
        val device = AdbConnectManager.ensureConnected()
        if (device == null) {
            val global = when (name) {
                "BACK", "4" -> AccessibilityService.GLOBAL_ACTION_BACK
                "HOME", "3" -> AccessibilityService.GLOBAL_ACTION_HOME
                "APP_SWITCH", "187" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return error("Pressing $name needs ADB. ${ADB_NEEDED.substringAfter("This needs ADB. ")}")
            }
            svc.performGlobalAction(global)
        } else {
            val code = if (name.all(Char::isDigit)) name else "KEYCODE_$name"
            val result = AdbConnectManager.deviceShell(device, "input keyevent $code", 15)
            if (result.exitCode != 0) return error(result.output.ifBlank { "input keyevent failed" })
        }
        svc.awaitIdle()
        return text("Pressed $name. ${foreground(svc)}")
    }

    private suspend fun uninstall(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val pkg = packageArg(args)
        if (pkg == context.packageName) return error("Refusing to uninstall AntiGem itself.")
        val device = AdbConnectManager.ensureConnected()
        if (device != null) {
            val keep = if (args.optBoolean("keep_data")) "-k " else ""
            val out = AdbConnectManager.deviceShell(device, "pm uninstall $keep$pkg", 60).output.trim()
            return if (out.contains("Success")) text("Uninstalled $pkg.") else error(out.ifBlank { "pm uninstall failed" })
        }
        @Suppress("DEPRECATION")
        svc.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        svc.awaitIdle(500, 3_000)
        return text("ADB isn't connected, so Android's uninstall dialog is open for $pkg. Read the UI tree and tap OK/Uninstall to confirm.")
    }

    // ---------------- screenshot ----------------

    private suspend fun screenshot(context: Context, svc: AutomationAccessibilityService, maxWidth: Int, quality: Int): JSONObject {
        val bitmap: Bitmap = if (canTakeScreenshots()) {
            svc.screenshot().getOrElse { return error("Screenshot failed: ${it.message}") }
        } else {
            val device = AdbConnectManager.ensureConnected()
                ?: return error("Screenshots need Android 11+ or ADB. ${ADB_NEEDED.substringAfter("This needs ADB. ")}")
            val b64 = AdbConnectManager.runAdb(device, "exec-out screencap -p | base64 | tr -d '\\n'", 30).output.trim()
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return error("adb screencap failed")
        }
        val (screenW, screenH) = screenSize(context)
        val scaled = if (bitmap.width > maxWidth) Bitmap.createScaledBitmap(bitmap, maxWidth, bitmap.height * maxWidth / bitmap.width, true) else bitmap
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val factor = screenW.toFloat() / scaled.width
        val note = if (factor in 0.99f..1.01f) "Screenshot ${scaled.width}x${scaled.height}; image pixels are screen pixels."
            else "Screenshot ${scaled.width}x${scaled.height} of a ${screenW}x$screenH screen: multiply image coordinates by ${"%.3f".format(factor)} for android_tap/android_swipe."
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))
            .put(JSONObject().put("type", "text").put("text", "$note ${foreground(svc)}")))
    }
}
