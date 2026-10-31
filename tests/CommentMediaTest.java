package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Offline checks for comment media format, size and fallback decisions. */
public final class CommentMediaTest {
    private static int checks;
    private static final String SIG = "s=4f1c0a9e8d7b6c5a4f3e2d1c0b9a8f7e6d5c4b3a";

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static void box(int[] actual, int width, int height, String message) {
        check(actual != null && actual[0] == width && actual[1] == height,
                message + " expected " + width + "x" + height + " got " + Arrays.toString(actual));
    }

    private static void none(int[] actual, String message) {
        check(actual == null, message + " expected link fallback, got " + Arrays.toString(actual));
    }

    public static void main(String[] args) throws Exception {
        CommentMedia.clearMetadata();
        // Offline sections must never probe the network.
        CommentMedia.settingsOverride = Boolean.FALSE;
        CommentMedia.giphyMediaBase = "http://127.0.0.1:9/unused/";

        // Dimension parsing: only positive bounded decimal integers.
        check(CommentMedia.dimension("640") == 640, "plain dimension");
        for (String bad : new String[] {null, "", "0", "-5", "+5", "6.5", "1e3", "abc", "0x20", "9999999", "100001", " 64"}) {
            check(CommentMedia.dimension(bad) == -1, "invalid dimension rejected: " + bad);
        }
        check(CommentMedia.dimension("100000") == 100000, "upper bound accepted");

        // Query values: any order, repeated escaping and case-insensitive names.
        check("720".equals(CommentMedia.queryValue("height=720&width=1280", "height")), "reordered height");
        check("1280".equals(CommentMedia.queryValue("format=png&amp;width=1280", "width")), "escaped ampersand");
        check("1280".equals(CommentMedia.queryValue("amp;amp;WIDTH=1280", "width")), "double escaped key");
        check(CommentMedia.queryValue("widths=1", "width") == null, "prefix key is not a match");
        check("".equals(CommentMedia.queryValue("width", "width")), "valueless key");

        // URL parsing never accepts non-web schemes and normalizes only the host.
        check(CommentMedia.parse("javascript:alert(1)") == null, "script URL ignored");
        check(CommentMedia.parse("ftp://i.redd.it/a.png") == null, "non-HTTP scheme ignored");
        check(CommentMedia.parse("https:///a.png") == null, "empty host ignored");
        CommentMedia.Url parsed = CommentMedia.parse("HTTPS://user@Preview.Redd.It.:443/a-v0-AbC123.png?width=1#frag");
        check(parsed != null && "preview.redd.it".equals(parsed.host) && "/a-v0-AbC123.png".equals(parsed.path)
                && "width=1".equals(parsed.query), "host/path/query split");
        check("abc123".equals(CommentMedia.mediaId(parsed)), "titled preview media ID");
        check("xyz789".equals(CommentMedia.mediaId(CommentMedia.parse("https://i.redd.it/xyz789.webp"))), "i.redd.it media ID");
        check(CommentMedia.mediaId(CommentMedia.parse("https://example.com/xyz789.png")) == null, "foreign host has no Reddit ID");

        // Formats: parsed path extension or rendition format, never substring matches.
        String[] images = {
            "https://i.redd.it/abc123.webp",
            "https://example.com/photos/cat.WEBP?x=1",
            "https://preview.redd.it/abc123.png?width=640&format=png&auto=webp&" + SIG,
            "https://preview.redd.it/abc123.jpeg?width=640&format=pjpg&auto=webp&" + SIG,
            "https://preview.redd.it/abc123.gif?width=320&format=webp&" + SIG,
            "https://i.redd.it/abc123.gif",
            "https://media.giphy.com/media/abc/200.gif",
            "http://example.org/a.jpg#section",
        };
        for (String url : images) check(CommentMedia.isInlineImage(url), "inline image: " + url);
        String[] notImages = {
            "https://preview.redd.it/abc123.gif?width=320&format=mp4&" + SIG,
            "https://v.redd.it/abc123",
            "https://example.com/watch.mp4",
            "https://example.com/a.gifv",
            "https://example.com/page.html?file=a.png",
            "https://example.com/.png.html",
            "https://www.reddit.com/r/test/comments/abc123/title/",
            "intent://i.redd.it/a.png",
            "not a url .webp",
            null,
        };
        for (String url : notImages) check(!CommentMedia.isInlineImage(url), "not inline image: " + url);
        check(CommentMedia.inlineImageOr("https://example.com/watch", true), "Sync's original decision preserved");
        check(!CommentMedia.inlineImageOr("https://example.com/watch", false), "unknown link stays a link");
        check(CommentMedia.inlineImageOr("https://i.redd.it/new.webp", false), "WebP added to Sync's decision");

        // Box fitting: aspect ratio preserved, never wider than the reply.
        box(CommentMedia.fitBox(1280, 720, 1000, 0f), 1000, 563, "landscape fills reply width");
        box(CommentMedia.fitBox(1280, 720, 400, 2.75f), 400, 225, "deep reply keeps aspect ratio");
        box(CommentMedia.fitBox(720, 1280, 1000, 0f), 844, 1500, "tall image scaled within height cap");
        box(CommentMedia.fitBox(64, 32, 1000, 3f), 192, 96, "small image shown at native dp size");
        box(CommentMedia.fitBox(64, 32, 100, 3f), 100, 50, "small image still bounded by reply width");
        box(CommentMedia.fitBox(1000, 1000, 1, 0f), 1, 1, "one-pixel reply width");
        box(CommentMedia.fitBox(100000, 50000, 1080, 0f), 1080, 540, "very large source");
        none(CommentMedia.fitBox(2000, 100, 1000, 0f), "extreme banner");
        none(CommentMedia.fitBox(100, 2000, 1000, 0f), "extreme strip");
        none(CommentMedia.fitBox(0, 100, 1000, 0f), "zero width");
        none(CommentMedia.fitBox(100, 100, 0, 0f), "unmeasured reply");
        none(CommentMedia.fitBox(100, 100, -5, 0f), "negative reply width");
        none(CommentMedia.fitBox(100001, 100, 1000, 0f), "oversized source");
        none(CommentMedia.fitBox(75, 288, 1, 0f), "ratio that one pixel cannot hold");
        int sized = 0;
        for (int width = 1; width <= 2000; width += 37) {
            for (int height = 1; height <= 2000; height += 41) {
                for (int max : new int[] {1, 50, 333, 1080}) {
                    int[] result = CommentMedia.fitBox(width, height, max, 2.5f);
                    if (result == null) continue;
                    check(result[0] >= 1 && result[0] <= max, "width within reply");
                    check(result[1] >= 1 && result[1] <= Math.round(max * CommentMedia.MAX_HEIGHT_FACTOR), "height capped");
                    float expected = (float) height / width;
                    float actual = (float) result[1] / result[0];
                    check(Math.abs(actual - expected) <= expected * CommentMedia.MAX_DISTORTION,
                            "aspect ratio preserved for " + width + "x" + height + " in " + max);
                    if (max == 1080) sized++;
                }
            }
        }

        check(sized > 2000, "sweep exercised inline boxes: " + sized);

        // Signed preview URLs: dimensions in any order; the URL itself is never rebuilt.
        String ordered = "https://preview.redd.it/abc123.png?width=1280&height=720&format=png&auto=webp&" + SIG;
        String reordered = "https://preview.redd.it/abc123.png?" + SIG + "&height=720&auto=webp&width=1280&format=png";
        box(CommentMedia.inlineImageSize(ordered, 1000, 0f, true), 1000, 563, "ordered dimensions");
        box(CommentMedia.inlineImageSize(reordered, 1000, 0f, true), 1000, 563, "reordered dimensions");
        box(CommentMedia.inlineImageSize("https://preview.redd.it/abc123.webp?height=720&amp;width=1280&amp;" + SIG, 1000, 0f, true),
                1000, 563, "escaped WebP preview");
        none(CommentMedia.inlineImageSize("https://preview.redd.it/abc123.png?width=1280&height=7x0&" + SIG, 1000, 0f, true),
                "non-numeric height falls back");
        none(CommentMedia.inlineImageSize("https://preview.redd.it/abc123.png?width=640&format=png&" + SIG, 1000, 0f, true),
                "missing height and no metadata falls back");
        none(CommentMedia.inlineImageSize("https://preview.redd.it/abc123.gif?width=640&height=480&format=mp4&" + SIG, 1000, 0f, true),
                "video rendition is not drawn as a picture");
        none(CommentMedia.inlineImageSize("https://example.com/a.png?width=640&height=480", 1000, 0f, true),
                "non-Reddit dimensions are not trusted");
        none(CommentMedia.inlineImageSize(ordered, 0, 0f, true), "unmeasured reply falls back");

        // media_metadata supplies type and source size when the link lacks them.
        JSONObject metadata = new JSONObject()
                .put("k2m9zq", new JSONObject().put("status", "valid").put("e", "Image").put("m", "image/webp")
                        .put("s", new JSONObject().put("x", 1080).put("y", 1350).put("u", "https://preview.redd.it/k2m9zq.webp?width=1080&" + SIG))
                        .put("id", "k2m9zq"))
                .put("vid123", new JSONObject().put("status", "valid").put("e", "RedditVideo")
                        .put("x", 1920).put("y", 1080).put("id", "vid123"))
                .put("fail01", new JSONObject().put("status", "failed").put("e", "Image")
                        .put("s", new JSONObject().put("x", 10).put("y", 10)))
                .put("bad002", new JSONObject().put("status", "valid").put("e", "Image")
                        .put("s", new JSONObject().put("x", "wide").put("y", 10)))
                .put("giphy|abc|downsized", new JSONObject().put("status", "valid").put("e", "AnimatedImage")
                        .put("s", new JSONObject().put("x", 200).put("y", 200)))
                .put("noobj", "string value");
        CommentMedia.rememberMetadata(metadata);
        CommentMedia.rememberMetadata(null);
        String titled = "https://preview.redd.it/my-photo-v0-k2m9zq.webp?width=640&format=pjpg&auto=webp&" + SIG;
        box(CommentMedia.inlineImageSize(titled, 1000, 0f, true), 1000, 1250, "metadata size for titled preview");
        box(CommentMedia.inlineImageSize("https://i.redd.it/k2m9zq.webp", 400, 0f, true), 400, 500, "metadata size for i.redd.it");
        check(CommentMedia.isInlineImage("https://i.redd.it/k2m9zq"), "metadata MIME identifies extensionless image");
        none(CommentMedia.inlineImageSize("https://i.redd.it/vid123", 1000, 0f, true), "video metadata is not cached as a picture");
        none(CommentMedia.inlineImageSize("https://i.redd.it/fail01.png", 1000, 0f, true), "failed metadata ignored");
        none(CommentMedia.inlineImageSize("https://i.redd.it/bad002.png", 1000, 0f, true), "malformed metadata ignored");
        // Explicit link dimensions win over cached metadata.
        box(CommentMedia.inlineImageSize("https://preview.redd.it/k2m9zq.webp?width=200&height=100&" + SIG, 1000, 0f, true),
                1000, 500, "explicit dimensions win");
        for (int i = 0; i < CommentMedia.CACHE_SIZE + 10; i++) {
            CommentMedia.rememberMetadata(new JSONObject().put("fill" + i, new JSONObject().put("e", "Image")
                    .put("m", "image/png").put("s", new JSONObject().put("x", 10).put("y", 10))));
        }
        none(CommentMedia.inlineImageSize("https://i.redd.it/k2m9zq.webp", 400, 0f, true), "metadata cache is bounded");
        box(CommentMedia.inlineImageSize("https://i.redd.it/fill" + (CommentMedia.CACHE_SIZE + 9) + ".png", 400, 0f, true),
                400, 400, "recent metadata retained");
        box(CommentMedia.inlineImageSize("https://i.redd.it/fill" + (CommentMedia.CACHE_SIZE + 9) + ".png", 400, 3f, true),
                30, 30, "small metadata image not stretched");

        // Giphy links: IDs from every URL form, case preserved.
        check("hlYFdKaA7Emi".equals(CommentMedia.giphyId(CommentMedia.parse("https://media.giphy.com/media/hlYFdKaA7Emi/giphy.gif"))), "media URL ID");
        check("hlYFdKaA7Emi".equals(CommentMedia.giphyId(CommentMedia.parse("https://media3.giphy.com/media/v1.Y2lkPTc5/hlYFdKaA7Emi/giphy-downsized.gif?cid=1&rid=x"))), "versioned media URL ID");
        check("hlYFdKaA7Emi".equals(CommentMedia.giphyId(CommentMedia.parse("https://i.giphy.com/hlYFdKaA7Emi.webp"))), "i.giphy.com ID");
        check("hlYFdKaA7Emi".equals(CommentMedia.giphyId(CommentMedia.parse("https://giphy.com/gifs/captain-insano-hlYFdKaA7Emi"))), "page URL ID");
        check(CommentMedia.giphyId(CommentMedia.parse("https://giphy.com/")) == null, "bare Giphy host");
        check(CommentMedia.giphyId(CommentMedia.parse("https://notgiphy.com/media/abc/giphy.gif")) == null, "lookalike host");
        check(CommentMedia.giphyId(CommentMedia.parse("https://giphy.com.evil.example/media/abc/giphy.gif")) == null, "suffix host");
        check(CommentMedia.giphyId(CommentMedia.parse("https://media.giphy.com/media/a%2Fb/giphy.gif")) == null, "encoded ID rejected");

        String giphyLink = "https://media.giphy.com/media/hlYFdKaA7Emi/giphy.gif";
        String giphyPage = "https://giphy.com/gifs/captain-insano-hlYFdKaA7Emi";
        String rendition = "https://media2.giphy.com/media/v1.Y2lk/hlYFdKaA7Emi/giphy-downsized.gif?cid=73&rid=giphy-downsized.gif";
        none(CommentMedia.inlineImageSize(giphyLink, 1000, 0f, true), "Giphy without metadata keeps Sync's card");
        check(giphyLink.equals(CommentMedia.displayUrl(giphyLink)), "unknown Giphy draws the link itself");
        CommentMedia.rememberMetadata(new JSONObject()
                .put("giphy|hlYFdKaA7Emi|downsized", new JSONObject().put("status", "valid").put("e", "AnimatedImage")
                        .put("m", "image/gif").put("ext", "https://giphy.com/gifs/hlYFdKaA7Emi")
                        .put("s", new JSONObject().put("x", 480).put("y", 270).put("gif", rendition)
                                .put("mp4", "https://media2.giphy.com/media/v1.Y2lk/hlYFdKaA7Emi/giphy-downsized.mp4"))
                        .put("id", "giphy|hlYFdKaA7Emi|downsized"))
                .put("giphy|evil01", new JSONObject().put("e", "AnimatedImage").put("m", "image/gif")
                        .put("s", new JSONObject().put("x", 100).put("y", 100).put("gif", "http://tracker.example/x.gif")))
                .put("giphy|", new JSONObject().put("e", "AnimatedImage")
                        .put("s", new JSONObject().put("x", 100).put("y", 100))));
        box(CommentMedia.inlineImageSize(giphyLink, 1000, 0f, true), 1000, 563, "Giphy link sized from metadata");
        box(CommentMedia.inlineImageSize(giphyLink, 400, 2.75f, true), 400, 225, "Giphy fits a nested reply");
        box(CommentMedia.inlineImageSize(giphyLink, 1080, 1.5f, true), 720, 405, "Giphy not stretched past native size");
        none(CommentMedia.inlineImageSize(giphyLink, 1000, 0f, false), "animated images off keeps Sync's card");
        none(CommentMedia.inlineImageSize("https://i.redd.it/anim.gif", 1000, 0f, false), "Reddit GIF honours animated setting");
        check(rendition.equals(CommentMedia.displayUrl(giphyLink)), "draws Reddit's downsized rendition");
        check(rendition.equals(CommentMedia.displayUrl(giphyPage)), "page link draws the rendition");
        box(CommentMedia.inlineImageSize(giphyPage, 1000, 0f, true), 1000, 563, "page link sized via rendition");
        check(CommentMedia.isInlineImage(giphyPage), "page link with metadata is an image");
        String other = "https://media.giphy.com/media/evil01/giphy.gif";
        check(other.equals(CommentMedia.displayUrl(other)), "untrusted rendition URL ignored");
        box(CommentMedia.inlineImageSize(other, 300, 0f, true), 300, 300, "media file draws itself");
        none(CommentMedia.inlineImageSize("https://giphy.com/gifs/title-evil01", 300, 0f, true),
                "page link without a trusted rendition keeps the card");
        check(CommentMedia.trustedRendition("https://external-preview.redd.it/a.gif?width=1&s=2") != null, "Reddit rendition allowed");
        check(CommentMedia.trustedRendition("http://media.giphy.com/media/a/giphy.gif") == null, "plain HTTP rendition rejected");
        check(CommentMedia.trustedRendition("javascript:alert(1)") == null, "script rendition rejected");
        check(CommentMedia.trustedRendition(null) == null, "missing rendition");
        check("https://i.redd.it/k.png".equals(CommentMedia.displayUrl("https://i.redd.it/k.png")), "Reddit pictures draw their own URL");
        check(CommentMedia.displayUrl(null) == null, "null display URL");

        // Giphy id|width|height markers from Sync's media_metadata pass.
        check(CommentMedia.hasGiphySize(new String[] {"abc", "480", "270"}), "sized Giphy");
        check(!CommentMedia.hasGiphySize(new String[] {"abc", "wide", "270"}), "non-numeric Giphy width");
        check(!CommentMedia.hasGiphySize(new String[] {"abc", "480", "0"}), "zero Giphy height");
        check(!CommentMedia.hasGiphySize(new String[] {"", "480", "270"}), "empty Giphy ID");
        check(!CommentMedia.hasGiphySize(new String[] {"abc", "480"}), "two-part Giphy marker");
        check(!CommentMedia.hasGiphySize(new String[] {"abc", "1", "2", "3"}), "four-part Giphy marker");
        check(!CommentMedia.hasGiphySize(null), "missing Giphy marker");

        // Public entry points never throw, even without Android display metrics.
        check(CommentMedia.inlineImageSize((String) null, 100, true) == null, "null URL");
        check(CommentMedia.inlineImageSize(ordered, 1000, true) != null, "JVM without display metrics still sizes");

        probeChecks();

        CommentMedia.clearMetadata();
        System.out.println("PASS: " + checks + " comment media checks (loopback GIF probes; no device rendering)");
    }

    private static byte[] gif(String version, int width, int height, int extra) {
        byte[] bytes = new byte[10 + extra];
        System.arraycopy(("GIF" + version).getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 6);
        bytes[6] = (byte) width;
        bytes[7] = (byte) (width >> 8);
        bytes[8] = (byte) height;
        bytes[9] = (byte) (height >> 8);
        return bytes;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** Real HTTP against a loopback server; no Giphy or Reddit requests. */
    private static void probeChecks() throws Exception {
        // Header parsing.
        check(Arrays.equals(CommentMedia.gifSize(gif("89a", 480, 270, 0)), new int[] {480, 270}), "GIF89a header");
        check(Arrays.equals(CommentMedia.gifSize(gif("87a", 65535, 1, 0)), new int[] {65535, 1}), "GIF87a little-endian maximum");
        check(CommentMedia.gifSize(gif("88a", 10, 10, 0)) == null, "unknown GIF version");
        check(CommentMedia.gifSize(gif("89a", 0, 10, 0)) == null, "zero width");
        check(CommentMedia.gifSize(new byte[] {'G', 'I', 'F'}) == null, "short header");
        check(CommentMedia.gifSize(null) == null, "missing header");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};
        check(CommentMedia.gifSize(png) == null, "PNG is not a GIF");
        check("abc".equals(CommentMedia.giphyKeyId("giphy|abc")) && "abc".equals(CommentMedia.giphyKeyId("giphy|abc|downsized")),
                "media_metadata Giphy keys");
        check(CommentMedia.giphyKeyId("giphy|") == null && CommentMedia.giphyKeyId("giphy|a/b") == null, "bad Giphy keys");

        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        Map<String, String> ranges = new ConcurrentHashMap<>();
        AtomicInteger credentialed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/media/", exchange -> {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String id = parts.length > 2 ? parts[2] : "";
            hits.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) ranges.put(id, range);
            if (exchange.getRequestHeaders().containsKey("Cookie") || exchange.getRequestHeaders().containsKey("Authorization")) {
                credentialed.incrementAndGet();
            }
            int status = 206;
            byte[] body;
            switch (id) {
                case "FULL1": status = 200; body = gif("87a", 320, 240, 5000); break;
                case "PNG1": body = png; break;
                case "ZERO1": body = gif("89a", 0, 0, 0); break;
                case "SHORT1": body = new byte[] {'G', 'I', 'F', '8', '9'}; break;
                case "MISSING1": status = 404; body = new byte[0]; break;
                case "SLOW1":
                    try { Thread.sleep(CommentMedia.PROBE_TIMEOUT_MS + 1500); } catch (InterruptedException ignored) { }
                    body = gif("89a", 10, 10, 0); break;
                default: body = gif("89a", 480, 270, 0);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CommentMedia.giphyMediaBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/media/";
            CommentMedia.clearProbes();

            // Direct probes.
            check(CommentMedia.probeGiphy("GIFOK1"), "partial-content probe");
            check("bytes=0-9".equals(ranges.get("GIFOK1")), "only the 10-byte header is requested");
            box(CommentMedia.inlineImageSize("https://media.giphy.com/media/GIFOK1/giphy.gif", 1000, 0f, true),
                    1000, 563, "probed Giphy sized");
            box(CommentMedia.inlineImageSize("https://giphy.com/gifs/some-title-GIFOK1", 400, 0f, true),
                    400, 225, "probed Giphy page link sized");
            check(CommentMedia.giphyMediaUrl("GIFOK1").equals(CommentMedia.displayUrl("https://giphy.com/gifs/some-title-GIFOK1")),
                    "page link draws the GIF file");
            none(CommentMedia.inlineImageSize("https://media.giphy.com/media/GIFOK1/giphy.gif", 1000, 0f, false),
                    "animated setting off still keeps the card");
            check(CommentMedia.probeGiphy("FULL1"), "server ignoring Range still sized from the first bytes");
            box(CommentMedia.inlineImageSize("https://media.giphy.com/media/FULL1/giphy.gif", 1000, 0f, true),
                    1000, 750, "full-response probe sized");
            for (String bad : new String[] {"PNG1", "ZERO1", "SHORT1", "MISSING1", "SLOW1"}) {
                check(!CommentMedia.probeGiphy(bad), "failed probe: " + bad);
                none(CommentMedia.inlineImageSize("https://media.giphy.com/media/" + bad + "/giphy.gif", 1000, 0f, true),
                        "failed probe keeps the card: " + bad);
            }
            check(credentialed.get() == 0, "probes send no cookies or authorization");

            // Failures and successes are not probed again.
            CommentMedia.settingsOverride = Boolean.TRUE;
            int before = hits.get("PNG1").get() + hits.get("GIFOK1").get();
            CommentMedia.requestGiphySize("PNG1");
            CommentMedia.requestGiphySize("GIFOK1");
            Thread.sleep(300);
            check(hits.get("PNG1").get() + hits.get("GIFOK1").get() == before, "known results are not re-probed");

            // media_metadata marks comment GIFs invalid: probe in the background, once.
            JSONObject invalid = new JSONObject().put("giphy|ASYNC1", new JSONObject().put("status", "invalid"));
            for (int i = 0; i < 5; i++) CommentMedia.rememberMetadata(invalid);
            check(waitFor(() -> CommentMedia.inlineImageSize("https://media.giphy.com/media/ASYNC1/giphy.gif", 1000, 0f, true) != null),
                    "background probe completes");
            check(hits.get("ASYNC1").get() == 1, "concurrent requests for one GIF are de-duplicated: " + hits.get("ASYNC1"));

            // A GIF first seen while drawing is probed for the next bind.
            String render = "https://giphy.com/gifs/title-RENDER1";
            none(CommentMedia.inlineImageSize(render, 1000, 0f, true), "first draw keeps the card");
            check(waitFor(() -> CommentMedia.inlineImageSize(render, 1000, 0f, true) != null), "next draw is sized");

            // Settings off: nothing is requested.
            CommentMedia.settingsOverride = Boolean.FALSE;
            CommentMedia.rememberMetadata(new JSONObject().put("giphy|NOPE1", new JSONObject().put("status", "invalid")));
            none(CommentMedia.inlineImageSize("https://media.giphy.com/media/NOPE2/giphy.gif", 1000, 0f, true), "no probe when off");
            Thread.sleep(300);
            check(!hits.containsKey("NOPE1") && !hits.containsKey("NOPE2"), "settings off sends no requests");

            // Outside Sync, settings cannot be read, so probing is off.
            CommentMedia.settingsOverride = null;
            check(!CommentMedia.probesAllowed(), "unknown settings disable probing");
        } finally {
            server.stop(0);
            CommentMedia.settingsOverride = Boolean.FALSE;
            CommentMedia.clearProbes();
        }
    }
}
