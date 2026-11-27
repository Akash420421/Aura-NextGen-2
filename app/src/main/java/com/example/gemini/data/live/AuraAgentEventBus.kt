package com.example.gemini.data.live

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Shared project state passed across Voice, Screen Share/Vision, and Antigravity Chat
 * so the assistant never loses track of what the user is currently building.
 */
data class ActiveProjectContext(
    val sessionId: String = "",
    val projectName: String = "",
    val projectType: String = "",
    val theme: String = "",
    val techStack: String = "",
    val workspaceDir: String = "",
    val requirementsSummary: String = "",
    val previewUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = projectName.isNotBlank() || workspaceDir.isNotBlank() || requirementsSummary.isNotBlank()

    fun isRecentlyActive(maxAgeMs: Long = 20 * 60_000L): Boolean =
        isActive && (System.currentTimeMillis() - updatedAt <= maxAgeMs)

    fun toPromptBlock(): String {
        if (!isActive) return ""
        return buildString {
            append("## Active Project Context:\n")
            if (projectName.isNotBlank()) append("- Project Name: $projectName\n")
            if (projectType.isNotBlank()) append("- Project Type: $projectType\n")
            if (theme.isNotBlank()) append("- Theme / Aesthetic Lock: $theme\n")
            if (techStack.isNotBlank()) append("- Tech Stack / Architecture: $techStack\n")
            if (workspaceDir.isNotBlank()) append("- Workspace Directory: $workspaceDir\n")
            if (previewUrl.isNotBlank()) append("- Live Preview URL: $previewUrl\n")
            if (requirementsSummary.isNotBlank()) append("- Requirements Summary: ${requirementsSummary.take(400)}\n")
            append("- Strict Scope Rule: ONLY link Pinterest, Dribbble, or screenshots to $projectName if the user EXPLICITLY asks to apply design/code to this project. If the user is solving a quiz/exam, messaging, or doing unrelated tasks in Chrome or other apps, IGNORE this project completely.\n")
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("sessionId", sessionId)
        put("projectName", projectName)
        put("projectType", projectType)
        put("theme", theme)
        put("techStack", techStack)
        put("workspaceDir", workspaceDir)
        put("requirementsSummary", requirementsSummary)
        put("previewUrl", previewUrl)
        put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJson(raw: String?): ActiveProjectContext? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                ActiveProjectContext(
                    sessionId = o.optString("sessionId", ""),
                    projectName = o.optString("projectName", ""),
                    projectType = o.optString("projectType", ""),
                    theme = o.optString("theme", ""),
                    techStack = o.optString("techStack", ""),
                    workspaceDir = o.optString("workspaceDir", ""),
                    requirementsSummary = o.optString("requirementsSummary", ""),
                    previewUrl = o.optString("previewUrl", ""),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            }.getOrNull()
        }
    }
}

/**
 * Unified real-time agent lifecycle events emitted by Antigravity/Chat backends and
 * LocalPreviewServerRunner so Aura Voice can proactively narrate milestones, questions,
 * completions, previews, and genuine errors as ONE unified assistant.
 */
sealed class AuraAgentEvent {
    abstract val conversationId: String
    abstract val timestamp: Long

    data class StepMilestone(
        override val conversationId: String,
        val summary: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    data class QuestionAsked(
        override val conversationId: String,
        val questionKey: String,
        val promptSummary: String,
        val options: List<String> = emptyList(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    data class BuildCompleted(
        override val conversationId: String,
        val title: String,
        val summary: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    data class ServerReady(
        override val conversationId: String,
        val url: String,
        val port: Int,
        val projectName: String = "",
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    data class RealError(
        override val conversationId: String,
        val errorTitle: String,
        val errorMessage: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    /** Incoming WhatsApp message held in Smart Confirm Mode awaiting user decision/reply. */
    data class WhatsAppIncoming(
        override val conversationId: String = "whatsapp_auto_reply",
        val messageId: String,
        val senderName: String,
        val cleanSenderName: String,
        val messageText: String,
        val suggestedDraft: String,
        val isGroupChat: Boolean,
        val groupName: String?,
        val isSensitive: Boolean,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    /** Notification that Aura executed a background Direct Auto-Reply on WhatsApp. */
    data class WhatsAppAutoReplied(
        override val conversationId: String = "whatsapp_auto_reply",
        val messageId: String,
        val senderName: String,
        val incomingText: String,
        val sentReply: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()

    /** Any incoming message/notification (SMS, Telegram, Instagram DM, Gmail, WhatsApp when Smart Confirm off, etc.). */
    data class IncomingMessageNotification(
        override val conversationId: String = "incoming_notifications",
        val notificationKey: String,
        val appName: String,
        val packageName: String,
        val senderName: String,
        val messageText: String,
        val otpCode: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuraAgentEvent()
}

object AuraAgentEventBus {
    private val _events = MutableSharedFlow<AuraAgentEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<AuraAgentEvent> = _events.asSharedFlow()

    private val _activeProject = MutableStateFlow(ActiveProjectContext())
    val activeProject: StateFlow<ActiveProjectContext> = _activeProject.asStateFlow()

    @Volatile private var lastQuestionKey: String = ""
    @Volatile private var lastErrorSignature: String = ""
    @Volatile private var lastErrorAt: Long = 0L

    fun emit(event: AuraAgentEvent) {
        when (event) {
            is AuraAgentEvent.QuestionAsked -> {
                if (event.questionKey.isNotBlank() && event.questionKey == lastQuestionKey) return
                lastQuestionKey = event.questionKey
            }
            is AuraAgentEvent.RealError -> {
                val sig = "${event.conversationId}:${event.errorTitle}:${event.errorMessage.take(80)}"
                val now = System.currentTimeMillis()
                if (sig == lastErrorSignature && now - lastErrorAt < 8_000L) return
                lastErrorSignature = sig
                lastErrorAt = now
            }
            else -> Unit
        }
        _events.tryEmit(event)
    }

    fun updateActiveProject(transform: (ActiveProjectContext) -> ActiveProjectContext) {
        val current = _activeProject.value
        val next = transform(current)
        if (next.copy(updatedAt = current.updatedAt) == current) return
        _activeProject.value = next.copy(updatedAt = System.currentTimeMillis())
    }

    fun setActiveProject(context: ActiveProjectContext) {
        _activeProject.value = context.copy(updatedAt = System.currentTimeMillis())
    }

    fun clearActiveProject() {
        if (_activeProject.value.isActive || _activeProject.value.sessionId.isNotBlank()) {
            _activeProject.value = ActiveProjectContext()
        }
    }

    fun clearActiveProjectIfSession(sessionId: String) {
        if (_activeProject.value.sessionId == sessionId) {
            _activeProject.value = ActiveProjectContext()
        }
    }

    fun clearActiveProjectIfDifferentSession(sessionId: String) {
        val current = _activeProject.value
        if (current.sessionId.isNotBlank() && current.sessionId != sessionId) {
            _activeProject.value = ActiveProjectContext()
        }
    }

    fun resetQuestionDedupe() {
        lastQuestionKey = ""
    }
}
