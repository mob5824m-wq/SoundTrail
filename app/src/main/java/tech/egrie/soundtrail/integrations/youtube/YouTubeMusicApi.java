package tech.egrie.soundtrail.integrations.youtube;


import android.content.SharedPreferences;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public YouTube Music API: search, browse and audio-only stream resolution.
 *
 * <p>Ported from the Kobo Arc 7 Freedom suite's {@code MorpheEngine} music paths.
 * Everything Android-4-specific was removed: the local TLS gateway, the DASH
 * range proxy, the MP4 remuxer, the HLS relay and the legacy AAC/TS probes.
 * Modern Android plays the resolved {@code audio/mp4} / {@code audio/webm} URL
 * directly through {@link android.media.MediaPlayer}.
 *
 * <p>No account, cookie or sign-in flow is used. Provider-side restrictions are
 * reported to the user, never bypassed.
 */
public final class YouTubeMusicApi {

    public static final String MODE_SONGS = "songs";
    public static final String MODE_ARTISTS = "artists";
    public static final String MODE_ALBUMS = "albums";
    public static final String MODE_PLAYLISTS = "playlists";
    public static final String MODE_COMMUNITY = "community_playlists";

    private static final String KEY_PREFERRED_CLIENT = "preferred_client";

    private final InnerTubeClient client;
    private final int targetBitrate;
    /** Example-owned setting: which InnerTube client to try first for /player. */
    private final SharedPreferences prefs;

    public YouTubeMusicApi(Context context) {
        this(context, 128000);
    }

    public YouTubeMusicApi(Context context, int targetBitrate) {
        this.client = new InnerTubeClient(context);
        this.targetBitrate = targetBitrate > 0 ? targetBitrate : 128000;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences("soundtrail_youtube_settings", Context.MODE_PRIVATE);
    }

    /* ================================================================== search */

    /** "auto" or an {@link InnerTubeClient} key; see {@link InnerTubeClient#clientByKey(String)}. */
    public String preferredClient() {
        String key = prefs.getString(KEY_PREFERRED_CLIENT, "auto");
        return key == null ? "auto" : key;
    }

    public void setPreferredClient(String clientKey) {
        prefs.edit().putString(KEY_PREFERRED_CLIENT, clientKey == null ? "auto" : clientKey).apply();
    }
    public List<YtTrack> search(String query, String mode) throws IOException {
        if (query == null || query.trim().length() == 0) {
            return new ArrayList<>();
        }
        final String clean = query.trim();
        final String normalized = mode == null ? MODE_SONGS : mode.toLowerCase(Locale.US);
        final boolean entitiesOnly = MODE_ARTISTS.equals(normalized)
                || MODE_ALBUMS.equals(normalized)
                || normalized.contains("playlist");

        try {
            JSONObject raw = client.search(InnerTubeClient.WEB_REMIX, clean, paramsFor(normalized));
            List<YtTrack> items = filterForMode(parseItems(raw, true), normalized);
            if (!items.isEmpty() || entitiesOnly) {
                return items;
            }
        } catch (IOException error) {
            // Entity searches must never silently turn into unrelated song results.
            if (entitiesOnly) {
                throw error;
            }
        }

        // Fallback: plain YouTube search. Mark everything as a music track and let
        // the resolver pick the audio-only stream, so the result is still playable.
        JSONObject raw = client.search(InnerTubeClient.WEB, clean + " official audio", null);
        List<YtTrack> items = parseItems(raw, false);
        for (YtTrack item : items) {
            }
        return filterForMode(items, MODE_SONGS);
    }

    private static String paramsFor(String mode) {
        if (MODE_SONGS.equals(mode)) {
            return "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
        }
        if (MODE_ARTISTS.equals(mode)) {
            return "EgWKAQIgAWoMEA4QChADEAQQCRAF";
        }
        if (MODE_ALBUMS.equals(mode)) {
            return "EgWKAQIYAWoMEA4QChADEAQQCRAF";
        }
        if (MODE_PLAYLISTS.equals(mode)) {
            return "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D";
        }
        if (MODE_COMMUNITY.equals(mode)) {
            return "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D";
        }
        return "";
    }

    private static List<YtTrack> filterForMode(List<YtTrack> items, String mode) {
        List<YtTrack> out = new ArrayList<>();
        for (YtTrack item : items) {
            if (item == null) {
                continue;
            }
            boolean matches;
            if (MODE_ARTISTS.equals(mode)) {
                matches = item.isArtist();
            } else if (MODE_ALBUMS.equals(mode)) {
                matches = item.isAlbum();
            } else if (mode != null && mode.contains("playlist")) {
                matches = item.isPlaylist();
            } else {
                matches = !item.isBrowsable() && item.id.length() > 0;
            }
            if (matches) {
                out.add(item);
            }
        }
        return out;
    }

    /* ================================================================== browse */

    public List<YtTrack> browseCollection(YtTrack item) throws IOException {
        if (item == null || !item.isCollection()) {
            throw new IOException("Choose an album or playlist");
        }
        String browse = item.id;
        if (!browse.matches("[A-Za-z0-9_-]{5,200}")) {
            throw new IOException("Invalid album or playlist id");
        }
        JSONObject raw = client.browse(InnerTubeClient.WEB_REMIX, browse);
        JSONObject shelf = findMusicShelf(raw, "musicPlaylistShelfRenderer", 0);
        if (shelf == null) {
            shelf = findMusicShelf(raw, "musicShelfRenderer", 0);
        }
        List<YtTrack> tracks = new ArrayList<>();
        if (shelf != null) {
            for (YtTrack track : parseItems(shelf, true)) {
                if (!track.isBrowsable() && track.id.length() > 0) {
                    tracks.add(track);
                }
            }
        }
        if (tracks.isEmpty()) {
            throw new IOException("No public tracks returned. The playlist may be private, "
                    + "empty, require sign-in or use an unsupported layout.");
        }
        return tracks;
    }

    public List<YtTrack> browseArtist(YtTrack artist) throws IOException {
        if (artist == null || !artist.isArtist()) {
            throw new IOException("Invalid music artist identity");
        }
        JSONObject raw = client.browse(InnerTubeClient.WEB_REMIX, artist.id);
        return parseArtistPage(raw);
    }

    private static List<YtTrack> parseArtistPage(JSONObject page) throws IOException {
        JSONObject contents = page.optJSONObject("contents");
        JSONObject primary = contents == null
                ? null : contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (primary == null && contents != null) {
            primary = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        }
        JSONArray tabs = primary == null ? null : primary.optJSONArray("tabs");
        JSONObject selected = null;
        if (tabs != null) {
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                JSONObject renderer = tab == null ? null : tab.optJSONObject("tabRenderer");
                if (renderer != null && (selected == null || renderer.optBoolean("selected", false))) {
                    selected = renderer;
                    if (renderer.optBoolean("selected", false)) {
                        break;
                    }
                }
            }
        }
        JSONObject sections = selected == null
                ? null : findMusicShelf(selected.optJSONObject("content"), "sectionListRenderer", 0);
        JSONArray rows = sections == null ? null : sections.optJSONArray("contents");
        LinkedHashMap<String, YtTrack> results = new LinkedHashMap<>();
        if (rows != null) {
            for (int i = 0; i < rows.length() && i < 100 && results.size() < 60; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                JSONObject shelf = row.optJSONObject("musicShelfRenderer");
                boolean directSongs = shelf != null;
                if (shelf == null) {
                    shelf = row.optJSONObject("musicCarouselShelfRenderer");
                }
                if (shelf == null) {
                    continue;
                }
                String title = readRunsText(shelf.optJSONObject("title"));
                JSONObject header = shelf.optJSONObject("header");
                JSONObject basic = header == null
                        ? null : header.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
                if (title.length() == 0 && basic != null) {
                    title = readRunsText(basic.optJSONObject("title"));
                }
                title = title.toLowerCase(Locale.US).trim();
                boolean songs = "songs".equals(title) || "top songs".equals(title)
                        || "popular songs".equals(title) || (directSongs && title.length() == 0);
                boolean releases = "albums".equals(title) || "singles".equals(title)
                        || "singles & eps".equals(title) || "singles and eps".equals(title)
                        || "eps".equals(title);
                if (!songs && !releases) {
                    continue;
                }
                JSONArray entries = shelf.optJSONArray("contents");
                if (entries == null) {
                    continue;
                }
                LinkedHashMap<String, YtTrack> parsed = new LinkedHashMap<>();
                walkRenderers(entries, parsed, true, 0);
                for (YtTrack item : parsed.values()) {
                    if (songs && !item.isBrowsable() && item.id.length() > 0) {
                        results.put(item.identity(), item);
                    } else if (releases
                            && item.isAlbum()
                            && item.isCollection()) {
                        results.put(item.identity(), item);
                    }
                    if (results.size() >= 60) {
                        break;
                    }
                }
            }
        }
        if (results.isEmpty()) {
            throw new IOException("No public artist songs or albums returned. "
                    + "The page may require sign-in or use an unsupported layout.");
        }
        return new ArrayList<>(results.values());
    }

    /* ================================================================ resolve */

    public YtStream resolve(String videoId) throws IOException {
        if (videoId == null || videoId.length() < 8) {
            throw new IOException("Missing video id");
        }
        List<InnerTubeClient.Client> order = new ArrayList<>();
        InnerTubeClient.Client preferred =
                InnerTubeClient.clientByKey(preferredClient());
        if (preferred != null
                && preferred != InnerTubeClient.WEB_REMIX
                && preferred != InnerTubeClient.WEB
                && !order.contains(preferred)) {
            order.add(preferred);
        }
        for (InnerTubeClient.Client candidate : InnerTubeClient.PLAYER_ORDER) {
            if (!order.contains(candidate)) {
                order.add(candidate);
            }
        }

        StringBuilder failures = new StringBuilder();
        IOException last = null;
        for (InnerTubeClient.Client candidate : order) {
            try {
                YtStream track = resolveWith(candidate, videoId);
                if (track != null && track.hasStream()) {
                    return track;
                }
                failures.append(candidate.key).append(":no-stream ");
            } catch (IOException error) {
                last = error;
                failures.append(candidate.key).append(':')
                        .append(shortMessage(error)).append(' ');
            }
        }
        String message = "No playable audio stream for " + videoId
                + " (" + failures.toString().trim() + "). If YouTube asks for sign-in, "
                + "this app cannot bypass it.";
        throw last == null ? new IOException(message) : new IOException(message, last);
    }

    private YtStream resolveWith(InnerTubeClient.Client candidate, String videoId)
            throws IOException {
        return parsePlayer(client.player(candidate, videoId), videoId, candidate);
    }

    private YtStream parsePlayer(JSONObject response, String expectedVideoId,
                                      InnerTubeClient.Client source) throws IOException {
        JSONObject playability = response.optJSONObject("playabilityStatus");
        String status = playability == null ? "OK" : playability.optString("status", "OK");
        if (!"OK".equalsIgnoreCase(status)) {
            String reason = playability == null ? "" : playability.optString("reason", "");
            throw new IOException("Playback refused (" + status + "): " + reason);
        }
        JSONObject details = response.optJSONObject("videoDetails");
        JSONObject streaming = response.optJSONObject("streamingData");
        if (details == null || streaming == null) {
            return null;
        }
        String returnedId = details.optString("videoId", "");
        // Guard against YouTube's placeholder "update app" video substitution.
        if (expectedVideoId.length() > 0 && !expectedVideoId.equals(returnedId)) {
            return null;
        }

        YtStream out = new YtStream();
        out.title = details.optString("title", "Track");
        out.author = details.optString("author", "");
        try {
            out.durationSec = Long.parseLong(details.optString("lengthSeconds", "0"));
        } catch (Exception ignored) {
        }
        out.artworkUrl = bestThumb(details.optJSONObject("thumbnail"), returnedId);
        out.sourceLabel = source.key;

        List<StreamCandidate> aac = new ArrayList<>();
        List<StreamCandidate> opus = new ArrayList<>();
        List<StreamCandidate> muxed = new ArrayList<>();

        JSONArray adaptive = streaming.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject format = adaptive.optJSONObject(i);
                if (format == null) {
                    continue;
                }
                String url = directUrl(format);
                if (url.length() == 0) {
                    continue;
                }
                String mime = format.optString("mimeType", "").toLowerCase(Locale.US);
                int bitrate = format.optInt("averageBitrate", format.optInt("bitrate", 0));
                StreamCandidate candidate = new StreamCandidate();
                candidate.url = url;
                candidate.itag = format.optInt("itag", 0);
                candidate.bitrate = bitrate > 0 ? bitrate : defaultBitrate(candidate.itag);
                candidate.mime = mime;
                candidate.hls = false;
                candidate.muxed = false;
                if (mime.startsWith("audio/mp4") && (mime.contains("mp4a") || mime.contains("aac"))) {
                    aac.add(candidate);
                } else if (mime.startsWith("audio/webm") && mime.contains("opus")) {
                    opus.add(candidate);
                }
            }
        }

        JSONArray formats = streaming.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.optJSONObject(i);
                if (format == null) {
                    continue;
                }
                String url = directUrl(format);
                if (url.length() == 0) {
                    continue;
                }
                String mime = format.optString("mimeType", "").toLowerCase(Locale.US);
                if (!mime.startsWith("video/mp4") || !mime.contains("mp4a")) {
                    continue;
                }
                StreamCandidate candidate = new StreamCandidate();
                candidate.url = url;
                candidate.itag = format.optInt("itag", 18);
                candidate.bitrate = format.optInt("bitrate", 500000);
                candidate.mime = mime;
                candidate.hls = false;
                candidate.muxed = true;
                muxed.add(candidate);
            }
        }

        int target = targetBitrate;
        StreamCandidate chosen = pickBest(aac, target);
        if (chosen == null) {
            chosen = pickBest(opus, target);
        }
        if (chosen == null && !muxed.isEmpty()) {
            chosen = muxed.get(0);
        }
        if (chosen == null) {
            String hls = streaming.optString("hlsManifestUrl", "");
            if (hls.length() > 0) {
                chosen = new StreamCandidate();
                chosen.url = hls;
                chosen.hls = true;
                chosen.mime = "application/x-mpegURL";
            }
        }
        if (chosen == null) {
            return null;
        }

        out.url = chosen.url;
        out.bitrate = chosen.bitrate;
        out.hls = chosen.hls;
        out.mimeType = chosen.hls ? "application/x-mpegURL"
                : chosen.muxed ? "video/mp4"
                : chosen.mime.startsWith("audio/webm") ? "audio/webm" : "audio/mp4";
        out.headers.put("User-Agent", source.userAgent);
        out.headers.put("Accept", "*/*");
        return out;
    }

    private static int defaultBitrate(int itag) {
        if (itag == 141) {
            return 256000;
        }
        if (itag == 140) {
            return 128000;
        }
        if (itag == 139) {
            return 48000;
        }
        if (itag == 251 || itag == 250) {
            return 160000;
        }
        return 128000;
    }

    private static StreamCandidate pickBest(List<StreamCandidate> candidates, int targetBitrate) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        StreamCandidate bestUnder = null;
        for (StreamCandidate candidate : candidates) {
            if (candidate.bitrate <= targetBitrate
                    && (bestUnder == null || candidate.bitrate > bestUnder.bitrate)) {
                bestUnder = candidate;
            }
        }
        if (bestUnder != null) {
            return bestUnder;
        }
        StreamCandidate lowest = candidates.get(0);
        for (StreamCandidate candidate : candidates) {
            if (candidate.bitrate < lowest.bitrate) {
                lowest = candidate;
            }
        }
        return lowest;
    }

    /** Extracts a directly usable URL, resolving clear signatureCipher parameters. */
    static String directUrl(JSONObject format) {
        if (format == null) {
            return "";
        }
        String direct = format.optString("url", "");
        if (direct.length() > 0) {
            return direct;
        }
        String cipher = format.optString("signatureCipher", "");
        if (cipher.length() == 0) {
            cipher = format.optString("cipher", "");
        }
        if (cipher.length() == 0) {
            return "";
        }
        Map<String, String> params = parseQuery(cipher);
        String url = params.get("url");
        String signature = params.get("sig");
        if (signature == null || signature.length() == 0) {
            signature = params.get("signature");
        }
        if (url == null || url.length() == 0 || signature == null || signature.length() == 0) {
            return "";
        }
        String key = params.get("sp");
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,32}")) {
            key = "signature";
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + key + "=" + encode(signature);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) {
            return out;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // Keep the raw pair if it is not valid percent-encoding.
                out.put(key, value);
            }
        }
        return out;
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    /* ================================================================ parsing */

    public static List<YtTrack> parseItems(JSONObject root, boolean musicMode) {
        LinkedHashMap<String, YtTrack> dedup = new LinkedHashMap<>();
        walkRenderers(root, dedup, musicMode, 0);
        return new ArrayList<>(dedup.values());
    }

    private static void walkRenderers(Object node, LinkedHashMap<String, YtTrack> out,
                                      boolean musicMode, int depth) {
        if (node == null || depth > 28 || out.size() >= 60) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                walkRenderers(array.opt(i), out, musicMode, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject object = (JSONObject) node;

        // Skip ad slots entirely.
        if (object.has("adSlotRenderer") || object.has("promotedSparklesWebRenderer")
                || object.has("searchPyvRenderer") || object.has("displayAdRenderer")) {
            return;
        }

        String[] videoKeys = {"videoRenderer", "compactVideoRenderer",
                "gridVideoRenderer", "playlistVideoRenderer"};
        for (String key : videoKeys) {
            JSONObject renderer = object.optJSONObject(key);
            if (renderer != null) {
                YtTrack item = parseVideoRenderer(renderer, musicMode);
                if (item != null && item.id.length() >= 8 && !out.containsKey(item.id)) {
                    out.put(item.id, item);
                }
            }
        }

        JSONObject listItem = object.optJSONObject("musicResponsiveListItemRenderer");
        if (listItem != null) {
            YtTrack collection = musicMode ? parseMusicEntity(listItem) : null;
            if (collection != null) {
                String key = collection.id;
                out.put(key, collection);
                return;
            }
            YtTrack item = parseMusicListItem(listItem);
            if (item != null && item.id.length() >= 8 && !out.containsKey(item.id)) {
                out.put(item.id, item);
            }
        }

        JSONObject twoRow = object.optJSONObject("musicTwoRowItemRenderer");
        if (twoRow != null) {
            YtTrack collection = musicMode ? parseMusicEntity(twoRow) : null;
            if (collection != null) {
                String key = collection.id;
                out.put(key, collection);
                return;
            }
            YtTrack item = parseMusicTwoRow(twoRow);
            if (item != null && item.id.length() >= 8 && !out.containsKey(item.id)) {
                out.put(item.id, item);
            }
        }

        JSONArray names = object.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                walkRenderers(object.opt(key), out, musicMode, depth + 1);
            }
        }
    }

    private static YtTrack parseVideoRenderer(JSONObject renderer, boolean musicMode) {
        String videoId = renderer.optString("videoId", "");
        if (videoId.length() < 8) {
            return null;
        }
        YtTrack item = new YtTrack();
        item.id = videoId;
        item.title = readRunsText(renderer.optJSONObject("title"));
        if (item.title.length() == 0) {
            return null;
        }
        item.author = readRunsText(renderer.optJSONObject("ownerText"));
        if (item.author.length() == 0) {
            item.author = readRunsText(renderer.optJSONObject("longBylineText"));
        }
        if (item.author.length() == 0) {
            item.author = readRunsText(renderer.optJSONObject("shortBylineText"));
        }
        item.durationText = readRunsText(renderer.optJSONObject("lengthText"));
        item.durationSec = parseDurationSec(item.durationText);
        item.thumbnailUrl = bestThumb(renderer.optJSONObject("thumbnail"), videoId);
        if (item.durationText.length() == 0) {
            item.isLive = true;
            item.durationText = "LIVE";
        }
        return item;
    }

    private static YtTrack parseMusicEntity(JSONObject renderer) {
        JSONObject title = renderer.optJSONObject("title");
        JSONArray flex = renderer.optJSONArray("flexColumns");
        if (title == null && flex != null && flex.length() > 0 && flex.optJSONObject(0) != null) {
            JSONObject column = flex.optJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            if (column != null) {
                title = column.optJSONObject("text");
            }
        }
        JSONObject navigation = renderer.optJSONObject("navigationEndpoint");
        if (navigation == null && title != null && title.optJSONArray("runs") != null) {
            JSONArray runs = title.optJSONArray("runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                JSONObject endpoint = run == null ? null : run.optJSONObject("navigationEndpoint");
                if (endpoint != null && endpoint.optJSONObject("browseEndpoint") != null) {
                    navigation = endpoint;
                    break;
                }
            }
        }
        if (navigation == null) {
            return null;
        }
        JSONObject browse = navigation.optJSONObject("browseEndpoint");
        JSONObject watch = navigation.optJSONObject("watchPlaylistEndpoint");
        String browseId = browse == null ? "" : browse.optString("browseId", "");
        String playlistId = watch == null ? "" : watch.optString("playlistId", "");
        String pageType = "";
        if (browse != null) {
            JSONObject supported = browse.optJSONObject("browseEndpointContextSupportedConfigs");
            JSONObject config = supported == null
                    ? null : supported.optJSONObject("browseEndpointContextMusicConfig");
            if (config != null) {
                pageType = config.optString("pageType", "");
            }
        }
        String type = browseId.matches("UC[A-Za-z0-9_-]{22}")
                && (pageType.length() == 0 || "MUSIC_PAGE_TYPE_ARTIST".equals(pageType))
                ? YtTrack.KIND_ARTIST
                : browseId.startsWith("MPRE")
                ? YtTrack.KIND_ALBUM
                : (browseId.startsWith("VL") || playlistId.length() > 0)
                ? YtTrack.KIND_PLAYLIST : "";
        if (type.length() == 0) {
            return null;
        }
        YtTrack item = new YtTrack();
        // Keep the browse form ("VL…") as the id; the bare playlist id is derivable.
        item.id = browseId.length() > 0 ? browseId
                : (playlistId.startsWith("VL") ? playlistId : "VL" + playlistId);
        item.kind = type;
        item.title = readRunsText(title);
        item.author = readRunsText(renderer.optJSONObject("subtitle"));
        if (item.author.length() == 0 && flex != null && flex.length() > 1
                && flex.optJSONObject(1) != null) {
            JSONObject column = flex.optJSONObject(1)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            if (column != null) {
                item.author = readRunsText(column.optJSONObject("text"));
            }
        }
        JSONObject thumbnail = renderer.optJSONObject("thumbnailRenderer");
        if (thumbnail == null) {
            thumbnail = renderer.optJSONObject("thumbnail");
        }
        if (thumbnail != null && thumbnail.optJSONObject("musicThumbnailRenderer") != null) {
            thumbnail = thumbnail.optJSONObject("musicThumbnailRenderer")
                    .optJSONObject("thumbnail");
        }
        item.thumbnailUrl = bestThumb(thumbnail, "");
        return item.title.length() > 0 ? item : null;
    }

    private static YtTrack parseMusicListItem(JSONObject renderer) {
        String videoId = findNestedVideoId(renderer);
        if (videoId == null || videoId.length() < 8) {
            return null;
        }
        YtTrack item = new YtTrack();
        item.id = videoId;
        JSONArray columns = renderer.optJSONArray("flexColumns");
        if (columns != null && columns.length() > 0) {
            JSONObject first = columns.optJSONObject(0);
            if (first != null) {
                JSONObject text = first.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                if (text != null) {
                    item.title = readRunsText(text.optJSONObject("text"));
                }
            }
            if (columns.length() > 1) {
                JSONObject second = columns.optJSONObject(1);
                if (second != null) {
                    JSONObject text = second
                            .optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                    if (text != null) {
                        String meta = readRunsText(text.optJSONObject("text"));
                        String[] parts = meta.split("•");
                        item.author = parts.length > 0 ? parts[0].trim() : meta;
                        if (parts.length > 1) {
                            item.album = parts[1].trim();
                        }
                        if (parts.length > 0) {
                            String last = parts[parts.length - 1].trim();
                            if (last.matches("^\\d+:\\d{2}(:\\d{2})?$")) {
                                item.durationText = last;
                                item.durationSec = parseDurationSec(last);
                            }
                        }
                    }
                }
            }
        }
        if (item.title.length() == 0) {
            return null;
        }
        JSONArray fixedColumns = renderer.optJSONArray("fixedColumns");
        JSONObject fixed = fixedColumns != null && fixedColumns.length() > 0
                ? fixedColumns.optJSONObject(0) : null;
        if (fixed != null && item.durationText.length() == 0) {
            JSONObject column = fixed.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            if (column != null) {
                item.durationText = readRunsText(column.optJSONObject("text"));
                item.durationSec = parseDurationSec(item.durationText);
            }
        }
        JSONObject thumbnail = renderer.optJSONObject("thumbnail");
        if (thumbnail != null && thumbnail.optJSONObject("musicThumbnailRenderer") != null) {
            item.thumbnailUrl = bestThumb(thumbnail.optJSONObject("musicThumbnailRenderer")
                    .optJSONObject("thumbnail"), videoId);
        } else {
            item.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return item;
    }

    private static YtTrack parseMusicTwoRow(JSONObject renderer) {
        String videoId = findNestedVideoId(renderer);
        if (videoId == null || videoId.length() < 8) {
            return null;
        }
        YtTrack item = new YtTrack();
        item.id = videoId;
        item.title = readRunsText(renderer.optJSONObject("title"));
        item.author = readRunsText(renderer.optJSONObject("subtitle"));
        if (item.title.length() == 0) {
            return null;
        }
        item.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return item;
    }

    private static String findNestedVideoId(JSONObject object) {
        if (object == null) {
            return null;
        }
        JSONObject playlistItem = object.optJSONObject("playlistItemData");
        if (playlistItem != null && playlistItem.optString("videoId", "").length() >= 8) {
            return playlistItem.optString("videoId");
        }
        JSONObject navigation = object.optJSONObject("navigationEndpoint");
        if (navigation != null && navigation.optJSONObject("watchEndpoint") != null) {
            String videoId = navigation.optJSONObject("watchEndpoint").optString("videoId", "");
            if (videoId.length() >= 8) {
                return videoId;
            }
        }
        Matcher matcher = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"")
                .matcher(object.toString());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static JSONObject findMusicShelf(Object node, String renderer, int depth) {
        if (node == null || depth > 16) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject shelf = object.optJSONObject(renderer);
            if (shelf != null) {
                return shelf;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    if ("musicCarouselShelfRenderer".equals(key)
                            || "header".equals(key) || "menu".equals(key)) {
                        continue;
                    }
                    shelf = findMusicShelf(object.opt(key), renderer, depth + 1);
                    if (shelf != null) {
                        return shelf;
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject shelf = findMusicShelf(array.opt(i), renderer, depth + 1);
                if (shelf != null) {
                    return shelf;
                }
            }
        }
        return null;
    }

        private static String readRunsText(JSONObject textObject) {
        if (textObject == null) {
            return "";
        }
        if (textObject.has("simpleText")) {
            return textObject.optString("simpleText", "");
        }
        JSONArray runs = textObject.optJSONArray("runs");
        if (runs == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                builder.append(run.optString("text", ""));
            }
        }
        return builder.toString().trim();
    }

    private static String bestThumb(JSONObject thumbObject, String fallbackVideoId) {
        if (thumbObject != null) {
            JSONArray thumbnails = thumbObject.optJSONArray("thumbnails");
            if (thumbnails != null && thumbnails.length() > 0) {
                JSONObject best = thumbnails.optJSONObject(thumbnails.length() - 1);
                if (best != null) {
                    String url = best.optString("url", "");
                    if (url.startsWith("//")) {
                        url = "https:" + url;
                    }
                    if (url.startsWith("http")) {
                        return url;
                    }
                }
            }
        }
        return fallbackVideoId.length() > 0
                ? "https://i.ytimg.com/vi/" + fallbackVideoId + "/hqdefault.jpg" : "";
    }

    public static long parseDurationSec(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        String[] parts = text.trim().split(":");
        try {
            if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600L
                        + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2]);
            }
            if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1]);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String shortMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null) {
            return error == null ? "" : error.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        if (newline > 0) {
            message = message.substring(0, newline);
        }
        return message.length() > 90 ? message.substring(0, 90) : message;
    }

    /* --------------------------------------------------------------- helpers */

    private static final class StreamCandidate {
        String url = "";
        String mime = "";
        int bitrate;
        int itag;
        boolean hls;
        boolean muxed;
    }

    /** Sorts tracks by title; used by offline and playlist views. */
    public static void sortAlphabetically(List<YtTrack> items) {
        if (items == null || items.size() < 2) {
            return;
        }
        Collections.sort(items, new Comparator<YtTrack>() {
            @Override
            public int compare(YtTrack left, YtTrack right) {
                String a = left.author == null ? "" : left.author;
                String b = right.author == null ? "" : right.author;
                int order = a.compareToIgnoreCase(b);
                if (order == 0) {
                    order = left.title.compareToIgnoreCase(right.title);
                }
                return order;
            }
        });
    }
}
