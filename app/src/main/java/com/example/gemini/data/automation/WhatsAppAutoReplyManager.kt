package com.example.gemini.data.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gemini.R
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.live.AuraAgentEvent
import com.example.gemini.data.live.AuraAgentEventBus
import com.example.gemini.data.live.AuraLiveVoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Coordinates #18 WhatsApp Notification Auto-Reply:
 * - Default mode: Smart Confirm Mode (queues message, suggests draft, waits for voice or notification confirm)
 * - Optional mode: Direct Auto-Reply for verified saved contacts in 1:1 chats with 120s anti-loop cooldown
 *   and unconditional sensitive-message protection (OTP/bank/UPI/emergency always hold in Smart Confirm).
 */
object WhatsAppAutoReplyManager {

    private const val TAG = "WhatsAppAutoReplyMgr"
    private const val CHANNEL_ID = "aura_wa_smart_confirm"
    private const val CHANNEL_NAME = "Aura WhatsApp Smart Confirm"
    private const val DEDUPE_WINDOW_MS = 20_000L
    private const val MAX_PENDING = 15
    private const val MAX_LOGS = 40
    private const val BASE_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    data class PendingWhatsAppReply(
        val message: WhatsAppAutoReplyHelper.IncomingWhatsAppMessage,
        val suggestedDraft: String,
        val decisionReason: String,
        val isSensitive: Boolean,
        val arrivedAtMs: Long
    )

    data class ReplyLogEntry(
        val id: String,
        val senderName: String,
        val incomingText: String,
        val replyText: String,
        val mode: String,
        val status: String,
        val timestampMs: Long
    )

    data class ReplyActionHandle(
        val notificationKey: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>
    )

    data class ReplyExecutionResult(
        val success: Boolean,
        val method: String,
        val recipientName: String,
        val sentMessage: String,
        val errorMessage: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val aiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _pendingReplies = MutableStateFlow<List<PendingWhatsAppReply>>(emptyList())
    val pendingReplies: StateFlow<List<PendingWhatsAppReply>> = _pendingReplies.asStateFlow()

    private val _replyLogs = MutableStateFlow<List<ReplyLogEntry>>(emptyList())
    val replyLogs: StateFlow<List<ReplyLogEntry>> = _replyLogs.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val actionHandles = ConcurrentHashMap<String, ReplyActionHandle>()
    private val notifIdMap = ConcurrentHashMap<String, Int>()
    private val lastRepliedBySender = ConcurrentHashMap<String, Long>()
    private val recentFingerprints = ConcurrentHashMap<String, Long>()

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        return enabledListeners.contains(context.packageName)
    }

    fun currentModeLabel(): String = when {
        !JarvisPreferences.waAutoReplyEnabled.value -> "Off"
        JarvisPreferences.waDirectSavedContacts.value -> "Direct Auto-Reply (Saved Contacts)"
        else -> "Smart Confirm Mode"
    }

    fun onWhatsAppNotificationPosted(
        context: Context,
        notificationKey: String,
        packageName: String,
        rawTitle: String,
        rawText: String,
        conversationTitle: String?,
        isGroupConversationExtra: Boolean,
        isGroupSummary: Boolean,
        replyHandle: ReplyActionHandle?
    ) {
        val appCtx = context.applicationContext
        JarvisPreferences.init(appCtx)
        AuraContactsManager.init(appCtx)

        if (!JarvisPreferences.waAutoReplyEnabled.value) return
        if (WhatsAppAutoReplyHelper.shouldIgnoreNotificationContent(rawTitle, rawText, packageName, isGroupSummary)) {
            return
        }

        val cleanText = WhatsAppAutoReplyHelper.sanitizeText(rawText)
        val (parsedGroup, groupName, individualSender) = WhatsAppAutoReplyHelper.parseGroupMetadata(
            rawTitle = rawTitle,
            conversationTitle = conversationTitle,
            isGroupConversationExtra = isGroupConversationExtra
        )
        val cleanSender = WhatsAppAutoReplyHelper.cleanSenderDisplay(individualSender)
            .ifBlank { WhatsAppAutoReplyHelper.cleanSenderDisplay(rawTitle) }
        val senderKey = WhatsAppAutoReplyHelper.normalizeSenderKey(cleanSender)
        if (cleanSender.isBlank() || senderKey.isBlank()) return

        val nowMs = System.currentTimeMillis()
        val fingerprint = "$notificationKey::$senderKey::$cleanText"
        val prevSeen = recentFingerprints[fingerprint]
        if (prevSeen != null && (nowMs - prevSeen) < DEDUPE_WINDOW_MS) {
            return
        }
        recentFingerprints[fingerprint] = nowMs
        pruneOldFingerprints(nowMs)

        // Strict saved-contact resolution (custom Aura contacts first, then exact normalized Android phonebook)
        val customMatch = AuraContactsManager.findAutoReplyContactMatch(cleanSender)
        val phoneBookSaved = if (customMatch == null &&
            !parsedGroup &&
            !WhatsAppAutoReplyHelper.looksLikeUnsavedPhoneNumber(cleanSender) &&
            ContactCaller.hasContactsPermission(appCtx)
        ) {
            runCatching {
                ContactCaller.findMatches(appCtx, cleanSender).any { contact ->
                    WhatsAppAutoReplyHelper.normalizeSenderKey(contact.name) == senderKey
                }
            }.getOrDefault(false)
        } else {
            false
        }

        val isSaved = !parsedGroup && (customMatch != null || phoneBookSaved) &&
            !WhatsAppAutoReplyHelper.looksLikeUnsavedPhoneNumber(cleanSender)

        val messageId = UUID.randomUUID().toString()
        val incoming = WhatsAppAutoReplyHelper.IncomingWhatsAppMessage(
            id = messageId,
            notificationKey = notificationKey,
            packageName = packageName,
            senderName = rawTitle.trim(),
            cleanSenderName = cleanSender,
            messageText = cleanText,
            isGroupChat = parsedGroup,
            groupName = groupName,
            conversationTitle = conversationTitle,
            isSavedContact = isSaved,
            contactDirectWhitelisted = customMatch?.autoReplyEnabled == true,
            hasRemoteInput = replyHandle != null,
            timestampMs = nowMs
        )

        if (replyHandle != null) {
            actionHandles[messageId] = replyHandle
            actionHandles["key::$notificationKey"] = replyHandle
            actionHandles["sender::$senderKey"] = replyHandle
        }

        val config = WhatsAppAutoReplyHelper.AutoReplyPolicyConfig(
            enabled = JarvisPreferences.waAutoReplyEnabled.value,
            directAutoReplyForSavedContacts = JarvisPreferences.waDirectSavedContacts.value,
            cooldownMs = WhatsAppAutoReplyHelper.DEFAULT_COOLDOWN_MS,
            requireRemoteInputForDirect = true
        )

        val lastRepliedAt = lastRepliedBySender[senderKey] ?: 0L
        val decision = WhatsAppAutoReplyHelper.evaluateIncomingMessage(
            message = incoming,
            config = config,
            lastAutoRepliedAtMs = lastRepliedAt,
            nowMs = nowMs,
            isGroupSummary = isGroupSummary
        )

        if (decision.action == WhatsAppAutoReplyHelper.ReplyAction.IGNORE) {
            Log.d(TAG, "Ignored WA msg from '$cleanSender': ${decision.reason}")
            return
        }

        scope.launch {
            val liveActive = AuraLiveVoiceManager.phase.value !in setOf(
                AuraLiveVoiceManager.Phase.IDLE,
                AuraLiveVoiceManager.Phase.CONNECTING
            )
            // Keep live voice notifications snappy (<1s) while preserving full AI drafting offline
            val draft = if (liveActive && decision.action == WhatsAppAutoReplyHelper.ReplyAction.SMART_CONFIRM) {
                kotlinx.coroutines.withTimeoutOrNull(900L) { draftReplyText(incoming) }
                    ?: WhatsAppAutoReplyHelper.buildSmartTemplateReply(incoming, JarvisPreferences.waAutoReplyTemplate.value)
            } else {
                draftReplyText(incoming)
            }
            when (decision.action) {
                WhatsAppAutoReplyHelper.ReplyAction.DIRECT_AUTO_REPLY -> {
                    val sentResult = sendViaHandleOrFallback(appCtx, incoming, draft)
                    if (sentResult.success) {
                        lastRepliedBySender[senderKey] = System.currentTimeMillis()
                        appendLog(
                            senderName = cleanSender,
                            incomingText = cleanText,
                            replyText = sentResult.sentMessage,
                            mode = "Direct Auto-Reply (${sentResult.method})",
                            status = "sent"
                        )
                        AuraAgentEventBus.emit(
                            AuraAgentEvent.WhatsAppAutoReplied(
                                messageId = messageId,
                                senderName = cleanSender,
                                incomingText = cleanText,
                                sentReply = sentResult.sentMessage
                            )
                        )
                    } else {
                        // Fail-safe fallback: move into Smart Confirm queue so message is never dropped silently
                        queueSmartConfirm(
                            appCtx = appCtx,
                            incoming = incoming,
                            draft = draft,
                            reason = "Direct reply failed (${sentResult.errorMessage ?: "unknown"}), moved to Smart Confirm",
                            isSensitive = decision.isSensitive
                        )
                    }
                }
                WhatsAppAutoReplyHelper.ReplyAction.SMART_CONFIRM -> {
                    queueSmartConfirm(
                        appCtx = appCtx,
                        incoming = incoming,
                        draft = draft,
                        reason = decision.reason,
                        isSensitive = decision.isSensitive
                    )
                }
                WhatsAppAutoReplyHelper.ReplyAction.IGNORE -> Unit
            }
        }
    }

    private fun queueSmartConfirm(
        appCtx: Context,
        incoming: WhatsAppAutoReplyHelper.IncomingWhatsAppMessage,
        draft: String,
        reason: String,
        isSensitive: Boolean
    ) {
        val entry = PendingWhatsAppReply(
            message = incoming,
            suggestedDraft = draft,
            decisionReason = reason,
            isSensitive = isSensitive,
            arrivedAtMs = System.currentTimeMillis()
        )

        val senderKey = WhatsAppAutoReplyHelper.normalizeSenderKey(incoming.cleanSenderName)
        val filtered = _pendingReplies.value.filterNot { existing ->
            existing.message.notificationKey == incoming.notificationKey ||
                (!incoming.isGroupChat &&
                    WhatsAppAutoReplyHelper.normalizeSenderKey(existing.message.cleanSenderName) == senderKey &&
                    existing.message.messageText == incoming.messageText)
        }
        _pendingReplies.value = (listOf(entry) + filtered).take(MAX_PENDING)

        AuraAgentEventBus.emit(
            AuraAgentEvent.WhatsAppIncoming(
                messageId = incoming.id,
                senderName = incoming.senderName,
                cleanSenderName = incoming.cleanSenderName,
                messageText = incoming.messageText,
                suggestedDraft = draft,
                isGroupChat = incoming.isGroupChat,
                groupName = incoming.groupName,
                isSensitive = isSensitive,
                reason = reason
            )
        )

        val liveActive = AuraLiveVoiceManager.phase.value !in setOf(
            AuraLiveVoiceManager.Phase.IDLE,
            AuraLiveVoiceManager.Phase.CONNECTING
        )
        if (!liveActive) {
            postSmartConfirmNotification(appCtx, entry)
        }
    }

    fun findPending(queryOrId: String?): PendingWhatsAppReply? {
        val pending = _pendingReplies.value
        if (pending.isEmpty()) return null
        val q = queryOrId?.trim().orEmpty()
        if (q.isBlank()) return pending.first()

        pending.firstOrNull { it.message.id == q }?.let { return it }

        var best: PendingWhatsAppReply? = null
        var bestScore = 0
        for (item in pending) {
            val score = WhatsAppAutoReplyHelper.matchPendingSenderScore(
                query = q,
                candidateSender = item.message.senderName,
                cleanSender = item.message.cleanSenderName
            )
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return if (bestScore >= 40) best else null
    }

    fun confirmAndSendReply(
        context: Context,
        senderOrId: String? = null,
        customMessage: String? = null
    ): ReplyExecutionResult {
        val appCtx = context.applicationContext
        JarvisPreferences.init(appCtx)

        val pending = findPending(senderOrId)
        val recipientName = pending?.message?.cleanSenderName
            ?: senderOrId?.trim().orEmpty()
        val messageToSend = customMessage?.trim()?.takeIf { it.isNotBlank() }
            ?: pending?.suggestedDraft
            ?: return ReplyExecutionResult(
                success = false,
                method = "none",
                recipientName = recipientName,
                sentMessage = "",
                errorMessage = "No matching pending WhatsApp message or reply text found."
            )

        val result = if (pending != null) {
            sendViaHandleOrFallback(appCtx, pending.message, messageToSend)
        } else {
            sendViaMessengerFallback(appCtx, recipientName, messageToSend)
        }

        if (result.success) {
            val senderKey = WhatsAppAutoReplyHelper.normalizeSenderKey(result.recipientName)
            if (senderKey.isNotBlank()) {
                lastRepliedBySender[senderKey] = System.currentTimeMillis()
            }
            if (pending != null) {
                removePendingInternal(appCtx, pending.message.id)
            }
            appendLog(
                senderName = result.recipientName,
                incomingText = pending?.message?.messageText ?: "(Manual/Voice reply)",
                replyText = result.sentMessage,
                mode = "Smart Confirm (${result.method})",
                status = "sent"
            )
        } else {
            appendLog(
                senderName = result.recipientName,
                incomingText = pending?.message?.messageText ?: "",
                replyText = messageToSend,
                mode = "Smart Confirm",
                status = "failed: ${result.errorMessage ?: "error"}"
            )
        }
        return result
    }

    fun dismissPending(context: Context, messageIdOrSender: String? = null): Int {
        val appCtx = context.applicationContext
        if (messageIdOrSender.isNullOrBlank()) {
            val count = _pendingReplies.value.size
            _pendingReplies.value.forEach { cancelConfirmNotification(appCtx, it.message.id) }
            _pendingReplies.value = emptyList()
            return count
        }
        val match = findPending(messageIdOrSender) ?: return 0
        removePendingInternal(appCtx, match.message.id)
        return 1
    }

    fun clearLogs() {
        _replyLogs.value = emptyList()
    }

    fun getPendingSummaryJson(limit: Int = 5): JSONArray {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for (item in _pendingReplies.value.take(limit.coerceIn(1, 15))) {
            arr.put(JSONObject().apply {
                put("id", item.message.id)
                put("sender", item.message.cleanSenderName)
                put("raw_sender", item.message.senderName)
                put("text", item.message.messageText)
                put("suggested_draft", item.suggestedDraft)
                put("is_group", item.message.isGroupChat)
                item.message.groupName?.let { put("group_name", it) }
                put("is_sensitive", item.isSensitive)
                put("has_quick_reply", item.message.hasRemoteInput)
                put("age_seconds", ((now - item.arrivedAtMs) / 1000L).coerceAtLeast(0L))
            })
        }
        return arr
    }

    private fun removePendingInternal(appCtx: Context, messageId: String) {
        _pendingReplies.value = _pendingReplies.value.filterNot { it.message.id == messageId }
        actionHandles.remove(messageId)
        cancelConfirmNotification(appCtx, messageId)
    }

    private fun sendViaHandleOrFallback(
        appCtx: Context,
        incoming: WhatsAppAutoReplyHelper.IncomingWhatsAppMessage,
        replyText: String
    ): ReplyExecutionResult {
        val senderKey = WhatsAppAutoReplyHelper.normalizeSenderKey(incoming.cleanSenderName)
        val handle = actionHandles[incoming.id]
            ?: actionHandles["key::${incoming.notificationKey}"]
            ?: actionHandles["sender::$senderKey"]

        if (handle != null) {
            val remoteOk = executeRemoteInput(appCtx, handle, replyText)
            if (remoteOk) {
                return ReplyExecutionResult(
                    success = true,
                    method = "RemoteInput",
                    recipientName = incoming.cleanSenderName,
                    sentMessage = replyText
                )
            }
        }

        return sendViaMessengerFallback(appCtx, incoming.cleanSenderName, replyText)
    }

    private fun executeRemoteInput(
        appCtx: Context,
        handle: ReplyActionHandle,
        replyText: String
    ): Boolean {
        return try {
            val fillInIntent = Intent()
            val resultsBundle = Bundle()
            for (ri in handle.remoteInputs) {
                resultsBundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(handle.remoteInputs, fillInIntent, resultsBundle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            handle.pendingIntent.send(appCtx, 0, fillInIntent)
            Log.i(TAG, "Sent WhatsApp RemoteInput reply successfully (${handle.notificationKey})")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "RemoteInput PendingIntent expired/canceled: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "RemoteInput execution failed: ${e.message}")
            false
        }
    }

    private fun sendViaMessengerFallback(
        appCtx: Context,
        recipientName: String,
        replyText: String
    ): ReplyExecutionResult {
        return when (val res = WhatsAppMessenger.sendMessage(appCtx, recipientName, replyText, appNumber = null, confirmed = true)) {
            is WhatsAppMessenger.SendResult.Success -> ReplyExecutionResult(
                success = true,
                method = "WhatsAppMessenger",
                recipientName = res.contactName,
                sentMessage = replyText
            )
            is WhatsAppMessenger.SendResult.Error -> ReplyExecutionResult(
                success = false,
                method = "WhatsAppMessenger",
                recipientName = recipientName,
                sentMessage = "",
                errorMessage = res.reason
            )
            else -> ReplyExecutionResult(
                success = false,
                method = "WhatsAppMessenger",
                recipientName = recipientName,
                sentMessage = "",
                errorMessage = "RemoteInput expired and contact '$recipientName' could not be matched automatically ($res)."
            )
        }
    }

    private fun draftReplyText(incoming: WhatsAppAutoReplyHelper.IncomingWhatsAppMessage): String {
        val fallback = WhatsAppAutoReplyHelper.generateContextualFallbackDraft(
            senderName = incoming.cleanSenderName,
            incomingText = incoming.messageText,
            defaultTemplate = JarvisPreferences.waAutoReplyTemplate.value
        )
        if (!JarvisPreferences.waAutoReplyAiDraft.value) return fallback

        val apiKey = JarvisPreferences.liveApiKey.value.trim()
        if (apiKey.isBlank()) return fallback

        return try {
            val prompt = buildString {
                append("Draft ONE short, polite, natural WhatsApp reply (max 18 words, single message, ")
                append("match sender language - Hinglish or English, no quotes, no markdown). ")
                append("User is currently busy unless message requires simple acknowledgement.\n")
                append("Sender: ").append(incoming.cleanSenderName).append('\n')
                append("Message: ").append(incoming.messageText.take(300))
            }
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.35)
                    put("maxOutputTokens", 60)
                })
            }.toString()

            for (model in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
                val req = Request.Builder()
                    .url("$BASE_GEMINI_URL/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                aiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val bodyStr = resp.body?.string() ?: continue
                    val json = JSONObject(bodyStr)
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.take(220)
                    if (!text.isNullOrBlank()) return text
                }
            }
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "AI draft fallback after error: ${e.message}")
            fallback
        }
    }

    private fun postSmartConfirmNotification(appCtx: Context, pending: PendingWhatsAppReply) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureNotificationChannel(nm)

        val notifId = pending.message.id.hashCode().let { if (it == 0) 8041 else kotlin.math.abs(it % 80_000) + 10_000 }
        notifIdMap[pending.message.id] = notifId

        val sendIntent = Intent(appCtx, WhatsAppConfirmActionReceiver::class.java).apply {
            action = WhatsAppConfirmActionReceiver.ACTION_SEND_SUGGESTED
            putExtra(WhatsAppConfirmActionReceiver.EXTRA_MESSAGE_ID, pending.message.id)
        }
        val dismissIntent = Intent(appCtx, WhatsAppConfirmActionReceiver::class.java).apply {
            action = WhatsAppConfirmActionReceiver.ACTION_DISMISS
            putExtra(WhatsAppConfirmActionReceiver.EXTRA_MESSAGE_ID, pending.message.id)
        }
        val sendPi = PendingIntent.getBroadcast(
            appCtx,
            notifId * 2 + 1,
            sendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getBroadcast(
            appCtx,
            notifId * 2 + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(
                appCtx,
                notifId,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val senderLabel = if (pending.message.isGroupChat && !pending.message.groupName.isNullOrBlank()) {
            "${pending.message.cleanSenderName} (${pending.message.groupName})"
        } else {
            pending.message.cleanSenderName
        }

        val bodyText = buildString {
            append("\"").append(pending.message.messageText.take(120)).append("\"\n")
            if (pending.isSensitive) append("⚠ Sensitive/Important • ")
            append("Suggested: ").append(pending.suggestedDraft)
        }

        try {
            val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis_mic)
                .setContentTitle("WhatsApp from $senderLabel")
                .setContentText("Suggested: ${pending.suggestedDraft}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { if (contentIntent != null) setContentIntent(contentIntent) }
                .addAction(0, "Send Reply", sendPi)
                .addAction(0, "Dismiss", dismissPi)
                .build()
            nm.notify(notifId, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post Smart Confirm notification: ${e.message}")
        }
    }

    private fun cancelConfirmNotification(appCtx: Context, messageId: String) {
        val notifId = notifIdMap.remove(messageId) ?: return
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(notifId) }
    }

    private fun ensureNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Smart Confirm prompts before sending WhatsApp notification replies"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun appendLog(
        senderName: String,
        incomingText: String,
        replyText: String,
        mode: String,
        status: String
    ) {
        val entry = ReplyLogEntry(
            id = UUID.randomUUID().toString(),
            senderName = senderName,
            incomingText = incomingText.take(180),
            replyText = replyText.take(220),
            mode = mode,
            status = status,
            timestampMs = System.currentTimeMillis()
        )
        _replyLogs.value = (listOf(entry) + _replyLogs.value).take(MAX_LOGS)
    }

    private fun pruneOldFingerprints(nowMs: Long) {
        if (recentFingerprints.size > 120) {
            val cutoff = nowMs - (DEDUPE_WINDOW_MS * 3)
            recentFingerprints.entries.removeIf { it.value < cutoff }
        }
    }
}
