package app.revanced.extension.synccompat;

import app.revanced.extension.shared.Utils;
import com.android.volley.NetworkResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Profile listings that Reddit answers with nothing, filled from the Arctic Shift archive.
 *
 * <p>Reddit lets users hide their posts and comments from their profile. Their Overview, Comments
 * and Submitted listings then come back empty, although the content is still public in the
 * communities where it was posted. Sync loads these listings with OAuthPostsRequest ({@code y8.k0})
 * from {@code user/<name>/<Section>.json}; the patch hands that request's URL and Reddit's answer to
 * {@link #response} before Sync parses it. When the answer is a listing with no children, the
 * archive's author search ({@code /api/posts/search}, {@code /api/comments/search}) supplies the
 * page instead, as an ordinary Reddit listing that Sync's own parser reads. Every other answer is
 * returned unchanged.
 *
 * <p>Pages hold {@link #PAGE} items, newest first (Overview merges posts and comments). Their
 * {@code after} is the last item's fullname, so Sync pages as usual; the item's {@code created_utc}
 * is remembered, or looked up by ID, and becomes the archive's exclusive {@code before}.
 *
 * <p>Only the validated username, and when paging a timestamp or one item ID, is sent, over HTTPS to
 * one fixed host, by a client of its own: no Sync interceptors, cookies, Reddit authorization or
 * redirects. Time and size are bounded. Nothing is logged. The archived copies are as Arctic Shift
 * saved them, not live Reddit data.
 */
public final class ArchivedProfile {
    static final String HOST = "arctic-shift.photon-reddit.com";
    static final String POSTS = "/api/posts/search";
    static final String COMMENTS = "/api/comments/search";
    static final String POST_IDS = "/api/posts/ids";
    static final String COMMENT_IDS = "/api/comments/ids";
    static final int PAGE = 25;
    static final int REMEMBERED = 2000;

    // Limits are non-final so tests can shorten them.
    static int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    static long CONNECT_TIMEOUT_MS = 10_000;
    static long READ_TIMEOUT_MS = 10_000;
    static long CALL_TIMEOUT_MS = 15_000;

    enum Section { OVERVIEW, COMMENTS, SUBMITTED }

    private static final Pattern PROFILE = Pattern.compile("/user/([A-Za-z0-9_-]{1,20})/(overview|comments|submitted)(?:\\.json)?/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FULLNAME = Pattern.compile("(t[13])_([0-9a-z]{1,13})");
    private static final Pattern ID = Pattern.compile("[0-9a-z]{1,13}");

    interface Platform {
        /** Shows a short message on Sync's main thread. */
        void toast(String message);
    }

    static Platform platform = new AndroidPlatform();
    private static volatile OkHttpClient client;

    /** created_utc of fullnames served from the archive, for the next page's before. */
    private static final Map<String, Long> CREATED = Collections.synchronizedMap(new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > REMEMBERED; }
    });
    /** Usernames whose archive message was already shown in this process. */
    private static final Set<String> ANNOUNCED = Collections.synchronizedSet(new HashSet<String>());

    private ArchivedProfile() {}

    /** One profile listing request, parsed from Sync's URL. */
    static final class Listing {
        final String user;
        final Section section;
        final String after;
        Listing(String user, Section section, String after) {
            this.user = user;
            this.section = section;
            this.after = after;
        }
    }

    /**
     * Called at the start of OAuthPostsRequest.parseNetworkResponse on Volley's network thread, with
     * the request's URL and Reddit's answer. Returns the answer Sync parses: Reddit's own, or an
     * archived page when Reddit's profile listing is empty. Never throws.
     */
    public static NetworkResponse response(String url, NetworkResponse reddit) {
        try {
            Listing listing = listing(url);
            if (listing == null || reddit == null || reddit.statusCode != 200 || !empty(reddit.data)) return reddit;
            byte[] page;
            try {
                page = page(listing);
            } catch (IOException | RuntimeException unreachable) {
                platform.toast(listing.after == null
                        ? "Reddit shows nothing for u/" + listing.user + ", and the Arctic Shift archive couldn't be reached."
                        : "Couldn't load more from the Arctic Shift archive.");
                return reddit;
            }
            if (page == null) return reddit;
            if (listing.after == null && ANNOUNCED.add(listing.user.toLowerCase(Locale.ROOT)))
                platform.toast("Reddit shows nothing for u/" + listing.user + ". Showing copies from the Arctic Shift archive.");
            return new NetworkResponse(200, page, false, reddit.networkTimeMs, reddit.allHeaders);
        } catch (Exception | LinkageError failed) {
            return reddit;
        }
    }

    /** The profile listing a Reddit URL asks for, or null for any other URL. */
    static Listing listing(String url) {
        if (url == null) return null;
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException invalid) {
            return null;
        }
        String scheme = parsed.getScheme(), host = parsed.getHost(), rawPath = parsed.getRawPath();
        if (scheme == null || !scheme.equalsIgnoreCase("https") || host == null || rawPath == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (!host.equals("reddit.com") && !host.endsWith(".reddit.com")) return null;
        Matcher path = PROFILE.matcher(rawPath);
        if (!path.matches()) return null;
        String after = null;
        String query = parsed.getRawQuery();
        if (query != null) for (String pair : query.split("&")) {
            if (!pair.startsWith("after=")) continue;
            String value = pair.substring(6);
            if (value.isEmpty()) continue;
            if (after != null || !FULLNAME.matcher(value).matches()) return null;
            after = value;
        }
        return new Listing(path.group(1), Section.valueOf(path.group(2).toUpperCase(Locale.ROOT)), after);
    }

    /** Whether Reddit's answer is a listing with no children. */
    static boolean empty(byte[] body) {
        if (body == null) return false;
        try {
            JSONObject data = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("data");
            JSONArray children = data == null ? null : data.optJSONArray("children");
            return children != null && children.length() == 0;
        } catch (JSONException notListing) {
            return false;
        }
    }

    /** One archived item of the page. */
    private static final class Item {
        final String kind, fullname;
        final long created;
        final JSONObject data;
        Item(String kind, String id, long created, JSONObject data) {
            this.kind = kind;
            this.fullname = kind + "_" + id;
            this.created = created;
            this.data = data;
        }
    }

    /**
     * The archived page as Reddit listing JSON, or null when the archive has nothing for it (no
     * items, or a paging item it cannot date). Unreachable archives and bad answers throw.
     */
    static byte[] page(Listing listing) throws IOException {
        Long boundary = null;
        if (listing.after != null) {
            boundary = created(listing.after);
            if (boundary == null) return null;
        }
        List<Item> items = new ArrayList<>();
        boolean more = false;
        if (listing.section != Section.COMMENTS) more |= search(POSTS, "t3", listing, boundary, items);
        if (listing.section != Section.SUBMITTED) more |= search(COMMENTS, "t1", listing, boundary, items);
        Collections.sort(items, (a, b) -> Long.compare(b.created, a.created));
        List<Item> page = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : items) {
            if (page.size() == PAGE) { more = true; break; }
            if (seen.add(item.fullname)) page.add(item);
        }
        if (page.isEmpty()) return null;
        try {
            JSONArray children = new JSONArray();
            for (Item item : page) {
                children.put(new JSONObject().put("kind", item.kind).put("data", item.data));
                CREATED.put(item.fullname, item.created);
            }
            JSONObject data = new JSONObject().put("after", more ? page.get(page.size() - 1).fullname : JSONObject.NULL)
                    .put("before", JSONObject.NULL).put("dist", page.size()).put("children", children);
            return new JSONObject().put("kind", "Listing").put("data", data).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
    }

    /**
     * Adds the author's archived items older than the previous page's last item; true when the archive
     * returned a full page. Items from the same second as that item are asked for too, and kept unless
     * an earlier page served them.
     */
    private static boolean search(String path, String kind, Listing listing, Long boundary, List<Item> items) throws IOException {
        String user = listing.user;
        String url = "https://" + HOST + path + "?author=" + user + "&limit=" + PAGE + "&sort=desc"
                + (boundary == null ? "" : "&before=" + (boundary + 1));
        JSONArray data = data(fetch(url, path));
        for (int n = 0; n < data.length(); n++) {
            JSONObject item = data.optJSONObject(n);
            if (item == null) continue;
            Object id = item.opt("id"), author = item.opt("author");
            long created = item.optLong("created_utc", -1);
            if (!(id instanceof String) || !ID.matcher((String) id).matches() || created <= 0
                    || !(author instanceof String) || !user.equalsIgnoreCase((String) author)) continue;
            if (boundary != null) {
                String fullname = kind + "_" + id;
                if (created > boundary || created == boundary
                        && (fullname.equals(listing.after) || CREATED.containsKey(fullname))) continue;
            }
            if (kind.equals("t3") ? !(item.opt("title") instanceof String)
                    : !(item.opt("body") instanceof String) || !(item.opt("link_id") instanceof String)) continue;
            item.remove("_meta");
            items.add(new Item(kind, (String) id, created, item));
        }
        return data.length() >= PAGE;
    }

    /** created_utc of an item an earlier archived page ended with, or null when it is unknown. */
    static Long created(String fullname) throws IOException {
        Long known = CREATED.get(fullname);
        if (known != null) return known;
        Matcher name = FULLNAME.matcher(fullname);
        if (!name.matches()) return null;
        String path = name.group(1).equals("t3") ? POST_IDS : COMMENT_IDS;
        JSONArray data = data(fetch("https://" + HOST + path + "?ids=" + name.group(2) + "&fields=id,created_utc", path));
        for (int n = 0; n < data.length(); n++) {
            JSONObject item = data.optJSONObject(n);
            long created = item == null ? -1 : item.optLong("created_utc", -1);
            if (item != null && name.group(2).equals(item.opt("id")) && created > 0) {
                CREATED.put(fullname, created);
                return created;
            }
        }
        return null;
    }

    private static JSONArray data(String text) throws IOException {
        try {
            JSONArray data = new JSONObject(text).optJSONArray("data");
            if (data == null) throw new IOException("Archive answered without data");
            return data;
        } catch (JSONException malformed) {
            throw new IOException("Malformed archive answer");
        }
    }

    /** The archive's answer; other hosts, statuses, oversized bodies and connection errors throw. */
    static String fetch(String url, String path) throws IOException {
        Request request = new Request.Builder().url(url).get()
                .header("Accept", "application/json").header("User-Agent", "sync_for_reddit").build();
        Response response = client().newCall(request).execute();
        try {
            HttpUrl seen = response.request().url();
            if (!seen.isHttps() || !HOST.equals(seen.host()) || !path.equals(seen.encodedPath()))
                throw new IOException("Unexpected archive URL");
            if (response.code() != 200) throw new IOException("Archive answered HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty archive answer");
            return read(body);
        } finally {
            response.close();
        }
    }

    private static String read(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw new IOException("Archive answer too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = body.byteStream()) {
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > MAX_RESPONSE_BYTES) throw new IOException("Archive answer too large");
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** A client of its own: none of Sync's interceptors, no cookie jar, no redirects. */
    private static OkHttpClient client() {
        OkHttpClient current = client;
        if (current == null) {
            synchronized (ArchivedProfile.class) {
                if (client == null) client = newClient();
                current = client;
            }
        }
        return current;
    }

    static OkHttpClient newClient() {
        return new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /** Test hook: forget remembered items and shown messages, and build the client again. */
    static void reset() {
        client = null;
        CREATED.clear();
        ANNOUNCED.clear();
    }

    static final class AndroidPlatform implements Platform {
        private android.os.Handler main;

        @Override public synchronized void toast(String message) {
            android.content.Context context = Utils.getContext();
            if (context == null) return;
            if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
        }
    }
}
