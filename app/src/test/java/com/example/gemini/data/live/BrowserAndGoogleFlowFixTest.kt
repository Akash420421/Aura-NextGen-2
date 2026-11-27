package com.example.gemini.data.live

import com.example.gemini.data.automation.DomainNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAndGoogleFlowFixTest {

    private val sampleTabs = listOf(
        BrowserTargetHelper.TabCandidate(
            id = "tab-google-111",
            title = "Google",
            url = "https://www.google.com/",
            isActive = false,
            index = 0
        ),
        BrowserTargetHelper.TabCandidate(
            id = "tab-amazon-222",
            title = "Amazon.in : gaming pc powerful cheap",
            url = "https://www.amazon.in/s?k=gaming+pc",
            isActive = true,
            index = 1
        ),
        BrowserTargetHelper.TabCandidate(
            id = "tab-ad-333",
            title = "Sponsored Deal Offer",
            url = "https://promo.example-ad.com/offer",
            isActive = false,
            index = 2
        )
    )

    @Test
    fun `resolveTargetTab chooses exact tab by name position or id and refuses wrong fallback`() {
        val bySite = BrowserTargetHelper.resolveTargetTab("amazon wala tab band karo", sampleTabs, "tab-google-111")
        assertEquals("tab-amazon-222", bySite.tab?.id)

        val byAd = BrowserTargetHelper.resolveTargetTab("sponsored deal tab", sampleTabs, "tab-amazon-222")
        assertEquals("tab-ad-333", byAd.tab?.id)

        val byPosition = BrowserTargetHelper.resolveTargetTab("pahla tab", sampleTabs, "tab-amazon-222")
        assertEquals("tab-google-111", byPosition.tab?.id)

        val byPrev = BrowserTargetHelper.resolveTargetTab("pichhla tab", sampleTabs, "tab-amazon-222")
        assertEquals("tab-google-111", byPrev.tab?.id)

        val missing = BrowserTargetHelper.resolveTargetTab("youtube tab", sampleTabs, "tab-amazon-222")
        assertNull(missing.tab)
        assertNotNull(missing.error)
    }

    @Test
    fun `pickBestClickCandidate prefers official organic link over search input and sponsored ads`() {
        val googleFindOut = """
            Best matches for "Google Flow" (best first):
            - ref_1: combobox "google flow" [nav]
            - ref_2: link "Flow AI Studio - Sponsored Ad" (host: example-ad.com) [sponsored]
            - ref_3: link "Google Flow Blog Review" (host: techblog.example)
            - ref_4: link "Flow - Google Labs" (host: labs.google)
        """.trimIndent()

        val chosen = BrowserTargetHelper.pickBestClickCandidate("Google Flow", googleFindOut)
        assertNotNull(chosen)
        assertEquals("ref_4", chosen!!.ref)
    }

    @Test
    fun `pickBestInputCandidate selects editable box rather than search submit button`() {
        val amazonFindOut = """
            Best matches for "search" (best first):
            - ref_5: button "Go" [nav]
            - ref_6: searchbox "Search Amazon.in" [nav]
            - ref_7: link "Search tips" (host: amazon.in)
        """.trimIndent()

        val chosen = BrowserTargetHelper.pickBestInputCandidate("search box", amazonFindOut)
        assertNotNull(chosen)
        assertEquals("ref_6", chosen!!.ref)
    }

    @Test
    fun `evaluateSearchSubmitOutcome detects missed search submission on Amazon homepage`() {
        val stuckOnHome = BrowserTargetHelper.evaluateSearchSubmitOutcome(
            beforeUrl = "https://www.amazon.in/",
            afterUrl = "https://www.amazon.in/",
            beforeSig = "https://www.amazon.in/|PAGE_CONTENT|Everything under 499|Sarees",
            afterSig = "https://www.amazon.in/|PAGE_CONTENT|Everything under 499|Sarees",
            submitDomResultOk = false
        )
        assertFalse(stuckOnHome.verified)

        val navigatedSearch = BrowserTargetHelper.evaluateSearchSubmitOutcome(
            beforeUrl = "https://www.amazon.in/",
            afterUrl = "https://www.amazon.in/s?k=gaming+pc+powerful+cheap",
            beforeSig = "https://www.amazon.in/|PAGE_CONTENT|Everything under 499",
            afterSig = "https://www.amazon.in/s?k=gaming+pc|SEARCH_RESULTS|Results",
            submitDomResultOk = true
        )
        assertTrue(navigatedSearch.verified)
        assertTrue(navigatedSearch.navigated)
    }

    @Test
    fun `getCloseBrowserGuardReason blocks accidental ASR slips and single tab confusion`() {
        val noiseOne = BrowserTargetHelper.getCloseBrowserGuardReason("one", 1200L, openTabsCount = 1)
        assertNotNull(noiseOne)

        val noiseOpen = BrowserTargetHelper.getCloseBrowserGuardReason("open dikhao", 2000L, openTabsCount = 2)
        assertNotNull(noiseOpen)

        val singleTabClose = BrowserTargetHelper.getCloseBrowserGuardReason("ye ad tab band karo", 1000L, openTabsCount = 3)
        assertNotNull(singleTabClose)

        val explicitClose = BrowserTargetHelper.getCloseBrowserGuardReason("browser band karo", 800L, openTabsCount = 3)
        assertNull(explicitClose)
    }

    @Test
    fun `DomainNormalizer routes Google Flow shortcuts directly to official Labs URL`() {
        assertEquals("https://labs.google/fx/tools/flow", DomainNormalizer.normalizeToUrl("google flow"))
        assertEquals("https://labs.google/fx/tools/flow", DomainNormalizer.normalizeToUrl("open google flow"))
        assertEquals("https://labs.google/fx/tools/flow", DomainNormalizer.normalizeToUrl("google flow kholo"))
        val chartUrl = DomainNormalizer.normalizeToUrl("flow chart tutorial")
        assertTrue(chartUrl.startsWith("https://www.google.com/search?q="))
    }
}
