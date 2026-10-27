package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.egrie.soundtrail.MainSources

/**
 * How credentials are stored, and what removes them.
 *
 * These are guarantees about code that has to stay a certain way rather than about a value a
 * function returns, so they are checked by reading the source — the same approach as
 * `BackupRulesTest`, and the only one available without a device: the Android Keystore does not
 * exist on a JVM, so `SecretStore` cannot be exercised here, only inspected.
 *
 * What is being protected against is specific and quiet. A plaintext fallback would still work,
 * still pass every functional test, and would mean the Jellyfin access token, the mood provider
 * API keys and the sync token and recovery key were sitting in a preferences file readable by any
 * backup that included it. And a sign-out that removed the token but not the recovery key would
 * leave the thing that decrypts the whole library behind on a device the listener believes they
 * signed out of.
 */
class SecretStorePolicyTest {

    private val secretStore = MainSources.read("integrations/SecretStore.kt")
    private val cloudSync = MainSources.read("sync/CloudSyncService.kt")

    // ----- Keystore-backed, with no plaintext fallback -----

    @Test fun `secrets are wrapped with a non-exportable Android Keystore key`() {
        assertTrue(
            "SecretStore must get its key from the Android Keystore",
            secretStore.contains("KeyStore.getInstance(\"AndroidKeyStore\")")
        )
        assertTrue(
            "SecretStore must generate an AES key in the Keystore",
            secretStore.contains("KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, \"AndroidKeyStore\")")
        )
        assertTrue(
            "the key must not be exportable, and must be for encrypt/decrypt only",
            secretStore.contains("KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT")
        )
        assertTrue(secretStore.contains("KeyProperties.BLOCK_MODE_GCM"))
        assertTrue(secretStore.contains("KeyProperties.ENCRYPTION_PADDING_NONE"))
        assertTrue(secretStore.contains("setKeySize(256)"))
        assertTrue(secretStore.contains("Cipher.getInstance(\"AES/GCM/NoPadding\")"))
    }

    @Test fun `no third-party or preference-based encryption is substituted`() {
        // EncryptedSharedPreferences and Jetpack Security are the usual shortcut, and they keep a
        // master key that a rooted device or an included backup can lift. This store does not use
        // them; if it ever does, that decision should be made deliberately and not by an import.
        assertFalse(secretStore.contains("EncryptedSharedPreferences"))
        assertFalse(secretStore.contains("MasterKey"))
        assertFalse(secretStore.contains("androidx.security"))
    }

    @Test fun `every stored value is ciphertext, never the plaintext it was given`() {
        val writes = secretStore.lines()
            .map { it.trim() }
            .filter { it.contains("putString(") }
        assertTrue("SecretStore should still write its values", writes.isNotEmpty())
        writes.forEach { line ->
            assertTrue(
                "a secret must be written as Base64 of iv+ciphertext, but found: $line",
                line.contains("Base64.encodeToString(cipher.iv + encrypted")
            )
            assertFalse("a raw value must never be written: $line", line.contains("putString(name, value)"))
        }
    }

    @Test fun `writing is durable, because a rotating token must not be the one a crash lost`() {
        val putAll = MainSources.bodyOf(secretStore, "fun putAll(values: Map<String, String>): Boolean {")
            ?: fail("could not find putAll in SecretStore.kt") as Nothing
        assertTrue(
            "putAll must commit rather than apply, so the value is on disk before the caller uses it",
            putAll.contains("editor.commit()")
        )
        assertFalse("putAll must not use apply()", putAll.contains(".apply()"))

        val remove = MainSources.bodyOf(secretStore, "fun remove(name: String) {")
            ?: fail("could not find remove in SecretStore.kt") as Nothing
        assertTrue("remove must commit too", remove.contains(".commit()"))
    }

    @Test fun `a token and its session id are written in one commit`() {
        // The server revokes the old token the moment it issues a new one, so a crash between two
        // writes would leave the app holding half a session. One putAll is one commit.
        val store = MainSources.bodyOf(cloudSync, "private fun storeSession(")
            ?: fail("could not find storeSession in CloudSyncService.kt") as Nothing
        assertEquals(
            "storeSession must write the token and the session id in a single putAll",
            1, Regex("secrets\\.putAll\\(").findAll(store).count()
        )
        assertTrue(store.contains("TOKEN_KEY to token"))
        assertTrue(store.contains("SESSION_ID_KEY to sessionId"))
        assertFalse("storeSession must not write them one at a time", store.contains("secrets.put("))
    }

    @Test fun `a value this device cannot decrypt is reported, never deleted`() {
        assertTrue(
            "a failed decrypt must be swallowed, not thrown",
            secretStore.contains("private fun decrypt(stored: String): String? = runCatching")
        )
        val decrypt = MainSources.bodyOf(secretStore, "private fun decrypt(stored: String): String?")
            ?: fail("could not find decrypt in SecretStore.kt") as Nothing
        assertFalse("a failed decrypt must never delete the ciphertext", decrypt.contains("remove("))
        assertFalse(decrypt.contains("putString("))
    }

    // ----- what a sign-out and an account deletion remove -----

    @Test fun `signing out removes the token and the recovery key`() {
        val body = MainSources.bodyOf(cloudSync, "suspend fun disconnect() {")
            ?: fail("could not find disconnect() in CloudSyncService.kt") as Nothing
        assertRemovesEverything(body, "disconnect()")
    }

    @Test fun `deleting the account removes the token and the recovery key`() {
        val body = MainSources.bodyOf(cloudSync, "suspend fun deleteAccount() {")
            ?: fail("could not find deleteAccount() in CloudSyncService.kt") as Nothing
        assertRemovesEverything(body, "deleteAccount()")
    }

    @Test fun `losing a session removes the token but keeps the recovery key`() {
        // The other direction matters as much: the key belongs to the account, not to the session,
        // so a revoked session must not cost the listener the key that decrypts their library.
        val body = MainSources.bodyOf(cloudSync, "fun forgetSession() {")
            ?: fail("could not find forgetSession() in CloudSyncService.kt") as Nothing
        assertTrue(body.contains("secrets.remove(TOKEN_KEY)"))
        assertTrue(body.contains("secrets.remove(SESSION_ID_KEY)"))
        assertFalse(
            "forgetSession must not remove the recovery key",
            body.contains("secrets.remove(RECOVERY_KEY)")
        )
    }

    @Test fun `the Keystore alias is the historical one, and renaming it would strand every secret`() {
        // The alias names key material, not contents. Changing this string does not migrate
        // anything: every secret already on a device becomes UNREADABLE at once — the Jellyfin
        // token, the mood provider keys, and the sync token and recovery key together.
        assertTrue(
            "if this assertion fails, the Keystore alias was changed, which signs every upgraded " +
                "device out of everything. Migrate deliberately or revert.",
            secretStore.contains("private val alias = \"soundtrail_spotify_aes_v1\"")
        )
    }

    @Test fun `the credential names nothing else may use are documented`() {
        // Spotify stored its token under the bare name "token" and its pending sign-in under
        // "pending". Those keys are deleted on upgrade and may never be reused, or a new
        // integration would collide with a value an old install still has.
        val cleanup = MainSources.read("data/LegacyServiceCleanup.kt")
        assertNotNull(cleanup)
        listOf("\"token\"", "\"pending\"", "\"sc_token\"", "\"youtube_oauth_token\"").forEach { key ->
            assertTrue("the upgrade cleanup must still delete $key", cleanup.contains(key))
        }
    }

    private fun assertRemovesEverything(body: String, what: String) {
        listOf("TOKEN_KEY", "SESSION_ID_KEY", "RECOVERY_KEY").forEach { key ->
            assertTrue(
                "$what must remove $key, or a credential outlives the sign-out that should have " +
                    "removed it",
                body.contains("secrets.remove($key)")
            )
        }
    }
}
