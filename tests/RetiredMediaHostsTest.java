package app.revanced.extension.synccompat;

import java.util.Objects;
import org.json.JSONObject;

/**
 * Checks which links RetiredMediaHosts treats as retired (vid.me, sli.mg, Eroshare, picsarus and
 * their subdomains, over http, https or no scheme, in any case) and that every other media host,
 * look-alike host and non-web link is left to Sync. Also checks the post-type answer for link posts.
 */
public final class RetiredMediaHostsTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    /** Links Sync 23033 sends to a retired resolver, viewer or image preview. */
    static final String[] RETIRED = {
        "https://vid.me/abc12", "http://vid.me/abc12", "https://www.vid.me/abc12", "https://m.vid.me/abc12",
        "HTTPS://VID.ME/Abc12", "https://Vid.Me/e/abc12?autoplay=1#t=3", "vid.me/abc12", "//vid.me/abc12",
        "https://vid.me", "https://vid.me/", "https://vid.me:443/abc", "https://user@vid.me/abc", "https://vid.me./abc",
        "https://sli.mg/AbCd", "http://sli.mg/a/AbCd", "https://i.sli.mg/AbCd.gif", "https://www.SLI.MG/AbCd",
        "https://m.sli.mg/AbCd.jpg", "  https://sli.mg/AbCd  ",
        "https://eroshare.com/abcd1234", "https://www.eroshare.com/i/abcd1234", "http://eroshare.com/abcd1234",
        "https://api.eroshare.com/api/v1/albums/abcd", "https://v.eroshare.com/abcd.mp4", "https://m.EroShare.com/abcd",
        "https://i.eroshare.com/abcd.jpg?x=1",
        "http://picsarus.com/AbCd", "https://picsarus.com/AbCd.jpg", "http://www.picsarus.com/AbCd",
        "https://www.PicSarus.com/AbCd.gif", "https://m.picsarus.com/AbCd", "picsarus.com/AbCd.png",
    };

    /** Live media hosts, look-alikes and other links that must keep Sync's own handling. */
    static final String[] KEPT = {
        "https://imgur.com/a/AbCd", "https://i.imgur.com/AbCd.jpg", "https://imgur.com/gallery/title-AbCd",
        "https://www.redgifs.com/watch/joyfulwhitebird", "https://thumbs2.redgifs.com/JoyfulWhiteBird-mobile.mp4",
        "https://giphy.com/gifs/abc-123", "https://media.giphy.com/media/abc/giphy.gif",
        "https://i.redd.it/abc.jpg", "https://v.redd.it/abc", "https://preview.redd.it/abc.png?width=640&s=1",
        "https://www.reddit.com/gallery/abc", "https://www.reddit.com/r/pics/comments/abc/title/",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", "https://gfycat.com/foo",
        "https://streamable.com/abc", "https://example.tumblr.com/post/123/title", "https://64.media.tumblr.com/x/s640x960/a.jpg",
        "https://www.deviantart.com/artist/art/Title-123", "https://images-wixmp-abc.wixmp.com/f/a.jpg",
        "https://covid.me/abc", "https://notvid.me/abc", "https://vid.me.example.com/abc", "https://vidme.com/abc",
        "https://slimg.com/abc", "https://sli.mg.example.org/a.gif", "https://myeroshare.com/abc",
        "https://eroshare.com.example.net/abc", "https://picsarus.co/abc", "https://notpicsarus.com/abc.jpg",
        "https://example.com/vid.me/abc", "https://example.com/?u=https://vid.me/abc", "https://example.com/#vid.me",
        "https://example.com@evil.example/x", "ftp://vid.me/abc", "javascript:vid.me", "mailto:someone@vid.me",
        "file:///sdcard/vid.me/x.mp4", "content://vid.me/x", "https://vid.me:abc/x", "", "   ", "https://", "/r/pics",
        null,
    };

    public static void main(String[] args) {
        for (String link : RETIRED) check(RetiredMediaHosts.isRetired(link), "retired: " + link);
        for (String link : KEPT) check(!RetiredMediaHosts.isRetired(link), "kept: " + link);

        equal("vid.me", RetiredMediaHosts.host("https://user:pw@VID.ME.:8080/x?y#z"), "host strips user, port, dot and case");
        equal("example.com", RetiredMediaHosts.host("https://example.com\\@vid.me/x"), "backslash ends the authority");
        equal(null, RetiredMediaHosts.host("ftp://vid.me/x"), "only http(s) or scheme-less links have a host");

        // Link posts (kind 3) on a retired host stay link posts; other kinds and hosts are Sync's.
        for (String link : RETIRED) {
            JSONObject post = new JSONObject().put("url", link);
            equal(3, RetiredMediaHosts.postType(post, 3), "link post " + link);
            for (int kind : new int[] {1, 9, 0, 2, 4}) equal(-1, RetiredMediaHosts.postType(post, kind), "kind " + kind + " " + link);
        }
        for (String link : KEPT) {
            JSONObject post = link == null ? new JSONObject() : new JSONObject().put("url", link);
            equal(-1, RetiredMediaHosts.postType(post, 3), "Sync decides " + link);
        }
        equal(-1, RetiredMediaHosts.postType(null, 3), "no post");
        equal(-1, RetiredMediaHosts.postType(new JSONObject().put("url", 12), 3), "non-string url");
        equal(3, RetiredMediaHosts.postType(new JSONObject().put("url", "https://picsarus.com/a.jpg?x=1&amp;y=2"), 3),
                "escaped query keeps the host");

        System.out.println("PASS: " + checks + " retired media host checks (" + RETIRED.length + " retired links, "
                + KEPT.length + " kept links)");
    }
}
