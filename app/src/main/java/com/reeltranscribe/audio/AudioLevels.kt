package com.reeltranscribe.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Small helpers for deciding whether audio is "silent".
 *
 * When an app blocks playback capture, Android hands us pure digital silence
 * (every sample is 0), so a very low threshold is enough.
 */
object AudioLevels {
    /** Samples whose loudness never goes above this are treated as silence (about -54 dBFS). */
    const val SILENCE_PEAK = 64

    /** Largest absolute sample value in the first [count] samples. */
    fun peak(samples: ShortArray, count: Int = samples.size): Int {
        var max = 0
        for (i in 0 until count) {
            val v = abs(samples[i].toInt())
            if (v > max) max = v
        }
        return max
    }

    fun isSilent(samples: ShortArray, count: Int = samples.size): Boolean =
        peak(samples, count) <= SILENCE_PEAK

    /** Converts a peak value to decibels relative to full scale (0 dBFS = loudest possible). */
    fun peakToDbfs(peak: Int): Double =
        if (peak <= 0) Double.NEGATIVE_INFINITY else 20 * log10(peak / 32767.0)
}

/**
 * Listens to the first few seconds of a recording and decides whether
 * any sound came through at all.
 *
 * @param skipSeconds   audio to ignore at the very start (gives you a moment to
 *                      resume the reel if the permission dialog paused it)
 * @param listenSeconds how much audio to judge
 */
class SilenceProbe(
    private val sampleRate: Int,
    skipSeconds: Double = 0.0,
    listenSeconds: Double = 10.0,
) {
    private val skipSamples = (sampleRate * skipSeconds).toLong()
    private val endSamples = skipSamples + (sampleRate * listenSeconds).toLong()
    private var seen = 0L
    var peak = 0
        private set

    /** True once enough audio has been seen to give a verdict. */
    val isDone: Boolean get() = seen >= endSamples

    /** True if the judged window contained no real sound. Only meaningful when [isDone]. */
    val wasSilent: Boolean get() = peak <= AudioLevels.SILENCE_PEAK

    fun add(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            if (seen >= endSamples) return
            if (seen >= skipSamples) {
                val v = abs(samples[i].toInt())
                if (v > peak) peak = v
            }
            seen++
        }
    }
}
