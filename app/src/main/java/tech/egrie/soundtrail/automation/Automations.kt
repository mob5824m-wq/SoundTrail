package tech.egrie.soundtrail.automation

import android.content.Context
import tech.egrie.soundtrail.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The app's motion vocabulary, read from GPS speed by [MotionClassifier]. */
enum class MotionKind(val label: String) { STATIONARY("Stationary"), WALKING("Walking"), RUNNING("Running"), DRIVING("Driving") }

/** Pure speed-based classification. Speeds are m/s; NaN/negative counts as stationary. */
object MotionClassifier {
    const val WALKING_MIN_MPS = 0.5f
    const val RUNNING_MIN_MPS = 2.6f
    const val DRIVING_MIN_MPS = 7.5f

    fun classify(speedMps: Float): MotionKind = when {
        !speedMps.isFinite() || speedMps < WALKING_MIN_MPS -> MotionKind.STATIONARY
        speedMps < RUNNING_MIN_MPS -> MotionKind.WALKING
        speedMps < DRIVING_MIN_MPS -> MotionKind.RUNNING
        else -> MotionKind.DRIVING
    }
}

data class TriggerContext(
    val motion: MotionKind,
    val location: GeoPoint?,
    val minuteOfDay: Int
)

/**
 * A playlist trigger. Conditions are ANDed; at least one must be set.
 * [activity] null means any non-stationary motion is accepted when a place or time is set,
 * and means "no motion condition" when it is the only unset condition.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val playlistId: String,
    val enabled: Boolean = true,
    val place: GeoPoint? = null,
    val radiusMeters: Double = 150.0,
    val activity: MotionKind? = null,
    val fromMinute: Int? = null,
    val toMinute: Int? = null,
    val cooldownMinutes: Long = 15L
) {
    val hasCondition: Boolean
        get() = place != null || activity != null ||
            (fromMinute != null && toMinute != null)

    fun satisfiedBy(context: TriggerContext): Boolean {
        if (!enabled || !hasCondition) return false
        place?.let { target ->
            val here = context.location ?: return false
            if (here.distanceTo(target) > radiusMeters) return false
        }
        activity?.let { wanted ->
            if (context.motion != wanted) return false
        }
        if (fromMinute != null && toMinute != null &&
            !timeWindowContains(context.minuteOfDay, fromMinute, toMinute)
        ) return false
        return true
    }

    companion object {
        /** Windows may wrap midnight (22:00–02:00). End minute is inclusive. */
        fun timeWindowContains(minuteOfDay: Int, fromMinute: Int, toMinute: Int): Boolean {
            if (minuteOfDay !in 0..MINUTES_PER_DAY - 1) return false
            if (fromMinute !in 0..MINUTES_PER_DAY - 1 || toMinute !in 0..MINUTES_PER_DAY - 1) return false
            return if (fromMinute <= toMinute) {
                minuteOfDay in fromMinute..toMinute
            } else {
                minuteOfDay >= fromMinute || minuteOfDay <= toMinute
            }
        }

        const val MINUTES_PER_DAY = 24 * 60
        val DEFAULT_COOLDOWN_MINUTES = 15L
    }
}

/** Edge-free, cooldown-based triggering: a rule fires again only after its cooldown lapses. */
object AutomationEngine {
    fun due(
        rules: List<AutomationRule>,
        context: TriggerContext,
        nowMs: Long,
        lastFiredAt: (String) -> Long?
    ): List<AutomationRule> = rules.filter { rule ->
        rule.satisfiedBy(context) && when (val fired = lastFiredAt(rule.id)) {
            null -> true
            else -> nowMs - fired >= rule.cooldownMinutes * 60_000L
        }
    }
}

/** Persists rules and local last-fired timestamps; optional sync sends rules only as ciphertext. */
class AutomationStore(
    context: Context,
    private val onChanged: (() -> Unit)? = null
) {
    private val prefs = context.getSharedPreferences("soundtrail_automations", Context.MODE_PRIVATE)

    fun all(): List<AutomationRule> {
        val array = runCatching { JSONArray(prefs.getString("rules", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                val place = entry.optJSONObject("place")?.let {
                    GeoPoint(it.getDouble("lat"), it.getDouble("lon"))
                }?.takeIf(GeoPoint::isValid)
                val activity = entry.optString("activity").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { name -> MotionKind.entries.firstOrNull { it.name == name } }
                AutomationRule(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    playlistId = entry.getString("playlistId"),
                    enabled = entry.optBoolean("enabled", true),
                    place = place,
                    radiusMeters = entry.optDouble("radius", 150.0),
                    activity = activity,
                    fromMinute = entry.optInt("fromMinute", -1).takeIf { it >= 0 },
                    toMinute = entry.optInt("toMinute", -1).takeIf { it >= 0 },
                    cooldownMinutes = entry.optLong("cooldown", AutomationRule.DEFAULT_COOLDOWN_MINUTES)
                )
            }.getOrNull()
        }
    }

    fun save(rule: AutomationRule) {
        replaceAll(all().filterNot { it.id == rule.id } + rule)
    }

    /** Applies a complete caller snapshot without extra reads or sync-journal events. */
    fun replaceAll(rules: List<AutomationRule>) = write(rules)

    fun remove(id: String) = replaceAll(all().filterNot { it.id == id })

    /**
     * Restores rules from a backup; ids already saved are kept as they are, so importing
     * never rewrites a locally edited rule. Returns how many were added.
     */
    fun merge(incoming: List<AutomationRule>): Int {
        val have = all()
        val ids = have.mapTo(HashSet()) { it.id }
        val fresh = incoming.filterNot { it.id in ids }
        if (fresh.isNotEmpty()) write(have + fresh)
        return fresh.size
    }

    fun lastFiredAt(id: String): Long? = prefs.getLong("fired:$id", -1L).takeIf { it >= 0 }

    fun markFired(id: String, atMs: Long) {
        prefs.edit().putLong("fired:$id", atMs).apply()
    }

    private fun write(rules: List<AutomationRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("playlistId", rule.playlistId)
                put("enabled", rule.enabled)
                rule.place?.let {
                    put("place", JSONObject().put("lat", it.latitude).put("lon", it.longitude))
                }
                put("radius", rule.radiusMeters)
                rule.activity?.let { put("activity", it.name) }
                rule.fromMinute?.let { put("fromMinute", it) }
                rule.toMinute?.let { put("toMinute", it) }
                put("cooldown", rule.cooldownMinutes)
            })
        }
        prefs.edit().putString("rules", array.toString()).apply()
        onChanged?.invoke()
    }
}

/** Snapshot of the watch service, shown on the Lists tab. */
data class WatchStatus(
    val running: Boolean = false,
    val motion: MotionKind = MotionKind.STATIONARY,
    val location: GeoPoint? = null,
    val minuteOfDay: Int = 0,
    val updatedAt: Long = 0L
) {
    fun clock(): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
