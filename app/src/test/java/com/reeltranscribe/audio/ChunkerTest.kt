package com.reeltranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    // A tiny sample rate keeps the numbers readable: 10 samples = 1 second.
    private val rate = 10

    /** Samples numbered 0,1,2,... so we can check order is preserved. */
    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun `nothing is emitted until a chunk is full`() {
        val chunker = Chunker(rate, chunkSeconds = 2.0)        // 20 samples per chunk
        assertTrue(chunker.add(ramp(0, 19)).isEmpty())
        val out = chunker.add(ramp(19, 1))
        assertEquals(1, out.size)
        assertEquals(20, out[0].samples.size)
    }

    @Test
    fun `one big write can produce several chunks, in order, with no samples lost`() {
        val chunker = Chunker(rate, chunkSeconds = 2.0)
        val out = chunker.add(ramp(0, 65))                      // 3 full chunks + 5 left over
        assertEquals(listOf(0, 1, 2), out.map { it.index })
        assertEquals((0 until 60).toList(), out.flatMap { c -> c.samples.map { it.toInt() } })

        // The 5 leftover samples (0.5 s) are below the 1 s minimum, so flush drops them.
        assertNull(chunker.flush())
    }

    @Test
    fun `many small writes join up across chunk boundaries`() {
        val chunker = Chunker(rate, chunkSeconds = 2.0)
        val all = mutableListOf<AudioChunk>()
        var next = 0
        repeat(14) {                                          // 14 x 3 = 42 samples
            all += chunker.add(ramp(next, 3))
            next += 3
        }
        assertEquals(2, all.size)
        assertEquals((0 until 40).toList(), all.flatMap { c -> c.samples.map { it.toInt() } })
    }

    @Test
    fun `flush returns the leftover when it is long enough`() {
        val chunker = Chunker(rate, chunkSeconds = 2.0, minFinalSeconds = 1.0)
        chunker.add(ramp(0, 35))                              // 1 chunk + 15 samples (1.5 s)
        val last = chunker.flush()
        assertEquals(1, last!!.index)
        assertEquals((20 until 35).toList(), last.samples.map { it.toInt() })
        assertEquals(1.5, last.durationSeconds, 1e-9)
    }

    @Test
    fun `flush drops a leftover that is too short, and an empty one`() {
        val chunker = Chunker(rate, chunkSeconds = 2.0, minFinalSeconds = 1.0)
        chunker.add(ramp(0, 9))                               // 0.9 s
        assertNull(chunker.flush())
        assertNull(chunker.flush())                           // nothing left at all
    }

    @Test
    fun `only the first count samples of the array are used`() {
        val chunker = Chunker(rate, chunkSeconds = 1.0)
        val out = chunker.add(ramp(0, 50), count = 10)
        assertEquals(1, out.size)
        assertEquals((0 until 10).toList(), out[0].samples.map { it.toInt() })
    }

    @Test
    fun `20-second chunks at 16 kHz have 320000 samples`() {
        val chunker = Chunker(16_000)                         // app defaults
        val out = chunker.add(ShortArray(16_000 * 45))        // 45 s of audio
        assertEquals(2, out.size)
        assertEquals(320_000, out[0].samples.size)
        assertEquals(5.0, chunker.flush()!!.durationSeconds, 1e-9)
    }

    @Test
    fun `emitted chunks are copies, not the internal buffer`() {
        val chunker = Chunker(rate, chunkSeconds = 1.0)
        val first = chunker.add(ramp(0, 10)).single()
        chunker.add(ramp(100, 10))                            // would overwrite a shared buffer
        assertEquals((0 until 10).toList(), first.samples.map { it.toInt() })
    }
}
