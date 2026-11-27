package com.example.gemini.data.vision

import com.example.gemini.data.remote.HubMediaResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McqAndMediaScrollFixTest {

    @Test
    fun `parseMcqOptionLetter parses English and Hindi option selectors accurately`() {
        assertEquals('a', McqGrounder.parseMcqOptionLetter("a"))
        assertEquals('b', McqGrounder.parseMcqOptionLetter("(B)"))
        assertEquals('c', McqGrounder.parseMcqOptionLetter("option c"))
        assertEquals('b', McqGrounder.parseMcqOptionLetter("option 2"))
        assertEquals('b', McqGrounder.parseMcqOptionLetter("b lagao"))
        assertEquals('b', McqGrounder.parseMcqOptionLetter("dusra option"))
        assertEquals('a', McqGrounder.parseMcqOptionLetter("पहला"))
        assertEquals('b', McqGrounder.parseMcqOptionLetter("दूसरा option"))
        assertEquals('c', McqGrounder.parseMcqOptionLetter("teesra"))
        assertEquals('d', McqGrounder.parseMcqOptionLetter("4th answer"))
        assertEquals('e', McqGrounder.parseMcqOptionLetter("option 5"))
    }

    @Test
    fun `parseMcqOptionLetter ignores toolbar icons and unrelated targets`() {
        assertNull(McqGrounder.parseMcqOptionLetter("3 dots"))
        assertNull(McqGrounder.parseMcqOptionLetter("menu"))
        assertNull(McqGrounder.parseMcqOptionLetter("star rating"))
        assertNull(McqGrounder.parseMcqOptionLetter("next page"))
        assertNull(McqGrounder.parseMcqOptionLetter(""))
    }

    @Test
    fun `normalizeUnicode and tokenizeUnicode preserve Hindi Devanagari letters and matras`() {
        val raw = "b. राजनीतिक प्रक्रिया और संविधान"
        val normalized = McqGrounder.normalizeUnicode(raw)
        assertTrue(
            "Expected Devanagari matras to remain intact, got: $normalized",
            normalized.contains("राजनीतिकप्रक्रियाऔरसंविधान")
        )

        val tokens = McqGrounder.tokenizeUnicode(raw)
        assertTrue(tokens.contains("राजनीतिक"))
        assertTrue(tokens.contains("प्रक्रिया"))
        assertTrue(tokens.contains("और"))
        assertTrue(tokens.contains("संविधान"))
    }

    @Test
    fun `navigation and explicit skip guards distinguish normal next from explicit skip`() {
        assertTrue(McqGrounder.isNextNavigationTarget("Next"))
        assertTrue(McqGrounder.isNextNavigationTarget("Save & Next"))
        assertTrue(McqGrounder.isNextNavigationTarget("next question"))
        assertTrue(McqGrounder.isNextNavigationTarget("अगला"))
        assertFalse(McqGrounder.isNextNavigationTarget("Option B"))

        assertTrue(McqGrounder.isPreviousNavigationTarget("Previous"))
        assertTrue(McqGrounder.isPreviousNavigationTarget("pichla question"))

        assertTrue(McqGrounder.isExplicitSkipGoal("skip this question and click next"))
        assertTrue(McqGrounder.isExplicitSkipGoal("bina tick kiye next kar do"))
        assertFalse(McqGrounder.isExplicitSkipGoal("option b select karo aur next dabao"))
    }

    @Test
    fun `HubMediaResolver filters non-image commands and tracks failed and aspect ratio caches`() {
        assertTrue(HubMediaResolver.isRenderableImageSource("https://example.com/photo.png?size=large"))
        assertTrue(HubMediaResolver.isRenderableImageSource("/storage/emulated/0/Pictures/shot.webp"))
        assertTrue(HubMediaResolver.isRenderableImageSource("data:image/jpeg;base64,/9j/4AAQSk"))

        assertFalse(HubMediaResolver.isRenderableImageSource(""))
        assertFalse(HubMediaResolver.isRenderableImageSource("   "))
        assertFalse(HubMediaResolver.isRenderableImageSource("Loading image..."))
        assertFalse(HubMediaResolver.isRenderableImageSource("grep -rn image app/src"))
        assertFalse(HubMediaResolver.isRenderableImageSource("/home/user/project/Main.kt"))

        val fakeFailedUri = "hub://missing/unit-test-image-404.png"
        HubMediaResolver.clearFailed(fakeFailedUri)
        assertFalse(HubMediaResolver.hasFailedRecently(fakeFailedUri))
        HubMediaResolver.markFailed(fakeFailedUri)
        assertTrue(HubMediaResolver.hasFailedRecently(fakeFailedUri))
        HubMediaResolver.clearFailed(fakeFailedUri)
        assertFalse(HubMediaResolver.hasFailedRecently(fakeFailedUri))

        val ratioUri = "https://example.com/aspect-test-16-9.jpg"
        HubMediaResolver.putAspectRatio(ratioUri, 1600, 900)
        val cachedRatio = HubMediaResolver.getCachedAspectRatio(ratioUri)
        assertNotNull(cachedRatio)
        assertEquals(1600f / 900f, cachedRatio!!, 0.001f)

        val invalidUri = "https://example.com/invalid-zero.jpg"
        HubMediaResolver.putAspectRatio(invalidUri, 0, 900)
        assertNull(HubMediaResolver.getCachedAspectRatio(invalidUri))
    }
}
