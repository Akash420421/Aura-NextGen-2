package com.example.gemini.data.live

import com.example.gemini.data.remote.dto.AskQuestionOptionDto
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import java.util.Locale

/**
 * Pure-Kotlin helper for matching spoken answers to active Chat Questionnaire cards
 * (AGY, Claude, OpenCode, Codex) and blocking duplicate-chat loops (e.g. 36-chat storms).
 */
object ChatQuestionnaireHelper {

    data class RecentTaskRecord(
        val sessionId: String,
        val prompt: String,
        val tokens: Set<String>,
        val createdAtMs: Long
    )

    sealed class TaskCreationDecision {
        data object CreateNewTask : TaskCreationDecision()
        data class AnswerPendingQuestion(val rawAnswer: String) : TaskCreationDecision()
        data class ReuseExistingTask(val record: RecentTaskRecord, val reason: String) : TaskCreationDecision()
    }

    data class QuestionAnswerBuildResult(
        val success: Boolean,
        val responses: List<AskQuestionResponseItemDto> = emptyList(),
        val summaryDisplay: String = "",
        val matchedLabels: List<String> = emptyList(),
        val skipped: Boolean = false,
        val errorMessage: String = ""
    )

    private data class SingleMatch(
        val selected: List<ChoiceOption>,
        val writeIn: String?
    )

    private val NON_ALNUM = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private val TASK_STOP_WORDS = setOf(
        "create", "make", "build", "start", "open", "new", "chat", "task", "banao", "banado",
        "karo", "kardo", "kholo", "wala", "wali", "wale", "select", "choose", "click", "tap",
        "submit", "dabao", "please", "pls", "the", "a", "an", "to", "for", "in", "on", "of",
        "and", "aur", "me", "mein", "pe", "par", "ko", "ki", "ka", "ke", "is", "this", "that"
    )

    private val ACTION_FILLERS = setOf(
        "click", "tap", "select", "choose", "pick", "press", "submit", "confirm", "send",
        "karo", "kardo", "kar", "dabao", "daba", "chuno", "wala", "wali", "wale",
        "pe", "par", "ko", "mein", "me", "the", "a", "an", "option", "please", "pls", "button"
    )

    private val ORDINAL_INDEX_WORDS = mapOf(
        "1" to 0, "first" to 0, "1st" to 0, "pahla" to 0, "pehla" to 0, "pehle" to 0, "ek" to 0,
        "2" to 1, "second" to 1, "2nd" to 1, "dusra" to 1, "doosra" to 1, "duja" to 1,
        "3" to 2, "third" to 2, "3rd" to 2, "teesra" to 2, "tisra" to 2,
        "4" to 3, "fourth" to 3, "4th" to 3, "chautha" to 3,
        "5" to 4, "fifth" to 4, "5th" to 4, "panchwa" to 4,
        "6" to 5, "sixth" to 5, "6th" to 5
    )

    private val SKIP_WORDS = Regex(
        """(?i)\b(skip|chhodo|chhod|rehne\s*do|rhne\s*do|never\s*mind|you\s*decide|khud\s*decide|up\s*to\s*you|whatever)\b"""
    )

    fun normalizeText(raw: String): String =
        HindiAsrNormalizer.normalize(raw)
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokenizeTaskIntent(raw: String): Set<String> =
        normalizeText(raw)
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in TASK_STOP_WORDS }
            .toSet()

    private fun matchSingleQuestion(rawAnswer: String, question: ChoiceQuestion): SingleMatch {
        val normalized = normalizeText(rawAnswer)
        val rawTokens = normalized.split(" ").filter { it.isNotBlank() }
        val cleanTokens = rawTokens.filter { it !in ACTION_FILLERS }
        val cleanPhrase = cleanTokens.joinToString(" ").trim()
        val opts = question.options.filter { !it.isCustomInput }

        if (opts.isEmpty()) {
            val write = rawAnswer.trim().takeIf { it.isNotBlank() }
            return SingleMatch(emptyList(), write)
        }

        // 1. Recommended shortcut ("recommended", "suggested", "best")
        if (cleanTokens.any { it in setOf("recommended", "recommend", "suggested", "default", "best") }) {
            val rec = opts.firstOrNull {
                it.label.contains("recommended", ignoreCase = true) ||
                    it.description?.contains("recommended", ignoreCase = true) == true
            } ?: opts.first()
            return SingleMatch(listOf(rec), null)
        }

        val picked = LinkedHashSet<ChoiceOption>()

        // 2. Ordinal / letter indices ("1", "option 2", "first", "pahla")
        for (tok in rawTokens) {
            ORDINAL_INDEX_WORDS[tok]?.let { idx ->
                opts.getOrNull(idx)?.let { picked += it }
            }
        }
        if (cleanTokens.size == 1 && cleanTokens[0].length == 1 && cleanTokens[0][0] in 'a'..'f') {
            val idx = cleanTokens[0][0] - 'a'
            opts.getOrNull(idx)?.let { picked += it }
        }

        // 3. Exact / substring / scored token matching on label + description
        val parts = if (question.isMultiSelect) {
            rawAnswer.split(';', ',', '|').map { normalizeText(it) }.filter { it.isNotBlank() }
        } else {
            listOf(cleanPhrase.ifBlank { normalized })
        }

        if (question.isMultiSelect && ("all" in rawTokens || "sab" in rawTokens || "everything" in rawTokens)) {
            picked.addAll(opts)
        }

        for (part in parts) {
            val partTokens = part.split(" ").filter { it.length >= 2 && it !in ACTION_FILLERS }
            if (partTokens.isEmpty()) continue
            var bestOpt: ChoiceOption? = null
            var bestScore = 0
            for (opt in opts) {
                val labelNorm = normalizeText(opt.label)
                val descNorm = normalizeText(opt.description.orEmpty())
                val combined = "$labelNorm $descNorm".trim()
                val optLabelTokens = labelNorm.split(" ").filter { it.length >= 2 }.toSet()
                val combinedTokens = combined.split(" ").filter { it.length >= 2 }.toSet()

                var score = 0
                if (labelNorm == part) score += 1000
                if (part.length >= 4 && labelNorm.contains(part)) score += 600
                if (part.length >= 4 && descNorm.contains(part)) score += 250

                val labelHits = partTokens.count { pt ->
                    optLabelTokens.any { ot -> ot == pt || (pt.length >= 4 && (ot.contains(pt) || pt.contains(ot))) }
                }
                val combinedHits = partTokens.count { pt ->
                    combinedTokens.any { ct -> ct == pt || (pt.length >= 4 && (ct.contains(pt) || pt.contains(ct))) }
                }
                score += labelHits * 140 + combinedHits * 50

                // Require strong label relevance (avoid matching both "Standard mode" and "Agent mode" solely on "mode")
                val distinctiveHits = partTokens.count { pt ->
                    pt !in setOf("mode", "with", "without", "option", "choice") &&
                        optLabelTokens.any { ot -> ot == pt || (pt.length >= 4 && ot.contains(pt)) }
                }
                if (distinctiveHits > 0) score += distinctiveHits * 200

                if (score > bestScore) {
                    bestScore = score
                    bestOpt = opt
                }
            }
            if (bestOpt != null && bestScore >= 180) {
                picked += bestOpt
            }
        }

        val selected = if (question.isMultiSelect) picked.toList() else picked.take(1)
        if (selected.isNotEmpty()) {
            return SingleMatch(selected, null)
        }

        // Write-in only if allowed AND not just a bare submit word
        val isBareSubmitOnly = cleanTokens.isEmpty()
        val writeIn = if (question.allowCustomAnswer && !isBareSubmitOnly) rawAnswer.trim() else null
        return SingleMatch(emptyList(), writeIn)
    }

    fun buildSubmission(
        rawAnswer: String,
        questionnaire: ChoiceQuestionnaire,
        action: String = "submit"
    ): QuestionAnswerBuildResult {
        val qs = questionnaire.questions
        if (qs.isEmpty()) {
            return QuestionAnswerBuildResult(success = false, errorMessage = "Active questionnaire has no questions.")
        }

        val actLower = action.lowercase(Locale.ROOT).trim()
        if (actLower == "skip" || (actLower == "submit" && SKIP_WORDS.containsMatchIn(rawAnswer))) {
            val skippedItems = qs.map { q ->
                AskQuestionResponseItemDto(
                    question = q.prompt,
                    options = q.options.map { AskQuestionOptionDto(id = it.id, text = it.label, label = it.label, description = it.description.orEmpty()) },
                    isMultiSelect = if (q.isMultiSelect) true else null,
                    skipped = true
                )
            }
            return QuestionAnswerBuildResult(
                success = true,
                responses = skippedItems,
                summaryDisplay = "Skipped by user",
                skipped = true
            )
        }

        val answerParts = if (qs.size > 1) {
            rawAnswer.split(';', '|', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(rawAnswer.trim())
        }

        val responses = mutableListOf<AskQuestionResponseItemDto>()
        val summaryLines = mutableListOf<String>()
        val matchedLabels = mutableListOf<String>()

        for ((idx, q) in qs.withIndex()) {
            val part = answerParts.getOrElse(idx) { answerParts.lastOrNull().orEmpty() }
            val match = matchSingleQuestion(part, q)
            if (match.selected.isEmpty() && match.writeIn.isNullOrBlank()) {
                val optsText = q.options.filter { !it.isCustomInput }
                    .mapIndexed { i, o -> "${i + 1}) ${o.label}" }
                    .joinToString(", ")
                return QuestionAnswerBuildResult(
                    success = false,
                    errorMessage = "Could not match '$part' to options for '${q.prompt}'. Available options: $optsText"
                )
            }
            val labels = match.selected.map { it.label } + listOfNotNull(match.writeIn?.let { "Other: \"$it\"" })
            matchedLabels.addAll(labels)
            summaryLines += "• ${q.prompt}: ${labels.joinToString(", ")}"
            responses += AskQuestionResponseItemDto(
                question = q.prompt,
                options = q.options.map { AskQuestionOptionDto(id = it.id, text = it.label, label = it.label, description = it.description.orEmpty()) },
                selectedOptionIds = match.selected.map { it.id }.ifEmpty { null },
                isMultiSelect = if (q.isMultiSelect) true else null,
                writeInResponse = match.writeIn
            )
        }

        val summary = "[User Clarification & Preferences]:\n${summaryLines.joinToString("\n")}"
        return QuestionAnswerBuildResult(
            success = true,
            responses = responses,
            summaryDisplay = summary,
            matchedLabels = matchedLabels
        )
    }

    fun formatPendingQuestionnairePrompt(questionnaire: ChoiceQuestionnaire): String = buildString {
        appendLine("- Title: \"${questionnaire.title}\"")
        questionnaire.questions.forEachIndexed { idx, q ->
            appendLine("- Question ${idx + 1}: \"${q.prompt}\" (multiSelect=${q.isMultiSelect})")
            q.options.filter { !it.isCustomInput }.forEachIndexed { optIdx, opt ->
                append("  ${optIdx + 1}) \"${opt.label}\"")
                opt.description?.takeIf { it.isNotBlank() }?.let { append(" — ${it.take(80)}") }
                appendLine()
            }
        }
        append("-> To select an option or Submit/Skip/Cancel on this card, ALWAYS call `answer_chat_question(answer=\"...\", action=\"submit\"|\"skip\"|\"cancel\")`! NEVER call `start_new_chat_task`!")
    }

    fun isQuestionnaireClickPhrase(
        targetName: String,
        goal: String,
        questionnaire: ChoiceQuestionnaire?
    ): Boolean {
        if (questionnaire == null) return false
        val combined = normalizeText("$targetName $goal")
        if (combined.isBlank()) return false
        val keywords = listOf("questionnaire", "submit", "skip question", "standard mode", "agent mode", "recommended", "option ")
        if (keywords.any { combined.contains(it) }) return true
        return questionnaire.questions.any { q ->
            q.options.any { opt ->
                val optNorm = normalizeText(opt.label)
                val firstTwo = optNorm.split(" ").filter { it.length >= 3 }.take(2)
                firstTwo.isNotEmpty() && firstTwo.all { combined.contains(it) }
            }
        }
    }

    fun evaluateTaskCreation(
        cleanPrompt: String,
        activeQuestionnaire: ChoiceQuestionnaire?,
        recentTasks: List<RecentTaskRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): TaskCreationDecision {
        val normPrompt = normalizeText(cleanPrompt)

        // 1. If a Questionnaire card is waiting AND prompt looks like answering/clicking it
        if (activeQuestionnaire != null) {
            if (isQuestionnaireClickPhrase(cleanPrompt, cleanPrompt, activeQuestionnaire)) {
                return TaskCreationDecision.AnswerPendingQuestion(cleanPrompt)
            }
            val firstQ = activeQuestionnaire.questions.firstOrNull()
            if (firstQ != null && normPrompt.split(" ").size <= 7) {
                val testMatch = matchSingleQuestion(cleanPrompt, firstQ)
                if (testMatch.selected.isNotEmpty()) {
                    return TaskCreationDecision.AnswerPendingQuestion(cleanPrompt)
                }
            }
        }

        // 2. Duplicate / infinite-loop prevention (prevents 36 duplicate chats!)
        val explicitSeparate = normPrompt.contains("nayi alag") ||
            normPrompt.contains("alag chat") ||
            normPrompt.contains("separate chat") ||
            normPrompt.contains("force new")
        if (explicitSeparate) {
            return TaskCreationDecision.CreateNewTask
        }

        val newTokens = tokenizeTaskIntent(cleanPrompt)
        val activeWindow = recentTasks.filter { (nowMs - it.createdAtMs) in 0L..240_000L }

        for (rec in activeWindow.asReversed()) {
            val ageMs = nowMs - rec.createdAtMs
            val recNorm = normalizeText(rec.prompt)
            if (normPrompt == recNorm && ageMs < 120_000L) {
                return TaskCreationDecision.ReuseExistingTask(rec, "identical_prompt_cooldown")
            }
            if (newTokens.isNotEmpty() && rec.tokens.isNotEmpty()) {
                val intersection = newTokens.count { it in rec.tokens }
                val minSize = minOf(newTokens.size, rec.tokens.size).coerceAtLeast(1)
                val overlap = intersection.toFloat() / minSize.toFloat()
                if (overlap >= 0.62f) {
                    return TaskCreationDecision.ReuseExistingTask(rec, "high_token_overlap_${(overlap * 100).toInt()}%")
                }
            }
        }

        val burstCount = activeWindow.count { (nowMs - it.createdAtMs) < 60_000L }
        if (burstCount >= 3 && activeWindow.isNotEmpty()) {
            return TaskCreationDecision.ReuseExistingTask(activeWindow.last(), "rate_limit_duplicate_guard")
        }

        return TaskCreationDecision.CreateNewTask
    }
}
