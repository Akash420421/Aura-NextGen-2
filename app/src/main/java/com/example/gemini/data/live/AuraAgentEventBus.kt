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

    fun toPromptBlock(): String {
        if (!isActive) return ""
        return buildString {
            append("## Active Project Context (Cross-Screen Memory — ALWAYS PRESERVE):\n")
            if (projectName.isNotBlank()) append("- Project Name: $projectName\n")
            if (projectType.isNotBlank()) append("- Project Type: $projectType\n")
            if (theme.isNotBlank()) append("- Theme / Aesthetic Lock: $theme\n")
            if (techStack.isNotBlank()) append("- Tech Stack / Architecture: $techStack\n")
            if (workspaceDir.isNotBlank()) append("- Workspace Directory: $workspaceDir\n")
            if (previewUrl.isNotBlank()) append("- Live Preview URL: $previewUrl\n")
            if (requirementsSummary.isNotBlank()) append("- Requirements Summary: ${requirementsSummary.take(400)}\n")
            append("- Cross-App Reference Rule: If the user opens Pinterest, Dribbble, Chrome, or shares a screenshot while this project is active, treat it as visual/layout/color inspiration for THIS active project (e.g., applying that style to $projectName), NOT as a random unrelated topic.\n")
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

    fun clearActiveProjectIfSession(sessionId: String) {
        if (_activeProject.value.sessionId == sessionId) {
            _activeProject.value = ActiveProjectContext()
        }
    }

    fun resetQuestionDedupe() {
        lastQuestionKey = ""
    }
}
