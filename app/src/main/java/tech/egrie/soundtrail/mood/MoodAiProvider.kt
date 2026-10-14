package tech.egrie.soundtrail.mood

/** The model that answers a mood reading when the listener has not named one. */
internal const val DEFAULT_OLLAMA_MODEL = "llama3.2"

/**
 * Where a mood reading happens.
 *
 * The order is deliberate: the on-device engine is the default and needs nothing, Ollama keeps
 * everything on hardware the listener controls, the OpenAI-compatible endpoint covers every
 * service that speaks `/v1/chat/completions` (a hosted API, a model on their own machine, or a
 * gateway in front of something that has no API of its own), and the fixed hosted model is last.
 * Nothing in the app requires any of them — [MoodEngine] always works — so every choice here is
 * additive.
 */
internal enum class MoodAiProvider(
    val id: String,
    val label: String,
    /** The short name on the picker pills. */
    val short: String,
    val blurb: String,
    val needsUrl: Boolean = false,
    val needsKey: Boolean = false,
    val keyHint: String = "",
    val keyUrl: String = "",
    val defaultModel: String = "",
    /** True when nothing leaves the device for this provider. */
    val local: Boolean = false
) {
    ON_DEVICE(
        id = "on_device",
        label = "On-device engine",
        short = "On-device",
        blurb = "A small, explainable reading that runs on the phone. No network, no account, " +
            "nothing sent — it is what the app falls back to when anything else fails.",
        local = true
    ),

    OLLAMA(
        id = "ollama",
        label = "Ollama (your own server)",
        short = "Ollama",
        blurb = "A full model on hardware you control — this phone, a computer on your network, " +
            "or a server you run. Only your mood description is sent, and only to that address.",
        needsUrl = true,
        keyHint = "Optional: only if a proxy in front of Ollama asks for a token",
        defaultModel = DEFAULT_OLLAMA_MODEL
    ),

    OPENAI_COMPATIBLE(
        id = "openai_compatible",
        label = "OpenAI-compatible endpoint",
        short = "OpenAI",
        blurb = "Any server that speaks OpenAI's chat-completions API: OpenAI itself, OpenRouter, " +
            "a model running on your own machine, a gateway in front of another service, or a " +
            "bridge you run for one that has no API of its own. Only your mood description is " +
            "sent, and only to the address you type.",
        needsUrl = true,
        needsKey = true,
        keyHint = "Required by OpenAI and most gateways; a server on your own machine may take none",
        keyUrl = "",
        defaultModel = ""
    ),

    GEMINI(
        id = "gemini",
        label = "Google AI Studio (Gemini)",
        short = "Gemini",
        blurb = "A hosted model with your own API key. Only your mood description is sent, and " +
            "only to Google.",
        needsKey = true,
        keyHint = "Paste an API key from Google AI Studio",
        keyUrl = "https://aistudio.google.com/app/apikey",
        defaultModel = MoodStore.DEFAULT_AI_MODEL
    );

    companion object {
        /** What a fresh install uses: no key, no address, no network. */
        val default: MoodAiProvider = ON_DEVICE

        /** Never throws: an unknown or blank id means the on-device engine. */
        fun from(id: String?): MoodAiProvider =
            values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: default
    }
}

/**
 * Ollama's address as the listener typed it, turned into a base URL the app can call.
 *
 * People paste whatever their browser shows: `192.168.1.20:11434`, a trailing slash, or the
 * whole `http://host:11434/api/chat` endpoint they found in the docs. All of those mean the same
 * server, so they are all accepted — and a value that cannot be a server address is rejected
 * rather than silently turned into a request to nowhere.
 */
internal object MoodAiUrls {
    private val endpointSuffixes = listOf(
        "/api/chat", "/api/generate", "/api/tags", "/api/ps", "/api/version",
        "/v1/chat/completions", "/v1"
    )

    /** The endings people paste from an OpenAI-compatible README. */
    private val openAiEndpointSuffixes = listOf(
        "/chat/completions", "/v1/chat/completions", "/completions", "/models"
    )

    /** A path a base URL may carry in front of `/chat/completions`: `/v1`, `/openai/v1`, … */
    private val openAiPath = Regex("^[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$")

    /** A host:port pair that could plausibly resolve: a name, an IPv4, or a bracketed IPv6. */
    private val authority = Regex(
        "^([A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?\\.?|\\[[0-9A-Fa-f:]+\\])(:\\d{1,5})?$"
    )

    fun normalize(input: String): String? {
        val typed = input.trim()
        if (typed.isBlank()) return null
        val withScheme = if (typed.contains("://")) typed else "http://$typed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        var value = withScheme.substringAfter("://").trimEnd('/')
        if (value.isBlank()) return null
        var stripped = true
        while (stripped) {
            stripped = false
            endpointSuffixes.forEach { suffix ->
                if (value.length > suffix.length && value.endsWith(suffix, ignoreCase = true)) {
                    value = value.dropLast(suffix.length).trimEnd('/')
                    stripped = true
                }
            }
        }
        if (value.isBlank() || !authority.matches(value.substringBefore('/'))) return null
        return "$scheme://$value"
    }

    /**
     * An OpenAI-compatible base, as the listener typed it, turned into the address the app
     * appends `/chat/completions` and `/models` to.
     *
     * People paste `api.openai.com`, a whole `…/v1/chat/completions` endpoint found in a README,
     * or a machine on their network with no scheme at all. All of those mean the same base.
     * Unlike [normalize] — which is Ollama's and drops every path — this keeps the path, because
     * a prefix is part of the address here (`/v1`, or `/openai/v1` behind a proxy), and appends
     * `/v1` when the listener typed only a host, since every one of these APIs lives under it.
     *
     * A host typed with no scheme defaults to plain HTTP only when it is plainly local — a
     * loopback name, a private address, `something.local`, or a bare machine name with no dot.
     * Those are the model servers and bridges people actually run at home, and they usually have
     * no TLS. Everything else is assumed to be public and HTTPS, because this provider sends a
     * key to whatever is on the other end.
     */
    fun normalizeOpenAi(input: String): String? {
        val typed = input.trim()
        if (typed.isBlank()) return null
        val withScheme =
            if (typed.contains("://")) typed else "${if (looksLocal(typed)) "http" else "https"}://$typed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        var value = withScheme.substringAfter("://").trim()
        if (value.isBlank()) return null
        var stripped = true
        while (stripped) {
            stripped = false
            openAiEndpointSuffixes.forEach { suffix ->
                if (value.length > suffix.length && value.endsWith(suffix, ignoreCase = true)) {
                    value = value.dropLast(suffix.length).trimEnd('/')
                    stripped = true
                }
            }
        }
        value = value.trimEnd('/')
        if (value.isBlank()) return null
        if (!authority.matches(value.substringBefore('/'))) return null
        val path = value.substringAfter('/', "")
        // A path is welcome (/v1, /openai/v1); anything that is not one — a query, a space, a
        // traversal — is not an address this app should build a request from.
        if (path.isNotBlank() &&
            (!openAiPath.matches(path) || path.split('/').any { it == ".." })
        ) return null
        val full = if (path.isBlank()) "$value/v1" else value
        return "$scheme://$full"
    }

    /** The chat-completions endpoint of a normalised OpenAI-compatible base. */
    fun openAiChatUrl(base: String): String = "$base/chat/completions"

    /** The model list of a normalised OpenAI-compatible base, where the server has one. */
    fun openAiModelsUrl(base: String): String = "$base/models"

    /** True when a host with no scheme is plainly a machine on the listener's own network. */
    private fun looksLocal(input: String): Boolean {
        val typed = input.substringAfter("://").substringBefore('/')
        val host = (if (typed.startsWith("[")) {
            typed.substringBefore(']') + "]"
        } else {
            typed.substringBefore(':')
        }).lowercase()
        if (host.isBlank()) return false
        if (host == "localhost" || host == "[::1]" || host == "::1") return true
        if (host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.startsWith("172.") && host.substringAfter("172.").substringBefore('.').toIntOrNull()
                ?.let { it in 16..31 } == true
        ) return true
        // A bare machine name is a LAN name; a public host nearly always carries a dot.
        return !host.contains('.') && !host.contains(':')
    }

    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    /**
     * True when the address is this device itself. Worth saying out loud on a phone: `localhost`
     * is the phone, not the computer the listener may be thinking of.
     */
    fun isLoopback(url: String): Boolean {
        val host = hostOf(url)
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    /** The address in a form worth showing back to the listener. */
    fun display(url: String): String = url.removePrefix("http://").removePrefix("https://")
}
