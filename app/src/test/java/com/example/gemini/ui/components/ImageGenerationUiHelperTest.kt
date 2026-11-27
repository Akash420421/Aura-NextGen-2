package com.example.gemini.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for pure-Kotlin [ImageGenUiHelper] and [ImageAspectRatio] powering the
 * redesigned Image Generation UI/UX workflow.
 */
class ImageGenerationUiHelperTest {

    @Test
    fun `detects natural English and Hinglish image prompts cleanly`() {
        assertTrue(ImageGenUiHelper.isLikelyImagePrompt("Ek rabbit ka image banao"))
        assertTrue(ImageGenUiHelper.isLikelyImagePrompt("Ek rabbit ka image bnao"))
        assertTrue(ImageGenUiHelper.isLikelyImagePrompt("Generate a cinematic sunset wallpaper"))
        assertTrue(ImageGenUiHelper.isLikelyImagePrompt("Create an image of a cyberpunk city"))
        assertTrue(ImageGenUiHelper.isLikelyImagePrompt("Draw a cute cat logo"))

        assertFalse(ImageGenUiHelper.isLikelyImagePrompt("hi"))
        assertFalse(ImageGenUiHelper.isLikelyImagePrompt("Build a Kotlin repository test"))
        assertFalse(ImageGenUiHelper.isLikelyImagePrompt(""))
    }

    @Test
    fun `aspect ratio tag build extract and strip round trip cleanly`() {
        val tagged = ImageGenUiHelper.buildPromptWithUiRatio(
            rawPrompt = "Futuristic sports car at night",
            ratio = ImageAspectRatio.LANDSCAPE,
            isImageMode = true
        )
        assertTrue(tagged.contains("[Image Aspect Ratio: 16:9]"))
        assertEquals(ImageAspectRatio.LANDSCAPE, ImageGenUiHelper.extractUiRatioTag(tagged))
        assertEquals("Futuristic sports car at night", ImageGenUiHelper.stripUiRatioTag(tagged))

        // Normal chat mode does not alter user prompt
        val plain = ImageGenUiHelper.buildPromptWithUiRatio(
            rawPrompt = "Explain Kotlin coroutines",
            ratio = ImageAspectRatio.LANDSCAPE,
            isImageMode = false
        )
        assertEquals("Explain Kotlin coroutines", plain)
        assertNull(ImageGenUiHelper.extractUiRatioTag(plain))
    }

    @Test
    fun `does not append duplicate ratio tag when user already specified ratio`() {
        val withRatio = ImageGenUiHelper.buildPromptWithUiRatio(
            rawPrompt = "Create a mountain poster in 9:16",
            ratio = ImageAspectRatio.PORTRAIT,
            isImageMode = true
        )
        assertEquals("Create a mountain poster in 9:16", withRatio)
    }

    @Test
    fun `aspect ratio enum lookup and follow up suggestions are complete`() {
        assertEquals(ImageAspectRatio.SQUARE, ImageAspectRatio.fromRatio("1:1"))
        assertEquals(ImageAspectRatio.LANDSCAPE, ImageAspectRatio.fromRatio("16:9"))
        assertEquals(ImageAspectRatio.PORTRAIT, ImageAspectRatio.fromRatio("9:16"))
        assertEquals(ImageAspectRatio.STANDARD, ImageAspectRatio.fromRatio("4:3"))
        assertEquals(ImageAspectRatio.TALL, ImageAspectRatio.fromRatio("3:4"))
        assertNull(ImageAspectRatio.fromRatio("99:1"))

        assertTrue(ImageGenUiHelper.FOLLOW_UP_SUGGESTIONS.contains("Change the background"))
        assertTrue(ImageGenUiHelper.LOADING_PHASES.first().contains("Creating"))
    }
}
