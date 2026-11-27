package com.example.gemini.data.live

import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireAndFirstTurnBrowserTest {

    private val videoQuestionnaire = ChoiceQuestionnaire(
        title = "Antigravity has a question",
        questions = listOf(
            ChoiceQuestion(
                id = "q1",
                prompt = "Choose execution mode for GTA 6 thumbnail project",
                isMultiSelect = false,
                options = listOf(
                    ChoiceOption(
                        id = "opt-standard",
                        label = "(Recommended) Standard mode (without Agent)",
                        description = "Faster direct execution"
                    ),
                    ChoiceOption(
                        id = "opt-agent",
                        label = "Agent Mode",
                        description = "Autonomous multi-step planning"
                    )
                ),
                allowCustomAnswer = true
            )
        )
    )

    @Test
    fun `ChatQuestionnaireHelper selects right mode from Hinglish commands and recommended shortcut`() {
        val standardRes = ChatQuestionnaireHelper.buildSubmission(
            "Standard mode pe submit karo",
            videoQuestionnaire,
            action = "submit"
        )
        assertTrue(standardRes.success)
        assertEquals(listOf("opt-standard"), standardRes.responses.first().selectedOptionIds)
        assertTrue(standardRes.summaryDisplay.contains("Standard mode"))

        val recRes = ChatQuestionnaireHelper.buildSubmission("recommended", videoQuestionnaire, "submit")
        assertTrue(recRes.success)
        assertEquals(listOf("opt-standard"), recRes.responses.first().selectedOptionIds)

        val agentRes = ChatQuestionnaireHelper.buildSubmission("Agent mode choose karo", videoQuestionnaire, "submit")
        assertTrue(agentRes.success)
        assertEquals(listOf("opt-agent"), agentRes.responses.first().selectedOptionIds)

        val ordinalRes = ChatQuestionnaireHelper.buildSubmission("dusra option", videoQuestionnaire, "submit")
        assertTrue(ordinalRes.success)
        assertEquals(listOf("opt-agent"), ordinalRes.responses.first().selectedOptionIds)
    }

    @Test
    fun `ChatQuestionnaireHelper skips and validates missing option cleanly`() {
        val skipped = ChatQuestionnaireHelper.buildSubmission("chhodo skip karo", videoQuestionnaire, "submit")
        assertTrue(skipped.success)
        assertTrue(skipped.skipped)
        assertEquals(true, skipped.responses.first().skipped)

        val bareSubmit = ChatQuestionnaireHelper.buildSubmission("submit", videoQuestionnaire, "submit")
        assertFalse(bareSubmit.success)
        assertTrue(bareSubmit.errorMessage.contains("Available options"))
    }

    @Test
    fun `evaluateTaskCreation stops 36 duplicate chat loops and intercepts questionnaire clicks`() {
        val now = 1_000_000L
        val existing = listOf(
            ChatQuestionnaireHelper.RecentTaskRecord(
                sessionId = "conv-gta-1",
                prompt = "Create DeepX Google Flow GTA 6 thumbnail website",
                tokens = ChatQuestionnaireHelper.tokenizeTaskIntent("Create DeepX Google Flow GTA 6 thumbnail website"),
                createdAtMs = now - 15_000L
            )
        )

        // 1. User answered Questionnaire -> must NEVER open new chat
        val ansDecision = ChatQuestionnaireHelper.evaluateTaskCreation(
            cleanPrompt = "Select Standard mode and click Submit",
            activeQuestionnaire = videoQuestionnaire,
            recentTasks = existing,
            nowMs = now
        )
        assertTrue(ansDecision is ChatQuestionnaireHelper.TaskCreationDecision.AnswerPendingQuestion)

        // 2. Model reworded & retried same task -> must reuse existing session instead of creating 36 chats
        val dupDecision = ChatQuestionnaireHelper.evaluateTaskCreation(
            cleanPrompt = "Google Flow DeepX GTA 6 thumbnail website banao",
            activeQuestionnaire = null,
            recentTasks = existing,
            nowMs = now + 20_000L
        )
        assertTrue(dupDecision is ChatQuestionnaireHelper.TaskCreationDecision.ReuseExistingTask)
        assertEquals("conv-gta-1", (dupDecision as ChatQuestionnaireHelper.TaskCreationDecision.ReuseExistingTask).record.sessionId)

        // 3. Explicit request for a separate new chat still succeeds
        val freshSeparate = ChatQuestionnaireHelper.evaluateTaskCreation(
            cleanPrompt = "nayi alag chat mein ecommerce dashboard banao",
            activeQuestionnaire = null,
            recentTasks = existing,
            nowMs = now + 25_000L
        )
        assertTrue(freshSeparate is ChatQuestionnaireHelper.TaskCreationDecision.CreateNewTask)
    }

    @Test
    fun `isQuestionnaireClickPhrase identifies card taps on Aura UI`() {
        assertTrue(ChatQuestionnaireHelper.isQuestionnaireClickPhrase("Standard mode", "", videoQuestionnaire))
        assertTrue(ChatQuestionnaireHelper.isQuestionnaireClickPhrase("Submit", "Submit questionnaire", videoQuestionnaire))
        assertFalse(ChatQuestionnaireHelper.isQuestionnaireClickPhrase("Custom AntiGem Plugins", "open plugins", videoQuestionnaire))
    }
}
