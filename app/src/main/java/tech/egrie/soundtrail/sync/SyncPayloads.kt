package tech.egrie.soundtrail.sync

import org.json.JSONObject
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.data.BackupContents
import tech.egrie.soundtrail.data.BackupFormat
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * Splits playlist metadata and its song entries into independent sync entities. That way,
 * two devices adding different songs at the same time can merge without losing either song.
 * Imported audio file names are intentionally omitted: private files do not sync.
 */
internal object SyncPayloads {
    fun snapshot(
        pins: List<MusicPin>, playlists: List<Playlist>, automations: List<AutomationRule>
    ): List<SyncRecord> = buildList {
        pins.forEach { pin ->
            add(content(SYNC_KIND_PIN, pin.id, BackupFormat.pinJson(pin).toString()))
        }
        playlists.forEach { playlist ->
            val metadata = JSONObject()
                .put("id", playlist.id)
                .put("name", playlist.name)
                .put("createdAt", playlist.createdAt)
            add(content(SYNC_KIND_PLAYLIST, playlist.id, metadata.toString()))
            playlist.items.forEach { item ->
                val song = JSONObject()
                    .put("id", item.id)
                    .put("playlistId", playlist.id)
                    .put("title", item.title)
                    .put("artist", item.artist)
                    .put("createdAt", item.createdAt)
                item.url?.let { song.put("url", it) }
                add(content(SYNC_KIND_PLAYLIST_ITEM, item.id, song.toString()))
            }
        }
        automations.forEach { rule ->
            add(content(SYNC_KIND_AUTOMATION, rule.id, BackupFormat.ruleJson(rule).toString()))
        }
    }

    fun restore(
        records: Map<String, SyncRecord>,
        existingPlaylists: List<Playlist>,
        localFileExists: (String) -> Boolean
    ): BackupContents {
        val active = records.values.filterNot { it.deleted }
        val pins = active.filter { it.kind == SYNC_KIND_PIN }.mapNotNull { record ->
            runCatching { BackupFormat.parsePin(JSONObject(record.payload ?: return@mapNotNull null)) }
                .getOrNull()?.takeIf { it.id == record.entityId }
        }
        val playlists = active.filter { it.kind == SYNC_KIND_PLAYLIST }.mapNotNull { record ->
            runCatching {
                val json = JSONObject(record.payload ?: return@mapNotNull null)
                val id = json.getString("id")
                if (id != record.entityId) return@mapNotNull null
                Playlist(
                    id = id,
                    name = json.getString("name"),
                    createdAt = json.getLong("createdAt"),
                    items = emptyList()
                )
            }.getOrNull()
        }.associateBy { it.id }

        val oldItems = existingPlaylists.flatMap { it.items }.associateBy { it.id }
        val itemsByPlaylist = active.filter { it.kind == SYNC_KIND_PLAYLIST_ITEM }.mapNotNull { record ->
            runCatching {
                val json = JSONObject(record.payload ?: return@mapNotNull null)
                val id = json.getString("id")
                if (id != record.entityId) return@mapNotNull null
                val playlistId = json.getString("playlistId")
                if (playlistId !in playlists) return@mapNotNull null
                val storedUrl = json.optString("url").takeIf { it.isNotBlank() && it != "null" }
                val localFile = oldItems[record.entityId]?.localFile?.takeIf(localFileExists)
                // An event written by an older version can carry a link to a service this one no
                // longer supports. The rule matches the local stores and the backup reader, so a
                // dead link cannot arrive from another device either.
                val dead = MusicLinkParser.isUnplayable(storedUrl)
                if (dead && localFile == null) return@mapNotNull null
                val link = if (dead) null else storedUrl?.let(MusicLinkParser::parse)
                playlistId to PlaylistItem(
                    id = id,
                    title = json.getString("title"),
                    artist = json.optString("artist"),
                    url = link?.url,
                    provider = link?.provider,
                    createdAt = json.getLong("createdAt"),
                    localFile = localFile
                )
            }.getOrNull()
        }.groupBy({ it.first }, { it.second })

        val groupedPlaylists = playlists.values.map { playlist ->
            playlist.copy(items = itemsByPlaylist[playlist.id].orEmpty().sortedBy { it.createdAt })
        }.sortedBy { it.createdAt }
        val rules = active.filter { it.kind == SYNC_KIND_AUTOMATION }.mapNotNull { record ->
            runCatching { BackupFormat.parseRule(JSONObject(record.payload ?: return@mapNotNull null)) }
                .getOrNull()?.takeIf { it.id == record.entityId }
        }.filter { rule -> groupedPlaylists.any { it.id == rule.playlistId } }

        return BackupContents(pins, groupedPlaylists, rules)
    }

    private fun content(kind: String, id: String, payload: String) =
        SyncRecord(kind, id, clock = 0L, deviceId = "", deleted = false, payload = payload)
}
