# Reel Transcriber

A small Android app (Kotlin + Jetpack Compose) for a Pixel 8a. Tap a floating bubble while an
Instagram reel plays: the app captures the reel's **sound** (not the microphone), sends it to
OpenAI's `whisper-1` model in 20-second pieces, and shows the text in a panel with
**Copy**, **Clear** and **Stop** buttons.

Nothing is stored: no history, no database, no analytics, no ads, no login. The transcript lives
in memory only and disappears when the app closes. The only thing saved is your API key,
encrypted on the phone.

---

## 1. Open and build the project

1. Install **Android Studio** (latest stable): https://developer.android.com/studio
2. *File → Open…* and pick this folder (the one containing `settings.gradle.kts`).
3. Let Gradle sync finish. If Android Studio offers to install a missing SDK platform
   (Android 17 / API 37.2), accept it.
4. Build the APK: *Build → Generate App Bundles or APKs → Generate APKs*.
   The file appears at `app/build/outputs/apk/debug/app-debug.apk`.

Or, from a terminal in this folder:

```bash
./gradlew assembleDebug
```

To run the unit tests (chunking and retry logic):

```bash
./gradlew testDebugUnitTest
```

## 2. Install on the Pixel 8a

**One-time phone setup (USB debugging):**

1. *Settings → About phone* → tap **Build number** 7 times (enables Developer options).
2. *Settings → System → Developer options* → turn on **USB debugging**.
3. Connect the phone by USB and tap **Allow** on the "Allow USB debugging?" prompt.

**Install, either way:**

- In Android Studio, pick your Pixel in the device menu at the top and press ▶ **Run**, or
- From a terminal:

  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

(You can also copy the APK to the phone and open it in Files; Android will ask you to allow
installing from that app.)

## 3. Add your OpenAI API key

1. Create a key at https://platform.openai.com/api-keys (your account needs billing/credit).
2. Open **Reel Transcriber** → section **1. OpenAI API key** → paste the key → **Save key**.

The key is stored with `EncryptedSharedPreferences` (encryption key kept in the Android
Keystore) and excluded from backups. It is never in the source code and never shown again.
Use **Delete key** to remove it.

## 4. Grant permissions

In section **2. Permissions**, tap **Grant** next to each item:

| Permission | Why | How |
|---|---|---|
| Display over other apps | Shows the bubble and panel over Instagram | Opens a system page: find *Reel Transcriber* and switch it **on**, then press Back |
| Audio recording | Android requires it for *playback* capture. The microphone is never recorded. | Tap **Allow** (choose *While using the app*) |
| Notifications (Android 13+) | Shows the status notification with its **Stop** button | Tap **Allow** |
| Screen capture | Needed for audio playback capture | Asked **every time** you tap the bubble (Android rule): tap **Start** |

**"App was denied access" / "Restricted setting" when turning on Display over other apps:**
Android blocks this for apps installed from a downloaded file. Fix it once:
*Settings → Apps → See all apps → Reel Transcriber → ⋮ (top right) → Allow restricted settings*,
confirm with your PIN, then turn on *Display over other apps* again. (If there's no such menu
item, Advanced Protection may be on; installing over USB with `adb install` avoids the block.)

If you denied the audio permission twice, Android stops asking; enable it under
*Settings → Apps → Reel Transcriber → Permissions → Microphone*.

While capturing, Android shows its green privacy dot and a "sharing/recording" chip in the
status bar. This is normal: only Instagram's playback audio is captured.

## 5. First: run the capture test

This checks whether Instagram lets other apps capture its audio on your phone.

1. In section **4. Capture test**, tap **Arm capture test** (this also turns the bubble on).
2. Open Instagram and play a reel **with the sound on**.
3. Tap the bubble, then tap **Start** on Android's dialog.
4. Keep the reel playing for ~13 seconds (the first 3 seconds are ignored, in case the dialog
   paused the reel; tap the reel to resume it if so).
5. The result appears in the panel and in the app:
   - **PASS**: sound was captured, so transcription will work.
   - **SILENT**: the buffer was pure silence. If the reel really was playing with sound,
     **Instagram opts out of audio capture on this device** and this app can't transcribe it.
     By design, the app does **not** work around this (no microphone recording, no video
     downloading).

## 6. Use the bubble

1. Turn on **3. Floating bubble** (once; it stays on until you switch it off or use
   *Turn off bubble* in the notification).
2. In Instagram, play a reel. Tap the bubble (indigo "Aa"), then **Start** on the dialog.
3. The bubble turns red and the panel appears at the top. About every 20 seconds, new text is
   added.
4. **Copy** copies the whole transcript. **Clear** empties it. **Stop** ends the capture: it
   releases screen capture right away, finishes transcribing the audio already recorded, then
   the capture service stops. **Hide** closes the panel; tap the bubble to show it again.
5. Drag the bubble to move it.

## Messages you may see

| Message | Meaning |
|---|---|
| *Screen-capture permission denied* | You tapped Cancel on Android's dialog. Tap the bubble again. |
| *Permission denied: … audio recording* / *Display over other apps* | Grant it in the app (section 2). |
| *No sound captured in the first 10 s…* | The reel is paused or muted, **or** Instagram blocks capture. Clears itself once sound arrives. |
| *No network connection* | Phone is offline. That chunk is retried once, then marked missing. |
| *OpenAI rejected the API key* | Wrong or revoked key. Capture stops; enter a new key. |
| *OpenAI rate limit reached* | Too many requests; the app waits (as OpenAI asks, max 20 s) and retries once. |
| *Your OpenAI account has no credit left* | Add credit on platform.openai.com. Capture stops. |
| `[missing: part N — reason]` in the text | That 20-second piece failed twice; the rest continues normally. |

## How it works (for the curious)

```
Bubble tap ─► ProjectionRequestActivity (Android "Start recording?" dialog)
          └─► CaptureService (foreground service, notification)
                 AudioRecord + AudioPlaybackCapture (Instagram's app only, 16 kHz mono)
                   └─► Chunker (20 s pieces) ─► queue ─► OpenAI whisper-1 (one at a time, in order)
                                                          └─► retry once ─► transcript (memory only)
OverlayService shows the bubble + panel and observes the transcript.
```

| File | What it does |
|---|---|
| `MainActivity.kt` | Settings screen (key, permissions, bubble switch, capture test) |
| `overlay/OverlayService.kt`, `overlay/OverlayUi.kt` | Floating bubble and transcript panel |
| `capture/ProjectionRequestActivity.kt` | Asks Android for screen-capture permission |
| `capture/CaptureService.kt` | Records, chunks, uploads, handles Stop and errors |
| `capture/PlaybackRecorder.kt` | AudioPlaybackCapture wrapper |
| `audio/Chunker.kt` | Cuts the audio stream into 20 s chunks (unit-tested) |
| `audio/AudioLevels.kt` | Silence detection for the test and warnings (unit-tested) |
| `audio/WavEncoder.kt` | Wraps samples in a WAV file (unit-tested) |
| `transcribe/Retry.kt` | "Retry once, then mark missing" + transcript formatting (unit-tested) |
| `transcribe/OpenAiTranscriber.kt` | The HTTPS call to OpenAI, error mapping |
| `data/SecureSettings.kt` | Encrypted API key storage |
| `data/SessionState.kt` | In-memory state (transcript, status, messages) |

## Assumptions

1. **Audio format: WAV, not AAC.** 16 kHz mono 16-bit WAV is about 640 KB per 20 s chunk, far
   below OpenAI's 25 MB limit, and needs no encoder. (The spec allowed either.)
2. **Only Instagram's audio is captured.** The capture is filtered to Instagram's app ID
   (`com.instagram.android`), so other apps' sounds and notifications are excluded.
3. **"Entire screen" capture.** On Android 14+ the app asks Android to skip the "single app"
   choice. Only audio is used; no screen images are recorded or sent.
4. **The audio-recording permission is required** by Android for playback capture, and the
   capture service declares the "microphone" type. The microphone itself is never opened.
5. **Silent chunks are skipped** (not uploaded): this saves money, and Whisper tends to invent
   text for silence.
6. **Chunk boundaries can split a word.** To soften this, the end of the transcript so far is
   sent as Whisper's `prompt` so it continues sentences naturally. There's no overlap or
   de-duplication.
7. **A failing chunk is retried once** for temporary problems (network, rate limit, server
   error, unexpected error). A wrong API key or empty quota is not retried, and capture stops,
   because every later chunk would fail too.
8. **Language is auto-detected** by Whisper (no language setting).
9. **On Stop**, audio already recorded (including the last partial chunk, if ≥ 1 s) is still
   transcribed before the service ends.
10. **The bubble runs as a foreground service** with a notification, so Android doesn't remove
    it while you're in Instagram.
11. **Debug build for sideloading.** No Play Store release signing is set up; `minSdk 29`,
    `targetSdk 37` (Android 17), compiled against API 37.2.
12. `EncryptedSharedPreferences` is marked deprecated by Google but still works; it's used as
    requested.
