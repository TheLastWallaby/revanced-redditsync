package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exercises live dimensions and the posterUrl/track completion race through the real helper. */
public final class VideoSpanLayoutTest {
    private static int checks;
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    public static final class Span {
        private WeakReference<Object> c;
        volatile int width = 600, height = 600;
        volatile int loadedWidth, loadedHeight;
        volatile String path;
        volatile int loads;
        Span(Object view) { c = view == null ? null : new WeakReference<>(view); }
        public boolean syncCompatResize(int width, int height) {
            if (width <= 0 || height <= 0 || (this.width == width && this.height == height)) return false;
            this.width = width;
            this.height = height;
            return true;
        }
        public void syncCompatLoad(String path) {
            if (c != null) c.clear();
            c = null;
            loadedWidth = width;
            loadedHeight = height;
            loads++;
            this.path = path;
        }
    }

    public static void main(String[] args) throws Exception {
        CommentVideoTest.FakePlatform platform = new CommentVideoTest.FakePlatform();
        CommentVideo.platform = platform;
        Object view = new Object();
        Span span = new Span(view);
        CommentVideo.load(span, "wide.jpg", 600, 338);
        check(span.width == 600 && span.height == 338, "square metrics replaced before drawing");
        check(span.loadedWidth == 600 && span.loadedHeight == 338, "loader sees the new dimensions");
        check(span.c.get() == view, "Glide clear does not lose the bound TextView");
        check(platform.relaidOut.size() == 1 && platform.relaidOut.get(0) == view,
                "dimension change requests a fresh text layout on the bound row");
        check(platform.invalidated.isEmpty(), "metric change uses relayout instead of repaint alone");
        CommentVideo.load(span, "same-shape.jpg", 600, 338);
        check(platform.relaidOut.size() == 1 && platform.invalidated.size() == 1,
                "unchanged dimensions only repaint");
        Object recycled = new Object();
        span.c = new WeakReference<>(recycled);
        CommentVideo.load(span, "portrait.jpg", 338, 600);
        check(platform.relaidOut.get(1) == recycled, "recycled span relayouts its current row");
        Span detached = new Span(null);
        CommentVideo.load(detached, "detached.jpg", 400, 225);
        check(detached.height == 225 && detached.path.equals("detached.jpg"), "detached span updates safely");
        check(platform.relaidOut.size() == 2, "detached span does not retain a row");
        CommentVideo.load(detached, "invalid.jpg", 0, -1);
        check(detached.width == 400 && detached.height == 225, "invalid dimensions leave metrics intact");
        loopback(platform);
        System.out.println("VideoSpanLayoutTest passed " + checks + " checks");
    }

    private static void loopback(CommentVideoTest.FakePlatform platform) throws Exception {
        File root = Files.createTempDirectory("video-span-layout").toFile();
        platform.directory = root;
        platform.posterSize = new int[] {720, 405};
        CommentVideo.clear();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean missing = path.startsWith("/missing123");
            byte[] body = path.endsWith(".mpd") ? CommentVideoTest.REDDIT_MPD.getBytes(StandardCharsets.UTF_8)
                    : new byte[] {1, 2, 3};
            exchange.sendResponseHeaders(missing ? 404 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            CommentVideo.videoBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            // The exact missed-update window: posterUrl returned the neutral image; generation
            // completes before track. Readiness at track must not skip delivery to the span.
            String link = "https://v.redd.it/race12345";
            String initial = CommentVideo.posterUrl(link, 600, 600);
            check(initial.endsWith(CommentVideo.LOADING_FILE), "placeholder selected before poster completion");
            check(CommentVideo.generate("race12345"), "poster finishes before span is registered");
            Object row = new Object();
            Span race = new Span(row);
            CommentVideo.track(race, link, 600, 600);
            String poster = new File(root, "race12345.jpg").getAbsolutePath();
            check(waitFor(() -> poster.equals(race.path)), "already-ready poster replaces the earlier placeholder");
            check(race.width == 600 && race.height == 338, "visible span takes the frame's real aspect ratio");
            check(platform.relaidOut.contains(row), "visible comment remeasured without scrolling");
            check(platform.fitted.get() == 0, "resizable span never creates a letterboxed square image");
            int loads = race.loads;
            CommentVideo.reload("race12345");
            check(race.loads == loads, "finished span is released after one final update");

            check(!CommentVideo.generate("missing123"), "unavailable video generation fails safely");
            Span failed = new Span(row);
            CommentVideo.track(failed, "https://v.redd.it/missing123", 600, 600);
            String compact = new File(root, "placeholder-480x96.jpg").getAbsolutePath();
            check(waitFor(() -> compact.equals(failed.path)), "failed preview gets a ready compact play tile");
            check(failed.width == 600 && failed.height == 120, "failed preview no longer occupies a square");
            check(CommentVideo.posterSize("https://v.redd.it/missing123", 600) == null,
                    "future bindings fall back to Sync's original link card");
            check(CommentVideo.tapUrl(link).equals(link), "existing video tap route unchanged");
            int failures = failed.loads;
            CommentVideo.reload("missing123");
            check(failed.loads == failures, "terminal fallback releases the waiting span");
        } finally {
            server.stop(0);
            CommentVideo.videoBase = "https://v.redd.it/";
            CommentVideo.clear();
        }
    }

    private static boolean waitFor(BooleanSupplier test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!test.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return test.getAsBoolean();
    }
}
