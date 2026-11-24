package com.reeltranscribe.overlay

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltranscribe.data.CaptureStatus

private val Recording = Color(0xFFD32F2F)
private val Idle = Color(0xFF3F51B5)

/**
 * The round floating button. [onTouch] receives raw touch events so the
 * service can tell a tap (start) from a drag (move the bubble).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Bubble(status: CaptureStatus, onTouch: (MotionEvent) -> Boolean) {
    val active = status != CaptureStatus.Idle
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (active) Recording else Idle)
            .pointerInteropFilter(onTouchEvent = onTouch),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (active) "●" else "Aa",
            color = Color.White,
            fontSize = if (active) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The transcript panel with Copy / Clear / Stop. */
@Composable
fun TranscriptPanel(
    status: CaptureStatus,
    transcript: String,
    message: String?,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xEE1E1E1E),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                // Header: status + close (×)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        statusLabel(status),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (status == CaptureStatus.Idle) Color.LightGray else Color(0xFFFF8A80),
                    )
                    TextButton(onClick = onClose) { Text("Hide") }
                }

                // Errors / notices
                if (message != null) {
                    Text(
                        message,
                        color = Color(0xFFFFD54F),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // Transcript, scrolled to the newest text
                val scroll = rememberScrollState()
                LaunchedEffect(transcript) { scroll.animateScrollTo(scroll.maxValue) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 220.dp)
                        .verticalScroll(scroll),
                ) {
                    SelectionContainer {
                        Text(
                            transcript.ifEmpty { "The transcript will appear here, about every 20 seconds." },
                            color = if (transcript.isEmpty()) Color.Gray else Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onCopy, enabled = transcript.isNotEmpty()) { Text("Copy") }
                    OutlinedButton(onClick = onClear, enabled = transcript.isNotEmpty()) { Text("Clear") }
                    Button(
                        onClick = onStop,
                        enabled = status == CaptureStatus.Recording || status == CaptureStatus.Testing,
                        colors = ButtonDefaults.buttonColors(containerColor = Recording),
                    ) { Text("Stop") }
                }
            }
        }
    }
}

private fun statusLabel(status: CaptureStatus) = when (status) {
    CaptureStatus.Idle -> "Stopped"
    CaptureStatus.WaitingForPermission -> "Waiting for permission…"
    CaptureStatus.Recording -> "● Listening"
    CaptureStatus.Testing -> "● Capture test (10 s)"
    CaptureStatus.Finishing -> "Finishing the last chunks…"
}
