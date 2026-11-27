package com.example.gemini.data.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.live.AuraAgentEvent
import com.example.gemini.data.live.AuraAgentEventBus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Android NotificationListenerService powering:
 * 1) Rich notification capture into [AutomationAccessibilityService.recordNotification] (including OTP extraction)
 * 2) Proactive incoming message events for all messaging/SMS/email apps while Live Voice is active
 * 3) #18 WhatsApp Notification Auto-Reply (capturing MessagingStyle text + RemoteInput Reply PendingIntent)
 */
class AuraNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "AuraNotifListener"
        private const val ANNOUNCE_DEDUPE_MS = 20_000L

        @Volatile
        var instance: AuraNotificationListenerService? = null
            private set

        private val announcedFingerprints = ConcurrentHashMap<String, Long>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        WhatsAppAutoReplyManager.setListenerConnected(true)
        Log.i(TAG, "NotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        WhatsAppAutoReplyManager.setListenerConnected(false)
        Log.w(TAG, "NotificationListenerService disconnected")
        super.onListenerDisconnected()
    }

    fun getActiveSnapshot(limit: Int = 10): List<AutomationAccessibilityService.Companion.CapturedNotification> {
        val sbns: Array<StatusBarNotification> = try {
            activeNotifications
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val results = mutableListOf<AutomationAccessibilityService.Companion.CapturedNotification>()
        for (sbn in sbns.sortedByDescending { it.postTime }) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") continue
            val n = sbn.notification ?: continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            val extras = n.extras ?: Bundle.EMPTY
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val body = extractBestNotificationBody(n, extras).trim()
            val combined = listOf(title, body).filter { it.isNotBlank() }.joinToString(": ")
            if (combined.isNotBlank()) {
                results.add(
                    AutomationAccessibilityService.Companion.CapturedNotification(
                        packageName = pkg,
                        text = combined.take(500),
                        timestamp = sbn.postTime
                    )
                )
                if (results.size >= limit) break
            }
        }
        return results
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
                AccessibilityGestureMath.extractOtpCode(combined)?.let { otp ->
                    AutomationAccessibilityService.latestExtractedOtp =
                        AutomationAccessibilityService.Companion.ExtractedOtp(otp, System.currentTimeMillis(), pkg)
                }
            }
        }

        if (isOngoing || isGroupSummary) return

        // Route WhatsApp / WhatsApp Business notifications to #18 Smart Confirm / Auto-Reply when enabled
        JarvisPreferences.init(applicationContext)
        val isWhatsApp = WhatsAppAutoReplyHelper.isWhatsAppPackage(pkg)
        if (isWhatsApp && JarvisPreferences.waAutoReplyEnabled.value) {
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
            return
        }

        // Emit live voice read-aloud event for incoming messages across SMS, Telegram, Instagram, Messenger, Gmail, etc.
        if (shouldAnnounceGenericNotification(pkg, notification, extras, rawTitle, messageBody)) {
            val sender = rawTitle.trim().ifBlank { resolveAppFriendlyName(pkg) }
            val text = messageBody.trim()
            val combined = "$sender: $text"
            val fp = "${pkg.lowercase(Locale.ROOT)}|${sender.lowercase(Locale.ROOT)}|${text.lowercase(Locale.ROOT)}"
            val now = System.currentTimeMillis()
            val prev = announcedFingerprints[fp]
            if (prev != null && now - prev < ANNOUNCE_DEDUPE_MS) return
            announcedFingerprints[fp] = now
            if (announcedFingerprints.size > 60) {
                val cutoff = now - ANNOUNCE_DEDUPE_MS * 2
                val it = announcedFingerprints.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value < cutoff) it.remove()
                }
            }
            val otp = AccessibilityGestureMath.extractOtpCode(combined)
            AuraAgentEventBus.emit(
                AuraAgentEvent.IncomingMessageNotification(
                    notificationKey = sbn.key,
                    appName = resolveAppFriendlyName(pkg),
                    packageName = pkg,
                    senderName = sender,
                    messageText = text.ifBlank { combined }.take(300),
                    otpCode = otp
                )
            )
        }
    }

    private fun shouldAnnounceGenericNotification(
        pkg: String,
        notification: Notification,
        extras: Bundle,
        title: String,
        body: String
    ): Boolean {
        val pkgLower = pkg.lowercase(Locale.ROOT)
        if (pkgLower == "android" || pkgLower == "com.android.systemui" ||
            pkgLower.contains("downloadmanager") || pkgLower.contains("vending")
        ) {
            return false
        }
        val cat = notification.category
        if (cat == Notification.CATEGORY_PROGRESS || cat == Notification.CATEGORY_SERVICE ||
            cat == Notification.CATEGORY_TRANSPORT || cat == Notification.CATEGORY_SYSTEM
        ) {
            return false
        }
        if (title.isBlank() && body.isBlank()) return false
        val bodyLower = body.lowercase(Locale.ROOT)
        if (bodyLower.contains("checking for new messages") ||
            bodyLower.contains("backup in progress") ||
            bodyLower.contains("downloading")
        ) {
            return false
        }

        val isMessagingCategory = cat == Notification.CATEGORY_MESSAGE ||
            cat == Notification.CATEGORY_EMAIL ||
            cat == Notification.CATEGORY_SOCIAL ||
            extras.containsKey(Notification.EXTRA_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
            notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true

        val isKnownMsgPkg = listOf(
            "whatsapp", "messaging", "mms", "sms", "telegram", "instagram", "orca",
            "facebook.mlite", "snapchat", "signal", "viber", "slack", "discord",
            "teams", "gm", "gmail", "outlook", "yahoo", "truecaller", "dialer", "contacts"
        ).any { pkgLower.contains(it) }

        return isMessagingCategory || isKnownMsgPkg
    }

    private fun resolveAppFriendlyName(pkg: String): String {
        val pm = applicationContext.packageManager
        val appName = runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().trim()
        }.getOrNull()
        if (!appName.isNullOrBlank()) return appName
        return pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
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
