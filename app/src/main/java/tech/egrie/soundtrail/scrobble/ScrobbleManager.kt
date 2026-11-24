package tech.egrie.soundtrail.scrobble

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects finished listens, submits them to ListenBrainz and keeps an on-disk queue so listens made
 * offline are sent later.
 *
 * Play-time accounting is driven on the main thread (trackChanged, playbackStarted, playbackTick,
 * playbackPaused); all network work happens on one background thread, spaced to respect ListenBrainz'
 * one-call-per-second limit.
 */
class ScrobbleManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = ListenBrainzStore(appContext)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val pending = ArrayList<Scrobble>()
    private val flushing = AtomicBoolean(false)
    private val nowPlayingLock = Any()

    @Volatile private var flushRequested = false
    @Volatile var lastError: String = ""
        private set
    @Volatile var nowPlayingError: String = ""
        private set
    @Volatile private var currentTrackStatus = TRACK_STATUS_NONE
    @Volatile private var currentTrackError = ""
    @Volatile private var currentListen: Scrobble? = null

    private var pendingNowPlaying: Scrobble? = null
    private var pendingNowPlayingGeneration: Long = 0L
    private var nowPlayingGeneration: Long = 0L
    private var nowPlayingWorkerScheduled = false
    private var lastApiRequestAtMs: Long = 0L
    private var api: ListenBrainzApi? = null

    // Play-time accounting; only touched on the main thread.
    private var currentKey = ""
    private var playedMs: Long = 0L
    private var startedAtMs: Long = 0L
    private var thresholdReached = false

    init {
        synchronized(lock) {
            pending.addAll(Scrobble.fromJsonArray(parse(prefs.getString(KEY_PENDING, "[]"))))
        }
    }

    fun settings(): ListenBrainzStore = store

    /* ======================================================== playback hooks */

    fun trackChanged(key: String?) {
        synchronized(nowPlayingLock) {
            nowPlayingGeneration++
            pendingNowPlaying = null
        }
        synchronized(lock) {
            currentKey = key.orEmpty()
            playedMs = 0L
            thresholdReached = false
            currentListen = null
            currentTrackStatus = TRACK_STATUS_NONE
            currentTrackError = ""
            startedAtMs = System.currentTimeMillis()
        }
    }

    fun playbackPaused() {
        synchronized(nowPlayingLock) {
            nowPlayingGeneration++
            pendingNowPlaying = null
        }
    }

    fun playbackStarted(track: ScrobbleTrack) {
        if (track.key.isEmpty()) return
        if (track.key != currentKey) {
            trackChanged(track.key)
        } else if (playedMs == 0L) {
            startedAtMs = System.currentTimeMillis()
        }
        if (store.isConfigured() && store.announceNowPlaying
            && ScrobblePolicy.isSubmittable(track.artist, track.title)
        ) {
            queuePlayingNow(track.toScrobble(nowSec(), track.durationMs, 0L))
        }
        flush()
    }

    private fun queuePlayingNow(listen: Scrobble) {
        var schedule = false
        synchronized(nowPlayingLock) {
            pendingNowPlaying = listen
            pendingNowPlayingGeneration = ++nowPlayingGeneration
            if (!nowPlayingWorkerScheduled) {
                nowPlayingWorkerScheduled = true
                schedule = true
            }
        }
        if (schedule) {
            try {
                executor.execute { drainPlayingNow() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                synchronized(nowPlayingLock) { nowPlayingWorkerScheduled = false }
                nowPlayingError = "ListenBrainz playing-now update could not be scheduled"
            }
        }
    }

    private fun drainPlayingNow() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val listen: Scrobble?
                val requestGeneration: Long
                synchronized(nowPlayingLock) {
                    listen = pendingNowPlaying
                    requestGeneration = pendingNowPlayingGeneration
                    pendingNowPlaying = null
                }
                if (listen == null) return
                if (!store.isConfigured() || !store.announceNowPlaying) continue
                if (!awaitApiRequestSlot()) return
                synchronized(nowPlayingLock) {
                    if (pendingNowPlaying != null || requestGeneration != nowPlayingGeneration) continue
                }
                if (!store.isConfigured() || !store.announceNowPlaying) continue
                val token = store.token
                recordApiRequest()
                try {
                    api().submitPlayingNow(token, listen)
                    nowPlayingError = ""
                } catch (e: IOException) {
                    nowPlayingError = describe(e)
                } catch (e: RuntimeException) {
                    nowPlayingError = describe(IOException(e.message ?: "ListenBrainz playing-now update failed", e))
                }
            }
        } finally {
            var reschedule = false
            synchronized(nowPlayingLock) {
                nowPlayingWorkerScheduled = false
                if (pendingNowPlaying != null && !Thread.currentThread().isInterrupted) {
                    nowPlayingWorkerScheduled = true
                    reschedule = true
                }
            }
            if (reschedule) {
                try {
                    executor.execute { drainPlayingNow() }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    synchronized(nowPlayingLock) { nowPlayingWorkerScheduled = false }
                    nowPlayingError = "ListenBrainz playing-now update could not be scheduled"
                }
            }
        }
    }

    fun playbackTick(track: ScrobbleTrack, deltaMs: Long, durationMs: Long) {
        if (track.key.isEmpty()) return
        if (track.key != currentKey) trackChanged(track.key)
        if (thresholdReached || !store.isConfigured()) return
        if (deltaMs <= 0L || deltaMs > ScrobblePolicy.MAX_TICK_MS) return
        if (!ScrobblePolicy.isSubmittable(track.artist, track.title)) {
            thresholdReached = true
            currentTrackStatus = TRACK_STATUS_MISSING_METADATA
            return
        }
        playedMs += deltaMs
        val duration = if (durationMs > 0L) durationMs else track.durationMs
        if (ScrobblePolicy.shouldScrobble(playedMs, duration)) {
            thresholdReached = true
            val listen = track.toScrobble(startedAtMs / 1000L, duration, playedMs)
            currentListen = listen
            currentTrackStatus = TRACK_STATUS_QUEUED
            currentTrackError = ""
            enqueue(listen)
        }
    }

    fun isCurrentTrackScrobbled(): Boolean = currentTrackStatus == TRACK_STATUS_SUBMITTED

    fun currentTrackStatusMessage(): String = when (currentTrackStatus) {
        TRACK_STATUS_QUEUED -> {
            val error = currentTrackError
            if (error.isNotEmpty()) "Listen queued for retry · $error"
            else if (store.isConfigured()) "Listen queued for ListenBrainz" else "Listen queued · scrobbling is off"
        }
        TRACK_STATUS_SUBMITTED -> "Scrobbled to ListenBrainz ✓"
        TRACK_STATUS_MISSING_METADATA -> "Not scrobbled · artist or title unavailable"
        TRACK_STATUS_DISCARDED -> if (currentTrackError.isNotEmpty()) "Listen discarded · $currentTrackError" else "Listen removed before submission"
        else -> ""
    }

    /* ================================================================== queue */

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun clearLastError() { lastError = "" }

    fun clearPending() {
        synchronized(lock) {
            val cur = currentListen
            if (cur != null && pending.contains(cur)) {
                currentTrackStatus = TRACK_STATUS_DISCARDED
                currentTrackError = ""
            }
            pending.clear()
            persistLocked()
        }
        lastError = ""
    }

    private fun enqueue(listen: Scrobble?) {
        if (listen == null || !listen.isSubmittable()) return
        synchronized(lock) {
            pending.add(listen)
            while (pending.size > MAX_PENDING) {
                val discarded = pending.removeAt(0)
                if (discarded === currentListen) {
                    currentTrackStatus = TRACK_STATUS_DISCARDED
                    currentTrackError = "Offline listen queue is full"
                }
            }
            persistLocked()
        }
        flush()
    }

    fun flush() {
        if (!store.isConfigured() || pendingCount() == 0) return
        if (!flushing.compareAndSet(false, true)) {
            flushRequested = true
            return
        }
        try {
            executor.execute {
                try {
                    do {
                        flushRequested = false
                        drain()
                    } while (flushRequested && pendingCount() > 0 && store.isConfigured() && !Thread.currentThread().isInterrupted)
                } finally {
                    flushing.set(false)
                    if (flushRequested && pendingCount() > 0 && store.isConfigured()) flush()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            flushing.set(false)
        }
    }

    private fun drain() {
        var rateLimitRetries = 0
        while (!Thread.currentThread().isInterrupted) {
            val batch: List<Scrobble>
            val single: Boolean
            synchronized(lock) {
                if (pending.isEmpty()) break
                single = pending.size == 1
                val count = minOf(pending.size, BATCH_SIZE)
                batch = ArrayList(pending.subList(0, count))
            }
            if (!store.isConfigured() || !awaitApiRequestSlot()) break
            synchronized(lock) {
                if (!pending.containsAll(batch)) continue
            }
            if (!store.isConfigured()) break
            val token = store.token
            recordApiRequest()
            try {
                if (single) api().submitSingle(token, batch[0]) else api().submitImport(token, batch)
                synchronized(lock) {
                    pending.removeAll(batch.toSet())
                    persistLocked()
                    lastError = ""
                    val tracked = currentListen
                    if (tracked != null && batch.contains(tracked)) {
                        currentTrackError = ""
                        currentTrackStatus = TRACK_STATUS_SUBMITTED
                    }
                }
                rateLimitRetries = 0
            } catch (e: ListenBrainzApi.HttpFailure) {
                if (e.code == 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                    rateLimitRetries++
                    if (!sleep(e.retryAfterSec * 1000L)) break
                    continue
                }
                recordQueueFailure(batch, e)
                break
            } catch (e: IOException) {
                recordQueueFailure(batch, e)
                break
            } catch (e: RuntimeException) {
                recordQueueFailure(batch, IOException("ListenBrainz submission failed", e))
                break
            }
        }
    }

    private fun recordQueueFailure(batch: List<Scrobble>, error: IOException) {
        val message = describe(error)
        synchronized(lock) {
            val retained = batch.any { pending.contains(it) }
            if (!retained) return
            lastError = message
            val tracked = currentListen
            if (tracked != null && batch.contains(tracked) && currentTrackStatus == TRACK_STATUS_QUEUED) {
                currentTrackError = message
            }
        }
    }

    private fun awaitApiRequestSlot(): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        val elapsed = SystemClock.elapsedRealtime() - lastApiRequestAtMs
        val wait = REQUEST_SPACING_MS - elapsed
        return sleep(wait) && !Thread.currentThread().isInterrupted
    }

    private fun recordApiRequest() { lastApiRequestAtMs = SystemClock.elapsedRealtime() }

    private fun api(): ListenBrainzApi = api ?: ListenBrainzApi(appContext).also { api = it }

    private fun persistLocked() {
        prefs.edit().putString(KEY_PENDING, Scrobble.toJsonArray(pending).toString()).apply()
    }

    companion object {
        private const val PREFS = "soundtrail_scrobbles"
        private const val KEY_PENDING = "pending"
        private const val MAX_PENDING = 1000
        private const val BATCH_SIZE = 50
        private const val REQUEST_SPACING_MS = 1100L
        private const val MAX_RATE_LIMIT_RETRIES = 3

        private const val TRACK_STATUS_NONE = 0
        private const val TRACK_STATUS_QUEUED = 1
        private const val TRACK_STATUS_SUBMITTED = 2
        private const val TRACK_STATUS_MISSING_METADATA = 3
        private const val TRACK_STATUS_DISCARDED = 4

        @Volatile private var instance: ScrobbleManager? = null

        fun get(context: Context): ScrobbleManager = instance ?: synchronized(this) {
            instance ?: ScrobbleManager(context.applicationContext).also { instance = it }
        }

        private fun parse(text: String?): JSONArray = try { JSONArray(text ?: "[]") } catch (_: Exception) { JSONArray() }

        private fun sleep(millis: Long): Boolean {
            if (Thread.currentThread().isInterrupted) return false
            if (millis <= 0L) return true
            return try {
                Thread.sleep(minOf(millis, 125_000L))
                !Thread.currentThread().isInterrupted
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        private fun describe(error: IOException?): String {
            val message = error?.message ?: return "ListenBrainz submission failed"
            return if (message.length > 160) message.substring(0, 160) + "…" else message
        }

        private fun nowSec(): Long = System.currentTimeMillis() / 1000L
    }
}

/** Minimal track snapshot for scrobble accounting. */
data class ScrobbleTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val musicService: String = "",
    val originUrl: String = ""
) {
    fun toScrobble(listenedAtSec: Long, durationMs: Long, playedMs: Long): Scrobble = Scrobble.from(
        artist = artist, title = title, album = album,
        listenedAtSec = listenedAtSec, durationMs = durationMs, playedMs = playedMs,
        musicService = musicService, originUrl = originUrl
    )
}
