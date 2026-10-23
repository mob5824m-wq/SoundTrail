package tech.egrie.soundtrail.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.automation.AutomationStore
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.integrations.SecretStore
import tech.egrie.soundtrail.integrations.httpRequest
import java.net.URI
import java.net.URL
import java.util.UUID

class CloudSyncException(message: String) : Exception(message)

internal data class CloudSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val pins: Int,
    val playlists: Int,
    val automations: Int,
    val compactionWarning: String? = null
)

private data class CloudPullResult(
    val downloaded: Int,
    val retainedEvents: Int
)

/**
 * Client for the Soundtrail sync account service (public server by default; self-hosted servers
 * can be pointed to in the Connect tab). Authentication is separate from data encryption:
 * the account server authenticates email/password, but only a recovery key held by the user
 * can decrypt event payloads. Every network sync is a pull/merge/push/pull cycle over a
 * compactable event log with encrypted checkpoints; entity clocks and tombstones give
 * additions, edits, and deletes deterministic two-way conflict handling.
 */
internal class CloudSyncService(
    context: Context,
    private val pins: PinStore,
    private val playlists: PlaylistStore,
    private val automations: AutomationStore,
    private val files: FileStore
) {
    private val settings = context.getSharedPreferences("soundtrail_cloud_sync", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val journal = SyncJournal(context)

    /** The public server Soundtrail connects to by default when no custom server has been set. */
    val serverUrl: String get() = settings.getString("server", "").orEmpty()

    /**
     * The server that API calls actually go to. Falls back to the official public server
     * ([DEFAULT_SYNC_SERVER]) when the user hasn't entered a custom address, so a fresh install
     * can sign up or sign in straight away. Users who self-host replace this in the Connect tab.
     */
    val effectiveServerUrl: String
        get() = serverUrl.ifBlank { DEFAULT_SYNC_SERVER }

    val accountEmail: String get() = settings.getString("email", "").orEmpty()
    val accountId: String get() = settings.getString("account_id", "").orEmpty()
    val connected: Boolean get() = accountId.isNotBlank() && secrets.get(TOKEN_KEY).orEmpty().isNotBlank()
    val hasRecoveryKey: Boolean get() = secrets.get(RECOVERY_KEY).orEmpty().isNotBlank()
    val lastSyncAt: Long get() = settings.getLong("last_sync_at", 0L)
    val autoSyncEnabled: Boolean get() = settings.getBoolean("auto_sync_enabled", false)

    /** True when the user has entered a custom self-hosted URL instead of the public default. */
    val isUsingCustomServer: Boolean get() = serverUrl.isNotBlank() && serverUrl.trimEnd('/') != DEFAULT_SYNC_SERVER.trimEnd('/')

    fun setAutoSyncEnabled(enabled: Boolean) {
        settings.edit().putBoolean("auto_sync_enabled", enabled).apply()
    }

    /** Adds new local changes/tombstones to the device journal after an app mutation. */
    fun observeLocalChanges() {
        journal.observeLocal(localSnapshot())
    }

    /** Current user-visible pending entities; the encrypted key verifier is not a library change. */
    fun pendingChangeCount(): Int = journal.pendingLatest().count { it.record.kind != SYNC_KIND_KEY_CHECK }

    /** Creates an account, generates the user's random E2E recovery key, and returns it once. */
    suspend fun createAccount(rawServer: String, rawEmail: String, password: String): String {
        val base = normalizeServer(rawServer)
        val email = rawEmail.trim().lowercase()
        val response = api(base, "/v1/accounts", method = "POST", body = credentials(email, password))
        val key = SyncCrypto.newRecoveryKey()
        acceptAccount(base, email, response, SyncCrypto.encodeRecoveryKey(key), isRegistration = true)
        return SyncCrypto.encodeRecoveryKey(key)
    }

    /** Authenticates an existing account; a new device must enter its recovery key before syncing. */
    suspend fun signIn(rawServer: String, rawEmail: String, password: String) {
        val base = normalizeServer(rawServer)
        val email = rawEmail.trim().lowercase()
        val response = api(base, "/v1/sessions", method = "POST", body = credentials(email, password))
        acceptAccount(base, email, response, recoveryKey = null, isRegistration = false)
    }

    /** Stores an entered key only after strict 256-bit format validation. Sync verifies it by GCM. */
    fun unlockRecoveryKey(rawKey: String) {
        val key = try {
            SyncCrypto.decodeRecoveryKey(rawKey)
        } catch (e: Exception) {
            throw CloudSyncException(e.message ?: "That recovery key is not valid.")
        }
        secrets.put(RECOVERY_KEY, SyncCrypto.encodeRecoveryKey(key))
    }

    /** May be shown again on an already signed-in device; never sent to the account server. */
    fun recoveryKeyForDisplay(): String? = secrets.get(RECOVERY_KEY)

    /** Removes only this device's saved key so the user can replace a mistyped key. */
    fun forgetRecoveryKey() = secrets.remove(RECOVERY_KEY)

    /**
     * Executes encrypted synchronization. Local per-entity events are merged by Lamport clock;
     * playlist entries are independent entities, so simultaneous additions do not overwrite.
     */
    suspend fun sync(): CloudSyncResult {
        if (!connected) throw CloudSyncException("Sign in to your Soundtrail sync account first.")
        val encodedKey = secrets.get(RECOVERY_KEY)
            ?: throw CloudSyncException("Enter the recovery key you saved when you created the account.")
        val key = try {
            SyncCrypto.decodeRecoveryKey(encodedKey)
        } catch (_: Exception) {
            throw CloudSyncException("The saved recovery key is unreadable. Enter your saved key again.")
        }

        val beforeCursor = journal.cursor()
        val beforeRecords = journal.latest()
        // Capture local edits before pulling, then merge them and remote versions using the same
        // deterministic Lamport-clock/device-id rule on every device.
        journal.seed(localSnapshot())
        journal.observeLocal(localSnapshot())
        val firstPull = pullAll(key)
        var downloaded = firstPull.downloaded
        if (journal.latest()["$SYNC_KIND_KEY_CHECK:$KEY_CHECK_ID"] == null &&
            beforeCursor == 0L && beforeRecords.isEmpty() && downloaded == 0
        ) {
            // Account exists but no event has ever been synced. Establish the encrypted verifier
            // now; with no existing ciphertext there is no prior key that could be displaced.
            journal.record(
                SYNC_KIND_KEY_CHECK, KEY_CHECK_ID, deleted = false,
                payload = JSONObject().put("marker", KEY_CHECK_MARKER).toString()
            )
        }
        requireKeyCheck(journal.latest())

        val uploaded = try {
            pushPending(key)
        } catch (e: CloudSyncException) {
            if (!e.message.orEmpty().contains("storage limit", ignoreCase = true)) throw e
            val compacted = compactHistory(key, journal.cursor())
            if (!compacted) throw e
            val covered = journal.pendingLatest()
            journal.acknowledge(covered.map { it.eventId }.toSet())
            covered.size // The encrypted checkpoint contains these local changes.
        }
        // A concurrent device may have appended while we pushed. Pull from the last fully
        // processed cursor instead of jumping to the server's head based on a POST response.
        val secondPull = pullAll(key)
        downloaded += secondPull.downloaded
        requireKeyCheck(journal.latest())

        var compactionWarning: String? = null
        if (secondPull.retainedEvents >= COMPACT_AFTER_EVENTS && journal.pendingLatest().isEmpty()) {
            try {
                compactHistory(key, journal.cursor())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                compactionWarning = e.message ?: "The history checkpoint could not be compacted."
            }
        }

        val oldFiles = playlists.all().flatMap { it.items }.mapNotNull { it.localFile }.toSet()
        val restored = SyncPayloads.restore(journal.latest(), playlists.all(), files::exists)
        pins.replaceAll(restored.pins)
        playlists.replaceAll(restored.playlists)
        automations.replaceAll(restored.automations)
        val keptFiles = restored.playlists.flatMap { it.items }.mapNotNull { it.localFile }.toSet()
        (oldFiles - keptFiles).forEach(files::delete)
        journal.markLocalSnapshot(localSnapshot())

        settings.edit().putLong("last_sync_at", System.currentTimeMillis()).apply()
        return CloudSyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            pins = restored.pins.size,
            playlists = restored.playlists.size,
            automations = restored.automations.size,
            compactionWarning = compactionWarning
        )
    }

    /** Revokes this session best-effort and removes credentials/key, but keeps local data. */
    suspend fun disconnect() {
        val base = effectiveServerUrl
        val token = secrets.get(TOKEN_KEY).orEmpty()
        if (token.isNotBlank()) {
            runCatching { api(base, "/v1/sessions/current", method = "DELETE", token = token) }
        }
        secrets.remove(TOKEN_KEY)
        secrets.remove(RECOVERY_KEY)
    }

    /** Deletes this account and its remote encrypted history; device library remains untouched. */
    suspend fun deleteAccount() {
        if (!connected) throw CloudSyncException("Sign in to the sync account before deleting it.")
        api(effectiveServerUrl, "/v1/account/current", method = "DELETE", token = secrets.get(TOKEN_KEY).orEmpty())
        secrets.remove(TOKEN_KEY)
        secrets.remove(RECOVERY_KEY)
        journal.resetForAccountChange()
        settings.edit()
            .remove("owner_account_id")
            .remove("owner_server")
            .remove("account_id")
            .remove("email")
            .remove("last_sync_at")
            .apply()
    }

    private fun acceptAccount(
        base: String,
        email: String,
        response: JSONObject,
        recoveryKey: String?,
        isRegistration: Boolean
    ) {
        val returnedId = response.optString("accountId")
        val token = response.optString("token")
        val returnedEmail = response.optString("email").ifBlank { email }
        if (returnedId.isBlank() || token.isBlank()) throw CloudSyncException("The sync server returned an incomplete sign-in.")
        val oldOwner = settings.getString("owner_account_id", null)
        val oldServer = settings.getString("owner_server", null)
        val sameOwner = oldOwner == returnedId && oldServer == base
        if (oldOwner != null && !sameOwner) {
            journal.resetForAccountChange()
            secrets.remove(RECOVERY_KEY)
        }
        settings.edit().apply {
            if (!sameOwner) remove("last_sync_at")
            putString("owner_account_id", returnedId)
            putString("owner_server", base)
            putString("server", base)
            putString("email", returnedEmail)
            putString("account_id", returnedId)
        }.apply()
        secrets.put(TOKEN_KEY, token)
        if (recoveryKey != null) {
            secrets.put(RECOVERY_KEY, recoveryKey)
            // The key-check is an encrypted constant, not a server-readable password verifier.
            val check = JSONObject().put("marker", KEY_CHECK_MARKER).toString()
            journal.record(SYNC_KIND_KEY_CHECK, KEY_CHECK_ID, deleted = false, payload = check)
        } else if (isRegistration) {
            throw CloudSyncException("Could not set up the account recovery key.")
        }
    }

    private fun credentials(email: String, password: String): String = JSONObject()
        .put("email", email)
        .put("password", password)
        .toString()

    private suspend fun pullAll(key: ByteArray): CloudPullResult {
        var downloaded = 0
        var after = journal.cursor()
        var pages = 0
        var retainedEvents = 0
        while (true) {
            if (++pages > MAX_SYNC_PAGES) {
                throw CloudSyncException("Sync made partial progress. Tap Sync now again to continue through this large history.")
            }
            val response = api(
                effectiveServerUrl,
                "/v1/events?after=$after&limit=$PAGE_SIZE",
                token = secrets.get(TOKEN_KEY).orEmpty()
            )
            val snapshotThrough = response.optLong("snapshotThrough", 0L)
            val encryptedSnapshot = response.optJSONObject("snapshot")
            if (after < snapshotThrough) {
                val snapshot = encryptedSnapshot?.let { EncryptedSyncSnapshot.fromJson(it) }
                    ?: throw CloudSyncException("The sync server requires a checkpoint but did not provide one.")
                if (snapshot.throughCursor != snapshotThrough) {
                    throw CloudSyncException("The sync server returned an inconsistent checkpoint cursor.")
                }
                val snapshotRecords = try {
                    SyncCrypto.decryptSnapshot(snapshot, key)
                } catch (_: Exception) {
                    throw CloudSyncException("The recovery key did not unlock this account's history checkpoint. Check the key and try again.")
                }
                snapshotRecords.forEach(::validateKeyCheck)
                journal.mergeRemote(snapshotRecords, snapshot.throughCursor)
                downloaded += snapshotRecords.size
                after = journal.cursor()
            } else if (encryptedSnapshot != null) {
                throw CloudSyncException("The sync server returned an unexpected history checkpoint.")
            }

            val cursor = after
            val page = response.optJSONArray("events") ?: JSONArray()
            val nextCursor = response.optLong("nextCursor", cursor)
            if (nextCursor < cursor) throw CloudSyncException("The sync server returned an invalid cursor.")
            var previousSequence = cursor
            var pageEnvelopeChars = 0L
            val pageRecords = (0 until page.length()).map { index ->
                val event = EncryptedSyncEvent.fromJson(page.getJSONObject(index))
                pageEnvelopeChars += event.iv.length + event.ciphertext.length
                if (pageEnvelopeChars > MAX_PAGE_ENVELOPE_CHARS) {
                    throw CloudSyncException("A sync history page is larger than this device can safely process.")
                }
                if (event.sequence <= previousSequence || event.sequence > nextCursor) {
                    throw CloudSyncException("The sync server returned events out of cursor order.")
                }
                previousSequence = event.sequence
                val record = try {
                    SyncCrypto.decrypt(event, key)
                } catch (_: Exception) {
                    throw CloudSyncException("The recovery key did not unlock this account's sync data. Check the key and try again.")
                }
                validateKeyCheck(record)
                record
            }
            if (pageRecords.isNotEmpty() && nextCursor < previousSequence) {
                throw CloudSyncException("The sync server returned an invalid cursor.")
            }
            // Commit every authenticated page. If the history is very large or a later request
            // fails, the next attempt resumes at the last verified cursor instead of restarting.
            journal.mergeRemote(pageRecords, nextCursor)
            downloaded += pageRecords.size
            after = journal.cursor()
            retainedEvents = response.optInt("eventCount", retainedEvents)
            val hasMore = response.optBoolean("hasMore", false)
            if (!hasMore) break
            if (nextCursor <= cursor) throw CloudSyncException("The sync server did not advance its event cursor.")
        }
        return CloudPullResult(downloaded, retainedEvents)
    }

    private suspend fun compactHistory(key: ByteArray, throughCursor: Long): Boolean {
        if (throughCursor <= 0L) return false
        val snapshot = SyncCrypto.encryptSnapshot(
            records = journal.latest().values,
            key = key,
            snapshotId = UUID.randomUUID().toString(),
            throughCursor = throughCursor
        )
        val response = api(
            effectiveServerUrl,
            "/v1/snapshots",
            method = "POST",
            body = JSONObject().put("snapshot", snapshot.toJson()).toString(),
            token = secrets.get(TOKEN_KEY).orEmpty()
        )
        return response.optBoolean("compacted", false)
    }

    private suspend fun pushPending(key: ByteArray): Int {
        var uploaded = 0
        val pending = journal.pendingLatest()
        pending.chunked(PUSH_BATCH_SIZE).forEach { batch ->
            val events = JSONArray().also { array ->
                batch.forEach { candidate ->
                    array.put(SyncCrypto.encrypt(candidate.record, key, candidate.eventId).toJson())
                }
            }
            val response = api(
                effectiveServerUrl,
                "/v1/events",
                method = "POST",
                body = JSONObject().put("events", events).toString(),
                token = secrets.get(TOKEN_KEY).orEmpty()
            )
            val accepted = response.optJSONArray("accepted") ?: JSONArray()
            val ids = (0 until accepted.length()).mapNotNull { index ->
                accepted.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)
            }.toSet()
            val expected = batch.map { it.eventId }.toSet()
            if (!ids.containsAll(expected)) throw CloudSyncException("The sync server did not confirm every change.")
            journal.acknowledge(ids)
            uploaded += batch.size
        }
        return uploaded
    }

    private fun requireKeyCheck(records: Map<String, SyncRecord>) {
        val check = records["$SYNC_KIND_KEY_CHECK:$KEY_CHECK_ID"]
            ?: throw CloudSyncException("This account has no recovery-key check. Sign in again or contact the server owner.")
        validateKeyCheck(check)
    }

    private fun validateKeyCheck(record: SyncRecord) {
        if (record.kind != SYNC_KIND_KEY_CHECK) return
        val marker = runCatching { JSONObject(record.payload.orEmpty()).optString("marker") }.getOrNull()
        if (record.deleted || marker != KEY_CHECK_MARKER) {
            throw CloudSyncException("The recovery key did not unlock this account's sync data. Check the key and try again.")
        }
    }

    private suspend fun api(
        base: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null
    ): JSONObject {
        val url = base.trimEnd('/') + path
        val response = try {
            httpRequest(
                url,
                method = method,
                body = body,
                authorization = token?.let { "Bearer $it" },
                contentType = if (body != null) "application/json; charset=UTF-8" else null,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CloudSyncException("Could not reach the sync server. Check its HTTPS address and try again.")
        }
        val result = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.code !in 200..299) {
            val detail = result?.optString("error").orEmpty()
            val message = when {
                response.code == 401 -> "Sign-in expired or was refused. Sign in again."
                response.code == 409 -> detail.ifBlank { "That email is already registered on this server." }
                else -> detail.ifBlank { "Sync server returned HTTP ${response.code}." }
            }
            throw CloudSyncException(message)
        }
        return result ?: JSONObject()
    }

    private fun normalizeServer(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        // Allow users to type just a hostname or IP (e.g. "soundtrail.duckdns.org" or
        // "192.168.1.20:8080") and auto-prefix HTTPS, so the default public server and
        // self-hosted domains work without typing the scheme manually.
        val value = when {
            trimmed.isBlank() -> DEFAULT_SYNC_SERVER
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw CloudSyncException("Enter the HTTPS address of your sync server.")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.query != null || uri.fragment != null || value.contains(' ') ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) {
            throw CloudSyncException("Sync needs an HTTPS server URL, for example https://sync.example.net.")
        }
        if (uri.path.orEmpty().split('/').any { it == ".." }) {
            throw CloudSyncException("That server address is not valid.")
        }
        val authority = uri.rawAuthority?.lowercase()
            ?: throw CloudSyncException("That server address is not valid.")
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "https://$authority$path"
    }

    private fun localSnapshot(): List<SyncRecord> = SyncPayloads.snapshot(
        pins = pins.all(), playlists = playlists.all(), automations = automations.all()
    )

    companion object {
        /** The official public Soundtrail sync server. Used when no custom server is configured. */
        const val DEFAULT_SYNC_SERVER = "https://soundtrail.duckdns.org"

        private const val TOKEN_KEY = "cloud_sync_token"
        private const val RECOVERY_KEY = "cloud_sync_recovery_key"
        private const val PAGE_SIZE = 20
        private const val PUSH_BATCH_SIZE = 10
        // Pages are committed one at a time, so the next attempt safely resumes after this cap.
        private const val MAX_SYNC_PAGES = 5_000
        private const val MAX_PAGE_ENVELOPE_CHARS = 32L * 1024L * 1024L
        private const val COMPACT_AFTER_EVENTS = 1_000
        private const val KEY_CHECK_ID = "account-key-check-v1"
        private const val KEY_CHECK_MARKER = "soundtrail-e2e-sync-v1"
    }
}
