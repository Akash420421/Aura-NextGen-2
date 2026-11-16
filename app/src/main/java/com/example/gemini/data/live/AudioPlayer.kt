package com.example.gemini.data.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * AudioPlayer: Plays 24kHz 16-bit mono PCM audio streamed back from Gemini Live.
 * Routes audio through loudspeaker (USAGE_MEDIA), includes anti-stutter jitter buffer,
 * and supports instant barge-in flushing on interruption.
 */
class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 8, 96000)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        playbackThread = Thread({
            try {
                synchronized(trackLock) {
                    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                        audioTrack?.play()
                    }
                }

                while (isRunning.get()) {
                    val chunk = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        if (!isPlayingState.get()) {
                            isPlayingState.set(true)
                            onPlaybackStateChanged(true)
                        }

                        val amp = calculateRms(chunk, chunk.size)
                        onPlaybackAmplitude(amp)

                        synchronized(trackLock) {
                            audioTrack?.write(chunk, 0, chunk.size)
                        }
                    } else {
                        if (isPlayingState.get() && audioQueue.isEmpty()) {
                            isPlayingState.set(false)
                            onPlaybackStateChanged(false)
                            onPlaybackAmplitude(0f)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback thread error: ${e.message}", e)
            } finally {
                isPlayingState.set(false)
                onPlaybackStateChanged(false)
                onPlaybackAmplitude(0f)
            }
        }, "GeminiLiveAudioPlayer").apply { start() }

        Log.d(TAG, "Audio player started at 24kHz")
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        audioQueue.offer(pcmData)
    }

    /**
     * Instantly flushes audio buffer when the user interrupts (talks over the model).
     */
    fun stopPlayback() {
        audioQueue.clear()
        synchronized(trackLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }
        isPlayingState.set(false)
        onPlaybackStateChanged(false)
        onPlaybackAmplitude(0f)
    }

    fun release() {
        isRunning.set(false)
        stopPlayback()
        playbackThread?.interrupt()
        playbackThread = null

        synchronized(trackLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
            }
            audioTrack = null
        }
        Log.d(TAG, "Audio player released")
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
