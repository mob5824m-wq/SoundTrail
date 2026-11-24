package tech.egrie.soundtrail.integrations.youtube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal YouTube InnerTube HTTP client.
 *
 * <p>This is the modern replacement for the Kobo suite's TLS gateway: Android 8+
 * negotiates TLS 1.3 with {@code youtubei.googleapis.com} and {@code googlevideo.com}
 * natively, so no local proxy, no bundled CA overrides and no certificate pinning
 * workaround are needed.
 *
 * <p>Only public, unauthenticated endpoints are used. The client never attempts to
 * sign in, solve a bot check or decipher a protected stream signature.
 */
public final class InnerTubeClient {

    public static final String PREFS = "soundtrail_innertube";

    public static final class Client {
        public final String key;
        public final String name;
        public final String version;
        public final String userAgent;
        public final String host;
        public final String numericId;

        Client(String key, String name, String version, String userAgent, String host, String numericId) {
            this.key = key;
            this.name = name;
            this.version = version;
            this.userAgent = userAgent;
            this.host = host;
            this.numericId = numericId;
        }

        public String label() {
            return key;
        }
    }

    public static final String WEB_UA =
            "Mozilla/5.0 (X11; Linux x86_64) rv:131.0 Gecko/20100101 Firefox/131.0";

    /** YouTube Music web client: search and browse. */
    public static final Client WEB_REMIX = new Client(
            "WEB_REMIX",
            "WEB_REMIX",
            "1.20260804.16.00",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36",
            "https://music.youtube.com",
            "67");

    /** Plain web client: fallback for song searches when the music client is thin. */
    public static final Client WEB = new Client(
            "WEB",
            "WEB",
            "2.20260708.00.00",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36",
            "https://www.youtube.com",
            "1");

    /** Native music client: returns clean audio/mp4 and audio/webm tracks. */
    public static final Client ANDROID_MUSIC = new Client(
            "ANDROID_MUSIC",
            "ANDROID_MUSIC",
            "7.12.52",
            "com.google.android.apps.youtube.music/7.12.52 (Linux; U; Android 14) gzip",
            "https://music.youtube.com",
            "21");

    /** iOS client: broad format coverage, used when the native client is refused. */
    public static final Client IOS = new Client(
            "IOS",
            "IOS",
            "21.26.4",
            "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "https://youtubei.googleapis.com",
            "5");

    /** TV client: last resort, sometimes returns progressive MP4. */
    public static final Client TV_SIMPLY = new Client(
            "TVHTML5_SIMPLY",
            "TVHTML5_SIMPLY",
            "1.0",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
                    + "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
            "https://youtubei.googleapis.com",
            "75");

    /** Player attempt order. The native music client is the most reliable source of AAC. */
    public static final Client[] PLAYER_ORDER = {ANDROID_MUSIC, IOS, TV_SIMPLY};

    public static final Client[] ALL_CLIENTS = {WEB_REMIX, ANDROID_MUSIC, IOS, TV_SIMPLY, WEB};

    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final long MIN_YOUTUBE_INTERVAL_MS = 1200L;

    private static final Object PACE_LOCK = new Object();
    private static long nextRequestAtMs = 0L;

    private final SharedPreferences prefs;

    public InnerTubeClient(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Client clientByKey(String key) {
        if (key == null || key.length() == 0) {
            return null;
        }
        for (Client client : ALL_CLIENTS) {
            if (client.key.equalsIgnoreCase(key)) {
                return client;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ HTTP */

    public JSONObject postJson(String url, JSONObject body, Map<String, String> headers)
            throws IOException {
        String raw = new String(request("POST", url, body == null ? null : body.toString(),
                headers, 16000), "UTF-8");
        try {
            return new JSONObject(raw);
        } catch (Exception error) {
            throw new IOException("YouTube returned a non-JSON response", error);
        }
    }

    public String getString(String url, Map<String, String> headers) throws IOException {
        return new String(request("GET", url, null, headers, 14000), "UTF-8");
    }

    public byte[] getBytes(String url, Map<String, String> headers, int maxBytes) throws IOException {
        return request("GET", url, null, headers, 30000, Math.max(1024, maxBytes));
    }

    private byte[] request(String method, String url, String jsonBody,
                           Map<String, String> headers, int timeoutMs) throws IOException {
        return request(method, url, jsonBody, headers, timeoutMs, MAX_RESPONSE_BYTES);
    }

    private byte[] request(String method, String url, String jsonBody,
                           Map<String, String> headers, int timeoutMs, int maxBytes)
            throws IOException {
        if (url == null || url.length() == 0) {
            throw new IOException("Empty request URL");
        }
        paceRequest(url);

        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("User-Agent", WEB_UA);
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) {
                        continue;
                    }
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (jsonBody != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                byte[] payload = jsonBody.getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream output = connection.getOutputStream();
                try {
                    output.write(payload);
                    output.flush();
                } finally {
                    output.close();
                }
            }

            int code = connection.getResponseCode();
            if (code == 429 || code == 503) {
                backoff(url, code);
            }
            input = code >= 200 && code < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] body = readFully(input, maxBytes);
            if (code < 200 || code >= 400) {
                String detail = body.length > 0 ? new String(body, "UTF-8") : "";
                if (detail.length() > 400) {
                    detail = detail.substring(0, 400);
                }
                throw new IOException("HTTP " + code + " from " + hostOf(url)
                        + (detail.length() > 0 ? ": " + detail : ""));
            }
            return body;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readFully(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;
        while ((read = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() >= maxBytes) {
                break;
            }
        }
        return buffer.toByteArray();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }

    private static boolean isYouTubeHost(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("youtube.com/") || lower.contains("youtubei.googleapis.com/")
                || lower.contains("googlevideo.com/");
    }

    /** Serialises YouTube requests so a burst of searches does not trigger 429s. */
    private static void paceRequest(String url) throws IOException {
        if (!isYouTubeHost(url)) {
            return;
        }
        synchronized (PACE_LOCK) {
            long waitMs;
            while ((waitMs = nextRequestAtMs - System.currentTimeMillis()) > 0L) {
                if (waitMs > 10000L) {
                    throw new IOException("YouTube rate limited this connection; retry later");
                }
                try {
                    PACE_LOCK.wait(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while pacing YouTube requests", interrupted);
                }
            }
            nextRequestAtMs = System.currentTimeMillis() + MIN_YOUTUBE_INTERVAL_MS;
        }
    }

    private static void backoff(String url, int statusCode) {
        if (!isYouTubeHost(url)) {
            return;
        }
        long delay = (statusCode == 429 || statusCode == 503) ? 60000L : 0L;
        if (delay == 0L) {
            return;
        }
        synchronized (PACE_LOCK) {
            nextRequestAtMs = Math.max(nextRequestAtMs, System.currentTimeMillis() + delay);
        }
    }

    /* --------------------------------------------------------- client context */

    /** Caches one visitor id per client; sharing a web visitor id raises bot scores. */
    public String visitorData(Client client) {
        String cacheKey = "visitor_" + client.key;
        String cached = prefs.getString(cacheKey, "");
        long cachedAt = prefs.getLong(cacheKey + "_at", 0L);
        if (cached.length() > 8 && System.currentTimeMillis() - cachedAt < 6L * 3600L * 1000L) {
            return cached;
        }
        String endpoint = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
        if ("TVHTML5_SIMPLY".equals(client.name) || "TVHTML5".equals(client.name)) {
            endpoint = "https://www.youtube.com/youtubei/v1/guide?prettyPrint=false";
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("context", buildContext(client, null));
            String raw = new String(request("POST", endpoint, payload.toString(),
                    headers(client, null), 9000), "UTF-8");
            JSONObject response = new JSONObject(raw);
            JSONObject responseContext = response.optJSONObject("responseContext");
            String visitor = responseContext == null
                    ? "" : responseContext.optString("visitorData", "");
            if (visitor.length() > 8) {
                prefs.edit()
                        .putString(cacheKey, visitor)
                        .putLong(cacheKey + "_at", System.currentTimeMillis())
                        .apply();
            }
            return visitor;
        } catch (Exception ignored) {
            return "";
        }
    }

    public JSONObject buildContext(Client client, String visitorData) {
        JSONObject context = new JSONObject();
        try {
            JSONObject clientObject = new JSONObject();
            clientObject.put("clientName", client.name);
            clientObject.put("clientVersion", client.version);
            clientObject.put("userAgent", client.userAgent);
            clientObject.put("hl", "en");
            clientObject.put("gl", "US");
            clientObject.put("utcOffsetMinutes", 0);
            if ("ANDROID_MUSIC".equals(client.key)) {
                clientObject.put("androidSdkVersion", 34);
                clientObject.put("osName", "Android");
                clientObject.put("osVersion", "14");
                clientObject.put("platform", "MOBILE");
            } else if ("IOS".equals(client.key)) {
                clientObject.put("deviceMake", "Apple");
                clientObject.put("deviceModel", "iPhone16,2");
                clientObject.put("osName", "iPhone");
                clientObject.put("osVersion", "18.3.2.22D82");
                clientObject.put("platform", "MOBILE");
            } else if ("WEB_REMIX".equals(client.key)) {
                clientObject.put("platform", "DESKTOP");
            }
            if (visitorData != null && visitorData.length() > 0) {
                clientObject.put("visitorData", visitorData);
            }
            context.put("client", clientObject);

            JSONObject request = new JSONObject();
            request.put("useSsl", true);
            context.put("request", request);

            JSONObject user = new JSONObject();
            user.put("lockedSafetyMode", false);
            context.put("user", user);
        } catch (Exception ignored) {
        }
        return context;
    }

    public Map<String, String> headers(Client client, String visitorData) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", client.userAgent);
        headers.put("X-YouTube-Client-Name", client.numericId);
        headers.put("X-YouTube-Client-Version", client.version);
        if (client.host.contains("youtube.com")) {
            headers.put("Origin", client.host);
            headers.put("Referer", client.host + "/");
        }
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("X-Goog-Api-Format-Version", "2");
        if (visitorData != null && visitorData.length() > 0) {
            headers.put("X-Goog-Visitor-Id", visitorData);
        }
        return headers;
    }

    /** Calls {@code /youtubei/v1/player} with the given client. */
    public JSONObject player(Client client, String videoId) throws IOException {
        if (videoId == null || videoId.length() < 8) {
            throw new IOException("Invalid video id");
        }
        String visitor = visitorData(client);
        JSONObject payload = new JSONObject();
        try {
            payload.put("context", buildContext(client, visitor));
            payload.put("videoId", videoId);
            payload.put("contentCheckOk", true);
            payload.put("racyCheckOk", true);
        } catch (Exception error) {
            throw new IOException("Could not build player request", error);
        }
        return postJson("https://" + hostFor(client) + "/youtubei/v1/player?prettyPrint=false",
                payload, headers(client, visitor));
    }

    private static String hostFor(Client client) {
        if (client.host.startsWith("https://")) {
            return client.host.substring(8);
        }
        return client.host;
    }

    public JSONObject search(Client client, String query, String params) throws IOException {
        return search(client, query, params, 14000);
    }

    public JSONObject search(Client client, String query, String params, int timeoutMs)
            throws IOException {
        String visitor = visitorData(client);
        JSONObject payload = new JSONObject();
        try {
            payload.put("context", buildContext(client, visitor));
            payload.put("query", query);
            if (params != null && params.length() > 0) {
                payload.put("params", params);
            }
        } catch (Exception error) {
            throw new IOException("Could not build search request", error);
        }
        return postJson("https://" + hostFor(client) + "/youtubei/v1/search?prettyPrint=false",
                payload, headers(client, visitor));
    }

    public JSONObject browse(Client client, String browseId) throws IOException {
        return browse(client, browseId, 16000);
    }

    public JSONObject browse(Client client, String browseId, int timeoutMs) throws IOException {
        String visitor = visitorData(client);
        JSONObject payload = new JSONObject();
        try {
            payload.put("context", buildContext(client, visitor));
            payload.put("browseId", browseId);
        } catch (Exception error) {
            throw new IOException("Could not build browse request", error);
        }
        return postJson("https://" + hostFor(client) + "/youtubei/v1/browse?prettyPrint=false",
                payload, headers(client, visitor));
    }
}
