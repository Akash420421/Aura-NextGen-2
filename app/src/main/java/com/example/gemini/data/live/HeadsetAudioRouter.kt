package com.example.gemini.data.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * HeadsetAudioRouter: Manages audio routing between:
 * 1. External headsets (Bluetooth earbuds / AirPods / wired headsets) -> mic & audio to headset.
 * 2. Device Loudspeaker -> When NO headset is connected, forces playback strictly through
 *    the phone's main loudspeaker (TYPE_BUILTIN_SPEAKER), NEVER the call earpiece speaker.
 */
class HeadsetAudioRouter(private val context: Context) {

    companion object {
        private const val TAG = "HeadsetAudioRouter"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isRegistered = false
    private var isScoStarted = false
    @Volatile
    private var cachedIsHeadsetConnected = false

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Audio broadcast received: $action")
            when (action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        Log.i(TAG, "Bluetooth SCO connected: microphone active on Bluetooth headset.")
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        Log.i(TAG, "Bluetooth SCO disconnected.")
                        routeToOptimalAudio()
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    Log.i(TAG, "Wired headset plug state changed: state=$state")
                    routeToOptimalAudio()
                }
                else -> {
                    routeToOptimalAudio()
                }
            }
        }
    }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device added, re-routing audio...")
                routeToOptimalAudio()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device removed, re-routing audio...")
                routeToOptimalAudio()
            }
        }
    } else null

    fun start() {
        try {
            if (!isRegistered) {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    addAction(Intent.ACTION_HEADSET_PLUG)
                    addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.adapter.action.STATE_CHANGED")
                }
                context.registerReceiver(headsetReceiver, filter)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
                }

                isRegistered = true
            }

            routeToOptimalAudio()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting HeadsetAudioRouter: ${e.message}", e)
        }
    }

    fun isHeadsetConnected(): Boolean = cachedIsHeadsetConnected

    private fun routeToOptimalAudio() {
        try {
            var hasBtHeadset = false
            var hasWiredHeadset = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
                for (dev in devices) {
                    when (dev.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_HEARING_AID -> {
                            hasBtHeadset = true
                        }
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> {
                            hasWiredHeadset = true
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                hasWiredHeadset = audioManager.isWiredHeadsetOn
                @Suppress("DEPRECATION")
                hasBtHeadset = audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
            }

            cachedIsHeadsetConnected = hasBtHeadset || hasWiredHeadset

            if (hasBtHeadset) {
                Log.i(TAG, "Bluetooth headset detected. Activating Bluetooth SCO for duplex voice...")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!isScoStarted) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    isScoStarted = true
                }
                audioManager.isSpeakerphoneOn = false
            } else if (hasWiredHeadset) {
                Log.i(TAG, "Wired headset detected. Routing voice to wired headset...")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                stopBluetoothScoIfActive()
                audioManager.isSpeakerphoneOn = false
            } else {
                Log.i(TAG, "No headset connected. Forcing audio to LOUDSPEAKER (speakerphone)...")
                audioManager.mode = AudioManager.MODE_NORMAL
                stopBluetoothScoIfActive()
                audioManager.isSpeakerphoneOn = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring audio routing: ${e.message}", e)
        }
    }

    private fun stopBluetoothScoIfActive() {
        if (isScoStarted) {
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Bluetooth SCO: ${e.message}")
            }
            isScoStarted = false
        }
    }

    fun release() {
        try {
            stopBluetoothScoIfActive()
            if (isRegistered) {
                try { context.unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
                }
                isRegistered = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing HeadsetAudioRouter: ${e.message}")
        }
    }
}
