package com.reeltranscribe.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.reeltranscribe.MainActivity
import com.reeltranscribe.data.CaptureStatus
import com.reeltranscribe.data.SecureSettings
import com.reeltranscribe.data.SessionState

/**
 * An invisible screen whose only job is to show Android's
 * "Start recording or casting?" permission dialog.
 *
 * Android only lets an Activity (not a service or the bubble) ask for this,
 * and it must be asked every time a capture starts.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private var testMode = false

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data, testMode)
            // Stay (invisibly) open until the service has started. Android only lets a
            // service begin audio capture while the app has a screen showing.
            lifecycleScope.launch {
                withTimeoutOrNull(3_000) {
                    SessionState.status.first { it != CaptureStatus.WaitingForPermission }
                }
                finish()
            }
        } else {
            SessionState.setStatus(CaptureStatus.Idle)
            SessionState.showMessage("Screen-capture permission denied, so nothing was captured. Tap the bubble to try again.")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testMode = intent.getBooleanExtra(EXTRA_TEST, false)
        if (savedInstanceState != null) return // dialog already showing (e.g. after rotation)

        // Check the things the capture needs before bothering you with the dialog.
        val problem = when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED ->
                "Permission denied: the \"audio recording\" permission is needed for playback capture. Grant it in the app."
            !testMode && SecureSettings(this).apiKey == null ->
                "No OpenAI API key saved yet. Add it in the app."
            else -> null
        }
        if (problem != null) {
            SessionState.setStatus(CaptureStatus.Idle)
            SessionState.showMessage(problem)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        SessionState.setStatus(CaptureStatus.WaitingForPermission)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: skip the "one app / entire screen" choice. Only audio is used,
            // and capturing the whole device is what lets us hear Instagram.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        consent.launch(request)
    }

    companion object {
        private const val EXTRA_TEST = "test"

        fun launch(context: Context, test: Boolean) {
            context.startActivity(
                Intent(context, ProjectionRequestActivity::class.java)
                    .putExtra(EXTRA_TEST, test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
