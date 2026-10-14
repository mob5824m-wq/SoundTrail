package tech.egrie.soundtrail.integrations

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
private const val YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/**
 * Browser OAuth redirect for the Desktop-client sign-in. Google's documented custom-scheme
 * form is `scheme:/path` (single slash); the scheme must contain a period. No Google Play
 * services, standard microG account, or MicroG RE account is needed for this path.
 */
const val YOUTUBE_OAUTH_REDIRECT_URI = "app.soundtrail:/youtube-auth"
private const val GOOGLE_OAUTH_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
private const val GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val GOOGLE_OAUTH_REVOKE_URL = "https://oauth2.googleapis.com/revoke"

open class YouTubeDataException(message: String) : Exception(message)

/**
 * Google approval for the read-only scope is missing for this account. The caller should run the
 * interactive Google Sign-In flow, which shows the consent screen, and then connect again.
 */
class YouTubeConsentRequiredException : YouTubeDataException(
    "Approve the YouTube read-only permission for this Google account, then connect again."
)

/** The Data API rejected the current access token. The OAuth path refreshes once before surfacing this. */
internal class YouTubeAuthExpiredException(message: String) : YouTubeDataException(message)

/**
 * Google no longer accepts the saved grant, so this device cannot use the connection until the
 * listener signs in again. The saved tokens and the account label are deliberately **kept**:
 * that is what makes reconnecting a single tap (the Client ID and the channel name are still
 * there), and it means a transient outage can never be mistaken for a lost login.
 */
class YouTubeReconnectRequiredException(message: String) : YouTubeDataException(message)

/** One playlist item as pulled from the YouTube Data API, ready to turn into a Soundtrail entry. */
internal data class YouTubeImportedItem(
    val videoId: String,
    val title: String,
    val artist: String
)

/** One playlist as pulled from YouTube: stable YouTube playlist id, display title, all items. */
internal data class YouTubeImportedPlaylist(
    val playlistId: String,
    val title: String,
    val items: List<YouTubeImportedItem>
)

/**
 * Read-only YouTube Data API access with two mutually exclusive sign-in methods. The
 * Google-account method uses the accounts microG (or Google Play services) registers under the
 * `com.google` type and requests its OAuth token from GmsCore when needed, keeping only the
 * account name and type. The browser-OAuth method uses the user's own Desktop client with PKCE
 * and keeps Keystore-encrypted access and refresh tokens, needing no Google services at all —
 * that is the path that works alongside MicroG RE, which only serves patched apps.
 */
internal class YouTubeDataService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = appContext.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    /**
     * Connection *status* only — timestamps and two flags. No token value is ever written here;
     * the tokens themselves live in [SecretStore], encrypted with a device key. Keeping the
     * status outside the encrypted store means the Connect tab can still explain what happened
     * to a connection whose ciphertext this device can no longer read.
     */
    private val statusPrefs = appContext.getSharedPreferences("soundtrail_google_auth", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)
    private val oauthMutex = Mutex()

    @Volatile private var cachedLibrary: CachedLibrary? = null

    private data class CachedPlaylist(val playlistId: String, val title: String, val body: String)
    private data class CachedLibrary(val cachedAt: Long, val playlists: List<CachedPlaylist>)

    val accountName: String? get() = secrets.get("youtube_data_account")?.takeIf(String::isNotBlank)

    /** Either sign-in method counts; the two are mutually exclusive and the newest wins. */
    val connected: Boolean get() = gmsConnected || oauthConnected

    private val gmsConnected: Boolean
        get() = accountName != null && secrets.get("youtube_data_account_name") != null

    /**
     * A saved browser-OAuth connection counts as connected as long as its tokens are readable
     * and Google has not rejected the grant. It stays "connected" through network outages and
     * app updates: only Google itself can clear it.
     */
    val oauthConnected: Boolean get() = readOAuthToken() != null && !needsReconnect

    /** The user's own Desktop OAuth client ID, mirrored in the Connect tab's Client ID field. */
    val oauthClientId: String get() = settings.getString("youtube_oauth_client_id", "").orEmpty()

    // ----- Connection status: kept outside the encrypted store, never a token value -----

    private var needsReconnect: Boolean
        get() = statusPrefs.getBoolean(KEY_NEEDS_RECONNECT, false)
        set(value) {
            statusPrefs.edit().putBoolean(KEY_NEEDS_RECONNECT, value).commit()
        }

    private var unreachable: Boolean
        get() = statusPrefs.getBoolean(KEY_UNREACHABLE, false)
        set(value) {
            statusPrefs.edit().putBoolean(KEY_UNREACHABLE, value).commit()
        }

    /** Records that Google answered, which is the only thing that clears a failure flag. */
    private fun recordVerified(now: Long = System.currentTimeMillis()) {
        statusPrefs.edit()
            .putBoolean(KEY_NEEDS_RECONNECT, false)
            .putBoolean(KEY_UNREACHABLE, false)
            .putLong(KEY_VERIFIED_AT, now)
            .commit()
    }

    /**
     * The connection's health for the Connect tab. Reads only local metadata: the label, two
     * flags, and timestamps. Nothing here touches the network, so it is safe to call on every
     * screen refresh.
     */
    fun authHealth(): GoogleAuthHealth {
        val now = System.currentTimeMillis()
        val token = readOAuthToken()
        val tokenState = secrets.state("youtube_oauth_token")
        val gms = gmsConnected
        val method = when {
            gms -> "Google account"
            tokenState != SecretState.ABSENT -> "Client ID (browser)"
            else -> null
        }
        val state = when {
            gms -> if (unreachable) GoogleAuthState.OFFLINE else GoogleAuthState.CONNECTED
            tokenState == SecretState.UNREADABLE -> GoogleAuthState.UNREADABLE
            tokenState == SecretState.ABSENT -> GoogleAuthState.NOT_CONNECTED
            needsReconnect -> GoogleAuthState.NEEDS_RECONNECT
            unreachable -> GoogleAuthState.OFFLINE
            else -> GoogleAuthState.CONNECTED
        }
        val verified = statusPrefs.getLong(KEY_VERIFIED_AT, 0L).takeIf { it > 0L }
        val testingExpiry = statusPrefs.getBoolean(KEY_TESTING_EXPIRY, false)
        val detail = when (state) {
            GoogleAuthState.CONNECTED -> buildString {
                append(accountName ?: "Saved on this device")
                GoogleAuthPolicy.describeAge(now, verified)?.let { append(" \u00b7 last verified $it") }
                if (gms) append(" \u00b7 access token requested on demand, never stored")
                else if (token != null && token.refreshCount > 0) {
                    append(" \u00b7 renewed by itself ${token.refreshCount}\u00d7")
                }
            }
            GoogleAuthState.OFFLINE -> buildString {
                append(accountName ?: "Saved on this device")
                GoogleAuthPolicy.describeAge(now, verified)?.let { append(" \u00b7 last verified $it") }
                append(" \u00b7 Google was not reachable just now")
            }
            GoogleAuthState.NEEDS_RECONNECT ->
                "Google stopped accepting this grant" +
                    (GoogleAuthPolicy.describeAge(now, statusPrefs.getLong(KEY_FAILED_AT, 0L).takeIf { it > 0L })
                        ?.let { " $it" } ?: "") +
                    ". The saved sign-in was kept, so reconnecting needs no re-typing."
            GoogleAuthState.UNREADABLE ->
                "Saved tokens are on this device but its key store could not read them; they were kept, not deleted."
            GoogleAuthState.NOT_CONNECTED -> "No Google connection is saved on this device."
        }
        return GoogleAuthHealth(
            state = state,
            account = accountName,
            method = method,
            issuedAt = token?.issuedAt?.takeIf { it > 0L },
            lastRefreshedAt = token?.lastRefreshedAt?.takeIf { it > 0L },
            lastVerifiedAt = verified,
            detail = detail,
            hint = GoogleAuthPolicy.helpText(
                state = state,
                method = method,
                clientIdRemembered = oauthClientId.isNotBlank(),
                testingExpirySuspected = testingExpiry
            ),
            testingExpirySuspected = testingExpiry
        )
    }

    /**
     * Renews the saved access token quietly in the background. Called when the app starts, so a
     * session that outlived its access token (or the app update that installed it) is already
     * healthy by the time the listener taps anything. Failures are recorded in the health
     * status, never by deleting the saved sign-in.
     */
    suspend fun warmUp() {
        if (!oauthConnected) return
        runCatching { oauthAccessToken() }
    }

    /**
     * Fails the call with the most useful sentence available: when Google has already rejected
     * the grant, the listener is told that reconnecting is one tap, instead of being told to
     * "connect" an account they have already connected.
     */
    private fun requireConnected(message: String) {
        if (connected) return
        if (authHealth().state == GoogleAuthState.NEEDS_RECONNECT) {
            throw YouTubeReconnectRequiredException(
                authHealth().hint ?: "Google no longer accepts this connection. Reconnect to continue."
            )
        }
        throw YouTubeDataException(message)
    }

    /** Connects a microG/Google account chosen by the user or found on the device. */
    suspend fun connect(account: Account, label: String?): String {
        // Avoid showing a previous Google account if the user switched accounts.
        cachedLibrary = null
        // This Google-account connection replaces any browser-OAuth connection.
        secrets.remove("youtube_oauth_pending")
        secrets.remove("youtube_oauth_token")
        secrets.remove("youtube_data_account")
        secrets.remove("youtube_data_account_name")
        val token = accessToken(account)
        // Verify that the user's consent and YouTube Data API configuration work before saving.
        apiGet(
            "playlists",
            listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"),
            token
        )
        val name = label?.takeIf(String::isNotBlank)
            ?: account.name.takeIf(String::isNotBlank)
            ?: "YouTube account"
        // The label and the account name are what make an update a no-op for this sign-in method:
        // microG/Play services keeps the grant itself, so nothing here has to be re-authorised.
        secrets.putAll(
            mapOf(
                "youtube_data_account_name" to account.name,
                "youtube_data_account_type" to account.type,
                "youtube_data_account" to name
            )
        )
        recordVerified()
        return name
    }

    /**
     * Clears both sign-in methods and asks Google services to revoke the Google-account grant.
     * Returns the previous browser-OAuth access token, if any, so the caller can revoke it too.
     */
    fun disconnect(): String? {
        cachedLibrary = null
        secrets.remove("youtube_oauth_pending")
        val oauthAccess = readOAuthToken()?.access
        secrets.remove("youtube_oauth_token")
        secrets.remove("youtube_data_account")
        secrets.remove("youtube_data_account_name")
        secrets.remove("youtube_data_account_type")
        statusPrefs.edit()
            .remove(KEY_NEEDS_RECONNECT)
            .remove(KEY_UNREACHABLE)
            .remove(KEY_VERIFIED_AT)
            .remove(KEY_FAILED_AT)
            .remove(KEY_TESTING_EXPIRY)
            .commit()
        runCatching { GoogleSignIn.getClient(appContext, signInOptions()).revokeAccess() }
        return oauthAccess
    }

    /** Asks Google to revoke a browser-OAuth token. Failures are ignored; local data is already gone. */
    suspend fun revokeOAuthToken(token: String) {
        runCatching {
            httpRequest("$GOOGLE_OAUTH_REVOKE_URL?token=${URLEncoder.encode(token, "UTF-8")}", "POST")
        }
    }

    // ----- Browser OAuth: Desktop client, PKCE, no Google services required -----

    /**
     * Starts the browser sign-in with the user's own Desktop OAuth client ID. MicroG RE only
     * serves Morphe/ReVanced patched apps, so this is the path that works for MicroG RE users,
     * de-Googled devices, and anyone without a device Google account — it needs only a browser.
     */
    fun beginOAuthSignIn(inputId: String): Uri {
        // An empty field means "reconnect with the Client ID you already know", which is what the
        // one-tap reconnect does. The Client ID is remembered in settings, so a reconnect never
        // asks the listener to paste it again.
        val remembered = oauthClientId
        val id = YouTubeOAuth.checkClientId(inputId.trim().ifBlank { remembered })
        if (remembered.isNotBlank() && remembered != id) {
            // A different Cloud project's client ID cannot refresh the old project's tokens.
            secrets.remove("youtube_oauth_token")
            secrets.remove("youtube_data_account")
            needsReconnect = false
        }
        settings.edit().putString("youtube_oauth_client_id", id).apply()
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        secrets.put("youtube_oauth_pending", JSONObject().apply {
            put("state", state)
            put("verifier", verifier)
            put("clientId", id)
            put("createdAt", System.currentTimeMillis())
        }.toString())
        return Uri.parse(YouTubeOAuth.authorizationUrl(id, state, Pkce.challenge(verifier)))
    }

    fun cancelOAuthSignIn() = secrets.remove("youtube_oauth_pending")

    /**
     * Finishes the browser sign-in: validates the redirect, exchanges the code with the PKCE
     * verifier, confirms the read-only scope, and verifies the grant against the Data API
     * before saving anything. Returns the channel label shown on the Connect tab.
     */
    suspend fun finishOAuthSignIn(uri: Uri): String {
        val saved = secrets.get("youtube_oauth_pending")
            ?: throw YouTubeDataException("Start YouTube sign-in again.")
        // The authorization response can be consumed only once, even if it was denied.
        secrets.remove("youtube_oauth_pending")
        val pending = runCatching { JSONObject(saved) }.getOrNull()
            ?: throw YouTubeDataException("Start YouTube sign-in again.")
        val code = YouTubeOAuth.authorizationCode(uri.toString(), pending.optString("state"))
        val age = System.currentTimeMillis() - pending.optLong("createdAt", -1L)
        if (age !in 0L..600_000L || oauthClientId != pending.optString("clientId")) {
            throw YouTubeDataException("YouTube sign-in expired or could not be verified. Try again.")
        }
        val response = httpRequest(
            GOOGLE_OAUTH_TOKEN_URL, "POST",
            body = form(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to YOUTUBE_OAUTH_REDIRECT_URI,
                "client_id" to oauthClientId,
                "code_verifier" to pending.optString("verifier")
            )
        )
        if (response.code !in 200..299) throw response.tokenError()
        val parsed = YouTubeOAuth.parseToken(response.body)
        YouTubeOAuth.checkScope(parsed.scope)
        val refresh = parsed.refresh ?: throw YouTubeDataException(
            "Google did not return a refresh token. Remove Soundtrail's access at " +
                "myaccount.google.com/permissions and connect again."
        )
        val now = System.currentTimeMillis()
        val token = YouTubeOAuthToken(
            access = parsed.access,
            refresh = refresh,
            clientId = oauthClientId,
            expiresAt = now + parsed.expiresInSec.coerceAtLeast(60L) * 1_000L,
            issuedAt = now
        )
        // Verify that the grant and the YouTube Data API configuration work before saving.
        apiGet(
            "playlists",
            listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"),
            token.access
        )
        saveOAuthToken(token)
        // This browser connection replaces any Google-account connection.
        secrets.remove("youtube_data_account_name")
        secrets.remove("youtube_data_account_type")
        cachedLibrary = null
        val label = channelLabel(token.access)
        secrets.put("youtube_data_account", label)
        recordVerified(now)
        return label
    }

    /** The channel title shown as the account label; plain "YouTube account" when unavailable. */
    private suspend fun channelLabel(token: String): String = runCatching {
        val json = JSONObject(
            apiGet("channels", listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"), token)
        )
        json.optJSONArray("items")?.optJSONObject(0)
            ?.optJSONObject("snippet")?.optString("title")
            ?.takeIf(String::isNotBlank)?.take(80)
    }.getOrNull() ?: "YouTube account"

    private suspend fun oauthAccessToken(forceRefresh: Boolean = false): String = oauthMutex.withLock {
        val stored = readOAuthToken() ?: throw YouTubeDataException(
            "Connect your YouTube account to search your playlists."
        )
        if (!forceRefresh && stored.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withLock stored.access
        }
        refreshOAuthToken(stored)
    }

    /**
     * Renews the access token, and only ever loses the saved connection when Google itself says
     * the grant is gone.
     *
     * Rotation is write-ahead: the new refresh token is committed to disk *before* the caller is
     * given the access token that belongs to it. If the process dies, or Android installs an
     * app update while this is in flight, the device still holds a token Google accepts. The old
     * code wrote asynchronously and cleared everything on any 400/401, which is what turned a
     * transient failure (or a crash at the wrong moment) into "sign in to Google again".
     */
    private suspend fun refreshOAuthToken(stored: YouTubeOAuthToken): String {
        var lastFailure = ""
        for (attempt in 0..1) {
            val response = try {
                httpRequest(
                    GOOGLE_OAUTH_TOKEN_URL, "POST",
                    body = form(
                        "grant_type" to "refresh_token",
                        "refresh_token" to stored.refresh,
                        "client_id" to stored.clientId
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A timeout or a dropped socket: keep everything and try once more below.
                null
            }

            if (response == null) {
                lastFailure = "no connection"
            } else if (response.code in 200..299) {
                return saveRefreshed(stored, response)
            } else {
                val failure = OAuthFailure.from(response)
                if (GoogleAuthPolicy.isPermanentGrantFailure(response.code, failure.error, failure.description)) {
                    markNeedsReconnect(stored)
                    throw YouTubeReconnectRequiredException(reconnectMessage(stored))
                }
                lastFailure = failure.description?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                // Rate limits and server errors are worth one more try; anything else is not.
                if (response.code != 429 && response.code < 500) break
            }
            if (attempt == 0) delay(400)
        }
        unreachable = true
        throw YouTubeDataException(
            "Couldn't renew the YouTube connection just now (${lastFailure.ifBlank { "unavailable" }}). " +
                "The saved sign-in is intact, so try again when you have a connection."
        )
    }

    /** Commits a rotated token durably, then hands back the access token it belongs to. */
    private fun saveRefreshed(stored: YouTubeOAuthToken, response: HttpResponse): String {
        val parsed = YouTubeOAuth.parseToken(response.body)
        val now = System.currentTimeMillis()
        val refreshed = stored.copy(
            access = parsed.access,
            refresh = parsed.refresh ?: stored.refresh,
            expiresAt = now + parsed.expiresInSec.coerceAtLeast(60L) * 1_000L,
            lastRefreshedAt = now,
            refreshCount = stored.refreshCount + 1
        )
        saveOAuthToken(refreshed)
        recordVerified(now)
        return refreshed.access
    }

    /**
     * Google refused the grant. The tokens and the account label stay exactly where they are:
     * the Client ID and the channel name are what make reconnecting one tap, and a future Google
     * change (or a fixed Cloud project) can only be retried if the grant was not thrown away.
     */
    private fun markNeedsReconnect(stored: YouTubeOAuthToken) {
        val now = System.currentTimeMillis()
        statusPrefs.edit()
            .putBoolean(KEY_NEEDS_RECONNECT, true)
            .putBoolean(KEY_UNREACHABLE, false)
            .putLong(KEY_FAILED_AT, now)
            .putBoolean(KEY_TESTING_EXPIRY, GoogleAuthPolicy.testingExpirySuspected(stored.issuedAt, now))
            .commit()
    }

    private fun reconnectMessage(stored: YouTubeOAuthToken): String {
        val suspected = GoogleAuthPolicy.testingExpirySuspected(stored.issuedAt, System.currentTimeMillis())
        return if (suspected) {
            "Google expired this YouTube grant. Cloud projects whose OAuth consent screen is still in " +
                "Testing lose grants after 7 days — publish the project to stop it repeating. " +
                "Reconnect now: your Client ID is remembered, so it is one tap."
        } else {
            "Google no longer accepts this YouTube grant. Reconnect to continue — your Client ID is " +
                "remembered, and nothing on this device was deleted."
        }
    }

    private fun readOAuthToken(): YouTubeOAuthToken? {
        val raw = secrets.get("youtube_oauth_token") ?: return null
        return runCatching {
            val json = JSONObject(raw)
            YouTubeOAuthToken(
                access = json.getString("access"),
                refresh = json.getString("refresh"),
                clientId = json.getString("clientId"),
                expiresAt = json.getLong("expiresAt"),
                issuedAt = json.optLong("issuedAt", 0L),
                lastRefreshedAt = json.optLong("lastRefreshedAt", 0L),
                refreshCount = json.optInt("refreshCount", 0)
            )
        }.getOrElse {
            // The ciphertext decrypted but is not a token record. Keep it (it is the listener's
            // data, and a fixed key store could still read it) and ask for a one-tap reconnect
            // rather than appearing connected while doing nothing.
            needsReconnect = true
            null
        }
    }

    /** Durably commits the token record; the write completes before the caller uses the token. */
    private fun saveOAuthToken(token: YouTubeOAuthToken) {
        secrets.put("youtube_oauth_token", JSONObject().apply {
            put("access", token.access)
            put("refresh", token.refresh)
            put("clientId", token.clientId)
            put("expiresAt", token.expiresAt)
            put("issuedAt", token.issuedAt)
            put("lastRefreshedAt", token.lastRefreshedAt)
            put("refreshCount", token.refreshCount)
        }.toString())
    }

    private fun HttpResponse.tokenError(): YouTubeDataException {
        val detail = detail()
        val message = when {
            detail.contains("redirect_uri", ignoreCase = true) ->
                "Google rejected the sign-in redirect. Use a Desktop (not Android or Web) OAuth client ID."
            detail.contains("invalid_client", ignoreCase = true) ->
                "Google rejected this Client ID. Use the Desktop OAuth client ID from your Cloud project."
            code in 400..499 -> detail.ifBlank {
                "YouTube sign-in was rejected (HTTP $code). Check the Client ID and try again."
            }
            else -> detail.ifBlank { "YouTube sign-in is unavailable (HTTP $code). Try again later." }
        }
        return YouTubeDataException(message)
    }

    /**
     * Pulls every accessible YouTube playlist (plus Liked videos and Uploads) and returns
     * them as [YouTubeImportedPlaylist]s with deterministic item lists. Each playlist uses
     * its stable YouTube playlist id; ViewModel layer maps that to a Soundtrail playlist id
     * so repeated imports merge (existing items keep their state, new videos are appended).
     *
     * The YouTube Data API pages through at most [MAX_IMPORT_PLAYLISTS] playlists and
     * [MAX_IMPORT_ITEMS_PER_PLAYLIST] items per list to bound bandwidth and token use.
     */
    suspend fun importPlaylists(): List<YouTubeImportedPlaylist> {
        requireConnected("Connect a Google account to import your YouTube playlists.")

        // Only one method is ever connected; the browser-OAuth token refreshes itself.
        val useOAuth = oauthConnected
        try {
            return importWithToken(if (useOAuth) oauthAccessToken() else accessToken())
        } catch (e: YouTubeAuthExpiredException) {
            // A long import can outlive its access token. Refresh once, never loop.
            if (!useOAuth) throw e
            return importWithToken(oauthAccessToken(forceRefresh = true))
        }
    }

    /** Fetches every playlist with [token], caches the snapshot, and parses it for import. */
    private suspend fun importWithToken(token: String): List<YouTubeImportedPlaylist> {
        val snapshot = fetchAllPlaylists(token)
        cachedLibrary = CachedLibrary(System.currentTimeMillis(), snapshot)
        return snapshot.map { cached ->
            val videos = YouTubeApiParser.playlistItems(cached.body)
            YouTubeImportedPlaylist(
                playlistId = cached.playlistId,
                title = cached.title,
                items = videos
            )
        }
    }

    /**
     * Searches videos in the user's own playlists. The public YouTube Data API does not expose
     * the complete YouTube Music library (saved songs/albums/artists/mixes), so this searches
     * accessible playlist items only.
     */
    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        requireConnected("Connect your YouTube account to search your playlists.")

        // Only one method is ever connected; the browser-OAuth token refreshes itself.
        val useOAuth = oauthConnected
        try {
            return searchWithToken(query, if (useOAuth) oauthAccessToken() else accessToken())
        } catch (e: YouTubeAuthExpiredException) {
            // A token can be revoked before its reported expiry. Refresh once, never loop.
            if (!useOAuth) throw e
            return searchWithToken(query, oauthAccessToken(forceRefresh = true))
        }
    }

    private suspend fun searchWithToken(query: String, token: String): List<TrackResult> {
        val now = System.currentTimeMillis()
        // Both branches produce a CachedLibrary, so the elvis result has one usable type.
        val snapshot = cachedLibrary?.takeIf {
            now >= it.cachedAt && now - it.cachedAt < LIBRARY_CACHE_MS
        } ?: run {
            val fetched = fetchAllPlaylists(token)
            CachedLibrary(System.currentTimeMillis(), fetched).also { cachedLibrary = it }
        }

        return snapshot.playlists.flatMap { playlist ->
            YouTubeApiParser.matchingTracks(playlist.body, query, playlist.title)
        }.distinctBy { it.url }.take(MAX_RESULTS)
    }

    /** Walks every page of playlists (mine=true), fetches items for each, and also pulls Likes + Uploads. */
    private suspend fun fetchAllPlaylists(token: String): List<CachedPlaylist> = coroutineScope {
        val owned = paginatePlaylists(token)
        // Likes & Uploads playlist ids live on the user's own channel.
        val channelBody = apiGet(
            "channels",
            listOf("part" to "contentDetails", "mine" to "true", "maxResults" to "1"),
            token
        )
        val related = channelPlaylistIds(channelBody)

        val allRefs = owned + related.map { (id, title) -> YouTubePlaylistRef(id, title) }
        val semaphore = Semaphore(4)
        val fetched = allRefs.map { playlist ->
            async {
                semaphore.withPermit {
                    val body = fetchAllPlaylistItems(token, playlist.id)
                    CachedPlaylist(playlist.id, playlist.title, body)
                }
            }
        }.awaitAll()
        // Dedupe by playlist id (special lists could theoretically overlap with owned playlists).
        fetched.associateBy { it.playlistId }.values.toList()
    }

    private suspend fun paginatePlaylists(token: String): List<YouTubePlaylistRef> {
        val collected = mutableListOf<YouTubePlaylistRef>()
        var pageToken: String? = null
        var pages = 0
        while (pages < MAX_PLAYLIST_PAGES) {
            val params = arrayOf(
                "part" to "snippet",
                "mine" to "true",
                "maxResults" to PAGE_SIZE,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken!!) else emptyArray())
            )
            val body = apiGet("playlists", params.toList(), token)
            collected += YouTubeApiParser.playlists(body)
            pageToken = JSONObject(body).optString("nextPageToken").takeIf(String::isNotBlank)
            pages++
            if (pageToken == null) break
            if (collected.size >= MAX_IMPORT_PLAYLISTS) break
        }
        return collected.take(MAX_IMPORT_PLAYLISTS)
    }

    private suspend fun fetchAllPlaylistItems(token: String, playlistId: String): String {
        // Pages are concatenated into a synthetic JSON object shaped the same as a single page,
        // so the existing playlistItems/matchingTracks parsers keep working.
        val items = StringBuilder()
        var pageToken: String? = null
        var total = 0
        var pages = 0
        while (pages < MAX_ITEM_PAGES) {
            val params = arrayOf(
                "part" to "snippet,contentDetails",
                "playlistId" to playlistId,
                "maxResults" to PAGE_SIZE,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken!!) else emptyArray())
            )
            val body = apiGet("playlistItems", params.toList(), token)
            val pageItems = JSONObject(body).optJSONArray("items")
            if (pageItems != null) {
                for (i in 0 until pageItems.length()) {
                    if (total >= MAX_IMPORT_ITEMS_PER_PLAYLIST) break
                    if (items.isNotEmpty()) items.append(',')
                    items.append(pageItems.getJSONObject(i).toString())
                    total++
                }
            }
            if (total >= MAX_IMPORT_ITEMS_PER_PLAYLIST) break
            pageToken = JSONObject(body).optString("nextPageToken").takeIf(String::isNotBlank)
            pages++
            if (pageToken == null) break
        }
        return """{"items":[$items]}"""
    }

    private fun channelPlaylistIds(body: String): List<Pair<String, String>> {
        val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0) ?: return emptyList()
        val details = item.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists") ?: return emptyList()
        return buildList {
            details.optString("likes").takeIf(String::isNotBlank)?.let { add(it to "Liked videos") }
            details.optString("uploads").takeIf(String::isNotBlank)?.let { add(it to "Uploads") }
            details.optString("favorites").takeIf(String::isNotBlank)?.let { add(it to "Favorites") }
        }
    }

    private suspend fun accessToken(account: Account? = null): String = withContext(Dispatchers.IO) {
        val selectedAccount = account ?: storedAccount()
            ?: throw YouTubeDataException("Your Google account is no longer connected. Connect again.")
        try {
            GoogleAuthUtil.getToken(appContext, selectedAccount, "oauth2:$YOUTUBE_READONLY_SCOPE")
                .takeIf(String::isNotBlank)
                ?: throw YouTubeDataException("Google services returned an empty YouTube token.")
        } catch (e: UserRecoverableAuthException) {
            throw YouTubeConsentRequiredException()
        } catch (e: GoogleAuthException) {
            throw YouTubeDataException(
                "Google services could not authorize YouTube read-only access. Check the microG/Google account setup."
            )
        } catch (e: IOException) {
            throw YouTubeDataException("Could not reach Google services. Check your connection and try again.")
        }
    }

    private fun storedAccount(): Account? {
        val name = secrets.get("youtube_data_account_name")?.takeIf(String::isNotBlank) ?: return null
        val type = secrets.get("youtube_data_account_type")?.takeIf(String::isNotBlank) ?: GOOGLE_ACCOUNT_TYPE
        return Account(name, type)
    }

    private suspend fun apiGet(
        resource: String,
        parameters: List<Pair<String, String>>,
        token: String
    ): String {
        val address = "$YOUTUBE_API_BASE/$resource?${form(*parameters.toTypedArray())}"
        val response = httpRequest(address, authorization = "Bearer $token")
        if (response.code !in 200..299) {
            if (response.code == 401) {
                throw YouTubeAuthExpiredException(
                    "YouTube authorization expired. Disconnect and connect the account again."
                )
            }
            val detail = response.detail()
            val message = when (response.code) {
                403 -> detail.ifBlank {
                    "YouTube denied access. Enable YouTube Data API v3 in your Google Cloud project and approve read-only access."
                }
                429 -> "YouTube is rate-limiting requests. Try again later."
                else -> detail.ifBlank { "YouTube Data API is unavailable (HTTP ${response.code})." }
            }
            throw YouTubeDataException(message)
        }
        return response.body
    }

    companion object {
        /** Status keys for the plain-text connection record (never a token value). */
        private const val KEY_NEEDS_RECONNECT = "needs_reconnect"
        private const val KEY_UNREACHABLE = "unreachable"
        private const val KEY_VERIFIED_AT = "verified_at"
        private const val KEY_FAILED_AT = "failed_at"
        private const val KEY_TESTING_EXPIRY = "testing_expiry"

        private const val PAGE_SIZE = "50"
        private const val MAX_PLAYLIST_PAGES = 4            // 4 * 50 = 200 playlists
        private const val MAX_ITEM_PAGES = 40               // 40 * 50 = 2000 items/playlist
        private const val MAX_IMPORT_PLAYLISTS = 200
        private const val MAX_IMPORT_ITEMS_PER_PLAYLIST = 2000
        private const val MAX_PLAYLISTS_TO_SEARCH = 50
        private const val MAX_RESULTS = 20
        private const val LIBRARY_CACHE_MS = 120_000L

        /**
         * Google-type accounts registered with the device's account manager (microG's account
         * store when microG is the Google services provider). Requires the GET_ACCOUNTS permission.
         */
        fun deviceGoogleAccounts(context: Context): List<Account> {
            if (context.checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS) !=
                PackageManager.PERMISSION_GRANTED
            ) return emptyList()
            return AccountManager.get(context).getAccountsByType(GOOGLE_ACCOUNT_TYPE).toList()
        }

        fun signInOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder(
            GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestEmail()
            .requestScopes(Scope(YOUTUBE_READONLY_SCOPE))
            .build()
    }
}

internal data class YouTubePlaylistRef(val id: String, val title: String)

/** Small pure parsers kept separate so the API response rules can be unit tested. */
internal object YouTubeApiParser {
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun playlists(body: String): List<YouTubePlaylistRef> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optJSONObject("snippet")?.optString("title").orEmpty()
            if (id.isBlank() || title.isBlank()) null else YouTubePlaylistRef(id, title.take(120))
        }
    }

    /** Parses a playlist-items page into structured video entries (stable id/title/artist). */
    fun playlistItems(body: String): List<YouTubeImportedItem> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val title = snippet.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (title.equals("Deleted video", ignoreCase = true) ||
                title.equals("Private video", ignoreCase = true)
            ) return@mapNotNull null
            val resource = snippet.optJSONObject("resourceId")
            val id = item.optJSONObject("contentDetails")?.optString("videoId")
                ?.takeIf(String::isNotBlank)
                ?: resource?.optString("videoId")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (!videoIdPattern.matches(id)) return@mapNotNull null
            val artist = snippet.optString("videoOwnerChannelTitle").ifBlank {
                snippet.optString("channelTitle")
            }.ifBlank { "Unknown artist" }.take(120)
            YouTubeImportedItem(videoId = id, title = title.take(120), artist = artist)
        }
    }

    fun matchingTracks(body: String, query: String, playlistTitle: String): List<TrackResult> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val title = snippet.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (title.equals("Deleted video", ignoreCase = true) ||
                title.equals("Private video", ignoreCase = true)
            ) return@mapNotNull null

            val resource = snippet.optJSONObject("resourceId")
            val id = item.optJSONObject("contentDetails")?.optString("videoId")
                ?.takeIf(String::isNotBlank)
                ?: resource?.optString("videoId")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (!videoIdPattern.matches(id)) return@mapNotNull null

            val artist = snippet.optString("videoOwnerChannelTitle").ifBlank {
                snippet.optString("channelTitle")
            }.ifBlank { "Unknown artist" }.take(120)
            val searchable = "$title $artist $playlistTitle"
            if (terms.any { !searchable.contains(it, ignoreCase = true) }) return@mapNotNull null

            TrackResult(
                title = title.take(120),
                artist = artist,
                url = "https://www.youtube.com/watch?v=$id",
                provider = MusicProvider.YOUTUBE
            )
        }.distinctBy { it.url }
    }
}

private data class YouTubeOAuthToken(
    val access: String,
    val refresh: String,
    val clientId: String,
    val expiresAt: Long,
    /** When Google first issued this grant; used to spot its 7-day Testing-mode expiry. */
    val issuedAt: Long = 0L,
    val lastRefreshedAt: Long = 0L,
    /** How many times the app renewed this grant by itself, shown on the Connect tab. */
    val refreshCount: Int = 0
)

/** The error fields of a failed token-endpoint response. */
internal data class OAuthFailure(val error: String?, val description: String?) {
    companion object {
        fun from(response: HttpResponse): OAuthFailure = runCatching {
            val json = JSONObject(response.body)
            val error = json.optString("error").takeIf(String::isNotBlank)
            val description = json.optString("error_description").takeIf(String::isNotBlank)
            OAuthFailure(error, description)
        }.getOrElse { OAuthFailure(null, null) }
    }
}

/** Pure browser-OAuth pieces (URL building, redirect checks, token parsing) kept testable. */
internal object YouTubeOAuth {
    private val clientIdPattern = Regex("^[0-9]{5,}-[A-Za-z0-9_-]{5,}\\.apps\\.googleusercontent\\.com$")

    /** A Google Cloud OAuth client ID always ends in `.apps.googleusercontent.com`. */
    fun checkClientId(input: String): String {
        val id = input.trim()
        if (!clientIdPattern.matches(id)) {
            throw YouTubeDataException(
                "Enter the Client ID from your Desktop OAuth client " +
                    "(123…-abc….apps.googleusercontent.com)."
            )
        }
        return id
    }

    /** Installed-app authorization URL: PKCE, offline access, and explicit consent for a refresh token. */
    fun authorizationUrl(clientId: String, state: String, challenge: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to YOUTUBE_OAUTH_REDIRECT_URI,
            "scope" to YOUTUBE_READONLY_SCOPE,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent"
        ).joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$GOOGLE_OAUTH_AUTH_URL?$params"
    }

    /**
     * Validates the browser redirect and returns the authorization code. Accepts Google's
     * documented `scheme:/path` form and the equivalent `scheme://host` form.
     */
    fun authorizationCode(redirect: String, expectedState: String): String {
        val uri = runCatching { java.net.URI(redirect) }.getOrNull()
            ?: throw YouTubeDataException("Unexpected YouTube sign-in response.")
        val targetOk = uri.scheme == "app.soundtrail" &&
            uri.port == -1 &&
            uri.fragment == null &&
            uri.userInfo == null &&
            (uri.rawAuthority == null || uri.rawAuthority == "youtube-auth") &&
            ((uri.host == "youtube-auth" && (uri.path.isNullOrEmpty() || uri.path == "/")) ||
                (uri.host == null && uri.path == "/youtube-auth"))
        if (!targetOk) throw YouTubeDataException("Unexpected YouTube sign-in response.")
        val params = runCatching {
            uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
                val separator = part.indexOf("=")
                if (separator <= 0) null else
                    URLDecoder.decode(part.substring(0, separator), "UTF-8") to
                        URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }.toMap()
        }.getOrNull() ?: throw YouTubeDataException("Unexpected YouTube sign-in response.")
        params["error"]?.let { error ->
            throw YouTubeDataException(
                if (error == "access_denied") "YouTube sign-in was cancelled."
                else "YouTube sign-in failed ($error)."
            )
        }
        if (expectedState.isBlank() || params["state"] != expectedState) {
            throw YouTubeDataException("YouTube sign-in expired or could not be verified. Try again.")
        }
        return params["code"]?.takeIf(String::isNotBlank)
            ?: throw YouTubeDataException("YouTube did not return an authorization code.")
    }

    internal data class ParsedToken(
        val access: String, val refresh: String?, val expiresInSec: Long, val scope: String?
    )

    /** Parses a token-endpoint response; throws a user-facing error when it carries no token. */
    fun parseToken(body: String): ParsedToken {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw YouTubeDataException("YouTube sign-in returned an unreadable response.")
        val access = json.optString("access_token")
        if (access.isBlank()) {
            val detail = json.optString("error_description").ifBlank { json.optString("error") }
            throw YouTubeDataException(
                detail.ifBlank { "YouTube sign-in returned an unreadable response." }
            )
        }
        return ParsedToken(
            access = access,
            refresh = json.optString("refresh_token").ifBlank { null },
            expiresInSec = json.optLong("expires_in", 0L),
            scope = json.optString("scope").ifBlank { null }
        )
    }

    /** Fails when Google reports granted scopes that omit the read-only permission. */
    fun checkScope(scope: String?) {
        if (scope != null && scope.split(Regex("\\s+")).none { it == YOUTUBE_READONLY_SCOPE }) {
            throw YouTubeDataException("Approve the YouTube read-only permission to use playlist search.")
        }
    }
}
