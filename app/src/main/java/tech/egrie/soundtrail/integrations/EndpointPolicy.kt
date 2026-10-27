package tech.egrie.soundtrail.integrations

import java.net.URL

/** Refused before a request is even opened, because the address is not one this app may use. */
internal class EndpointPolicyException(message: String) : Exception(message)

/**
 * Which transport rule one request is made under.
 *
 * The default is the strict one — HTTPS, with no exception at all — so a new integration is safe
 * by doing nothing and has to ask, in writing, for the one exception that exists.
 */
internal enum class TransportRule {
    /**
     * HTTPS, and nothing else.
     *
     * This is the rule for everything that carries a credential or personal data somewhere else:
     * the sync server, the mood AI providers, TheAudioDB. A private address is **not** an
     * exception to it. "It is only my own network" is the assumption that fails quietly: a
     * `192.168.1.20` can be a flatmate's laptop, a printer with a web server on it, a router
     * answering for a name it should not, or a network that is not the listener's at all because
     * the phone joined a café's — and this is precisely the traffic carrying an API key or a
     * bearer token. Where the address really is the listener's own box, HTTPS costs one
     * certificate on one machine.
     */
    HTTPS,

    /**
     * The listener's own Jellyfin server, wherever it is.
     *
     * Plain HTTP is allowed for this one integration, and only this one, even on a public host,
     * because a self-hosted server without a certificate is the normal case and the app has no
     * other music source: refusing it would leave the app with nothing to play at all. The
     * address itself is still normalized and validated by [EndpointPolicy.jellyfinServer], which
     * refuses credentials in the authority, queries, fragments and traversal segments.
     */
    JELLYFIN
}

/**
 * Which endpoints may be reached over plain HTTP, decided in code rather than left to the
 * network security config.
 *
 * `res/xml/network_security_config.xml` permits cleartext for **every** host, and it has to: a
 * self-hosted Jellyfin server on a home network usually has no certificate, and refusing that
 * would break the one music source this app has. But a config that permissive is not a policy, it
 * is the absence of one — so the rule lives here instead, at the single funnel every request in
 * the app goes through ([httpRequest]), where it cannot be forgotten by a new integration.
 *
 * In short: **HTTPS for everything, plain HTTP only for the listener's own Jellyfin server.** A
 * private or local-looking address is not an exception — see [TransportRule.HTTPS] for why that
 * exception is not as safe as it looks. What is never allowed is a credential crossing a network
 * in the clear.
 *
 * The sync server does not go through this rule at all. [syncServer] requires `https://` outright
 * and refuses an address carrying credentials, a query, a fragment or a `..` segment, because that
 * one endpoint holds an account password, a bearer token and the encrypted library, and its own
 * deployment docs say to put a TLS proxy in front of it and never expose the port directly.
 */
internal object EndpointPolicy {

    /**
     * The reason an address may not be requested as it stands, or null when it may.
     *
     * Under [TransportRule.JELLYFIN] only a non-HTTP(S) scheme is a problem; under the default
     * rule, plain HTTP is a problem wherever the host happens to live.
     */
    fun cleartextProblem(address: String, rule: TransportRule = TransportRule.HTTPS): String? {
        val url = runCatching { URL(address) }.getOrNull()
            ?: return "That address is not a usable URL."
        val scheme = url.protocol.lowercase()
        if (scheme == "https") return null
        if (scheme != "http") return "Only http and https addresses are supported."
        if (rule == TransportRule.JELLYFIN) return null
        val host = url.host?.lowercase().orEmpty()
        if (host.isBlank()) return "That address has no host."
        // Worded as what to do rather than as what was refused: the listener typed an address that
        // looks right, and the useful answer is the scheme it needs.
        return "Plain HTTP is only allowed for your own Jellyfin server. Use https:// for $host."
    }

    /**
     * Refuses an address that would carry a request — and any credential in it — over plain HTTP
     * to somewhere the rule does not cover. Called by [httpRequest] before the connection is
     * opened, so nothing is ever sent in order to decide whether to send it.
     */
    fun requireSecureTransport(address: String, rule: TransportRule = TransportRule.HTTPS) {
        cleartextProblem(address, rule)?.let { throw EndpointPolicyException(it) }
    }

    /**
     * The sync server's address, normalized and validated: HTTPS only, with no local exception.
     *
     * A bare hostname or `host:port` is accepted and given `https://`, because that is what people
     * type for a server they run themselves. Everything the address could hide — credentials in
     * the authority, a query, a fragment, a traversal segment — is refused rather than trimmed,
     * so the URL that comes back is the URL that will be requested.
     *
     * @throws EndpointPolicyException with a message that can be shown to the listener as-is
     */
    fun syncServer(raw: String): String {
        val typed = raw.trim()
        if (typed.isBlank()) throw EndpointPolicyException("Enter the address of your sync server.")
        // Whether a scheme was typed has to be decided *before* trailing slashes are trimmed:
        // "https://" trimmed first becomes "https:", which no longer looks scheme-ful and would be
        // given a second "https://" — producing "https://https:", a URL that parses happily with
        // the host "https" because RFC 3986 permits an empty port. Deciding first means the
        // scheme-only address stays "https:", whose host is null and is therefore refused.
        val hasScheme = typed.contains("://")
        val trimmed = typed.trimEnd('/')
        val value = if (hasScheme) trimmed else "https://$trimmed"
        val uri = runCatching { java.net.URI(value) }.getOrNull()
            ?: throw EndpointPolicyException("Enter the HTTPS address of your sync server.")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.query != null || uri.fragment != null || value.contains(' ') ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) {
            throw EndpointPolicyException(
                "Sync needs an HTTPS server URL, for example https://sync.example.net."
            )
        }
        if (uri.path.orEmpty().split('/').any { it == ".." }) {
            throw EndpointPolicyException("That server address is not valid.")
        }
        val authority = uri.rawAuthority?.lowercase()
            ?: throw EndpointPolicyException("That server address is not valid.")
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "https://$authority$path"
    }

    /**
     * A Jellyfin server's address, normalized. This is the one integration that may be plain HTTP
     * wherever it lives; [TransportRule.JELLYFIN] is what says so at request time.
     */
    fun jellyfinServer(raw: String): String? = JellyfinApi.normalizeServerUrl(raw)
}
