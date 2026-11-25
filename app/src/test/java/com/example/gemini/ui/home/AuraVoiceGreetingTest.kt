package com.example.gemini.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuraVoiceGreetingTest {

    @Test
    fun `resolveVoiceTimeGreeting returns expected greetings for every hour and never Good night`() {
        for (hour in 0..23) {
            val greeting = resolveVoiceTimeGreeting(hour)
            assertNotEquals("Good night", greeting)
            when (hour) {
                in 4..11 -> assertEquals("Good morning", greeting)
                in 12..16 -> assertEquals("Good afternoon", greeting)
                in 17..21 -> assertEquals("Good evening", greeting)
                else -> assertEquals("How are you", greeting)
            }
        }
    }

    @Test
    fun `resolveVoiceDisplayName preserves full name regardless of length`() {
        assertEquals("Arrr", resolveVoiceDisplayName("Arrr"))
        assertEquals("Rahul Sharma", resolveVoiceDisplayName("Rahul Sharma"))
        assertEquals(
            "Mohammad Abdul Rahman Siddiqui",
            resolveVoiceDisplayName("  Mohammad   Abdul Rahman\nSiddiqui  ")
        )
        assertEquals("Sir", resolveVoiceDisplayName(null))
        assertEquals("Sir", resolveVoiceDisplayName("   "))
    }
}
