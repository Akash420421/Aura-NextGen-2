package com.example.gemini.data.context

import com.example.gemini.data.live.ActiveProjectContext
import com.example.gemini.data.live.AuraAgentEventBus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests ensuring stale or cross-chat project context (e.g. `flipkart-clone` from an older
 * chat) never leaks into a fresh "New Chat" greeting like `"hi"` or unrelated image prompts.
 */
class GlobalContextProjectScopeTest {

    @Before
    fun setUp() {
        AuraAgentEventBus.clearActiveProject()
    }

    @After
    fun tearDown() {
        AuraAgentEventBus.clearActiveProject()
    }

    @Test
    fun `stale project from another session never injects into new chat`() {
        val now = 1_700_000_000_000L
        val flipkartProj = ActiveProjectContext(
            sessionId = "old-flipkart-chat",
            projectName = "flipkart-clone",
            workspaceDir = "/data/user/0/com.termux/files/home/projects/flipkart-clone",
            requirementsSummary = "Build ecommerce storefront",
            updatedAt = now - 60_000L
        )

        assertFalse(
            GlobalContext.shouldInjectActiveProjectContext(
                project = flipkartProj,
                chatId = "fresh-new-chat",
                hasEarlierMessages = false,
                nowMs = now
            )
        )
        assertFalse(
            GlobalContext.shouldInjectActiveProjectContext(
                project = flipkartProj,
                chatId = null,
                hasEarlierMessages = false,
                nowMs = now
            )
        )
    }

    @Test
    fun `same session with casual first turn greeting does not inject active project banner`() {
        val now = 1_700_000_000_000L
        val casualProj = ActiveProjectContext(
            sessionId = "chat-1",
            projectName = "flipkart-clone",
            workspaceDir = "/data/user/0/com.termux/files/home/projects/flipkart-clone",
            requirementsSummary = "hi",
            updatedAt = now - 5_000L
        )

        assertFalse(
            GlobalContext.shouldInjectActiveProjectContext(
                project = casualProj,
                chatId = "chat-1",
                hasEarlierMessages = false,
                nowMs = now
            )
        )
    }

    @Test
    fun `active ongoing session within recency window keeps project context`() {
        val now = 1_700_000_000_000L
        val activeProj = ActiveProjectContext(
            sessionId = "chat-proj",
            projectName = "flipkart-clone",
            workspaceDir = "/data/user/0/com.termux/files/home/projects/flipkart-clone",
            requirementsSummary = "Fix cart drawer alignment",
            updatedAt = now - 2 * 60_000L
        )

        assertTrue(
            GlobalContext.shouldInjectActiveProjectContext(
                project = activeProj,
                chatId = "chat-proj",
                hasEarlierMessages = true,
                nowMs = now
            )
        )
        assertFalse(
            GlobalContext.shouldInjectActiveProjectContext(
                project = activeProj,
                chatId = "chat-proj",
                hasEarlierMessages = true,
                nowMs = now + 25 * 60_000L
            )
        )
    }

    @Test
    fun `everyMessageBody returns null when stale project is on event bus for new chat`() {
        AuraAgentEventBus.setActiveProject(
            ActiveProjectContext(
                sessionId = "old-flipkart-chat",
                projectName = "flipkart-clone",
                workspaceDir = "/data/user/0/com.termux/files/home/projects/flipkart-clone"
            )
        )

        assertNull(GlobalContext.everyMessageBody(chatId = "fresh-new-chat", hasEarlierMessages = false))
    }

    @Test
    fun `casual greetings vs explicit project signals are classified accurately`() {
        assertTrue(GlobalContext.isCasualNonProjectText("hi"))
        assertTrue(GlobalContext.isCasualNonProjectText("Hello!"))
        assertTrue(GlobalContext.isCasualNonProjectText("kya haal hai?"))
        assertFalse(GlobalContext.hasExplicitProjectSignal("hi", "flipkart-clone"))
        assertFalse(GlobalContext.hasExplicitProjectSignal("Tell me a joke", "flipkart-clone"))

        assertTrue(GlobalContext.hasExplicitProjectSignal("Continue working on flipkart-clone", "flipkart-clone"))
        assertTrue(GlobalContext.hasExplicitProjectSignal("Fix the React navbar bug in App.tsx", "flipkart-clone"))
    }
}
