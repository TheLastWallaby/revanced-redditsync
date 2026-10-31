package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline and loopback checks for comment video links, manifests and poster caching. */
public final class CommentVideoTest {
    private static int checks;

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    /** Reddit's manifest for v.redd.it/zj9xk97frsyh1 (a 3 s, 480x480 comment clip), fetched 2026-10-31. */
    static final String REDDIT_MPD = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" mediaPresentationDuration=\"PT3.066667S\" minBufferTime=\"PT4S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" xsi:schemaLocation=\"urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd\">\n"
            + "  <Period duration=\"PT3.066667S\" id=\"0\">\n"
            + "    <AdaptationSet contentType=\"video\" id=\"0\" maxFrameRate=\"15360/512\" maxHeight=\"480\" maxWidth=\"480\" par=\"1:1\" sar=\"1:1\" segmentAlignment=\"true\" startWithSAP=\"1\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">\n"
            + "      <Representation bandwidth=\"110283\" codecs=\"avc1.4d401e\" frameRate=\"15360/512\" height=\"220\" id=\"0\" mimeType=\"video/mp4\" width=\"220\">\n"
            + "        <BaseURL>CMAF_220.mp4</BaseURL>\n"
            + "        <SegmentBase indexRange=\"870-913\" timescale=\"15360\">\n"
            + "          <Initialization range=\"0-869\" />\n"
            + "        </SegmentBase>\n"
            + "      </Representation>\n"
            + "      <Representation bandwidth=\"210854\" codecs=\"avc1.4d401e\" frameRate=\"15360/512\" height=\"270\" id=\"1\" mimeType=\"video/mp4\" width=\"270\">\n"
            + "        <BaseURL>CMAF_270.mp4</BaseURL>\n"
            + "        <SegmentBase indexRange=\"871-914\" timescale=\"15360\">\n"
            + "          <Initialization range=\"0-870\" />\n"
            + "        </SegmentBase>\n"
            + "      </Representation>\n"
            + "      <Representation bandwidth=\"405345\" codecs=\"avc1.4d401e\" frameRate=\"15360/512\" height=\"360\" id=\"2\" mimeType=\"video/mp4\" width=\"360\">\n"
            + "        <BaseURL>CMAF_360.mp4</BaseURL>\n"
            + "        <SegmentBase indexRange=\"871-914\" timescale=\"15360\">\n"
            + "          <Initialization range=\"0-870\" />\n"
            + "        </SegmentBase>\n"
            + "      </Representation>\n"
            + "      <Representation bandwidth=\"590972\" codecs=\"avc1.4d401f\" frameRate=\"15360/512\" height=\"480\" id=\"3\" mimeType=\"video/mp4\" width=\"480\">\n"
            + "        <BaseURL>CMAF_480.mp4</BaseURL>\n"
            + "        <SegmentBase indexRange=\"870-913\" timescale=\"15360\">\n"
            + "          <Initialization range=\"0-869\" />\n"
            + "        </SegmentBase>\n"
            + "      </Representation>\n"
            + "    </AdaptationSet>\n"
            + "  </Period>\n"
            + "</MPD>\n";

    /** Records platform calls; makePoster writes a small file and returns a configured size. */
    static final class FakePlatform implements CommentVideo.Platform {
        volatile boolean previews = true;
        volatile File directory;
        volatile int[] posterSize = {720, 405};
        volatile int[] diskSize;
        final AtomicInteger posters = new AtomicInteger();
        volatile long lastVideoBytes = -1;
        volatile int lastNativeWidth = -1;

        @Override public boolean previewsEnabled() { return previews; }
        @Override public File posterDirectory() { return directory; }
        @Override public int[] imageSize(File poster) { return diskSize; }
        @Override public float density() { return 2f; }
        final AtomicInteger placeholders = new AtomicInteger();
        volatile boolean placeholderWorks = true;
        @Override public boolean makePlaceholder(File placeholder, int width, int height) {
            placeholders.incrementAndGet();
            if (!placeholderWorks) return false;
            try {
                Files.write(placeholder.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) width, (byte) height});
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        final AtomicInteger fitted = new AtomicInteger();
        @Override public boolean makeFitted(File poster, File out, int width, int height) {
            fitted.incrementAndGet();
            if (!poster.isFile()) return false;
            try {
                Files.write(out.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) width, (byte) height});
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        /** Runs inline; the app posts to Sync's main thread. */
        @Override public void post(Runnable runnable) { runnable.run(); }
        @Override public int[] makePoster(File video, File poster, int nativeWidth) {
            posters.incrementAndGet();
            lastVideoBytes = video.length();
            lastNativeWidth = nativeWidth;
            if (posterSize == null) return null;
            try {
                Files.write(poster.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3});
            } catch (Exception e) {
                return null;
            }
            return posterSize;
        }
    }

    public static void main(String[] args) throws Exception {
        FakePlatform platform = new FakePlatform();
        CommentVideo.platform = platform;
        CommentVideo.videoBase = "http://127.0.0.1:9/unused/";
        CommentVideo.clear();
        File root = Files.createTempDirectory("comment-video-test").toFile();
        try {
            links();
            manifests();
            drawing();
            settingsAndFallbacks(platform, root);
            loopback(platform, root);
            placeholders(platform, root);
            pruning(root);
        } finally {
            deleteTree(root);
        }
        System.out.println("CommentVideoTest passed " + checks + " checks");
    }

    // ------------------------------------------------------------------ links

    private static void links() {
        String id = "zj9xk97frsyh1";
        String[] valid = {
            "https://reddit.com/link/19rxhc8m35b4x/video/zj9xk97frsyh1/player",
            "https://www.reddit.com/link/19rxhc8m35b4x/video/zj9xk97frsyh1/player/",
            "https://old.reddit.com/link/19rxhc8m35b4x/video/zj9xk97frsyh1",
            "http://new.reddit.com/link/abc12/video/ZJ9XK97FRSYH1/player?utm_source=share#t=1",
            "https://m.reddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://np.reddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://sh.reddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "HTTPS://WWW.Reddit.COM./link/abc12/video/zj9xk97frsyh1/player",
            "https://v.redd.it/zj9xk97frsyh1",
            "https://v.redd.it/zj9xk97frsyh1/",
        };
        for (String url : valid) {
            check(id.equals(CommentVideo.mediaId(url)), "video link: " + url);
            check(CommentVideo.isVideoLink(url), "isVideoLink: " + url);
        }
        String[] invalid = {
            null, "", "reddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "javascript:alert('https://reddit.com/link/abc12/video/zj9xk97frsyh1/player')",
            "ftp://reddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://example.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://reddit.com.example.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://evilreddit.com/link/abc12/video/zj9xk97frsyh1/player",
            "https://i.redd.it/link/abc12/video/zj9xk97frsyh1/player",
            "https://reddit.com/link/abc12/video/zj9xk97frsyh1/embed",
            "https://reddit.com/link/abc12/video/zj9xk97frsyh1/player/extra",
            "https://reddit.com/link/abc12/image/zj9xk97frsyh1/player",
            "https://reddit.com/r/pics/link/abc12/video/zj9xk97frsyh1/player",
            "https://reddit.com/link//video/zj9xk97frsyh1/player",
            "https://reddit.com/link/abc12/video//player",
            "https://reddit.com/link/a-b12/video/zj9xk97frsyh1/player",
            "https://reddit.com/link/abc12/video/zj9x.97frsyh1/player",
            "https://reddit.com/link/abc12/video/abcd/player",
            "https://reddit.com/link/abc12/video/" + "a".repeat(33) + "/player",
            "https://reddit.com/link/abc12/video/zj9xk97frsyh١/player",
            "https://v.redd.it/zj9xk97frsyh1/DASHPlaylist.mpd",
            "https://v.redd.it/zj9xk97frsyh1/CMAF_480.mp4",
            "https://v.redd.it/",
            "https://v.redd.it//zj9xk97frsyh1",
            "https://reddit.com/r/pics/comments/abc123/title/",
        };
        for (String url : invalid) {
            check(CommentVideo.mediaId(url) == null, "not a video link: " + url);
            check(!CommentVideo.isVideoLink(url), "isVideoLink false: " + url);
        }
        // User info and ports cannot change where the rebuilt URL points.
        check(id.equals(CommentVideo.mediaId("https://user:pw@reddit.com:8443/link/abc12/video/zj9xk97frsyh1/player")),
                "user info and port are ignored; output uses a fixed origin");

        // Tap mapping: only the /link/ form changes, always to the fixed HTTPS video host.
        check(("https://v.redd.it/" + id).equals(CommentVideo.tapUrl(valid[0])), "link maps to v.redd.it");
        check(("https://v.redd.it/" + id).equals(CommentVideo.tapUrl(valid[3])), "query, fragment, case and http normalized");
        check(("https://v.redd.it/" + id).equals(
                CommentVideo.tapUrl("https://user@reddit.com:1/link/abc12/video/zj9xk97frsyh1/player")), "user info dropped");
        for (String url : new String[] {null, "", "https://v.redd.it/zj9xk97frsyh1", "https://v.redd.it/zj9xk97frsyh1/",
                "https://www.reddit.com/r/pics/comments/abc123/x/", "https://reddit.com/link/abc12/video/abcd/player",
                "sync-settings://3", "https://example.com/link/abc12/video/zj9xk97frsyh1/player", "/r/pics"}) {
            check(url == CommentVideo.tapUrl(url), "unchanged tap URL: " + url);
        }
    }

    // --------------------------------------------------------------- manifest

    private static void manifests() {
        List<CommentVideo.Rendition> reddit = CommentVideo.renditions(REDDIT_MPD);
        check(reddit.size() == 4, "four Reddit renditions, got " + reddit.size());
        check(reddit.get(0).width == 220 && reddit.get(0).height == 220 && reddit.get(0).bandwidth == 110283
                && "CMAF_220.mp4".equals(reddit.get(0).base), "first Reddit rendition parsed");
        check("CMAF_360.mp4".equals(CommentVideo.choose(reddit).base), "smallest rendition with a 360 px short side");
        check("CMAF_480.mp4".equals(CommentVideo.largest(reddit).base), "largest rendition gives native width");

        // Older DASH_ names, MIME types on the AdaptationSet, namespace prefixes, audio and
        // single-quoted attributes.
        String older = "<?xml version='1.0'?><mpd:MPD xmlns:mpd='urn:mpeg:dash:schema:mpd:2011'><mpd:Period>"
                + "<mpd:AdaptationSet mimeType='video/mp4'>"
                + "<mpd:Representation width='1920' height='1080' bandwidth='4800000'><mpd:BaseURL>DASH_1080.mp4</mpd:BaseURL></mpd:Representation>"
                + "<mpd:Representation width='1280' height='720' bandwidth='2400000'><mpd:BaseURL> DASH_720.mp4 </mpd:BaseURL></mpd:Representation>"
                + "<mpd:Representation width='640' height='360' bandwidth='800000'><mpd:BaseURL>DASH_360.mp4</mpd:BaseURL></mpd:Representation>"
                + "<mpd:Representation width='426' height='240'><mpd:BaseURL>DASH_240.mp4</mpd:BaseURL></mpd:Representation>"
                + "</mpd:AdaptationSet><mpd:AdaptationSet mimeType='audio/mp4'>"
                + "<mpd:Representation bandwidth='128000'><mpd:BaseURL>DASH_audio.mp4</mpd:BaseURL></mpd:Representation>"
                + "</mpd:AdaptationSet></mpd:Period></mpd:MPD>";
        List<CommentVideo.Rendition> list = CommentVideo.renditions(older);
        check(list.size() == 4, "audio skipped, four video renditions");
        check("DASH_720.mp4".equals(list.get(1).base), "BaseURL whitespace trimmed");
        check("DASH_360.mp4".equals(CommentVideo.choose(list).base), "cheapest 360p rendition chosen");
        check(CommentVideo.largest(list).width == 1920, "native width from largest");

        // Vertical video: the short side is the width.
        String vertical = "<MPD><AdaptationSet contentType=\"video\">"
                + "<Representation width=\"1080\" height=\"1920\" bandwidth=\"5000000\"><BaseURL>CMAF_1080.mp4</BaseURL></Representation>"
                + "<Representation width=\"360\" height=\"640\" bandwidth=\"700000\"><BaseURL>CMAF_640.mp4</BaseURL></Representation>"
                + "<Representation width=\"270\" height=\"480\" bandwidth=\"400000\"><BaseURL>CMAF_480.mp4</BaseURL></Representation>"
                + "</AdaptationSet></MPD>";
        check("CMAF_640.mp4".equals(CommentVideo.choose(CommentVideo.renditions(vertical)).base), "vertical uses short side");

        // Only small renditions: the largest is used.
        String small = "<MPD><AdaptationSet contentType='video'>"
                + "<Representation width='220' height='220' bandwidth='1'><BaseURL>a.mp4</BaseURL></Representation>"
                + "<Representation width='270' height='270' bandwidth='2'><BaseURL>b.mp4</BaseURL></Representation>"
                + "</AdaptationSet></MPD>";
        check("b.mp4".equals(CommentVideo.choose(CommentVideo.renditions(small)).base), "largest when none reaches 360");

        // Unsafe or unsupported file names never become a request.
        String[] bad = {"https://evil.example/a.mp4", "//evil.example/a.mp4", "../a.mp4", "a/b.mp4", "a.mp4?x=1",
                "a.mp4&amp;b", "a.m3u8", "", ".mp4x", "a..mp4", "%2e%2e.mp4", "a b.mp4", "-" + "a".repeat(70) + ".mp4"};
        for (String base : bad) {
            String mpd = "<MPD><AdaptationSet contentType='video'><Representation width='640' height='360'>"
                    + "<BaseURL>" + base + "</BaseURL></Representation></AdaptationSet></MPD>";
            check(CommentVideo.renditions(mpd).isEmpty(), "rejected BaseURL: " + base);
        }
        // Missing sizes, non-video and malformed input.
        check(CommentVideo.renditions("<MPD><AdaptationSet contentType='video'><Representation bandwidth='5'>"
                + "<BaseURL>a.mp4</BaseURL></Representation></AdaptationSet></MPD>").isEmpty(), "rendition without size");
        check(CommentVideo.renditions("<MPD><AdaptationSet contentType='audio'><Representation width='1' height='1'>"
                + "<BaseURL>a.mp4</BaseURL></Representation></AdaptationSet></MPD>").isEmpty(), "contentType audio");
        check(CommentVideo.renditions("<MPD><Representation width='640' height='360' mimeType='text/vtt'>"
                + "<BaseURL>a.mp4</BaseURL></Representation></MPD>").isEmpty(), "subtitle MIME");
        check(CommentVideo.renditions("<MPD><Representation width='640' height='360'><BaseURL>a.mp4</BaseURL></Representation></MPD>")
                .size() == 1, "sized representation without MIME counts as video");
        check(CommentVideo.renditions("<MPD><Representation width='640' height='360'/><BaseURL>a.mp4</BaseURL></MPD>").isEmpty(),
                "self-closing representation has no file");
        check(CommentVideo.renditions("<MPD><Representation width='-640' height='360'><BaseURL>a.mp4</BaseURL></Representation></MPD>")
                .isEmpty(), "negative width");
        check(CommentVideo.renditions("<html>not a manifest</html>").isEmpty(), "HTML page");
        check(CommentVideo.renditions("<MPD><Representation width='640' height='360'><BaseURL>a.mp4").isEmpty(), "truncated");
        check(CommentVideo.choose(CommentVideo.renditions("")) == null, "no rendition, no choice");
    }

    // ----------------------------------------------------------------- drawing

    private static void drawing() {
        check(Arrays.equals(new int[] {480, 480}, CommentVideo.posterDimensions(360, 360, 480)), "upscaled to native width");
        check(Arrays.equals(new int[] {720, 1280}, CommentVideo.posterDimensions(360, 640, 1080)), "vertical capped at 720 wide");
        check(Arrays.equals(new int[] {220, 220}, CommentVideo.posterDimensions(220, 220, 220)), "small native video stays small");
        check(Arrays.equals(new int[] {720, 405}, CommentVideo.posterDimensions(1920, 1080, 1920)), "large frame scaled down");
        check(Arrays.equals(new int[] {480, 270}, CommentVideo.posterDimensions(480, 270, -1)), "unknown native width uses frame");
        check(CommentVideo.posterDimensions(0, 10, 10) == null && CommentVideo.posterDimensions(10, 0, 10) == null, "empty frame");
        check(CommentVideo.posterDimensions(1, 200_000, 720) == null, "absurd height rejected");
        check(Math.abs(CommentVideo.badgeRadius(720, 405) - 48.6f) < 0.001f, "badge is 12% of short side");
        check(CommentVideo.badgeRadius(50, 50) == 12f, "badge has a minimum");
        check(CommentVideo.badgeRadius(16, 300) == 8f, "badge stays inside a thin poster");
    }

    // ------------------------------------------------- settings and fallbacks

    private static void settingsAndFallbacks(FakePlatform platform, File root) throws Exception {
        String link = "https://reddit.com/link/abc12/video/aaaaa11111/player";
        CommentVideo.clear();
        platform.directory = new File(root, "settings");
        platform.previews = false;
        check(CommentVideo.posterSize(link, 1000) == null, "Inline image previews off keeps the link");
        platform.previews = true;
        check(CommentVideo.posterSize("https://example.com/a.png", 1000) == null, "non-video link ignored");
        check(CommentVideo.posterSize(link, 0) == null && CommentVideo.posterSize(link, -5) == null, "no reply width");
        check(CommentVideo.posterSize(null, 1000) == null, "null link");
        check(CommentVideo.posterUrl(null, 100, 100).isEmpty(), "posterUrl never returns null");

        // A poster saved by an earlier session is used without any network request.
        File directory = new File(root, "disk");
        check(directory.mkdirs(), "disk directory");
        platform.directory = directory;
        Files.write(new File(directory, "bbbbb22222.jpg").toPath(), new byte[] {1});
        platform.diskSize = new int[] {640, 360};
        int[] box = CommentVideo.posterSize("https://v.redd.it/bbbbb22222", 1000);
        check(box != null && box[0] == 1000 && box[1] == 563, "disk poster sized: " + Arrays.toString(box));
        check(new File(directory, "bbbbb22222.jpg").getAbsolutePath().equals(
                CommentVideo.posterUrl("https://reddit.com/link/x1234/video/bbbbb22222/player", 1000, 563)),
                "posterUrl is the file path");
        // Small native size is not stretched past its density-scaled width (220 * 2 = 440).
        Files.write(new File(directory, "ccccc33333.jpg").toPath(), new byte[] {1});
        platform.diskSize = new int[] {220, 220};
        box = CommentVideo.posterSize("https://v.redd.it/ccccc33333", 1000);
        check(box != null && box[0] == 440 && box[1] == 440, "small poster box: " + Arrays.toString(box));
        // Unreadable or absurd saved posters are ignored.
        Files.write(new File(directory, "ddddd44444.jpg").toPath(), new byte[] {1});
        platform.diskSize = new int[] {0, 10};
        check(CommentVideo.cachedSize("ddddd44444") == null, "zero-width disk poster ignored");
        platform.diskSize = new int[] {1, 1, 1};
        Files.write(new File(directory, "eeeee55555.jpg").toPath(), new byte[] {1});
        check(CommentVideo.cachedSize("eeeee55555") == null, "malformed size ignored");
        platform.diskSize = null;
        // No directory: nothing is drawn and nothing throws.
        CommentVideo.clear();
        platform.directory = null;
        check(CommentVideo.posterSize("https://v.redd.it/fffff66666", 1000) == null, "no cache directory");
        check(!CommentVideo.generate("fffff66666"), "generate without directory fails");
        check(CommentVideo.posterUrl("https://v.redd.it/fffff66666", 100, 100).equals("https://v.redd.it/fffff66666"),
                "posterUrl falls back to the link");
    }

    // ---------------------------------------------------------------- loopback

    private static void loopback(FakePlatform platform, File root) throws Exception {
        CommentVideo.clear();
        File directory = new File(root, "posters");
        platform.directory = directory;
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        List<String> ranges = new CopyOnWriteArrayList<>();
        List<String> leaked = new CopyOnWriteArrayList<>();
        byte[] video = new byte[3 * 1024 * 1024];
        Arrays.fill(video, (byte) 7);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            for (String header : new String[] {"Cookie", "Authorization"}) {
                if (exchange.getRequestHeaders().containsKey(header)) leaked.add(header + " " + path);
            }
            byte[] body;
            int status = 200;
            if (path.equals("/good1234/DASHPlaylist.mpd") || path.equals("/async1234/DASHPlaylist.mpd")
                    || path.equals("/nopost1234/DASHPlaylist.mpd")) {
                body = REDDIT_MPD.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/CMAF_360.mp4")) {
                String range = exchange.getRequestHeaders().getFirst("Range");
                ranges.add(String.valueOf(range));
                body = video; // Ignores Range, as a misbehaving server might.
            } else if (path.equals("/redirect1234/DASHPlaylist.mpd")) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:9/elsewhere.mpd");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            } else if (path.equals("/huge1234/DASHPlaylist.mpd")) {
                body = new byte[CommentVideo.MAX_MANIFEST_BYTES + 1];
                Arrays.fill(body, (byte) ' ');
            } else if (path.equals("/offsite1234/DASHPlaylist.mpd")) {
                body = ("<MPD><AdaptationSet contentType='video'><Representation width='640' height='360'>"
                        + "<BaseURL>https://evil.example/x.mp4</BaseURL></Representation></AdaptationSet></MPD>")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = "missing".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // Daemon threads, shut down below, so the test JVM can exit.
        ExecutorService serverThreads = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "comment-video-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.start();
        try {
            CommentVideo.videoBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            // Success: manifest, one bounded rendition fetch, poster saved and sized.
            platform.posterSize = new int[] {480, 480};
            check(CommentVideo.generate("good1234"), "poster generated");
            check(hits.get("/good1234/DASHPlaylist.mpd").get() == 1, "manifest fetched once");
            check(hits.get("/good1234/CMAF_360.mp4").get() == 1, "360p rendition fetched");
            check(!hits.containsKey("/good1234/CMAF_480.mp4") && !hits.containsKey("/good1234/CMAF_220.mp4"), "only one rendition");
            check(("bytes=0-" + (CommentVideo.MAX_VIDEO_BYTES - 1)).equals(ranges.get(0)), "Range request: " + ranges);
            check(platform.lastVideoBytes == CommentVideo.MAX_VIDEO_BYTES, "download truncated at the limit");
            check(platform.lastNativeWidth == 480, "native width from the largest rendition");
            check(new File(directory, "good1234.jpg").isFile(), "poster file saved");
            check(!new File(directory, "good1234.part").exists() && !new File(directory, "good1234.tmp").exists(),
                    "partial files removed");
            int[] box = CommentVideo.posterSize("https://reddit.com/link/p1234/video/good1234/player", 600);
            check(box != null && box[0] == 600 && box[1] == 600, "ready poster drawn at reply width: " + Arrays.toString(box));
            check(hits.get("/good1234/DASHPlaylist.mpd").get() == 1, "cached poster makes no request");

            // Failures are remembered and leave the link.
            check(!CommentVideo.generate("missing1234"), "404 manifest");
            check(!CommentVideo.generate("redirect1234"), "redirect refused");
            check(!hits.containsKey("/elsewhere.mpd"), "redirect not followed");
            check(!CommentVideo.generate("huge1234"), "oversized manifest");
            check(!CommentVideo.generate("offsite1234"), "off-site rendition");
            platform.posterSize = null;
            check(!CommentVideo.generate("nopost1234"), "frame decoding failure");
            check(!new File(directory, "nopost1234.jpg").exists(), "no poster after decode failure");
            platform.posterSize = new int[] {480, 480};
            int before = hits.get("/missing1234/DASHPlaylist.mpd").get();
            check(CommentVideo.posterSize("https://v.redd.it/missing1234", 600) == null, "failed video keeps link");
            Thread.sleep(200);
            check(hits.get("/missing1234/DASHPlaylist.mpd").get() == before, "failed video not retried this session");

            // Direct helpers: size limits and status handling.
            String base = CommentVideo.videoBase;
            check(CommentVideo.fetchText(base + "huge1234/DASHPlaylist.mpd", CommentVideo.MAX_MANIFEST_BYTES) == null, "fetch limit");
            check(CommentVideo.fetchText(base + "nothing", 100) == null, "fetch 404");
            File part = new File(root, "small.part");
            check(CommentVideo.download(base + "x/CMAF_360.mp4", part, 1000) && part.length() == 1000, "download limit");
            check(!CommentVideo.download(base + "x/CMAF_999.mp4", part, 1000), "download 404");

            // Background path: first draw queues the poster, a later draw shows it.
            String asyncLink = "https://www.reddit.com/link/q1234/video/async1234/player";
            // Without media_metadata the first draw keeps the link unless the manifest already answered.
            CommentVideo.posterSize(asyncLink, 600);
            int[] ready = null;
            for (int i = 0; i < 100 && ready == null; i++) {
                Thread.sleep(50);
                ready = CommentVideo.posterSize(asyncLink, 600);
            }
            check(ready != null && ready[0] == 600, "poster appears on a later draw");
            check(hits.get("/async1234/DASHPlaylist.mpd").get() == 1, "one background job per video");
            check(leaked.isEmpty(), "no cookies or credentials sent: " + leaked);
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
            CommentVideo.videoBase = "http://127.0.0.1:9/unused/";
        }
    }

    // ------------------------------------------------- metadata and placeholders

    /** Stands in for Sync's inline image span with the method the patch adds. */
    public static final class FakeSpan {
        final List<String> loads = new CopyOnWriteArrayList<>();
        public void syncCompatLoad(String path) { loads.add(path); }
        String last() { return loads.isEmpty() ? null : loads.get(loads.size() - 1); }
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static JSONObject video(String id, int width, int height) {
        return new JSONObject().put(id, new JSONObject().put("status", "valid").put("e", "RedditVideo")
                .put("x", width).put("y", height));
    }

    private static void placeholders(FakePlatform platform, File root) throws Exception {
        CommentVideo.clear();
        File directory = new File(root, "placeholders");
        platform.directory = directory;
        platform.posterSize = new int[] {480, 270};

        // Shapes and keys.
        check(Arrays.equals(new int[] {480, 270}, CommentVideo.placeholderDimensions(1920, 1080)), "16:9 placeholder");
        check(Arrays.equals(new int[] {480, 853}, CommentVideo.placeholderDimensions(1080, 1920)), "9:16 placeholder");
        check(CommentVideo.placeholderDimensions(100, 5) == null && CommentVideo.placeholderDimensions(5, 100) == null,
                "extreme shapes have no placeholder");
        check(CommentVideo.placeholderDimensions(0, 1) == null, "empty shape");
        check("placeholder-480x270".equals(CommentVideo.placeholderKey(640, 360)), "same shape, same placeholder");
        check("abc12-fit-720x720".equals(CommentVideo.fittedKey("abc12", 1000, 1000)), "fitted key");
        check(CommentVideo.sameShape(720, 405, 1000, 563) && !CommentVideo.sameShape(720, 405, 1000, 1000)
                && !CommentVideo.sameShape(0, 1, 1, 1), "shape comparison");
        check(Arrays.equals(new int[] {0, 157, 720, 562}, CommentVideo.fitRect(720, 405, 720, 720)),
                "wide frame letterboxed: " + Arrays.toString(CommentVideo.fitRect(720, 405, 720, 720)));
        check(Arrays.equals(new int[] {246, 0, 474, 405}, CommentVideo.fitRect(405, 720, 720, 405)),
                "tall frame pillarboxed: " + Arrays.toString(CommentVideo.fitRect(405, 720, 720, 405)));

        // media_metadata parsing, with previews off so nothing is fetched.
        platform.previews = false;
        JSONObject metadata = new JSONObject()
                .put("meta12345", new JSONObject().put("status", "valid").put("e", "RedditVideo").put("x", 480).put("y", 270)
                        .put("id", "meta12345").put("isGif", false)
                        .put("dashUrl", "https://v.redd.it/link/abc/asset/meta12345/DASHPlaylist.mpd"))
                .put("source1234", new JSONObject().put("status", "valid").put("e", "RedditVideo")
                        .put("s", new JSONObject().put("x", 1080).put("y", 1920)).put("id", "SOURCE1234"))
                .put("keyonly12", new JSONObject().put("e", "RedditVideo").put("x", 640).put("y", 360))
                .put("failed123", new JSONObject().put("status", "failed").put("e", "RedditVideo").put("x", 640).put("y", 360))
                .put("image1234", new JSONObject().put("status", "valid").put("e", "Image")
                        .put("s", new JSONObject().put("x", 640).put("y", 360)))
                .put("badid", new JSONObject().put("e", "RedditVideo").put("x", 640).put("y", 360).put("id", "bad-id!"))
                .put("nosize123", new JSONObject().put("status", "valid").put("e", "RedditVideo"))
                .put("zero12345", new JSONObject().put("status", "valid").put("e", "RedditVideo").put("x", 0).put("y", 360))
                .put("text", "not an object");
        CommentVideo.rememberMetadata(metadata);
        CommentVideo.rememberMetadata(null);
        check(Arrays.equals(new int[] {480, 270}, CommentVideo.videoSize("meta12345")), "x/y size recorded");
        check(Arrays.equals(new int[] {1080, 1920}, CommentVideo.videoSize("source1234")), "s.x/s.y size, ID lowercased");
        check(Arrays.equals(new int[] {640, 360}, CommentVideo.videoSize("keyonly12")), "ID from the key");
        for (String id : new String[] {"failed123", "image1234", "badid", "nosize123", "zero12345"}) {
            check(CommentVideo.videoSize(id) == null, "no size for " + id);
        }

        AtomicInteger metaRequests = new AtomicInteger();
        AtomicInteger failRequests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if (path.equals("/meta12345/DASHPlaylist.mpd")) {
                metaRequests.incrementAndGet();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                body = REDDIT_MPD.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/def12345/DASHPlaylist.mpd")) {
                try {
                    releaseDefault.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                body = REDDIT_MPD.getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/meta12345/CMAF_360.mp4") || path.equals("/def12345/CMAF_360.mp4")) {
                body = new byte[] {1, 2, 3};
            } else {
                if (path.equals("/fail12345/DASHPlaylist.mpd")) failRequests.incrementAndGet();
                status = 404;
                body = new byte[] {'x'};
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ExecutorService serverThreads = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "comment-video-placeholder-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.start();
        try {
            CommentVideo.videoBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Thread.sleep(200);
            check(metaRequests.get() == 0, "previews off: metadata does not prefetch");

            // Previews on: parsing the comment prefetches the poster.
            platform.previews = true;
            CommentVideo.rememberMetadata(video("meta12345", 480, 270));
            check(waitFor(() -> metaRequests.get() == 1), "metadata prefetches the poster");

            // The box is reserved before the poster exists.
            String link = "https://reddit.com/link/p1234/video/meta12345/player";
            int[] box = CommentVideo.posterSize(link, 1000);
            check(box != null && box[0] == 960 && box[1] == 540, "box reserved from metadata: " + Arrays.toString(box));
            String posterPath = new File(directory, "meta12345.jpg").getAbsolutePath();
            String placeholderPath = new File(directory, "placeholder-480x270.jpg").getAbsolutePath();
            check(posterPath.equals(CommentVideo.posterUrl(link, 960, 540)), "before any file, the span gets the poster path");

            // Drawing the span queues the placeholder and loads it in place.
            FakeSpan span = new FakeSpan();
            CommentVideo.track(span, link, 960, 540);
            check(waitFor(() -> placeholderPath.equals(span.last())), "placeholder loaded into the span: " + span.loads);
            check(new File(placeholderPath).isFile(), "placeholder saved");
            check(placeholderPath.equals(CommentVideo.posterUrl(link, 960, 540)), "later spans start with the placeholder");
            check(platform.placeholders.get() == 1, "one placeholder drawn");

            // The poster replaces the placeholder in the same span.
            release.countDown();
            check(waitFor(() -> posterPath.equals(span.last())), "poster loaded into the span: " + span.loads);
            check(posterPath.equals(CommentVideo.posterUrl(link, 960, 540)), "poster path after it is ready");
            box = CommentVideo.posterSize(link, 1000);
            check(box != null && box[0] == 960 && box[1] == 540, "poster keeps the reserved shape");
            int loads = span.loads.size();

            // A span drawn after the poster is ready starts with it and is not tracked; earlier spans are released.
            FakeSpan late = new FakeSpan();
            CommentVideo.track(late, link, 960, 540);
            check(late.loads.isEmpty(), "late span already had the poster: " + late.loads);
            check(span.loads.size() == loads, "finished spans are not reloaded");

            // Failure: the span keeps the placeholder (dark frame with play badge), never a missing poster.
            CommentVideo.rememberMetadata(video("fail12345", 640, 360));
            String failLink = "https://v.redd.it/fail12345";
            int[] failBox = CommentVideo.posterSize(failLink, 1000);
            check(failBox != null && failBox[0] == 1000 && failBox[1] == 563, "fail box: " + Arrays.toString(failBox));
            check(placeholderPath.equals(CommentVideo.posterUrl(failLink, 1000, 563)), "same-shaped placeholder reused");
            FakeSpan failed = new FakeSpan();
            CommentVideo.track(failed, failLink, 1000, 563);
            check(waitFor(() -> failRequests.get() == 1), "failing poster attempted");
            Thread.sleep(200);
            for (String path : failed.loads) check(placeholderPath.equals(path), "failed video keeps the placeholder: " + failed.loads);
            check(platform.placeholders.get() == 1, "placeholder not redrawn for the same shape");
            check(CommentVideo.posterSize(failLink, 1000) != null, "failed video keeps its reserved box");

            // Waiting spans are capped, and spans without the load method are ignored.
            CommentVideo.rememberMetadata(video("many12345", 1080, 1920));
            FakeSpan[] spans = new FakeSpan[CommentVideo.MAX_WAITING_SPANS + 3];
            for (int i = 0; i < spans.length; i++) {
                spans[i] = new FakeSpan();
                CommentVideo.track(spans[i], "https://v.redd.it/many12345", 844, 1500);
            }
            CommentVideo.track(new Object(), "https://v.redd.it/many12345", 844, 1500);
            CommentVideo.track(null, "https://v.redd.it/many12345", 844, 1500);
            CommentVideo.track(new FakeSpan(), "https://example.com/not-a-video", 844, 1500);
            CommentVideo.track(new FakeSpan(), "https://v.redd.it/many12345", 0, 1500);
            String tall = new File(directory, "placeholder-480x853.jpg").getAbsolutePath();
            check(waitFor(() -> tall.equals(spans[0].last())), "tall placeholder loaded");
            int loaded = 0;
            for (FakeSpan each : spans) if (tall.equals(each.last())) loaded++;
            check(loaded == CommentVideo.MAX_WAITING_SPANS, "waiting spans capped at "
                    + CommentVideo.MAX_WAITING_SPANS + ", got " + loaded);
            CommentVideo.load(new Object(), "x");
            CommentVideo.load("not a span", "x");

            // A placeholder that cannot be drawn leaves Sync's own loading image.
            CommentVideo.clear();
            platform.placeholderWorks = false;
            CommentVideo.rememberMetadata(video("odd123456", 300, 100));
            FakeSpan odd = new FakeSpan();
            CommentVideo.track(odd, "https://v.redd.it/odd123456", 600, 200);
            Thread.sleep(300);
            check(odd.loads.isEmpty(), "no load without a placeholder or poster: " + odd.loads);
            platform.placeholderWorks = true;

            // No size in media_metadata: the first draw reserves the default square box.
            CommentVideo.clear();
            platform.posterSize = new int[] {720, 405};
            String square = "https://reddit.com/link/p1234/video/def12345/player";
            box = CommentVideo.posterSize(square, 1000);
            check(box != null && box[0] == 1000 && box[1] == 1000, "default square box: " + Arrays.toString(box));
            String defPoster = new File(directory, "def12345.jpg").getAbsolutePath();
            String squarePlaceholder = new File(directory, "placeholder-480x480.jpg").getAbsolutePath();
            String fittedPath = new File(directory, "def12345-fit-720x720.jpg").getAbsolutePath();
            check(defPoster.equals(CommentVideo.posterUrl(square, 1000, 1000)), "nothing ready yet");
            FakeSpan first = new FakeSpan();
            CommentVideo.track(first, square, 1000, 1000);
            check(waitFor(() -> squarePlaceholder.equals(first.loads.isEmpty() ? null : first.loads.get(0))),
                    "square placeholder first: " + first.loads);
            releaseDefault.countDown();
            check(waitFor(() -> fittedPath.equals(first.last())), "poster fitted into the square box: " + first.loads);
            check(platform.fitted.get() == 1, "one fitted image");
            check(!first.loads.contains(defPoster), "a wide poster is never stretched into the square box");
            check(fittedPath.equals(CommentVideo.posterUrl(square, 1000, 1000)), "square boxes reuse the fitted image");
            box = CommentVideo.posterSize(square, 1000);
            check(box != null && box[0] == 1000 && box[1] == 563, "later draws use the real shape: " + Arrays.toString(box));
            check(defPoster.equals(CommentVideo.posterUrl(square, 1000, 563)), "real-shape box gets the poster");
            FakeSpan fresh = new FakeSpan();
            CommentVideo.track(fresh, square, 1000, 563);
            check(fresh.loads.isEmpty(), "real-shape span already final");
        } finally {
            release.countDown();
            releaseDefault.countDown();
            server.stop(0);
            serverThreads.shutdownNow();
            CommentVideo.videoBase = "http://127.0.0.1:9/unused/";
            platform.posterSize = new int[] {720, 405};
        }
    }

    // ----------------------------------------------------------------- pruning

    private static void pruning(File root) throws Exception {
        File directory = new File(root, "prune");
        check(directory.mkdirs(), "prune directory");
        long now = System.currentTimeMillis();
        for (int i = 0; i < CommentVideo.MAX_POSTERS + 5; i++) {
            File file = new File(directory, String.format("p%05d.jpg", i));
            Files.write(file.toPath(), new byte[] {1});
            check(file.setLastModified(now - (CommentVideo.MAX_POSTERS + 5 - i) * 1000L), "set time " + i);
        }
        File stale = new File(directory, "old1234.part");
        Files.write(stale.toPath(), new byte[] {1});
        check(stale.setLastModified(now - 3600_000L), "stale time");
        File fresh = new File(directory, "new1234.part");
        Files.write(fresh.toPath(), new byte[] {1});
        CommentVideo.prune(directory);
        String[] left = directory.list((dir, name) -> name.endsWith(".jpg"));
        check(left != null && left.length == CommentVideo.PRUNE_TO, "pruned to " + CommentVideo.PRUNE_TO);
        check(!new File(directory, "p00000.jpg").exists() && new File(directory, String.format("p%05d.jpg",
                CommentVideo.MAX_POSTERS + 4)).exists(), "oldest removed, newest kept");
        check(!stale.exists() && fresh.exists(), "only stale partial files removed");
        // Under the limit nothing is removed.
        CommentVideo.prune(directory);
        left = directory.list((dir, name) -> name.endsWith(".jpg"));
        check(left != null && left.length == CommentVideo.PRUNE_TO, "no further pruning under the limit");
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
