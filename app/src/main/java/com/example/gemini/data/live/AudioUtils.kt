package com.example.gemini.data.live

import kotlin.math.sqrt

data class AudioFrequencyData(
    val amplitude: Float = 0f,
    val pitchHz: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f
)

object AudioUtils {

    /**
     * Calculates normalized RMS (0.0 to 1.0) from 16-bit Mono PCM bytes.
     */
    fun calculateRmsNormalized(bytes: ByteArray, length: Int): Float {
        if (length <= 0) return 0f
        var sumSquares = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f

        var i = 0
        while (i < length - 1) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val normalized = (rms / 3500.0).toFloat()
        return normalized.coerceIn(0f, 1f)
    }

    /**
     * Lightweight real-time frequency analysis on 16-bit Mono PCM audio.
     */
    fun analyzeFrequency(bytes: ByteArray, length: Int, sampleRate: Int = 16000): AudioFrequencyData {
        if (length <= 0) return AudioFrequencyData()
        val sampleCount = length / 2
        if (sampleCount < 4) return AudioFrequencyData()

        var sumSquares = 0.0
        var zeroCrossings = 0
        var lastSample = 0
        var diffSumSquares = 0.0
        var lowPassSumSquares = 0.0
        var lowPassFilterState = 0.0

        val alpha = 0.17

        var i = 0
        var idx = 0
        while (i < length - 1) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()

            if (idx > 0) {
                if ((sample >= 0 && lastSample < 0) || (sample < 0 && lastSample >= 0)) {
                    zeroCrossings++
                }
                val diff = sample - lastSample
                diffSumSquares += (diff * diff).toDouble()
            }

            lowPassFilterState += alpha * (sample - lowPassFilterState)
            lowPassSumSquares += (lowPassFilterState * lowPassFilterState)

            lastSample = sample
            idx++
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val amp = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
        if (amp < 0.015f) {
            return AudioFrequencyData(amplitude = amp)
        }

        val zcr = zeroCrossings.toFloat() / (sampleCount - 1).coerceAtLeast(1)
        val pitchHz = (zcr * (sampleRate / 2.0f)).coerceIn(80f, 3500f)

        val bassRms = sqrt(lowPassSumSquares / sampleCount)
        val bass = ((bassRms / 2500.0).toFloat() * 1.2f).coerceIn(0f, 1f)

        val highRms = sqrt(diffSumSquares / (sampleCount - 1).coerceAtLeast(1))
        val high = ((highRms / 3000.0).toFloat() * 1.1f).coerceIn(0f, 1f)

        val mid = (amp * 1.4f - (bass * 0.4f + high * 0.4f)).coerceIn(0f, 1f)

        return AudioFrequencyData(
            amplitude = amp,
            pitchHz = pitchHz,
            bass = bass,
            mid = mid,
            high = high
        )
    }
}
