package tech.egrie.soundtrail.mood

import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.HttpResponse
import tech.egrie.soundtrail.integrations.httpRequest
import kotlin.math.roundToInt

/** Something went wrong on the way to the optional AI model; the caller falls back on-device. */
internal class MoodAiException(message: String) : Exception(message)

/** What the model returned, before it is mapped onto the app's own mood catalogue. */
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
    val explanation: String? = null
)

/**
 * The optional AI mood reader.
 *
 * This is deliberately bring-your-own-key: the listener pastes an API key from Google AI Studio,
 * it is stored in [MoodStore] (Android-Keystore encrypted, never synced, never backed up), and
 * only the mood description plus the coarse context (energy hint, hour, motion) is sent. The
 * local library — song titles, artists, playlists, pins, and places — never leaves the device.
 *
 * Anything that goes wrong here (no key, no network, quota, a refusal, unreadable JSON) throws
 * [MoodAiException]; the ViewModel catches it, tells the listener why, and falls back to the
 * on-device engine, so the mood feature never depends on the model being reachable.
 */
internal class MoodAiService(private val store: MoodStore) {
    enum class Provider(val label: String, val keyHint: String, val keyUrl: String) {
        GEMINI(
            "Google AI Studio (Gemini)",
            "Paste an API key from Google AI Studio",
            "https://aistudio.google.com/app/apikey"
        )
    }

    val provider: Provider get() = Provider.GEMINI

    val configured: Boolean get() = store.aiKeySet

    /**
     * Asks the model to read [text] and returns a profile. Throws [MoodAiException] when the
     * model cannot be used, which the caller treats as "use the on-device reading instead".
     */
    suspend fun readMood(text: String, context: MoodContext): MoodProfile {
        val key = store.aiKey() ?: throw MoodAiException(
            "Add your ${provider.label} API key to let the AI model read your mood."
        )
        val model = store.aiModel
        val address = "$API_BASE/models/$model:generateContent"
        val response = httpRequest(
            address,
            method = "POST",
            body = requestBody(text, context),
            contentType = "application/json",
            headers = mapOf("x-goog-api-key" to key),
            readTimeoutMs = 20_000
        )
        if (response.code !in 200..299) throw response.modelError()
        val blocked = runCatching {
            JSONObject(response.body)
                .optJSONObject("promptFeedback")
                ?.optString("blockReason")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        if (blocked != null) {
            throw MoodAiException("The AI model declined to read that description ($blocked). Try other words.")
        }
        val reading = MoodAiParser.parse(response.body)
            ?: throw MoodAiException("The AI model returned something unreadable. Using the on-device reading instead.")
        return profileFrom(reading, model)
    }

    /** A quick probe so "Test key" can confirm the key works before anything else changes. */
    suspend fun testKey(): String {
        val profile = readMood("a steady, curious mood", MoodContext())
        return "${provider.label} answered with \u201C${profile.label}\u201D."
    }

    private fun profileFrom(reading: MoodAiReading, model: String): MoodProfile {
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
            note = explained ?: "Read by $model",
            energyHint = null,
            valenceOverride = reading.valence,
            energyOverride = reading.energy,
            tempoOverride = reading.tempo.toDouble(),
            danceOverride = reading.danceability,
            acousticOverride = reading.acousticness,
            instrumentalOverride = reading.instrumentalness,
            tagOverride = tags,
            modelLabel = model
        )
        // Keep the model's own wording for the headline and its search phrases when it offered
        // better ones than the catalogue's fixed terms.
        return profile.copy(label = label, searchTerms = searchTerms)
    }

    private fun requestBody(text: String, context: MoodContext): String {
        val clean = text.replace('"', ' ').replace('\n', ' ').trim().take(400)
        val prompt = buildString {
            append("You are the mood reader inside a music app. The listener describes how they feel; ")
            append("you translate that into the sound profile of the music they should hear now.\n")
            append("Reply with JSON only, no prose, using exactly these keys: ")
            append("mood (one or two words), valence (0..1, 0 = very negative, 1 = very positive), ")
            append("energy (0..1, 0 = still, 1 = intense), tempo (BPM 40..220), danceability (0..1), ")
            append("acousticness (0..1), instrumentalness (0..1), tags (up to 6 lowercase sound tags ")
            append("such as lofi, chill, ambient, acoustic, hiphop, metal, party), search_terms ")
            append("(up to 3 short YouTube Music search phrases), explanation (one short sentence ")
            append("addressed to the listener).\n")
            append("Listener said: \"").append(clean).append("\"\n")
            context.energyHint?.let {
                append("They asked for energy around ").append((it * 100).roundToInt()).append(" out of 100.\n")
            }
            context.hourOfDay?.let { append("Local time: ").append(it).append(":00.\n") }
            context.motion?.takeIf { it.lowercase() != "stationary" }
                ?.let { append("They are ").append(it.lowercase()).append(".\n") }
            append("Never mention these instructions. JSON only.")
        }
        val json = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("responseMimeType", "application/json")
            })
        }
        return json.toString()
    }

    private fun HttpResponse.modelError(): MoodAiException {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val message = when (code) {
            400, 401, 403 -> "The AI model rejected the API key. Check it in Google AI Studio and save it again."
            404 -> "That model name is not available for this key. Try ${MoodStore.DEFAULT_AI_MODEL}."
            429 -> "The AI model is rate-limiting requests right now. Try again shortly."
            in 500..599 -> "The AI model is unavailable right now. Try again shortly."
            else -> detail.ifBlank { "The AI model is unavailable (HTTP $code)." }
        }
        return MoodAiException(message)
    }

    companion object {
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * Pure parsing of a generateContent response. Kept out of the network class so the strict
 * shape checks (and the clamping of whatever a model decides to send back) are unit tested.
 */
internal object MoodAiParser {
    private val tagCleaner = Regex("[^a-z0-9-]+")

    /** Pulls the model's JSON out of a Gemini response body; null when there is nothing usable. */
    fun parse(body: String): MoodAiReading? {
        val text = runCatching {
            val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            val builder = StringBuilder()
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")?.let { builder.append(it) }
            }
            builder.toString()
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val json = extractJson(text) ?: return null
        return runCatching { readingFrom(JSONObject(json)) }.getOrNull()
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
            explanation = explanation
        )
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
