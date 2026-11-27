package com.example.gemini.data.live

import com.example.gemini.data.automation.YouTubeSearchHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeIncognitoAndYouTubeFixTest {

    @Test
    fun `HindiAsrNormalizer repairs Chrome 3-dot and Incognito Hinglish ASR slips`() {
        assertEquals(
            "Chrome mein 3 dots pe click karo",
            HindiAsrNormalizer.normalize("Chrome mein teen dot pe click karo")
        )
        assertEquals(
            "3 dots kholo",
            HindiAsrNormalizer.normalize("3 doubt kholo")
        )
        assertEquals(
            "Incognito tab kholo",
            HindiAsrNormalizer.normalize("inkinido tab kholo")
        )
        assertEquals(
            "Incognito mode on karo",
            HindiAsrNormalizer.normalize("cognition mode on karo")
        )
    }

    @Test
    fun `YouTubeSearchHelper cleans Hinglish queries and skips Shorts or Ads when picking top video`() {
        assertEquals("Tum Hi Ho", YouTubeSearchHelper.cleanQuery("YouTube pe Tum Hi Ho song laga do"))
        assertEquals("Arijit Singh", YouTubeSearchHelper.cleanQuery("play Arijit Singh ka gaana"))

        // 1. Legacy videoRenderer with Shorts & Sponsored Ad before real song
        val legacyHtml = """
            {"adSlotRenderer":{"videoRenderer":{"videoId":"ADVIDEO1234","title":{"runs":[{"text":"Sponsored Buy App"}]},"thumbnail":{}}}}
            {"videoRenderer":{"videoId":"SHORTVID123","thumbnail":{},"title":{"runs":[{"text":"Tum Hi Ho #shorts"}]}}}
            {"videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail":{"thumbnails":[]},"title":{"runs":[{"text":"Tum Hi Ho Aashiqui 2 Official Song"}]}}}
        """.trimIndent()
        val picked = YouTubeSearchHelper.parseBestVideoFromHtml(legacyHtml, "Tum Hi Ho")
        assertNotNull(picked)
        assertEquals("dQw4w9WgXcQ", picked!!.videoId)
        assertTrue(picked.title.contains("Tum Hi Ho"))

        // 2. Modern lockupViewModel 2025/2026 YouTube HTML layout
        val lockupHtml = """
            {"lockupViewModel":{"contentId":"PLAYLIST123","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","metadata":{"lockupMetadataViewModel":{"title":{"content":"All Mix Playlist"}}}}}
            {"lockupViewModel":{"contentId":"ABC123xyz89","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Kesariya Official Song | Brahmastra"}}}}}
        """.trimIndent()
        val pickedLockup = YouTubeSearchHelper.parseBestVideoFromHtml(lockupHtml, "Kesariya")
        assertNotNull(pickedLockup)
        assertEquals("ABC123xyz89", pickedLockup!!.videoId)
    }

    @Test
    fun `isPlayableYouTubeVideoCard selects real videos over filter chips, Sponsored ads, and Shorts`() {
        // Real playable Litho video card
        assertTrue(
            YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                viewId = "",
                text = "",
                contentDescription = "Tum Hi Ho Aashiqui 2 Full Video - 500M views - 4 minutes, 22 seconds - play video",
                topPx = 420,
                heightPx = 680,
                screenHeightPx = 2400
            )
        )

        // Filter chip / tiny header must be rejected
        assertFalse(
            YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                viewId = "com.google.android.youtube:id/chip",
                text = "Music",
                contentDescription = "Music",
                topPx = 310,
                heightPx = 84,
                screenHeightPx = 2400
            )
        )

        // Sponsored ad card must be rejected
        assertFalse(
            YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                viewId = "",
                text = "Sponsored",
                contentDescription = "Ad · Install App",
                topPx = 390,
                heightPx = 560,
                screenHeightPx = 2400
            )
        )

        // Shorts shelf must be rejected when looking for song video
        assertFalse(
            YouTubeSearchHelper.isPlayableYouTubeVideoCard(
                viewId = "com.google.android.youtube:id/shorts_shelf",
                text = "Shorts",
                contentDescription = "Shorts viral clip",
                topPx = 450,
                heightPx = 500,
                screenHeightPx = 2400
            )
        )
    }

    @Test
    fun `isSkippableAdButton rejects countdown timers and Skip navigation while matching real Skip Ad buttons`() {
        // Countdown badge ("5" or "Skip in 3s") even inside skip_ad_button_text MUST be rejected!
        assertFalse(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "com.google.android.youtube:id/skip_ad_button_text",
                text = "5",
                contentDescription = "",
                isVisible = true,
                isEnabled = true,
                widthPx = 120,
                heightPx = 64
            )
        )
        assertFalse(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "com.google.android.youtube:id/modern_skip_ad_text",
                text = "Skip in 3s",
                contentDescription = "",
                isVisible = true,
                isEnabled = true,
                widthPx = 160,
                heightPx = 64
            )
        )

        // Chrome / WebView "Skip navigation" accessibility header MUST never be clicked
        assertFalse(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "",
                text = "Skip navigation",
                contentDescription = "Skip navigation",
                isVisible = true,
                isEnabled = true,
                widthPx = 180,
                heightPx = 60
            )
        )

        // Zero-size placeholder must be rejected
        assertFalse(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "com.google.android.youtube:id/skip_ad_button",
                text = "Skip Ad",
                contentDescription = "Skip Ad",
                isVisible = true,
                isEnabled = true,
                widthPx = 0,
                heightPx = 0
            )
        )

        // Real clickable English & Hindi Skip Ad buttons MUST match
        assertTrue(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "com.google.android.youtube:id/skip_ad_button_text",
                text = "Skip",
                contentDescription = "",
                isVisible = true,
                isEnabled = true,
                widthPx = 140,
                heightPx = 72
            )
        )
        assertTrue(
            YouTubeSearchHelper.isSkippableAdButton(
                viewId = "",
                text = "विज्ञापन छोड़ें",
                contentDescription = "",
                isVisible = true,
                isEnabled = true,
                widthPx = 180,
                heightPx = 72
            )
        )
    }
}
