package app.revanced.extension.synccompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        Metadata(int width, int height, String mime) {
            this.width = width;
            this.height = height;
            this.mime = mime;
        }
    }

    /** Process-local and bounded; holds media IDs, dimensions and MIME types only. */
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
                String status = item.optString("status", "valid");
                if (!"valid".equals(status)) continue;
                String kind = item.optString("e", "");
                if (!"Image".equals(kind) && !"AnimatedImage".equals(kind)) continue;
                String mime = item.optString("m", "").toLowerCase(Locale.ROOT);
                JSONObject source = item.optJSONObject("s");
                if (source == null) continue;
                int width = source.optInt("x", -1);
                int height = source.optInt("y", -1);
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) continue;
                String id = item.optString("id", key);
                // Emote and Giphy keys contain '|' and are rejected here; Sync handles them itself.
                String normalized = normalizeId(id);
                if (normalized == null) continue;
                synchronized (METADATA) {
                    METADATA.put(normalized, new Metadata(width, height, mime));
                }
            }
        } catch (RuntimeException ignored) {
            // Metadata only improves sizing; malformed data keeps Sync's original handling.
        }
    }

    static Metadata metadata(Url url) {
        String id = mediaId(url);
        if (id == null) return null;
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

    /** Inline box for a Reddit-hosted picture whose dimensions are known, otherwise null. */
    static int[] inlineImageSize(String value, int maxWidth, float density) {
        Url url = parse(value);
        if (url == null || !REDDIT_MEDIA_HOSTS.contains(url.host) || !isInlineImage(value)) return null;
        int width = dimension(queryValue(url.query, "width"));
        int height = dimension(queryValue(url.query, "height"));
        if (width <= 0 || height <= 0) {
            Metadata metadata = metadata(url);
            if (metadata == null) return null;
            width = metadata.width;
            height = metadata.height;
        }
        return fitBox(width, height, maxWidth, density);
    }

    /** Called from Sync's comment HTML converter. Null keeps the tappable link/preview path. */
    public static int[] inlineImageSize(String url, int maxWidth) {
        try {
            return inlineImageSize(url, maxWidth, density());
        } catch (RuntimeException e) {
            return null;
        }
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
