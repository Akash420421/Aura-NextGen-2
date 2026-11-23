package com.example.gemini.data.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gemini.MainActivity
import com.example.gemini.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AuraLiveVoiceService: Keeps the Gemini Live duplex audio session, microphone,
 * and WebSocket alive when Aura AI is minimized or when Jarvis is controlling
 * other apps (WhatsApp, Chrome, YouTube, etc.) on the user's phone.
 */
class AuraLiveVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        if (intent?.action == ACTION_STOP) {
            AuraLiveVoiceManager.stopSession()
            stopSelf()
            return START_NOT_STICKY
        }

        val started = runCatching {
            val n = buildNotification("Connecting to Gemini Live...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }

        if (started.isFailure) {
            android.util.Log.w(TAG, "Live Voice Service could not go foreground: ${started.exceptionOrNull()}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:live-voice-session").apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L) // 2 hours max
                }
            }
        }

        statusJob?.cancel()
        statusJob = scope.launch {
            AuraLiveVoiceManager.phase.collectLatest { phase ->
                if (phase == AuraLiveVoiceManager.Phase.IDLE) {
                    stopSelf()
                    return@collectLatest
                }

                val status = when (phase) {
                    AuraLiveVoiceManager.Phase.CONNECTING -> "Connecting to Gemini Live..."
                    AuraLiveVoiceManager.Phase.IDLE_READY -> "Speak to ask"
                    AuraLiveVoiceManager.Phase.LISTENING -> "Jarvis listening..."
                    AuraLiveVoiceManager.Phase.THINKING -> "Jarvis thinking..."
                    AuraLiveVoiceManager.Phase.WORKING -> "Jarvis working..."
                    AuraLiveVoiceManager.Phase.SPEAKING -> "Jarvis speaking..."
                    AuraLiveVoiceManager.Phase.MUTED -> "Jarvis muted"
                    AuraLiveVoiceManager.Phase.IDLE -> "Idle"
                }

                runCatching {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(status))
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis Live Voice Mode", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Active while speaking live with Jarvis / Gemini Live"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AuraLiveVoiceService::class.java).setAction(ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_mic)
            .setContentTitle("Jarvis · Gemini Live")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openIntent)
            .addAction(0, "End Voice Call", stopIntent)
            .build()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AuraLiveVoiceService"
        private const val CHANNEL_ID = "aura_live_voice"
        private const val NOTIF_ID = 8842
        const val ACTION_STOP = "com.example.gemini.live.STOP"

        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, AuraLiveVoiceService::class.java))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to start AuraLiveVoiceService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AuraLiveVoiceService::class.java))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error stopping AuraLiveVoiceService: ${e.message}")
            }
        }
    }
}
