package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Exercises Imgur's real poster pipeline with loopback HTTP and a fake Android frame decoder. */
public final class ImgurCommentVideoTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        links();
        loopback();
        System.out.println("ImgurCommentVideoTest passed " + checks + " checks");
    }

    private static void links() {
        String[] valid = {
            "https://i.imgur.com/AbC123x.mp4",
            "https://i.imgur.com/AbC123x.gifv",
            "https://imgur.com/AbC123x.gifv",
            "https://www.imgur.com/AbC123x.gifv?utm_source=share#t=1",
            "HTTP://I.IMGUR.COM./AbC123x.MP4",
            "https://user:pw@i.imgur.com:8443/AbC123x.gifv?x=1",
        };
        for (String url : valid) {
            check("imgur-AbC123x".equals(CommentVideo.mediaId(url)), "case preserved: " + url);
            check(CommentVideo.isVideoLink(url), "supported clip: " + url);
            check(url == CommentVideo.tapUrl(url), "original Imgur tap target retained: " + url);
        }
        check(!CommentVideo.mediaId(valid[0]).equals(CommentVideo.mediaId("https://i.imgur.com/abc123x.mp4")),
                "Imgur IDs differing only by case must not collide");
        check(!CommentVideo.mediaId(valid[0]).equals(CommentVideo.mediaId("https://v.redd.it/abc123x")),
                "Imgur and Reddit cache keys must not collide");
        for (String url : new String[] {
            "https://i.imgur.com/AbC123x.gif", "https://i.imgur.com/AbC123x.jpg",
            "https://i.imgur.com/AbC123x.webp", "https://imgur.com/AbC123x",
            "https://imgur.com/a/AbC123x.gifv", "https://imgur.com/gallery/AbC123x.gifv",
            "https://i.imgur.com/AbC123x.mp4.exe", "https://i.imgur.com/AbC123x.gifv/extra",
            "https://i.imgur.com//AbC123x.mp4", "https://i.imgur.com/a-b_c.mp4",
            "https://i.imgur.com/%41bC123x.mp4", "https://i.imgur.com/abcd.mp4",
            "https://i.imgur.com/" + "A".repeat(33) + ".mp4",
            "https://i.imgur.com/AbC123١.mp4", "https://i.imgur.com/../AbC123x.mp4",
            "https://imgur.com.evil.example/AbC123x.mp4", "https://evilimgur.com/AbC123x.mp4",
            "https://i.imgur.com@evil.example/AbC123x.mp4", "https://example.com/AbC123x.mp4",
            "file:///AbC123x.mp4", "javascript:https://i.imgur.com/AbC123x.mp4",
            "ftp://i.imgur.com/AbC123x.mp4",
        }) {
            check(!CommentVideo.isVideoLink(url), "unsupported link keeps original rendering: " + url);
            check(url == CommentVideo.tapUrl(url), "unsupported tap unchanged: " + url);
        }
    }

    private static void loopback() throws Exception {
        File root = Files.createTempDirectory("imgur-comment-video-test").toFile();
        CommentVideoTest.FakePlatform platform = new CommentVideoTest.FakePlatform();
        platform.directory = root;
        platform.posterSize = new int[] {720, 302};
        CommentVideo.platform = platform;
        CommentVideo.videoBase = "http://127.0.0.1:9/reddit-must-not-be-used/";
        CommentVideo.clear();
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        List<String> ranges = new CopyOnWriteArrayList<>();
        List<String> leaked = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] large = new byte[CommentVideo.MAX_VIDEO_BYTES + 8192];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            if (path.endsWith(".mp4")) ranges.add(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
            for (String header : new String[] {"Cookie", "Authorization"}) {
                if (exchange.getRequestHeaders().containsKey(header)) leaked.add(header);
            }
            if (exchange.getRequestURI().getRawQuery() != null) leaked.add("query");
            if (path.equals("/Wait123.mp4")) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/Redir12.mp4")) {
                exchange.getResponseHeaders().set("Location", "/must-not-follow.mp4");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            boolean missing = path.equals("/Missing.mp4") || path.endsWith(".webp");
            byte[] body = path.equals("/Large12.mp4") ? large : new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'};
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            // Test both an honest Range response and a server that ignores Range.
            if (path.equals("/Range12.mp4")) exchange.getResponseHeaders().set("Content-Range", "bytes 0-7/8");
            exchange.sendResponseHeaders(missing ? 404 : path.equals("/Range12.mp4") ? 206 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (java.io.IOException ignored) {
                // The bounded reader intentionally closes a server's oversized response early.
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "imgur-comment-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        try {
            CommentVideo.imgurBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            check(CommentVideo.generate("imgur-AbC123x"), "direct MP4 poster generated");
            check(hits.get("/AbC123x.mp4").get() == 1 && hits.get("/AbC123x.webp").get() == 1 && hits.size() == 2,
                    "one case-sensitive MP4 fallback after the unavailable WebP still");
            check(platform.lastNativeWidth == 0, "frame decoder supplies Imgur dimensions");
            check(platform.lastVideoBytes == 8, "complete short clip passed to decoder");
            File poster = new File(root, "imgur-AbC123x.jpg");
            check(poster.isFile(), "namespaced poster file saved");
            check(!new File(root, "imgur-AbC123x.part").exists() && !new File(root, "imgur-AbC123x.tmp").exists(),
                    "temporary video and JPEG removed");
            int[] box = CommentVideo.posterSize("https://i.imgur.com/AbC123x.gifv", 600);
            check(Arrays.equals(box, new int[] {600, 252}), "landscape frame fits reply width without stretching");
            check(poster.getAbsolutePath().equals(CommentVideo.posterUrl("https://imgur.com/AbC123x.gifv", 600, 252)),
                    "gifv and mp4 share the poster");
            check(hits.get("/AbC123x.mp4").get() == 1, "cached gifv makes no extra request");

            platform.previews = false;
            check(CommentVideo.posterSize("https://i.imgur.com/Off1234.gifv", 600) == null, "previews off keeps card");
            check(!hits.containsKey("/Off1234.mp4"), "previews off queues no network task");
            check(CommentVideo.posterSize("https://i.imgur.com/AbC123x.mp4", 600) == null, "previews off hides cached frame");
            platform.previews = true;

            check(CommentVideo.generate("imgur-Range12"), "206 partial response accepted");
            check(CommentVideo.generate("imgur-Large12"), "bounded bytes given to decoder if Range ignored");
            check(platform.lastVideoBytes == CommentVideo.MAX_VIDEO_BYTES, "at most 2 MB downloaded");
            check(!CommentVideo.generate("imgur-Missing"), "404 leaves card");
            check(CommentVideo.posterSize("https://i.imgur.com/Missing.gifv", 600) == null, "failed clip stays card");
            check(!CommentVideo.generate("imgur-Redir12"), "redirect refused");
            check(!hits.containsKey("/must-not-follow.mp4"), "redirect target never fetched");
            platform.posterSize = null;
            check(!CommentVideo.generate("imgur-NoFrame"), "undecodable MP4 leaves card");
            check(CommentVideo.posterSize("https://i.imgur.com/NoFrame.mp4", 600) == null, "decode failure fallback");
            check(!new File(root, "imgur-NoFrame.jpg").exists(), "no corrupt cached poster");
            platform.posterSize = new int[] {720, 302};

            // Unknown size reserves a square; both link forms share one queued job. When ready,
            // the first frame is fitted inside that exact box and loaded into the live span.
            String link = "https://i.imgur.com/Wait123.gifv?utm_source=share#t=1";
            check(Arrays.equals(new int[] {600, 600}, CommentVideo.posterSize(link, 600)), "initial square reserved");
            check(entered.await(5, TimeUnit.SECONDS), "background request started");
            CommentVideo.posterSize("https://i.imgur.com/Wait123.mp4", 600);
            CommentVideoTest.FakeSpan span = new CommentVideoTest.FakeSpan();
            CommentVideo.track(span, link, 600, 600);
            release.countDown();
            String fitted = new File(root, "imgur-Wait123-fit-720x720.jpg").getAbsolutePath();
            check(waitFor(() -> fitted.equals(span.last())), "poster swapped into reserved box in place");
            check(hits.get("/Wait123.mp4").get() == 1, "one job across mp4/gifv aliases");
            check(!span.loads.contains(new File(root, "imgur-Wait123.jpg").getAbsolutePath()),
                    "wide poster is never stretched into square");
            box = CommentVideo.posterSize(link, 400);
            check(Arrays.equals(box, new int[] {400, 168}), "subsequent draw uses decoded aspect ratio");
            check(link == CommentVideo.tapUrl(link), "poster tap retains original link and timestamp");

            // Disk cache survives process-local state loss, including the mixed-case source key.
            CommentVideo.clear();
            platform.diskSize = new int[] {720, 302};
            check(Arrays.equals(new int[] {500, 210}, CommentVideo.posterSize("https://i.imgur.com/AbC123x.mp4", 500)),
                    "saved Imgur poster reused next session");
            check(hits.get("/AbC123x.mp4").get() == 1, "disk cache avoids network");
            check(leaked.isEmpty(), "no credentials, cookies or comment query sent to media host: " + leaked);
            check(ranges.stream().allMatch(range -> range.equals("bytes=0-" + (CommentVideo.MAX_VIDEO_BYTES - 1))),
                    "all clip requests bounded with Range");
            check(hits.keySet().stream().allMatch(path -> path.endsWith(".mp4") || path.endsWith(".webp") || path.endsWith("h.jpg")),
                    "only WebP/thumbnail stills and MP4 fallbacks; no GIF or website HTML fetched");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            CommentVideo.imgurBase = "https://i.imgur.com/";
            deleteTree(root);
        }
    }

    private static boolean waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return condition.getAsBoolean();
    }

    private static void deleteTree(File root) {
        File[] files = root.listFiles();
        if (files != null) for (File file : files) deleteTree(file);
        root.delete();
    }
}
