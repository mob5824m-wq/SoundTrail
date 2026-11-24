package tech.egrie.soundtrail.integrations.youtube;

import java.util.LinkedHashMap;
import java.util.Map;

/** A directly playable audio stream resolved from YouTube Music. */
public final class YtStream {
    public String url = "";
    public String mimeType = "";
    public int bitrate = 0;
    public boolean hls = false;
    public String sourceLabel = "";

    public String title = "";
    public String author = "";
    public String album = "";
    public String artworkUrl = "";
    public long durationSec = 0L;

    public final Map<String, String> headers = new LinkedHashMap<>();

    public boolean hasStream() {
        return url != null && !url.isEmpty();
    }
}
