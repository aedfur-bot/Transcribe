package com.reeltranscribe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the OpenAI API key encrypted on the phone.
 *
 * The encryption key lives in the Android Keystore (hardware-backed on the
 * Pixel), so the API key file is unreadable even if copied off the phone.
 * The app also opts out of backups (see AndroidManifest.xml), so the key
 * never leaves the device.
 *
 * Note: Google has marked EncryptedSharedPreferences as deprecated, but it
 * still works and is exactly what was asked for. It is used only here.
 */
@Suppress("DEPRECATION")
class SecureSettings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value.trim())
            }
        }

    private companion object {
        const val KEY_API = "openai_api_key"
    }
}
