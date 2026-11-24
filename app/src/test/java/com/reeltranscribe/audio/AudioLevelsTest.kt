package com.reeltranscribe.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioLevelsTest {

    @Test
    fun `all zeros is silent, real sound is not`() {
        assertTrue(AudioLevels.isSilent(ShortArray(1000)))
        assertFalse(AudioLevels.isSilent(ShortArray(1000) { if (it == 500) -3000 else 0 }))
    }

    @Test
    fun `silence probe ignores the skipped start and judges the window`() {
        val probe = SilenceProbe(sampleRate = 10, skipSeconds = 1.0, listenSeconds = 2.0)
        probe.add(ShortArray(10) { 20000 })   // loud, but in the skipped first second
        assertFalse(probe.isDone)
        probe.add(ShortArray(20))             // 2 s of silence
        assertTrue(probe.isDone)
        assertTrue(probe.wasSilent)
    }

    @Test
    fun `silence probe detects sound inside the window`() {
        val probe = SilenceProbe(sampleRate = 10, listenSeconds = 2.0)
        probe.add(ShortArray(15) { if (it == 12) 5000 else 0 })
        probe.add(ShortArray(10))
        assertTrue(probe.isDone)
        assertFalse(probe.wasSilent)
        assertEquals(5000, probe.peak)
    }

    @Test
    fun `wav header describes 16 kHz mono 16-bit`() {
        val wav = WavEncoder.encode(shortArrayOf(1, -1, 300), 16_000)
        assertEquals(44 + 6, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt())        // channels
        assertEquals(16_000, b.getInt(24))             // sample rate
        assertEquals(16, b.getShort(34).toInt())       // bits per sample
        assertEquals(6, b.getInt(40))                  // data size
        assertEquals(300, b.getShort(48).toInt())      // last sample
    }

    @Test
    fun `downsampling by 3 averages groups of three`() {
        val out = Downsampler.downsample(shortArrayOf(3, 6, 9, 30, 30, 30, 1), count = 6, factor = 3)
        assertArrayEquals(shortArrayOf(6, 30), out)
    }
}
