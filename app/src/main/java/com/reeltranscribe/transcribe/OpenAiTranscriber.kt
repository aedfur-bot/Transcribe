package com.reeltranscribe.transcribe

import com.reeltranscribe.audio.AudioChunk
import com.reeltranscribe.audio.WavEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends audio to OpenAI's transcription endpoint (model "whisper-1").
 * Docs: https://platform.openai.com/docs/api-reference/audio/createTranscription
 *
 * @param apiKey   read from encrypted storage, never hard-coded
 * @param isOnline quick check so "no network" is reported clearly
 */
class OpenAiTranscriber(
    private val apiKey: String,
    private val isOnline: () -> Boolean,
) : Transcriber {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(chunk: AudioChunk, previousText: String): String =
        withContext(Dispatchers.IO) {
            if (!isOnline()) throw TranscriptionError.NoNetwork()

            val wav = WavEncoder.encode(chunk.samples, chunk.sampleRate)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "text") // plain text back, no JSON to parse
                .apply { if (previousText.isNotBlank()) addFormDataPart("prompt", previousText) }
                .addFormDataPart(
                    "file", "chunk${chunk.index}.wav",
                    wav.toRequestBody("audio/wav".toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful -> text.trim()
                        response.code == 401 -> throw TranscriptionError.InvalidApiKey()
                        response.code == 429 && text.contains("insufficient_quota") ->
                            throw TranscriptionError.QuotaExceeded()
                        response.code == 429 -> throw TranscriptionError.RateLimited(
                            response.header("Retry-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                        )
                        response.code >= 500 -> throw TranscriptionError.ServerError(response.code)
                        else -> throw TranscriptionError.Rejected(response.code, text.take(200))
                    }
                }
            } catch (e: IOException) {
                // DNS failure, connection refused, timeout... all look like "network" to the user.
                throw TranscriptionError.NoNetwork()
            }
        }
}
