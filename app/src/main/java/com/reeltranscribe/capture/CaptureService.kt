package com.reeltranscribe.capture

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.reeltranscribe.Notifications
import com.reeltranscribe.audio.AudioChunk
import com.reeltranscribe.audio.AudioLevels
import com.reeltranscribe.audio.Chunker
import com.reeltranscribe.audio.SilenceProbe
import com.reeltranscribe.data.CaptureStatus
import com.reeltranscribe.data.SecureSettings
import com.reeltranscribe.data.SessionState
import com.reeltranscribe.transcribe.ChunkOutcome
import com.reeltranscribe.transcribe.OpenAiTranscriber
import com.reeltranscribe.transcribe.TranscriptFormatter
import com.reeltranscribe.transcribe.transcribeWithOneRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The background worker that records Instagram's audio and gets it transcribed.
 *
 * It runs as a "foreground service" (with a notification), which is what
 * Android requires for screen/audio capture. Two jobs run side by side:
 *
 *   recorder job:  AudioRecord -> Chunker -> queue of 20-second chunks
 *   upload job:    queue -> OpenAI (one chunk at a time, in order) -> transcript
 *
 * Because the upload job takes chunks one by one from the queue, the
 * transcript is always in the right order.
 *
 * Stopping: the recorder stops at once and MediaProjection is released;
 * chunks already recorded are still transcribed, then the service ends.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var recordJob: Job? = null

    /** Set to false to ask the recorder loop to finish. */
    @Volatile private var keepRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> if (recordJob == null) start(intent) // ignore double starts
        }
        return START_NOT_STICKY // never restart by itself after being killed
    }

    private fun start(intent: Intent) {
        val testMode = intent.getBooleanExtra(EXTRA_TEST, false)

        // Android 14+ rule: we must be a foreground service of type "mediaProjection"
        // BEFORE asking for the MediaProjection object.
        goForeground(if (testMode) "Running 10-second capture test…" else "Listening… tap Stop when done.")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = data?.let { runCatching { mpm.getMediaProjection(resultCode, it) }.getOrNull() }
        if (mp == null) {
            fail("Screen-capture permission was not granted.")
            return
        }
        projection = mp
        // Called if Android ends the capture (e.g. you tap "Stop sharing" or lock the screen).
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (keepRecording) SessionState.showMessage("Android stopped the capture (screen locked or sharing stopped).")
                keepRecording = false
            }
        }, Handler(Looper.getMainLooper()))

        val instagramUid = instagramUid()
        if (instagramUid == null) {
            fail("Instagram is not installed, so there is nothing to capture.")
            return
        }

        val recorder = try {
            PlaybackRecorder(mp, instagramUid)
        } catch (e: Exception) {
            fail("Capture blocked: ${e.message}")
            return
        }

        keepRecording = true
        if (testMode) runCaptureTest(recorder) else runTranscription(recorder)
    }

    // ---------------------------------------------------------------- capture test

    /**
     * The "first step" check: record Instagram for 10 seconds and see
     * whether anything other than silence came through.
     */
    private fun runCaptureTest(recorder: PlaybackRecorder) {
        SessionState.setStatus(CaptureStatus.Testing)
        SessionState.showMessage("Testing… keep the reel playing with sound on.")
        // Ignore the first 3 s: the permission dialog may have paused the reel.
        val probe = SilenceProbe(recorder.sampleRate, skipSeconds = TEST_SKIP_SECONDS, listenSeconds = 10.0)

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                recorder.start()
                while (keepRecording && !probe.isDone) {
                    val samples = recorder.read() ?: break
                    probe.add(samples)
                }
            } finally {
                releaseCapture(recorder)
            }
            val result = when {
                !probe.isDone -> "Test did not finish (it was stopped early). Run it again."
                probe.wasSilent -> SILENT_TEST_RESULT
                else -> "PASS — sound was captured from Instagram " +
                    "(peak level %.0f dBFS). Instagram allows audio playback capture on this device, so transcription can work."
                        .format(AudioLevels.peakToDbfs(probe.peak))
            }
            SessionState.setTestResult(result)
            SessionState.showMessage(result)
            finishService()
        }
    }

    // ---------------------------------------------------------------- transcription

    private fun runTranscription(recorder: PlaybackRecorder) {
        val apiKey = SecureSettings(this).apiKey
        if (apiKey == null) {
            releaseCapture(recorder)
            fail("No OpenAI API key saved. Open the app and add one.")
            return
        }
        val transcriber = OpenAiTranscriber(apiKey, ::isOnline)
        val queue = Channel<AudioChunk>(Channel.UNLIMITED)
        SessionState.setStatus(CaptureStatus.Recording)
        SessionState.showMessage(null)

        // Job 1: record and cut into chunks.
        recordJob = scope.launch(Dispatchers.IO) {
            val chunker = Chunker(recorder.sampleRate, chunkSeconds = CHUNK_SECONDS)
            val probe = SilenceProbe(recorder.sampleRate, listenSeconds = 10.0)
            var silenceWarningShown = false
            try {
                recorder.start()
                while (keepRecording) {
                    val samples = recorder.read()
                    if (samples == null) {
                        SessionState.showMessage("Capture stopped: the audio recorder reported an error.")
                        break
                    }
                    // Warn once if the first 10 s are pure silence; clear the warning when sound arrives.
                    probe.add(samples)
                    if (probe.isDone && probe.wasSilent && !silenceWarningShown) {
                        SessionState.showMessage(SILENT_WARNING)
                        silenceWarningShown = true
                    } else if (silenceWarningShown && !AudioLevels.isSilent(samples)) {
                        if (SessionState.message.value == SILENT_WARNING) SessionState.showMessage(null)
                        silenceWarningShown = false
                    }
                    chunker.add(samples).forEach { queue.send(it) }
                }
                chunker.flush()?.let { queue.send(it) } // the last, shorter piece
            } finally {
                queue.close()
                releaseCapture(recorder) // MediaProjection is released as soon as recording ends
                if (SessionState.status.value == CaptureStatus.Recording) {
                    SessionState.setStatus(CaptureStatus.Finishing)
                }
            }
        }

        // Job 2: send chunks to OpenAI, one at a time, in order.
        scope.launch {
            for (chunk in queue) {
                // Don't pay to transcribe silence (Whisper also tends to invent text for it).
                if (AudioLevels.isSilent(chunk.samples)) continue

                val outcome = transcribeWithOneRetry(
                    chunk,
                    previousText = TranscriptFormatter.contextTail(SessionState.transcript.value),
                    transcriber = transcriber,
                )
                SessionState.updateTranscript { TranscriptFormatter.append(it, outcome) }

                when (outcome) {
                    is ChunkOutcome.Success -> clearTransientError()
                    is ChunkOutcome.Missing -> {
                        SessionState.showMessage(outcome.error.userMessage)
                        if (outcome.error.fatal) {
                            // e.g. wrong API key: every chunk would fail, so stop now.
                            keepRecording = false
                            break
                        }
                    }
                }
            }
            finishService()
        }
    }

    /** After a successful chunk, hide an earlier network/rate-limit message. */
    private fun clearTransientError() {
        val msg = SessionState.message.value ?: return
        if (msg != SILENT_WARNING) SessionState.showMessage(null)
    }

    // ---------------------------------------------------------------- helpers

    /** Stop button: stop recording; queued chunks still get transcribed. */
    private fun requestStop() {
        if (recordJob == null) { finishService(); return }
        keepRecording = false
    }

    private fun goForeground(text: String) {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // The microphone type is needed because Android treats any audio capture as
        // "recording". This app never records the microphone itself.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(
            this, Notifications.ID_CAPTURE, Notifications.capture(this, text, stop), type,
        )
    }

    @Synchronized
    private fun releaseCapture(recorder: PlaybackRecorder?) {
        keepRecording = false
        runCatching { recorder?.release() }
        projection?.let { runCatching { it.stop() } }
        projection = null
    }

    private fun fail(message: String) {
        SessionState.showMessage(message)
        releaseCapture(null)
        finishService()
    }

    private fun finishService() {
        SessionState.setStatus(CaptureStatus.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun instagramUid(): Int? =
        runCatching { packageManager.getApplicationInfo(INSTAGRAM_PACKAGE, 0).uid }.getOrNull()

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        keepRecording = false
        releaseCapture(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val CHUNK_SECONDS = 20.0
        const val TEST_SKIP_SECONDS = 3.0

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_TEST = "test"

        const val SILENT_WARNING =
            "No sound captured in the first 10 s. Is the reel playing with sound on? " +
                "If it is, Instagram is blocking audio capture on this device."

        const val SILENT_TEST_RESULT =
            "SILENT — the captured audio was completely silent for 10 seconds. If the reel was playing " +
                "with sound on, Instagram opts out of audio playback capture on this device, and this app " +
                "cannot transcribe it. (By design, it will not record the microphone or download videos instead.)"

        fun start(context: Context, resultCode: Int, data: Intent, test: Boolean) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_TEST, test)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
