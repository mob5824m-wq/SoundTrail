package tech.egrie.soundtrail.mood

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.SecretStore

/**
 * Local storage for the mood layer: the listener's settings, the last thing they typed, and a
 * bounded cache of learned track tags. The optional AI model's API key is the only secret here
 * and it is kept in [SecretStore], which is Android-Keystore encrypted; nothing else in this
 * file is sensitive and none of it is synced or backed up.
 */
internal class MoodStore(context: Context) {
    private val prefs = context.getSharedPreferences("soundtrail_mood", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", false)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()

    /** The model name used for the optional AI reading; the default suits the free tier. */
    var aiModel: String
        get() = prefs.getString("ai_model", DEFAULT_AI_MODEL).orEmpty().ifBlank { DEFAULT_AI_MODEL }
        set(value) = prefs.edit().putString("ai_model", sanitizeModel(value)).apply()

    /** Whether the listener agreed to tag lookups against TheAudioDB's free metadata API. */
    var tagLookupEnabled: Boolean
        get() = prefs.getBoolean("tag_lookup", false)
        set(value) = prefs.edit().putBoolean("tag_lookup", value).apply()

    var lastText: String
        get() = prefs.getString("last_text", "").orEmpty()
        set(value) = prefs.edit().putString("last_text", value.take(400)).apply()

    /** Last energy hint, 0..100, or null when the listener has not set one. */
    var lastEnergyHint: Int?
        get() = prefs.getInt("last_energy", -1).takeIf { it in 0..100 }
        set(value) = prefs.edit().putInt("last_energy", value ?: -1).apply()

    val aiKeySet: Boolean get() = !aiKey().isNullOrBlank()

    fun aiKey(): String? = secrets.get(AI_KEY)?.takeIf(String::isNotBlank)

    fun saveAiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) clearAiKey() else secrets.put(AI_KEY, trimmed)
    }

    fun clearAiKey() = secrets.remove(AI_KEY)

    // ----- Learned track tags -----

    /** Tags already learned for a library entry, or null when it has none yet. */
    fun tags(key: String): TrackMoodTags.Tags? {
        val raw = runCatching { JSONObject(cacheJson()).optJSONObject(key) }.getOrNull() ?: return null
        return runCatching {
            val tags = raw.optJSONArray("tags") ?: JSONArray()
            TrackMoodTags.Tags(
                tags = (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) },
                valence = raw.optDouble("valence", Double.NaN).takeIf { !it.isNaN() },
                energy = raw.optDouble("energy", Double.NaN).takeIf { !it.isNaN() },
                tempo = raw.optInt("tempo", -1).takeIf { it > 0 },
                origin = raw.optString("origin").takeIf(String::isNotBlank)
            )
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    fun cachedTagCount(): Int = runCatching { JSONObject(cacheJson()).length() }.getOrDefault(0)

    /** Merges newly learned tags in, keeping the cache bounded and most recent entries last. */
    fun saveTags(learned: Map<String, TrackMoodTags.Tags>) {
        if (learned.isEmpty()) return
        val merged = linkedMapOf<String, TrackMoodTags.Tags>()
        runCatching { JSONObject(cacheJson()).keys() }.getOrNull()?.forEach { key ->
            tags(key)?.let { merged[key] = it }
        }
        learned.forEach { (key, value) -> if (!value.isEmpty) merged[key] = value }
        val trimmed = merged.entries.toList().takeLast(MAX_CACHED_TAGS)
        val json = JSONObject()
        trimmed.forEach { (key, value) ->
            json.put(key, JSONObject().apply {
                put("tags", JSONArray(value.tags))
                value.valence?.let { put("valence", it) }
                value.energy?.let { put("energy", it) }
                value.tempo?.let { put("tempo", it) }
                value.origin?.let { put("origin", it) }
            })
        }
        prefs.edit().putString("tag_cache", json.toString()).apply()
    }

    fun clearTags() = prefs.edit().remove("tag_cache").apply()

    private fun cacheJson(): String = prefs.getString("tag_cache", "{}").orEmpty().ifBlank { "{}" }

    companion object {
        const val DEFAULT_AI_MODEL = "gemini-2.0-flash"
        private const val AI_KEY = "mood_ai_key"
        private const val MAX_CACHED_TAGS = 1500

        /** A stable cache key for a library entry: its link, or its text when it has none. */
        fun keyOf(candidate: MoodCandidate): String = candidate.url
            ?: "text:" + MoodEngine.normalize("${candidate.title} ${candidate.artist}")

        fun sanitizeModel(value: String): String = value.trim()
            .take(60)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            .ifBlank { DEFAULT_AI_MODEL }
    }
}
