package com.reeltranscribe.audio

/**
 * One piece of recorded audio, ready to be sent for transcription.
 *
 * @param index    0 for the first chunk, 1 for the second, ... (keeps the transcript in order)
 * @param samples  16-bit mono PCM samples
 * @param sampleRate samples per second (16 000 in this app)
 */
class AudioChunk(val index: Int, val samples: ShortArray, val sampleRate: Int) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
}

/**
 * Collects a continuous stream of audio samples and cuts it into
 * fixed-length chunks (20 seconds by default).
 *
 * Usage:
 *   val chunker = Chunker(sampleRate = 16_000)
 *   chunker.add(samplesFromMic)   // returns any chunks that are now complete
 *   chunker.flush()               // at the end: returns the leftover piece, if long enough
 *
 * This class has no Android code in it, so it can be unit-tested on a computer.
 */
class Chunker(
    private val sampleRate: Int,
    chunkSeconds: Double = 20.0,
    /** A final leftover shorter than this is thrown away (too short to transcribe). */
    private val minFinalSeconds: Double = 1.0,
) {
    private val chunkSize: Int = (sampleRate * chunkSeconds).toInt()
    private val buffer = ShortArray(chunkSize)
    private var filled = 0          // how many samples are currently in `buffer`
    private var nextIndex = 0       // index to give the next chunk

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(chunkSize > 0) { "chunkSeconds must be positive" }
    }

    /**
     * Adds the first [count] samples of [samples].
     * Returns the chunks that became complete (often none, sometimes one or more).
     */
    fun add(samples: ShortArray, count: Int = samples.size): List<AudioChunk> {
        require(count in 0..samples.size) { "count out of range" }
        val finished = mutableListOf<AudioChunk>()
        var offset = 0
        while (offset < count) {
            // Copy as much as fits into the current chunk.
            val toCopy = minOf(count - offset, chunkSize - filled)
            System.arraycopy(samples, offset, buffer, filled, toCopy)
            filled += toCopy
            offset += toCopy
            if (filled == chunkSize) {
                finished += AudioChunk(nextIndex++, buffer.copyOf(), sampleRate)
                filled = 0
            }
        }
        return finished
    }

    /**
     * Call when recording stops. Returns the partly-filled last chunk,
     * or null if it is empty or shorter than [minFinalSeconds].
     */
    fun flush(): AudioChunk? {
        val leftover = filled
        filled = 0
        if (leftover == 0 || leftover < sampleRate * minFinalSeconds) return null
        return AudioChunk(nextIndex++, buffer.copyOf(leftover), sampleRate)
    }
}
