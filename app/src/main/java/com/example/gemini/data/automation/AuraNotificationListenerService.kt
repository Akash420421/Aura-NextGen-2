package com.example.gemini.data.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Real Android NotificationListenerService powering:
 * 1) Rich notification capture into [AutomationAccessibilityService.recordNotification] (including OTP extraction)
 * 2) #18 WhatsApp Notification Auto-Reply (capturing MessagingStyle text + RemoteInput Reply PendingIntent)
 */
class AuraNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "AuraNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        WhatsAppAutoReplyManager.setListenerConnected(true)
        Log.i(TAG, "NotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        WhatsAppAutoReplyManager.setListenerConnected(false)
        Log.w(TAG, "NotificationListenerService disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: Bundle.EMPTY

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroupConv = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        val messageBody = extractBestNotificationBody(notification, extras)

        // Feed all non-empty notifications into shared ring buffer so read_notifications works anywhere
        if (!isGroupSummary && (rawTitle.isNotBlank() || messageBody.isNotBlank())) {
            val combined = listOf(rawTitle.trim(), messageBody.trim())
                .filter { it.isNotBlank() }
                .joinToString(": ")
            if (combined.isNotBlank()) {
                AutomationAccessibilityService.recordNotification(pkg, combined)
            }
        }

        // Route WhatsApp / WhatsApp Business notifications to #18 Smart Confirm / Auto-Reply
        if (!WhatsAppAutoReplyHelper.isWhatsAppPackage(pkg)) return
        if (isOngoing || isGroupSummary) return

        val replyHandle = extractReplyActionHandle(sbn.key, notification)
        WhatsAppAutoReplyManager.onWhatsAppNotificationPosted(
            context = applicationContext,
            notificationKey = sbn.key,
            packageName = pkg,
            rawTitle = rawTitle,
            rawText = messageBody,
            conversationTitle = conversationTitle,
            isGroupConversationExtra = isGroupConv,
            isGroupSummary = isGroupSummary,
            replyHandle = replyHandle
        )
    }

    private fun extractBestNotificationBody(notification: Notification, extras: Bundle): String {
        // 1. Prefer MessagingStyle EXTRA_MESSAGES to avoid "2 new messages" collapsed text
        @Suppress("DEPRECATION")
        val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgBundles.isNullOrEmpty()) {
            for (idx in msgBundles.indices.reversed()) {
                val bundle = msgBundles[idx] as? Bundle ?: continue
                val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isBlank()) continue

                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    (bundle.getParcelable("sender_person") as? Person)?.name?.toString()?.trim()
                        ?: bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
                } else {
                    bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
                }

                // Skip own echoed bubble in MessagingStyle
                if (sender.equals("You", ignoreCase = true) || sender.equals("Me", ignoreCase = true)) {
                    continue
                }
                return text
            }
        }

        // 2. Try BIG_TEXT, then standard TEXT
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val stdText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        // 3. Fallback to InboxStyle TEXT_LINES if standard text is a summary like "2 new messages"
        if (stdText.isNullOrBlank() || stdText.contains("new messages", ignoreCase = true)) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            val lastLine = lines?.lastOrNull()?.toString()?.trim()
            if (!lastLine.isNullOrBlank()) {
                return if (lastLine.contains(": ")) lastLine.substringAfter(": ").trim() else lastLine
            }
        }

        return bigText?.takeIf { it.isNotBlank() } ?: stdText.orEmpty()
    }

    private fun extractReplyActionHandle(
        notificationKey: String,
        notification: Notification
    ): WhatsAppAutoReplyManager.ReplyActionHandle? {
        val candidates = mutableListOf<Notification.Action>()
        notification.actions?.let { candidates.addAll(it) }
        Notification.WearableExtender(notification).actions?.let { candidates.addAll(it) }

        val replyActions = candidates.filter { action ->
            val ri = action.remoteInputs
            val pi: PendingIntent? = action.actionIntent
            ri != null && ri.isNotEmpty() && pi != null
        }
        if (replyActions.isEmpty()) return null

        val preferred = replyActions.firstOrNull { action ->
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) ||
                action.title?.toString()?.contains("reply", ignoreCase = true) == true
        } ?: replyActions.first()

        val remoteInputs = preferred.remoteInputs ?: return null
        val pendingIntent = preferred.actionIntent ?: return null
        return WhatsAppAutoReplyManager.ReplyActionHandle(
            notificationKey = notificationKey,
            pendingIntent = pendingIntent,
            remoteInputs = remoteInputs
        )
    }
}
