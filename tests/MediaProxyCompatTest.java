package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.VolleyError;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline and loopback checks for the direct replacements of Sync's legacy media proxies. */
public final class MediaProxyCompatTest {
    private static int checks;

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    /** Shape of Imgur's public album embed page: the item list is a JavaScript object literal. */
    static final String ALBUM_PAGE = "<html><head><title>Imgur</title></head><body><script>\n"
            + "  var images            = {\"count\":5,\"images\":["
            + "{\"hash\":\"wElih4t\",\"title\":\"\",\"description\":\"First &amp; best } {\",\"has_sound\":false,"
            + "\"width\":630,\"height\":847,\"size\":1,\"ext\":\".png\",\"animated\":false,\"prefer_video\":false},"
            + "{\"hash\":\"xii6O2j\",\"title\":\"Clip\",\"description\":null,\"width\":480,\"height\":854,"
            + "\"ext\":\".mp4\",\"animated\":true,\"prefer_video\":true,\"processing\":{\"status\":\"completed\"}},"
            + "{\"hash\":\"bad!hash\",\"ext\":\".jpg\",\"width\":1,\"height\":1},"
            + "{\"hash\":\"Valid12\",\"ext\":\".exe\",\"width\":1,\"height\":1},"
            + "{\"hash\":\"UMnwLif\",\"title\":\"Loop\",\"description\":\"\",\"width\":700,\"height\":394,"
            + "\"ext\":\".gif\",\"animated\":true,\"prefer_video\":false}]},\n"
            + "      albumHash         = '4vvpnn7',\n      currentIndex      = 0;\n</script></body></html>";
    static final String VIDEO_PAGE = "<html><script>var title = \"A \\\"quoted\\\" caf\\u00e9\\n\";\n"
            + "var videoItem = { looping: true, width: 480, height: 854 };</script>"
            + "<div class=\"image-panel\"><video><source src=\"//i.imgur.com/xii6O2j.mp4\" type=\"video/mp4\">"
            + "</video></div></html>";
    static final String IMAGE_PAGE = "<html><script>var title = \"\";</script>"
            + "<img id=\"image-element\" class=\"post\" src=\"//i.imgur.com/pM6spu7l.jpg\" /></html>";

    public static void main(String[] args) throws Exception {
        FakePlatform platform = new FakePlatform();
        MediaProxyCompat.Platform original = MediaProxyCompat.platform;
        MediaProxyCompat.platform = platform;
        long[] now = {1_000_000L};
        MediaProxyCompat.clock = () -> now[0];
        try {
            ids();
            requests();
            albums();
            singleItems();
            websitePreviews();
            thumbnails(platform, now);
            autoplay(platform, now);
            loopback(platform);
        } finally {
            MediaProxyCompat.platform = original;
            MediaProxyCompat.imgurOrigin = MediaProxyCompat.IMGUR_ORIGIN;
            MediaProxyCompat.clear();
        }
        System.out.println("MediaProxyCompatTest: " + checks + " checks passed");
    }

    private static void ids() {
        String[][] valid = {
                {"https://imgur.com/a/4vvpnn7", "4vvpnn7"},
                {"http://imgur.com/a/4vvpnn7", "4vvpnn7"}, // Sync's iob.imgur.com resolver builds http links.
                {"https://imgur.com/gallery/funny-title-words-2LbCZZB", "2LbCZZB"},
                {"https://m.imgur.com/gallery/2LbCZZB?utm_source=x&b=2#frag", "2LbCZZB"},
                {"https://imgur.io/a/AbCdE", "AbCdE"},
                {"https://www.imgur.com/a/4vvpnn7/embed", "4vvpnn7"},
                {"https://imgur.com/a/4vvpnn7/all/", "4vvpnn7"},
                {"https://IMGUR.com/a/4vvpnn7", "4vvpnn7"},
                {"https://imgur.com/pM6spu7", "pM6spu7"},
                {"https://imgur.com/pM6spu7.jpg", "pM6spu7"},
                {"https://imgur.com/pM6spu7_d.jpg", "pM6spu7"},
                {"  https://imgur.com/a/4vvpnn7\nsecond line", "4vvpnn7"},
                {"https://imgur.com:443/a/4vvpnn7", "4vvpnn7"},
        };
        for (String[] row : valid) check(row[1].equals(MediaProxyCompat.imgurId(row[0])), "Imgur ID " + row[0]);
        String[] invalid = {
                null, "", "javascript:alert(1)", "https://evil.example/a/4vvpnn7",
                "https://imgur.com.evil.example/a/4vvpnn7", "https://user@imgur.com/a/4vvpnn7",
                "https://imgur.com:8443/a/4vvpnn7", "https://imgur.com/a/", "https://imgur.com/a/abc",
                "https://imgur.com/a/abc def", "https://imgur.com/a/waytoolongidentifier", "ftp://imgur.com/a/4vvpnn7",
                "https://i.imgur.com/a/4vvpnn7",
        };
        for (String link : invalid) check(MediaProxyCompat.imgurId(link) == null, "rejects " + link);
    }

    private static void requests() {
        check("https://imgur.com/a/4vvpnn7/embed?pub=true".equals(
                MediaProxyCompat.albumRequestUrl("https://imgur.com/gallery/title-4vvpnn7")), "album embed URL");
        check("https://imgur.com/pM6spu7/embed?pub=true".equals(
                MediaProxyCompat.imageRequestUrl("http://imgur.com/gallery/pM6spu7")), "item embed URL");
        check(MediaProxyCompat.UNAVAILABLE_URL.equals(MediaProxyCompat.albumRequestUrl("https://evil.example/a/x")),
                "unusable album link");
        check(MediaProxyCompat.UNAVAILABLE_URL.equals(MediaProxyCompat.imageRequestUrl(null)), "missing image link");
        for (String url : new String[] {MediaProxyCompat.albumRequestUrl("https://imgur.com/a/4vvpnn7"),
                MediaProxyCompat.imageRequestUrl("https://imgur.com/pM6spu7"), MediaProxyCompat.UNAVAILABLE_URL}) {
            check(url.startsWith("https://") && !url.contains("syncforreddit"), "direct HTTPS request " + url);
        }
        check("pM6spu7".equals(MediaProxyCompat.embedId("https://imgur.com/pM6spu7/embed?pub=true")), "embed ID");
        check(MediaProxyCompat.embedId("https://imgur.com/a/pM6spu7/embed?pub=true") == null, "album URL is not an item");
        check(MediaProxyCompat.embedId("https://evil.example/pM6spu7/embed?pub=true") == null, "other origin");
    }

    private static NetworkResponse response(String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "max-age=3600");
        return new NetworkResponse(200, body.getBytes(StandardCharsets.UTF_8), headers, false, 12L);
    }

    /** The reads Sync's ImgurGalleryRequest makes, which throw on a missing key. */
    private static List<String> syncAlbumParse(byte[] data) throws Exception {
        List<String> media = new ArrayList<>();
        JSONArray images = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONObject("data").getJSONArray("images");
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.getJSONObject(i);
            String link = image.getString("link");
            if (!image.optString("mp4").isEmpty()) link = image.optString("mp4");
            image.getInt("width");
            image.getInt("height");
            media.add(link + "|" + image.optString("title") + "|" + image.optString("description"));
        }
        return media;
    }

    private static void albums() throws Exception {
        NetworkResponse page = response(ALBUM_PAGE);
        NetworkResponse adapted = MediaProxyCompat.albumResponse(page);
        check(adapted != page && adapted.statusCode == 200 && adapted.headers == page.headers
                && adapted.networkTimeMs == 12L, "status, cache headers and timing kept");
        List<String> media = syncAlbumParse(adapted.data);
        check(media.equals(java.util.Arrays.asList(
                "https://i.imgur.com/wElih4t.png||First &amp; best } {",
                "https://i.imgur.com/xii6O2j.mp4|Clip|",
                "https://i.imgur.com/UMnwLif.mp4|Loop|")), "album order, captions and video items: " + media);
        JSONArray images = new JSONObject(new String(adapted.data, StandardCharsets.UTF_8))
                .getJSONObject("data").getJSONArray("images");
        check("https://i.imgur.com/UMnwLif.gif".equals(images.getJSONObject(2).getString("link"))
                && images.getJSONObject(0).optString("mp4").isEmpty(), "stills keep their file, GIFs play as MP4");
        check(images.getJSONObject(1).getInt("width") == 480 && images.getJSONObject(1).getInt("height") == 854, "sizes");
        // A Volley cache hit returns the stored adapted JSON, including Sync's saved position.
        JSONObject stored = new JSONObject(new String(adapted.data, StandardCharsets.UTF_8)).put("position", 2);
        NetworkResponse cached = response(stored.toString());
        check(MediaProxyCompat.albumResponse(cached) == cached, "cached JSON passes through");
        check(new JSONObject(new String(MediaProxyCompat.albumResponse(cached).data, StandardCharsets.UTF_8))
                .optInt("position") == 2, "saved album position survives");
        for (String body : new String[] {"<html>no list</html>", "<script>var images = {\"images\":[]};</script>",
                "<script>var images = {\"images\":[{\"hash\":\"x\",\"ext\":\".jpg\"}]};</script>",
                "<script>var images = {broken</script>", ""}) {
            NetworkResponse unusable = response(body);
            check(MediaProxyCompat.albumResponse(unusable) == unusable, "unusable page left for Sync's error path");
        }
        check(MediaProxyCompat.albumResponse(null) == null, "null response");

        NetworkResponse missing = new NetworkResponse(404, "<html>not found</html>".getBytes(StandardCharsets.UTF_8),
                Collections.<String, String>emptyMap(), false, 3L);
        VolleyError mapped = MediaProxyCompat.albumError(new VolleyError(missing));
        check(mapped.networkResponse.statusCode == 404 && new String(mapped.networkResponse.data, StandardCharsets.UTF_8)
                .contains("Unable to find an album with the id"), "404 takes Sync's single-image fallback");
        VolleyError limited = new VolleyError(new NetworkResponse(429, new byte[0],
                Collections.<String, String>emptyMap(), false, 1L));
        check(MediaProxyCompat.albumError(limited) == limited, "rate limit stays an error");
        VolleyError offline = new VolleyError();
        check(MediaProxyCompat.albumError(offline) == offline && MediaProxyCompat.albumError(null) == null, "no response");
    }

    private static void singleItems() throws Exception {
        JSONObject video = MediaProxyCompat.imageJson(VIDEO_PAGE, "xii6O2j").getJSONObject("data");
        check("https://i.imgur.com/xii6O2j.mp4".equals(video.getString("link")), "video item plays its MP4");
        check(("A \"quoted\" caf" + (char) 0xe9 + "\n").equals(video.getString("title")),
                "JavaScript title decoded: " + video.getString("title"));
        JSONObject still = MediaProxyCompat.imageJson(IMAGE_PAGE, "pM6spu7").getJSONObject("data");
        check("https://i.imgur.com/pM6spu7.jpg".equals(still.getString("link")), "still keeps its type, not the sized copy");
        check("https://i.imgur.com/pM6spu7.png".equals(MediaProxyCompat.imageJson(
                IMAGE_PAGE.replace("pM6spu7l.jpg", "pM6spu7.png"), "pM6spu7").getJSONObject("data").getString("link")),
                "unsuffixed PNG");
        check("https://i.imgur.com/pM6spu7.webp".equals(MediaProxyCompat.imageJson(
                IMAGE_PAGE.replace("//i.imgur.com/pM6spu7l.jpg", "https://i.imgur.com/pM6spu7m.webp"), "pM6spu7")
                .getJSONObject("data").getString("link")), "absolute HTTPS source");
        check(MediaProxyCompat.imageJson(IMAGE_PAGE, "otherId") == null, "another item's media is ignored");
        check(MediaProxyCompat.imageJson(IMAGE_PAGE.replace("i.imgur.com", "evil.example"), "pM6spu7") == null,
                "other hosts are ignored");
        check(MediaProxyCompat.imageJson(VIDEO_PAGE.replace("video/mp4", "video/webm"), "xii6O2j") == null,
                "only MP4 sources");
        check(MediaProxyCompat.imageJson("<html></html>", "pM6spu7") == null, "page without media");
        check(MediaProxyCompat.imageJson(IMAGE_PAGE.replace("pM6spu7l.jpg", "pM6spu7.exe"), "pM6spu7") == null,
                "unsupported file type");

        NetworkResponse page = response(IMAGE_PAGE);
        NetworkResponse adapted = MediaProxyCompat.imageResponse(page, "https://imgur.com/pM6spu7/embed?pub=true");
        JSONObject data = new JSONObject(new String(adapted.data, StandardCharsets.UTF_8)).getJSONObject("data");
        check("https://i.imgur.com/pM6spu7.jpg".equals(data.getString("link")) && data.has("title")
                && adapted.headers == page.headers, "item response for Sync's parser");
        check(MediaProxyCompat.imageResponse(page, "https://evil.example/pM6spu7/embed?pub=true") == page,
                "unexpected request URL passes through");
        NetworkResponse cached = response(new String(adapted.data, StandardCharsets.UTF_8));
        check(MediaProxyCompat.imageResponse(cached, "https://imgur.com/pM6spu7/embed?pub=true") == cached, "cache hit");
        check("x\\".equals(MediaProxyCompat.unescape("x\\")) && "\\u12".equals(MediaProxyCompat.unescape("\\\\u12"))
                && "u12".equals(MediaProxyCompat.unescape("\\u12")) && "A".equals(MediaProxyCompat.unescape("\\x41")),
                "escape edge cases");
    }

    private static void websitePreviews() {
        for (String link : new String[] {"https://www.youtube.com/watch?v=abc", "https://youtu.be/abc",
                "https://m.youtube.com/watch?v=abc&t=3"}) {
            check(MediaProxyCompat.websitePreviewAllowed(link), "YouTube keeps its thumbnail " + link);
        }
        for (String link : new String[] {"https://example.com/article?id=1&ref=x#top", "https://github.com/a/b",
                "https://youtube.com.evil.example/watch", "javascript:youtube.com", null, "not a url"}) {
            check(!MediaProxyCompat.websitePreviewAllowed(link), "link card for " + link);
        }
    }

    private static void thumbnails(FakePlatform platform, long[] now) throws Exception {
        MediaProxyCompat.clear();
        platform.reset();
        String page = "https://example.com/article?id=1&ref=x#top";
        check(MediaProxyCompat.thumbnail("span", page,
                "https://ap.syncforreddit.com/image?url=" + page) == null, "website proxy becomes a link card");
        check(MediaProxyCompat.thumbnail("span", "https://gfycat.com/SomeName",
                "https://ap.syncforreddit.com/image?url=https://gfycat.com/SomeName") == null, "Gfycat is unavailable");
        check(MediaProxyCompat.thumbnail("span", "https://tenor.com/view/x-gif-1", "") == null, "empty preview");
        check(MediaProxyCompat.thumbnail("span", page, page) == null, "pages never reach the image decoder");
        check(MediaProxyCompat.thumbnail("span", "https://www.reddit.com/r/x/comments/abc/t/",
                "https://www.reddit.com/r/x/comments/abc/t/") == null, "Reddit pages are pages too");
        Integer settings = 0x7f080678;
        check(MediaProxyCompat.thumbnail("span", "Settings shortcut: x", settings) == settings, "drawable kept");
        for (String picture : new String[] {"https://i.redd.it/abc.jpg", "https://example.com/x.PNG?size=2",
                "https://preview.redd.it/abc?width=640&s=sig", "https://b.thumbs.redditmedia.com/x"}) {
            check(picture.equals(MediaProxyCompat.thumbnail("span", picture, picture)), "picture link " + picture);
        }
        String[][] built = {
                {"https://www.youtube.com/watch?v=abc", "https://i.ytimg.com/vi/abc/hqdefault.jpg"},
                {"https://giphy.com/gifs/x-abc", "https://media.giphy.com/media/abc/100.gif"},
                {"https://imgur.com/pM6spu7", "https://i.imgur.com/pM6spu7h.jpg"},
        };
        for (String[] row : built) check(row[1].equals(MediaProxyCompat.thumbnail("span", row[0], row[1])), "Sync's thumbnail " + row[1]);
        check(platform.jobs.isEmpty() && platform.fetched.isEmpty(), "nothing fetched for the cases above");

        // Album covers: resolved once in the background, then loaded into each waiting card.
        String albumLink = "https://imgur.com/gallery/some-title-4vvpnn7";
        String proxy = "https://ap.syncforreddit.com/image?url=imgur-album-4vvpnn7";
        Object first = new Object();
        Object second = new Object();
        platform.pages.put("https://imgur.com/a/4vvpnn7/embed?pub=true", new MediaProxyCompat.Page(200, ALBUM_PAGE));
        check(MediaProxyCompat.thumbnail(first, albumLink, proxy) == null, "cover pending");
        check(MediaProxyCompat.thumbnail(second, albumLink, proxy) == null, "second card waits too");
        check(MediaProxyCompat.thumbnail(first, albumLink, proxy) == null, "same card again");
        check(platform.jobs.size() == 1, "one lookup per album: " + platform.jobs.size());
        platform.runJobs();
        check(platform.fetched.equals(Collections.singletonList("https://imgur.com/a/4vvpnn7/embed?pub=true")),
                "fetched the album's embed page: " + platform.fetched);
        platform.runPosted();
        check(platform.loads.size() == 2 && platform.loads.get(0)[0] == first && platform.loads.get(1)[0] == second
                && "https://i.imgur.com/wElih4tb.jpg".equals(platform.loads.get(0)[1]), "cover loaded into both cards");
        check("https://i.imgur.com/wElih4tb.jpg".equals(MediaProxyCompat.thumbnail(new Object(), albumLink, proxy)),
                "later cards get the cover directly");
        check(platform.jobs.isEmpty() && platform.fetched.size() == 1, "cover cached");

        // A gallery link that is a single item: the album 404s, the item's own thumbnail is used.
        platform.reset();
        platform.pages.put("https://imgur.com/a/pM6spu7/embed?pub=true", new MediaProxyCompat.Page(404, ""));
        platform.pages.put("https://imgur.com/pM6spu7/embed?pub=true", new MediaProxyCompat.Page(200, IMAGE_PAGE));
        Object card = new Object();
        MediaProxyCompat.thumbnail(card, "https://imgur.com/gallery/pM6spu7", "https://ap.syncforreddit.com/image?url=imgur-album-pM6spu7");
        platform.runJobs();
        platform.runPosted();
        check(platform.loads.size() == 1 && "https://i.imgur.com/pM6spu7b.jpg".equals(platform.loads.get(0)[1]), "single item cover");

        // Removed everywhere: remembered as unavailable, the card keeps no thumbnail.
        platform.reset();
        platform.pages.put("https://imgur.com/a/Gone123/embed?pub=true", new MediaProxyCompat.Page(404, ""));
        platform.pages.put("https://imgur.com/Gone123/embed?pub=true", new MediaProxyCompat.Page(404, ""));
        String gone = "https://imgur.com/a/Gone123";
        MediaProxyCompat.thumbnail(new Object(), gone, "https://ap.syncforreddit.com/image?url=imgur-album-Gone123");
        platform.runJobs();
        platform.runPosted();
        check(platform.loads.isEmpty(), "no load for a removed album");
        check(MediaProxyCompat.thumbnail(new Object(), gone, "https://ap.syncforreddit.com/image?url=imgur-album-Gone123") == null
                && platform.jobs.isEmpty(), "unavailable album not fetched again");

        // Rate limit or network failure: no load, retried only after a delay.
        platform.reset();
        platform.pages.put("https://imgur.com/a/Busy123/embed?pub=true", new MediaProxyCompat.Page(429, ""));
        String busy = "https://imgur.com/a/Busy123";
        String busyProxy = "https://ap.syncforreddit.com/image?url=imgur-album-Busy123";
        MediaProxyCompat.thumbnail(new Object(), busy, busyProxy);
        platform.runJobs();
        platform.runPosted();
        check(platform.loads.isEmpty() && platform.fetched.size() == 1, "429 stops at the album page");
        MediaProxyCompat.thumbnail(new Object(), busy, busyProxy);
        check(platform.jobs.isEmpty(), "no immediate retry");
        now[0] += MediaProxyCompat.RETRY_MS;
        MediaProxyCompat.thumbnail(new Object(), busy, busyProxy);
        check(platform.jobs.size() == 1, "retried after the delay");
        platform.runJobs();
        platform.pages.clear(); // IOException on the next attempt
        now[0] += MediaProxyCompat.RETRY_MS;
        MediaProxyCompat.thumbnail(new Object(), busy, busyProxy);
        platform.runJobs();
        check(platform.loads.isEmpty(), "network failure leaves the card without a thumbnail");

        // A full worker queue: the lookup can be queued again by the next draw.
        platform.reset();
        platform.reject = true;
        String queued = "https://ap.syncforreddit.com/image?url=imgur-album-Queue12";
        MediaProxyCompat.thumbnail(new Object(), "https://imgur.com/a/Queue12", queued);
        platform.reject = false;
        MediaProxyCompat.thumbnail(new Object(), "https://imgur.com/a/Queue12", queued);
        check(platform.jobs.size() == 1, "rejected lookup is not stuck pending");

        // Waiting cards per album are bounded.
        platform.reset();
        platform.pages.put("https://imgur.com/a/Many123/embed?pub=true", new MediaProxyCompat.Page(200, ALBUM_PAGE));
        List<Object> cards = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Object waiting = new Object();
            cards.add(waiting);
            MediaProxyCompat.thumbnail(waiting, "https://imgur.com/a/Many123", "https://ap.syncforreddit.com/image?url=imgur-album-Many123");
        }
        platform.runJobs();
        platform.runPosted();
        check(platform.loads.size() == MediaProxyCompat.MAX_WAITING_SPANS, "bounded waiting cards: " + platform.loads.size());
        check(cards.size() == 20, "cards held by the test");
        for (String fetched : platform.allFetched) check(!fetched.contains("syncforreddit"), "never fetches a proxy: " + fetched);
    }

    private static void autoplay(FakePlatform platform, long[] now) {
        MediaProxyCompat.clear();
        platform.reset();
        Object player = new Object();
        Object other = new Object();
        Object idle = new Object();
        platform.active.add(player);
        platform.active.add(other);
        String fallback = "https://v.redd.it/abc/DASHPlaylist.mpd";
        String redgifs = "https://www.redgifs.com/watch/massivewarmheartedgnat";
        check(fallback.equals(MediaProxyCompat.autoplayUrl(player, redgifs, fallback, false, true, false)), "setting off");
        check(MediaProxyCompat.takeRedgifsRequest(player) == null, "no RedGifs request with the setting off");
        check(fallback.equals(MediaProxyCompat.autoplayUrl(player, "https://gfycat.com/SomeName", fallback, true, true, false)),
                "Gfycat uses Sync's ordinary source");
        check("".equals(MediaProxyCompat.autoplayUrl(player, "https://gfycat.com/SomeName", "", true, true, false)),
                "Gfycat without a source has nothing to play");
        check(fallback.equals(MediaProxyCompat.autoplayUrl(player, "https://example.com/v.mp4", fallback, true, true, false)), "other link");

        check(MediaProxyCompat.autoplayUrl(player, redgifs, fallback, true, true, false) == null, "RedGifs waits for the resolver");
        check(redgifs.equals(MediaProxyCompat.takeRedgifsRequest(player)), "Sync's resolver gets the link");
        check(MediaProxyCompat.takeRedgifsRequest(player) == null, "request handed over once");
        check(MediaProxyCompat.autoplayUrl(other, "https://redgifs.com/ifr/MassiveWarmheartedGnat", "", true, false, true) == null,
                "another player for the same post waits");
        check(MediaProxyCompat.takeRedgifsRequest(other) == null, "one resolver request per post");
        check(MediaProxyCompat.autoplayUrl(idle, redgifs, fallback, true, true, true) == null, "idle player waits");
        String video = "https://media.redgifs.com/MassiveWarmheartedGnat.mp4";
        MediaProxyCompat.redgifsListener(redgifs).onResponse(video);
        platform.runPosted();
        check(platform.plays.size() == 2, "both active players start: " + platform.plays.size());
        Map<Object, Object[]> plays = new HashMap<>();
        for (Object[] play : platform.plays) plays.put(play[0], play);
        check(video.equals(plays.get(player)[1]) && Boolean.TRUE.equals(plays.get(player)[2])
                && Boolean.FALSE.equals(plays.get(player)[3]), "player keeps its muted/controls flags");
        check(video.equals(plays.get(other)[1]) && Boolean.FALSE.equals(plays.get(other)[2])
                && Boolean.TRUE.equals(plays.get(other)[3]), "second player flags");
        check(!plays.containsKey(idle), "a detached player is not started");
        check(video.equals(MediaProxyCompat.autoplayUrl(player, redgifs, fallback, true, true, false)), "resolved video cached");

        // Unsafe resolver output and errors fall back to Sync's ordinary source.
        platform.reset();
        String second = "https://redgifs.com/watch/secondpost";
        for (String unsafe : new String[] {"http://media.redgifs.com/Second.mp4", "https://evil.example/Second.mp4",
                "https://media.redgifs.com.evil.example/Second.mp4", "https://media.redgifs.com/Second.html",
                "https://user@media.redgifs.com/Second.mp4", null}) {
            MediaProxyCompat.clear();
            platform.reset();
            check(MediaProxyCompat.autoplayUrl(player, second, fallback, true, true, false) == null, "waits");
            MediaProxyCompat.redgifsListener(second).onResponse(unsafe);
            platform.runPosted();
            check(platform.plays.size() == 1 && fallback.equals(platform.plays.get(0)[1]), "unsafe result ignored: " + unsafe);
        }
        check(fallback.equals(MediaProxyCompat.autoplayUrl(player, second, fallback, true, true, false)),
                "failed post plays the fallback without asking again");
        check(MediaProxyCompat.takeRedgifsRequest(player) == null, "no repeat request");
        now[0] += MediaProxyCompat.RETRY_MS;
        check(MediaProxyCompat.autoplayUrl(player, second, fallback, true, true, false) == null
                && second.equals(MediaProxyCompat.takeRedgifsRequest(player)), "asked again after the delay");

        MediaProxyCompat.clear();
        platform.reset();
        MediaProxyCompat.autoplayUrl(player, second, "", true, true, false);
        MediaProxyCompat.redgifsErrorListener(second).onErrorResponse(new VolleyError());
        platform.runPosted();
        check(platform.plays.isEmpty(), "nothing to play after an error without a fallback");

        // A player moved to another post no longer waits for the first one.
        MediaProxyCompat.clear();
        platform.reset();
        MediaProxyCompat.autoplayUrl(player, second, fallback, true, true, false);
        MediaProxyCompat.autoplayUrl(player, "https://example.com/other.mp4", fallback, true, true, false);
        MediaProxyCompat.redgifsListener(second).onResponse("https://media.redgifs.com/Second.mp4");
        platform.runPosted();
        check(platform.plays.isEmpty(), "stale callback does not restart the player");

        // A resolver request that never answers (cancelled queue) is replaced after a timeout.
        MediaProxyCompat.clear();
        platform.reset();
        MediaProxyCompat.autoplayUrl(player, second, fallback, true, true, false);
        MediaProxyCompat.takeRedgifsRequest(player);
        MediaProxyCompat.autoplayUrl(other, second, fallback, true, true, false);
        check(MediaProxyCompat.takeRedgifsRequest(other) == null, "still pending");
        now[0] += MediaProxyCompat.PENDING_TIMEOUT_MS;
        MediaProxyCompat.autoplayUrl(other, second, fallback, true, true, false);
        check(second.equals(MediaProxyCompat.takeRedgifsRequest(other)), "stuck request replaced");

        String[][] ids = {
                {"https://www.redgifs.com/watch/MassiveWarmheartedGnat", "massivewarmheartedgnat"},
                {"https://redgifs.com/ifr/abcdef?x=1", "abcdef"},
                {"https://v3.redgifs.com/watch/abcdef-mobile.mp4", "abcdef"},
                {"https://redgifs.com/watch/abcdef/", "abcdef"},
        };
        for (String[] row : ids) check(row[1].equals(MediaProxyCompat.redgifsId(row[0])), "RedGifs ID " + row[0]);
        for (String link : new String[] {"https://notredgifs.com/watch/abcdef", "https://redgifs.com.evil.example/watch/abcdef",
                "https://redgifs.com/", null, "https://redgifs.com/watch/a!b"}) {
            check(MediaProxyCompat.redgifsId(link) == null, "not a RedGifs post " + link);
        }
        check(MediaProxyCompat.autoplayUrl(player, "https://redgifs.com.evil.example/watch/abcdef", fallback, true, true, false)
                .equals(fallback), "lookalike host is not resolved");
    }

    private static void loopback(FakePlatform platform) throws Exception {
        MediaProxyCompat.clear();
        platform.reset();
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        List<String> leaked = new CopyOnWriteArrayList<>();
        List<String> agents = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            for (String header : new String[] {"Cookie", "Authorization"}) {
                if (exchange.getRequestHeaders().containsKey(header)) leaked.add(header + " " + path);
            }
            agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            byte[] body;
            int status = 200;
            if (path.equals("/a/Album12/embed")) {
                body = ALBUM_PAGE.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/a/Moved12/embed")) {
                exchange.getResponseHeaders().add("Location", "/elsewhere");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            } else if (path.equals("/a/Huge123/embed")) {
                body = new byte[MediaProxyCompat.MAX_PAGE_BYTES + 1];
            } else {
                status = 404;
                body = "missing".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ExecutorService threads = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "media-proxy-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(threads);
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            MediaProxyCompat.imgurOrigin = origin;
            platform.network = true;
            MediaProxyCompat.Page page = MediaProxyCompat.get(origin + "/a/Album12/embed?pub=true");
            check(page.status == 200 && MediaProxyCompat.albumJson(page.body) != null, "loopback album page");
            check(MediaProxyCompat.get(origin + "/a/Moved12/embed?pub=true").status == 302
                    && !hits.containsKey("/elsewhere"), "redirects are not followed");
            boolean refused = false;
            try {
                MediaProxyCompat.get(origin + "/a/Huge123/embed?pub=true");
            } catch (IOException tooLarge) {
                refused = true;
            }
            check(refused, "oversized page refused");
            refused = false;
            try {
                MediaProxyCompat.get("https://evil.example/a/Album12/embed?pub=true");
            } catch (IOException otherOrigin) {
                refused = true;
            }
            check(refused, "only the fixed Imgur origin is fetched");

            Object card = new Object();
            MediaProxyCompat.thumbnail(card, "https://imgur.com/a/Album12", "https://ap.syncforreddit.com/image?url=imgur-album-Album12");
            platform.runJobs();
            platform.runPosted();
            check(platform.loads.size() == 1 && "https://i.imgur.com/wElih4tb.jpg".equals(platform.loads.get(0)[1]),
                    "cover resolved over HTTP");
            MediaProxyCompat.thumbnail(new Object(), "https://imgur.com/a/Moved12", "https://ap.syncforreddit.com/image?url=imgur-album-Moved12");
            platform.runJobs();
            check(platform.loads.size() == 1, "redirected album has no cover");
            check(leaked.isEmpty(), "no cookies or credentials sent: " + leaked);
            check(agents.stream().allMatch("sync_for_reddit"::equals), "Sync's Imgur user agent: " + agents);
        } finally {
            platform.network = false;
            MediaProxyCompat.imgurOrigin = MediaProxyCompat.IMGUR_ORIGIN;
            server.stop(0);
            threads.shutdownNow();
        }
    }

    static final class FakePlatform implements MediaProxyCompat.Platform {
        final List<Runnable> jobs = new ArrayList<>();
        final List<Runnable> posted = new ArrayList<>();
        final List<Object[]> loads = new ArrayList<>();
        final List<Object[]> plays = new ArrayList<>();
        final Set<Object> active = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final Map<String, MediaProxyCompat.Page> pages = new HashMap<>();
        final List<String> fetched = new ArrayList<>();
        final Set<String> allFetched = new HashSet<>();
        boolean reject;
        boolean network;

        void reset() {
            jobs.clear();
            posted.clear();
            loads.clear();
            plays.clear();
            pages.clear();
            fetched.clear();
            reject = false;
        }

        void runJobs() {
            while (!jobs.isEmpty()) jobs.remove(0).run();
        }

        void runPosted() {
            while (!posted.isEmpty()) posted.remove(0).run();
        }

        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException();
            jobs.add(task);
        }

        @Override public void post(Runnable task) {
            posted.add(task);
        }

        @Override public void loadThumbnail(Object span, String url) {
            loads.add(new Object[] {span, url});
        }

        @Override public boolean isActive(Object player) {
            return active.contains(player);
        }

        @Override public void play(Object player, String url, boolean muted, boolean controls) {
            plays.add(new Object[] {player, url, muted, controls});
        }

        @Override public MediaProxyCompat.Page fetch(String url) throws IOException {
            fetched.add(url);
            allFetched.add(url);
            if (network) return MediaProxyCompat.get(url);
            MediaProxyCompat.Page page = pages.get(url);
            if (page == null) throw new IOException("offline");
            return page;
        }
    }
}
