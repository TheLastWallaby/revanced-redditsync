package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Direct HTTPS replacements for the media proxies that Sync 23033 still builds:
 * images.syncforreddit.com (Imgur image and album lookups) and ap.syncforreddit.com/image
 * (link-card thumbnails, website previews and enhanced autoplay). Neither proxy answers now.
 *
 * <ul>
 *   <li>Imgur albums and gallery posts are read from Imgur's public embed page, which lists every
 *       item's hash, extension, size, title and description. Single items use the item's embed
 *       page. The responses are adapted to the JSON shape Sync's own parsers read, so album order,
 *       captions, video items and the saved album position keep working.</li>
 *   <li>Album link cards get the album cover as a thumbnail, resolved in the background.</li>
 *   <li>Website previews and link-card thumbnails for web pages show the link card instead of
 *       handing a page's HTML to the image decoder. YouTube previews (i.ytimg.com) are unchanged.</li>
 *   <li>Enhanced autoplay resolves RedGifs posts through Sync's own RedGifs resolver, whose
 *       RedGifs token is separate from Reddit's. Gfycat shut down; its posts use Sync's ordinary
 *       autoplay source, or none.</li>
 * </ul>
 *
 * Every URL built here uses a validated ID and a fixed HTTPS host. Requests carry no Reddit
 * authorization. Methods called from Sync's code never throw.
 */
public final class MediaProxyCompat {
    static final String IMGUR_ORIGIN = "https://imgur.com";
    /** Overridden by tests with a loopback origin. */
    static String imgurOrigin = IMGUR_ORIGIN;
    static final String EMBED_SUFFIX = "/embed?pub=true";
    static final String MEDIA_BASE = "https://i.imgur.com/";
    /** A reserved name that never resolves, for links without a usable Imgur ID. */
    static final String UNAVAILABLE_URL = "https://invalid.invalid/sync-compat/unavailable";
    /** Sync's album request turns a response containing this into "try it as a single image". */
    static final String ALBUM_NOT_FOUND = "Unable to find an album with the id";
    /** Method the patch adds to Sync's link-card span (nb.d) to load a thumbnail into it. */
    static final String SPAN_THUMBNAIL = "syncCompatThumbnail";
    static final int MAX_PAGE_BYTES = 512 * 1024;
    static final int TIMEOUT_MS = 10000;
    static final long RETRY_MS = 10 * 60 * 1000L;
    static final long PENDING_TIMEOUT_MS = 30 * 1000L;
    static final int MAX_WAITING_SPANS = 8;
    private static final int CACHE_SIZE = 200;

    private static final String[] LEGACY_PROXY_PREFIXES = {
            "https://images.syncforreddit.com/", "http://images.syncforreddit.com/",
            "https://ap.syncforreddit.com/", "http://ap.syncforreddit.com/",
    };
    private static final Set<String> IMGUR_PAGE_HOSTS = new HashSet<>(Arrays.asList(
            "imgur.com", "www.imgur.com", "m.imgur.com", "imgur.io", "www.imgur.io", "m.imgur.io"));
    /** Path words Sync's own Imgur helper skips when it looks for an ID. */
    private static final Set<String> IGNORED_SEGMENTS = new HashSet<>(Arrays.asList(
            "new", "horizontal", "layout", "blog", "noscript", "embed", "all"));
    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "mp4"));
    private static final Set<String> YOUTUBE_HOSTS = new HashSet<>(Arrays.asList(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
            "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com"));
    /** Hosts that serve only pictures, so a link card may load the link itself. */
    private static final Set<String> PICTURE_HOSTS = new HashSet<>(Arrays.asList(
            "i.redd.it", "preview.redd.it", "external-preview.redd.it", "i.redditmedia.com",
            "g.redditmedia.com", "i.reddituploads.com", "i.imgur.com", "media.giphy.com", "i.giphy.com",
            "i.ytimg.com", "pbs.twimg.com", "media.tenor.com"));
    private static final String[] PICTURE_ENDINGS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"};

    private static final Pattern IMGUR_ID = Pattern.compile("[A-Za-z0-9]{5,10}");
    private static final Pattern IMAGES_VARIABLE = Pattern.compile("\\bvar\\s+images\\s*=\\s*\\{");
    private static final Pattern TITLE_VARIABLE = Pattern.compile("\\bvar\\s+title\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IMAGE_ELEMENT = Pattern.compile("<img\\b[^>]*\\bid=\"image-element\"[^>]*>");
    private static final Pattern SOURCE_ELEMENT = Pattern.compile("<source\\b[^>]*>");
    private static final Pattern SRC = Pattern.compile("\\bsrc=\"([^\"]*)\"");
    private static final Pattern REDGIFS_ID = Pattern.compile("[A-Za-z0-9]{3,80}");

    private static final Object LOCK = new Object();
    private static final Map<String, String> COVERS = lru();
    private static final Map<String, Long> COVER_FAILED = lru();
    private static final Set<String> COVER_PENDING = new HashSet<>();
    private static final Map<String, List<WeakReference<Object>>> WAITING_SPANS = new HashMap<>();
    private static final Map<String, String> REDGIFS = lru();
    private static final Map<String, Long> REDGIFS_FAILED = lru();
    private static final Map<String, Long> REDGIFS_PENDING = new HashMap<>();
    private static final Map<Object, Wait> WAITING_PLAYERS = new WeakHashMap<>();
    private static final Map<Object, String> REQUESTS = new WeakHashMap<>();

    static Platform platform = new AndroidPlatform();
    static Clock clock = System::currentTimeMillis;

    private MediaProxyCompat() {}

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    interface Clock { long now(); }

    interface Platform {
        /** Runs a bounded background job; may throw RejectedExecutionException when busy. */
        void execute(Runnable task);
        /** Runs on Sync's main thread, where Glide loads and player calls must start. */
        void post(Runnable task);
        /** Loads a thumbnail into a link-card span through the method the patch adds to it. */
        void loadThumbnail(Object span, String url);
        /** True while the inline player is still attached and showing a post. */
        boolean isActive(Object player);
        /** Starts the inline player, as Sync's own autoplay call does. */
        void play(Object player, String url, boolean muted, boolean controls);
        /** GET of a page on the fixed Imgur origin. */
        Page fetch(String url) throws IOException;
    }

    static final class Page {
        final int status;
        final String body;

        Page(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // ------------------------------------------------------------------ Imgur IDs

    /** The Imgur album, gallery or item ID in a link, or null. Case is preserved. */
    static String imgurId(String link) {
        if (link == null) return null;
        String text = link.trim();
        int line = text.indexOf('\n');
        if (line >= 0) text = text.substring(0, line).trim();
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception malformed) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return null;
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null) return null;
        if (!IMGUR_PAGE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) return null;
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) return null;
        String path = uri.getPath();
        if (path == null) return null;
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) segments.add(part);
        }
        String candidate = null;
        for (int i = 0; i + 1 < segments.size(); i++) {
            String kind = segments.get(i).toLowerCase(Locale.ROOT);
            if (kind.equals("a") || kind.equals("gallery")) {
                candidate = segments.get(i + 1);
                break;
            }
        }
        if (candidate == null) {
            for (int i = segments.size() - 1; i >= 0; i--) {
                String part = segments.get(i);
                if (!IGNORED_SEGMENTS.contains(part.toLowerCase(Locale.ROOT))) {
                    candidate = part;
                    break;
                }
            }
        }
        if (candidate == null) return null;
        int dot = candidate.indexOf('.');
        if (dot >= 0) candidate = candidate.substring(0, dot);
        // Current gallery links put a title slug before the ID: /gallery/some-title-AbCdE12.
        int dash = candidate.lastIndexOf('-');
        if (dash >= 0) candidate = candidate.substring(dash + 1);
        // Imgur's downscaled-file suffix, which Sync's helper also strips.
        if (candidate.endsWith("_d")) candidate = candidate.substring(0, candidate.length() - 2);
        return IMGUR_ID.matcher(candidate).matches() ? candidate : null;
    }

    /** Replaces s8.b.a: Sync's album lookup through images.syncforreddit.com/a/. */
    public static String albumRequestUrl(String link) {
        String id = imgurId(link);
        return id == null ? UNAVAILABLE_URL : imgurOrigin + "/a/" + id + EMBED_SUFFIX;
    }

    /** Replaces s8.d.a: Sync's single-image lookup through images.syncforreddit.com/image/. */
    public static String imageRequestUrl(String link) {
        String id = imgurId(link);
        return id == null ? UNAVAILABLE_URL : imgurOrigin + "/" + id + EMBED_SUFFIX;
    }

    // ------------------------------------------------------------------ Imgur responses

    /** Adapts an album embed page to the {"data":{"images":[...]}} shape s8.b parses. */
    public static NetworkResponse albumResponse(NetworkResponse response) {
        try {
            if (response == null || response.data == null) return response;
            String text = new String(response.data, StandardCharsets.UTF_8);
            // A Volley cache hit returns what was stored: the already adapted JSON.
            if (text.trim().startsWith("{")) return response;
            JSONObject adapted = albumJson(text);
            return adapted == null ? response : replace(response, adapted);
        } catch (RuntimeException unexpected) {
            return response;
        }
    }

    /** Turns a missing album into Sync's "try it as a single image" error. */
    public static VolleyError albumError(VolleyError error) {
        try {
            if (error == null || error.networkResponse == null || error.networkResponse.statusCode != 404) return error;
            NetworkResponse missing = new NetworkResponse(404, ALBUM_NOT_FOUND.getBytes(StandardCharsets.UTF_8),
                    error.networkResponse.headers, false, error.networkResponse.networkTimeMs);
            return new VolleyError(missing);
        } catch (RuntimeException unexpected) {
            return error;
        }
    }

    /** Adapts an item embed page to the {"data":{"link":...}} shape s8.d parses. */
    public static NetworkResponse imageResponse(NetworkResponse response, String requestUrl) {
        try {
            if (response == null || response.data == null) return response;
            String text = new String(response.data, StandardCharsets.UTF_8);
            if (text.trim().startsWith("{")) return response;
            String id = embedId(requestUrl);
            if (id == null) return response;
            JSONObject adapted = imageJson(text, id);
            return adapted == null ? response : replace(response, adapted);
        } catch (RuntimeException unexpected) {
            return response;
        }
    }

    private static NetworkResponse replace(NetworkResponse response, JSONObject body) {
        return new NetworkResponse(response.statusCode, body.toString().getBytes(StandardCharsets.UTF_8),
                response.headers, response.notModified, response.networkTimeMs);
    }

    /** The ID in a request URL built by imageRequestUrl, or null. */
    static String embedId(String requestUrl) {
        if (requestUrl == null) return null;
        String prefix = imgurOrigin + "/";
        if (!requestUrl.startsWith(prefix) || !requestUrl.endsWith(EMBED_SUFFIX)) return null;
        String id = requestUrl.substring(prefix.length(), requestUrl.length() - EMBED_SUFFIX.length());
        return IMGUR_ID.matcher(id).matches() ? id : null;
    }

    /** Album items in album order, or null when the page carries no usable list. */
    static JSONObject albumJson(String html) {
        if (html == null) return null;
        try {
            Matcher found = IMAGES_VARIABLE.matcher(html);
            if (!found.find()) return null;
            Object value = new JSONTokener(html.substring(found.end() - 1)).nextValue();
            if (!(value instanceof JSONObject)) return null;
            JSONArray entries = ((JSONObject) value).optJSONArray("images");
            if (entries == null) return null;
            JSONArray images = new JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String hash = entry.optString("hash", "");
                String extension = extension(entry.optString("ext", ""));
                if (!IMGUR_ID.matcher(hash).matches() || extension == null) continue;
                JSONObject image = new JSONObject();
                image.put("link", MEDIA_BASE + hash + "." + extension);
                if (extension.equals("mp4") || extension.equals("gif") || entry.optBoolean("prefer_video")) {
                    image.put("mp4", MEDIA_BASE + hash + ".mp4");
                }
                // Sync reads these with getInt, so they must be present.
                image.put("width", Math.max(0, entry.optInt("width")));
                image.put("height", Math.max(0, entry.optInt("height")));
                image.put("title", text(entry, "title"));
                image.put("description", text(entry, "description"));
                images.put(image);
            }
            if (images.length() == 0) return null;
            return new JSONObject().put("data", new JSONObject().put("images", images));
        } catch (Exception malformed) {
            return null;
        }
    }

    /** One Imgur item, or null when the page doesn't name this ID's media. */
    static JSONObject imageJson(String html, String id) {
        if (html == null || id == null || !IMGUR_ID.matcher(id).matches()) return null;
        try {
            String link = null;
            Matcher sources = SOURCE_ELEMENT.matcher(html);
            while (link == null && sources.find()) {
                String tag = sources.group();
                if (!tag.contains("type=\"video/mp4\"")) continue;
                String src = src(tag);
                if (src != null && src.equals("//i.imgur.com/" + id + ".mp4")) link = MEDIA_BASE + id + ".mp4";
            }
            if (link == null) {
                Matcher image = IMAGE_ELEMENT.matcher(html);
                if (image.find()) {
                    String src = src(image.group());
                    String prefix = "//i.imgur.com/" + id;
                    if (src != null && src.startsWith(prefix)) {
                        String rest = src.substring(prefix.length());
                        // Imgur draws a sized rendition here: an optional one-letter size suffix.
                        if (rest.length() > 1 && Character.isLowerCase(rest.charAt(0)) && rest.charAt(1) == '.') {
                            rest = rest.substring(1);
                        }
                        String extension = rest.startsWith(".") ? extension(rest) : null;
                        if (extension != null) link = MEDIA_BASE + id + "." + extension;
                    }
                }
            }
            if (link == null) return null;
            JSONObject data = new JSONObject().put("link", link).put("description", "");
            Matcher title = TITLE_VARIABLE.matcher(html);
            data.put("title", title.find() ? unescape(title.group(1)) : "");
            return new JSONObject().put("data", data);
        } catch (Exception malformed) {
            return null;
        }
    }

    private static String src(String tag) {
        Matcher src = SRC.matcher(tag);
        if (!src.find()) return null;
        String value = src.group(1);
        if (value.startsWith("https:")) value = value.substring("https:".length());
        return value;
    }

    /** A supported media extension without its dot (gifv as mp4), or null. */
    static String extension(String value) {
        if (value == null) return null;
        String extension = value.startsWith(".") ? value.substring(1) : value;
        extension = extension.toLowerCase(Locale.ROOT);
        if (extension.equals("gifv")) extension = "mp4";
        return MEDIA_EXTENSIONS.contains(extension) ? extension : null;
    }

    private static String text(JSONObject entry, String key) {
        Object value = entry.opt(key);
        return value instanceof String ? (String) value : "";
    }

    /** Decodes the escapes a JavaScript string literal on Imgur's page uses. */
    static String unescape(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 >= literal.length()) {
                out.append(c);
                continue;
            }
            char next = literal.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'u':
                case 'x': {
                    int length = next == 'u' ? 4 : 2;
                    if (i + length < literal.length()) {
                        try {
                            out.append((char) Integer.parseInt(literal.substring(i + 1, i + 1 + length), 16));
                            i += length;
                            break;
                        } catch (RuntimeException notHex) {
                            // Keep the character as written.
                        }
                    }
                    out.append(next);
                    break;
                }
                default: out.append(next);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ website previews

    /**
     * Sync's website previews were images from ap.syncforreddit.com. Only YouTube's own
     * thumbnail remains direct; other links show Sync's ordinary link card.
     */
    public static boolean websitePreviewAllowed(String link) {
        String host = host(link);
        return host != null && YOUTUBE_HOSTS.contains(host);
    }

    // ------------------------------------------------------------------ link-card thumbnails

    /**
     * The thumbnail model for a link card (oc.c.o), or null to leave the card without one.
     * Replaces legacy proxy thumbnails and never hands a web page to the image decoder.
     */
    public static Object thumbnail(Object span, String link, Object model) {
        try {
            if (!(model instanceof String)) return model;
            String value = (String) model;
            if (value.isEmpty()) return null;
            if (isLegacyProxy(value)) {
                if (value.contains("imgur-album-")) return albumCover(span, link);
                // Gfycat shut down and web pages are not pictures: keep the link card.
                return null;
            }
            if (value.equals(link) && !looksLikePicture(link)) return null;
            return model;
        } catch (RuntimeException unexpected) {
            return null;
        }
    }

    static boolean isLegacyProxy(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        for (String prefix : LEGACY_PROXY_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean looksLikePicture(String link) {
        URI uri = uri(link);
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return false;
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null) return false;
        if (PICTURE_HOSTS.contains(host) || host.endsWith(".thumbs.redditmedia.com")) return true;
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        for (String ending : PICTURE_ENDINGS) {
            if (path.endsWith(ending)) return true;
        }
        return false;
    }

    private static Object albumCover(Object span, String link) {
        String id = imgurId(link);
        if (id == null) return null;
        long now = clock.now();
        synchronized (LOCK) {
            String cover = COVERS.get(id);
            if (cover != null) return cover.isEmpty() ? null : cover;
            Long failed = COVER_FAILED.get(id);
            if (failed != null && now - failed < RETRY_MS) return null;
            if (span != null) {
                List<WeakReference<Object>> waiting = WAITING_SPANS.get(id);
                if (waiting == null) WAITING_SPANS.put(id, waiting = new ArrayList<>());
                boolean present = false;
                for (Iterator<WeakReference<Object>> it = waiting.iterator(); it.hasNext(); ) {
                    Object existing = it.next().get();
                    if (existing == null) it.remove();
                    else if (existing == span) present = true;
                }
                if (!present && waiting.size() < MAX_WAITING_SPANS) waiting.add(new WeakReference<>(span));
            }
            if (!COVER_PENDING.add(id)) return null;
        }
        try {
            platform.execute(() -> resolveCover(id));
        } catch (RejectedExecutionException busy) {
            synchronized (LOCK) {
                COVER_PENDING.remove(id);
            }
        }
        return null;
    }

    /** Background: the album's first item, or a single item's own ID, as a small square thumbnail. */
    static void resolveCover(String id) {
        String thumbnail = null;
        boolean definitive = false;
        try {
            Page album = platform.fetch(imgurOrigin + "/a/" + id + EMBED_SUFFIX);
            if (album.status == 200) {
                JSONObject json = albumJson(album.body);
                if (json != null) {
                    String first = json.getJSONObject("data").getJSONArray("images").getJSONObject(0).getString("link");
                    String hash = first.substring(MEDIA_BASE.length(), first.lastIndexOf('.'));
                    thumbnail = MEDIA_BASE + hash + "b.jpg";
                    definitive = true;
                }
            } else if (album.status == 404) {
                Page item = platform.fetch(imgurOrigin + "/" + id + EMBED_SUFFIX);
                if (item.status == 200 && imageJson(item.body, id) != null) thumbnail = MEDIA_BASE + id + "b.jpg";
                definitive = item.status == 200 || item.status == 404;
            }
        } catch (Exception unavailable) {
            // Network failure or rate limit: try again later.
        }
        List<Object> spans = new ArrayList<>();
        synchronized (LOCK) {
            COVER_PENDING.remove(id);
            if (thumbnail != null) COVERS.put(id, thumbnail);
            else if (definitive) COVERS.put(id, "");
            else COVER_FAILED.put(id, clock.now());
            List<WeakReference<Object>> waiting = WAITING_SPANS.remove(id);
            if (waiting != null && thumbnail != null) {
                for (WeakReference<Object> reference : waiting) {
                    Object span = reference.get();
                    if (span != null) spans.add(span);
                }
            }
        }
        final String url = thumbnail;
        for (Object span : spans) platform.post(() -> platform.loadThumbnail(span, url));
    }

    // ------------------------------------------------------------------ enhanced autoplay

    private static final class Wait {
        final String id;
        final String fallback;
        final boolean muted;
        final boolean controls;

        Wait(String id, String fallback, boolean muted, boolean controls) {
            this.id = id;
            this.fallback = fallback;
            this.muted = muted;
            this.controls = controls;
        }
    }

    /**
     * Replaces the URL choice in lc.f.z. Returns the URL to play now, or null while a RedGifs
     * post is resolved; the player then starts from the resolver's callback.
     */
    public static String autoplayUrl(Object player, String link, String fallback, boolean enhanced,
                                     boolean muted, boolean controls) {
        try {
            synchronized (LOCK) {
                WAITING_PLAYERS.remove(player);
                REQUESTS.remove(player);
            }
            if (!enhanced) return fallback;
            String id = redgifsId(link);
            // Gfycat shut down; other links keep Sync's ordinary autoplay source.
            if (id == null) return fallback;
            long now = clock.now();
            synchronized (LOCK) {
                String direct = REDGIFS.get(id);
                if (direct != null) return direct;
                Long failed = REDGIFS_FAILED.get(id);
                if (failed != null && now - failed < RETRY_MS) return fallback;
                if (player == null) return fallback;
                WAITING_PLAYERS.put(player, new Wait(id, fallback, muted, controls));
                Long started = REDGIFS_PENDING.get(id);
                if (started == null || now - started >= PENDING_TIMEOUT_MS) {
                    REDGIFS_PENDING.put(id, now);
                    REQUESTS.put(player, link);
                }
            }
            return null;
        } catch (RuntimeException unexpected) {
            return fallback;
        }
    }

    /** The RedGifs link lc.f.z should hand to Sync's resolver now, or null. */
    public static String takeRedgifsRequest(Object player) {
        synchronized (LOCK) {
            return REQUESTS.remove(player);
        }
    }

    public static Response.Listener<String> redgifsListener(String link) {
        final String id = redgifsId(link);
        return url -> redgifsResolved(id, url);
    }

    public static Response.ErrorListener redgifsErrorListener(String link) {
        final String id = redgifsId(link);
        return error -> redgifsResolved(id, null);
    }

    static void redgifsResolved(String id, String url) {
        if (id == null) return;
        String direct = redgifsMedia(url);
        List<Object[]> starts = new ArrayList<>();
        synchronized (LOCK) {
            REDGIFS_PENDING.remove(id);
            if (direct != null) {
                REDGIFS.put(id, direct);
                REDGIFS_FAILED.remove(id);
            } else {
                REDGIFS_FAILED.put(id, clock.now());
            }
            for (Iterator<Map.Entry<Object, Wait>> it = WAITING_PLAYERS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Object, Wait> entry = it.next();
                Wait wait = entry.getValue();
                if (!wait.id.equals(id)) continue;
                it.remove();
                String target = direct != null ? direct : wait.fallback;
                if (target != null && !target.isEmpty()) {
                    starts.add(new Object[] {entry.getKey(), target, wait.muted, wait.controls});
                }
            }
        }
        for (Object[] start : starts) {
            platform.post(() -> {
                if (platform.isActive(start[0])) {
                    platform.play(start[0], (String) start[1], (Boolean) start[2], (Boolean) start[3]);
                }
            });
        }
    }

    /** The RedGifs post ID in a link, as Sync's resolver will read it, or null. */
    static String redgifsId(String link) {
        URI uri = uri(link);
        if (uri == null) return null;
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || !(host.equals("redgifs.com") || host.endsWith(".redgifs.com"))) return null;
        String path = uri.getPath();
        if (path == null) return null;
        String last = null;
        for (String part : path.split("/")) {
            if (!part.isEmpty()) last = part;
        }
        if (last == null) return null;
        int dot = last.indexOf('.');
        if (dot >= 0) last = last.substring(0, dot);
        int dash = last.indexOf('-');
        if (dash >= 0) last = last.substring(0, dash);
        return REDGIFS_ID.matcher(last).matches() ? last.toLowerCase(Locale.ROOT) : null;
    }

    /** A resolved RedGifs video on an HTTPS RedGifs host, or null. */
    static String redgifsMedia(String url) {
        URI uri = uri(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return null;
        if (uri.getPort() != -1 && uri.getPort() != 443) return null;
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || !(host.equals("redgifs.com") || host.endsWith(".redgifs.com"))) return null;
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".mp4") ? url : null;
    }

    // ------------------------------------------------------------------ helpers

    private static URI uri(String value) {
        if (value == null) return null;
        try {
            return new URI(value.trim());
        } catch (Exception malformed) {
            return null;
        }
    }

    private static String host(String link) {
        URI uri = uri(link);
        if (uri == null || uri.getHost() == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return null;
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Test hook: forget every cached resolution. */
    static void clear() {
        synchronized (LOCK) {
            COVERS.clear();
            COVER_FAILED.clear();
            COVER_PENDING.clear();
            WAITING_SPANS.clear();
            REDGIFS.clear();
            REDGIFS_FAILED.clear();
            REDGIFS_PENDING.clear();
            WAITING_PLAYERS.clear();
            REQUESTS.clear();
        }
    }

    /** GET with no redirects, cookies or credentials, bounded in size. */
    static Page get(String target) throws IOException {
        if (target == null || !target.startsWith(imgurOrigin + "/")) throw new IOException("Unexpected origin");
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("User-Agent", "sync_for_reddit");
            int status = connection.getResponseCode();
            if (status != 200) return new Page(status, "");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > MAX_PAGE_BYTES) throw new IOException("Page too large");
                    out.write(buffer, 0, read);
                }
                return new Page(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class AndroidPlatform implements Platform {
        private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(16), runnable -> {
                    Thread thread = new Thread(runnable, "sync-compat-media");
                    thread.setDaemon(true);
                    return thread;
                });
        private android.os.Handler main;

        AndroidPlatform() {
            worker.allowCoreThreadTimeOut(true);
        }

        @Override public void execute(Runnable task) {
            worker.execute(task);
        }

        @Override public synchronized void post(Runnable task) {
            if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(task);
        }

        @Override public void loadThumbnail(Object span, String url) {
            try {
                Method load = span.getClass().getMethod(SPAN_THUMBNAIL, Object.class);
                load.invoke(span, url);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // The card keeps no thumbnail.
            }
        }

        @Override public boolean isActive(Object player) {
            if (!(player instanceof android.view.View)) return false;
            android.view.View view = (android.view.View) player;
            // A window token is present only while the view is attached to a window.
            return view.getParent() != null && view.getWindowToken() != null;
        }

        @Override public void play(Object player, String url, boolean muted, boolean controls) {
            try {
                Method start = player.getClass().getMethod("x", String.class, boolean.class, boolean.class);
                start.invoke(player, url, muted, controls);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // The post keeps its thumbnail.
            }
        }

        @Override public Page fetch(String url) throws IOException {
            return get(url);
        }
    }
}
