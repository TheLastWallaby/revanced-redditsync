package app.revanced.extension.synccompat;

import java.util.Objects;

/**
 * Checks the link YouTubeLinks hands to Android for each form of YouTube link (normal videos,
 * Shorts, live, youtu.be, m./music./nocookie/embed forms, t= in seconds and 1h2m3s form, start=,
 * list= and index=, odd casing, escaped ampersands, missing schemes) and that look-alike and other
 * hosts are left to Sync (null), and which links count as single videos for Sync's link router.
 */
public final class YouTubeLinksTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static final String ID = "aqz-KE-bpKQ";
    private static final String WATCH = "https://www.youtube.com/watch?v=" + ID;

    /** Link, link handed to Android (null: not YouTube, Sync keeps its handling), video link. */
    static final Object[][] LINKS = {
        // Normal videos: kept as they are.
        {WATCH, WATCH, true},
        {"http://www.youtube.com/watch?v=" + ID, "http://www.youtube.com/watch?v=" + ID, true},
        {"https://youtube.com/watch?v=" + ID, "https://youtube.com/watch?v=" + ID, true},
        {"https://www.youtube.com/watch/?v=" + ID, "https://www.youtube.com/watch/?v=" + ID, true},
        {"https://www.youtube.com/watch?feature=share&v=" + ID, "https://www.youtube.com/watch?feature=share&v=" + ID, true},
        {"https://www.youtube.com/watch?v=" + ID + "#comments", "https://www.youtube.com/watch?v=" + ID + "#comments", true},
        {"https://www.youtube.com:443/watch?v=" + ID, "https://www.youtube.com:443/watch?v=" + ID, true},
        // Timestamps: t= in seconds, with s, and the 1h2m3s form, on every host; kept as written.
        {WATCH + "&t=90", WATCH + "&t=90", true},
        {WATCH + "&t=90s", WATCH + "&t=90s", true},
        {WATCH + "&t=1h2m3s", WATCH + "&t=1h2m3s", true},
        {"https://youtu.be/" + ID + "?t=90", "https://youtu.be/" + ID + "?t=90", true},
        {"https://youtu.be/" + ID + "?si=AbC123&t=1h2m3s", "https://youtu.be/" + ID + "?si=AbC123&t=1h2m3s", true},
        {"https://youtu.be/" + ID + "/", "https://youtu.be/" + ID + "/", true},
        {WATCH + "#t=90", WATCH + "#t=90", true},
        // Playlists in watch links: list and index kept.
        {WATCH + "&list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf&index=3", WATCH + "&list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf&index=3", true},
        {WATCH + "&list=PLx1&feature=playlist", WATCH + "&list=PLx1&feature=playlist", true},
        // Shorts and live.
        {"https://www.youtube.com/shorts/" + ID, "https://www.youtube.com/shorts/" + ID, true},
        {"https://youtube.com/shorts/" + ID + "?feature=share", "https://youtube.com/shorts/" + ID + "?feature=share", true},
        {"https://www.youtube.com/shorts/" + ID + "/", "https://www.youtube.com/shorts/" + ID + "/", true},
        {"https://www.youtube.com/live/" + ID + "?si=xyz", "https://www.youtube.com/live/" + ID + "?si=xyz", true},
        {"https://m.youtube.com/live/" + ID, "https://www.youtube.com/live/" + ID, true},
        // m. becomes www.; music. stays for the YouTube Music app.
        {"https://m.youtube.com/watch?v=" + ID + "&t=1h2m3s", WATCH + "&t=1h2m3s", true},
        {"https://m.youtube.com/shorts/" + ID, "https://www.youtube.com/shorts/" + ID, true},
        {"https://music.youtube.com/watch?v=" + ID + "&list=RDAMVM" + ID, "https://music.youtube.com/watch?v=" + ID + "&list=RDAMVM" + ID, true},
        // Embed forms and youtube-nocookie.com become standard watch or playlist links.
        {"https://www.youtube.com/embed/" + ID, WATCH, true},
        {"https://www.youtube.com/embed/" + ID + "?start=90&autoplay=1", WATCH + "&t=90", true},
        {"https://www.youtube.com/embed/" + ID + "?t=1h2m3s", WATCH + "&t=1h2m3s", true},
        {"https://www.youtube.com/v/" + ID + "?version=3", WATCH, true},
        {"https://www.youtube.com/e/" + ID, WATCH, true},
        {"https://www.youtube-nocookie.com/embed/" + ID + "?start=30", WATCH + "&t=30", true},
        {"https://youtube-nocookie.com/embed/" + ID + "?list=PLx1&index=2", WATCH + "&list=PLx1&index=2", true},
        {"https://www.youtube-nocookie.com/embed/" + ID + "#t=45", WATCH + "&t=45", true},
        {"https://www.youtube.com/embed/videoseries?list=PLx1", "https://www.youtube.com/playlist?list=PLx1", true},
        {"https://www.youtube-nocookie.com/embed/" + ID + "?start=abc", WATCH, true},
        {"https://www.youtube-nocookie.com/", "https://www.youtube-nocookie.com/", false},
        // Odd casing: scheme and host lower-cased (Android matches intent hosts case-sensitively); IDs kept.
        {"HTTPS://WWW.YouTube.com/watch?v=" + ID, WATCH, true},
        {"https://YOUTU.BE/" + ID, "https://youtu.be/" + ID, true},
        {"Https://M.YouTube.Com/Shorts/" + ID, "https://www.youtube.com/Shorts/" + ID, false},
        {"https://www.YOUTUBE-NOCOOKIE.com/embed/" + ID, WATCH, true},
        // Missing scheme, surrounding space, and Reddit's escaped ampersand.
        {"youtube.com/watch?v=" + ID, "https://youtube.com/watch?v=" + ID, true},
        {"//youtu.be/" + ID, "https://youtu.be/" + ID, true},
        {"  " + WATCH + "  ", WATCH, true},
        {WATCH + "&amp;t=90", WATCH + "&t=90", true},
        {"https://youtu.be/" + ID + "?si=a&amp;t=1m", "https://youtu.be/" + ID + "?si=a&t=1m", true},
        // Other YouTube pages: still YouTube (handed over unchanged), but not single videos.
        {"https://www.youtube.com/playlist?list=PLx1", "https://www.youtube.com/playlist?list=PLx1", false},
        {"https://www.youtube.com/channel/UCabc", "https://www.youtube.com/channel/UCabc", false},
        {"https://www.youtube.com/@someone", "https://www.youtube.com/@someone", false},
        {"https://www.youtube.com/clip/UgkxAbc", "https://www.youtube.com/clip/UgkxAbc", false},
        {"https://www.youtube.com/results?search_query=test", "https://www.youtube.com/results?search_query=test", false},
        {"https://www.youtube.com/", "https://www.youtube.com/", false},
        {"https://img.youtube.com/vi/" + ID + "/0.jpg", "https://img.youtube.com/vi/" + ID + "/0.jpg", false},
        {"https://gaming.youtube.com/watch?v=" + ID, "https://gaming.youtube.com/watch?v=" + ID, true},
        // Malformed video IDs: still YouTube pages, not videos.
        {"https://www.youtube.com/watch?v=short", "https://www.youtube.com/watch?v=short", false},
        {"https://www.youtube.com/watch?v=" + ID + "x", "https://www.youtube.com/watch?v=" + ID + "x", false},
        {"https://youtu.be/", "https://youtu.be/", false},
        {"https://www.youtube.com/shorts/" + ID + "/extra", "https://www.youtube.com/shorts/" + ID + "/extra", false},
        {"https://www.youtube.com/embed/bad%20id", "https://www.youtube.com/embed/bad%20id", false},
        // Look-alike and other hosts: not YouTube, left to Sync.
        {"https://notyoutube.com/watch?v=" + ID, null, false},
        {"https://youtube.com.evil.example/watch?v=" + ID, null, false},
        {"https://www.youtube.co/watch?v=" + ID, null, false},
        {"https://gifyoutube.com/" + ID, null, false},
        {"https://youtu.be.example.com/" + ID, null, false},
        {"https://youtube.com./watch?v=" + ID, null, false},
        {"https://evil.example/youtu.be/" + ID, null, false},
        {"https://evil.example/?u=https://www.youtube.com/watch?v=" + ID, null, false},
        {"https://www.youtube-nocookie.com.example/embed/" + ID, null, false},
        {"https://i.ytimg.com/vi/" + ID + "/hqdefault.jpg", null, false},
        {"https://user@www.youtube.com/watch?v=" + ID, null, false},
        {"https://www.youtube.com:8443/watch?v=" + ID, null, false},
        {"https://www.youtube.com/watch?v=" + ID + " x", null, false},
        {"https://www.youtube.com\\@evil.example/", null, false},
        {"vnd.youtube:" + ID, null, false},
        {"ftp://www.youtube.com/watch?v=" + ID, null, false},
        {"javascript:alert(1)//youtube.com", null, false},
        {"https://v.redd.it/abc123", null, false},
        {"https://www.reddit.com/r/videos/comments/abc123/", null, false},
        {"https://streamable.com/abc", null, false},
        {"", null, false},
        {null, null, false},
    };

    public static void main(String[] args) {
        for (Object[] row : LINKS) {
            String link = (String) row[0];
            String expected = (String) row[1];
            String handed = YouTubeLinks.appUrl(link);
            equal(expected, handed, "app URL for " + link);
            equal(row[2], YouTubeLinks.isVideoLink(link), "video link " + link);
            if (handed == null) continue;
            // Whatever is handed over is an http(s) YouTube link that stays the same when handed over again.
            check(handed.startsWith("https://") || handed.startsWith("http://"), "web scheme " + handed);
            equal(handed, YouTubeLinks.appUrl(handed), "stable " + handed);
            // An embedded playlist becomes its playlist page, which a tap opens as a page, not a video.
            if (!handed.startsWith("https://www.youtube.com/playlist?"))
                equal(YouTubeLinks.isVideoLink(link), YouTubeLinks.isVideoLink(handed), "same video kind " + handed);
            YouTubeLinks.Parsed parsed = YouTubeLinks.parse(handed);
            check(parsed != null && YouTubeLinks.isYouTubeHost(parsed.host), "YouTube host " + handed);
            check(!parsed.host.equals("m.youtube.com") && !parsed.host.contains("nocookie") || !(Boolean) row[2],
                    "app-accepted host " + handed);
        }
        // The video ID survives every rewrite with its case.
        for (Object[] row : LINKS) {
            String handed = YouTubeLinks.appUrl((String) row[0]);
            if (handed != null && (Boolean) row[2] && ((String) row[0]).contains(ID)) check(handed.contains(ID), "ID kept in " + handed);
        }
        System.out.println("PASS: " + checks + " YouTube link checks (" + LINKS.length + " links)");
    }
}
