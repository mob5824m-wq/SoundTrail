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

    /**
     * Arena's gateway, which speaks this same API — so Arena is one of the endpoints this provider
     * covers rather than a provider of its own.
     *
     * These three values come from Arena's own portal (`portal.api.preview.arena.ai`: its OpenCode
     * page gives `…/v1` as the base URL, its Claude Code page gives `coding-router-preview` as the
     * router alias, and both point at the Keys page). The gateway is labelled **preview**, so if
     * Arena moves it — a GA host, a new alias — these are the only lines that need to change.
     * Everything else is typed by the listener.
     */
    const val ARENA_BASE_URL = "https://api.preview.arena.ai/v1"
    const val ARENA_MODEL = "coding-router-preview"
    const val ARENA_KEYS_URL = "https://portal.api.preview.arena.ai/dashboard/keys"

    /**
     * Every alias Arena's own setup script accepts for its coding router, in the order it
     * prefers them. A key may have any one of these enabled rather than the first — the script
     * looks for `coding-router-preview` and falls back to whichever else is present — so the app
     * reads a model list the same way instead of leaving an alias that would fail the call.
     */
    val ARENA_ROUTER_MODELS = listOf(
        "coding-router-preview",
        "claude-coding-router-preview",
        "claude-max-v0",
        "max-v0"
    )

    /**
     * The refusals Arena's gateway makes about the **client** rather than the key.
     *
     * Arena's free router is documented as serving its own harnesses — interactive Claude Code,
     * the Code tab in Claude Desktop, and OpenCode — so a request from anything else is turned
     * away with `free_router_harness_restricted`, and a client the workspace blocks (Cowork,
     * print mode, the Agent SDK) with `organization_client_restricted`. Both are recognised by
     * code and never by wording, so a reworded sentence on Arena's side cannot change the advice
     * the app gives, and a genuinely bad key — which answers `invalid_request_error` — keeps the
     * advice about the key.
     */
    val ARENA_HARNESS_REFUSALS = listOf(
        "free_router_harness_restricted",
        "organization_client_restricted"
    )

    /** The router alias to use from an Arena model list: the preferred one, else the first present. */
    fun arenaRouterFrom(models: List<String>): String? =
        ARENA_ROUTER_MODELS.firstOrNull { alias -> models.any { it == alias } }

    /** True when a name is one of Arena's router aliases rather than a model of its own. */
    fun isArenaRouter(model: String): Boolean = ARENA_ROUTER_MODELS.any { it == model }

    /**
     * The model to keep from a list a key is enabled for.
     *
     * A key may be enabled for a router alias, for a handful of named models (Claude, GPT, Gemini,
     * Kimi and the rest), or for both. The alias is preferred when the key has one, because that is
     * what Arena's own setup script picks; when it has none, the first model the key is enabled for
     * is used, which is the same fallback `setup.sh` makes with `models[0]`. Either way the name
     * that comes back is one that authenticated discovery returned — never a guess.
     */
    fun arenaModelFrom(models: List<String>): String? =
        arenaRouterFrom(models) ?: models.firstOrNull()

    /** True when an address is Arena's gateway, so the app can offer its keys page. */
    fun isArena(base: String?): Boolean =
        base != null && hostOf(base) == "api.preview.arena.ai"

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
