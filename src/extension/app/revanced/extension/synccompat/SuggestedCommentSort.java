package app.revanced.extension.synccompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Uses the subreddit's suggested comment sort when Sync's comment sort is "Suggested".
 *
 * <p>Sync 23033 reads only the post's own {@code suggested_sort}. When a post has none, Sync's
 * comments screen ({@code CommentsFragment.F}) labels the sort "Top" and its comments request
 * ({@code y8.g.f}) leaves out {@code sort}, so a subreddit's suggested sort, such as "new", is not
 * used. Reddit keeps that sort in the subreddit's {@code about} data
 * ({@code suggested_comment_sort}), not on each post.
 *
 * <p>The patch "Sync: use subreddit suggested comment sort" adds three hooks:
 * <ul>
 * <li>{@link #commentsUrl}: Sync's comments request asks for its URL. For a post's comments with no
 *     {@code sort}, the post's own suggested sort is used if Sync parsed one; otherwise the
 *     subreddit's, looked up once per subreddit from Reddit's {@code /r/<name>/about.json} with
 *     Sync's own Authorization and User-Agent and kept for some hours. The sort is added to the URL
 *     explicitly. The lookup runs only on Sync's network thread; on the main thread (Sync logs the
 *     URL while building the request) only an already known sort is used.</li>
 * <li>{@link #postSort}: Sync's post parser ({@code PostParserHelper.a}, {@code c9.c.a}) reads a
 *     post's {@code suggested_sort}. A post without one gets its subreddit's known suggested sort,
 *     so Sync's sort label and sort menu show the sort the comments were loaded with.</li>
 * <li>{@link #threadUrl}: Sync's URL builder. For a comment thread (a link to one comment) Sync
 *     never asked for a sort; a chosen sort is now asked for, and "Suggested" is resolved as
 *     above.</li>
 * </ul>
 * "Load more comments" requests made with the "Suggested" sort use the sort sent for that post
 * ({@link #requestSort}). Explicitly chosen sorts of a post's comments are left alone. If the
 * lookup fails, the request is unchanged and Reddit chooses as before. Never throws; nothing is
 * logged.
 */
public final class SuggestedCommentSort {
    interface Connections { HttpURLConnection open(String url) throws IOException; }
    interface MainThread { boolean current(); }

    static Connections connections = url -> (HttpURLConnection) new URL(url).openConnection();
    static MainThread mainThread = () -> {
        try {
            return android.os.Looper.getMainLooper() == android.os.Looper.myLooper();
        } catch (Throwable unavailable) {
            return true; // Unknown thread: never block it.
        }
    };
    /** Total time for one subreddit lookup. Non-final so tests can shorten it. */
    static int lookupMs = 2500;
    /** How long a subreddit's answer is kept. */
    static long knownMs = 6 * 3600_000L;
    /** How long to wait before asking again after a failed lookup. */
    static long failedMs = 5 * 60_000L;
    static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_POSTS = 500;
    private static final String BLANK = "blank";

    /** Reddit's comment sorts a subreddit or post can suggest, as its API names them. */
    static final Set<String> SORTS = new HashSet<>(Arrays.asList(
            "confidence", "top", "new", "controversial", "old", "random", "qa", "live"));
    /** Listing names that look like subreddits but have no about page of their own. */
    private static final Set<String> NOT_SUBREDDITS = new HashSet<>(Arrays.asList(
            "all", "popular", "random", "randnsfw", "myrandom", "friends", "mod"));
    private static final Pattern COMMENTS = Pattern.compile(
            "/(?:r/([A-Za-z0-9_]{2,21})/)?comments/([a-z0-9]{1,13})(?:/_/[a-z0-9]{1,13})?\\.json");
    private static final Pattern SUBREDDIT = Pattern.compile("[A-Za-z0-9_]{2,21}");
    private static final Pattern POST_ID = Pattern.compile("[a-z0-9]{1,13}");

    private static final class Known {
        final String sort; // "" when the subreddit suggests none.
        final long until;
        Known(String sort, long until) { this.sort = sort; this.until = until; }
    }

    private static final Map<String, Known> subreddits = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Each post's own suggested sort, as Sync parsed it ("" for none). */
    private static final Map<String, String> postSorts = lru();
    /** The sort added to each post's comments request. */
    private static final Map<String, String> sent = lru();

    private SuggestedCommentSort() {}

    private static Map<String, String> lru() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > MAX_POSTS; }
        });
    }

    static void reset() {
        subreddits.clear();
        locks.clear();
        postSorts.clear();
        sent.clear();
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    static String sort(String value) {
        if (value == null) return null;
        String sort = value.trim().toLowerCase(Locale.ROOT);
        return SORTS.contains(sort) ? sort : null;
    }

    /**
     * Sync's post parser hook: the suggested sort to store for a post. {@code parsed} is what Sync
     * read from the post's {@code suggested_sort} (null when none). A post's own sort is kept and
     * remembered; a post without one gets its subreddit's sort if that is already known.
     */
    public static String postSort(JSONObject post, String parsed) {
        try {
            if (post == null || !post.has("title")) return parsed;
            String id = post.optString("id", "");
            String own = sort(parsed);
            boolean other = own == null && parsed != null && !parsed.trim().isEmpty();
            // Any other value, such as "blank", is the post's own choice: keep it and suggest nothing.
            if (POST_ID.matcher(id).matches()) postSorts.put(id, own != null ? own : other ? BLANK : "");
            if (own != null || other) return parsed;
            String subreddit = post.optString("subreddit", "");
            Known known = known(subreddit);
            return known != null && !known.sort.isEmpty() ? known.sort : parsed;
        } catch (Throwable unexpected) {
            return parsed;
        }
    }

    /** Sync's comments request URL hook ({@code y8.g.getUrl}). */
    public static String commentsUrl(Object request, String url) {
        try {
            if (url == null) return null;
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawAuthority() == null) return url;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("reddit.com") && !host.endsWith(".reddit.com")) return url;
            Matcher path = COMMENTS.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (!path.matches() || hasParameter(uri.getRawQuery(), "sort") || hasParameter(uri.getRawQuery(), "comment")) return url;
            String post = path.group(2), subreddit = path.group(1);
            String sort = postSorts.get(post);
            if (BLANK.equals(sort)) return url;
            if (sort == null || sort.isEmpty()) {
                if (subreddit == null) return url;
                sort = subredditSort(request, "https://" + uri.getRawAuthority(), subreddit);
            }
            if (sort == null || sort.isEmpty()) return url;
            sent.put(post, sort);
            return url + (url.indexOf('?') < 0 ? "?" : url.endsWith("?") || url.endsWith("&") ? "" : "&") + "sort=" + sort;
        } catch (Throwable unexpected) {
            return url;
        }
    }

    /**
     * Sync's comments URL builder hook ({@code y8.g.f}, at its return). For a comment thread
     * ({@code /_/<comment>}) Sync never adds a sort, so a thread always came back in Reddit's
     * default order whatever sort was chosen. A chosen sort is added here as Sync adds it for a
     * post's comments; the "Suggested" sort (or none) is left to {@link #commentsUrl}.
     */
    public static String threadUrl(String url, String comment, String sort) {
        try {
            if (url == null || comment == null || comment.isEmpty() || sort == null || sort.isEmpty()
                    || "suggested".equalsIgnoreCase(sort)) return url;
            int query = url.indexOf('?');
            if (query >= 0 && hasParameter(url.substring(query + 1), "sort")) return url;
            String value = sort.toLowerCase(Locale.ENGLISH).replace("&", "");
            if (!value.matches("[a-z0-9_]+")) return url;
            return url + (query < 0 ? "?" : url.endsWith("?") || url.endsWith("&") ? "" : "&") + "sort=" + value;
        } catch (Throwable unexpected) {
            return url;
        }
    }

    /**
     * The sort for a "load more comments" request. Returns Reddit's name for an explicitly chosen
     * sort ("best" is Reddit's "confidence"), the sort sent with the post's comments request for
     * "Suggested" (or no sort), or null to leave {@code sort} out.
     */
    public static String requestSort(String sort, String linkId) {
        if (sort != null && !sort.isEmpty() && !"suggested".equalsIgnoreCase(sort)) {
            return "best".equalsIgnoreCase(sort) ? "confidence" : sort;
        }
        if (linkId == null) return null;
        String post = linkId.startsWith("t3_") ? linkId.substring(3) : linkId;
        return sent.get(post.toLowerCase(Locale.ROOT));
    }

    private static boolean hasParameter(String query, String name) {
        if (query == null) return false;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if ((equals < 0 ? part : part.substring(0, equals)).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static Known known(String subreddit) {
        if (subreddit == null || !SUBREDDIT.matcher(subreddit).matches()) return null;
        Known known = subreddits.get(subreddit.toLowerCase(Locale.ROOT));
        return known != null && known.until - now() > 0 ? known : null;
    }

    /** The subreddit's suggested sort, "" for none, or null when unknown and not looked up here. */
    static String subredditSort(Object request, String origin, String subreddit) {
        if (!SUBREDDIT.matcher(subreddit).matches()) return null;
        String key = subreddit.toLowerCase(Locale.ROOT);
        if (NOT_SUBREDDITS.contains(key)) return "";
        Known known = known(subreddit);
        if (known != null) return known.sort;
        if (mainThread.current() || canceled(request)) return null;
        Object lock = locks.get(key);
        if (lock == null) {
            Object made = new Object();
            lock = locks.putIfAbsent(key, made);
            if (lock == null) lock = made;
        }
        synchronized (lock) {
            known = known(subreddit);
            if (known != null) return known.sort;
            String sort;
            long keep;
            try {
                sort = lookup(request, origin + "/r/" + subreddit + "/about.json?raw_json=1");
                keep = knownMs;
            } catch (Throwable failed) {
                sort = "";
                keep = failedMs;
            }
            subreddits.put(key, new Known(sort, now() + keep));
            return sort;
        }
    }

    private static boolean canceled(Object request) {
        try {
            return request instanceof com.android.volley.Request && ((com.android.volley.Request<?>) request).isCanceled();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** "" when the subreddit suggests no sort or is missing; throws when unknown (retried later). */
    private static String lookup(Object request, String url) throws Exception {
        long deadline = now() + lookupMs;
        HttpURLConnection connection = connections.open(url);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(lookupMs);
            connection.setReadTimeout(lookupMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (request instanceof com.android.volley.Request) {
                Map<String, String> headers = ((com.android.volley.Request<?>) request).getHeaders();
                if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) continue;
                    if ("Authorization".equalsIgnoreCase(header.getKey()) || "User-Agent".equalsIgnoreCase(header.getKey()))
                        connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            connection.connect();
            int status = connection.getResponseCode();
            // Banned or missing: nothing to suggest. A private subreddit's 403 is retried later.
            if (status == 404) return "";
            if (status != 200 || connection.getContentLength() > MAX_BYTES) throw new IOException("HTTP " + status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    long left = deadline - now();
                    if (left <= 0 || canceled(request)) throw new IOException("timeout");
                    connection.setReadTimeout((int) Math.max(1, left));
                    int count = in.read(buffer);
                    if (count == -1) break;
                    if (out.size() + count > MAX_BYTES) throw new IOException("too large");
                    out.write(buffer, 0, count);
                }
                JSONObject about = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (!"t5".equals(about.optString("kind"))) return "";
                JSONObject data = about.optJSONObject("data");
                String sort = data == null ? null : sort(data.optString("suggested_comment_sort", ""));
                return sort == null ? "" : sort;
            }
        } finally {
            connection.disconnect();
        }
    }
}
