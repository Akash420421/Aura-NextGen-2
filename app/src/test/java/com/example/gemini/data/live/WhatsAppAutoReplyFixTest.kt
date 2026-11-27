package com.example.gemini.data.live

import com.example.gemini.data.automation.WhatsAppAutoReplyHelper
import com.example.gemini.data.automation.WhatsAppAutoReplyHelper.AutoReplyPolicyConfig
import com.example.gemini.data.automation.WhatsAppAutoReplyHelper.IncomingWhatsAppMessage
import com.example.gemini.data.automation.WhatsAppAutoReplyHelper.ReplyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for #18 WhatsApp Notification Auto-Reply:
 * - Smart Confirm Mode default
 * - Direct Auto-Reply toggle for verified saved 1:1 contacts only
 * - Group chat, unsaved number, OTP/payment/emergency sensitivity guards
 * - Anti-loop cooldown protection
 * - Ignored system/self notifications & contextual Hinglish fallback drafting
 */
class WhatsAppAutoReplyFixTest {

    private fun sampleMessage(
        sender: String = "Rahul Sharma",
        text: String = "Kahan ho bhai?",
        pkg: String = WhatsAppAutoReplyHelper.WHATSAPP_PACKAGE,
        isGroup: Boolean = false,
        groupName: String? = null,
        isSaved: Boolean = true,
        whitelisted: Boolean = false,
        hasRemoteInput: Boolean = true
    ) = IncomingWhatsAppMessage(
        id = "msg-1",
        notificationKey = "0|com.whatsapp|1|null|10123",
        packageName = pkg,
        senderName = sender,
        cleanSenderName = WhatsAppAutoReplyHelper.cleanSenderDisplay(sender),
        messageText = text,
        isGroupChat = isGroup,
        groupName = groupName,
        isSavedContact = isSaved,
        contactDirectWhitelisted = whitelisted,
        hasRemoteInput = hasRemoteInput,
        timestampMs = 1_000_000L
    )

    @Test
    fun `ignores non whatsapp, self echoes, summary notices and ongoing calls`() {
        assertTrue(WhatsAppAutoReplyHelper.isWhatsAppPackage("com.whatsapp"))
        assertTrue(WhatsAppAutoReplyHelper.isWhatsAppPackage("com.whatsapp.w4b"))
        assertFalse(WhatsAppAutoReplyHelper.isWhatsAppPackage("com.instagram.android"))

        assertTrue(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("Rahul", "You: Ok", "com.whatsapp"))
        assertTrue(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("WhatsApp", "Checking for new messages", "com.whatsapp"))
        assertTrue(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("Rahul", "5 messages from 2 chats", "com.whatsapp"))
        assertTrue(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("Rahul", "Ongoing voice call", "com.whatsapp"))
        assertTrue(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("Rahul", "Hi", "com.whatsapp", isGroupSummary = true))

        assertFalse(WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent("Rahul", "Bhai free ho kya?", "com.whatsapp"))
    }

    @Test
    fun `smart confirm mode is default even for saved contacts`() {
        val defaultConfig = AutoReplyPolicyConfig()
        assertTrue(defaultConfig.enabled)
        assertFalse(defaultConfig.directAutoReplyForSavedContacts)

        val decision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(isSaved = true, hasRemoteInput = true),
            config = defaultConfig
        )
        assertEquals(ReplyAction.SMART_CONFIRM, decision.action)
        assertFalse(decision.isSensitive)
    }

    @Test
    fun `direct auto reply triggers when enabled for saved 1to1 contact or whitelisted contact`() {
        val directConfig = AutoReplyPolicyConfig(
            enabled = true,
            directAutoReplyForSavedContacts = true
        )
        val directDecision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(isSaved = true, isGroup = false, hasRemoteInput = true),
            config = directConfig
        )
        assertEquals(ReplyAction.DIRECT_AUTO_REPLY, directDecision.action)

        // Per-contact VIP whitelist works even when global directSavedContacts is false
        val vipDecision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(isSaved = true, whitelisted = true, hasRemoteInput = true),
            config = AutoReplyPolicyConfig(enabled = true, directAutoReplyForSavedContacts = false)
        )
        assertEquals(ReplyAction.DIRECT_AUTO_REPLY, vipDecision.action)
    }

    @Test
    fun `group chats, unsaved callers, and phone numbers always stay in smart confirm`() {
        val directConfig = AutoReplyPolicyConfig(
            enabled = true,
            directAutoReplyForSavedContacts = true
        )

        val groupDecision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(isSaved = true, isGroup = true, groupName = "Office Team"),
            config = directConfig
        )
        assertEquals(ReplyAction.SMART_CONFIRM, groupDecision.action)

        val unsavedDecision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(sender = "Unknown Person", isSaved = false),
            config = directConfig
        )
        assertEquals(ReplyAction.SMART_CONFIRM, unsavedDecision.action)

        val phoneNumDecision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(sender = "+91 98765 43210", isSaved = true),
            config = directConfig
        )
        assertEquals(ReplyAction.SMART_CONFIRM, phoneNumDecision.action)
    }

    @Test
    fun `otp payment upi and emergency content forces smart confirm even in direct mode`() {
        val directConfig = AutoReplyPolicyConfig(
            enabled = true,
            directAutoReplyForSavedContacts = true
        )

        listOf(
            "Bhai OTP aaya hoga 481209 bhej de",
            "Urgent Rs. 2500 UPI bhej do GPay par",
            "Password reset link aaya hai?",
            "Hospital mein emergency hai jaldi aao"
        ).forEach { riskyText ->
            val d = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
                message = sampleMessage(text = riskyText, isSaved = true, whitelisted = true),
                config = directConfig
            )
            assertEquals("Expected SMART_CONFIRM for '$riskyText'", ReplyAction.SMART_CONFIRM, d.action)
            assertTrue("Expected isSensitive=true for '$riskyText'", d.isSensitive)
        }
    }

    @Test
    fun `anti loop cooldown suppresses rapid repeated direct replies`() {
        val directConfig = AutoReplyPolicyConfig(
            enabled = true,
            directAutoReplyForSavedContacts = true,
            cooldownMs = 120_000L
        )
        val firstRepliedAt = 1_000_000L

        val insideCooldown = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(text = "Hello again", isSaved = true),
            config = directConfig,
            lastAutoRepliedAtMs = firstRepliedAt,
            nowMs = firstRepliedAt + 30_000L
        )
        assertEquals(ReplyAction.IGNORE, insideCooldown.action)

        val afterCooldown = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = sampleMessage(text = "Hello again", isSaved = true),
            config = directConfig,
            lastAutoRepliedAtMs = firstRepliedAt,
            nowMs = firstRepliedAt + 125_000L
        )
        assertEquals(ReplyAction.DIRECT_AUTO_REPLY, afterCooldown.action)
    }

    @Test
    fun `group metadata parsing contextual fallback draft and sender matching work`() {
        val (isGrp, grpName, individual) = WhatsAppAutoReplyHelper.parseGroupMetadata(
            rawTitle = "Rahul @ Family Group (2 messages)",
            conversationTitle = "Family Group",
            isGroupConversationExtra = true
        )
        assertTrue(isGrp)
        assertEquals("Family Group", grpName)
        assertEquals("Rahul", individual)

        val draft1 = WhatsAppAutoReplyHelper.generateContextualFallbackDraft("Rahul", "Kaise ho bhai?")
        assertTrue(draft1.contains("badhiya", ignoreCase = true))

        val draft2 = WhatsAppAutoReplyHelper.generateContextualFallbackDraft("Rahul", "Random update")
        assertEquals(WhatsAppAutoReplyHelper.DEFAULT_FALLBACK_TEMPLATE, draft2)

        val score = WhatsAppAutoReplyHelper.matchPendingSenderScore(
            query = "Rahul",
            candidateSender = "Rahul (3 messages)",
            cleanSender = "Rahul"
        )
        assertTrue(score >= 85)
    }
}
