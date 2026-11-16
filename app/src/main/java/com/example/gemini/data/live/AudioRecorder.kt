package com.example.gemini.data.live

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * AudioRecorder: Captures 16kHz 16-bit mono PCM audio in 20ms chunks (640 bytes)
 * for streaming into Gemini Live WebSocket. Applies hardware Acoustic Echo Cancellation,
 * Noise Suppression, and Automatic Gain Control.
 */
class AudioRecorder(
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 16000 samples/sec * 0.02s * 2 bytes/sample = 640 bytes per 20ms chunk
        const val CHUNK_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 32000)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return false
            }

            audioRecord = record

            // Attach hardware acoustic effects to suppress feedback loop
            val audioSessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
            }

            record.startRecording()
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && isRecording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRms(buffer, read)
                        if (!isMuted.get()) {
                            val chunk = buffer.copyOf(read)
                            onAudioChunk(chunk, amplitude)
                        } else {
                            // If muted, send zeroed silence or skip
                            onAudioChunk(ByteArray(0), 0f)
                        }
                    }
                }
            }
            Log.d(TAG, "Audio recording started at 16kHz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stop()
            false
        }
    }

    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        Log.d(TAG, "Audio recording stopped")
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until length step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val sampleShort = sample.toShort()
            sum += sampleShort * sampleShort
        }
        val mean = sum / maxOf(1, sampleCount)
        val rms = sqrt(mean)
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }
}
