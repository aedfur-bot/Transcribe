package com.reeltranscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.reeltranscribe.data.SecureSettings
import com.reeltranscribe.data.SessionState
import com.reeltranscribe.overlay.OverlayService

/**
 * The app's only screen: one-time setup.
 *   1. API key   2. permissions   3. capture test   4. bubble on/off
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SecureSettings

    /** Bumped every time the screen comes back, so permission states are re-read. */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannel(this)
        settings = SecureSettings(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { padding ->
                    SettingsScreen(Modifier.padding(padding), resumeCount)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    @Composable
    private fun SettingsScreen(modifier: Modifier, refresh: Int) {
        // Re-read permission state whenever `refresh` changes (i.e. on return to the app).
        val overlayOk = remember(refresh) { Settings.canDrawOverlays(this) }
        val audioOk = remember(refresh) { granted(Manifest.permission.RECORD_AUDIO) }
        val notifOk = remember(refresh) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        }
        var hasKey by remember { mutableStateOf(settings.apiKey != null) }
        val bubbleOn by OverlayService.isRunning.collectAsState()
        val testArmed by SessionState.testArmed.collectAsState()
        val testResult by SessionState.testResult.collectAsState()
        val message by SessionState.message.collectAsState()

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { resumeCount++ }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Reel Transcriber", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Transcribes the sound of an Instagram reel while it plays. " +
                    "Nothing is saved: the transcript disappears when the app closes.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (message != null) {
                Card(Modifier.fillMaxWidth()) {
                    Text(message!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }

            // ---- 1. API key
            Section("1. OpenAI API key") {
                val input = rememberTextFieldState()
                Text(
                    if (hasKey) "A key is saved (encrypted on this phone)." else "No key saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Hidden input (shown as dots), like a password.
                OutlinedSecureTextField(
                    state = input,
                    label = { Text(if (hasKey) "Replace key (sk-…)" else "Paste key (sk-…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        settings.apiKey = input.text.toString()
                        input.clearText()
                        hasKey = settings.apiKey != null
                    }, enabled = input.text.isNotBlank()) { Text("Save key") }
                    if (hasKey) {
                        OutlinedButton(onClick = {
                            settings.apiKey = null
                            hasKey = false
                        }) { Text("Delete key") }
                    }
                }
            }

            // ---- 2. Permissions
            Section("2. Permissions") {
                PermissionRow("Display over other apps (for the bubble)", overlayOk) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
                    )
                }
                PermissionRow("Audio recording (Android requires it for playback capture; the mic is never used)", audioOk) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow("Notifications (shows the Stop button)", notifOk) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                Text(
                    "Screen capture is asked each time you tap the bubble — Android requires that.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- 3. Bubble
            Section("3. Floating bubble") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show bubble over other apps", Modifier.weight(1f))
                    Switch(
                        checked = bubbleOn,
                        enabled = overlayOk,
                        onCheckedChange = { on ->
                            if (on) OverlayService.show(this@MainActivity) else OverlayService.hide(this@MainActivity)
                        },
                    )
                }
                if (!overlayOk) Text("Grant \"Display over other apps\" first.", style = MaterialTheme.typography.bodySmall)
            }

            // ---- 4. Capture test
            Section("4. Capture test (do this first)") {
                Text(
                    "Checks whether Instagram lets other apps capture its audio. " +
                        "Tap the button, open Instagram, play a reel with sound on, then tap the bubble. " +
                        "The test listens for about 13 seconds (the first 3 are ignored).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        SessionState.armTest(true)
                        if (!bubbleOn && overlayOk) OverlayService.show(this@MainActivity)
                    },
                    enabled = overlayOk && audioOk && !testArmed,
                ) { Text(if (testArmed) "Armed: next bubble tap runs the test" else "Arm capture test") }
                if (testResult != null) {
                    Text("Last result: $testResult", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.padding(8.dp))
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, ok: Boolean, onGrant: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (ok) "✅ " else "❌ ") + label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (!ok) OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
