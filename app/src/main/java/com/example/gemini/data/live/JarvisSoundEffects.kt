package com.example.gemini.data.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural, ultra-low latency sound effect generator for Jarvis.
 * Synthesizes high-tech sci-fi earcons (wake & standby) directly into 16-bit PCM,
 * completely eliminating external audio file dependencies and load latency.
 */
object JarvisSoundEffects {
    private const val TAG = "JarvisSoundEffects"
    private const val SAMPLE_RATE = 44100

    private val wakeSoundPcm: ByteArray by lazy { generateWakeSound() }
    private val standbySoundPcm: ByteArray by lazy { generateStandbySound() }

    /**
     * Futuristic ascending dual-tone chime when Jarvis wakes up or comes out of background.
     */
    fun playWakeSound() {
        playPcm(wakeSoundPcm)
    }

    /**
     * Sleek descending harmonic earcon when Jarvis enters standby / goes to background.
     */
    fun playStandbySound() {
        playPcm(standbySoundPcm)
    }

    private fun playPcm(pcmData: ByteArray) {
        Thread {
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcmData.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcmData, 0, pcmData.size)
                track.play()

                val durationMs = (pcmData.size / 2 * 1000L) / SAMPLE_RATE
                Thread.sleep(durationMs + 60)
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error playing sound effect: ${e.message}")
            }
        }.start()
    }

    private fun generateWakeSound(): ByteArray {
        val totalDurationMs = 270
        val totalSamples = (SAMPLE_RATE * totalDurationMs) / 1000
        val buffer = ByteArray(totalSamples * 2)

        val note1Samples = (SAMPLE_RATE * 90) / 1000
        val silenceSamples = (SAMPLE_RATE * 20) / 1000
        val note2Start = note1Samples + silenceSamples

        var idx = 0
        for (i in 0 until totalSamples) {
            val sampleVal: Short = when {
                i < note1Samples -> {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = (i.toDouble() / ((SAMPLE_RATE * 8) / 1000)).coerceAtMost(1.0)
                    val decay = ((note1Samples - i).toDouble() / ((SAMPLE_RATE * 30) / 1000)).coerceIn(0.0, 1.0)
                    val env = attack * decay
                    val wave = sin(2 * PI * 587.33 * t) + 0.3 * sin(2 * PI * 1174.66 * t)
                    (wave * env * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                i < note2Start -> 0.toShort()
                else -> {
                    val note2Idx = i - note2Start
                    val note2Duration = totalSamples - note2Start
                    val t = note2Idx.toDouble() / SAMPLE_RATE
                    val attack = (note2Idx.toDouble() / ((SAMPLE_RATE * 10) / 1000)).coerceAtMost(1.0)
                    val decay = ((note2Duration - note2Idx).toDouble() / note2Duration).coerceIn(0.0, 1.0)
                    val env = attack * decay * decay
                    val wave = sin(2 * PI * 880.0 * t) + 0.35 * sin(2 * PI * 1760.0 * t) + 0.15 * sin(2 * PI * 2640.0 * t)
                    (wave * env * 24000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
            buffer[idx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[idx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun generateStandbySound(): ByteArray {
        val totalDurationMs = 240
        val totalSamples = (SAMPLE_RATE * totalDurationMs) / 1000
        val buffer = ByteArray(totalSamples * 2)

        val note1Samples = (SAMPLE_RATE * 80) / 1000
        val silenceSamples = (SAMPLE_RATE * 15) / 1000
        val note2Start = note1Samples + silenceSamples

        var idx = 0
        for (i in 0 until totalSamples) {
            val sampleVal: Short = when {
                i < note1Samples -> {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = (i.toDouble() / ((SAMPLE_RATE * 8) / 1000)).coerceAtMost(1.0)
                    val decay = ((note1Samples - i).toDouble() / note1Samples).coerceIn(0.0, 1.0)
                    val env = attack * decay
                    val wave = sin(2 * PI * 783.99 * t) + 0.25 * sin(2 * PI * 1567.98 * t)
                    (wave * env * 19000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                i < note2Start -> 0.toShort()
                else -> {
                    val note2Idx = i - note2Start
                    val note2Duration = totalSamples - note2Start
                    val t = note2Idx.toDouble() / SAMPLE_RATE
                    val attack = (note2Idx.toDouble() / ((SAMPLE_RATE * 8) / 1000)).coerceAtMost(1.0)
                    val decay = ((note2Duration - note2Idx).toDouble() / note2Duration).coerceIn(0.0, 1.0)
                    val env = attack * decay * decay
                    val wave = sin(2 * PI * 440.0 * t) + 0.2 * sin(2 * PI * 880.0 * t)
                    (wave * env * 18000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
            buffer[idx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[idx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }
}
