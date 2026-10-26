package tech.egrie.soundtrail.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class HttpResponse(
    val code: Int,
    val body: String,
    /**
     * Response headers with lower-cased names. The status line's null key is dropped.
     *
     * Only the headers a caller actually reasons about are used — today that is `Retry-After`,
     * which is how the sync client learns how long a rate limit lasts instead of guessing.
     */
    val headers: Map<String, List<String>> = emptyMap()
) {
    /** The provider's own explanation, when the response carries one. */
    fun detail(): String = runCatching {
        val json = JSONObject(body)
        json.optString("error_description").ifBlank {
            json.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                json.optString("error")
            }
        }
    }.getOrDefault("")

    /** One header's first value, or null when the server did not send it. */
    fun header(name: String): String? =
        headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }

    /**
     * `Retry-After` in whole seconds, when the server sent a value this app can use.
     *
     * The HTTP-date form is deliberately not parsed: every endpoint this app talks to answers
     * with a delay in seconds, and inventing a clock comparison to handle a form nobody sends
     * would add a way to be wrong about how long to wait.
     */
    val retryAfterSeconds: Int?
        get() = header("retry-after")?.trim()?.toIntOrNull()?.takeIf { it in 0..86_400 }

    /** True for any 3xx. This app never follows a redirect, so one of these is always an error. */
    val isRedirect: Boolean get() = code in 300..399
}

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
        val trimmed = raw.trim().trimEnd('/')
        val value = when {
            trimmed.isBlank() -> throw EndpointPolicyException("Enter the address of your sync server.")
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }
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

/**
 * Shared plumbing for the app's hand-rolled HTTP clients. No third-party HTTP dependency.
 *
 * Three rules live here because this is the one place every request passes through:
 *
 * 1. **Redirects are never followed.** `instanceFollowRedirects = false` stays off, and a 3xx is
 *    reported to the caller as the response it was. Following one would let a server, or whoever
 *    answers for it, move a request — and the credentials in its headers — to a host the caller
 *    never chose. Callers treat [HttpResponse.isRedirect] as an error.
 * 2. **Cleartext is refused** for anything the request's [TransportRule] does not cover, by
 *    [EndpointPolicy.requireSecureTransport], before the connection is opened.
 * 3. **Nothing is logged.** In particular the [authorization] argument — a bearer token, a
 *    Jellyfin authorization header, or an API key — is set on the connection and never written
 *    anywhere else; `CredentialLoggingTest` fails the build if that changes.
 */
internal suspend fun httpRequest(
    address: String,
    method: String = "GET",
    body: String? = null,
    authorization: String? = null,
    contentType: String? = null,
    /** Extra request headers, for APIs that take their credential in a header of their own. */
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 10_000,
    /** How this endpoint may be reached. The default requires HTTPS off the listener's network. */
    transport: TransportRule = TransportRule.HTTPS_UNLESS_LOCAL
): HttpResponse = withContext(Dispatchers.IO) {
    EndpointPolicy.requireSecureTransport(address, transport)
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        instanceFollowRedirects = false
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Cache-Control", "no-store")
        authorization?.let { setRequestProperty("Authorization", it) }
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        if (body != null) {
            doOutput = true
            setRequestProperty(
                "Content-Type",
                contentType ?: "application/x-www-form-urlencoded; charset=UTF-8"
            )
        }
    }
    try {
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseHeaders = connection.headerFields.entries
            .mapNotNull { (name, values) -> name?.lowercase()?.let { it to values.orEmpty() } }
            .toMap()
        HttpResponse(
            code,
            stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
            responseHeaders
        )
    } finally {
        connection.disconnect()
    }
}

internal fun form(vararg params: Pair<String, String>): String = params.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}

/**
 * An authorization header reduced to something safe to put in front of a person.
 *
 * Nothing in this app logs today, and [httpRequest] never writes its `authorization` argument
 * anywhere but the request header. This exists so that the first diagnostic line somebody adds
 * has a correct default to reach for instead of the raw value: a bearer token, a Jellyfin
 * authorization header and an API key are all equally unreadable afterwards, and the shape of the
 * value is still visible, which is usually what a diagnostic actually needed.
 *
 * `CredentialLoggingTest` fails the build if a credential is ever interpolated into a log call,
 * and if this function stops redacting.
 */
internal fun redactAuthorization(value: String?): String = when {
    value.isNullOrBlank() -> "(none)"
    else -> {
        val scheme = value.substringBefore(' ', "")
        // No scheme at all: a bare token or key. Nothing about it is safe to echo, not even a
        // guess at what kind of credential it was.
        if (scheme.isBlank() || scheme == value) "***" else "$scheme ***"
    }
}
