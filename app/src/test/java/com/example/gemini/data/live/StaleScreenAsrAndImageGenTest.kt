package com.example.gemini.data.live

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleScreenAsrAndImageGenTest {

    @Test
    fun `HindiAsrNormalizer repairs app names, plugin names, and Hindi ordinal slips cleanly`() {
        assertEquals(
            "Meta Suite ka kya scene hai",
            HindiAsrNormalizer.normalize("melt ka kya scene hai")
        )
        assertEquals(
            "Meta Suite wale ka kya scene hai",
            HindiAsrNormalizer.normalize("melt wale ka kya scene hai")
        )
        assertEquals(
            "Chrome mein Incognito tab kholo",
            HindiAsrNormalizer.normalize("Chrome mein in cognito tab kholo")
        )
        assertEquals(
            "In-Built Browser open karo",
            HindiAsrNormalizer.normalize("in bill browser open karo")
        )
        assertEquals(
            "3 dots par click karo",
            HindiAsrNormalizer.normalize("three doubt par click karo")
        )

        // Exam / MCQ ordinal disambiguation ("15 rupaye ka" -> "15ve ka")
        val examHint = HindiAsrNormalizer.ScreenContextHint(
            foregroundPackage = "com.android.chrome",
            isMcqOrExam = true
        )
        assertEquals(
            "15ve ka answer batao",
            HindiAsrNormalizer.normalize("15 rupaye ka answer batao", examHint)
        )
        assertEquals(
            "15ve batao",
            HindiAsrNormalizer.normalize("15 rupaye batao", examHint)
        )
        assertEquals(
            "14ve ka kya hoga",
            HindiAsrNormalizer.normalize("14 rupaye ka kya hoga", examHint)
        )

        // Genuine ice/money phrases are NOT corrupted
        assertEquals(
            "baraf melt ho rahi hai",
            HindiAsrNormalizer.normalize("baraf melt ho rahi hai")
        )
        assertEquals(
            "15 rupaye ka recharge karo",
            HindiAsrNormalizer.normalize("15 rupaye ka recharge karo", examHint)
        )
    }

    @Test
    fun `HindiAsrNormalizer filters Spanish and subtitle hallucinations without dropping Hindi or Hinglish`() {
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("¡Yo calla!"))
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("¿Pero bien?"))
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("Gracias por ver."))
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("Subtítulos por la comunidad de Amara.org"))
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("спасибо"))
        assertTrue(HindiAsrNormalizer.isNoiseOrForeignHallucination("a"))

        assertFalse(HindiAsrNormalizer.isNoiseOrForeignHallucination("Option B tick karo aur next dabao"))
        assertFalse(HindiAsrNormalizer.isNoiseOrForeignHallucination("मेरे लिए एक बिल्ली की इमेज बनाओ"))
    }

    @Test
    fun `VoiceImageGenerator builds valid Nano Banana payloads and parses camel or snake inline data`() {
        assertEquals("16:9", VoiceImageGenerator.normalizeAspectRatio("16:9"))
        assertEquals("1:1", VoiceImageGenerator.normalizeAspectRatio("invalid_ratio"))

        val payload = VoiceImageGenerator.buildRequestPayloadJson(
            prompt = "A cute orange cat sitting on a wooden desk",
            aspectRatio = "16:9",
            model = "gemini-nano-banana-2.1",
            includeImageConfig = true
        )
        val genCfg = payload["generationConfig"]!!.jsonObject
        val modalities = genCfg["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(modalities.contains("IMAGE"))
        assertTrue(modalities.contains("TEXT"))
        assertEquals("16:9", genCfg["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
        assertEquals("1K", genCfg["imageConfig"]!!.jsonObject["imageSize"]!!.jsonPrimitive.content)
        assertFalse(genCfg.containsKey("temperature"))

        val stripped = VoiceImageGenerator.buildRequestPayloadJson(
            prompt = "A cute cat",
            aspectRatio = "1:1",
            model = "gemini-3.1-flash-image",
            includeImageConfig = false
        )
        assertFalse(stripped["generationConfig"]!!.jsonObject.containsKey("imageConfig"))

        val sampleB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val camelResp = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"text": "Here is your cat!"},
                    {"inlineData": {"mimeType": "image/png", "data": "$sampleB64"}}
                  ]
                }
              }]
            }
        """.trimIndent()
        val parsedCamel = VoiceImageGenerator.extractImagePayloadFromJson(camelResp)
        assertNotNull(parsedCamel)
        assertEquals("image/png", parsedCamel!!.mimeType)
        assertEquals("Here is your cat!", parsedCamel.caption)
        assertEquals(sampleB64, parsedCamel.base64Data)

        val snakeResp = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"inline_data": {"mime_type": "image/jpeg", "data": "$sampleB64"}}
                  ]
                }
              }]
            }
        """.trimIndent()
        val parsedSnake = VoiceImageGenerator.extractImagePayloadFromJson(snakeResp)
        assertNotNull(parsedSnake)
        assertEquals("image/jpeg", parsedSnake!!.mimeType)

        assertNull(VoiceImageGenerator.extractImagePayloadFromJson("""{"candidates":[{"content":{"parts":[{"text":"only text"}]}}]}"""))
    }

    @Test
    fun `ActiveProjectContext gates stale project memory and keeps non-hijack instructions`() {
        val now = System.currentTimeMillis()
        val freshProj = ActiveProjectContext(
            sessionId = "s1",
            projectName = "Flipkart Clone",
            updatedAt = now - 60_000L
        )
        assertTrue(freshProj.isRecentlyActive(maxAgeMs = 20 * 60_000L))
        assertTrue(freshProj.toPromptBlock().contains("IGNORE this project completely"))

        val staleProj = ActiveProjectContext(
            sessionId = "s2",
            projectName = "GTA 6 Thumbnail",
            updatedAt = now - 60 * 60_000L
        )
        assertFalse(staleProj.isRecentlyActive(maxAgeMs = 20 * 60_000L))
    }
}
