package com.example.gemini.data.live

import java.util.Locale

/**
 * Pure-Kotlin internal navigation resolver for Aura AI's own screens, drawer tabs,
 * plugin pages, settings sections, and top-level view modes.
 * Allows Voice to open any internal screen directly without Android Accessibility clicks.
 */
object AppNavigationRouter {

    sealed class NavTarget {
        abstract val label: String

        data class ViewMode(
            val mode: String,
            override val label: String
        ) : NavTarget()

        data class Drawer(
            val open: Boolean,
            val tabFilter: String? = null,
            override val label: String
        ) : NavTarget()

        data class Settings(
            val sectionName: String,
            override val label: String
        ) : NavTarget()

        data class Modal(
            val modalKey: String,
            override val label: String
        ) : NavTarget()
    }

    private val NON_ALNUM = Regex("[^\\p{L}\\p{M}\\p{N}&]+")

    fun resolve(rawTarget: String): NavTarget? {
        val normalized = HindiAsrNormalizer.normalize(rawTarget)
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null

        val isCloseCmd = normalized.contains("close drawer") ||
            normalized.contains("drawer band") ||
            normalized.contains("drawer close") ||
            normalized.contains("sidebar band") ||
            normalized.contains("sidebar close")
        if (isCloseCmd) {
            return NavTarget.Drawer(open = false, tabFilter = null, label = "Side Drawer closed")
        }

        if (normalized.contains("close settings") ||
            normalized.contains("settings band") ||
            normalized.contains("close modal") ||
            normalized.contains("close menu") ||
            normalized.contains("plugins band")
        ) {
            return NavTarget.Modal("CLOSE_OVERLAYS", "Chat Screen")
        }

        // Guard: explicit named/pinned chat requests ("Cat Image Creation wala chat kholo")
        // must route to ChatMatcher/open_chat_by_name instead of misrouting to Screens/Gallery.
        val isExplicitChatRequest = (
            normalized.contains("wala chat") ||
                normalized.contains("wali chat") ||
                normalized.contains("wale chat") ||
                (normalized.contains("chat") &&
                    !normalized.contains("chat screen") &&
                    !normalized.contains("chat list") &&
                    !normalized.contains("chat history") &&
                    !normalized.contains("pin") &&
                    !normalized.contains("archiv") &&
                    !normalized.contains("drawer") &&
                    !normalized.contains("sidebar") &&
                    !normalized.contains("voice setting") &&
                    !normalized.contains("jarvis") &&
                    !normalized.contains("plugin") &&
                    !normalized.contains("setting"))
            ) && normalized != "chat"
        if (isExplicitChatRequest) {
            return null
        }

        // 1. Custom AntiGem Plugins vs Skills & Plugins vs MCP vs Slash Commands
        if (normalized.contains("custom antigem plugin") ||
            normalized.contains("custom plugin") ||
            normalized.contains("webview extension") ||
            normalized.contains("browser extension") ||
            normalized.contains("extensions")
        ) {
            return NavTarget.Settings("WEBVIEW_EXTENSIONS", "Custom AntiGem Plugins")
        }
        if (normalized.contains("skills") ||
            normalized.contains("google plugin") ||
            normalized.contains("agent plugin") ||
            normalized.contains("cascade plugin")
        ) {
            return NavTarget.Settings("SKILLS_PLUGINS", "Skills & Plugins")
        }
        if (normalized.contains("plugin")) {
            return NavTarget.Settings("WEBVIEW_EXTENSIONS", "Custom AntiGem Plugins")
        }
        if (normalized.contains("mcp")) {
            return NavTarget.Settings("MCP", "MCP Servers")
        }
        if (normalized.contains("slash command") || normalized.contains("custom command")) {
            return NavTarget.Settings("SLASH_COMMANDS", "Slash Commands")
        }

        // 2. Drawer & Drawer filter tabs
        val mentionsDrawer = normalized.contains("drawer") ||
            normalized.contains("sidebar") ||
            normalized.contains("side menu") ||
            normalized.contains("3 line") ||
            normalized.contains("three line") ||
            normalized.contains("chat list") ||
            normalized.contains("chat history") ||
            normalized.contains("hamburger")
        if (mentionsDrawer) {
            val tab = when {
                normalized.contains("pin") -> "PIN"
                normalized.contains("project") -> "PROJECT"
                normalized.contains("archiv") -> "ARCHIVE"
                normalized.contains("voice") -> "VOICE"
                else -> null
            }
            return NavTarget.Drawer(open = true, tabFilter = tab, label = if (tab != null) "Drawer ($tab)" else "Side Drawer")
        }
        if (normalized.contains("pinned tab") ||
            normalized.contains("pin tab") ||
            normalized.contains("pinned chats list") ||
            normalized.contains("pin wale chats") ||
            normalized.contains("archived chat") ||
            normalized.contains("archive list")
        ) {
            val tab = if (normalized.contains("archiv")) "ARCHIVE" else "PIN"
            val lbl = if (tab == "ARCHIVE") "Archived Chats" else "Pinned Chats"
            return NavTarget.Drawer(open = true, tabFilter = tab, label = lbl)
        }

        // 3. ChatScreen modals / overlays
        if (
            !normalized.contains("creation") && (
                (normalized.contains("images") && (normalized.contains("dikhao") || normalized.contains("kholo") || normalized.contains("open") || normalized.contains("show"))) ||
                    (normalized.contains("image") && (normalized.contains("gallery") || normalized.contains("list") || normalized.contains("history") || normalized.contains("all") || normalized.contains("saari")))
                )
        ) {
            return NavTarget.Modal("IMAGES_GALLERY", "Images Gallery")
        }
        if (normalized == "gallery" || normalized == "images" || normalized == "photos" || normalized.contains("tasveer")) {
            return NavTarget.Modal("IMAGES_GALLERY", "Images Gallery")
        }
        if (normalized.contains("customiz")) {
            return NavTarget.Modal("CUSTOMIZE", "Customize")
        }
        if (normalized.contains("project picker") ||
            normalized == "projects" ||
            normalized == "open projects" ||
            normalized == "projects kholo" ||
            normalized.contains("switch project") ||
            normalized.contains("workspace picker")
        ) {
            return NavTarget.Modal("PROJECT_PICKER", "Projects")
        }
        if (normalized.contains("theme")) {
            return NavTarget.Modal("THEMES_PANEL", "Themes")
        }
        if (normalized.contains("account") || normalized.contains("sign in") || normalized.contains("login")) {
            return NavTarget.Modal("ACCOUNTS", "Accounts")
        }
        if (normalized.contains("model picker") ||
            normalized.contains("model selector") ||
            normalized.contains("change model") ||
            normalized == "models"
        ) {
            return NavTarget.Modal("MODEL_SELECTOR", "Models")
        }

        // 4. Settings sub-pages (more specific before generic "settings")
        if (normalized.contains("auto reply") ||
            normalized.contains("autoreply") ||
            normalized.contains("smart confirm") ||
            (normalized.contains("whatsapp") && (normalized.contains("reply") || normalized.contains("setting")))
        ) {
            return NavTarget.Settings("JARVIS_WA_AUTO_REPLY", "WhatsApp Auto-Reply")
        }
        if (normalized.contains("quick dial") ||
            (normalized.contains("contact") && (normalized.contains("open") || normalized.contains("setting") || normalized.contains("kholo")))
        ) {
            return NavTarget.Settings("JARVIS_CONTACTS", "Contacts & Quick Dial")
        }
        if (normalized.contains("personality") || normalized.contains("persona")) {
            return NavTarget.Settings("JARVIS_PERSONALITY", "Voice Personality")
        }
        if (normalized.contains("permission") || normalized.contains("authorization")) {
            return NavTarget.Settings("JARVIS_PERMISSIONS", "System Authorizations")
        }
        if (normalized.contains("live api") || normalized.contains("api key") || normalized.contains("gemini key")) {
            return NavTarget.Settings("JARVIS_LIVE_API", "Gemini Live API Settings")
        }
        if (normalized.contains("memory") || normalized.contains("remembered fact")) {
            return NavTarget.Settings("JARVIS_MEMORY", "Persistent Memory")
        }
        if (normalized.contains("voice setting") ||
            normalized.contains("aura voice") ||
            normalized.contains("jarvis setting")
        ) {
            return NavTarget.Settings("JARVIS", "Aura Voice Settings")
        }
        if (normalized.contains("task") && !normalized.contains("background task")) {
            return NavTarget.Settings("JARVIS_TASKS", "Tasks")
        }
        if (normalized.contains("server") || normalized.contains("bridge") || normalized.contains("hub url")) {
            return NavTarget.Settings("SERVERS", "Servers & Connectivity")
        }
        if (normalized.contains("agent") && (normalized.contains("setting") || normalized.contains("kholo") || normalized.contains("open"))) {
            return NavTarget.Settings("AGENTS", "Agents")
        }
        if (normalized.contains("appearance") || normalized.contains("font size")) {
            return NavTarget.Settings("APPEARANCE", "Appearance")
        }
        if (normalized.contains("global context")) {
            return NavTarget.Settings("GLOBAL_CONTEXT", "Global Context")
        }
        if (normalized.contains("automation") && normalized.contains("setting")) {
            return NavTarget.Settings("ANDROID_AUTOMATION", "Android Automation Settings")
        }
        if (normalized.contains("backup") || normalized.contains("restore")) {
            return NavTarget.Settings("BACKUPS", "Backups & Restore")
        }
        if (normalized.contains("github") && (normalized.contains("setting") || normalized.contains("open") || normalized.contains("kholo"))) {
            return NavTarget.Settings("GITHUB", "GitHub")
        }
        if (normalized == "rea" || normalized.contains("reverse engineering")) {
            return NavTarget.Settings("REA", "REA Reverse Engineering")
        }
        if (normalized.contains("diagnostic") || normalized.contains("network inspector")) {
            return NavTarget.Settings("DIAGNOSTICS", "Diagnostics")
        }
        if (normalized.contains("changelog") || normalized.contains("what s new")) {
            return NavTarget.Settings("CHANGELOG", "What's New")
        }
        if (normalized.contains("about")) {
            return NavTarget.Settings("ABOUT", "About & Info")
        }
        if (normalized.contains("setting") || normalized.contains("preference") || normalized.contains("config")) {
            return NavTarget.Settings("MAIN", "Settings")
        }

        // 5. Top-level MainActivity AppViewMode screens
        if (normalized == "ide" || normalized.contains("code editor") || normalized.contains("open ide") || normalized.contains("ide kholo")) {
            return NavTarget.ViewMode("IDE", "IDE")
        }
        if (normalized.contains("terminal") || normalized.contains("termux") || normalized.contains("shell")) {
            return NavTarget.ViewMode("TERMINAL", "Terminal")
        }
        if (normalized == "notes" || normalized.contains("open notes") || normalized.contains("notes kholo") || normalized.contains("notepad")) {
            return NavTarget.ViewMode("NOTES", "Notes")
        }
        if (normalized == "files" || normalized.contains("file manager") || normalized.contains("file explorer") || normalized.contains("files kholo")) {
            return NavTarget.ViewMode("FILES", "Files")
        }
        if (normalized.contains("in built browser") || normalized == "browser") {
            return NavTarget.ViewMode("BROWSER", "In-Built Browser")
        }
        if (normalized == "chat" || normalized.contains("chat screen") || normalized.contains("home screen")) {
            return NavTarget.ViewMode("CHAT", "Chat")
        }

        return null
    }
}
