package com.reeltranscribe.transcribe

import com.reeltranscribe.audio.AudioChunk

/** Anything that can turn a chunk of audio into text. The real one talks to OpenAI. */
fun interface Transcriber {
    /**
     * @param previousText the end of the transcript so far; helps the model
     *                     continue sentences that were cut at a chunk boundary
     * @throws TranscriptionError when something goes wrong
     */
    suspend fun transcribe(chunk: AudioChunk, previousText: String): String
}

/**
 * Every way a transcription can fail, with a message meant for the user.
 *
 * [retryable] says whether trying the same chunk again could help.
 * [fatal] says whether there is no point sending further chunks at all
 * (for example, a wrong API key will fail for every chunk).
 */
sealed class TranscriptionError(
    val userMessage: String,
    val retryable: Boolean,
    val fatal: Boolean = false,
) : Exception(userMessage) {

    class NoNetwork : TranscriptionError(
        "No network connection. Check Wi-Fi or mobile data.", retryable = true)

    class InvalidApiKey : TranscriptionError(
        "OpenAI rejected the API key. Open the app and enter a valid key.",
        retryable = false, fatal = true)

    /** @param retryAfterMs how long OpenAI asked us to wait, if it said */
    class RateLimited(val retryAfterMs: Long?) : TranscriptionError(
        "OpenAI rate limit reached. Slowing down.", retryable = true)

    class QuotaExceeded : TranscriptionError(
        "Your OpenAI account has no credit left (quota exceeded).",
        retryable = false, fatal = true)

    class ServerError(code: Int) : TranscriptionError(
        "OpenAI server error ($code).", retryable = true)

    class Rejected(code: Int, detail: String) : TranscriptionError(
        "OpenAI refused this audio ($code): $detail", retryable = false)

    class Unexpected(detail: String) : TranscriptionError(
        "Unexpected error: $detail", retryable = true)
}
