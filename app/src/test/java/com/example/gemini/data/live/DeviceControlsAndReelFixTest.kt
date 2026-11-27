package com.example.gemini.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlsAndReelFixTest {

    @Test
    fun `parseBrightnessArgs handles Hinglish synonyms, percentages, auto modes, and invalid args`() {
        val full = DeviceControlHelper.parseBrightnessArgs("full", null)
        assertNull(full.parseError)
        assertEquals("set", full.action)
        assertEquals(100, full.percentage)

        val omittedAction = DeviceControlHelper.parseBrightnessArgs("", "45%")
        assertNull(omittedAction.parseError)
        assertEquals("set", omittedAction.action)
        assertEquals(45, omittedAction.percentage)

        val inc = DeviceControlHelper.parseBrightnessArgs("badhao", null, "25")
        assertEquals("increase", inc.action)
        assertEquals(25, inc.stepPercent)

        val dec = DeviceControlHelper.parseBrightnessArgs("kam", null)
        assertEquals("decrease", dec.action)

        val autoOff = DeviceControlHelper.parseBrightnessArgs("auto", null, null, rawEnabled = "off")
        assertEquals("auto_off", autoOff.action)

        val missing = DeviceControlHelper.parseBrightnessArgs("set", null)
        assertNotNull(missing.parseError)
    }

    @Test
    fun `normalizeRingerMode, normalizeSystemAction, and parseDeviceSettingArgs normalize all controls`() {
        assertEquals("silent", DeviceControlHelper.normalizeRingerMode("chup"))
        assertEquals("vibrate", DeviceControlHelper.normalizeRingerMode("vibration"))
        assertEquals("normal", DeviceControlHelper.normalizeRingerMode("ringtone"))
        assertNull(DeviceControlHelper.normalizeRingerMode("unknown_mode"))

        assertEquals("take_screenshot", DeviceControlHelper.normalizeSystemAction("ss"))
        assertEquals("quick_settings", DeviceControlHelper.normalizeSystemAction("control center"))
        assertEquals("lock_screen", DeviceControlHelper.normalizeSystemAction("screen lock"))

        val wifiOn = DeviceControlHelper.parseDeviceSettingArgs("Wi-Fi", "chalu")
        assertEquals("wifi", wifiOn.canonicalSetting)
        assertEquals(true, wifiOn.enabled)

        val gpsOff = DeviceControlHelper.parseDeviceSettingArgs("GPS", "band")
        assertEquals("location", gpsOff.canonicalSetting)
        assertEquals(false, gpsOff.enabled)

        val rotateOn = DeviceControlHelper.parseDeviceSettingArgs("screen rotation", true)
        assertEquals("auto_rotate", rotateOn.canonicalSetting)
        assertEquals(true, rotateOn.enabled)

        val timeout2m = DeviceControlHelper.parseDeviceSettingArgs("screen timeout", null, "2 min")
        assertEquals("screen_timeout", timeout2m.canonicalSetting)
        assertEquals(120, timeout2m.timeoutSeconds)
        assertEquals(1800, DeviceControlHelper.parseTimeoutSecondsAny("1 hour"))
        assertEquals(15, DeviceControlHelper.parseTimeoutSecondsAny("5s"))
    }

    @Test
    fun `isShortVideoScreen distinguishes Reels and Shorts from Instagram Likes grids and Home feeds`() {
        // Active Instagram Reels viewer -> true
        assertTrue(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.instagram.android",
                viewIds = listOf("com.instagram.android:id/clips_viewer_view_pager"),
                labels = listOf("Follow", "Like", "Comment"),
                descriptions = listOf("Original audio")
            )
        )

        // Instagram Likes management grid -> must remain false!
        assertFalse(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.instagram.android",
                viewIds = listOf("com.instagram.android:id/recycler_view"),
                labels = listOf("Posts you've liked", "Select", "Reel by creator"),
                descriptions = listOf("Video thumbnail")
            )
        )

        // Active YouTube Shorts player -> true
        assertTrue(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.google.android.youtube",
                viewIds = listOf("com.google.android.youtube:id/reel_recycler"),
                labels = listOf("Subscribe", "Remix"),
                descriptions = listOf("Dislike this video", "Shorts remix")
            )
        )

        // Normal YouTube home feed -> false
        assertFalse(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.google.android.youtube",
                viewIds = listOf("com.google.android.youtube:id/results"),
                labels = listOf("Home", "Shorts", "Subscriptions"),
                descriptions = emptyList()
            )
        )

        // TikTok -> true
        assertTrue(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.zhiliaoapp.musically",
                viewIds = emptyList(),
                labels = emptyList(),
                descriptions = emptyList()
            )
        )

        // YouTube Shorts with Hindi/English action stack without home/search tabs -> true
        assertTrue(
            DeviceControlHelper.isShortVideoScreen(
                packageName = "com.google.android.youtube",
                viewIds = listOf("com.google.android.youtube:id/shorts_pivot"),
                labels = listOf("Like", "Dislike", "Comments"),
                descriptions = emptyList()
            )
        )
    }

    @Test
    fun `parseAlarmArgs parses morning evening times, timers, show alarms, and validates missing hour`() {
        val morning = DeviceControlHelper.parseAlarmArgs("set_alarm", rawTimeText = "subah 6:30 baje", rawLabel = "Gym")
        assertNull(morning.parseError)
        assertEquals("set_alarm", morning.action)
        assertEquals(6, morning.hour)
        assertEquals(30, morning.minute)
        assertEquals("Gym", morning.label)

        val evening = DeviceControlHelper.parseAlarmArgs("set_alarm", rawTimeText = "sham 7:15")
        assertNull(evening.parseError)
        assertEquals(19, evening.hour)
        assertEquals(15, evening.minute)

        val timer = DeviceControlHelper.parseAlarmArgs("set_timer", rawDurationSeconds = "10 min", rawLabel = "Tea")
        assertNull(timer.parseError)
        assertEquals("set_timer", timer.action)
        assertEquals(600, timer.timerSeconds)

        val show = DeviceControlHelper.parseAlarmArgs("show")
        assertEquals("show_alarms", show.action)

        val invalid = DeviceControlHelper.parseAlarmArgs("set_alarm")
        assertNotNull(invalid.parseError)
    }

    @Test
    fun `AuraMemoryManager auto-extraction gate, dedupe similarity, and topic key helpers work cleanly`() {
        assertFalse(AuraMemoryManager.shouldRunAiMemoryExtraction("ok"))
        assertFalse(AuraMemoryManager.shouldRunAiMemoryExtraction("volume 50 percent kar do"))
        assertTrue(AuraMemoryManager.shouldRunAiMemoryExtraction("Mera exam next Monday subah 9 baje hai yaad rakhna"))
        assertTrue(AuraMemoryManager.shouldRunAiMemoryExtraction("I prefer dark minimal themes for my websites and apps"))

        assertTrue(
            AuraMemoryManager.isNearDuplicate(
                "User wakes up at 6 AM for gym everyday",
                "user wakes up at 6 AM for gym everyday!"
            )
        )
        assertEquals("favorite_color", AuraMemoryManager.normalizeTopicKey("Favorite Color!"))
    }
}
