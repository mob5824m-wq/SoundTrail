package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One snapshot the app wrote for itself, without the listener choosing a file each time. */
internal data class ManagedBackup(
    val fileName: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val pins: Int,
    val playlists: Int,
    val automations: Int
) {
    val totalItems: Int get() = pins + playlists + automations
}

/** What the Connections screen shows about the automatic backups. */
internal data class ManagedBackupStatus(
    val enabled: Boolean = true,
    val backups: List<ManagedBackup> = emptyList(),
    val busy: Boolean = false
) {
    val latest: ManagedBackup? get() = backups.firstOrNull()
}

/**
 * A managed backup process that lives entirely on the phone.
 *
 * The manual Export button exists for a file the listener takes somewhere; this is the one that
 * keeps working without anyone remembering it. After a change to pins, playlists, or
 * automations, the view model asks for a snapshot (debounced), the newest [MAX_KEPT] are kept in
 * private app storage, and the older ones are deleted. Nothing here is uploaded, nothing needs a
 * document picker, and audio files and stored credentials are not part of the JSON — exactly the
 * same [BackupFormat] document the manual export produces.
 *
 * Restoring merges additively through the ordinary import path, so a backup taken before an
 * accidental deletion brings the missing items back without overwriting anything edited since.
 */
internal class ManagedBackupStore(context: Context) {
    private val dir = File(context.filesDir, DIRECTORY)
    private val prefs = context.getSharedPreferences("soundtrail_backups", Context.MODE_PRIVATE)

    /** Automatic snapshots are on by default; the listener can switch them off without losing files. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** Newest first. Unreadable or foreign files are skipped rather than breaking the list. */
    fun list(): List<ManagedBackup> =
        snapshotFiles().mapNotNull { file -> runCatching { describe(file) }.getOrNull() }

    /** Every file this store owns, newest first, whether or not it still parses. */
    private fun snapshotFiles(): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && isBackupName(it.name) }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Writes the current library, prunes old snapshots, and returns the new file. Null means
     * automatic backups are off or the directory could not be written.
     */
    fun write(
        pins: List<MusicPin>,
        playlists: List<Playlist>,
        automations: List<tech.egrie.soundtrail.automation.AutomationRule>,
        evenWhenDisabled: Boolean = false
    ): ManagedBackup? {
        if (!enabled && !evenWhenDisabled) return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val now = System.currentTimeMillis()
        val text = BackupFormat.export(pins, playlists, automations)
        var target = File(dir, "$PREFIX$now.json")
        var suffix = 1
        while (target.exists()) {
            target = File(dir, "$PREFIX$now-$suffix.json")
            suffix += 1
        }
        val temp = File(dir, ".${target.name}.tmp")
        return runCatching {
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                temp.delete()
                return@runCatching null
            }
            prefs.edit().putLong("last_at", now).apply()
            prune()
            describe(target)
        }.onFailure { temp.delete() }.getOrNull()
    }

    /** Parses one managed snapshot through the same reader the document picker uses. */
    fun read(fileName: String): BackupContents {
        val file = safeFile(fileName)
            ?: throw BackupException("That on-device backup is no longer available.")
        val text = runCatching { file.readText() }
            .getOrElse { throw BackupException("Couldn't read that on-device backup.") }
        return BackupFormat.parse(text)
    }

    /** Deletes every managed snapshot; returns how many files were removed. */
    fun clear(): Int = snapshotFiles().count { file -> file.delete() }

    /** Retention is by file, so a snapshot that no longer parses is still rotated out. */
    private fun prune() {
        snapshotFiles().drop(MAX_KEPT).forEach { old -> runCatching { old.delete() } }
    }

    private fun describe(file: File): ManagedBackup {
        val root = JSONObject(file.readText())
        if (root.optString("format") != BackupFormat.FORMAT) {
            throw BackupException("Not a Soundtrail backup.")
        }
        return ManagedBackup(
            fileName = file.name,
            createdAt = root.optLong("exportedAt", file.lastModified()),
            sizeBytes = file.length(),
            pins = root.optJSONArray("pins")?.length() ?: 0,
            playlists = root.optJSONArray("playlists")?.length() ?: 0,
            automations = root.optJSONArray("automations")?.length() ?: 0
        )
    }

    /**
     * Only names this store wrote, resolved inside its own directory. A name from the UI must
     * never be able to walk out of private storage.
     */
    private fun safeFile(fileName: String): File? {
        if (!isBackupName(fileName)) return null
        val file = File(dir, fileName)
        val parent = file.canonicalFile.parentFile ?: return null
        if (parent != dir.canonicalFile) return null
        return file.takeIf { it.isFile }
    }

    companion object {
        /** How many snapshots are kept; older ones are deleted as new ones arrive. */
        const val MAX_KEPT = 10
        private const val DIRECTORY = "managed-backups"
        private const val PREFIX = "soundtrail-managed-"

        /** Matches exactly `soundtrail-managed-<epochMs>[-n].json`; the store writes no other name. */
        private val NAME = Regex("^soundtrail-managed-\\d{1,19}(-\\d{1,4})?\\.json$")

        /** True for the file names this store creates, and nothing else. */
        fun isBackupName(name: String): Boolean = name.length <= 80 && NAME.matches(name)
    }
}
