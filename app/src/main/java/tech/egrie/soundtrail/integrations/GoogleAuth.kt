package tech.egrie.soundtrail.integrations

/** How a saved Google connection is doing, as shown on the Connect tab. */
internal enum class GoogleAuthState(val label: String) {
    /** No Google connection is saved on this device. */
    NOT_CONNECTED("Not connected"),

    /** Saved and readable; the access token refreshes by itself when it expires. */
    CONNECTED("Connected"),

    /** Saved, but the last attempt to reach Google failed. Nothing was deleted. */
    OFFLINE("Saved, waiting for a connection"),

    /** Google says the grant is gone (revoked, expired, or a password change). One tap fixes it. */
    NEEDS_RECONNECT("Needs one tap to reconnect"),

    /** Saved, but this device's key store could not read it. Nothing was deleted. */
    UNREADABLE("Saved, but unreadable on this device")
}

/**
 * The connection's health as the app knows it. Everything here is metadata the app has kept
 * locally (a label, timestamps, a method name) — never a token value.
 */
internal data class GoogleAuthHealth(
    val state: GoogleAuthState,
    /** The channel or account label shown to the listener, e.g. a YouTube channel name. */
    val account: String? = null,
    /** Always "Client ID (browser)" when connected. */
    val method: String? = null,
    /** When the current grant was first saved. */
    val issuedAt: Long? = null,
    /** When the access token was last renewed without the listener doing anything. */
    val lastRefreshedAt: Long? = null,
    /** When the connection was last confirmed against Google. */
    val lastVerifiedAt: Long? = null,
    /** One line explaining the state. */
    val detail: String? = null,
    /** What the listener should do next, when anything is wrong. */
    val hint: String? = null,
    /** True when the failure looks like Google's own expiry of a Testing-mode grant. */
    val testingExpirySuspected: Boolean = false
) {
    /** True when the listener has to do something for the connection to work again. */
    val needsAttention: Boolean
        get() = state == GoogleAuthState.NEEDS_RECONNECT || state == GoogleAuthState.UNREADABLE

    /** True when a one-tap reconnect (no re-typing a Client ID) is possible. */
    val reconnectable: Boolean get() = needsAttention
}

/**
 * The rules that decide whether a failed token refresh should keep the saved connection or ask
 * for a new sign-in. Pure functions, so the behaviour that protects a listener's login is unit
 * tested rather than guessed at.
 *
 * The governing principle: only Google itself may invalidate a saved credential. Anything the
 * app cannot attribute to Google (a timeout, a 5xx, a rate limit, an unparseable body) keeps
 * the credential and retries later, because throwing away a working login is what forces people
 * to sign in again.
 */
internal object GoogleAuthPolicy {
    const val DAY_MS = 86_400_000L

    /**
     * Google's OAuth error codes that mean the grant itself is gone. Deliberately a short list:
     * a code the app does not recognise keeps the saved login, because asking someone to sign in
     * again is a bigger cost than retrying a request that a future build may handle.
     * `invalid_request` is not here — it usually describes a malformed request, not a dead grant.
     */
    private val permanentErrors = setOf(
        "invalid_grant", "invalid_client", "unauthorized_client", "access_denied",
        "expired", "revoked"
    )

    /** Phrases Google uses in the description when a grant expired or was revoked. */
    private val permanentPhrases = listOf(
        "invalid_grant", "expired or revoked", "has been revoked", "token has been",
        "revoked", "deleted"
    )

    /**
     * True only when Google explicitly rejected the grant. A 5xx, a 429, an empty body, or an
     * unrecognised error is treated as transient and keeps the saved connection.
     */
    fun isPermanentGrantFailure(code: Int, error: String?, description: String?): Boolean {
        if (code == 429 || code in 500..599) return false
        if (code !in 400..401) return false
        val normalizedError = error?.trim()?.lowercase().orEmpty()
        if (normalizedError.isNotBlank() && normalizedError in permanentErrors) return true
        val text = description?.lowercase().orEmpty()
        if (text.isBlank()) return false
        return permanentPhrases.any { text.contains(it) }
    }

    /**
     * Google expires the refresh tokens of projects whose OAuth consent screen is still in
     * Testing after seven days. When a grant that was issued about a week ago stops working,
     * that is almost always the cause — and it has a real fix, so the app says so instead of
     * only asking for another sign-in.
     */
    fun testingExpirySuspected(issuedAt: Long?, failedAt: Long): Boolean {
        val issued = issuedAt ?: return false
        val age = failedAt - issued
        return age in (6L * DAY_MS + DAY_MS / 3)..(10L * DAY_MS)
    }

    /** The sentence shown under the connection when it needs the listener's attention. */
    fun helpText(
        state: GoogleAuthState,
        method: String?,
        clientIdRemembered: Boolean,
        testingExpirySuspected: Boolean
    ): String? = when (state) {
        GoogleAuthState.NEEDS_RECONNECT -> when {
            testingExpirySuspected ->
                "Google expired this grant. If your Cloud project's OAuth consent screen is still in " +
                    "Testing, Google expires grants after 7 days — publish the project (or re-add yourself " +
                    "as a test user) to stop the weekly sign-in. Reconnecting is one tap here" +
                    (if (clientIdRemembered) ": your Client ID is remembered." else ".")
            method?.startsWith("Client") == true || clientIdRemembered ->
                "Google is no longer accepting the saved grant. Reconnect — your Client ID is remembered, " +
                    "so it is one tap and a browser confirmation. Your pins, playlists, and audio are untouched."
            else ->
                "Google is no longer accepting the saved grant. Connect the account again; nothing else on " +
                    "this device is affected."
        }
        GoogleAuthState.UNREADABLE ->
            "This device's key store could not read the saved Google tokens, so they were kept rather than " +
                "deleted. Reconnect once to store fresh ones; if it happens again after an app update, " +
                "reinstall with the same signing key (see the README)."
        GoogleAuthState.OFFLINE ->
            "Could not reach Google just now. The saved connection is intact and will be used again as soon " +
                "as the device is online."
        GoogleAuthState.CONNECTED -> null
        GoogleAuthState.NOT_CONNECTED -> null
    }

    /** A short "verified 3 h ago" style stamp for the connection card. */
    fun describeAge(now: Long, then: Long?): String? {
        val value = then ?: return null
        if (value <= 0L || value > now) return null
        val minutes = (now - value) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}
