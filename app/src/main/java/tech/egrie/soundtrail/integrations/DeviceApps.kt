package tech.egrie.soundtrail.integrations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import java.util.Locale

/** Device app detection and optional external browsing. Music playback now happens directly in-app. */
class DeviceApps(private val context: Context) {
    private fun installed(name: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(name, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    val spotifyInstalled: Boolean get() = installed("com.spotify.music")
    val soundcloudInstalled: Boolean get() = installed("com.soundcloud.android")
    val youtubeMusicPackage: String? get() = listOf(
        "app.morphe.android.apps.youtube.music",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.apps.youtube.music",
        "app.revanced.android.youtube.music"
    ).firstOrNull(::installed)

    /**
     * True Google Sign-In support: Google Play Services or standard microG under the system
     * package name. MicroG RE does not serve unpatched third-party apps, so it is reported
     * separately and the Connect tab offers the browser Client ID sign-in instead.
     */
    val systemGmsInstalled: Boolean get() = installed("com.google.android.gms")

    /** MicroG RE / ReVanced GmsCore: authenticates Morphe/ReVanced patched apps only. */
    val microGReInstalled: Boolean get() =
        installed("app.revanced.android.gms") || installed("com.mgoogle.android.gms")

    /** A standard com.google.android.gms installation may be Google Play Services OR microG. */
    val servicesLabel: String get() = when {
        systemGmsInstalled && microGReInstalled -> "Google services + MicroG RE detected"
        microGReInstalled -> "MicroG RE detected"
        systemGmsInstalled -> "Google services available (may be microG)"
        else -> "No Google services detected"
    }

    /**
     * Legacy external playback — no longer used for primary music playback since all music
     * now plays directly in Soundtrail. Kept for optional "open in external app" actions.
     */
    fun play(link: MusicLink): Boolean {
        val verified = MusicLinkParser.parse(link.url) ?: return false
        // All providers now play in-app; this is only a fallback for opening in external apps if user wants.
        val preferredPackage = when (verified.provider) {
            MusicProvider.SPOTIFY -> "com.spotify.music".takeIf(::installed)
            MusicProvider.YOUTUBE_MUSIC -> youtubeMusicPackage
            MusicProvider.YOUTUBE -> "com.google.android.youtube".takeIf(::installed)
            MusicProvider.SOUNDCLOUD -> "com.soundcloud.android".takeIf(::installed)
            MusicProvider.JELLYFIN -> null
        }
        if (preferredPackage != null && view(verified.url, preferredPackage)) return true
        return view(verified.url)
    }

    /**
     * Opens the YouTube Music app (including Morphe/ReVanced patched installs), or the website
     * when no music app is installed.
     *
     * This never involves Google sign-in: handing a link to an app the listener is already
     * signed in to is not an API call of Soundtrail's. Only the optional playlist import/search
     * below needs the read-only connection, so opening the music app keeps working across app
     * updates, reinstalls, and revoked grants.
     */
    fun openYouTubeMusic(): Boolean = launch(youtubeMusicPackage) || view("https://music.youtube.com/")

    /** True when a YouTube Music app is installed (any of the known package names). */
    val youtubeMusicInstalled: Boolean get() = youtubeMusicPackage != null

    /** Opens the signed-in user's library inside YouTube Music or a Morphe-compatible install. */
    fun openYouTubeMusicLibrary(): Boolean =
        openYouTubeMusicUrl("https://music.youtube.com/library")

    /**
     * Hands a search to the user's YouTube Music app (including installs using microG), falling
     * back to the YouTube Music website. Soundtrail never queries a YouTube Music API itself.
     */
    fun searchYouTubeMusic(query: String): Boolean =
        openYouTubeMusicSearch(YouTubeSearch.searchUrl(query, ""))

    fun searchYouTubeMusic(title: String, artist: String): Boolean =
        openYouTubeMusicSearch(YouTubeSearch.searchUrl(title, artist))

    /**
     * Hands a mood's search phrases to YouTube Music — the escape hatch when the local library
     * has nothing that fits. No sign-in, no API: it is the same link a browser would open.
     */
    fun searchYouTubeMusicForMood(query: String): Boolean {
        val cleaned = query.replace(Regex("\\s+"), " ").trim().take(120)
        if (cleaned.isBlank()) return openYouTubeMusic()
        return openYouTubeMusicSearch(YouTubeSearch.searchUrl(cleaned, ""))
    }

    /** Only accept search links built for the YouTube Music host before handing them off. */
    fun openYouTubeMusicSearch(url: String): Boolean {
        if (Uri.parse(url).path != "/search") return false
        return openYouTubeMusicUrl(url)
    }

    /**
     * Opens one song in the YouTube Music app — the same video id, so it is the same recording —
     * which is how a catalogue result is heard in full with YT Music's own account and quality.
     */
    fun openYouTubeMusicSong(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.path != "/watch") return false
        if (uri.getQueryParameter("v").isNullOrBlank()) return false
        return openYouTubeMusicUrl(url)
    }

    private fun openYouTubeMusicUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "music.youtube.com") return false
        val packageName = youtubeMusicPackage
        return (packageName != null && view(url, packageName)) || view(url)
    }

    fun openSpotify(): Boolean = launch("com.spotify.music".takeIf(::installed)) || view("https://open.spotify.com/")
    fun openSoundCloud(): Boolean = launch("com.soundcloud.android".takeIf(::installed)) || view("https://soundcloud.com/")

    /** Hands a URL to an app the user chooses. This external share action performs no download. */
    fun shareUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        return start(Intent.createChooser(intent, null))
    }

    /** Legacy: local files now play directly in-app via JellyfinPlayer. Kept for share/open actions. */
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

    private fun launch(packageName: String?): Boolean {
        if (packageName == null) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent)
    }

    private fun view(url: String, packageName: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
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
