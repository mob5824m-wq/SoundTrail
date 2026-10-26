package tech.egrie.soundtrail.integrations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import java.util.Locale

/**
 * The few things this app still hands to another app.
 *
 * Music playback is not one of them: every song plays inside Soundtrail, from the listener's own
 * Jellyfin server or from an audio file they attached. What is left is deliberately small —
 * sharing a link, opening a web page, opening a place on a map, and opening an attached file with
 * whatever the listener prefers. The Spotify, SoundCloud and YouTube Music hand-offs (including
 * the Morphe and ReVanced package lookups) were removed with those services, so this class no
 * longer asks the package manager about any music app.
 */
class DeviceApps(private val context: Context) {

    /** Hands a URL to an app the user chooses. This external share action performs no download. */
    fun shareUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        return start(Intent.createChooser(intent, null))
    }

    /** Opens an attached offline copy in another app, for the listener who wants their own player. */
    fun playLocalFile(fileName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileStore(context).openUri(fileName), "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent)
    }

    fun openWeb(url: String): Boolean = view(url)

    fun showOnMap(point: GeoPoint): Boolean {
        val lat = String.format(Locale.US, "%.6f", point.latitude)
        val lon = String.format(Locale.US, "%.6f", point.longitude)
        val geo = "geo:$lat,$lon?q=$lat,$lon"
        if (view(geo)) return true
        return view("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon")
    }

    private fun view(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
