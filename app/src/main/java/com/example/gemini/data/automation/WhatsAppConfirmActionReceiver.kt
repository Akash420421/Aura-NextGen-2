package com.example.gemini.data.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles quick-action confirmation / dismissal from Aura's Smart Confirm
 * WhatsApp notification actions when Gemini Live Voice is not actively running.
 */
class WhatsAppConfirmActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SEND_SUGGESTED = "com.example.gemini.action.WA_SEND_SUGGESTED"
        const val ACTION_DISMISS = "com.example.gemini.action.WA_DISMISS"
        const val EXTRA_MESSAGE_ID = "extra_wa_message_id"
        private const val TAG = "WaConfirmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val msgId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val appCtx = context.applicationContext

        when (intent.action) {
            ACTION_SEND_SUGGESTED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val res = WhatsAppAutoReplyManager.confirmAndSendReply(
                            context = appCtx,
                            senderOrId = msgId,
                            customMessage = null
                        )
                        CoroutineScope(Dispatchers.Main).launch {
                            val label = if (res.success) {
                                "WhatsApp reply sent to ${res.recipientName}"
                            } else {
                                "WhatsApp reply failed: ${res.errorMessage ?: "error"}"
                            }
                            Toast.makeText(appCtx, label, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed quick-confirm send", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_DISMISS -> {
                WhatsAppAutoReplyManager.dismissPending(appCtx, msgId)
            }
        }
    }
}
