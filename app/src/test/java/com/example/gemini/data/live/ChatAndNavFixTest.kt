package com.example.gemini.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAndNavFixTest {

    private val sampleCandidates = listOf(
        ChatMatcher.ChatCandidate(
            id = "conv-cat-100",
            title = "Cat Image Creation",
            updatedAt = 5_000L,
            isPinned = false
        ),
        ChatMatcher.ChatCandidate(
            id = "conv-flip-9912",
            title = "Flipkart E-Commerce Website Design",
            projectName = "flipkart-storefront",
            workspaceUri = "file:///storage/emulated/0/projects/flipkart-storefront",
            summary = "Build Next.js Flipkart store with Tailwind cart and product grid",
            updatedAt = 2_000L,
            isPinned = true
        ),
        ChatMatcher.ChatCandidate(
            id = "conv-flip-old",
            title = "Flipkart Website Clone",
            updatedAt = 4_000L,
            isPinned = false
        ),
        ChatMatcher.ChatCandidate(
            id = "conv-arch-300",
            title = "Old Portfolio Website",
            summary = "Static HTML portfolio from last month",
            updatedAt = 1_000L,
            isPinned = false,
            isArchived = true
        ),
        ChatMatcher.ChatCandidate(
            id = "conv-pin-arch",
            title = "Archived Pinned Draft",
            updatedAt = 9_000L,
            isPinned = true,
            isArchived = true
        )
    )

    @Test
    fun `ChatMatcher resolves Hinglish pinned chat request above newer unpinned duplicates`() {
        val res = ChatMatcher.findBestMatch("Flipkart E-Commerce website wala chat kholo", sampleCandidates)
        assertNotNull(res)
        assertEquals("conv-flip-9912", res!!.candidate.id)
        assertTrue(res.candidate.isPinned)

        val pinHintRes = ChatMatcher.findBestMatch("jo Flipkart wala chat pin hai wo kholo", sampleCandidates)
        assertNotNull(pinHintRes)
        assertEquals("conv-flip-9912", pinHintRes!!.candidate.id)
    }

    @Test
    fun `ChatMatcher handles pure pinned chat intent without title keywords`() {
        val res = ChatMatcher.findBestMatch("jo chat pin hai sabse upar wo kholo", sampleCandidates)
        assertNotNull(res)
        assertEquals("conv-flip-9912", res!!.candidate.id)
        assertEquals("top_pinned", res.reason)
    }

    @Test
    fun `ChatMatcher matches by exact ID prefix project and summary and rejects unknown chats`() {
        val byId = ChatMatcher.findBestMatch("conv-flip-99", sampleCandidates)
        assertNotNull(byId)
        assertEquals("conv-flip-9912", byId!!.candidate.id)
        assertEquals("exact_id", byId.reason)

        val bySummary = ChatMatcher.findBestMatch("Tailwind product grid store", sampleCandidates)
        assertNotNull(bySummary)
        assertEquals("conv-flip-9912", bySummary!!.candidate.id)

        val archived = ChatMatcher.findBestMatch("archived Old Portfolio Website", sampleCandidates)
        assertNotNull(archived)
        assertEquals("conv-arch-300", archived!!.candidate.id)

        val nonexistent = ChatMatcher.findBestMatch("Quantum Rocket Launchpad", sampleCandidates)
        assertNull(nonexistent)
    }

    @Test
    fun `ChatMatcher detects chat opening phrases accurately`() {
        assertTrue(ChatMatcher.isLikelyChatOpenPhrase("Flipkart E-Commerce website wala chat"))
        assertTrue(ChatMatcher.isLikelyChatOpenPhrase("Cat Image Creation chat"))
        assertTrue(ChatMatcher.isLikelyChatOpenPhrase("pinned chat kholo"))
        assertFalse(ChatMatcher.isLikelyChatOpenPhrase("Custom AntiGem Plugins"))
        assertFalse(ChatMatcher.isLikelyChatOpenPhrase("Open Side Drawer"))
    }

    @Test
    fun `AppNavigationRouter resolves plugins drawer tabs modals settings and view modes`() {
        val customPlugins = AppNavigationRouter.resolve("Custom AntiGem Plugins kholo")
        assertTrue(customPlugins is AppNavigationRouter.NavTarget.Settings)
        assertEquals("WEBVIEW_EXTENSIONS", (customPlugins as AppNavigationRouter.NavTarget.Settings).sectionName)

        val skillsPlugins = AppNavigationRouter.resolve("Skills & Plugins open karo")
        assertTrue(skillsPlugins is AppNavigationRouter.NavTarget.Settings)
        assertEquals("SKILLS_PLUGINS", (skillsPlugins as AppNavigationRouter.NavTarget.Settings).sectionName)

        val genericPlugins = AppNavigationRouter.resolve("Plugins dikhao")
        assertTrue(genericPlugins is AppNavigationRouter.NavTarget.Settings)
        assertEquals("WEBVIEW_EXTENSIONS", (genericPlugins as AppNavigationRouter.NavTarget.Settings).sectionName)

        val drawer = AppNavigationRouter.resolve("Drawer kholo")
        assertEquals(AppNavigationRouter.NavTarget.Drawer(open = true, tabFilter = null, label = "Side Drawer"), drawer)

        val pinDrawer = AppNavigationRouter.resolve("Drawer ka pin tab kholo")
        assertEquals(AppNavigationRouter.NavTarget.Drawer(open = true, tabFilter = "PIN", label = "Drawer (PIN)"), pinDrawer)

        val closeDrawer = AppNavigationRouter.resolve("Drawer band karo")
        assertEquals(AppNavigationRouter.NavTarget.Drawer(open = false, tabFilter = null, label = "Side Drawer closed"), closeDrawer)

        val gallery = AppNavigationRouter.resolve("Images Gallery dikhao")
        assertEquals(AppNavigationRouter.NavTarget.Modal("IMAGES_GALLERY", "Images Gallery"), gallery)

        val customize = AppNavigationRouter.resolve("Customize screen kholo")
        assertEquals(AppNavigationRouter.NavTarget.Modal("CUSTOMIZE", "Customize"), customize)

        val voiceSettings = AppNavigationRouter.resolve("Aura Voice Settings open karo")
        assertEquals(AppNavigationRouter.NavTarget.Settings("JARVIS", "Aura Voice Settings"), voiceSettings)

        val ide = AppNavigationRouter.resolve("IDE kholo")
        assertEquals(AppNavigationRouter.NavTarget.ViewMode("IDE", "IDE"), ide)

        val terminal = AppNavigationRouter.resolve("Terminal open karo")
        assertEquals(AppNavigationRouter.NavTarget.ViewMode("TERMINAL", "Terminal"), terminal)
    }

    @Test
    fun `AppNavigationRouter does not hijack named chat requests as gallery or plugin screens`() {
        assertNull(AppNavigationRouter.resolve("Cat Image Creation wala chat kholo"))
        assertNull(AppNavigationRouter.resolve("Cat Image Creation kholo"))
        assertNull(AppNavigationRouter.resolve("Plugin Project wala chat open karo"))
        assertNull(AppNavigationRouter.resolve("Flipkart E-Commerce Website Design wala chat kholo"))
    }
}
