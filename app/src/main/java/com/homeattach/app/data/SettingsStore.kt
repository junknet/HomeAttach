package com.homeattach.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Connection settings needed to reach the home PC over SSH. */
data class HostConfig(
    val host: String,
    val port: Int,
    val username: String,
    val privateKeyPem: String,
) {
    val isValid: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && privateKeyPem.isNotBlank() && port in 1..65535
}

/**
 * Persists [HostConfig] in EncryptedSharedPreferences (AES256-GCM/SIV backed by an
 * Android Keystore master key) so the private key material never touches plaintext storage.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "homeattach_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): HostConfig = HostConfig(
        host = prefs.getString(KEY_HOST, "") ?: "",
        port = prefs.getInt(KEY_PORT, 22),
        username = prefs.getString(KEY_USERNAME, "") ?: "",
        privateKeyPem = prefs.getString(KEY_KEY_PEM, "") ?: "",
    )

    fun save(config: HostConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_KEY_PEM, config.privateKeyPem)
            .apply()
    }

    fun isConfigured(): Boolean = load().isValid

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_KEY_PEM = "key_pem"
    }
}
