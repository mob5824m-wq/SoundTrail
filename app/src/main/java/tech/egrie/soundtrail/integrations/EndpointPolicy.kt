package tech.egrie.soundtrail.integrations

import java.net.URL

/** Refused before a request is even opened, because the address is not one this app may use. */
internal class EndpointPolicyException(message: String) : Exception(message)

/**
 * Which transport rule one request is made under.
 *
 * The default is the strict one, so a new integration gets HTTPS by doing nothing at all and has
 * to ask, in writing, for the exception.
 */
internal enum class TransportRule {
    /**
     * HTTPS, unless the host is plainly the listener's own machine or network.
     *
     * This is the rule for everything that carries a credential or personal data to somewhere
     * else: the sync server, the mood AI providers, TheAudioDB. A model server on `localhost` or
     * `192.168.1.20` is the same situation as a LAN Jellyfin box and is allowed to stay plain
     * HTTP, because that is how those servers are actually run at home — and refusing it would
     * break a setup that works today without making anything safer.
     */
    HTTPS_UNLESS_LOCAL,

    /**
     * The listener's own Jellyfin server, wherever it is.
     *
     * Plain HTTP is allowed for this one integration even on a public host, because a self-hosted
     * server without a certificate is the normal case and the app has no other music source. The
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
 * In short: **HTTPS for everything, plain HTTP only for the listener's own Jellyfin server or a
 * host on their own machine or network.** What is never allowed is a credential crossing the
 * public internet in the clear.
 *
 * The sync server is stricter still — HTTPS with no local exception at all — because it carries an
 * account password, a bearer token and the encrypted library, and its own deployment docs say to
 * put a TLS proxy in front of it and never expose the port directly. See [syncServer].
 */
internal object EndpointPolicy {

    /**
     * True when a host is plainly the listener's own machine or their own network.
     *
     * A name with no dot in it is treated as local because that is how people address a box on
     * their LAN (`http://jellyfin:8096`, `http://ollama:11434`); a public host always has one.
     */
    fun isOnPremises(host: String): Boolean {
        val value = host.trim().lowercase().removeSuffix(".")
        if (value.isEmpty()) return false
        val bare = value.removePrefix("[").removeSuffix("]")
        if (bare == "localhost" || value.endsWith(".localhost")) return true
        if (bare == "::1") return true
        // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
        if (bare.startsWith("fc") || bare.startsWith("fd") || bare.startsWith("fe8")) return true
        if (value.endsWith(".local") || value.endsWith(".lan")) return true
        val parts = bare.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            return first == 10 ||
                first == 127 ||
                (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) ||
                (first == 169 && second == 254)
        }
        // A bare machine name with no dot: a box on the listener's own network.
        return '.' !in bare && ':' !in bare
    }

    /**
     * The reason an address may not be requested as it stands, or null when it may.
     *
     * Under [TransportRule.JELLYFIN] only a non-HTTP(S) scheme is a problem; under the default
     * rule, plain HTTP to a host that is not the listener's own is.
     */
    fun cleartextProblem(address: String, rule: TransportRule = TransportRule.HTTPS_UNLESS_LOCAL): String? {
        val url = runCatching { URL(address) }.getOrNull()
            ?: return "That address is not a usable URL."
        val scheme = url.protocol.lowercase()
        if (scheme == "https") return null
        if (scheme != "http") return "Only http and https addresses are supported."
        if (rule == TransportRule.JELLYFIN) return null
        val host = url.host?.lowercase().orEmpty()
        if (host.isBlank()) return "That address has no host."
        return if (isOnPremises(host)) {
            null
        } else {
            "Plain HTTP is only allowed for a server on this device or your own network. " +
                "Use https:// for $host."
        }
    }

    /**
     * Refuses an address that would carry a request — and any credential in it — over plain HTTP
     * to somewhere the rule does not cover. Called by [httpRequest] before the connection is
     * opened, so nothing is ever sent in order to decide whether to send it.
     */
    fun requireSecureTransport(address: String, rule: TransportRule = TransportRule.HTTPS_UNLESS_LOCAL) {
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
