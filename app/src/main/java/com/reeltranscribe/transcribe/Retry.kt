package com.reeltranscribe.transcribe

import com.reeltranscribe.audio.AudioChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What happened to one chunk. */
sealed interface ChunkOutcome {
    val index: Int

    data class Success(override val index: Int, val text: String) : ChunkOutcome

    data class Missing(override val index: Int, val error: TranscriptionError) : ChunkOutcome
}

/**
 * Sends one chunk, and if it fails with a temporary problem, waits and tries
 * exactly once more. Never throws for transcription problems: a chunk that
 * still fails comes back as [ChunkOutcome.Missing] so the app can carry on.
 *
 * @param retryDelayMs how long to wait before the retry (unless OpenAI says otherwise)
 * @param sleep        the waiting function; tests replace it so they run instantly
 */
suspend fun transcribeWithOneRetry(
    chunk: AudioChunk,
    previousText: String,
    transcriber: Transcriber,
    retryDelayMs: Long = 2_000,
    maxRetryDelayMs: Long = 20_000,
    sleep: suspend (Long) -> Unit = { delay(it) },
): ChunkOutcome {
    // First attempt.
    val first = attempt(chunk, previousText, transcriber)
    if (first is ChunkOutcome.Success) return first
    val firstError = (first as ChunkOutcome.Missing).error
    if (!firstError.retryable) return first

    // Respect "Retry-After" from a rate limit, but never wait absurdly long.
    val wait = ((firstError as? TranscriptionError.RateLimited)?.retryAfterMs ?: retryDelayMs)
        .coerceIn(0, maxRetryDelayMs)
    sleep(wait)

    // Second and last attempt: whatever happens now is the final answer.
    return attempt(chunk, previousText, transcriber)
}

/** Runs one attempt. Unexpected crashes are turned into errors too, so nothing crashes the app. */
private suspend fun attempt(chunk: AudioChunk, previousText: String, transcriber: Transcriber): ChunkOutcome =
    try {
        ChunkOutcome.Success(chunk.index, transcriber.transcribe(chunk, previousText))
    } catch (e: CancellationException) {
        throw e // the user pressed Stop / the service ended: not a failure
    } catch (e: TranscriptionError) {
        ChunkOutcome.Missing(chunk.index, e)
    } catch (e: Exception) {
        ChunkOutcome.Missing(chunk.index, TranscriptionError.Unexpected(e.message ?: e.javaClass.simpleName))
    }

/** Builds the transcript text. Kept separate so it can be unit-tested. */
object TranscriptFormatter {
    /** Adds one chunk's result to the end of the transcript. */
    fun append(transcript: String, outcome: ChunkOutcome): String {
        val piece = when (outcome) {
            is ChunkOutcome.Success -> outcome.text.trim()
            is ChunkOutcome.Missing ->
                "[missing: part ${outcome.index + 1} — ${outcome.error.userMessage}]"
        }
        if (piece.isEmpty()) return transcript
        return if (transcript.isEmpty()) piece else "$transcript $piece"
    }

    /** The last [maxChars] characters, used as context for the next chunk. Skips "missing" markers. */
    fun contextTail(transcript: String, maxChars: Int = 200): String {
        val clean = transcript.replace(Regex("""\[missing[^\]]*]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        return clean.takeLast(maxChars)
    }
}
