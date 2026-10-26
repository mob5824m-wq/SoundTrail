package tech.egrie.soundtrail.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.automation.AutomationStore
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.integrations.EndpointPolicy
import tech.egrie.soundtrail.integrations.EndpointPolicyException
import tech.egrie.soundtrail.integrations.HttpResponse
import tech.egrie.soundtrail.integrations.SecretStore
import tech.egrie.soundtrail.integrations.httpRequest
import java.util.UUID

/**
 * A sync failure that carries the HTTP status the server answered with.
 *
 * [status] is what callers branch on, never the message text: a message is wording for a person
 * and changes when the wording improves, while a status is the server's own answer. It is null
 * when no answer arrived at all — a timeout, a dropped connection — which is the ambiguous case,
 * because the request may still have been acted on.
 */
class CloudSyncException(
    message: String,
    val status: Int? = null
) : Exception(message)

internal data class CloudSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val pins: Int,
    val playlists: Int,
    val automations: Int,
    val compactionWarning: String? = null,
    /**
     * Set when the server's history looks older than this device's last sync, which is what a
     * restore from a database backup looks like from out here. A warning, never a failure: the
     * restore is a legitimate thing for a server owner to have done, and the sync itself still
     * completed. See [CloudSyncService.repushJournal].
     */
    val rollbackWarning: String? = null
)

private data class CloudPullResult(
    val downloaded: Int,
    val retainedEvents: Int,
    val rolledBack: Boolean = false
)

/** What an account response had to contain before anything is stored. See [SyncAccountResponse]. */
internal data class SyncAccount(
    val accountId: String,
    val email: String,
    val token: String,
    /** The session's own id, so the device list can mark "this device" without asking again. */
    val sessionId: String?,
    val expiresAt: Long
)

/**
 * The one gate between an account response and the credential store.
 *
 * Split out from [CloudSyncService] because the rule it enforces is the important one and it is
 * testable without Android: **nothing is persisted until the response has been proved to carry an
 * account id and a token.** A half-written sign-in is worse than a failed one — it leaves the app
 * reporting itself as connected with a token it does not have, and the listener's next sync fails
 * in a way that looks like a server problem.
 *
 * Free of Android types so [tech.egrie.soundtrail.sync.SyncAccountResponseTest] can pin it down.
 */
internal object SyncAccountResponse {

    /**
     * Reads a sign-in or registration response, or null when it is not one this app may act on.
     *
     * @param fallbackEmail the address the listener typed, used when the server omits its own copy
     */
    fun parse(response: JSONObject?, fallbackEmail: String): SyncAccount? {
        if (response == null) return null
        // Strictly strings. optString() would coerce a nested object to "{}" or a number to its
        // digits and hand back something that is not blank, so a malformed response would be
        // accepted as a token — which is the one thing this gate exists to refuse.
        val accountId = (response.opt("accountId") as? String)?.trim().orEmpty()
        val token = (response.opt("token") as? String).orEmpty()
        if (accountId.isBlank() || token.isBlank()) return null
        val sessionId = (response.opt("sessionId") as? String)?.trim()?.takeIf(String::isNotBlank)
        val expiresAt = response.optLong("expiresAt", 0L).takeIf { it > 0L } ?: 0L
        return SyncAccount(
            accountId = accountId,
            email = (response.opt("email") as? String).orEmpty().ifBlank { fallbackEmail },
            token = token,
            sessionId = sessionId,
            expiresAt = expiresAt
        )
    }
}

/** One signed-in device on this account, as the server lists it. */
internal data class SyncDeviceSession(
    val id: String,
    val createdAt: Long,
    val expiresAt: Long,
    val current: Boolean
) {
    /**
     * What the row is called.
     *
     * The server stores no device name — it has only ever seen a token, an IP and a time — so none
     * is invented here. A row is either this device or the date it signed in, and nothing pretends
     * to know which phone it was.
     */
    fun label(isThisDevice: Boolean): String = when {
        current || isThisDevice -> "This device"
        createdAt > 0L -> "Signed in ${formatTimestamp(createdAt)}"
        else -> "Another device"
    }
}

internal data class SyncSessionList(
    val sessions: List<SyncDeviceSession>,
    val maxSessions: Int
)

/** What came back from asking the server to revoke one session. */
internal sealed class SessionSignOut {
    /** Another device was signed out; this one is unaffected. */
    object RevokedOther : SessionSignOut()

    /**
     * The session that was revoked turned out to be this device's. Treated exactly like signing
     * out here: credentials go, local data stays.
     */
    object SignedOutThisDevice : SessionSignOut()

    data class Refused(val message: String) : SessionSignOut()
}

/** What a session check decided. See [CloudSyncService.refreshSession]. */
internal sealed class SessionRenewal {
    object NotConnected : SessionRenewal()

    /** Checked recently already; renewing more often would invalidate a working token for nothing. */
    object Skipped : SessionRenewal()

    /** Checked, and the token is nowhere near its expiry. */
    object StillValid : SessionRenewal()

    /** The token and the session id were replaced together, in one durable write. */
    object Renewed : SessionRenewal()

    /** The server no longer recognises this session: sign in again. */
    object Lost : SessionRenewal()

    /** The renewal may or may not have happened. Nothing is claimed either way. */
    object Unknown : SessionRenewal()
}

/** What a password change did. See [CloudSyncService.changePassword]. */
internal sealed class PasswordChange {
    /**
     * @param revokedSessions how many sessions the server signed out, this one included
     */
    data class Changed(val revokedSessions: Int) : PasswordChange()

    /** `403`: the current password was wrong. Deliberately not worded like a sign-in failure. */
    object WrongCurrentPassword : PasswordChange()

    /** `400`, or a rule this app checked before sending: the server's own words where it has any. */
    data class Refused(val message: String) : PasswordChange()

    /**
     * The outcome is unknown — a timeout, a `408`, a dropped connection. The server may have
     * changed the password and revoked this device, or may never have seen the request. Neither
     * success nor failure may be claimed.
     */
    object Unknown : PasswordChange()
}

/**
 * Client for the Soundtrail sync account service (public server by default; self-hosted servers
 * can be pointed to in the Connect tab). Authentication is separate from data encryption:
 * the account server authenticates email/password, but only a recovery key held by the user
 * can decrypt event payloads. Every network sync is a pull/merge/push/pull cycle over a
 * compactable event log with encrypted checkpoints; entity clocks and tombstones give
 * additions, edits, and deletes deterministic two-way conflict handling.
 *
 * Two properties of the transport are enforced here rather than hoped for:
 *
 * - **Status codes are the contract.** A quota is `507`, a rate limit is `429` with a
 *   `Retry-After`, a stalled body is `408`, a duplicate registration is `409`. Nothing branches on
 *   the text of a message; [SyncRetry] decides what may be tried again, and only calls the server
 *   deduplicates ever are.
 * - **An ambiguous failure is reported as ambiguous.** A call that changes account state is made
 *   once. When no answer comes back, the message says the outcome is unknown and what to do about
 *   it, rather than claiming a failure that may not have happened.
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

    /** This device's session id, saved at sign-in so the device list can mark it locally. */
    val sessionId: String? get() = secrets.get(SESSION_ID_KEY)?.takeIf(String::isNotBlank)

    /** When the current session expires, as the server last reported it; 0 when unknown. */
    val sessionExpiresAt: Long get() = settings.getLong(SESSION_EXPIRES_AT, 0L)

    /** True when a server history that looks older than this device's has been seen and not re-pushed. */
    val hasRollbackWarning: Boolean get() = settings.getBoolean(ROLLBACK_SEEN, false)

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

    /**
     * Creates an account, generates the user's random E2E recovery key, and returns it once.
     *
     * The failure path is the part that matters. Account creation is not idempotent and cannot be
     * retried, so when the server does not answer there are two possible worlds: the account was
     * created and the response was lost — in which case it exists with a recovery key this app
     * never saw — or it was never created at all. Nothing here may claim to know which, and
     * nothing is stored, so the message hands the listener both ways out instead.
     */
    suspend fun createAccount(rawServer: String, rawEmail: String, password: String): String {
        val base = normalizeServer(rawServer)
        val email = rawEmail.trim().lowercase()
        val response = api(
            base, "/v1/accounts", method = "POST", body = credentials(email, password),
            idempotence = SyncRetry.Idempotence.ONCE,
            // A registration that got no answer is the stranded-account case, and it needs its
            // own words: the account may exist, and the key for it never reached this device.
            ambiguousMessage = SyncResponses.REGISTRATION_AMBIGUOUS_MESSAGE
        )
        val key = SyncCrypto.newRecoveryKey()
        acceptAccount(base, email, response, SyncCrypto.encodeRecoveryKey(key), isRegistration = true)
        return SyncCrypto.encodeRecoveryKey(key)
    }

    /** Authenticates an existing account; a new device must enter its recovery key before syncing. */
    suspend fun signIn(rawServer: String, rawEmail: String, password: String) {
        val base = normalizeServer(rawServer)
        val email = rawEmail.trim().lowercase()
        val response = api(
            base, "/v1/sessions", method = "POST", body = credentials(email, password),
            idempotence = SyncRetry.Idempotence.ONCE
        )
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
            // 507 is the account's encrypted-storage quota, and it is the one push failure that a
            // retry can fix: compacting the history into a checkpoint frees the event rows it
            // covers, and the covered changes are inside that checkpoint, so they are not lost.
            if (e.status != SyncResponses.STATUS_INSUFFICIENT_STORAGE) throw e
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

        val rolledBack = firstPull.rolledBack || secondPull.rolledBack
        if (rolledBack) settings.edit().putBoolean(ROLLBACK_SEEN, true).apply()

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
            compactionWarning = compactionWarning,
            rollbackWarning = if (rolledBack) ROLLBACK_WARNING else null
        )
    }

    /**
     * Re-pushes the whole local journal to a server whose history went backwards.
     *
     * After a server is restored from a database backup, the events this device already had
     * acknowledged are gone from it, and nothing in the ordinary sync path would send them again —
     * the journal only pushes what is still pending. This re-queues every current record so the
     * library is whole on the server again.
     *
     * Re-pushing is safe rather than merely hopeful: each record carries the entity clock and
     * device id it was created with, so merge is last-writer-wins on an unchanged value. A
     * duplicate of a record the server still has resolves to the same winner, and a duplicate of
     * one it lost restores it. Nothing is re-encrypted with a new clock, which is what would make
     * this destructive.
     *
     * @return how many encrypted records were uploaded
     */
    suspend fun repushJournal(): Int {
        if (!connected) throw CloudSyncException("Sign in to your Soundtrail sync account first.")
        val encodedKey = secrets.get(RECOVERY_KEY)
            ?: throw CloudSyncException("Enter the recovery key you saved when you created the account.")
        val key = try {
            SyncCrypto.decodeRecoveryKey(encodedKey)
        } catch (_: Exception) {
            throw CloudSyncException("The saved recovery key is unreadable. Enter your saved key again.")
        }
        val queued = journal.requeueAll()
        if (queued == 0) {
            settings.edit().putBoolean(ROLLBACK_SEEN, false).apply()
            return 0
        }
        val uploaded = pushPending(key)
        settings.edit().putBoolean(ROLLBACK_SEEN, false).apply()
        return uploaded
    }

    // ----- The account's own sessions -----

    /**
     * Every session on this account, newest first.
     *
     * Ids only: the server never returns a token again after sign-in, so this list can name a
     * device and sign one out, and cannot be used to become one.
     */
    suspend fun sessions(): SyncSessionList {
        requireConnected()
        val response = api(effectiveServerUrl, "/v1/sessions", token = token())
        val array = response.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id").trim()
            if (id.isBlank()) return@mapNotNull null
            SyncDeviceSession(
                id = id,
                createdAt = entry.optLong("createdAt", 0L),
                expiresAt = entry.optLong("expiresAt", 0L),
                current = entry.optBoolean("current", false)
            )
        }
        return SyncSessionList(sessions, response.optInt("maxSessions", 0))
    }

    /**
     * Signs out one device.
     *
     * An id belonging to another account answers `404`, exactly like an id that does not exist, so
     * this cannot be used to learn whether a session exists somewhere else; both are reported the
     * same way. If the id revoked turns out to be this device's — which the server says with
     * `signedOut` — the result is [SessionSignOut.SignedOutThisDevice] and the caller clears
     * credentials, because holding a revoked token is how an app ends up failing every later
     * request with no idea why.
     */
    suspend fun signOutSession(id: String): SessionSignOut {
        requireConnected()
        val trimmed = id.trim()
        if (trimmed.isBlank()) return SessionSignOut.Refused("That session is not valid.")
        return try {
            val response = api(
                effectiveServerUrl,
                "/v1/sessions/${java.net.URLEncoder.encode(trimmed, "UTF-8")}",
                method = "DELETE",
                token = token()
            )
            if (response.optBoolean("signedOut", false)) {
                forgetSession()
                SessionSignOut.SignedOutThisDevice
            } else {
                SessionSignOut.RevokedOther
            }
        } catch (e: CloudSyncException) {
            if (e.status == 404 || e.status == 400) {
                SessionSignOut.Refused("That session is not on this account any more.")
            } else {
                throw e
            }
        }
    }

    /**
     * Checks this session against the server and renews it when it is close to expiry.
     *
     * Renewal is not free: `POST /v1/sessions/current/renew` kills the old token the moment it
     * answers, and hands back a new token *and* a new session id. So it is done at most once a day,
     * and only when expiry is inside [RENEW_WITHIN_MILLIS] — not on every request, which would
     * invalidate a working token for nothing.
     *
     * The new token and the new session id are written in **one** [SecretStore.putAll], which is a
     * single `commit()`. That ordering is the whole point: the old token is already dead when the
     * response exists, so a crash between the call and the write loses the session either way —
     * but a crash *between two writes* would leave the app holding a live token under a stale
     * session id, or the reverse, and neither state is recoverable by looking at it. One write
     * means the stored pair is always a pair the server issued together.
     *
     * A lost session (`401`) is reported as [SessionRenewal.Lost] and never retried with the old
     * token; an ambiguous renewal is [SessionRenewal.Unknown], and the next call to this function
     * re-reads `GET /v1/session/current` to find out what actually happened.
     *
     * @param force ignore the once-a-day limit; used when a sync just failed on an expired session
     */
    suspend fun refreshSession(force: Boolean = false): SessionRenewal {
        if (!connected) return SessionRenewal.NotConnected
        val now = System.currentTimeMillis()
        if (!force && now - settings.getLong(LAST_SESSION_CHECK, 0L) < RENEW_CHECK_INTERVAL_MILLIS) {
            return SessionRenewal.Skipped
        }

        val current = try {
            api(effectiveServerUrl, "/v1/session/current", token = token())
        } catch (e: CloudSyncException) {
            if (e.status == 401) {
                settings.edit().putLong(LAST_SESSION_CHECK, now).apply()
                return SessionRenewal.Lost
            }
            throw e
        }
        settings.edit().putLong(LAST_SESSION_CHECK, now).apply()

        val expiresAt = current.optLong("expiresAt", 0L)
        if (expiresAt > 0L) settings.edit().putLong(SESSION_EXPIRES_AT, expiresAt).apply()
        val knownExpiry = if (expiresAt > 0L) expiresAt else sessionExpiresAt
        if (knownExpiry > 0L && knownExpiry - now > RENEW_WITHIN_MILLIS) {
            return SessionRenewal.StillValid
        }

        // Close enough to expiry to be worth the invalidation. One attempt: a retry after an
        // ambiguous failure would renew against a token that may already be dead.
        val renewed = try {
            api(
                effectiveServerUrl, "/v1/sessions/current/renew", method = "POST",
                token = token(), idempotence = SyncRetry.Idempotence.ONCE
            )
        } catch (e: CloudSyncException) {
            if (e.status == 401) return SessionRenewal.Lost
            // No answer, or a 408: the old token may already be dead and the new one may be
            // unreachable. Claim nothing; the next check reads the server's own answer.
            if (e.status == null || e.status == 408) return SessionRenewal.Unknown
            throw e
        }
        val newToken = renewed.optString("token")
        if (newToken.isBlank()) return SessionRenewal.Unknown
        storeSession(
            token = newToken,
            sessionId = renewed.optString("sessionId").trim().takeIf(String::isNotBlank),
            expiresAt = renewed.optLong("expiresAt", 0L)
        )
        return SessionRenewal.Renewed
    }

    /**
     * Changes the account password.
     *
     * Every session on the account is signed out by this, including the caller's, and the token in
     * the response is the only one still working — so it is stored **before** this returns, and
     * therefore before any success message is shown. An app that announced success first and
     * stored second would leave a window where the listener believes they are signed in with a
     * token the server has already revoked.
     *
     * The recovery key is untouched, and that is not an oversight: the password authenticates, the
     * key decrypts, and they are independent by design. Changing one has never rotated the other,
     * so a device that holds the key keeps holding it.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChange {
        requireConnected()
        // The server's own rules, checked here first so the listener hears them before a round
        // trip rather than after one.
        if (newPassword.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
            return PasswordChange.Refused(
                "Use a password of $PASSWORD_MIN_LENGTH to $PASSWORD_MAX_LENGTH characters."
            )
        }
        if (newPassword == currentPassword) {
            return PasswordChange.Refused("The new password has to differ from the current one.")
        }
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
            .toString()

        val response = try {
            api(
                effectiveServerUrl, "/v1/account/current/password", method = "POST", body = body,
                token = token(), idempotence = SyncRetry.Idempotence.ONCE
            )
        } catch (e: CloudSyncException) {
            return when (e.status) {
                403 -> PasswordChange.WrongCurrentPassword
                400 -> PasswordChange.Refused(
                    e.message ?: "The server did not accept that new password."
                )
                // Timeout, 408, dropped connection: the change may have gone through and this
                // device may already be signed out. Neither outcome may be claimed.
                null, 408 -> PasswordChange.Unknown
                else -> throw e
            }
        }
        val newToken = response.optString("token")
        if (newToken.isBlank()) {
            // The server said it changed the password but gave nothing to continue with. Treat it
            // as unknown rather than as success: this device's old session is gone either way.
            return PasswordChange.Unknown
        }
        storeSession(
            token = newToken,
            sessionId = response.optString("sessionId").trim().takeIf(String::isNotBlank),
            expiresAt = response.optLong("expiresAt", 0L)
        )
        return PasswordChange.Changed(response.optInt("revokedSessions", 0))
    }

    /**
     * Drops this device's session credentials and keeps the recovery key.
     *
     * This is the "your session ended" path, not the "sign out" path: the key stays because it
     * belongs to the account rather than to the session, and keeping it means signing in again
     * resumes syncing immediately instead of asking for a key the listener already entered.
     */
    fun forgetSession() {
        secrets.remove(TOKEN_KEY)
        secrets.remove(SESSION_ID_KEY)
        settings.edit()
            .remove(SESSION_EXPIRES_AT)
            .remove(LAST_SESSION_CHECK)
            .apply()
    }

    /** Revokes this session best-effort and removes credentials/key, but keeps local data. */
    suspend fun disconnect() {
        val base = effectiveServerUrl
        val currentToken = secrets.get(TOKEN_KEY).orEmpty()
        if (currentToken.isNotBlank()) {
            runCatching {
                api(base, "/v1/sessions/current", method = "DELETE", token = currentToken)
            }
        }
        secrets.remove(TOKEN_KEY)
        secrets.remove(SESSION_ID_KEY)
        secrets.remove(RECOVERY_KEY)
        settings.edit()
            .remove(SESSION_EXPIRES_AT)
            .remove(LAST_SESSION_CHECK)
            .apply()
    }

    /** Deletes this account and its remote encrypted history; device library remains untouched. */
    suspend fun deleteAccount() {
        if (!connected) throw CloudSyncException("Sign in to the sync account before deleting it.")
        api(
            effectiveServerUrl, "/v1/account/current", method = "DELETE", token = token(),
            idempotence = SyncRetry.Idempotence.ONCE
        )
        secrets.remove(TOKEN_KEY)
        secrets.remove(SESSION_ID_KEY)
        secrets.remove(RECOVERY_KEY)
        journal.resetForAccountChange()
        settings.edit()
            .remove("owner_account_id")
            .remove("owner_server")
            .remove("account_id")
            .remove("email")
            .remove("last_sync_at")
            .remove(SESSION_EXPIRES_AT)
            .remove(LAST_SESSION_CHECK)
            .remove(HIGHEST_SEQUENCE)
            .remove(ROLLBACK_SEEN)
            .apply()
    }

    /**
     * Writes a token and its session id together, in one durable commit.
     *
     * Never call [SecretStore.put] twice for these: the server issues them as a pair and revokes
     * the previous pair when it does, so a crash between two writes leaves the app holding half of
     * a session — a live token with a stale id, or an id with a dead token.
     */
    private fun storeSession(token: String, sessionId: String?, expiresAt: Long) {
        // One putAll is one commit(), so the token and its session id land together or not at all.
        // An id the server did not return is stored blank rather than left as it was: a stale id
        // sitting next to a fresh token would name a session the server has already replaced.
        secrets.putAll(
            mapOf(
                TOKEN_KEY to token,
                SESSION_ID_KEY to sessionId.orEmpty()
            )
        )
        settings.edit()
            .putLong(SESSION_EXPIRES_AT, expiresAt)
            .putLong(LAST_SESSION_CHECK, System.currentTimeMillis())
            .apply()
    }

    private fun acceptAccount(
        base: String,
        email: String,
        response: JSONObject,
        recoveryKey: String?,
        isRegistration: Boolean
    ) {
        // The gate: nothing below runs, and nothing is written, unless the response actually
        // carries an account id and a token.
        val account = SyncAccountResponse.parse(response, email)
            ?: throw CloudSyncException("The sync server returned an incomplete sign-in.")
        val oldOwner = settings.getString("owner_account_id", null)
        val oldServer = settings.getString("owner_server", null)
        val sameOwner = oldOwner == account.accountId && oldServer == base
        if (oldOwner != null && !sameOwner) {
            journal.resetForAccountChange()
            secrets.remove(RECOVERY_KEY)
        }
        settings.edit().apply {
            if (!sameOwner) {
                remove("last_sync_at")
                remove(HIGHEST_SEQUENCE)
                remove(ROLLBACK_SEEN)
            }
            putString("owner_account_id", account.accountId)
            putString("owner_server", base)
            putString("server", base)
            putString("email", account.email)
            putString("account_id", account.accountId)
            putLong(SESSION_EXPIRES_AT, account.expiresAt)
            putLong(LAST_SESSION_CHECK, System.currentTimeMillis())
        }.apply()
        storeSession(account.token, account.sessionId, account.expiresAt)
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

    private fun requireConnected() {
        if (!connected) throw CloudSyncException("Sign in to your Soundtrail sync account first.")
    }

    private fun token(): String = secrets.get(TOKEN_KEY).orEmpty()

    private suspend fun pullAll(key: ByteArray): CloudPullResult {
        var downloaded = 0
        var after = journal.cursor()
        var pages = 0
        var retainedEvents = 0
        var rolledBack = false
        while (true) {
            if (++pages > MAX_SYNC_PAGES) {
                throw CloudSyncException("Sync made partial progress. Tap Sync now again to continue through this large history.")
            }
            val response = api(
                effectiveServerUrl,
                "/v1/events?after=$after&limit=$PAGE_SIZE",
                token = token()
            )
            if (detectRollback(response)) rolledBack = true
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
        return CloudPullResult(downloaded, retainedEvents, rolledBack)
    }

    /**
     * Notices a server whose history has gone backwards.
     *
     * The highest `latestSequence` this device has seen for this account is remembered. A later
     * response reporting a *lower* one means the server's log no longer contains events this device
     * already pulled and applied — which is what restoring the database from a backup looks like
     * from the client side.
     *
     * It is a warning and not an error on purpose. Restoring a server is a legitimate thing for the
     * person who runs it to do, the local library is still intact, and refusing to sync would turn
     * a recoverable situation into a dead end. What the listener is offered instead is
     * [repushJournal], which puts the missing events back.
     *
     * @return true when this response looks older than what was already seen
     */
    private fun detectRollback(response: JSONObject): Boolean {
        val reported = response.optLong("latestSequence", -1L)
        if (reported < 0L) return false
        val seen = settings.getLong(HIGHEST_SEQUENCE, -1L)
        if (seen < 0L) {
            settings.edit().putLong(HIGHEST_SEQUENCE, reported).apply()
            return false
        }
        if (reported > seen) {
            settings.edit().putLong(HIGHEST_SEQUENCE, reported).apply()
            return false
        }
        // Equal is normal: nothing was appended since the last page. Only strictly older is a
        // rollback, and the mark is never lowered, so one old response cannot erase the evidence.
        return reported < seen
    }

    private suspend fun compactHistory(key: ByteArray, throughCursor: Long): Boolean {
        if (throughCursor <= 0L) return false
        val snapshot = SyncCrypto.encryptSnapshot(
            records = journal.latest().values,
            key = key,
            snapshotId = UUID.randomUUID().toString(),
            throughCursor = throughCursor
        )
        val envelope = snapshot.toJson()
        // The server's own 8 MiB checkpoint limit, checked here: a local sentence about a size
        // beats a 413 from the far side of an upload that was never going to be accepted.
        SyncEnvelopeLimits.snapshotProblem(envelope)?.let { throw CloudSyncException(it) }
        val response = api(
            effectiveServerUrl,
            "/v1/snapshots",
            method = "POST",
            body = JSONObject().put("snapshot", envelope).toString(),
            token = token()
        )
        return response.optBoolean("compacted", false)
    }

    private suspend fun pushPending(key: ByteArray): Int {
        var uploaded = 0
        val pending = journal.pendingLatest()
        pending.chunked(PUSH_BATCH_SIZE).forEach { batch ->
            // The server accepts at most 100 events per request. PUSH_BATCH_SIZE is well under
            // that, so this is a guard on the constant rather than a runtime condition: raising
            // the batch size past the server's limit would otherwise fail every push with a 400
            // that says nothing about the cause.
            require(batch.size in 1..SyncEnvelopeLimits.MAX_EVENTS_PER_REQUEST) {
                "A sync batch must hold 1 to ${SyncEnvelopeLimits.MAX_EVENTS_PER_REQUEST} events."
            }
            val events = JSONArray().also { array ->
                batch.forEach { candidate ->
                    val envelope = SyncCrypto.encrypt(candidate.record, key, candidate.eventId).toJson()
                    // Each envelope is checked before the batch is sent, so an oversized or
                    // malformed one is reported here rather than refused by the server.
                    SyncEnvelopeLimits.eventProblem(envelope)?.let { throw CloudSyncException(it) }
                    array.put(envelope)
                }
            }
            // Retrying this POST is safe and only safe because the event ids are stable: the
            // journal mints one UUID per record when it is created, not per attempt, so the server
            // sees a repeat delivery of an id it already has rather than a second change.
            val response = api(
                effectiveServerUrl,
                "/v1/events",
                method = "POST",
                body = JSONObject().put("events", events).toString(),
                token = token()
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

    /**
     * One call to the sync server, with the retry policy applied.
     *
     * @param idempotence whether the server would deduplicate a repeat. Anything that changes
     *   account state is [SyncRetry.Idempotence.ONCE] and is never retried, because a retry after
     *   an ambiguous failure could create a second account or invalidate a token this app has not
     *   stored yet.
     * @param ambiguousMessage what to say when no answer arrived for a call that must not be
     *   repeated; the default covers an ordinary unreachable server.
     */
    private suspend fun api(
        base: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null,
        idempotence: SyncRetry.Idempotence = SyncRetry.Idempotence.RETRYABLE,
        ambiguousMessage: String? = null
    ): JSONObject {
        val url = base.trimEnd('/') + path
        var attempt = 0
        while (true) {
            attempt += 1
            val response: HttpResponse?
            val transport: Exception?
            try {
                response = httpRequest(
                    url,
                    method = method,
                    body = body,
                    authorization = token?.let { "Bearer $it" },
                    contentType = if (body != null) "application/json; charset=UTF-8" else null,
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 60_000
                )
                transport = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: EndpointPolicyException) {
                // A refused address is not transient: waiting and asking again changes nothing.
                throw CloudSyncException(e.message ?: "That sync server address cannot be used.")
            } catch (e: Exception) {
                response = null
                transport = e
            }

            if (response != null) {
                val parsed = runCatching { JSONObject(response.body) }.getOrNull()
                // A 3xx is not a success and is not retried: httpRequest never follows a
                // redirect, so one arriving here is a server trying to move the request — and the
                // bearer token with it — to a host nobody chose. SyncResponses turns it into an
                // error rather than an answer.
                if (response.code in 200..299 && !response.isRedirect) return parsed ?: JSONObject()

                val decision = SyncRetry.decide(
                    status = response.code,
                    retryAfterSeconds = response.retryAfterSeconds,
                    attempt = attempt,
                    idempotence = idempotence
                )
                if (decision is SyncRetry.Decision.Retry) {
                    delay(decision.waitMillis)
                    continue
                }
                throw SyncResponses.failureFor(response, path)
            }

            val decision = SyncRetry.decide(
                status = null,
                retryAfterSeconds = null,
                attempt = attempt,
                idempotence = idempotence
            )
            if (decision is SyncRetry.Decision.Retry) {
                delay(decision.waitMillis)
                continue
            }
            throw CloudSyncException(ambiguousMessage ?: SyncResponses.UNREACHABLE_MESSAGE)
        }
    }

    /**
     * The address of the sync server, normalized and validated: HTTPS only, with no local
     * exception, because this endpoint carries a password, a bearer token and an encrypted
     * library. [EndpointPolicy.syncServer] holds the rule so it can be tested on its own.
     */
    private fun normalizeServer(raw: String): String = try {
        EndpointPolicy.syncServer(raw)
    } catch (e: EndpointPolicyException) {
        throw CloudSyncException(e.message ?: "Enter the HTTPS address of your sync server.")
    }

    private fun localSnapshot(): List<SyncRecord> = SyncPayloads.snapshot(
        pins = pins.all(), playlists = playlists.all(), automations = automations.all()
    )

    companion object {
        /** The official public Soundtrail sync server. Used when no custom server is configured. */
        const val DEFAULT_SYNC_SERVER = "https://soundtrail.duckdns.org"

        private const val TOKEN_KEY = "cloud_sync_token"
        private const val SESSION_ID_KEY = "cloud_sync_session_id"
        private const val RECOVERY_KEY = "cloud_sync_recovery_key"
        private const val SESSION_EXPIRES_AT = "session_expires_at"
        private const val LAST_SESSION_CHECK = "last_session_check"
        private const val HIGHEST_SEQUENCE = "highest_sequence"
        private const val ROLLBACK_SEEN = "rollback_warning_seen"

        private const val PAGE_SIZE = 20
        private const val PUSH_BATCH_SIZE = 10
        // Pages are committed one at a time, so the next attempt safely resumes after this cap.
        private const val MAX_SYNC_PAGES = 5_000
        private const val MAX_PAGE_ENVELOPE_CHARS = 32L * 1024L * 1024L
        private const val COMPACT_AFTER_EVENTS = 1_000
        private const val KEY_CHECK_ID = "account-key-check-v1"
        private const val KEY_CHECK_MARKER = "soundtrail-e2e-sync-v1"

        /** The server's password rules, mirrored so the listener hears them before a round trip. */
        const val PASSWORD_MIN_LENGTH = 10
        const val PASSWORD_MAX_LENGTH = 128

        /** Renew at most this often, so a working token is not invalidated on every request. */
        private const val RENEW_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

        /** Renew when expiry is this close. The server's sessions last 30 days. */
        private const val RENEW_WITHIN_MILLIS = 7L * 24L * 60L * 60L * 1000L

    }
}

/** A timestamp as the device list shows it: the date a session was created. */
internal fun formatTimestamp(timestamp: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
