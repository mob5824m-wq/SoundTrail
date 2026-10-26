package tech.egrie.soundtrail.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Unsynced encrypted-event candidate; its UUID remains stable across network retries. */
internal data class PendingSyncRecord(val eventId: String, val record: SyncRecord)

/**
 * Device-local sync journal. Current entity versions, the server cursor and pending changes are
 * stored in private SharedPreferences. Each account's payload is already in the same private
 * app sandbox as the existing pins and playlists; the journal itself is never uploaded in the
 * clear. Only SyncCrypto ciphertext envelopes leave this device.
 */
internal class SyncJournal(context: Context) {
    private val prefs = context.getSharedPreferences("soundtrail_cloud_sync", Context.MODE_PRIVATE)
    private val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    @Synchronized
    fun observeLocal(snapshot: List<SyncRecord>) {
        val current = snapshot.associateBy { it.key }
        val previous = localSnapshot()
        var state = latest()
        snapshot.forEach { local ->
            val old = previous[local.key]
            if (old?.payload != local.payload) {
                val known = state[local.key]
                // If the stores were updated but the app stopped before recording its baseline,
                // do not echo the same remote winner back as a fresh local edit.
                if (known == null || known.deleted || known.payload != local.payload) {
                    record(local.kind, local.entityId, deleted = false, payload = local.payload)
                    state = latest()
                }
            }
        }
        // A missing live record means an explicit local delete only if that key appeared in
        // the last store snapshot. Remote-only entities pulled before a failed sync are not
        // mistaken for local deletions.
        previous.keys.filterNot { it in current }.forEach { key ->
            val oldLocal = previous.getValue(key)
            val known = state[key]
            if (known != null && !known.deleted) {
                record(oldLocal.kind, oldLocal.entityId, deleted = true, payload = null)
                state = latest()
            }
        }
        saveLocalSnapshot(snapshot)
    }

    /**
     * Re-queues every current record for upload with a fresh delivery id, leaving the records
     * themselves exactly as they are.
     *
     * This is the recovery path for a server whose history went backwards — one restored from a
     * database backup has lost events this device already had acknowledged, and the ordinary push
     * only sends what is still pending, so nothing would ever put them back.
     *
     * Nothing is re-encrypted and no clock is advanced: each record goes out with the clock, the
     * device id and the ciphertext it was created with, so merge on any device resolves it to the
     * same winner it already had. Only the delivery id is new, because the old one was consumed by
     * the upload that the restore undid. That is what makes the re-push idempotent in effect — a
     * record the server still has collapses to the same version, and one it lost is restored.
     *
     * @return how many records are now pending, so the caller can say whether anything was queued
     */
    @Synchronized
    fun requeueAll(): Int {
        val state = latest()
        if (state.isEmpty()) return 0
        val queued = state.values.map { PendingSyncRecord(UUID.randomUUID().toString(), it) }
        save(prefs.getLong("clock", 0L), state, queued)
        return queued.size
    }

    /** Records the store snapshot after a successful remote apply without creating new events. */
    @Synchronized
    fun markLocalSnapshot(snapshot: List<SyncRecord>) {
        saveLocalSnapshot(snapshot)
        prefs.edit().putBoolean("seeded", true).apply()
    }

    private fun localSnapshot(): Map<String, SyncRecord> {
        val json = runCatching { JSONObject(prefs.getString("local_snapshot", "{}")) }.getOrNull()
            ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { key ->
            runCatching {
                val entry = json.getJSONObject(key.toString())
                val kind = entry.getString("kind")
                val id = entry.getString("entityId")
                val payload = entry.getString("payload")
                require(kind != SYNC_KIND_KEY_CHECK && id.isNotBlank())
                SyncRecord(kind, id, clock = 0L, deviceId = "local-baseline", deleted = false, payload = payload)
            }.getOrNull()
        }.associateBy { it.key }
    }

    private fun saveLocalSnapshot(snapshot: List<SyncRecord>) {
        val json = JSONObject()
        snapshot.filter { it.kind != SYNC_KIND_KEY_CHECK }.forEach { record ->
            json.put(record.key, JSONObject()
                .put("kind", record.kind)
                .put("entityId", record.entityId)
                .put("payload", record.payload))
        }
        prefs.edit().putString("local_snapshot", json.toString()).apply()
    }

    @Synchronized
    fun seed(snapshot: List<SyncRecord>) {
        if (prefs.getBoolean("seeded", false)) return
        observeLocal(snapshot)
        prefs.edit().putBoolean("seeded", true).apply()
    }

    @Synchronized
    fun record(kind: String, entityId: String, deleted: Boolean, payload: String?): SyncRecord {
        require(kind in setOf(SYNC_KIND_PIN, SYNC_KIND_PLAYLIST, SYNC_KIND_PLAYLIST_ITEM, SYNC_KIND_AUTOMATION, SYNC_KIND_KEY_CHECK))
        require(entityId.isNotBlank() && entityId.length <= 128)
        require(deleted || payload != null)
        val clock = prefs.getLong("clock", 0L) + 1L
        val record = SyncRecord(kind, entityId, clock, deviceId, deleted, payload)
        val events = pending().toMutableList()
        events += PendingSyncRecord(UUID.randomUUID().toString(), record)
        val state = latest().toMutableMap().apply { put(record.key, record) }
        save(clock, state, events)
        return record
    }

    /** Merges one fully fetched/decrypted change set and advances the cursor only after applying it. */
    @Synchronized
    fun mergeRemote(records: List<SyncRecord>, nextCursor: Long) {
        val state = SyncMerge.merge(latest(), records)
        // A pending local event remains pending only when deterministic LWW says it is still
        // the winner. Stale pending versions are dropped; their event IDs were never uploaded.
        val pending = pending().filter { state[it.record.key] == it.record }
        val maxClock = maxOf(prefs.getLong("clock", 0L), state.values.maxOfOrNull { it.clock } ?: 0L)
        save(maxClock, state, pending, cursor = maxOf(cursor(), nextCursor))
    }

    @Synchronized
    fun latest(): Map<String, SyncRecord> {
        val array = runCatching { JSONArray(prefs.getString("latest", "[]")) }.getOrNull()
            ?: return emptyMap()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { SyncRecord.fromJson(array.getJSONObject(index)) }.getOrNull()
        }.associateBy { it.key }
    }

    @Synchronized
    fun pendingLatest(): List<PendingSyncRecord> {
        val newest = latest()
        val all = pending()
        val current = all.filter { newest[it.record.key] == it.record }
        if (current.size != all.size) save(prefs.getLong("clock", 0L), newest, current)
        return current.sortedWith(compareBy<PendingSyncRecord>({ it.record.kind != SYNC_KIND_KEY_CHECK }, { it.record.kind }, { it.record.entityId }))
    }

    @Synchronized
    fun acknowledge(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        save(
            prefs.getLong("clock", 0L), latest(),
            pending().filterNot { it.eventId in eventIds }
        )
    }

    fun cursor(): Long = prefs.getLong("cursor", 0L).coerceAtLeast(0L)

    @Synchronized
    fun resetForAccountChange() {
        prefs.edit()
            .remove("clock")
            .remove("cursor")
            .remove("latest")
            .remove("pending")
            .remove("seeded")
            .remove("local_snapshot")
            .apply()
    }

    private fun pending(): List<PendingSyncRecord> {
        val array = runCatching { JSONArray(prefs.getString("pending", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                PendingSyncRecord(entry.getString("eventId"), SyncRecord.fromJson(entry.getJSONObject("record")))
            }.getOrNull()
        }
    }

    private fun save(
        clock: Long,
        state: Map<String, SyncRecord>,
        pending: List<PendingSyncRecord>,
        cursor: Long = this.cursor()
    ) {
        val latestArray = JSONArray().also { array ->
            state.values.sortedWith(compareBy<SyncRecord>({ it.kind }, { it.entityId }))
                .forEach { array.put(it.toJson()) }
        }
        val pendingArray = JSONArray().also { array ->
            pending.forEach { entry ->
                array.put(JSONObject().put("eventId", entry.eventId).put("record", entry.record.toJson()))
            }
        }
        prefs.edit()
            .putLong("clock", clock)
            .putLong("cursor", cursor)
            .putString("latest", latestArray.toString())
            .putString("pending", pendingArray.toString())
            .apply()
    }
}
