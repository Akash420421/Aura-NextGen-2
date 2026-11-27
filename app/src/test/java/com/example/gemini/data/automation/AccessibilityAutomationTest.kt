package com.example.gemini.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AccessibilityAutomationTest {

    @Test
    fun clampPointKeepsCoordinatesWithinDisplayMargin() {
        val clampedNeg = AccessibilityGestureMath.clampPoint(-50f, -10f, 1080, 2400)
        assertEquals(4f, clampedNeg.x, 0.001f)
        assertEquals(4f, clampedNeg.y, 0.001f)

        val clampedMax = AccessibilityGestureMath.clampPoint(1200f, 2600f, 1080, 2400)
        assertEquals(1076f, clampedMax.x, 0.001f)
        assertEquals(2396f, clampedMax.y, 0.001f)
    }

    @Test
    fun pinchZoomInSpreadsFingersApartAndZoomOutBringsThemTogether() {
        val zoomIn = AccessibilityGestureMath.computePinchVectors(
            centerX = 540f,
            centerY = 1200f,
            screenWidth = 1080,
            screenHeight = 2400,
            zoomIn = true,
            scale = 0.5f
        )
        val startDistIn = hypot(
            (zoomIn.finger2Start.x - zoomIn.finger1Start.x).toDouble(),
            (zoomIn.finger2Start.y - zoomIn.finger1Start.y).toDouble()
        )
        val endDistIn = hypot(
            (zoomIn.finger2End.x - zoomIn.finger1End.x).toDouble(),
            (zoomIn.finger2End.y - zoomIn.finger1End.y).toDouble()
        )
        assertTrue("Zoom-in should spread fingers apart", endDistIn > startDistIn + 100.0)

        val zoomOut = AccessibilityGestureMath.computePinchVectors(
            centerX = 540f,
            centerY = 1200f,
            screenWidth = 1080,
            screenHeight = 2400,
            zoomIn = false,
            scale = 0.5f
        )
        val startDistOut = hypot(
            (zoomOut.finger2Start.x - zoomOut.finger1Start.x).toDouble(),
            (zoomOut.finger2Start.y - zoomOut.finger1Start.y).toDouble()
        )
        val endDistOut = hypot(
            (zoomOut.finger2End.x - zoomOut.finger1End.x).toDouble(),
            (zoomOut.finger2End.y - zoomOut.finger1End.y).toDouble()
        )
        assertTrue("Zoom-out should pinch fingers closer together", startDistOut > endDistOut + 100.0)
    }

    @Test
    fun pinchNearEdgeStaysClampedWithinScreenBounds() {
        val vec = AccessibilityGestureMath.computePinchVectors(
            centerX = 10f,
            centerY = 10f,
            screenWidth = 1080,
            screenHeight = 2400,
            zoomIn = true,
            scale = 0.8f
        )
        for (pt in listOf(vec.finger1Start, vec.finger1End, vec.finger2Start, vec.finger2End)) {
            assertTrue(pt.x in 4f..1076f)
            assertTrue(pt.y in 4f..2396f)
        }
    }

    @Test
    fun extractOtpCodeMatchesVerificationMessagesAndIgnoresUnrelatedNumbers() {
        assertEquals("482910", AccessibilityGestureMath.extractOtpCode("Your verification code is 482910. Valid for 5 minutes."))
        assertEquals("7391", AccessibilityGestureMath.extractOtpCode("OTP: 7391 for login"))
        assertNull(AccessibilityGestureMath.extractOtpCode("Meeting starts at 1430 in room 2045"))
    }

    @Test
    fun organizeReadableBlocksOrdersDeduplicatesAndMasksPasswords() {
        val raw = listOf(
            AccessibilityGestureMath.RawTextBlock(text = "Continue", top = 1200, left = 400),
            AccessibilityGestureMath.RawTextBlock(text = "Sign In", top = 120, left = 40, isHeading = true),
            AccessibilityGestureMath.RawTextBlock(text = "secret123", top = 500, left = 40, isPassword = true, isEditable = true),
            AccessibilityGestureMath.RawTextBlock(text = "user@example.com", top = 360, left = 40, isEditable = true),
            AccessibilityGestureMath.RawTextBlock(text = "continue", top = 1210, left = 410),
            AccessibilityGestureMath.RawTextBlock(text = "Help", top = 125, left = 900)
        )

        val organized = AccessibilityGestureMath.organizeReadableBlocks(raw)
        assertEquals(
            listOf(
                "# Sign In",
                "Help",
                "[input] user@example.com",
                "[input] ••••",
                "Continue"
            ),
            organized
        )
    }

    @Test
    fun notificationBufferCapsSizeAndReturnsNewestFirst() {
        for (i in 1..25) {
            AutomationAccessibilityService.recordNotification("com.example.msg", "Message $i")
        }
        val recent = AutomationAccessibilityService.getRecentNotifications(limit = 5, maxAgeMs = 60_000L)
        assertEquals(5, recent.size)
        assertEquals("Message 25", recent[0].text)
        assertEquals("Message 21", recent[4].text)
    }

    @Test
    fun twoStageScrollAndReelMotionStayInsideSafeScreenBounds() {
        val (scrollPlan, scrollDur) = AccessibilityGestureMath.computeSafeScrollPoints("down", "medium", 1080, 2400)
        assertTrue(scrollDur in 200L..400L)
        assertTrue(scrollPlan.start.y > scrollPlan.engageEnd.y)
        assertTrue(scrollPlan.engageEnd.y > scrollPlan.flingEnd.y)
        assertTrue(scrollPlan.flingEnd.y >= 100f && scrollPlan.start.y <= 2280f)

        val reelUp = AccessibilityGestureMath.computeReelSwipeMotion(upward = true, attempt = 1, screenWidth = 1080, screenHeight = 2400)
        assertTrue("Reel X should avoid right-side buttons", reelUp.start.x < 540f)
        assertTrue(reelUp.start.y > reelUp.engageEnd.y)
        assertTrue(reelUp.engageEnd.y > reelUp.flingEnd.y)
        assertTrue(reelUp.flingEnd.y >= 120f && reelUp.start.y <= 2270f)
    }
}
