package com.reeltranscribe.transcribe

import com.reeltranscribe.audio.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {

    private val chunk = AudioChunk(index = 4, samples = ShortArray(16), sampleRate = 16)

    /** A fake transcriber that plays back a script of results, one per call. */
    private class ScriptedTranscriber(vararg steps: Any) : Transcriber {
        private val script = steps.toMutableList()
        var calls = 0
        override suspend fun transcribe(chunk: AudioChunk, previousText: String): String {
            calls++
            return when (val step = script.removeAt(0)) {
                is Exception -> throw step
                else -> step as String
            }
        }
    }

    /** Records how long the code asked to wait, without actually waiting. */
    private val waits = mutableListOf<Long>()
    private val fakeSleep: suspend (Long) -> Unit = { waits += it }

    private suspend fun run(t: Transcriber) =
        transcribeWithOneRetry(chunk, "", t, retryDelayMs = 2_000, sleep = fakeSleep)

    @Test
    fun `success on first try does not retry or wait`() = runTest {
        val t = ScriptedTranscriber("hello")
        assertEquals(ChunkOutcome.Success(4, "hello"), run(t))
        assertEquals(1, t.calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `temporary failure then success`() = runTest {
        val t = ScriptedTranscriber(TranscriptionError.NoNetwork(), "world")
        assertEquals(ChunkOutcome.Success(4, "world"), run(t))
        assertEquals(2, t.calls)
        assertEquals(listOf(2_000L), waits)
    }

    @Test
    fun `two failures give a missing chunk - only one retry`() = runTest {
        val t = ScriptedTranscriber(TranscriptionError.ServerError(503), TranscriptionError.NoNetwork(), "never used")
        val outcome = run(t)
        assertTrue(outcome is ChunkOutcome.Missing)
        assertTrue((outcome as ChunkOutcome.Missing).error is TranscriptionError.NoNetwork)
        assertEquals(4, outcome.index)
        assertEquals(2, t.calls)
    }

    @Test
    fun `invalid key is not retried`() = runTest {
        val t = ScriptedTranscriber(TranscriptionError.InvalidApiKey(), "never used")
        val outcome = run(t) as ChunkOutcome.Missing
        assertTrue(outcome.error is TranscriptionError.InvalidApiKey)
        assertTrue(outcome.error.fatal)
        assertEquals(1, t.calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `rate limit waits as long as OpenAI asks, capped at 20 s`() = runTest {
        run(ScriptedTranscriber(TranscriptionError.RateLimited(retryAfterMs = 5_000), "ok"))
        run(ScriptedTranscriber(TranscriptionError.RateLimited(retryAfterMs = 600_000), "ok"))
        run(ScriptedTranscriber(TranscriptionError.RateLimited(retryAfterMs = null), "ok"))
        assertEquals(listOf(5_000L, 20_000L, 2_000L), waits)
    }

    @Test
    fun `an unexpected crash becomes a retryable error instead of crashing`() = runTest {
        val t = ScriptedTranscriber(IllegalStateException("boom"), "recovered")
        assertEquals(ChunkOutcome.Success(4, "recovered"), run(t))

        val t2 = ScriptedTranscriber(IllegalStateException("boom"), IllegalStateException("again"))
        val outcome = run(t2) as ChunkOutcome.Missing
        assertTrue(outcome.error is TranscriptionError.Unexpected)
    }

    @Test
    fun `transcript keeps successes and marks missing parts`() {
        var text = ""
        text = TranscriptFormatter.append(text, ChunkOutcome.Success(0, " Hello there. "))
        text = TranscriptFormatter.append(text, ChunkOutcome.Missing(1, TranscriptionError.NoNetwork()))
        text = TranscriptFormatter.append(text, ChunkOutcome.Success(2, "Bye."))
        text = TranscriptFormatter.append(text, ChunkOutcome.Success(3, "   ")) // empty result adds nothing
        assertEquals(
            "Hello there. [missing: part 2 — No network connection. Check Wi-Fi or mobile data.] Bye.",
            text,
        )
        assertEquals("Hello there. Bye.", TranscriptFormatter.contextTail(text))
        assertEquals("Bye.", TranscriptFormatter.contextTail(text, maxChars = 4))
    }
}
