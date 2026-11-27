package com.example.gemini.ui.components

import java.util.Locale

/**
 * Pure-Kotlin helper constants, aspect-ratio definitions, and prompt-tag utilities
 * for the Image Generation UI/UX flow (no Android/Compose framework dependencies).
 */
enum class ImageAspectRatio(
    val ratio: String,
    val label: String,
    val previewAspect: Float
) {
    SQUARE("1:1", "Square", 1f),
    LANDSCAPE("16:9", "Landscape", 16f / 9f),
    PORTRAIT("9:16", "Portrait", 9f / 16f),
    STANDARD("4:3", "Classic", 4f / 3f),
    TALL("3:4", "Portrait+", 3f / 4f);

    companion object {
        fun fromRatio(raw: String?): ImageAspectRatio? {
            val clean = raw?.trim() ?: return null
            return entries.firstOrNull { it.ratio == clean }
        }
    }
}

object ImageGenUiHelper {

    val FOLLOW_UP_SUGGESTIONS: List<String> = listOf(
        "Change the background",
        "Make it more realistic",
        "Add cinematic lighting",
        "Remove the background"
    )

    val LOADING_PHASES: List<String> = listOf(
        "Creating your image",
        "Defining the Core Subject",
        "Developing the Prompt",
        "Refining lighting & details",
        "Rendering artwork"
    )

    private val UI_RATIO_TAG_REGEX = Regex("\\s*\\[Image\\s+Aspect\\s+Ratio:\\s*(\\d+:\\d+)\\]\\s*$", RegexOption.IGNORE_CASE)

    private val IMAGE_REQUEST_KEYWORDS = listOf(
        "generate image", "generate an image", "create image", "create an image",
        "make an image", "draw an image", "draw a ", "generate photo", "create photo",
        "generate picture", "create picture", "generate illustration", "design a logo",
        "ka image", "ki image", "ka photo", "ki photo", "tasveer", "chitra",
        "image banao", "image bnao", "image bana", "photo banao", "photo bnao",
        "pic banao", "pic bnao", "poster banao", "logo banao", "wallpaper banao",
        "illustrate ", "render an image"
    )

    /**
     * Detects whether a user prompt clearly asks to generate/edit an image so the chat UI
     * can display the cohesive image creation placeholder cleanly even outside manual Image Mode.
     */
    fun isLikelyImagePrompt(rawPrompt: String?): Boolean {
        val text = rawPrompt?.trim().orEmpty()
        if (text.isBlank()) return false
        if (extractUiRatioTag(text) != null) return true

        val lower = text.lowercase(Locale.ROOT)
        if (IMAGE_REQUEST_KEYWORDS.any { lower.contains(it) }) return true

        val hasImageNoun = Regex("\\b(image|photo|picture|portrait|wallpaper|logo|poster|illustration|artwork|sketch)\\b")
            .containsMatchIn(lower)
        val hasCreateVerb = Regex("\\b(create|generate|make|draw|paint|design|banao|bnao|banado|bana)\\b")
            .containsMatchIn(lower)
        return hasImageNoun && hasCreateVerb && lower.length <= 240
    }

    /**
     * Formats the submitted prompt with a subtle UI aspect-ratio cue when non-default or explicitly requested,
     * keeping the underlying sendMessage/API path completely unchanged.
     */
    fun buildPromptWithUiRatio(
        rawPrompt: String,
        ratio: ImageAspectRatio,
        isImageMode: Boolean
    ): String {
        val clean = rawPrompt.trim()
        if (clean.isBlank()) return ""
        if (!isImageMode) return clean
        val alreadyHasRatio = Regex("\\b\\d+:\\d+\\b").containsMatchIn(clean)
        return if (alreadyHasRatio) {
            clean
        } else {
            "$clean\n[Image Aspect Ratio: ${ratio.ratio}]"
        }
    }

    fun extractUiRatioTag(rawPrompt: String?): ImageAspectRatio? {
        if (rawPrompt.isNullOrBlank()) return null
        val m = UI_RATIO_TAG_REGEX.find(rawPrompt.trim()) ?: return null
        return ImageAspectRatio.fromRatio(m.groupValues[1])
    }

    fun stripUiRatioTag(rawPrompt: String): String {
        if (rawPrompt.isBlank()) return rawPrompt
        return UI_RATIO_TAG_REGEX.replace(rawPrompt, "").trimEnd()
    }
}
