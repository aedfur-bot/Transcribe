package com.reeltranscribe.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns raw 16-bit mono PCM samples into a .wav file (in memory).
 *
 * WAV is just a 44-byte header followed by the samples, so no Android
 * encoder is needed. A 20-second, 16 kHz chunk is about 640 KB,
 * well under OpenAI's 25 MB upload limit.
 */
object WavEncoder {
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)            // size of everything after this field
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                        // size of the format block
        buf.putShort(1)                       // 1 = plain PCM
        buf.putShort(1)                       // 1 channel (mono)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)            // bytes per second
        buf.putShort(2)                       // bytes per sample frame
        buf.putShort(16)                      // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }
}

/** Reduces the sample rate by an integer factor by averaging neighbours (e.g. 48 kHz / 3 = 16 kHz). */
object Downsampler {
    fun downsample(input: ShortArray, count: Int, factor: Int): ShortArray {
        if (factor == 1) return input.copyOf(count)
        val out = ShortArray(count / factor)
        for (i in out.indices) {
            var sum = 0
            for (j in 0 until factor) sum += input[i * factor + j]
            out[i] = (sum / factor).toShort()
        }
        return out
    }
}
