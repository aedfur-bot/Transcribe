package com.reeltranscribe.overlay

import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reeltranscribe.Notifications
import com.reeltranscribe.capture.CaptureService
import com.reeltranscribe.capture.ProjectionRequestActivity
import com.reeltranscribe.data.CaptureStatus
import com.reeltranscribe.data.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * Shows the floating bubble (and the transcript panel) on top of other apps.
 *
 * Tap the bubble:
 *   - not capturing  -> opens the panel and starts a capture
 *   - capturing      -> shows/hides the panel
 * Drag the bubble to move it.
 *
 * Runs as a foreground service so Android doesn't remove the bubble
 * while you're in Instagram.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()

    private var bubbleView: View? = null
    private var panelView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        lifecycleOwner.create()
        goForeground()
        isRunning.value = true
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
        } else if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permission denied: allow \"Display over other apps\" first.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        bubbleParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 72 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.45).toInt()
        }
        val view = composeView {
            val status by SessionState.status.collectAsState()
            Bubble(status, ::onBubbleTouch)
        }
        windowManager.addView(view, bubbleParams)
        bubbleView = view
    }

    // Tap vs. drag detection, using screen ("raw") coordinates.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    private fun onBubbleTouch(e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                downX = bubbleParams.x; downY = bubbleParams.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) > slop || abs(dy) > slop) dragging = true
                if (dragging) {
                    bubbleParams.x = downX + dx.toInt()
                    bubbleParams.y = downY + dy.toInt()
                    bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
                }
            }
            MotionEvent.ACTION_UP -> if (!dragging) onBubbleTap()
        }
        return true
    }

    private fun onBubbleTap() {
        if (SessionState.status.value != CaptureStatus.Idle) {
            // Already capturing: the bubble just shows/hides the panel.
            if (panelView == null) showPanel() else hidePanel()
            return
        }
        showPanel()
        SessionState.showMessage(null)
        val test = SessionState.consumeTestArmed()
        ProjectionRequestActivity.launch(this, test)
    }

    // ---------------------------------------------------------------- panel

    private fun showPanel() {
        if (panelView != null) return
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP
            y = (40 * resources.displayMetrics.density).toInt() // just below the status bar
        }
        val view = composeView {
            val status by SessionState.status.collectAsState()
            val transcript by SessionState.transcript.collectAsState()
            val message by SessionState.message.collectAsState()
            TranscriptPanel(
                status = status,
                transcript = transcript,
                message = message,
                onCopy = ::copyTranscript,
                onClear = { SessionState.clearTranscript() },
                onStop = { CaptureService.stop(this) },
                onClose = ::hidePanel,
            )
        }
        windowManager.addView(view, params)
        panelView = view
    }

    private fun hidePanel() {
        panelView?.let { windowManager.removeView(it) }
        panelView = null
    }

    private fun copyTranscript() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Reel transcript", SessionState.transcript.value))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Window settings for an overlay that doesn't steal the keyboard or block touches elsewhere. */
    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent(content)
        }

    private fun goForeground() {
        val hide = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notifications.bubble(this, hide)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, Notifications.ID_BUBBLE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_BUBBLE, notification)
        }
    }

    override fun onDestroy() {
        hidePanel()
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        lifecycleOwner.destroy()
        isRunning.value = false
        // Turning the bubble off also stops any capture in progress.
        if (SessionState.status.value != CaptureStatus.Idle) CaptureService.stop(this)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HIDE = "hide"

        /** Whether the bubble is currently shown (for the switch in the app). */
        val isRunning = MutableStateFlow(false)

        fun show(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
