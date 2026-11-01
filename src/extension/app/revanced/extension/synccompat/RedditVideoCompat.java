package app.revanced.extension.synccompat;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Plays Reddit-hosted videos through their DASH manifest.
 *
 * <p>Sync 23033 turns every {@code v.redd.it} link into {@code https://v.redd.it/<id>/HLSPlaylist.m3u8},
 * dropping the signed {@code ?a=…} parameter Reddit now requires for the HLS playlist, so the player
 * gets HTTP 403. The DASH manifest ({@code DASHPlaylist.mpd}) and the CMAF renditions it lists still
 * load without a signature, and Sync's player already plays a {@code v.redd.it} {@code .mpd} source
 * through its own DASH branch. This rewrites exactly that HLS playlist URL to the video's DASH
 * manifest where Sync hands a source to its player; every other source is returned unchanged.
 */
public final class RedditVideoCompat {
    private static final String HOST = "v.redd.it";
    private static final String HLS = "HLSPlaylist.m3u8";
    private static final String DASH = "DASHPlaylist.mpd";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final int MAX_LENGTH = 4096;

    private RedditVideoCompat() { }

    /** The source Sync's player should open for {@code source}. Never throws. */
    public static String playbackSource(String source) {
        try {
            String id = hlsVideoId(source);
            return id == null ? source : "https://" + HOST + "/" + id + "/" + DASH;
        } catch (RuntimeException unexpected) {
            return source;
        }
    }

    /** The video ID of an {@code http(s)://v.redd.it/<id>/HLSPlaylist.m3u8[?query]} URL, or null. */
    static String hlsVideoId(String source) {
        if (source == null || source.isEmpty() || source.length() > MAX_LENGTH) return null;
        URI uri;
        try {
            uri = new URI(source);
        } catch (java.net.URISyntaxException malformed) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        if (!host.toLowerCase(Locale.ROOT).equals(HOST)) return null;
        int port = uri.getPort();
        if (port != -1 && port != (scheme.equals("https") ? 443 : 80)) return null;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || !path.endsWith("/" + HLS)) return null;
        String id = path.substring(1, path.length() - HLS.length() - 1);
        return ID.matcher(id).matches() ? id : null;
    }
}
