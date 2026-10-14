package tech.egrie.soundtrail.mood

/** The model that answers a mood reading when the listener has not named one. */
internal const val DEFAULT_OLLAMA_MODEL = "llama3.2"

/**
 * Where a mood reading happens.
 *
 * The order is deliberate: the on-device engine is the default and needs nothing, Ollama keeps
 * everything on hardware the listener controls, and the hosted model is opt-in last. Nothing in
 * the app requires any of them — [MoodEngine] always works — so every choice here is additive.
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
