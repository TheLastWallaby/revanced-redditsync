package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.VolleyError;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Native Reddit galleries for Sync 23033. No response bodies, URLs or headers are logged. */
public final class GalleryCompat {
    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_ITEMS = 100;
    static final int REFRESH_MS = 5000;
    static final String UNREADABLE = "Couldn't read Reddit's gallery response.";
    static final String WRONG_POST = "Reddit returned a different gallery post.";
    static final String UNAVAILABLE = "Reddit didn't provide this gallery's items.";
    static final String ITEM_UNAVAILABLE = "Media unavailable";
    private static final Pattern REQUEST_PATH = Pattern.compile("/comments/([a-z0-9]+)\\.json");
    private static final Pattern EXPIRY = Pattern.compile("(?:^|&)(?:expires|Expires|exp)=([0-9]{1,13})(?=&|$)");

    interface Connections { HttpURLConnection open(String url) throws Exception; }
    static Connections connections = url -> (HttpURLConnection) new URL(url).openConnection();

    private GalleryCompat() {}

    public static final class Result {
        public final o8.b gallery;
        public final VolleyError error;
        /** Canonical listing (not the two-listing thread) for Sync's saved-position cache. */
        public final NetworkResponse response;
        Result(o8.b gallery, VolleyError error, NetworkResponse response) {
            this.gallery = gallery;
            this.error = error;
            this.response = response;
        }
    }

    private static Result fail(String message) {
        return new Result(null, new VolleyError(message), null);
    }

    private static final class Data {
        final JSONObject listing, post, media;
        final JSONArray items;
        Data(JSONObject listing, JSONObject post, JSONObject media, JSONArray items) {
            this.listing = listing; this.post = post; this.media = media; this.items = items;
        }
    }

    /** Replaces the strict y8.q parser; called on Volley's worker, never the UI thread. */
    public static Result parseGallery(NetworkResponse response, Request<?> request) {
        try {
            if (request == null || request.isCanceled()) return fail("Gallery loading canceled.");
            String id = requestedId(request.getUrl());
            if (id == null) return fail(UNREADABLE);
            Data data = read(response, id);
            int position = data.listing.optInt("position", 0);
            long now = System.currentTimeMillis();
            boolean refresh = hasExpiredSource(data, now);
            boolean cacheable = true;
            if (refresh) {
                // One request per load, to the same fixed OAuth origin. No redirects, media
                // probes, recursive refresh, retry loops or credentials sent to a CDN.
                try {
                    NetworkResponse fresh = refresh(request, id);
                    Data replacement = read(fresh, id);
                    // A metadata refresh must not move the user's current gallery index.
                    // Keep the original item order/captions too: refreshed metadata is joined by ID.
                    JSONObject merged = new JSONObject(data.media.toString());
                    for (int i = 0; i < data.items.length(); i++) {
                        String mediaId = text(data.items.optJSONObject(i), "media_id");
                        if (mediaId != null && replacement.media.has(mediaId))
                            merged.put(mediaId, replacement.media.opt(mediaId));
                    }
                    data = new Data(data.listing, data.post, merged, data.items);
                    cacheable = !hasExpiredSource(data, System.currentTimeMillis());
                    response = fresh;
                } catch (Exception unavailable) {
                    cacheable = false; // Leave a later explicit open retryable.
                }
            }
            if (request.isCanceled()) return fail("Gallery loading canceled.");
            now = System.currentTimeMillis();
            o8.b gallery = new o8.b(data.items.length());
            for (int i = 0; i < data.items.length(); i++) {
                JSONObject item = data.items.optJSONObject(i);
                JSONObject media = metadata(data.media, item);
                String source = source(media, now);
                String caption = caption(item);
                gallery.b[i] = source == null ? "" : source;
                gallery.c[i] = source == null ? "" : preview(media, source, now);
                gallery.d[i] = source == null
                        ? ITEM_UNAVAILABLE + (caption.isEmpty() ? "" : "\n\n" + caption) : caption;
            }
            gallery.a = Math.max(0, Math.min(position, data.items.length() - 1));
            // Flatten a crosspost's chosen media into the requested post in the cache. Store
            // the refreshed metadata, so a successful refresh is not repeated on cache reads.
            data.post.put("gallery_data", new JSONObject().put("items", data.items));
            data.post.put("media_metadata", data.media);
            data.listing.put("position", gallery.a);
            NetworkResponse cached = cacheable ? new NetworkResponse(response.statusCode,
                    data.listing.toString().getBytes(StandardCharsets.UTF_8), response.headers,
                    response.notModified, response.networkTimeMs) : null;
            return new Result(gallery, null, cached);
        } catch (GalleryFailure expected) {
            return fail(expected.getMessage());
        } catch (Exception | StackOverflowError malformed) {
            return fail(UNREADABLE);
        }
    }

    static String requestedId(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || !"oauth.reddit.com".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null) return null;
            Matcher path = REQUEST_PATH.matcher(uri.getRawPath());
            return path.matches() ? path.group(1) : null;
        } catch (Exception invalid) { return null; }
    }

    private static final class GalleryFailure extends Exception {
        GalleryFailure(String message) { super(message); }
    }

    private static Data read(NetworkResponse response, String expected) throws Exception {
        if (response == null || response.data == null || response.data.length > MAX_BYTES
                || !((response.statusCode >= 200 && response.statusCode < 300)
                    || (response.statusCode == 304 && response.notModified))) throw new GalleryFailure(UNREADABLE);
        JSONTokener tokens = new JSONTokener(new String(response.data, StandardCharsets.UTF_8));
        Object root = tokens.nextValue();
        if (tokens.nextClean() != 0) throw new GalleryFailure(UNREADABLE);
        JSONObject listing;
        if (root instanceof JSONArray) {
            JSONArray thread = (JSONArray) root;
            if (thread.length() != 2 || !listing(thread.optJSONObject(1))) throw new GalleryFailure(UNREADABLE);
            listing = thread.optJSONObject(0);
        } else listing = root instanceof JSONObject ? (JSONObject) root : null;
        if (!listing(listing)) throw new GalleryFailure(UNREADABLE);
        JSONArray children = listing.getJSONObject("data").getJSONArray("children");
        if (children.length() != 1) throw new GalleryFailure(UNREADABLE);
        JSONObject thing = children.optJSONObject(0);
        JSONObject post = thing == null ? null : thing.optJSONObject("data");
        if (post == null || !"t3".equals(thing.optString("kind"))) throw new GalleryFailure(UNREADABLE);
        if (!identity(post, expected)) throw new GalleryFailure(WRONG_POST);
        JSONObject chosen = post;
        JSONArray items = items(post);
        JSONObject parent = parent(post);
        if (items == null || items.length() == 0) {
            chosen = parent;
            items = items(parent);
        }
        if (items == null || items.length() == 0 || items.length() > MAX_ITEMS) throw new GalleryFailure(UNAVAILABLE);
        JSONObject media = chosen.optJSONObject("media_metadata");
        media = media == null ? new JSONObject() : new JSONObject(media.toString());
        // Some crossposts have their own gallery order but only the parent's media metadata.
        if (chosen == post && parent != null) {
            JSONObject parentMedia = parent.optJSONObject("media_metadata");
            JSONArray parentItems = items(parent);
            if (parentMedia != null && parentItems != null) for (int i = 0; i < parentItems.length(); i++) {
                JSONObject item = parentItems.optJSONObject(i);
                String id = text(item, "media_id");
                if (id != null && media.optJSONObject(id) == null && parentMedia.optJSONObject(id) != null)
                    media.put(id, parentMedia.getJSONObject(id));
            }
        }
        return new Data(listing, post, media, items);
    }

    private static boolean listing(JSONObject value) {
        JSONObject data = value == null ? null : value.optJSONObject("data");
        return value != null && "Listing".equals(value.optString("kind"))
                && data != null && data.optJSONArray("children") != null;
    }

    private static boolean identity(JSONObject post, String id) {
        return id.equals(text(post, "id")) && ("t3_" + id).equals(text(post, "name"));
    }

    private static JSONArray items(JSONObject post) {
        JSONObject gallery = post == null ? null : post.optJSONObject("gallery_data");
        return gallery == null ? null : gallery.optJSONArray("items");
    }

    private static JSONObject parent(JSONObject post) {
        String name = text(post, "crosspost_parent");
        JSONArray parents = post.optJSONArray("crosspost_parent_list");
        if (name == null || !name.matches("t3_[a-z0-9]+") || parents == null) return null;
        for (int i = 0; i < parents.length(); i++) {
            JSONObject parent = parents.optJSONObject(i);
            if (parent != null && identity(parent, name.substring(3))) return parent;
        }
        return null;
    }

    private static JSONObject metadata(JSONObject metadata, JSONObject item) {
        String id = text(item, "media_id");
        JSONObject media = id == null ? null : metadata.optJSONObject(id);
        if (media != null && ((media.has("status") && !(media.opt("status") instanceof String))
                || (media.has("e") && !(media.opt("e") instanceof String)))) return null;
        String status = text(media, "status"), type = text(media, "e");
        return (media == null || !media.has("status") || "valid".equals(status))
                && (media == null || !media.has("e") || "Image".equals(type) || "AnimatedImage".equals(type)) ? media : null;
    }

    private static String source(JSONObject media, long now) {
        JSONObject source = media == null ? null : media.optJSONObject("s");
        return rendition(source, "AnimatedImage".equals(text(media, "e")), now);
    }

    private static String rendition(JSONObject value, boolean animated, long now) {
        for (String field : animated ? new String[] {"mp4", "gif", "u"} : new String[] {"u", "gif", "mp4"}) {
            String url = mediaUrl(text(value, field));
            if (url != null && !expired(url, now)) return url;
        }
        return null;
    }

    private static String preview(JSONObject media, String fallback, long now) {
        JSONArray previews = media.optJSONArray("p");
        if (previews != null) for (int i = 0; i < previews.length(); i++) {
            String url = rendition(previews.optJSONObject(i), false, now);
            if (url != null) return url;
        }
        return fallback;
    }

    static String mediaUrl(String value) {
        if (value == null) return null;
        // Decode HTML ampersands only; never decode/re-encode a signed query string.
        value = value.replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&");
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("i.redd.it".equalsIgnoreCase(host) || "preview.redd.it".equalsIgnoreCase(host)
                        || "external-preview.redd.it".equalsIgnoreCase(host) || "v.redd.it".equalsIgnoreCase(host))
                    && uri.getRawPath() != null && uri.getRawPath().length() > 1 ? value : null;
        } catch (Exception invalid) { return null; }
    }

    static boolean expired(String url, long now) {
        try {
            String query = new URI(url).getRawQuery();
            Matcher match = EXPIRY.matcher(query == null ? "" : query);
            while (match.find()) {
                long expiry = Long.parseLong(match.group(1));
                if (match.group(1).length() <= 10) expiry *= 1000;
                if (expiry <= now) return true;
            }
        } catch (Exception invalid) { /* Opaque signatures cannot establish expiry. */ }
        return false;
    }

    private static boolean hasExpiredSource(Data data, long now) {
        for (int i = 0; i < data.items.length(); i++) {
            JSONObject media = metadata(data.media, data.items.optJSONObject(i));
            JSONObject source = media == null ? null : media.optJSONObject("s");
            if (source == null || source(media, now) != null) continue;
            for (String field : new String[] {"u", "gif", "mp4"}) {
                String url = mediaUrl(text(source, field));
                if (url != null && expired(url, now)) return true;
            }
        }
        return false;
    }

    private static String text(JSONObject value, String key) {
        Object text = value == null ? null : value.opt(key);
        return text instanceof String && !((String) text).isEmpty() ? (String) text : null;
    }

    /**
     * Caption and outbound link exactly as Reddit sent them, like Sync's own gallery parser.
     * Sync's caption renderer (RedditHelper d7.f.r) unescapes them with WebsafeUtils (wc.p.a),
     * so decoding here would decode literal entities twice.
     */
    private static String caption(JSONObject item) {
        String caption = text(item, "caption"), outbound = text(item, "outbound_url");
        return (caption == null ? "" : caption + "\n\n") + (outbound == null ? "" : outbound);
    }

    private static NetworkResponse refresh(Request<?> request, String id) throws Exception {
        if (request.isCanceled()) throw new java.io.IOException();
        HttpURLConnection connection = connections.open(RequestCompat.galleryUrl(id));
        long deadline = System.nanoTime() + REFRESH_MS * 1_000_000L;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(REFRESH_MS);
            connection.setReadTimeout(REFRESH_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            Map<String, String> headers = request.getHeaders();
            if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
                if ("Authorization".equalsIgnoreCase(header.getKey()) || "User-Agent".equalsIgnoreCase(header.getKey()))
                    connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.connect();
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (request.isCanceled() || remaining <= 0) throw new java.io.IOException();
            connection.setReadTimeout((int) Math.max(1, remaining));
            int status = connection.getResponseCode();
            if (status != 200 || connection.getContentLength() > MAX_BYTES) throw new java.io.IOException();
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    long left = (deadline - System.nanoTime()) / 1_000_000L;
                    if (request.isCanceled() || left <= 0) throw new java.io.IOException();
                    connection.setReadTimeout((int) Math.max(1, left));
                    int count = in.read(buffer);
                    if (count == -1) break;
                    if (out.size() + count > MAX_BYTES) throw new java.io.IOException();
                    out.write(buffer, 0, count);
                }
                return new NetworkResponse(status, out.toByteArray(), responseHeaders(connection), false, 0);
            }
        } finally { connection.disconnect(); }
    }

    private static Map<String, String> responseHeaders(HttpURLConnection connection) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() != null && header.getValue() != null && !header.getValue().isEmpty())
                headers.put(header.getKey(), header.getValue().get(0));
        }
        return headers;
    }
}
