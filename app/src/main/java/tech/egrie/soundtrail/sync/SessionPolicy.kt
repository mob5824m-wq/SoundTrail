package tech.egrie.soundtrail.sync

/**
 * The rules about the sync account's own lifetime: how often a session may be renewed, what a
 * password has to look like, and when a server that has fallen behind is a warning rather than a
 * failure.
 *
 * Each of these is a decision the contract states in numbers, and a number buried in a `suspend`
 * function that needs a Context, a Keystore and a reachable server cannot be tested — it can only
 * be trusted. Here they can be tested, and both the service and the form the listener types into
 * read the same constant, so the "10–128 characters" on screen cannot drift away from the rule the
 * server actually enforces.
 */
internal object SessionPolicy {

    /** The server's own password rule, mirrored so a refusal does not cost a round trip. */
    const val PASSWORD_MIN_LENGTH = 10
    const val PASSWORD_MAX_LENGTH = 128

    /**
     * How often a session may be checked. Renewal kills the token it replaces, so this is what
     * keeps a working session from being invalidated on every launch of the app.
     */
    const val RENEW_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    /** How close to expiry is close enough to be worth that invalidation. Sessions last 30 days. */
    const val RENEW_WITHIN_MILLIS = 7L * 24L * 60L * 60L * 1000L

    /**
     * Whether a session was checked recently enough that checking again is not worth doing.
     *
     * @param now the current time in milliseconds
     * @param lastCheckAt when a check last ran; 0 means never, which is never "recently"
     * @param force ignore the interval — used when a sync has just failed on an expired session and
     *   the answer is needed now rather than tomorrow
     */
    fun checkedRecently(now: Long, lastCheckAt: Long, force: Boolean = false): Boolean =
        !force && now - lastCheckAt < RENEW_CHECK_INTERVAL_MILLIS

    /**
     * Whether a session is close enough to expiry to be worth renewing.
     *
     * An expiry the server has never stated ([expiresAt] 0 or less) counts as due. A token with no
     * recorded expiry is exactly the one worth asking about: treating it as valid would leave the
     * session to die on its own and be discovered by a failed sync.
     */
    fun renewalDue(now: Long, expiresAt: Long): Boolean =
        expiresAt <= 0L || expiresAt - now <= RENEW_WITHIN_MILLIS

    /**
     * What is wrong with a new password, in the sentence the listener is shown, or null when it is
     * acceptable.
     *
     * A blank password is out of range rather than a special case — that is what an untouched form
     * field looks like to this rule — so a caller that does not want to complain at an empty field
     * decides that for itself before asking.
     */
    fun passwordProblem(currentPassword: String, newPassword: String): String? = when {
        newPassword.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH ->
            "Use a password of $PASSWORD_MIN_LENGTH to $PASSWORD_MAX_LENGTH characters."
        currentPassword.isNotEmpty() && newPassword == currentPassword ->
            "The new password has to differ from the current one."
        else -> null
    }

    /**
     * Whether a server sequence that is lower than the highest this device has already seen is a
     * rollback worth warning about.
     *
     * @param reportedSequence the `latestSequence` the response carried; negative means it carried
     *   none, which is evidence of nothing
     * @param highestSeen the highest this device has recorded; negative means none yet
     *
     * Equal is normal — nothing was appended since the last page — so only *strictly* older is a
     * rollback, and the first sequence ever seen simply becomes the highest rather than being
     * reported as one. It is a warning and never an error: the local library is intact, restoring a
     * server from a database backup is a legitimate thing for whoever runs it to have done, and
     * refusing to sync would turn a recoverable situation into a dead end.
     */
    fun rollbackIsWarning(reportedSequence: Long, highestSeen: Long): Boolean =
        reportedSequence >= 0L && highestSeen >= 0L && reportedSequence < highestSeen
}
