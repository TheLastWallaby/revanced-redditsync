package app.revanced.extension.synccompat;

import java.util.Objects;

/**
 * Checks the Reddit video source rewrite: Sync's unsigned v.redd.it HLS playlist becomes the
 * video's DASH manifest, and every other source is left as Sync passed it. The classifier model
 * is transcribed from Sync 23033's y7.c.s (DASH: v.redd.it + .mpd) and y7.c.t (HLS: v.redd.it + .m3u8).
 */
public final class RedditVideoCompatTest {
    private static int checks;

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    /** Sync's y7.c.s: plays through DashMediaSource. */
    private static boolean syncDash(String source) {
        return source != null && !source.isEmpty() && source.contains("v.redd.it") && source.contains(".mpd");
    }

    /** Sync's y7.c.t: plays through HlsMediaSource. */
    private static boolean syncHls(String source) {
        return source != null && !source.isEmpty() && source.contains("v.redd.it") && source.contains(".m3u8");
    }

    public static void main(String[] args) {
        String dash = "https://v.redd.it/6oemnktwd1zh1/DASHPlaylist.mpd";
        String signed = "?a=1796157371%2CZDIwNTA3ODlkMWI1OGI4YzEx%3D%3D";
        String[] rewritten = {
                "https://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8",                         // Sync's rebuilt URL
                "https://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8" + signed + "&amp;v=1&amp;f=sd", // hls_url as parsed (escaped)
                "https://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8" + signed + "&v=1&f=sd",
                "http://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8",
                "https://v.redd.it:443/6oemnktwd1zh1/HLSPlaylist.m3u8",
        };
        for (String source : rewritten) {
            check(syncHls(source) && !syncDash(source), "precondition: Sync plays as HLS: " + source);
            String result = RedditVideoCompat.playbackSource(source);
            equal(dash, result, "HLS playlist rewritten to the DASH manifest");
            check(syncDash(result) && !syncHls(result), "Sync now takes its DASH branch: " + source);
            check(!result.contains("?") && !result.contains("&"), "no signature or query carried over");
            equal(result, RedditVideoCompat.playbackSource(result), "rewriting twice changes nothing");
        }
        // Sync's classifiers are case-sensitive, so it never played this as HLS; the canonical DASH form plays.
        String upper = RedditVideoCompat.playbackSource("HTTPS://V.REDD.IT/6oemnktwd1zh1/HLSPlaylist.m3u8");
        equal(dash, upper, "scheme and host case");
        check(syncDash(upper), "canonical form reaches Sync's DASH branch");
        equal("https://v.redd.it/AbC123/DASHPlaylist.mpd",
                RedditVideoCompat.playbackSource("https://v.redd.it/AbC123/HLSPlaylist.m3u8"), "ID case kept");

        String[] unchanged = {
                null, "", "   ",
                dash,                                                         // already DASH
                "https://v.redd.it/6oemnktwd1zh1",                            // Sync's link before its own rebuild
                "https://v.redd.it/6oemnktwd1zh1/CMAF_480.mp4",
                "https://v.redd.it/6oemnktwd1zh1/DASH_480.mp4?source=fallback",
                "https://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8/extra",
                "https://v.redd.it/6oemnktwd1zh1/HLS_540.m3u8",
                "https://v.redd.it/a/b/HLSPlaylist.m3u8",
                "https://v.redd.it/../HLSPlaylist.m3u8",
                "https://v.redd.it//HLSPlaylist.m3u8",
                "https://v.redd.it/" + "a".repeat(33) + "/HLSPlaylist.m3u8",
                "https://v.redd.it/abc%2F../HLSPlaylist.m3u8",
                "https://v.redd.it/abc/HLSPlaylist.m3u8#frag",
                "https://user@v.redd.it/abc/HLSPlaylist.m3u8",
                "https://v.redd.it:8443/abc/HLSPlaylist.m3u8",
                "https://v.redd.it.example.org/abc/HLSPlaylist.m3u8",
                "https://example.org/v.redd.it/abc/HLSPlaylist.m3u8",
                "https://www.reddit.com/abc/HLSPlaylist.m3u8",
                "ftp://v.redd.it/abc/HLSPlaylist.m3u8",
                "/storage/emulated/0/Sync/video.mp4",                         // Sync's cached playback
                "file:///data/user/0/cache/v.redd.it/abc/HLSPlaylist.m3u8",
                "https://i.imgur.com/abc.mp4",
                "https://media.redgifs.com/AltruisticGrubbyPug.mp4",
                "https://v.redd.it/abc/HLSPlaylist.m3u8 with space",
                "https://v.redd.it/abc/HLSPlaylist.m3u8" + "x".repeat(5000),
        };
        for (String source : unchanged) {
            check(RedditVideoCompat.playbackSource(source) == source,
                    "left as passed: " + (source == null ? "null" : source.substring(0, Math.min(80, source.length()))));
        }
        check(RedditVideoCompat.hlsVideoId("https://v.redd.it/abc/HLSPlaylist.m3u8?a=b%3D").equals("abc"), "ID read");
        System.out.println("PASS: " + checks + " Reddit video source checks (Sync 23033 player classifier model; no network)");
    }
}
