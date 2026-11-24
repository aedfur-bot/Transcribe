package com.reeltranscribe.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the capture service is doing right now. */
enum class CaptureStatus { Idle, WaitingForPermission, Recording, Testing, Finishing }

/**
 * The app's live state, kept ONLY in memory.
 *
 * Nothing here is ever written to disk: when the app process ends,
 * the transcript is gone. That is how "no history" is guaranteed.
 *
 * Screens and services observe these values; when one changes,
 * the UI updates by itself.
 */
object SessionState {
    private val _status = MutableStateFlow(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** Latest problem or notice to show the user (null = nothing to show). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Result of the last 10-second capture test (null = not run yet). */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** When true, the next bubble tap runs the capture test instead of transcribing. */
    private val _testArmed = MutableStateFlow(false)
    val testArmed: StateFlow<Boolean> = _testArmed.asStateFlow()

    fun setStatus(s: CaptureStatus) { _status.value = s }
    fun updateTranscript(change: (String) -> String) = _transcript.update(change)
    fun clearTranscript() { _transcript.value = "" }
    fun showMessage(text: String?) { _message.value = text }
    fun setTestResult(text: String) { _testResult.value = text }
    fun armTest(armed: Boolean) { _testArmed.value = armed }

    /** Returns true (once) if a test was armed, and disarms it. */
    fun consumeTestArmed(): Boolean {
        val wasArmed = _testArmed.value
        _testArmed.value = false
        return wasArmed
    }
}
