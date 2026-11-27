package com.example.gemini.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVolumeAndBatteryFixTest {

    @Test
    fun `parseVolumeArgs handles Hinglish synonyms, string percentages, and omitted actions`() {
        val fullCmd = DeviceControlHelper.parseVolumeArgs("full", null, null)
        assertNull(fullCmd.parseError)
        assertEquals("set", fullCmd.action)
        assertEquals(100, fullCmd.percentage)
        assertEquals("music", fullCmd.stream)

        val pctStringCmd = DeviceControlHelper.parseVolumeArgs("", "50%", "media")
        assertNull(pctStringCmd.parseError)
        assertEquals("set", pctStringCmd.action)
        assertEquals(50, pctStringCmd.percentage)
        assertEquals("music", pctStringCmd.stream)

        val hindiDown = DeviceControlHelper.parseVolumeArgs("kam", null, "ringtone", "25%")
        assertNull(hindiDown.parseError)
        assertEquals("decrease", hindiDown.action)
        assertEquals("ring", hindiDown.stream)
        assertEquals(25, hindiDown.stepPercent)

        val hindiUp = DeviceControlHelper.parseVolumeArgs("zyada", null, "voice_call")
        assertNull(hindiUp.parseError)
        assertEquals("increase", hindiUp.action)
        assertEquals("call", hindiUp.stream)

        val halfWord = DeviceControlHelper.parseVolumeArgs("set", "aadha", "all")
        assertNull(halfWord.parseError)
        assertEquals("set", halfWord.action)
        assertEquals(50, halfWord.percentage)
        assertEquals("all", halfWord.stream)

        val invalidSet = DeviceControlHelper.parseVolumeArgs("set", null, "music")
        assertNotNull(invalidSet.parseError)

        val unknownAct = DeviceControlHelper.parseVolumeArgs("dance", null, "music")
        assertNotNull(unknownAct.parseError)
    }

    @Test
    fun `evaluateVolumeOutcome verifies exact changes and exposes 67 percent Safe Volume cap`() {
        // Verified exact change to ~50% (8/15 = 53%)
        val ok50 = DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = "media",
            action = "set",
            requestedPercent = 50,
            targetStep = 8,
            beforeStep = 10,
            afterStep = 8,
            maxStep = 15
        )
        assertTrue(ok50.verified)
        assertFalse(ok50.cappedBySystem)
        assertEquals(53, ok50.actualPercent)
        assertTrue(ok50.summary.contains("53%"))

        // Safe Media Volume capped at 10/15 (67%) when 100% (15/15) was requested
        val capped67 = DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = "media",
            action = "set",
            requestedPercent = 100,
            targetStep = 15,
            beforeStep = 10,
            afterStep = 10,
            maxStep = 15
        )
        assertFalse(capped67.verified)
        assertTrue(capped67.cappedBySystem)
        assertEquals(67, capped67.actualPercent)
        assertNotNull(capped67.errorReason)

        // Increase blocked below max
        val incBlocked = DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = "media",
            action = "increase",
            requestedPercent = null,
            targetStep = 12,
            beforeStep = 10,
            afterStep = 10,
            maxStep = 15
        )
        assertFalse(incBlocked.verified)
        assertTrue(incBlocked.cappedBySystem)

        // Fixed volume device
        val fixedVol = DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = "media",
            action = "set",
            requestedPercent = 80,
            targetStep = 12,
            beforeStep = 15,
            afterStep = 15,
            maxStep = 15,
            isFixedVolume = true
        )
        assertFalse(fixedVol.verified)
        assertTrue(fixedVol.cappedBySystem)

        // DND SecurityException
        val dndBlocked = DeviceControlHelper.evaluateVolumeOutcome(
            streamLabel = "ring",
            action = "set",
            requestedPercent = 0,
            targetStep = 0,
            beforeStep = 4,
            afterStep = 4,
            maxStep = 7,
            securityError = "Not allowed to change Do Not Disturb state"
        )
        assertFalse(dndBlocked.verified)
        assertTrue(dndBlocked.cappedBySystem)
    }

    @Test
    fun `computeBatteryPercent and formatHumanDeviceSummaryFromValues return accurate human text`() {
        // Prefers live BatteryManager capacity when valid (0..100)
        assertEquals(84, DeviceControlHelper.computeBatteryPercent(bmCapacity = 84, intentLevel = 83, intentScale = 100))
        // Rounds sticky intent ratio cleanly when kernel capacity is unavailable (-1)
        assertEquals(88, DeviceControlHelper.computeBatteryPercent(bmCapacity = -1, intentLevel = 7, intentScale = 8))
        // Invalid inputs -> -1
        assertEquals(-1, DeviceControlHelper.computeBatteryPercent(bmCapacity = -1, intentLevel = -1, intentScale = 100))

        val summary = DeviceControlHelper.formatHumanDeviceSummaryFromValues(
            batteryPercent = 76,
            batteryStatus = "charging",
            batteryPlugged = "USB",
            temperatureC = 32.4,
            chargeRemainingMin = 42,
            mediaVolumePercent = 53,
            ringVolumePercent = 80,
            alarmVolumePercent = 90,
            callVolumePercent = 71,
            brightnessPercent = 45,
            flashlightOn = false
        )
        assertTrue(summary.contains("Battery: 76% (charging, USB, 32.4°C, 42 min to full)"))
        assertTrue(summary.contains("Volumes: Media 53%, Ring 80%, Alarm 90%, Call 71%"))
        assertTrue(summary.contains("Brightness: 45%"))
        assertTrue(summary.contains("Flashlight: OFF"))
    }
}
