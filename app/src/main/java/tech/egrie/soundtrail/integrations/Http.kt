package tech.egrie.soundtrail.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class HttpResponse(val code: Int, val body: String) {
    /** The provider's own explanation, when the response carries one. */
    fun detail(): String = runCatching {
        val json = JSONObject(body)
        json.optString("error_description").ifBlank {
            json.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                json.optString("error")
            }
        }
    }.getOrDefault("")
}

/** Shared plumbing for the small, hand-rolled OAuth clients. No third-party HTTP dependency. */
internal suspend fun httpRequest(
    address: String,
    method: String = "GET",
    body: String? = null,
    authorization: String? = null,
    contentType: String? = null,
    /** Extra request headers, for APIs that take their credential in a header of their own. */
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 10_000
): HttpResponse = withContext(Dispatchers.IO) {
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
        HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } finally {
        connection.disconnect()
    }
}

internal fun form(vararg params: Pair<String, String>): String = params.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}
