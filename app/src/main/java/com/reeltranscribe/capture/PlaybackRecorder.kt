package com.reeltranscribe.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.reeltranscribe.audio.Downsampler

/** Thrown when Android refuses to set up playback capture at all. */
class CaptureSetupException(message: String) : Exception(message)

/**
 * Records the sound another app is playing (not the microphone!),
 * using Android's AudioPlaybackCapture feature (Android 10+).
 *
 * Only audio played by [targetUid] (Instagram's app id) is captured, so
 * notification sounds and other apps don't end up in the transcript.
 *
 * Output: 16 kHz mono 16-bit samples, delivered ~0.1 s at a time by [read].
 */
class PlaybackRecorder(projection: MediaProjection, targetUid: Int) {

    val sampleRate = TARGET_RATE

    private val record: AudioRecord
    private val downsampleFactor: Int
    private val readBuffer: ShortArray

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(targetUid)
            .build()
        // Ask for 16 kHz directly; if this phone refuses, record at 48 kHz and convert.
        val (rec, factor) = tryCreate(config, TARGET_RATE)?.let { it to 1 }
            ?: tryCreate(config, 48_000)?.let { it to 3 }
            ?: throw CaptureSetupException("Android refused to start audio playback capture.")
        record = rec
        downsampleFactor = factor
        // 0.1 s worth of samples at the recording rate (a multiple of the factor).
        readBuffer = ShortArray(TARGET_RATE / 10 * factor)
    }

    fun start() = record.startRecording()

    /**
     * Blocks until ~0.1 s of audio is available and returns it at 16 kHz.
     * Returns null if the recorder failed (for example, capture was revoked).
     */
    fun read(): ShortArray? {
        val n = record.read(readBuffer, 0, readBuffer.size)
        if (n < 0) return null
        return Downsampler.downsample(readBuffer, n - n % downsampleFactor, downsampleFactor)
    }

    fun release() {
        runCatching { record.stop() }
        record.release()
    }

    // RECORD_AUDIO is checked before this class is ever used (see ProjectionRequestActivity).
    @SuppressLint("MissingPermission")
    private fun tryCreate(config: AudioPlaybackCaptureConfiguration, rate: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, rate)) // ~0.5 s buffer
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val TARGET_RATE = 16_000
    }
}
