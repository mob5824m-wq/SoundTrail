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
