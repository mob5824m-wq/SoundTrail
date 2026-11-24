package tech.egrie.soundtrail.integrations.youtube;

/** A track or browsable item returned from YouTube Music search/browse. */
public final class YtTrack {
    public static final String KIND_TRACK = "track";
    public static final String KIND_ARTIST = "artist";
    public static final String KIND_ALBUM = "album";
    public static final String KIND_PLAYLIST = "playlist";

    public String id = "";
    public String kind = KIND_TRACK;
    public String title = "";
    public String author = "";
    public String album = "";
    public String durationText = "";
    public long durationSec = 0L;
    public String thumbnailUrl = "";
    public boolean isLive = false;

    public boolean isArtist() { return KIND_ARTIST.equals(kind) && !id.isEmpty(); }
    public boolean isAlbum() { return KIND_ALBUM.equals(kind) && !id.isEmpty(); }
    public boolean isPlaylist() { return KIND_PLAYLIST.equals(kind) && !id.isEmpty(); }
    public boolean isCollection() { return isAlbum() || isPlaylist(); }
    public boolean isBrowsable() { return isArtist() || isCollection(); }
}
