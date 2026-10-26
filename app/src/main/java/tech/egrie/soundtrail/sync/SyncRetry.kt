package tech.egrie.soundtrail.sync

import org.json.JSONObject
import tech.egrie.soundtrail.integrations.HttpResponse
import kotlin.random.Random

/**
 * Whether a failed call to the sync server may be tried again, and how long to wait first.
 *
 * Kept apart from [CloudSyncService] on purpose: the decision is arithmetic over a status code, an
 * attempt counter and a `Retry-After`, so it is unit-tested directly instead of being inferred from
 * whatever a sync run happened to do. Nothing here suspends, sleeps or touches the network — the
 * caller performs the wait it is told to.
 *
 * The rule the server's own behaviour implies:
 *
 * - **`429`** is an answer, not a failure: it comes with `Retry-After`, so wait that long (capped,
 *   because a server can ask for anything) and try again — but never for a call that must not be
 *   repeated, which is how a sign-in that was rate-limited goes back to the listener instead of
 *   quietly retrying behind them.
 * - **`408` and the gateway failures (`502`, `503`, `504`)** are transient by definition, so they
 *   are retried with exponential backoff.
 * - **No response at all** — a timeout, a dropped connection, a name that did not resolve — is the
 *   ambiguous case. The request may have been acted on. It is retried only when the server would
 *   deduplicate a repeat, and reported as ambiguous when it would not.
 * - **Everything else** is a real answer about this request. Retrying it cannot change it.
 */
internal object SyncRetry {

    /** One attempt plus two retries, which is what "retry at most twice" means. */
    const val MAX_ATTEMPTS = 3

    /** Exponential backoff for a transient failure: 1 s, then 2 s, never more than 8 s. */
    private const val BACKOFF_BASE_MILLIS = 1_000L
    private const val BACKOFF_CAP_MILLIS = 8_000L

    /** A rate limit is honoured as asked, up to a minute; beyond that the listener waits too long. */
    const val RETRY_AFTER_CAP_SECONDS = 60

    /** What to assume when a `429` arrives with no usable `Retry-After`. */
    const val DEFAULT_RETRY_AFTER_SECONDS = 30

    /** Added to every wait so a fleet of devices does not come back in lockstep. */
    const val MAX_JITTER_MILLIS = 250L

    /**
     * Whether the server can be asked again safely.
     *
     * This is the distinction that keeps a retry from becoming a duplicate action. It is decided by
     * the endpoint, not by the failure: an ambiguous failure on a call that creates something is
     * never retried, however retryable the status code looks.
     */
    enum class Idempotence {
        /**
         * A read, a delete, or a write the server deduplicates.
         *
         * `POST /v1/events` and `POST /v1/snapshots` belong here even though they are POSTs: the
         * server keys an event by its id and a snapshot by its cursor, so repeating one with the
         * same ids is a no-op rather than a second change.
         */
        RETRYABLE,

        /**
         * A call that changes account state and cannot be undone by repeating it: creating an
         * account, signing in, renewing a token, changing a password. One attempt, ever — a retry
         * after an ambiguous failure could create a second account or invalidate a token the app
         * has not stored yet.
         */
        ONCE
    }

    sealed class Decision {
        /** Try again after this long. */
        data class Retry(val waitMillis: Long) : Decision()

        /** Do not try again; the caller reports the failure. */
        object GiveUp : Decision()
    }

    /**
     * The status codes that mean "ask again later" rather than "that was wrong".
     *
     * `408` is the server's own socket timeout on a stalled body; `502`/`503`/`504` are a proxy or
     * an extractor that is busy or briefly unreachable.
     */
    private val transientStatuses = setOf(408, 502, 503, 504)

    /**
     * Decides what to do about one failed attempt.
     *
     * @param status the HTTP status the server answered with, or null when no answer arrived
     * @param retryAfterSeconds the server's `Retry-After`, when it sent one
     * @param attempt the 1-based number of the attempt that just failed
     * @param jitterMillis jitter to add to the wait; a parameter so tests are deterministic
     */
    fun decide(
        status: Int?,
        retryAfterSeconds: Int?,
        attempt: Int,
        idempotence: Idempotence,
        jitterMillis: Long = jitter()
    ): Decision {
        // A call that must not be repeated is never repeated, whatever came back — including a
        // 429 on sign-in, which goes back to the listener rather than being retried behind them.
        if (idempotence == Idempotence.ONCE) return Decision.GiveUp
        if (attempt >= MAX_ATTEMPTS) return Decision.GiveUp

        return when {
            status == 429 -> Decision.Retry(
                rateLimitWaitMillis(retryAfterSeconds) + jitterMillis
            )
            // No answer at all: the request may or may not have been acted on, which is exactly
            // why only a deduplicated call gets here.
            status == null -> Decision.Retry(backoffMillis(attempt) + jitterMillis)
            status in transientStatuses -> Decision.Retry(backoffMillis(attempt) + jitterMillis)
            else -> Decision.GiveUp
        }
    }

    /** How long to wait for a rate limit: what the server asked for, capped, in milliseconds. */
    fun rateLimitWaitMillis(retryAfterSeconds: Int?): Long {
        val asked = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
            .coerceIn(1, RETRY_AFTER_CAP_SECONDS)
        return asked * 1_000L
    }

    /** Exponential backoff for the attempt that just failed: 1 s, 2 s, 4 s, capped at 8 s. */
    fun backoffMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val scaled = BACKOFF_BASE_MILLIS shl exponent.coerceAtMost(3)
        return scaled.coerceAtMost(BACKOFF_CAP_MILLIS)
    }

    /**
     * What a rate limit says to the listener, including how long it lasts.
     *
     * A wait the app is silently sleeping through is indistinguishable from a hang, so the number
     * is in the sentence.
     */
    fun rateLimitMessage(retryAfterSeconds: Int?): String {
        val seconds = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
            .coerceIn(1, RETRY_AFTER_CAP_SECONDS)
        return if (seconds < 60) {
            "Too many attempts. Try again in $seconds seconds."
        } else {
            val minutes = (seconds + 59) / 60
            "Too many attempts. Try again in $minutes ${if (minutes == 1) "minute" else "minutes"}."
        }
    }

    /** A small random wait, so several devices do not retry in step. */
    fun jitter(): Long = Random.nextLong(0L, MAX_JITTER_MILLIS + 1L)
}

/**
 * The mapping from one server response to the failure a listener is told about.
 *
 * Pure, so it is tested without a server, a Context or a coroutine: a redirect becoming an
 * exception rather than a silently followed host, a `429` carrying the wait the server asked for,
 * a `409` on registration saying what to do next, and a `507` keeping the status its caller
 * branches on. [CloudSyncService.api] owns the retry timing; this owns what a refusal means.
 */
internal object SyncResponses {

    /** The account's encrypted-sync quota is full. Callers branch on this, never on wording. */
    const val STATUS_INSUFFICIENT_STORAGE = 507

    internal const val QUOTA_MESSAGE =
        "This account has reached its encrypted sync storage limit."

    private const val REDIRECT_MESSAGE =
        "The sync server sent a redirect, which Soundtrail does not follow: a redirect could " +
            "move your sign-in token to a host you did not choose."

    /**
     * What a registration with no answer says.
     *
     * Both paths are offered because both are live: the account may exist, and if it does its
     * recovery key never reached this device — so signing in and letting the first sync establish
     * a new key on the empty account works, and so does simply using another address. What is
     * never said is "wrong password", which is a claim about a credential when the truth is that
     * no answer arrived.
     */
    internal const val REGISTRATION_AMBIGUOUS_MESSAGE =
        "The server did not answer, so that account may or may not have been created, and " +
            "nothing was saved on this device. If it was created, its recovery key never " +
            "reached you. Try signing in with the same email and password — an account with " +
            "no synced data yet can still adopt the key this device creates on its first " +
            "sync. If signing in fails, no account was made: try again, or use a different " +
            "email address."

    /** What an ordinary call says when no answer arrived at all. */
    internal const val UNREACHABLE_MESSAGE =
        "Could not reach the sync server. Check its HTTPS address and try again."

    /**
     * The exception for a response that is not a success.
     *
     * A `3xx` is included, and first: `httpRequest` never follows a redirect, so one arriving here
     * is a server trying to move the request — and with it the bearer token — somewhere the caller
     * did not choose. Treating it as an answer would be the silent host change this exists to
     * prevent.
     */
    fun failureFor(response: HttpResponse, path: String): CloudSyncException {
        if (response.isRedirect) return CloudSyncException(REDIRECT_MESSAGE, response.code)
        return CloudSyncException(messageFor(response, path), response.code)
    }

    /** The sentence for a status code the server actually returned. */
    fun messageFor(response: HttpResponse, path: String): String {
        val detail = response.detail()
        return when (response.code) {
            401 -> "Sign-in expired or was refused. Sign in again."
            403 -> detail.ifBlank { "The sync server refused that request." }
            404 -> detail.ifBlank { "The sync server has no such endpoint or session." }
            408 -> detail.ifBlank { "The sync server timed out reading that request." }
            409 -> when {
                // Actionable on purpose: the thing to do next is on the same screen, and the
                // email the listener typed stays in the field they can sign in from.
                path.startsWith("/v1/accounts") ->
                    "That email already has an account on this server. Sign in instead."
                else -> detail.ifBlank { "That is already on the server." }
            }
            413 -> detail.ifBlank { "That is larger than the sync server accepts." }
            422 -> detail.ifBlank { "The sync server could not use that request." }
            429 -> SyncRetry.rateLimitMessage(response.retryAfterSeconds)
            // Before the 5xx range: a quota is an answer about this account, not a server that
            // could not cope, and the caller branches on it to compact the history and retry.
            STATUS_INSUFFICIENT_STORAGE -> detail.ifBlank { QUOTA_MESSAGE }
            in 500..599 -> detail.ifBlank {
                "The sync server could not answer (HTTP ${response.code}). Try again shortly."
            }
            else -> detail.ifBlank { "Sync server returned HTTP ${response.code}." }
        }
    }
}

/**
 * The server's input limits, asserted here before anything is uploaded.
 *
 * A local failure says what is wrong in words the listener can act on, and costs no round trip; a
 * server `400` or `413` says only that something was too big, after the bytes have already been
 * sent. These numbers are the server's, from its own constants: 100 events per request, a 12-byte
 * IV, 1 MiB per encrypted event, 8 MiB per encrypted checkpoint.
 *
 * Nothing here re-implements encryption or changes a payload — [SyncCrypto] owns that and is
 * deliberately untouched. This only inspects what it produced, on the way out.
 */
internal object SyncEnvelopeLimits {

    const val MAX_EVENTS_PER_REQUEST = 100
    const val MAX_EVENT_BYTES = 1024 * 1024
    const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
    const val IV_BYTES = 12

    private val base64Url = Regex("^[A-Za-z0-9_-]+$")

    /**
     * How many bytes an unpadded base64url string decodes to, or null when it is not base64url.
     *
     * Computed rather than decoded: the point is to check a size, and allocating a copy of every
     * ciphertext on the way out to measure it would be waste.
     */
    fun decodedSize(value: String): Int? {
        if (value.isEmpty() || !base64Url.matches(value)) return null
        return value.length * 3 / 4
    }

    /**
     * Why one encrypted event cannot be sent, or null when it is within every limit.
     *
     * @param envelope the `{id, iv, ciphertext}` object about to be put in a batch
     */
    fun eventProblem(envelope: JSONObject): String? {
        val iv = envelope.optString("iv")
        val ciphertext = envelope.optString("ciphertext")
        val ivBytes = decodedSize(iv)
            ?: return "An encrypted change could not be prepared for upload. Try again."
        if (ivBytes != IV_BYTES) {
            return "An encrypted change has a ${ivBytes}-byte nonce where the server needs " +
                "$IV_BYTES. Try again."
        }
        val cipherBytes = decodedSize(ciphertext)
            ?: return "An encrypted change could not be prepared for upload. Try again."
        if (cipherBytes > MAX_EVENT_BYTES) {
            return "One encrypted change is ${cipherBytes / 1024} KiB, over the server's " +
                "${MAX_EVENT_BYTES / 1024} KiB limit for a single event."
        }
        return null
    }

    /** Why an encrypted checkpoint cannot be sent, or null when it is within the limit. */
    fun snapshotProblem(envelope: JSONObject): String? {
        val iv = envelope.optString("iv")
        val ciphertext = envelope.optString("ciphertext")
        if (decodedSize(iv) != IV_BYTES) {
            return "The history checkpoint could not be prepared for upload. Try again."
        }
        val cipherBytes = decodedSize(ciphertext)
            ?: return "The history checkpoint could not be prepared for upload. Try again."
        if (cipherBytes > MAX_SNAPSHOT_BYTES) {
            return "Your encrypted history checkpoint is ${cipherBytes / (1024 * 1024)} MiB, over " +
                "the server's ${MAX_SNAPSHOT_BYTES / (1024 * 1024)} MiB limit. Sync more often to " +
                "keep the history small enough to compact."
        }
        return null
    }
}
