package tech.egrie.soundtrail.integrations

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What is stored under a name, as far as this device's key store can tell. */
internal enum class SecretState {
    /** Nothing was ever saved, or the listener disconnected. */
    ABSENT,

    /** Saved and readable right now. */
    READABLE,

    /** Saved, but this device's key store could not decrypt it. Never deleted automatically. */
    UNREADABLE
}

/**
 * Secrets encrypted at rest with a non-exportable Android Keystore key; app backups are disabled.
 *
 * Two deliberate properties make stored logins survive app updates and restarts:
 *
 * 1. **Writes are durable.** [put] and [putAll] use `commit()`, so a token is on disk before the
 *    caller uses it. That matters for rotating refresh tokens: a token that Google has already
 *    replaced must never be the one the app lost in a crash. Android keeps this key and the
 *    preferences across updates, so a build installed over an older one stays signed in.
 *
 * 2. **Reads never destroy anything.** If this device's key material cannot decrypt a value
 *    (for example a key store that was reset, or a backup restored onto a different device) the
 *    ciphertext is left in place and reported as [SecretState.UNREADABLE]. The app asks for a
 *    one-tap reconnect instead of quietly forgetting the account, which is what used to make
 *    people sign in to Google again after something went wrong.
 */
internal class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("soundtrail_secrets", Context.MODE_PRIVATE)
    private val alias = "soundtrail_spotify_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** Encrypts and commits [value]; returns true when it is durably on disk. */
    @Synchronized
    fun put(name: String, value: String): Boolean = putAll(mapOf(name to value))

    /** Writes several secrets (or one rotating token) in a single durable commit. */
    @Synchronized
    fun putAll(values: Map<String, String>): Boolean {
        if (values.isEmpty()) return true
        val editor = prefs.edit()
        values.forEach { (name, value) ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
        }
        return editor.commit()
    }

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return decrypt(stored)
    }

    @Synchronized
    fun remove(name: String) {
        prefs.edit().remove(name).commit()
    }

    fun has(name: String): Boolean = prefs.contains(name)

    /** Distinguishes "never saved" from "saved but unreadable", for honest connection status. */
    @Synchronized
    fun state(name: String): SecretState {
        val stored = prefs.getString(name, null) ?: return SecretState.ABSENT
        return if (decrypt(stored) != null) SecretState.READABLE else SecretState.UNREADABLE
    }

    /** Decryption is best-effort: an unreadable value is left alone, never deleted. */
    private fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        require(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrNull()
}
