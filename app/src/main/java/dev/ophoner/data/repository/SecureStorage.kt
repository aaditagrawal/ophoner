package dev.ophoner.data.repository

// NOTE: Requires `androidx.security:security-crypto:1.1.0-alpha06` in app/build.gradle.kts.
// Another agent owns that file; ensure this dependency is present there.

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper wrapping [EncryptedSharedPreferences] with a hardware-backed [MasterKey]
 * (AES256-GCM). Used for storing sensitive values such as provider API keys,
 * which must never be written to plaintext DataStore or external storage.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "ophoner_secure_prefs"
    }
}
