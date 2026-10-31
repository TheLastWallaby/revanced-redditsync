package app.revanced.extension.synccompat;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Format and size decisions for media links inside comment HTML.
 *
 * URLs are only inspected, never rebuilt, so signed preview URLs and the
 * tappable original link stay byte-for-byte unchanged.
 */
public final class CommentMedia {
    static final int MAX_SOURCE_DIMENSION = 100_000;
    /** Wider or taller than this (height/width) stays a link instead of an inline box. */
    static final float MIN_RATIO = 0.1f;
    static final float MAX_RATIO = 10f;
    /** Tall images are scaled down so their height stays within 1.5x the reply width. */
    static final float MAX_HEIGHT_FACTOR = 1.5f;
    static final float MAX_DISTORTION = 0.1f;
    static final int CACHE_SIZE = 512;

    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "pjpg", "gif", "webp"));
    private static final Set<String> REDDIT_MEDIA_HOSTS = new HashSet<>(Arrays.asList(
            "preview.redd.it", "external-preview.redd.it", "i.redd.it"));

    static final class Metadata {
        final int width;
        final int height;
        final String mime;
        final boolean animated;
        /** Reddit's chosen rendition for drawing (Giphy only); null draws the link itself. */
        final String display;
        Metadata(int width, int height, String mime, boolean animated, String display) {
            this.width = width;
            this.height = height;
            this.mime = mime;
            this.animated = animated;
            this.display = display;
        }
    }

    private static final String GIPHY_KEY = "giphy:";

    /** Process-local and bounded; holds media IDs, dimensions, MIME types and Giphy rendition URLs only. */
    private static final Map<String, Metadata> METADATA = new LinkedHashMap<String, Metadata>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Metadata> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private CommentMedia() {}

    // ---------------------------------------------------------------- URL parts

    static final class Url {
        final String host;
        final String path;
        final String query;
        Url(String host, String path, String query) {
            this.host = host;
            this.path = path;
            this.query = query;
        }
    }

    /** Minimal HTTP(S) parser; returns null for anything else. */
    static Url parse(String url) {
        if (url == null) return null;
        String value = url.trim();
        int scheme = value.indexOf("://");
        if (scheme <= 0) return null;
        String protocol = value.substring(0, scheme).toLowerCase(Locale.ROOT);
        if (!protocol.equals("https") && !protocol.equals("http")) return null;
        String rest = value.substring(scheme + 3);
        int fragment = rest.indexOf('#');
        if (fragment >= 0) rest = rest.substring(0, fragment);
        int queryStart = rest.indexOf('?');
        String query = queryStart >= 0 ? rest.substring(queryStart + 1) : "";
        if (queryStart >= 0) rest = rest.substring(0, queryStart);
        int pathStart = rest.indexOf('/');
        String authority = pathStart >= 0 ? rest.substring(0, pathStart) : rest;
        String path = pathStart >= 0 ? rest.substring(pathStart) : "/";
        int user = authority.lastIndexOf('@');
        if (user >= 0) authority = authority.substring(user + 1);
        int port = authority.lastIndexOf(':');
        if (port >= 0) authority = authority.substring(0, port);
        String host = authority.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;
        return new Url(host, path, query);
    }

    /** Query value for a key in any parameter order, tolerating leftover {@code &amp;} escapes. */
    static String queryValue(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            while (name.startsWith("amp;")) name = name.substring(4);
            if (name.equalsIgnoreCase(key)) return equals >= 0 ? part.substring(equals + 1) : "";
        }
        return null;
    }

    /** Positive bounded integer, or -1 for missing, signed, decimal or non-numeric input. */
    static int dimension(String value) {
        if (value == null || value.isEmpty() || value.length() > 6) return -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        int parsed = Integer.parseInt(value);
        return parsed > 0 && parsed <= MAX_SOURCE_DIMENSION ? parsed : -1;
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Reddit media ID from paths such as /abc123.png or /some-title-v0-abc123.webp. */
    static String mediaId(Url url) {
        if (url == null || !REDDIT_MEDIA_HOSTS.contains(url.host)) return null;
        String name = url.path.substring(url.path.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        int dash = name.lastIndexOf('-');
        if (dash >= 0) name = name.substring(dash + 1);
        return normalizeId(name);
    }

    static boolean isGiphyHost(String host) {
        return host.equals("giphy.com") || host.endsWith(".giphy.com");
    }

    /**
     * Giphy ID from media.giphy.com/media/ID/giphy.gif, media2.giphy.com/media/v1.x/ID/giphy.gif,
     * i.giphy.com/ID.gif or giphy.com/gifs/title-ID. IDs are case-sensitive.
     */
    static String giphyId(Url url) {
        if (url == null || !isGiphyHost(url.host)) return null;
        String[] segments = url.path.split("/");
        int last = segments.length - 1;
        while (last >= 0 && segments[last].isEmpty()) last--;
        if (last < 0) return null;
        String name = segments[last];
        String candidate;
        if (last >= 1 && segments[1].equals("gifs")) {
            int dash = name.lastIndexOf('-');
            candidate = dash >= 0 ? name.substring(dash + 1) : name;
        } else if (last >= 2 && segments[1].equals("media") && name.indexOf('.') >= 0) {
            candidate = segments[last - 1];
        } else if (last == 1 && name.indexOf('.') >= 0) {
            candidate = name.substring(0, name.indexOf('.'));
        } else {
            return null;
        }
        if (candidate.isEmpty() || candidate.length() > 64) return null;
        for (int i = 0; i < candidate.length(); i++) {
            if (!Character.isLetterOrDigit(candidate.charAt(i))) return null;
        }
        return candidate;
    }

    private static String normalizeId(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return null;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isLetterOrDigit(name.charAt(i))) return null;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------- media_metadata cache

    /** Records image dimensions/types from a comment's media_metadata object. Never throws. */
    public static void rememberMetadata(JSONObject mediaMetadata) {
        if (mediaMetadata == null) return;
        try {
            Iterator<String> keys = mediaMetadata.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = mediaMetadata.optJSONObject(key);
                if (item == null) continue;
                String id = item.optString("id", key);
                // giphy|ID or giphy|ID|downsized
                String giphy = id.startsWith("giphy|") ? giphyKeyId(id) : null;
                if (id.startsWith("giphy|") && giphy == null) continue;
                String status = item.optString("status", "valid");
                String kind = item.optString("e", "");
                String mime = item.optString("m", "").toLowerCase(Locale.ROOT);
                JSONObject source = item.optJSONObject("s");
                int width = source == null ? -1 : source.optInt("x", -1);
                int height = source == null ? -1 : source.optInt("y", -1);
                boolean sized = "valid".equals(status) && ("Image".equals(kind) || "AnimatedImage".equals(kind))
                        && width > 0 && height > 0 && width <= MAX_SOURCE_DIMENSION && height <= MAX_SOURCE_DIMENSION;
                if (!sized) {
                    // Reddit usually marks comment GIFs {"status": "invalid"} with no size at all.
                    if (giphy != null && probesAllowed()) requestGiphySize(giphy);
                    continue;
                }
                boolean animated = "AnimatedImage".equals(kind) || "image/gif".equals(mime);
                String cacheKey;
                String display = null;
                if (giphy != null) {
                    // Keep Reddit's chosen GIF rendition for drawing.
                    cacheKey = GIPHY_KEY + giphy;
                    display = trustedRendition(source.optString("gif", ""));
                } else {
                    // Emote keys contain '|' and are rejected here; Sync handles them itself.
                    cacheKey = normalizeId(id);
                    if (cacheKey == null) continue;
                }
                synchronized (METADATA) {
                    METADATA.put(cacheKey, new Metadata(width, height, mime, animated, display));
                }
            }
        } catch (RuntimeException ignored) {
            // Metadata only improves sizing; malformed data keeps Sync's original handling.
        }
    }

    /** Validated Giphy ID from a media_metadata key such as giphy|ID or giphy|ID|downsized. */
    static String giphyKeyId(String key) {
        String[] parts = key.split("\\|");
        return parts.length < 2 ? null : validGiphyId(parts[1]);
    }

    static String validGiphyId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return null;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isLetterOrDigit(id.charAt(i))) return null;
        }
        return id;
    }

    // ------------------------------------------- Giphy <img> markers (giphy|ID)

    /**
     * Sync turns ![gif](giphy|ID) into an img tag whose static handler has no reply width.
     * Its caller records the converter's width here just before handing over the tag.
     */
    private static final ThreadLocal<int[]> IMAGE_WIDTH = new ThreadLocal<>();

    public static void setImageWidth(int width) {
        int[] holder = IMAGE_WIDTH.get();
        if (holder == null) IMAGE_WIDTH.set(holder = new int[1]);
        holder[0] = width;
    }

    static int imageWidth() {
        int[] holder = IMAGE_WIDTH.get();
        return holder == null ? 0 : holder[0];
    }

    /**
     * Full-size box for Sync's unsized Giphy marker, or null to keep its small preview.
     * An unknown size queues a header probe so the next draw can be full size.
     */
    static int[] giphyInlineSize(String value, int maxWidth, float density) {
        String id = validGiphyId(value);
        if (id == null || maxWidth <= 0 || !probesAllowed()) return null;
        Metadata metadata;
        synchronized (METADATA) {
            metadata = METADATA.get(GIPHY_KEY + id);
        }
        if (metadata == null) {
            requestGiphySize(id);
            return null;
        }
        return fitBox(metadata.width, metadata.height, maxWidth, density);
    }

    /** Called from Sync's Giphy marker handler (nc.d.a); never throws. */
    public static int[] giphyInlineSize(String id) {
        try {
            return giphyInlineSize(id, imageWidth(), density());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** GIF to draw and open for a sized Giphy marker: Reddit's rendition or Giphy's original. */
    public static String giphyDisplayUrl(String id) {
        String valid = validGiphyId(id);
        if (valid == null) return id;
        synchronized (METADATA) {
            Metadata metadata = METADATA.get(GIPHY_KEY + valid);
            if (metadata != null && metadata.display != null) return metadata.display;
        }
        return giphyMediaUrl(valid);
    }

    // ------------------------------------------------------------- Giphy size probe

    static final int PROBE_TIMEOUT_MS = 3000;
    /** Tests point this at a loopback server; the app always uses Giphy's HTTPS CDN. */
    static String giphyMediaBase = "https://media.giphy.com/media/";
    /** Tests set this; in the app Sync's own settings decide. */
    static Boolean settingsOverride;

    private static final Set<String> PROBING = new HashSet<>();
    private static final Map<String, Boolean> PROBE_FAILED = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final ExecutorService PROBES = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "sync-compat-giphy-size");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    static String giphyMediaUrl(String id) {
        return giphyMediaBase + id + "/giphy.gif";
    }

    /**
     * Probing only runs while Sync would draw the result: inline image previews and
     * animated comment images both enabled. Reads Sync 23033's settings reflectively.
     */
    static boolean probesAllowed() {
        if (settingsOverride != null) return settingsOverride;
        try {
            Class<?> singleton = Class.forName("com.laurencedawson.reddit_sync.singleton.SettingsSingleton");
            Object settings = singleton.getMethod("x").invoke(null);
            if (settings == null) return false;
            Class<?> type = settings.getClass();
            return type.getField("inlineImagePreviews").getBoolean(settings)
                    && type.getField("commentsAnimatedImages").getBoolean(settings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Queues one background size probe per Giphy ID; never blocks the caller. */
    static void requestGiphySize(String id) {
        if (id == null) return;
        synchronized (METADATA) {
            if (METADATA.containsKey(GIPHY_KEY + id) || PROBE_FAILED.containsKey(id) || !PROBING.add(id)) return;
        }
        try {
            PROBES.execute(() -> {
                try {
                    probeGiphy(id);
                } finally {
                    synchronized (METADATA) {
                        PROBING.remove(id);
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (METADATA) {
                PROBING.remove(id);
            }
        }
    }

    /**
     * Reads only the 10-byte GIF header (Range request, no cookies or credentials) to learn
     * the logical screen size, then caches it with the GIF URL to draw. Never logs.
     */
    static boolean probeGiphy(String id) {
        HttpURLConnection connection = null;
        boolean sized = false;
        try {
            URL url = new URL(giphyMediaUrl(id));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Range", "bytes=0-9");
            // Ask for the file itself: a default Accept with text/html makes CDNs redirect to a web page.
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if ((status == 200 || status == 206) && sameOrigin(url, connection.getURL())) {
                byte[] header = new byte[10];
                int read = 0;
                try (InputStream in = connection.getInputStream()) {
                    while (read < header.length) {
                        int count = in.read(header, read, header.length - read);
                        if (count < 0) break;
                        read += count;
                    }
                }
                int[] size = read == header.length ? gifSize(header) : null;
                if (size != null) {
                    synchronized (METADATA) {
                        METADATA.put(GIPHY_KEY + id, new Metadata(size[0], size[1], "image/gif", true, giphyMediaUrl(id)));
                    }
                    sized = true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed probe keeps Sync's link card for this GIF.
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (!sized) {
            synchronized (METADATA) {
                PROBE_FAILED.put(id, Boolean.TRUE);
            }
        }
        return sized;
    }

    /** The probe must not follow a redirect to another scheme, host or port. */
    static boolean sameOrigin(URL requested, URL served) {
        return requested.getProtocol().equalsIgnoreCase(served.getProtocol())
                && requested.getHost().equalsIgnoreCase(served.getHost())
                && requested.getPort() == served.getPort();
    }

    /** Logical screen size from a GIF87a/GIF89a header, or null. */
    static int[] gifSize(byte[] header) {
        if (header == null || header.length < 10) return null;
        if (header[0] != 'G' || header[1] != 'I' || header[2] != 'F' || header[3] != '8'
                || (header[4] != '7' && header[4] != '9') || header[5] != 'a') return null;
        int width = (header[6] & 0xff) | (header[7] & 0xff) << 8;
        int height = (header[8] & 0xff) | (header[9] & 0xff) << 8;
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    static void clearProbes() {
        synchronized (METADATA) {
            PROBE_FAILED.clear();
            PROBING.clear();
        }
    }

    // ----------------------------------------------- direct picture links (Imgur)

    /** Hosts whose direct picture links are sized by reading the file header. */
    private static final Set<String> PROBE_IMAGE_HOSTS = new HashSet<>(Arrays.asList("i.imgur.com"));
    private static final String IMAGE_KEY = "img:";
    /** Larger files stay a card: Imgur can serve a 10-16 MB GIF even for a .jpg link. */
    static final long MAX_INLINE_BYTES = 8L * 1024 * 1024;
    /** Enough for JPEG EXIF and frame headers; PNG, GIF and WebP need under 40 bytes. */
    static final int IMAGE_HEADER_LIMIT = 64 * 1024;
    /** Tests point https://i.imgur.com/ at a loopback server; null in the app. */
    static String imageProbeBase;

    /** Cache key for a direct Imgur picture such as https://i.imgur.com/AbC123x.png, otherwise null. */
    static String imageKey(Url url) {
        if (url == null || !PROBE_IMAGE_HOSTS.contains(url.host)) return null;
        String path = url.path;
        int dot = path.lastIndexOf('.');
        if (!path.startsWith("/") || dot < 2 || path.indexOf('/', 1) >= 0) return null;
        String id = path.substring(1, dot);
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (id.length() < 5 || id.length() > 16 || !IMAGE_TYPES.contains(ext) || ext.equals("pjpg")) return null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c > 127 || !Character.isLetterOrDigit(c)) return null;
        }
        return IMAGE_KEY + url.host + "/" + id + "." + ext;
    }

    /** Picture probes only need Sync's Inline image previews setting; GIFs are gated when drawn. */
    static boolean imagesAllowed() {
        if (settingsOverride != null) return settingsOverride;
        try {
            Class<?> singleton = Class.forName("com.laurencedawson.reddit_sync.singleton.SettingsSingleton");
            Object settings = singleton.getMethod("x").invoke(null);
            return settings != null && settings.getClass().getField("inlineImagePreviews").getBoolean(settings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Queues one background header probe per picture link; never blocks the caller. */
    static void requestImageSize(String link, String key) {
        if (link == null || key == null) return;
        synchronized (METADATA) {
            if (METADATA.containsKey(key) || PROBE_FAILED.containsKey(key) || !PROBING.add(key)) return;
        }
        try {
            PROBES.execute(() -> {
                try {
                    probeImage(link, key);
                } finally {
                    synchronized (METADATA) {
                        PROBING.remove(key);
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (METADATA) {
                PROBING.remove(key);
            }
        }
    }

    /**
     * Reads at most the first 64 KB (Range request, no cookies or credentials) to learn the
     * picture's size and whether it is animated. Files over 8 MB, or of unknown total size,
     * are not drawn inline. Never logs.
     */
    static boolean probeImage(String link, String key) {
        HttpURLConnection connection = null;
        boolean sized = false;
        try {
            String target = link;
            if (imageProbeBase != null && link.startsWith("https://i.imgur.com/")) {
                target = imageProbeBase + link.substring("https://i.imgur.com/".length());
            }
            URL url = new URL(target);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Range", "bytes=0-" + (IMAGE_HEADER_LIMIT - 1));
            // Imgur redirects requests that accept text/html (Java's default) to its web page.
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            long total = -1;
            if (status == 206) {
                String range = connection.getHeaderField("Content-Range");
                int slash = range == null ? -1 : range.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        total = Long.parseLong(range.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                        total = -1;
                    }
                }
            } else if (status == 200) {
                total = connection.getContentLengthLong();
            }
            if (total > 0 && total <= MAX_INLINE_BYTES && sameOrigin(url, connection.getURL())) {
                byte[] header = new byte[IMAGE_HEADER_LIMIT];
                int read = 0;
                try (InputStream in = connection.getInputStream()) {
                    while (read < header.length) {
                        int count = in.read(header, read, header.length - read);
                        if (count < 0) break;
                        read += count;
                    }
                }
                ImageInfo info = imageInfo(header, read);
                if (info != null) {
                    synchronized (METADATA) {
                        METADATA.put(key, new Metadata(info.width, info.height, info.mime, info.animated, null));
                    }
                    sized = true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed probe keeps Sync's card for this picture.
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (!sized) {
            synchronized (METADATA) {
                PROBE_FAILED.put(key, Boolean.TRUE);
            }
        }
        return sized;
    }

    static final class ImageInfo {
        final int width;
        final int height;
        final String mime;
        final boolean animated;
        ImageInfo(int width, int height, String mime, boolean animated) {
            this.width = width;
            this.height = height;
            this.mime = mime;
            this.animated = animated;
        }
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xff;
    }

    private static int be16(byte[] b, int i) {
        return u8(b, i) << 8 | u8(b, i + 1);
    }

    private static int le16(byte[] b, int i) {
        return u8(b, i) | u8(b, i + 1) << 8;
    }

    private static int le24(byte[] b, int i) {
        return le16(b, i) | u8(b, i + 2) << 16;
    }

    private static boolean ascii(byte[] b, int i, String text) {
        if (i + text.length() > b.length) return false;
        for (int j = 0; j < text.length(); j++) {
            if (b[i + j] != text.charAt(j)) return false;
        }
        return true;
    }

    /** Size, type and animation from GIF, PNG/APNG, WebP or JPEG header bytes, or null. */
    static ImageInfo imageInfo(byte[] b, int length) {
        if (b == null || length <= 0) return null;
        int len = Math.min(length, b.length);
        if (len >= 10) {
            int[] gif = gifSize(b);
            if (gif != null) return new ImageInfo(gif[0], gif[1], "image/gif", true);
        }
        if (len >= 24 && u8(b, 0) == 0x89 && ascii(b, 1, "PNG\r\n\u001a\n") && ascii(b, 12, "IHDR")) {
            long width = (long) be16(b, 16) << 16 | be16(b, 18);
            long height = (long) be16(b, 20) << 16 | be16(b, 22);
            boolean animated = false;
            for (int i = 24; i + 4 <= len && !animated; i++) {
                if (ascii(b, i, "IDAT")) break;
                animated = ascii(b, i, "acTL");
            }
            return sized(width, height, "image/png", animated);
        }
        if (len >= 30 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")) {
            if (ascii(b, 12, "VP8 ") && u8(b, 23) == 0x9d && u8(b, 24) == 0x01 && u8(b, 25) == 0x2a) {
                return sized(le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff, "image/webp", false);
            }
            if (ascii(b, 12, "VP8L") && u8(b, 20) == 0x2f) {
                long bits = (long) le16(b, 21) | (long) le16(b, 23) << 16;
                return sized((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1, "image/webp", false);
            }
            if (ascii(b, 12, "VP8X")) {
                return sized(le24(b, 24) + 1L, le24(b, 27) + 1L, "image/webp", (u8(b, 20) & 0x02) != 0);
            }
            return null;
        }
        if (len >= 4 && u8(b, 0) == 0xff && u8(b, 1) == 0xd8) return jpegInfo(b, len);
        return null;
    }

    private static ImageInfo sized(long width, long height, String mime, boolean animated) {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null;
        return new ImageInfo((int) width, (int) height, mime, animated);
    }

    /** Walks JPEG segments to the frame header, swapping sides for EXIF orientations 5-8. */
    private static ImageInfo jpegInfo(byte[] b, int len) {
        int i = 2;
        boolean rotated = false;
        while (i + 4 <= len) {
            if (u8(b, i) != 0xff) return null;
            int marker = u8(b, i + 1);
            if (marker == 0xff) {
                i++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
                i += 2;
                continue;
            }
            if (marker == 0xd9 || marker == 0xda) return null;
            int segment = be16(b, i + 2);
            if (segment < 2) return null;
            if (marker == 0xe1 && i + 4 + segment <= len) rotated |= exifRotated(b, i + 4, segment - 2);
            boolean frame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (frame) {
                if (i + 9 > len) return null;
                int height = be16(b, i + 5);
                int width = be16(b, i + 7);
                return rotated ? sized(height, width, "image/jpeg", false) : sized(width, height, "image/jpeg", false);
            }
            i += 2 + segment;
        }
        return null;
    }

    /** True when an APP1 EXIF block's IFD0 orientation is 5-8 (stored sideways). */
    private static boolean exifRotated(byte[] b, int start, int length) {
        if (length < 14 || !ascii(b, start, "Exif") || b[start + 4] != 0 || b[start + 5] != 0) return false;
        int tiff = start + 6;
        int end = start + length;
        boolean little;
        if (ascii(b, tiff, "II")) little = true;
        else if (ascii(b, tiff, "MM")) little = false;
        else return false;
        int ifd = tiff + (int) read32(b, tiff + 4, little);
        if (ifd < tiff || ifd + 2 > end) return false;
        int entries = little ? le16(b, ifd) : be16(b, ifd);
        for (int e = 0; e < entries; e++) {
            int entry = ifd + 2 + e * 12;
            if (entry + 12 > end) return false;
            int tag = little ? le16(b, entry) : be16(b, entry);
            if (tag == 0x0112) {
                int orientation = little ? le16(b, entry + 8) : be16(b, entry + 8);
                return orientation >= 5 && orientation <= 8;
            }
        }
        return false;
    }

    private static long read32(byte[] b, int i, boolean little) {
        return little ? (le16(b, i) | (long) le16(b, i + 2) << 16) : ((long) be16(b, i) << 16 | be16(b, i + 2));
    }

    /** HTTPS Giphy or Reddit media URL from metadata, otherwise null. */
    static String trustedRendition(String value) {
        if (value == null || !value.regionMatches(true, 0, "https://", 0, 8)) return null;
        Url url = parse(value);
        if (url == null || !(isGiphyHost(url.host) || REDDIT_MEDIA_HOSTS.contains(url.host))) return null;
        return value;
    }

    static Metadata metadata(Url url) {
        String id = mediaId(url);
        if (id == null) {
            String giphy = giphyId(url);
            id = giphy != null ? GIPHY_KEY + giphy : imageKey(url);
            if (id == null) return null;
        }
        synchronized (METADATA) {
            return METADATA.get(id);
        }
    }

    static void clearMetadata() {
        synchronized (METADATA) {
            METADATA.clear();
        }
    }

    // ------------------------------------------------------------ format checks

    /** True for static or animated picture formats that Sync's image loader can show inline. */
    static boolean isInlineImage(String value) {
        Url url = parse(value);
        if (url == null) return false;
        String format = queryValue(url.query, "format");
        if (format != null) {
            // An explicit rendition format wins: format=mp4 is a video even on a .gif path.
            return IMAGE_TYPES.contains(format.toLowerCase(Locale.ROOT));
        }
        if (IMAGE_TYPES.contains(extension(url.path))) return true;
        Metadata metadata = metadata(url);
        return metadata != null && metadata.mime.startsWith("image/");
    }

    /** Keeps Sync's original decision and adds parsed formats such as WebP. */
    public static boolean inlineImageOr(String url, boolean original) {
        if (original) return true;
        try {
            return isInlineImage(url);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------- sizing

    /**
     * Fits a source image into the reply width without changing its aspect ratio.
     * Small images are not stretched past their native size in density-independent pixels.
     */
    static int[] fitBox(int width, int height, int maxWidth, float density) {
        if (maxWidth <= 0 || width <= 0 || height <= 0) return null;
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null;
        float ratio = (float) height / width;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return null;
        int boxWidth = maxWidth;
        if (density > 0) {
            long nativeWidth = (long) Math.ceil(width * (double) density);
            if (nativeWidth < boxWidth) boxWidth = (int) nativeWidth;
        }
        int boxHeight = Math.round(boxWidth * ratio);
        int maxHeight = Math.round(maxWidth * MAX_HEIGHT_FACTOR);
        if (boxHeight > maxHeight) {
            boxHeight = maxHeight;
            boxWidth = Math.round(maxHeight / ratio);
        }
        if (boxWidth < 1 || boxHeight < 1) return null;
        // Tiny boxes cannot hold some ratios after rounding; a link beats a distorted picture.
        float drawn = (float) boxHeight / boxWidth;
        if (Math.abs(drawn - ratio) > ratio * MAX_DISTORTION) return null;
        return new int[] {boxWidth, boxHeight};
    }

    /** GIFs and Giphy media; an explicit static format= rendition is not animated. */
    static boolean isAnimated(Url url) {
        String format = queryValue(url.query, "format");
        if (format != null) return format.equalsIgnoreCase("gif");
        if (isGiphyHost(url.host) || extension(url.path).equals("gif")) return true;
        Metadata metadata = metadata(url);
        return metadata != null && metadata.animated;
    }

    /**
     * Inline box for a Reddit-hosted picture, or a Giphy link described by the comment's
     * media_metadata, whose dimensions are known. Null keeps Sync's link/preview card.
     */
    static int[] inlineImageSize(String value, int maxWidth, float density, boolean animatedAllowed) {
        Url url = parse(value);
        if (url == null) return null;
        boolean reddit = REDDIT_MEDIA_HOSTS.contains(url.host);
        String imageKey = imageKey(url);
        if (!reddit && !isGiphyHost(url.host) && imageKey == null) return null;
        if (!animatedAllowed && isAnimated(url)) return null;
        Metadata metadata = metadata(url);
        int width = -1;
        int height = -1;
        if (reddit) {
            if (!isInlineImage(value)) return null;
            width = dimension(queryValue(url.query, "width"));
            height = dimension(queryValue(url.query, "height"));
        }
        if (width <= 0 || height <= 0) {
            // Giphy links carry no dimensions; only metadata or a header probe gives a safe ratio.
            if (metadata == null) {
                if (imageKey != null) {
                    // Probe the canonical HTTPS file, without the link's query or fragment.
                    if (imagesAllowed()) requestImageSize("https://" + imageKey.substring(IMAGE_KEY.length()), imageKey);
                } else if (!reddit && probesAllowed()) {
                    requestGiphySize(giphyId(url));
                }
                return null;
            }
            width = metadata.width;
            height = metadata.height;
        }
        // A giphy.com page link needs Reddit's rendition URL; a media file can be drawn directly.
        if (!reddit && metadata.display == null && !IMAGE_TYPES.contains(extension(url.path))) return null;
        return fitBox(width, height, maxWidth, density);
    }

    /** Called from Sync's comment HTML converter. Null keeps the tappable link/preview path. */
    public static int[] inlineImageSize(String url, int maxWidth, boolean animatedAllowed) {
        try {
            return inlineImageSize(url, maxWidth, density(), animatedAllowed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** URL to draw: Reddit's chosen Giphy rendition when known, otherwise the link itself. */
    public static String displayUrl(String value) {
        try {
            Url url = parse(value);
            if (url != null && isGiphyHost(url.host)) {
                Metadata metadata = metadata(url);
                if (metadata != null && metadata.display != null) return metadata.display;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the original link.
        }
        return value;
    }

    private static float density() {
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (RuntimeException | LinkageError e) {
            return 0f;
        }
    }

    /** Giphy markers are id|width|height after Sync's media_metadata pass; validate before sizing. */
    public static boolean hasGiphySize(String[] parts) {
        return parts != null && parts.length == 3 && parts[0] != null && !parts[0].isEmpty()
                && dimension(parts[1]) > 0 && dimension(parts[2]) > 0;
    }
}
