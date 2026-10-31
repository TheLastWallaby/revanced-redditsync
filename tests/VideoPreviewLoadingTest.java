package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Cold-load regression: existing local placeholder, bounded still fast path and MP4 fallback. */
public final class VideoPreviewLoadingTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(CommentVideo.stillSampleSize(720, 405) == 1, "small still uses native resolution");
        check(CommentVideo.stillSampleSize(7680, 4320) == 8, "large still decoded at bounded resolution");
        check(CommentVideo.stillSampleSize(0, 400) == 0, "zero dimension rejected before allocation");
        check(CommentVideo.stillSampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE) == 0, "absurd dimensions rejected");
        check(CommentVideo.stillSampleSize(1, 10000) == 0, "extreme aspect ratio rejected");
        loopback();
        System.out.println("VideoPreviewLoadingTest passed " + checks + " checks");
    }

    private static void loopback() throws Exception {
        File root = Files.createTempDirectory("video-preview-loading").toFile();
        CommentVideoTest.FakePlatform platform = new CommentVideoTest.FakePlatform();
        platform.directory = root;
        platform.stillSize = new int[] {720, 405};
        platform.posterSize = new int[] {720, 405};
        CommentVideo.platform = platform;
        CommentVideo.clear();
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        AtomicInteger leaked = new AtomicInteger();
        AtomicInteger wrongAccept = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] large = new byte[CommentVideo.MAX_STILL_BYTES + 1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (path.endsWith(".webp") && (accept == null || !accept.contains("image/"))) wrongAccept.incrementAndGet();
            if (path.endsWith(".mp4") && !"video/mp4".equals(accept)) wrongAccept.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("Authorization")
                    || exchange.getRequestHeaders().containsKey("Cookie")
                    || exchange.getRequestURI().getRawQuery() != null) leaked.incrementAndGet();
            if (path.equals("/Slow123.webp")) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (path.equals("/Timeout.webp")) {
                try { Thread.sleep(2300); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body = path.startsWith("/Huge123") || path.startsWith("/Chunk12") ? large : new byte[] {1, 2, 3, 4};
            String type = path.endsWith(".webp") ? "image/webp" : "video/mp4";
            int status = 200;
            if (path.equals("/Redir12.webp")) {
                status = 302;
                exchange.getResponseHeaders().set("Location", "/must-not-follow.webp");
            } else if (path.equals("/Limit12.webp")) status = 429;
            else if (path.equals("/Html123.webp")) type = "text/html";
            else if (path.equals("/Part123.webp")) status = 206;
            exchange.getResponseHeaders().set("Content-Type", type);
            // A chunked oversized image exercises the byte cap even with no Content-Length.
            exchange.sendResponseHeaders(status, path.equals("/Chunk12.webp") ? 0 : body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            catch (java.io.IOException ignored) { /* bounded client may close early */ }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "video-preview-loading-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        try {
            CommentVideo.imgurBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            check(CommentVideo.generate("imgur-Quick12"), "small still generates poster");
            check(platform.stillPosters.get() == 1 && platform.posters.get() == 0, "still skips MP4 frame decoder");
            check(hits.size() == 1 && hits.get("/Quick12.webp").get() == 1, "only the still is requested");
            check(Arrays.equals(new int[] {600, 338}, CommentVideo.posterSize("https://i.imgur.com/Quick12.gifv", 600)),
                    "still keeps its native aspect ratio in reply width");
            check(!new File(root, "imgur-Quick12.part").exists() && !new File(root, "imgur-Quick12.tmp").exists(),
                    "still temporaries cleaned up");

            // Invalid/oversized/partial/HTML/redirect responses use the same canonical MP4
            // fallback as rc.1. Rejected bodies never reach the image decoder.
            for (String id : new String[] {"Huge123", "Chunk12", "Html123", "Redir12", "Part123"}) {
                int decodes = platform.stillPosters.get();
                check(CommentVideo.generate("imgur-" + id), "MP4 fallback after rejected still: " + id);
                check(platform.stillPosters.get() == decodes, "rejected still not decoded: " + id);
                check(hits.get("/" + id + ".mp4").get() == 1, "one MP4 fallback: " + id);
            }
            check(!hits.containsKey("/must-not-follow.webp"), "image redirect never followed");
            platform.stillSize = null;
            check(CommentVideo.generate("imgur-BadImg1"), "undecodable still falls back to MP4");
            check(hits.get("/BadImg1.mp4").get() == 1, "decode fallback uses canonical MP4");
            platform.stillSize = new int[] {720, 405};
            check(CommentVideo.generate("imgur-Timeout"), "optional still timeout reaches MP4 fallback");
            check(hits.get("/Timeout.mp4").get() == 1, "one MP4 fallback after timeout");
            platform.stillThrows = true;
            check(CommentVideo.generate("imgur-Throws1"), "optional still decoder exception reaches MP4 fallback");
            check(hits.get("/Throws1.mp4").get() == 1, "one MP4 fallback after decoder exception");
            platform.stillThrows = false;
            check(!CommentVideo.generate("imgur-Limit12"), "429 stops the job");
            check(!hits.containsKey("/Limit12.mp4"), "429 does not trigger another request to the same host");
            check(CommentVideo.posterSize("https://i.imgur.com/Limit12.gifv", 600) == null,
                    "rate-limited item keeps the link on redraw");

            platform.previews = false;
            check(CommentVideo.posterSize("https://i.imgur.com/Off1234.gifv", 600) == null, "previews off keeps card");
            check(!hits.containsKey("/Off1234.webp") && !hits.containsKey("/Off1234.mp4"), "previews off makes no request");
            platform.previews = true;

            String link = "https://i.imgur.com/Slow123.gifv?tracking=fixture#t=1";
            check(Arrays.equals(new int[] {600, 600}, CommentVideo.posterSize(link, 600)), "cold load reserves its box");
            check(entered.await(5, TimeUnit.SECONDS), "still request blocked to simulate slow media");
            String initial = CommentVideo.posterUrl(link, 600, 600);
            File loading = new File(initial);
            check(loading.isFile() && loading.getName().equals(CommentVideo.LOADING_FILE),
                    "first Glide target already exists while the network is blocked");
            check(javax.imageio.ImageIO.read(loading).getRGB(0, 0) == 0xff202020, "neutral clean first image");
            byte[] bytes = Files.readAllBytes(loading.toPath());
            check(bytes.length < 100 && initial.equals(CommentVideo.posterUrl(link, 400, 400)),
                    "tiny first image shared across box sizes");
            CommentVideoTest.FakeSpan span = new CommentVideoTest.FakeSpan();
            CommentVideo.track(span, link, 600, 600);
            String placeholder = new File(root, "placeholder-480x480.jpg").getAbsolutePath();
            check(waitFor(() -> placeholder.equals(span.last())), "clean shaped placeholder loaded");
            int loads = span.loads.size();
            CommentVideo.reload("imgur-Slow123");
            CommentVideo.reload("imgur-Slow123");
            check(span.loads.size() == loads, "same placeholder never reloaded while waiting");
            release.countDown();
            String fitted = new File(root, "imgur-Slow123-fit-720x720.jpg").getAbsolutePath();
            check(waitFor(() -> fitted.equals(span.last())), "still replaces placeholder in the same span");
            check(!hits.containsKey("/Slow123.mp4"), "slow still also skips MP4");
            check(link == CommentVideo.tapUrl(link), "original tap URL preserved");
            check(leaked.get() == 0, "no credentials, cookies or comment query sent");
            check(wrongAccept.get() == 0, "stills and videos request explicit media MIME types");

            // A process-local cache reset reuses the saved clean image; an interrupted zero-byte
            // placeholder write is repaired instead of handed to Glide as a missing image.
            CommentVideo.clear();
            check(initial.equals(CommentVideo.loadingPath(root)), "ready neutral image reused");
            Files.write(loading.toPath(), new byte[0]);
            check(initial.equals(CommentVideo.loadingPath(root)) && Arrays.equals(bytes, Files.readAllBytes(loading.toPath())),
                    "interrupted placeholder repaired atomically");
            File notDirectory = new File(root, "not-a-directory");
            Files.write(notDirectory.toPath(), new byte[] {1});
            check(CommentVideo.loadingPath(notDirectory) == null, "unwritable location fails safely");
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
