package com.example.gemini.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuraBrandAliasNormalizer] (#23):
 * Verifies Hindi/Hinglish Aura / Aura Pro ASR alias normalization while strictly preserving
 * normal Hindi "aur" / "और" conjunctions.
 */
class AuraBrandAliasNormalizerTest {

    @Test
    fun normalizesAuraProMishearings() {
        assertEquals("Aura Pro open karo", AuraBrandAliasNormalizer.normalize("aurya pro open karo"))
        assertEquals("Aura Pro open karo", AuraBrandAliasNormalizer.normalize("औरया प्रो open karo"))
        assertEquals("Aura Pro app kholo", AuraBrandAliasNormalizer.normalize("aur pro app kholo"))
        assertEquals("Aura Pro kholo", AuraBrandAliasNormalizer.normalize("ora pro kholo"))
    }

    @Test
    fun normalizesAuraAppContextMishearings() {
        assertEquals("Aura app open karo", AuraBrandAliasNormalizer.normalize("aur app open karo"))
        assertEquals("Aura app kholo", AuraBrandAliasNormalizer.normalize("और ऐप kholo"))
        assertEquals("Aura app mein jao", AuraBrandAliasNormalizer.normalize("aora app mein jao"))
        assertEquals("Aura app kholo", AuraBrandAliasNormalizer.normalize("oura app kholo"))
        assertEquals("Aura app kholo", AuraBrandAliasNormalizer.normalize("aurat app kholo"))
        assertEquals("Aura AI se baat karo", AuraBrandAliasNormalizer.normalize("ora ai se baat karo"))
    }

    @Test
    fun normalizesWakePhraseAndNavigationMishearings() {
        assertEquals("Hey Aura kya haal hai", AuraBrandAliasNormalizer.normalize("hey aur kya haal hai"))
        assertEquals("Aura open karo", AuraBrandAliasNormalizer.normalize("aur open karo"))
        assertEquals("Aura wapas kholo", AuraBrandAliasNormalizer.normalize("aur wapas kholo"))
    }

    @Test
    fun preservesNormalHindiAurConjunctions() {
        val sentences = listOf(
            "mujhe aur paani chahiye",
            "chai aur biscuit lao",
            "aur kya chal raha hai",
            "thoda aur neeche scroll karo",
            "मुझे और जानकारी दो",
            "राम और श्याम अच्छे दोस्त हैं",
            "aur batao aaj ka weather kaisa hai"
        )
        for (sentence in sentences) {
            assertEquals("Normal Hindi 'aur' must stay untouched: $sentence", sentence, AuraBrandAliasNormalizer.normalize(sentence))
        }
    }

    @Test
    fun detectsAuraAppAliasesAccurately() {
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("Aura"))
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("Aura Pro"))
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("aurya pro"))
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("aur app"))
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("और ऐप"))
        assertTrue(AuraBrandAliasNormalizer.isAuraAppAlias("aora"))
        assertFalse(AuraBrandAliasNormalizer.isAuraAppAlias("WhatsApp"))
        assertFalse(AuraBrandAliasNormalizer.isAuraAppAlias("YouTube"))
        assertFalse(AuraBrandAliasNormalizer.isAuraAppAlias("aur"))
    }
}
