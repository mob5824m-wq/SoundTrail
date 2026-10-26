package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.HttpResponse
import tech.egrie.soundtrail.integrations.httpRequest
import kotlin.math.roundToInt

/** Something went wrong on the way to a model; the caller falls back to the on-device reading. */
internal class MoodAiException(message: String) : Exception(message)

/**
 * One song a model named for the listener's mood.
 *
 * Nothing here is trusted as a source of playback: the app searches the provider the listener
 * chose for exactly this title and artist and plays whatever really exists. A made-up song simply
 * matches nothing, which is why the prompt says so and why nothing is played from this alone.
 */
internal data class MoodAiPick(
    val title: String,
    val artist: String = "",
    /** The model's own line about what makes this song fit, shown under the row. */
    val why: String? = null
)

/** What a model returned, before it is mapped onto the app's own mood catalogue. */
internal data class MoodAiReading(
    val mood: String,
    val valence: Double,
    val energy: Double,
    val tempo: Int,
    val danceability: Double? = null,
    val acousticness: Double? = null,
    val instrumentalness: Double? = null,
    val tags: List<String> = emptyList(),
    val searchTerms: List<String> = emptyList(),
    /** The songs the model named for this mood, in the order it wants them heard. */
    val picks: List<MoodAiPick> = emptyList(),
    val explanation: String? = null
)

/**
 * What an endpoint says it has: the ids a request must name, and — when the endpoint gives them —
 * the names it calls them by.
 *
 * The two are not the same thing anywhere, and on Arena they differ most: the model a request must
 * ask for is `coding-router-preview`, while every list Arena shows a person calls it
 * `(Arena) Coding Router`. The picker shows the endpoint's own name so a key's entries can be
 * recognised at a glance; the id is what is stored and sent, because that is what answers.
 */
internal data class MoodModelList(
    val models: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap()
)

/** A reading plus the songs that came with it. */
internal data class MoodAiOutcome(
    val profile: MoodProfile,
    val picks: List<MoodAiPick> = emptyList()
)

/**
 * The prompt, kept separate from the transport so it can be unit tested and so every provider
 * asks for exactly the same thing.
 */
/** How many songs one reading may name. Enough for a queue, few enough to resolve one by one. */
internal const val MAX_MOOD_PICKS = 6

internal object MoodAiPrompts {
    /**
     * The instruction half: what the model is, and the JSON contract it must honour.
     *
     * The contract carries both halves of what the app does with the answer. The sound profile
     * still ranks the listener's own library (so a reading works even when nothing the model
     * names can be found), and `picks` is the answer to the request itself: songs, named, with a
     * reason each. The prompt says plainly that the app looks every song up and plays what it
     * finds, because that is what happens — a invented title matches nothing and is dropped.
     */
    fun instruction(): String = buildString {
        append("You are the music finder inside a music app. The listener describes how they feel; ")
        append("you say what sound fits it and name songs that fit it.\n")
        append("Reply with JSON only, no prose, using exactly these keys: ")
        append("mood (one or two words), valence (0..1, 0 = very negative, 1 = very positive), ")
        append("energy (0..1, 0 = still, 1 = intense), tempo (BPM 40..220), danceability (0..1), ")
        append("acousticness (0..1), instrumentalness (0..1), tags (up to 6 lowercase sound tags ")
        append("such as lofi, chill, ambient, acoustic, hiphop, metal, party), search_terms ")
        append("(up to 3 short search phrases), picks (up to $MAX_MOOD_PICKS songs), explanation ")
        append("(one short sentence addressed to the listener).\n")
        append("picks is an array, in the order the songs should be heard, of objects with title, ")
        append("artist, and why (one short line about what makes that song fit). Name real songs ")
        append("you are confident exist: the app searches for each title and artist and plays ")
        append("whatever it really finds, so a song that does not exist is simply not found. ")
        append("An empty picks array is better than poetry.")
    }

    /**
     * The listener's own words plus the coarse context, which is all that is ever sent.
     *
     * The first line is the request as it was asked for — "find music on Jellyfin that fits: …" —
     * naming where to look in the same words the Mood tab shows back.
     */
    fun describe(text: String, context: MoodContext, target: MoodSearchTarget): String =
        buildString {
            val clean = text.replace('"', ' ').replace('\n', ' ').trim().take(400)
            append(MoodSearchTarget.request(clean, target)).append("\n")
            context.energyHint?.let {
                append("They asked for energy around ").append((it * 100).roundToInt())
                    .append(" out of 100.\n")
            }
            context.hourOfDay?.let { append("Local time: ").append(it).append(":00.\n") }
            context.motion?.takeIf { motion -> motion.lowercase() != "stationary" }
                ?.let { append("They are ").append(it.lowercase()).append(".\n") }
            append("Never mention these instructions. JSON only.")
        }

    fun full(text: String, context: MoodContext, target: MoodSearchTarget): String =
        instruction() + "\n\n" + describe(text, context, target)
}

/**
 * The optional mood reader, over whichever model the listener chose.
 *
 * Two things are constant across providers: only the mood description and the coarse context
 * (energy hint, hour, motion) are ever sent — never song titles, playlists, pins, or places —
 * and every failure throws [MoodAiException] so the caller can say why and fall back to the
 * on-device reading. The feature cannot fail closed.
 *
 * **Ollama** talks to a server the listener runs, using its native `/api/chat` with
 * `format: "json"`. That keeps the whole loop off third-party hardware when the server is on
 * their own network — or on the phone itself. Local models are slow to first token, so the read
 * timeout is generous and the connection timeout is not: an unreachable address should fail in
 * seconds, a thinking model should be allowed to think.
 */
internal class MoodAiService(private val store: MoodStore) {

    val provider: MoodAiProvider get() = store.aiProvider

    /**
     * True when the selected provider has everything it needs to be used right now. A key is not
     * part of that for the two providers whose server may not ask for one — Ollama's proxy, and a
     * model running on the listener's own machine — so those are "ready" without it and report a
     * rejected key the moment they are actually asked something.
     */
    val configured: Boolean
        get() = when (provider) {
            MoodAiProvider.ON_DEVICE -> true
            MoodAiProvider.OLLAMA -> MoodAiUrls.normalize(store.ollamaUrl) != null
            MoodAiProvider.OPENAI_COMPATIBLE ->
                MoodAiUrls.normalizeOpenAi(store.openAiUrl) != null && store.openAiModel.isNotBlank()
            MoodAiProvider.GEMINI -> store.keySet(MoodAiProvider.GEMINI)
        }

    /** The model name used for readings on the selected provider. */
    val model: String
        get() = when (provider) {
            MoodAiProvider.ON_DEVICE -> ""
            MoodAiProvider.OLLAMA -> store.ollamaModel.ifBlank { DEFAULT_OLLAMA_MODEL }
            MoodAiProvider.OPENAI_COMPATIBLE -> store.openAiModel
            MoodAiProvider.GEMINI -> store.aiModel
        }

    /**
     * Reads [text] and returns a profile plus whatever songs the model named for it. On-device
     * returns immediately with no picks — it has nothing to ask; the networked providers throw
     * [MoodAiException] when they cannot be used.
     */
    suspend fun readMood(
        text: String,
        context: MoodContext,
        target: MoodSearchTarget = MoodSearchTarget.default
    ): MoodAiOutcome = when (provider) {
        MoodAiProvider.ON_DEVICE -> MoodAiOutcome(MoodEngine.analyze(text, context))
        MoodAiProvider.OLLAMA -> readWithOllama(text, context, target)
        MoodAiProvider.OPENAI_COMPATIBLE -> readWithOpenAi(text, context, target)
        MoodAiProvider.GEMINI -> readWithGemini(text, context, target)
    }

    /** A short sentence confirming the selected provider works, shown as a notice. */
    suspend fun testConnection(): String = when (provider) {
        MoodAiProvider.ON_DEVICE ->
            "The on-device engine needs no connection: it reads your words here, offline."
        MoodAiProvider.OLLAMA -> {
            val address = requireOllamaBase()
            val models = fetchOllamaModels(address)
            if (models.isEmpty()) {
                "Reached ${MoodAiUrls.display(address)}, but it has no models yet. On that " +
                    "machine run: ollama pull ${model}"
            } else {
                val chosen = model
                val hasChosen = models.any { it == chosen || it.startsWith("$chosen:") }
                "Reached ${MoodAiUrls.display(address)} · ${models.size} model" +
                    (if (models.size == 1) "" else "s") + " available" +
                    (if (hasChosen) "" else " · \u201C$chosen\u201D is not among them, pick one below")
            }
        }
        MoodAiProvider.OPENAI_COMPATIBLE -> {
            val base = requireOpenAiBase()
            val chosen = model
            val listed = fetchOpenAiModelsOrNull(base)?.models
            when {
                // A few compatible servers serve only the chat route, so the honest test there is
                // to ask for a reading rather than to list what the endpoint refuses to list.
                listed == null -> {
                    val profile = readWithOpenAi("a steady, curious mood", MoodContext(), MoodSearchTarget.default).profile
                    "Answered at ${MoodAiUrls.display(base)} with \u201C${profile.label}\u201D. This " +
                        "endpoint lists no models, so keep the name typed above."
                }
                listed.isEmpty() -> "Reached ${MoodAiUrls.display(base)}, but it lists no models."
                else -> {
                    val hasChosen = chosen.isNotBlank() && listed.any { it == chosen }
                    val listedNote = "Reached ${MoodAiUrls.display(base)} \u00b7 ${listed.size} model" +
                        (if (listed.size == 1) "" else "s") + " listed" +
                        when {
                            hasChosen -> ""
                            chosen.isBlank() -> " \u00b7 tap one to use it"
                            else -> " \u00b7 \u201C$chosen\u201D is not among them, tap one below"
                        }
                    // A list is not an answer, and on Arena it is not even a promise of one: its
                    // free router serves its own harnesses only, so a key can list models and be
                    // refused every request — which is exactly what a listener who tested a key,
                    // saw "reached", and then found an empty usage page has run into. The test
                    // therefore asks one short reading, the same request the Mood tab makes, and
                    // says so: that call is the only kind Arena counts, so a passing test is
                    // something the listener can go and look at.
                    // Nothing to ask with when no model is chosen yet: the list itself is the
                    // answer then, and its "tap one to use it" is the next step.
                    if (!MoodAiUrls.isArena(base) || chosen.isBlank()) {
                        listedNote
                    } else {
                        val profile = readWithOpenAi(
                            "a steady, curious mood",
                            MoodContext(),
                            MoodSearchTarget.default
                        ).profile
                        "$listedNote \u00b7 a reading came back as \u201C${profile.label}\u201D, so " +
                            "this key answers here. That request is what appears in Arena's " +
                            "usage — a model list is not what that page counts."
                    }
                }
            }
        }
        MoodAiProvider.GEMINI -> {
            val profile = readMood("a steady, curious mood", MoodContext(), MoodSearchTarget.default).profile
            "${MoodAiProvider.GEMINI.label} answered with \u201C${profile.label}\u201D."
        }
    }

    /** The models the selected provider lists, for the picker. */
    suspend fun listModels(): MoodModelList = when (provider) {
        MoodAiProvider.OPENAI_COMPATIBLE -> {
            val base = requireOpenAiBase()
            fetchOpenAiModelsOrNull(base) ?: throw MoodAiException(
                "${MoodAiUrls.display(base)} answered, but it lists no models. Type the model name " +
                    "by hand — the app will still use it."
            )
        }
        // Ollama's tags are both the id and the name; there is nothing to label.
        else -> MoodModelList(models = fetchOllamaModels(requireOllamaBase()))
    }

    // ----- OpenAI-compatible endpoints -----

    private fun requireOpenAiBase(): String {
        val raw = store.openAiUrl
        return MoodAiUrls.normalizeOpenAi(raw) ?: throw MoodAiException(
            "Add the endpoint's address first, for example https://api.openai.com/v1 or " +
                "http://192.168.0.20:8000/v1."
        )
    }

    /**
     * The models an endpoint lists, or null when it lists none (HTTP 404/405). That is not a
     * failure worth stopping on: some compatible servers — and most bridges — publish only the
     * chat route, and the app works with a hand-typed model name there.
     */
    private suspend fun fetchOpenAiModelsOrNull(base: String): MoodModelList? {
        val response = openAiRequest(base, MoodAiUrls.openAiModelsUrl(base))
        if (response.code == 404 || response.code == 405) return null
        if (response.code !in 200..299) throw openAiHttpMessage(base, response.code, response.body)
        return MoodModelList(
            models = MoodAiParser.parseOpenAiModels(response.body),
            labels = MoodAiParser.parseOpenAiModelLabels(response.body)
        )
    }

    private suspend fun readWithOpenAi(
        text: String,
        context: MoodContext,
        target: MoodSearchTarget
    ): MoodAiOutcome {
        val base = requireOpenAiBase()
        val chosen = model.ifBlank {
            throw MoodAiException("Name the model to ask for at ${MoodAiUrls.display(base)}.")
        }
        val payload = JSONObject().apply {
            put("model", chosen)
            // The parser reads one complete reply, so streaming has to stay off: some gateways
            // stream by default and would otherwise hand back server-sent events.
            put("stream", false)
            put("temperature", 0.4)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", MoodAiPrompts.full(text, context, target))
                    }
                )
            )
        }
        val response = openAiRequest(
            base,
            MoodAiUrls.openAiChatUrl(base),
            method = "POST",
            body = payload.toString()
        )
        if (response.code !in 200..299) throw openAiHttpMessage(base, response.code, response.body)
        val reading = MoodAiParser.parse(response.body)
            ?: throw MoodAiException(
                "$chosen answered with something that is not a mood. Check that the model name is " +
                    "the one that endpoint serves, or try a larger model."
            )
        return MoodAiOutcome(profileFrom(reading, chosen), reading.picks)
    }

    /** One request to an OpenAI-compatible endpoint, with its failures turned into advice. */
    private suspend fun openAiRequest(
        base: String,
        url: String,
        method: String = "GET",
        body: String? = null
    ): HttpResponse = try {
        httpRequest(
            url,
            method = method,
            body = body,
            contentType = if (body == null) null else "application/json",
            headers = openAiHeaders(),
            connectTimeoutMs = OPENAI_CONNECT_TIMEOUT_MS,
            readTimeoutMs = if (body == null) OPENAI_LIST_TIMEOUT_MS else OPENAI_READ_TIMEOUT_MS
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw MoodAiException(MoodAiParser.describeOpenAiNetworkFailure(base, e))
    }

    /** Sent only when the listener saved one: a model on their own machine may want none. */
    private fun openAiHeaders(): Map<String, String> =
        store.key(MoodAiProvider.OPENAI_COMPATIBLE)
            ?.let { token -> mapOf("Authorization" to "Bearer $token") }
            ?: emptyMap<String, String>()

    private fun openAiHttpMessage(base: String, code: Int, body: String): MoodAiException {
        val detail = MoodAiParser.openAiError(body)
        val shown = MoodAiUrls.display(base)
        val message = when {
            // The endpoint's own sentence is kept when it has one: an Arena gateway answering
            // "missing or invalid bearer token" says more than "rejected the key" ever could.
            code == 401 || code == 403 -> {
                // A refusal about the client is not a refusal about the key, and Arena's is the
                // one that happens in practice: its free-router keys are issued for its own
                // coding harnesses, so no key the listener can create on that page serves this
                // app. Blaming the key there would send them round the same loop again.
                val refusal = MoodAiParser.openAiHarnessRefusal(body)
                if (refusal != null) {
                    MoodAiParser.describeOpenAiHarnessRefusal(base, refusal, detail)
                } else {
                    val advice =
                        "The endpoint at $shown rejected the API key. Check the key, and that the " +
                            "address is the API root — for OpenAI that is https://api.openai.com/v1."
                    detail?.let { "$advice The endpoint said: $it" } ?: advice
                }
            }
            code == 404 && detail != null -> detail
            code == 404 ->
                "$shown answered, but nothing is at ${MoodAiUrls.display(MoodAiUrls.openAiChatUrl(base))}. " +
                    "Check the address: an OpenAI-compatible API usually ends in /v1."
            code == 429 ->
                "The endpoint at $shown is rate-limiting requests right now. Try again shortly."
            code in 500..599 ->
                "The endpoint at $shown is unavailable right now. Try again shortly."
            detail != null -> detail
            else -> "The endpoint at $shown answered with HTTP $code."
        }
        return MoodAiException(message)
    }

    // ----- Ollama -----

    private fun requireOllamaBase(): String {
        val raw = store.ollamaUrl
        return MoodAiUrls.normalize(raw) ?: throw MoodAiException(
            "Add your Ollama server's address first, for example http://192.168.0.20:11434."
        )
    }

    private suspend fun fetchOllamaModels(address: String): List<String> {
        val response = try {
            httpRequest(
                "$address/api/tags",
                headers = ollamaHeaders(),
                connectTimeoutMs = OLLAMA_CONNECT_TIMEOUT_MS,
                readTimeoutMs = OLLAMA_LIST_TIMEOUT_MS
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MoodAiException(MoodAiParser.describeNetworkFailure(address, e))
        }
        if (response.code !in 200..299) {
            throw MoodAiException(ollamaHttpMessage(address, response.code, response.body))
        }
        return MoodAiParser.parseModels(response.body)
    }

    private suspend fun readWithOllama(
        text: String,
        context: MoodContext,
        target: MoodSearchTarget
    ): MoodAiOutcome {
        val address = requireOllamaBase()
        val chosen = model
        val payload = JSONObject().apply {
            put("model", chosen)
            put("stream", false)
            put("format", "json")
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", MoodAiPrompts.full(text, context, target))
                    }
                )
            )
            put("options", JSONObject().apply { put("temperature", 0.4) })
        }
        val response = try {
            httpRequest(
                "$address/api/chat",
                method = "POST",
                body = payload.toString(),
                contentType = "application/json",
                headers = ollamaHeaders(),
                connectTimeoutMs = OLLAMA_CONNECT_TIMEOUT_MS,
                readTimeoutMs = OLLAMA_READ_TIMEOUT_MS
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MoodAiException(MoodAiParser.describeNetworkFailure(address, e))
        }
        if (response.code !in 200..299) {
            throw MoodAiException(ollamaHttpMessage(address, response.code, response.body))
        }
        val reading = MoodAiParser.parse(response.body)
            ?: throw MoodAiException(
                "$chosen answered with something that is not a mood. A small model may need a " +
                    "clearer description, or pick a larger one below."
            )
        return MoodAiOutcome(profileFrom(reading, "$chosen \u00b7 Ollama"), reading.picks)
    }

    /** Only a proxy in front of Ollama would need a credential, so this is usually empty. */
    private fun ollamaHeaders(): Map<String, String> = store.key(MoodAiProvider.OLLAMA)
        ?.let { token -> mapOf("Authorization" to "Bearer $token") }
        ?: emptyMap<String, String>()

    private fun ollamaHttpMessage(address: String, code: Int, body: String): String {
        val detail = MoodAiParser.ollamaError(body)
        return when {
            code == 404 && detail != null -> detail
            code == 404 -> "That address answered, but not like Ollama (HTTP 404). Check the " +
                "address and any path prefix in front of the server."
            code == 401 || code == 403 ->
                "Ollama at ${MoodAiUrls.display(address)} asked for a token. Add it below, or " +
                    "check the proxy in front of it."
            detail != null -> "Ollama answered with an error: $detail"
            else -> "Ollama at ${MoodAiUrls.display(address)} is unavailable (HTTP $code)."
        }
    }

    // ----- Gemini -----

    private suspend fun readWithGemini(
        text: String,
        context: MoodContext,
        target: MoodSearchTarget
    ): MoodAiOutcome {
        val key = store.key(MoodAiProvider.GEMINI) ?: throw MoodAiException(
            "Add your ${MoodAiProvider.GEMINI.label} API key to let it read your mood."
        )
        val chosen = model
        val address = "$GEMINI_API_BASE/models/$chosen:generateContent"
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", MoodAiPrompts.full(text, context, target))
                            )
                        )
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.4)
                    put("responseMimeType", "application/json")
                }
            )
        }
        val response = httpRequest(
            address,
            method = "POST",
            body = payload.toString(),
            contentType = "application/json",
            headers = mapOf("x-goog-api-key" to key),
            readTimeoutMs = GEMINI_READ_TIMEOUT_MS
        )
        if (response.code !in 200..299) throw response.modelError()
        val blocked = runCatching {
            JSONObject(response.body)
                .optJSONObject("promptFeedback")
                ?.optString("blockReason")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        if (blocked != null) {
            throw MoodAiException("The model declined to read that description ($blocked). Try other words.")
        }
        val reading = MoodAiParser.parse(response.body)
            ?: throw MoodAiException("The model returned something unreadable. Using the on-device reading instead.")
        return MoodAiOutcome(profileFrom(reading, chosen), reading.picks)
    }

    private fun profileFrom(reading: MoodAiReading, modelLabel: String): MoodProfile {
        val mood = MoodCatalog.nearest(
            valence = reading.valence,
            energy = reading.energy,
            label = reading.mood,
            tags = reading.tags
        )
        val tags = (reading.tags + mood.tags).map { it.lowercase() }.distinct().take(10)
        val searchTerms = reading.searchTerms.takeIf { it.isNotEmpty() } ?: mood.searchTerms
        val explained = reading.explanation?.take(180)
        val label = reading.mood.take(28).ifBlank { mood.label }
        val profile = MoodEngine.profileFor(
            mood = mood,
            source = MoodSource.AI_MODEL,
            confidence = 0.92,
            matched = emptyList(),
            supporting = emptyList(),
            note = explained ?: "Read by $modelLabel",
            energyHint = null,
            valenceOverride = reading.valence,
            energyOverride = reading.energy,
            tempoOverride = reading.tempo.toDouble(),
            danceOverride = reading.danceability,
            acousticOverride = reading.acousticness,
            instrumentalOverride = reading.instrumentalness,
            tagOverride = tags,
            modelLabel = modelLabel
        )
        // Keep the model's own wording for the headline and its search phrases when it offered
        // better ones than the catalogue's fixed terms.
        return profile.copy(label = label, searchTerms = searchTerms)
    }

    private fun tech.egrie.soundtrail.integrations.HttpResponse.modelError(): MoodAiException {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val message = when (code) {
            400, 401, 403 -> "The model rejected the API key. Check it in Google AI Studio and save it again."
            404 -> "That model name is not available for this key. Try ${MoodStore.DEFAULT_AI_MODEL}."
            429 -> "The model is rate-limiting requests right now. Try again shortly."
            in 500..599 -> "The model is unavailable right now. Try again shortly."
            else -> detail.ifBlank { "The model is unavailable (HTTP $code)." }
        }
        return MoodAiException(message)
    }

    companion object {
        /** Long enough for a cloud model to answer, short enough to notice a dead connection. */
        private const val GEMINI_READ_TIMEOUT_MS = 30_000

        /** An unreachable address should fail fast; a local model thinking should not. */
        private const val OLLAMA_CONNECT_TIMEOUT_MS = 6_000
        private const val OLLAMA_LIST_TIMEOUT_MS = 15_000
        private const val OLLAMA_READ_TIMEOUT_MS = 180_000

        /**
         * The same shape of patience for an OpenAI-compatible endpoint, a little shorter: one of
         * these can be a bridge relaying to a whole other service, but none of them is a model
         * loaded on the listener's own machine the way an Ollama tag is.
         */
        private const val OPENAI_CONNECT_TIMEOUT_MS = 8_000
        private const val OPENAI_LIST_TIMEOUT_MS = 20_000
        private const val OPENAI_READ_TIMEOUT_MS = 120_000
    }
}

/**
 * Pure parsing of whatever a model server sends back, kept out of the network class so the
 * strict shape checks (and the clamping of whatever a model decides to send) are unit tested.
 *
 * Three reply shapes are understood, because the listener may point the app at Ollama directly,
 * at an OpenAI-compatible gateway, or at a proxy that passes the mood JSON through untouched.
 */
internal object MoodAiParser {
    private val tagCleaner = Regex("[^a-z0-9-]+")

    /** Pulls a reading out of any supported reply shape; null when there is nothing usable. */
    fun parse(body: String): MoodAiReading? {
        val text = textFrom(body) ?: return null
        val json = extractJson(text) ?: return null
        return runCatching { readingFrom(JSONObject(json)) }.getOrNull()
    }

    /** The assistant's text, found wherever the server put it. */
    fun textFrom(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null

        // Gemini: candidates[0].content.parts[*].text
        root.optJSONArray("candidates")?.let { candidates ->
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                val builder = StringBuilder()
                for (index in 0 until parts.length()) {
                    parts.optJSONObject(index)?.optString("text")?.let { builder.append(it) }
                }
                builder.toString().takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        // Ollama /api/chat, and /api/generate for the older single-prompt form.
        root.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        root.optString("response").takeIf { it.isNotBlank() }?.let { return it }

        // OpenAI-compatible gateways: choices[0].message.content, or choices[0].text.
        root.optJSONArray("choices")?.let { choices ->
            val first = choices.optJSONObject(0)
            if (first != null) {
                (first.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: first.optString("text").takeIf { it.isNotBlank() })?.let { return it }
            }
        }

        // A server that answered with the mood object itself and no envelope at all.
        return root.toString().takeIf { root.has("mood") }
    }

    /** Validates and clamps one JSON object; null when it carries no usable mood. */
    fun readingFrom(json: JSONObject): MoodAiReading? {
        val mood = json.optString("mood").trim().take(28)
        if (mood.isBlank()) return null
        val valence = unit(json, "valence") ?: 0.5
        val energy = unit(json, "energy") ?: 0.5
        val tempo = json.optInt("tempo", 0).takeIf { it > 0 }?.coerceIn(40, 220)
            ?: TrackMoodTags.tempoFor(energy)
        val tags = stringList(json, "tags", limit = 8).map { tag ->
            tag.lowercase().replace(tagCleaner, "-").trim('-').take(24)
        }.filter { it.isNotBlank() }.distinct()
        val searchTerms = stringList(json, "search_terms", limit = 3)
            .map { it.replace(Regex("\\s+"), " ").trim().take(60) }
            .filter(String::isNotBlank)
        val explanation = json.optString("explanation").trim().takeIf { it.isNotBlank() }?.take(180)
        return MoodAiReading(
            mood = mood,
            valence = valence,
            energy = energy,
            tempo = tempo,
            danceability = unit(json, "danceability"),
            acousticness = unit(json, "acousticness"),
            instrumentalness = unit(json, "instrumentalness"),
            tags = tags,
            searchTerms = searchTerms,
            picks = pickList(json),
            explanation = explanation
        )
    }

    /**
     * The songs a model named, from `picks` — or from `songs` or `tracks`, which models reach for
     * on their own often enough to be worth accepting. Both shapes are read: an object with a
     * title and an artist, and a plain `"Artist - Title"` string. Titles are kept short, blanks
     * and duplicates are dropped, and the list is capped, because a model that ignores "up to six"
     * should not be able to turn one reading into fifty searches.
     */
    fun pickList(json: JSONObject, limit: Int = MAX_MOOD_PICKS): List<MoodAiPick> {
        val array = json.optJSONArray("picks")
            ?: json.optJSONArray("songs")
            ?: json.optJSONArray("tracks")
            ?: return emptyList()
        val picks = mutableListOf<MoodAiPick>()
        for (index in 0 until array.length()) {
            if (picks.size >= limit) break
            val pick = when (val raw = array.opt(index)) {
                is JSONObject -> pickFrom(raw)
                is String -> pickFromText(raw)
                else -> null
            } ?: continue
            if (picks.any { it.title.equals(pick.title, ignoreCase = true) &&
                    it.artist.equals(pick.artist, ignoreCase = true) }
            ) continue
            picks += pick
        }
        return picks
    }

    private fun pickFrom(raw: JSONObject): MoodAiPick? {
        val title = raw.optString("title").ifBlank { raw.optString("name") }
            .ifBlank { raw.optString("song") }
            .replace(Regex("\\s+"), " ").trim().take(80)
        if (title.isBlank()) return null
        val artist = raw.optString("artist").ifBlank { raw.optString("by") }
            .replace(Regex("\\s+"), " ").trim().take(80)
        val why = raw.optString("why").ifBlank { raw.optString("reason") }
            .replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }?.take(140)
        return MoodAiPick(title = title, artist = artist, why = why)
    }

    /**
     * A model that answers with a plain string instead of an object. The wording decides which
     * half is the song: "Sunsets by the xx" names the title first, while a dash follows the
     * convention every music database uses — "the xx - Sunsets" is artist then title.
     */
    private fun pickFromText(raw: String): MoodAiPick? {
        val cleaned = raw.replace(Regex("\\s+"), " ").trim().trim('"').take(160)
        if (cleaned.isBlank()) return null
        run {
            val by = cleaned.split(Regex("\\s+by\\s+", RegexOption.IGNORE_CASE), limit = 2)
            if (by.size == 2 && by[0].isNotBlank() && by[1].isNotBlank()) {
                return MoodAiPick(title = by[0].trim().take(80), artist = by[1].trim().take(80))
            }
        }
        run {
            val dashed = cleaned.split(Regex("\\s+[-\u2013\u2014]\\s+"), limit = 2)
            if (dashed.size == 2 && dashed[0].isNotBlank() && dashed[1].isNotBlank()) {
                return MoodAiPick(title = dashed[1].trim().take(80), artist = dashed[0].trim().take(80))
            }
        }
        return MoodAiPick(cleaned.take(80))
    }

    /** The model names an Ollama server reports from `/api/tags`. */
    fun parseModels(body: String): List<String> {
        val array = runCatching { JSONObject(body).optJSONArray("models") }.getOrNull()
            ?: return emptyList()
        val names = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").ifBlank { item.optString("model") }.trim()
            if (name.isNotBlank() && names.none { it == name }) names += name
        }
        return names.take(60)
    }

    /** Ollama's own error sentence, when the body carries one. */
    fun ollamaError(body: String): String? = runCatching {
        JSONObject(body).optString("error").trim().takeIf { it.isNotBlank() }?.take(160)
    }.getOrNull()

    /**
     * The model ids an OpenAI-compatible server reports from `/models`. Both spellings are
     * understood: `{"data":[{"id":…}]}` (OpenAI, and almost everything that copies it — including
     * the model list Arena's portal hands its own setup script) and the bare
     * `{"models":[{"name":…}]}` some self-hosted servers use.
     *
     * Arena's rows carry one field the others do not, and it matters: `arena_type` marks entries
     * that are not a model you can call — a row of type `routing` (the router picks for you), and
     * Arena's own `exo` entry. Its setup script skips both, so this picker does too; a tap on
     * either would send a request nothing answers.
     */
    fun parseOpenAiModels(body: String): List<String> =
        openAiModelRows(body).take(200).map { it.first }

    /**
     * What the endpoint calls its models, for the picker. Only names that differ from the id are
     * kept: a server that returns `name` equal to `id` — or none at all, which is most of them —
     * has nothing to say here, and a label repeating the id would just be noise above the field
     * that already shows it.
     */
    fun parseOpenAiModelLabels(body: String): Map<String, String> =
        openAiModelRows(body).take(200)
            .mapNotNull { (id, name) -> name?.takeIf { it != id }?.let { id to it } }
            .toMap()

    /** The id and display name of every callable row, in the order the endpoint listed them. */
    private fun openAiModelRows(body: String): List<Pair<String, String?>> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val rows = mutableListOf<Pair<String, String?>>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }.trim()
            val arenaType = item.optString("arena_type").trim()
            if (arenaType == "routing" || (arenaType.isNotBlank() && id == "exo")) continue
            if (id.isBlank() || rows.any { it.first == id }) continue
            rows += id to item.optString("name").trim().takeIf { it.isNotBlank() }
        }
        return rows
    }

    /** An OpenAI-shaped error sentence — `{"error":{"message":…}}` — when the body carries one. */
    fun openAiError(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.trim()
            ?.takeIf { it.isNotBlank() }?.take(200)
    }.getOrNull()

    /**
     * Arena's refusal code when its gateway turned the **client** away instead of rejecting the
     * key.
     *
     * Arena's free-router keys are issued for Arena's own coding harnesses, so a request from
     * anywhere else — this app included — comes back as `free_router_harness_restricted` rather
     * than an answer. The code is read from `error.code`, `error.type`, or the top level, because
     * the code is the part Arena's wording cannot change; a rejected key carries
     * `invalid_request_error` instead and must keep the advice about the key. Null when the body
     * says nothing of the kind.
     */
    fun openAiHarnessRefusal(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val error = root.optJSONObject("error")
        val codes = listOfNotNull(
            error?.optString("code")?.takeIf(String::isNotBlank),
            error?.optString("type")?.takeIf(String::isNotBlank),
            root.optString("code").takeIf(String::isNotBlank),
            root.optString("type").takeIf(String::isNotBlank)
        ).map { it.trim().lowercase() }
        return MoodAiUrls.ARENA_HARNESS_REFUSALS.firstOrNull { refusal -> refusal in codes }
    }

    /**
     * What to say when Arena turned this app away.
     *
     * The key is not the problem, and saying that it is would send the listener off to create a
     * key that was never going to work here. Arena's free-router keys are served to its own
     * harnesses only, so no reading can run from this card on one — which is also why a refused
     * request, which never reaches a model, leaves Arena's usage page empty. The gateway's own
     * sentence is kept beside the advice when it sent one, and the code is named so it can be
     * looked up in Arena's own documentation.
     */
    fun describeOpenAiHarnessRefusal(base: String, code: String, detail: String?): String {
        val advice =
            "${MoodAiUrls.display(base)} accepted the key and turned this app away. Arena's " +
                "free-router keys are served only to Arena's own coding harnesses — Claude Code, " +
                "the Code tab in Claude Desktop, and OpenCode — so a reading cannot run from " +
                "here on one, and a request that is refused reaches no model and registers no " +
                "usage. Use a key from a workspace with API access, or read with another " +
                "provider."
        val said = detail?.let { "Arena said: $it ($code)" } ?: "(Arena's code: $code)"
        return "$advice $said"
    }

    /**
     * Turns a socket failure at an OpenAI-compatible endpoint into something a listener can act
     * on. The commonest mistake by far is typing a `localhost` address that means the phone.
     */
    fun describeOpenAiNetworkFailure(base: String, error: Exception): String {
        val reason = error.message?.take(80)?.takeIf { it.isNotBlank() } ?: "no response"
        val hint = if (MoodAiUrls.isLoopback(base)) {
            "${MoodAiUrls.hostOf(base)} is this device itself. If the endpoint runs on your network, " +
                "use that machine's address instead."
        } else {
            "Check the address, that the server is running and reachable from this device, and " +
                "whether it is expecting a key."
        }
        return "Couldn't reach ${MoodAiUrls.display(base)} ($reason). $hint"
    }

    /**
     * Turns a socket failure into something a listener can act on. The commonest cause by far is
     * an Ollama that is running but only listening on its own machine.
     */
    fun describeNetworkFailure(address: String, error: Exception): String {
        val reason = error.message?.take(80)?.takeIf { it.isNotBlank() } ?: "no response"
        val hint = if (MoodAiUrls.isLoopback(address)) {
            "${MoodAiUrls.hostOf(address)} is this phone itself. Either Ollama runs here (then " +
                "check it is actually up, and listening on 0.0.0.0:11434 rather than 127.0.0.1), " +
                "or you meant a computer on your network — use that machine's address."
        } else {
            "Check that Ollama is running there with OLLAMA_HOST=0.0.0.0:11434 and that this " +
                "device is on the same network."
        }
        return "Couldn't reach Ollama at ${MoodAiUrls.display(address)} ($reason). $hint"
    }

    /**
     * Finds the first balanced JSON object in a text block, tolerating the code fences and
     * chatter that models sometimes add even when asked not to.
     */
    fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** Reads a 0..1 value, accepting either a fraction or a percentage. */
    private fun unit(json: JSONObject, name: String): Double? {
        val raw = json.optDouble(name, Double.NaN)
        if (raw.isNaN()) return null
        val scaled = if (raw > 1.5) raw / 100.0 else raw
        return scaled.coerceIn(0.0, 1.0)
    }

    private fun stringList(json: JSONObject, name: String, limit: Int): List<String> {
        val array = json.optJSONArray(name) ?: return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) values += value
            if (values.size >= limit) break
        }
        return values
    }
}
