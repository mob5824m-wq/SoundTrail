package tech.egrie.soundtrail.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Audio attached from the user's device or downloaded through an authenticated Jellyfin
 * account. Files are kept in private app storage
 * and shared read-only through FileProvider when played.
 */
internal data class StoredAudioFile(val name: String, val sizeBytes: Long)

class FileStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "audio").apply { mkdirs() }

    /** Creates an unguessable staging file inside private audio storage. */
    fun newDownloadTemp(): File = File(dir, ".audio-download-${UUID.randomUUID()}.part")

    /** Promotes a completed offline-audio transfer to a private file and returns its name. */
    fun finishDownload(temp: File, contentType: String?, sourceName: String?): String? = runCatching {
        if (temp.canonicalFile.parentFile != dir.canonicalFile || !temp.isFile || temp.length() == 0L) return null
        val extension = extensionForDownload(contentType, sourceName)
        val name = "${System.nanoTime()}-${UUID.randomUUID()}$extension"
        val target = File(dir, name)
        try {
            if (!temp.renameTo(target)) {
                temp.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                temp.delete()
            }
            if (!target.isFile || target.length() == 0L) {
                target.delete()
                null
            } else name
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }.getOrNull()

    /** Removes an incomplete or cancelled transfer, but only when it belongs to our audio dir. */
    fun discardDownloadTemp(temp: File) {
        runCatching { if (temp.canonicalFile.parentFile == dir.canonicalFile) temp.delete() }
    }

    private fun extensionForDownload(contentType: String?, sourceName: String?): String {
        val mimeExtension = when (contentType?.substringBefore(';')?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/ogg", "audio/vorbis", "audio/opus" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            "audio/x-ms-wma" -> "wma"
            else -> null
        }
        val nameExtension = sourceName.orEmpty().substringAfterLast('.', "")
            .lowercase().takeIf { it in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "aiff", "aif", "wma", "mka", "webm") }
        return ".${mimeExtension ?: nameExtension ?: "audio"}"
    }

    /** Copies the picked audio into private storage and returns the stored file name. */
    fun importAudio(uri: Uri): String? {
        val name = "${System.nanoTime()}${extensionFor(uri)}"
        return runCatching {
            val target = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (target.length() == 0L) {
                target.delete()
                return null
            }
            name
        }.getOrNull()
    }

    fun fileFor(name: String): File? = runCatching {
        val root = dir.canonicalFile
        File(root, name).canonicalFile.takeIf { it.parentFile == root && it.isFile }
    }.getOrNull()

    fun openUri(name: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, name))

    fun delete(name: String) {
        removeStoredFile(name)
    }

    /** Deletes only a direct child of the private audio directory; missing files count as removed. */
    fun removeStoredFile(name: String): Boolean = runCatching {
        val root = dir.canonicalFile
        val target = File(root, name).canonicalFile
        if (target.parentFile != root) return@runCatching false
        !target.exists() || target.delete()
    }.getOrDefault(false)

    /** True when a previously imported audio file is still present — a backup's file names mean nothing on another device. */
    fun exists(name: String): Boolean = runCatching {
        val root = dir.canonicalFile
        File(root, name).canonicalFile.let { it.parentFile == root && it.isFile }
    }.getOrDefault(false)

    /** Physical local audio files, largest first; incomplete .part transfers are excluded. */
    internal fun listStoredAudio(): List<StoredAudioFile> = runCatching {
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part", ignoreCase = true) }
            .map { StoredAudioFile(it.name, it.length()) }
            .sortedByDescending { it.sizeBytes }
    }.getOrDefault(emptyList())

    /** Total private audio storage, excluding incomplete offline-transfer files. */
    fun totalBytes(): Long = listStoredAudio().sumOf { it.sizeBytes }

    private fun extensionFor(uri: Uri): String {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        when (type) {
            "audio/mpeg", "audio/mp3" -> return ".mp3"
            "audio/mp4", "audio/m4a", "audio/aac" -> return ".m4a"
            "audio/ogg", "audio/vorbis", "audio/opus" -> return ".ogg"
            "audio/flac", "audio/x-flac" -> return ".flac"
            "audio/wav", "audio/x-wav" -> return ".wav"
        }
        val display = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().orEmpty()
        val candidate = display.substringAfterLast('.', "")
        return if (candidate.matches(Regex("[A-Za-z0-9]{1,5}"))) ".$candidate" else ".mp3"
    }
}
