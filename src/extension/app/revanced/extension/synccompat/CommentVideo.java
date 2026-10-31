package app.revanced.extension.synccompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Still-frame previews for Reddit-hosted videos in comments.
 *
 * Reddit writes comment videos as https://reddit.com/link/POST/video/MEDIA/player. Sync 23033
 * sends that link to a browser, where Reddit redirects it to a placeholder post that does not
 * exist. This class maps the link to https://v.redd.it/MEDIA, which Sync's own video viewer
 * opens, and makes a JPEG poster (first frame plus a play badge) that Sync's inline image span
 * draws like a comment picture. Posters are made in the background from the start of the
 * smallest suitable rendition, without cookies or credentials, and cached in the app's cache.
 */
public final class CommentVideo {
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    /** Only the start of the rendition is fetched; Reddit's fragmented MP4 has a frame near the start. */
    static final int MAX_VIDEO_BYTES = 2 * 1024 * 1024;
    /** Smallest rendition whose short side reaches this is used for the poster. */
    static final int MIN_POSTER_SIDE = 360;
    static final int MAX_POSTER_WIDTH = 720;
    static final int MAX_POSTERS = 300;
    static final int PRUNE_TO = 200;
    static final int TIMEOUT_MS = 5000;
    static final int CACHE_SIZE = 512;
    static final String DIRECTORY = "sync-compat-video-posters";

    private static final Set<String> REDDIT_HOSTS = new HashSet<>(Arrays.asList(
            "reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com",
            "m.reddit.com", "np.reddit.com", "sh.reddit.com"));
    private static final String VIDEO_HOST = "v.redd.it";

    /** Tests point this at a loopback server; the app always uses Reddit's HTTPS video host. */
    static String videoBase = "https://v.redd.it/";
    /** Android access (settings, cache directory, frame decoding); tests supply a fake. */
    static Platform platform = new AndroidPlatform();

    private static final Object LOCK = new Object();
    /** Poster sizes by media ID; process-local and bounded. */
    private static final Map<String, int[]> SIZES = lru();
    private static final Map<String, Boolean> FAILED = lru();
    private static final Map<String, Boolean> DISK_CHECKED = lru();
    private static final Set<String> PENDING = new HashSet<>();

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(16), runnable -> {
                Thread thread = new Thread(runnable, "sync-compat-video-poster");
                thread.setDaemon(true);
                return thread;
            }, (runnable, executor) -> {
                // A full queue drops the task; forget it so a later draw can queue it again.
                if (runnable instanceof Task) {
                    synchronized (LOCK) {
                        PENDING.remove(((Task) runnable).id);
                    }
                }
            });

    private CommentVideo() {}

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    interface Platform {
        /** Sync's Inline image previews setting. */
        boolean previewsEnabled();
        /** Directory for poster files, or null when unavailable. */
        File posterDirectory();
        /** Width and height of a saved poster, or null. */
        int[] imageSize(File poster);
        /** Writes a JPEG poster from the video's first frame; returns its size or null. */
        int[] makePoster(File video, File poster, int nativeWidth);
        float density();
    }

    // ------------------------------------------------------------------ links

    /** Media ID for reddit.com/link/POST/video/MEDIA[/player] or v.redd.it/MEDIA, otherwise null. */
    static String mediaId(String href) {
        CommentMedia.Url url = CommentMedia.parse(href);
        if (url == null) return null;
        String[] parts = segments(url.path);
        if (parts == null) return null;
        if (url.host.equals(VIDEO_HOST)) return parts.length == 1 ? validId(parts[0]) : null;
        if (!REDDIT_HOSTS.contains(url.host)) return null;
        if (parts.length != 4 && parts.length != 5) return null;
        if (!parts[0].equals("link") || !parts[2].equals("video")) return null;
        if (parts.length == 5 && !parts[4].equals("player")) return null;
        if (validId(parts[1]) == null) return null;
        return validId(parts[3]);
    }

    /** Path segments, allowing one trailing slash; null for empty segments. */
    private static String[] segments(String path) {
        if (path == null || !path.startsWith("/")) return null;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.substring(1).split("/", -1);
        for (String part : parts) {
            if (part.isEmpty()) return null;
        }
        return parts;
    }

    static String validId(String id) {
        if (id == null || id.length() < 5 || id.length() > 32) return null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return null;
        }
        return id.toLowerCase(Locale.ROOT);
    }

    /** True for a Reddit comment video link. Called from Sync's comment HTML converter; never throws. */
    public static boolean isVideoLink(String href) {
        try {
            return mediaId(href) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Called first in Sync's link router (y7.b.c). A reddit.com/link/.../video/MEDIA link becomes
     * https://v.redd.it/MEDIA so Sync opens its video viewer instead of a browser redirect to a
     * placeholder post. Every other URL is returned unchanged. Never throws.
     */
    public static String tapUrl(String url) {
        try {
            CommentMedia.Url parsed = CommentMedia.parse(url);
            if (parsed == null || parsed.host.equals(VIDEO_HOST)) return url;
            String id = mediaId(url);
            return id == null ? url : "https://" + VIDEO_HOST + "/" + id;
        } catch (RuntimeException e) {
            return url;
        }
    }

    // ---------------------------------------------------------------- posters

    /**
     * Inline box for a video link whose poster is ready, or null to keep Sync's link. An unknown
     * poster is queued in the background and appears on a later redraw. Never throws.
     */
    public static int[] posterSize(String href, int maxWidth) {
        try {
            String id = mediaId(href);
            if (id == null || maxWidth <= 0 || !platform.previewsEnabled()) return null;
            int[] size = cachedSize(id);
            if (size == null) {
                requestPoster(id);
                return null;
            }
            return CommentMedia.fitBox(size[0], size[1], maxWidth, platform.density());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Poster file for Sync's image span; only called after posterSize returned a box. Never null. */
    public static String posterUrl(String href) {
        try {
            String id = mediaId(href);
            File file = id == null ? null : posterFile(id);
            if (file != null) return file.getAbsolutePath();
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through: the span then fails to load and draws nothing.
        }
        return href == null ? "" : href;
    }

    static File posterFile(String id) {
        File directory = platform.posterDirectory();
        return directory == null ? null : new File(directory, id + ".jpg");
    }

    /** Size from memory, or once per session from a poster saved by an earlier session. */
    static int[] cachedSize(String id) {
        synchronized (LOCK) {
            int[] size = SIZES.get(id);
            if (size != null || DISK_CHECKED.put(id, Boolean.TRUE) != null) return size;
        }
        File file = posterFile(id);
        int[] size = file != null && file.isFile() ? validSize(platform.imageSize(file)) : null;
        if (size != null) {
            synchronized (LOCK) {
                SIZES.put(id, size);
            }
        }
        return size;
    }

    private static int[] validSize(int[] size) {
        return size != null && size.length == 2 && size[0] > 0 && size[1] > 0
                && size[0] <= CommentMedia.MAX_SOURCE_DIMENSION && size[1] <= CommentMedia.MAX_SOURCE_DIMENSION
                ? new int[] {size[0], size[1]} : null;
    }

    private static final class Task implements Runnable {
        final String id;
        Task(String id) {
            this.id = id;
        }
        @Override public void run() {
            try {
                generate(id);
            } finally {
                synchronized (LOCK) {
                    PENDING.remove(id);
                }
            }
        }
    }

    /** Queues one poster job per media ID; never blocks the caller. */
    static void requestPoster(String id) {
        synchronized (LOCK) {
            if (SIZES.containsKey(id) || FAILED.containsKey(id) || !PENDING.add(id)) return;
        }
        try {
            WORKER.execute(new Task(id));
        } catch (RuntimeException e) {
            synchronized (LOCK) {
                PENDING.remove(id);
            }
        }
    }

    /** Fetches the manifest and the start of one rendition, then saves the poster. Never logs. */
    static boolean generate(String id) {
        boolean made = false;
        File video = null;
        File temp = null;
        try {
            File directory = platform.posterDirectory();
            if (directory != null && (directory.isDirectory() || directory.mkdirs())) {
                video = new File(directory, id + ".part");
                temp = new File(directory, id + ".tmp");
                String manifest = fetchText(videoBase + id + "/DASHPlaylist.mpd", MAX_MANIFEST_BYTES);
                List<Rendition> renditions = manifest == null ? null : renditions(manifest);
                Rendition pick = renditions == null ? null : choose(renditions);
                if (pick != null && download(videoBase + id + "/" + pick.base, video, MAX_VIDEO_BYTES)) {
                    int[] size = validSize(platform.makePoster(video, temp, largest(renditions).width));
                    File poster = new File(directory, id + ".jpg");
                    if (size != null && temp.isFile() && (!poster.exists() || poster.delete()) && temp.renameTo(poster)) {
                        synchronized (LOCK) {
                            SIZES.put(id, size);
                        }
                        made = true;
                        prune(directory);
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // A failed poster keeps Sync's link for this video.
        } finally {
            if (video != null) video.delete();
            if (temp != null) temp.delete();
        }
        if (!made) {
            synchronized (LOCK) {
                FAILED.put(id, Boolean.TRUE);
            }
        }
        return made;
    }

    private static HttpURLConnection open(String target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        // Redirects are refused rather than followed to another origin.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    /** UTF-8 body of a 200 response no larger than limit bytes, otherwise null. */
    static String fetchText(String target, int limit) throws IOException {
        HttpURLConnection connection = open(target);
        try {
            if (connection.getResponseCode() != 200) return null;
            long length = connection.getContentLength();
            if (length > limit) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (InputStream in = connection.getInputStream()) {
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    body.write(buffer, 0, count);
                    if (body.size() > limit) return null;
                }
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** Saves at most limit bytes from the start of the file (Range request); false on failure. */
    static boolean download(String target, File destination, int limit) throws IOException {
        HttpURLConnection connection = open(target);
        try {
            connection.setRequestProperty("Range", "bytes=0-" + (limit - 1));
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) return false;
            long written = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(destination)) {
                int count;
                while (written < limit && (count = in.read(buffer, 0, (int) Math.min(buffer.length, limit - written))) >= 0) {
                    out.write(buffer, 0, count);
                    written += count;
                }
            }
            return written > 0;
        } finally {
            connection.disconnect();
        }
    }

    /** Keeps the poster directory bounded and removes stale partial files. */
    static void prune(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        List<File> posters = new ArrayList<>();
        long stale = System.currentTimeMillis() - 10 * 60 * 1000L;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".jpg")) {
                posters.add(file);
            } else if ((name.endsWith(".part") || name.endsWith(".tmp")) && file.lastModified() < stale) {
                file.delete();
            }
        }
        if (posters.size() <= MAX_POSTERS) return;
        Collections.sort(posters, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : posters.subList(0, posters.size() - PRUNE_TO)) {
            if (file.delete()) {
                String id = file.getName().substring(0, file.getName().length() - 4);
                synchronized (LOCK) {
                    SIZES.remove(id);
                }
            }
        }
    }

    // --------------------------------------------------------------- manifest

    static final class Rendition {
        final int width;
        final int height;
        final long bandwidth;
        final String base;
        Rendition(int width, int height, long bandwidth, String base) {
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.base = base;
        }
    }

    private static final Pattern TAG = Pattern.compile(
            "<(/?)(?:[A-Za-z_][\\w.-]*:)?(AdaptationSet|Representation|BaseURL)\\b([^>]*?)(/?)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][\\w.:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");

    private static Map<String, String> attributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            values.put(name, matcher.group(3) != null ? matcher.group(3) : matcher.group(4));
        }
        return values;
    }

    /**
     * Video renditions with a plain relative MP4 file name from a DASH manifest. Accepts
     * namespace-prefixed elements and MIME types on either the AdaptationSet or Representation.
     */
    static List<Rendition> renditions(String manifest) {
        List<Rendition> result = new ArrayList<>();
        Map<String, String> set = null;
        Map<String, String> representation = null;
        String base = null;
        Matcher matcher = TAG.matcher(manifest);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            boolean empty = !matcher.group(4).isEmpty();
            String name = matcher.group(2);
            if (name.equals("AdaptationSet")) {
                set = closing ? null : attributes(matcher.group(3));
                if (empty) set = null;
            } else if (name.equals("Representation")) {
                if (closing) {
                    add(result, set, representation, base);
                    representation = null;
                    base = null;
                } else {
                    representation = attributes(matcher.group(3));
                    base = null;
                    if (empty) {
                        representation = null;
                    }
                }
            } else if (!closing && !empty && representation != null) {
                int end = manifest.indexOf('<', matcher.end());
                if (end > matcher.end()) base = manifest.substring(matcher.end(), end).trim();
            }
        }
        return result;
    }

    private static void add(List<Rendition> result, Map<String, String> set, Map<String, String> representation, String base) {
        if (representation == null || base == null) return;
        if (!FILE_NAME.matcher(base).matches() || base.contains("..")
                || !base.toLowerCase(Locale.ROOT).endsWith(".mp4")) return;
        String mime = first(representation, set, "mimeType");
        String content = first(representation, set, "contentType");
        int width = CommentMedia.dimension(representation.get("width"));
        int height = CommentMedia.dimension(representation.get("height"));
        boolean video = mime != null ? mime.toLowerCase(Locale.ROOT).startsWith("video/")
                : content != null ? content.equalsIgnoreCase("video") : width > 0 && height > 0;
        if (!video || width <= 0 || height <= 0) return;
        long bandwidth = -1;
        String value = representation.get("bandwidth");
        if (value != null && value.matches("[0-9]{1,10}")) bandwidth = Long.parseLong(value);
        result.add(new Rendition(width, height, bandwidth, base));
    }

    private static String first(Map<String, String> representation, Map<String, String> set, String key) {
        String value = representation.get(key);
        if (value == null && set != null) value = set.get(key);
        return value;
    }

    private static long cost(Rendition rendition) {
        return rendition.bandwidth > 0 ? rendition.bandwidth : (long) rendition.width * rendition.height;
    }

    /** Cheapest rendition whose short side is at least MIN_POSTER_SIDE, else the largest. */
    static Rendition choose(List<Rendition> renditions) {
        Rendition best = null;
        for (Rendition rendition : renditions) {
            if (Math.min(rendition.width, rendition.height) < MIN_POSTER_SIDE) continue;
            if (best == null || cost(rendition) < cost(best)) best = rendition;
        }
        return best != null ? best : largest(renditions);
    }

    static Rendition largest(List<Rendition> renditions) {
        Rendition best = null;
        for (Rendition rendition : renditions) {
            if (best == null || (long) rendition.width * rendition.height > (long) best.width * best.height) best = rendition;
        }
        return best;
    }

    // ----------------------------------------------------------------- drawing

    /** Poster size: the frame's aspect ratio at the video's native width, at most 720 pixels wide. */
    static int[] posterDimensions(int frameWidth, int frameHeight, int nativeWidth) {
        if (frameWidth <= 0 || frameHeight <= 0) return null;
        int width = Math.min(MAX_POSTER_WIDTH, Math.max(frameWidth, nativeWidth));
        int height = Math.round(width * (float) frameHeight / frameWidth);
        return height > 0 && height <= CommentMedia.MAX_SOURCE_DIMENSION ? new int[] {width, height} : null;
    }

    /** Play badge radius: 12% of the short side, at least 12 pixels, never past the poster edge. */
    static float badgeRadius(int width, int height) {
        int side = Math.min(width, height);
        return Math.min(side / 2f, Math.max(12f, side * 0.12f));
    }

    static void clear() {
        synchronized (LOCK) {
            SIZES.clear();
            FAILED.clear();
            DISK_CHECKED.clear();
            PENDING.clear();
        }
    }

    /** Sync 23033 on Android. Only loaded in the app; tests replace it. */
    static final class AndroidPlatform implements Platform {
        private File directory;

        @Override public boolean previewsEnabled() {
            try {
                Class<?> singleton = Class.forName("com.laurencedawson.reddit_sync.singleton.SettingsSingleton");
                Object settings = singleton.getMethod("x").invoke(null);
                return settings != null && settings.getClass().getField("inlineImagePreviews").getBoolean(settings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return false;
            }
        }

        private static android.content.Context context() {
            String[][] sources = {
                {"app.revanced.extension.shared.Utils", "getContext"},
                {"com.laurencedawson.reddit_sync.RedditApplication", "f"},
                {"android.app.ActivityThread", "currentApplication"},
            };
            for (String[] source : sources) {
                try {
                    Object value = Class.forName(source[0]).getMethod(source[1]).invoke(null);
                    if (value instanceof android.content.Context) return (android.content.Context) value;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // Try the next source.
                }
            }
            return null;
        }

        @Override public synchronized File posterDirectory() {
            if (directory == null) {
                android.content.Context context = context();
                File cache = context == null ? null : context.getCacheDir();
                if (cache != null) directory = new File(cache, DIRECTORY);
            }
            return directory;
        }

        @Override public int[] imageSize(File poster) {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(poster.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0 ? new int[] {options.outWidth, options.outHeight} : null;
        }

        @Override public int[] makePoster(File video, File poster, int nativeWidth) {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            android.graphics.Bitmap frame = null;
            android.graphics.Bitmap image = null;
            try {
                retriever.setDataSource(video.getAbsolutePath());
                frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) return null;
                int[] size = posterDimensions(frame.getWidth(), frame.getHeight(), nativeWidth);
                if (size == null) return null;
                image = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(image);
                android.graphics.Paint paint = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(frame, null, new android.graphics.Rect(0, 0, size[0], size[1]), paint);
                float radius = badgeRadius(size[0], size[1]);
                float x = size[0] / 2f;
                float y = size[1] / 2f;
                paint.setColor(0x8C000000);
                canvas.drawCircle(x, y, radius, paint);
                android.graphics.Path triangle = new android.graphics.Path();
                triangle.moveTo(x - radius * 0.32f, y - radius * 0.45f);
                triangle.lineTo(x - radius * 0.32f, y + radius * 0.45f);
                triangle.lineTo(x + radius * 0.5f, y);
                triangle.close();
                paint.setColor(0xFFFFFFFF);
                canvas.drawPath(triangle, paint);
                try (OutputStream out = new FileOutputStream(poster)) {
                    if (!image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)) return null;
                }
                return size;
            } catch (IOException | RuntimeException e) {
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Newer Android declares IOException here.
                }
                if (frame != null) frame.recycle();
                if (image != null) image.recycle();
            }
        }

        @Override public float density() {
            try {
                return android.content.res.Resources.getSystem().getDisplayMetrics().density;
            } catch (RuntimeException | LinkageError e) {
                return 0f;
            }
        }
    }
}
