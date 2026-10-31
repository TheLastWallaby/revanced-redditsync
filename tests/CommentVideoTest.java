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
import java.util.concurrent.ExecutorService;
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
        check(CommentVideo.posterUrl(null).isEmpty(), "posterUrl never returns null");

        // A poster saved by an earlier session is used without any network request.
        File directory = new File(root, "disk");
        check(directory.mkdirs(), "disk directory");
        platform.directory = directory;
        Files.write(new File(directory, "bbbbb22222.jpg").toPath(), new byte[] {1});
        platform.diskSize = new int[] {640, 360};
        int[] box = CommentVideo.posterSize("https://v.redd.it/bbbbb22222", 1000);
        check(box != null && box[0] == 1000 && box[1] == 563, "disk poster sized: " + Arrays.toString(box));
        check(new File(directory, "bbbbb22222.jpg").getAbsolutePath().equals(
                CommentVideo.posterUrl("https://reddit.com/link/x1234/video/bbbbb22222/player")), "posterUrl is the file path");
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
        check(CommentVideo.posterUrl("https://v.redd.it/fffff66666").equals("https://v.redd.it/fffff66666"),
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
            check(CommentVideo.posterSize(asyncLink, 600) == null, "first draw keeps the link");
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
