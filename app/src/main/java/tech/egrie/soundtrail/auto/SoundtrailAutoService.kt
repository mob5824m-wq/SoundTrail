package tech.egrie.soundtrail.auto

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot
import androidx.media.MediaBrowserServiceCompat.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.R
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.playback.LibraryMedia
import tech.egrie.soundtrail.playback.PlaybackHub
import tech.egrie.soundtrail.playback.QueueEntry

/**
 * Android Auto's way in: the browse tree the car lists, and the session it controls.
 *
 * The car is another app, so it can only see what this service chooses to hand over — pins,
 * playlists and their songs, in the shape [AutoTree] builds. Playback itself never happens
 * here: a tapped row goes to [PlaybackHub], which is the same player the app's own screens,
 * the notification and the lockscreen drive, so the phone and the car cannot disagree about
 * what is playing.
 *
 * Who may connect is decided by [AutoClientPolicy]; everyone else is answered with "no root".
 * The library stays where it has always been — on this phone.
 */
class SoundtrailAutoService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var hub: PlaybackHub

    private val pins by lazy { PinStore(this) }
    private val playlists by lazy { PlaylistStore(this) }

    /**
     * The app's own mark, shipped as a plain PNG on purpose: a car host reads an icon URI
     * itself, and the mipmap id resolves to the adaptive icon (an XML) on API 26+, which it
     * could not decode.
     */
    private val artwork: Uri by lazy {
        Uri.parse("android.resource://$packageName/${R.drawable.soundtrail_artwork}")
    }

    override fun onCreate() {
        super.onCreate()
        hub = PlaybackHub.shared(this)
        // The car needs a session the moment it connects, even if nothing is playing yet.
        setSessionToken(hub.session.token)
        hub.refresh()
        // An edit made on the phone while the car is open (a song added, a copy downloaded)
        // should show up in the next list the driver opens.
        hub.setLibraryListener { runCatching { notifyChildrenChanged(LibraryMedia.ROOT) } }
    }

    override fun onDestroy() {
        hub.setLibraryListener(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!isTrustedClient(clientPackageName, clientUid)) return null
        return BrowserRoot(LibraryMedia.ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        // The library lives in this app's own store: quick to read, but a car must never wait
        // on a phone's main thread, so the answer is sent from here once it is ready.
        result.detach()
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                AutoTree.rows(parentId, pins.all(), playlists.all()).map { it.toMediaItem() }
            }
            runCatching { result.sendResult(items) }
        }
    }

    override fun onLoadItem(itemId: String?, result: Result<MediaBrowserCompat.MediaItem>) {
        if (itemId == null) {
            result.sendResult(null)
            return
        }
        result.detach()
        scope.launch {
            val item = withContext(Dispatchers.IO) {
                AutoTree.row(itemId, pins.all(), playlists.all())?.toMediaItem()
            }
            runCatching { result.sendResult(item) }
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                AutoTree.search(query, pins.all(), playlists.all())
                    ?.entries.orEmpty()
                    .map { it.toMediaItem() }
            }
            runCatching { result.sendResult(items) }
        }
    }

    /**
     * The caller's own uid decides, not the name it claims: every package that uid owns is
     * checked against the policy, so an app cannot pass itself off as the car host.
     */
    private fun isTrustedClient(clientPackageName: String, clientUid: Int): Boolean {
        val byUid = if (clientUid >= 0) {
            runCatching { packageManager.getPackagesForUid(clientUid)?.toList().orEmpty() }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val callers = (byUid + clientPackageName).distinct()
        return AutoClientPolicy.isTrusted(callers, ownPackage = packageName) { candidate ->
            runCatching {
                packageManager.checkPermission(AutoClientPolicy.MEDIA_CONTENT_CONTROL, candidate) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }

    private fun AutoRow.toMediaItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle.takeIf(String::isNotBlank))
            .setIconUri(artwork)
            .build()
        val flags = (if (browsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else 0) or
            (if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0)
        return MediaBrowserCompat.MediaItem(description, flags)
    }

    /** A spoken search result: playable on its own, with the app's mark beside it. */
    private fun QueueEntry.toMediaItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle.takeIf(String::isNotBlank))
            .setIconUri(artwork)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }
}
