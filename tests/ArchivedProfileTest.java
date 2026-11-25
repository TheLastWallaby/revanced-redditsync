package app.revanced.extension.synccompat;

import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ArchivedProfile against a loopback Arctic Shift (the fixed host routed to 127.0.0.1 by the OkHttp
 * test stand-in) that implements author search with an exclusive {@code before}, newest first, and
 * ID lookups, over test-only data. Checks which Sync URLs and Reddit answers are replaced, the page
 * built for Sync's parser (order, kinds, after), paging to the end without gaps or repeats (including
 * items that share a second across a page boundary, and a restart that forgot the last page), the
 * exact requests sent (no Authorization or Cookie header), the messages, and every failure path
 * returning Reddit's own answer.
 */
public final class ArchivedProfileTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    /** One recorded request at the loopback archive. */
    private static final class Seen {
        final String path;
        final Map<String, String> query = new HashMap<>();
        final Set<String> headers = new HashSet<>();
        Seen(HttpExchange exchange) {
            path = exchange.getRequestURI().getRawPath();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            exchange.getRequestHeaders().keySet().forEach(name -> headers.add(name.toLowerCase()));
        }
        @Override public String toString() { return path + " " + query; }
    }

    private interface Answer { void send(HttpExchange exchange) throws IOException, InterruptedException; }

    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static final List<String> OTHER_HOST = new CopyOnWriteArrayList<>();
    private static volatile Answer answer;
    private static final List<String> TOASTS = new CopyOnWriteArrayList<>();

    private static final String USER = "Hidden_User";
    /** Archived items of USER: posts and comments, times in seconds. */
    private static final List<JSONObject> POSTS = new ArrayList<>(), COMMENTS = new ArrayList<>();
    private static final Set<String> VALID = new HashSet<>();

    private static final String HIDDEN = "{\"kind\":\"Listing\",\"data\":{\"after\":null,\"dist\":0,\"modhash\":\"\",\"children\":[],\"before\":null}}";
    private static final String VISIBLE = "{\"kind\":\"Listing\",\"data\":{\"after\":null,\"children\":[{\"kind\":\"t3\",\"data\":{\"id\":\"live1\"}}]}}";
    private static final String OVERVIEW = "https://oauth.reddit.com/user/" + USER + "/Overview.json?sort=new";

    public static void main(String[] args) throws Exception {
        data();
        messages();
        listings();
        empties();

        HttpServer archive = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        archive.createContext("/", exchange -> {
            SEEN.add(new Seen(exchange));
            try { answer.send(exchange); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (IOException clientGone) { /* the client gave up */ }
            finally { exchange.close(); }
        });
        archive.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        archive.start();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> { OTHER_HOST.add(exchange.getRequestURI().toString()); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        other.start();
        OkHttpClient.ROUTES.put(ArchivedProfile.HOST, "http://127.0.0.1:" + archive.getAddress().getPort());
        OkHttpClient.ROUTES.put("example.org", "http://127.0.0.1:" + other.getAddress().getPort());
        ArchivedProfile.platform = TOASTS::add;
        try {
            untouched();
            overview();
            sections();
            restart();
            failures();
            limits();
            onDemand();
        } finally {
            archive.stop(0);
            other.stop(0);
        }
        System.out.println("PASS: " + checks + " archived profile checks (loopback archive: which listings are replaced, page order, "
                + "paging to the end without gaps or repeats, ID lookup after a restart, failures keep Reddit's answer; "
                + "no Authorization or Cookie header)");
    }

    // ---- Test data and the fake archive ----

    private static void data() throws Exception {
        // 40 posts and 40 comments, interleaved in time. Every sixth pair shares a second, including
        // post 12 / comment 12, which the first Overview page (25 items) splits.
        long t = 1_790_000_000L;
        for (int n = 0; n < 40; n++) {
            long post = t - n * 1000L, comment = n % 6 == 0 ? post : t - n * 1000L - 500;
            POSTS.add(new JSONObject().put("id", "p" + Integer.toString(n, 36)).put("name", "t3_p" + Integer.toString(n, 36))
                    .put("author", n % 2 == 0 ? USER : USER.toLowerCase()).put("created_utc", post).put("title", "Post " + n)
                    .put("selftext", "").put("subreddit", "pics").put("_meta", new JSONObject().put("retrieved_2nd_on", 1)));
            COMMENTS.add(new JSONObject().put("id", "c" + Integer.toString(n, 36)).put("name", "t1_c" + Integer.toString(n, 36))
                    .put("author", USER).put("created_utc", comment).put("body", "Comment " + n).put("link_id", "t3_x" + n)
                    .put("link_title", "Thread " + n).put("subreddit", "news"));
            VALID.add("t3_p" + Integer.toString(n, 36));
            VALID.add("t1_c" + Integer.toString(n, 36));
        }
        // Entries a page must never contain.
        POSTS.add(new JSONObject().put("id", "other1").put("author", "someone_else").put("created_utc", t - 1500).put("title", "x"));
        POSTS.add(new JSONObject().put("id", "BAD-ID").put("author", USER).put("created_utc", t - 2500).put("title", "x"));
        POSTS.add(new JSONObject().put("id", "notime").put("author", USER).put("title", "x"));
        POSTS.add(new JSONObject().put("id", "notitle").put("author", USER).put("created_utc", t - 3500));
        COMMENTS.add(new JSONObject().put("id", "nolink").put("author", USER).put("created_utc", t - 4500).put("body", "x"));
        COMMENTS.add(new JSONObject().put("id", 12345).put("author", USER).put("created_utc", t - 5500).put("body", "x").put("link_id", "t3_a"));
        Comparator<JSONObject> newest = Comparator.comparingLong(o -> -o.optLong("created_utc", 0));
        POSTS.sort(newest);
        COMMENTS.sort(newest);
    }

    /** Arctic Shift's author search and ID lookup, as probed: exclusive before, newest first, limit 1-100. */
    private static final Answer ARCHIVE = exchange -> {
        Seen seen = SEEN.get(SEEN.size() - 1);
        List<JSONObject> source = seen.path.startsWith("/api/posts/") ? POSTS : COMMENTS;
        JSONArray out = new JSONArray();
        if (seen.path.endsWith("/search")) {
            int limit = Integer.parseInt(seen.query.getOrDefault("limit", "25"));
            if (limit < 1 || limit > 100) { send(exchange, 400, "{\"data\":null,\"error\":\"'limit' must be between 1 and 100\"}"); return; }
            String before = seen.query.get("before");
            for (JSONObject item : source) {
                if (!item.optString("author").equalsIgnoreCase(seen.query.get("author"))) continue;
                if (before != null && item.optLong("created_utc", 0) >= Long.parseLong(before)) continue;
                if (out.length() == limit) break;
                out.put(new JSONObject(item.toString()));
            }
        } else {
            for (JSONObject item : source)
                if (String.valueOf(item.opt("id")).equals(seen.query.get("ids")))
                    out.put(new JSONObject().put("id", item.get("id")).put("created_utc", item.opt("created_utc")));
        }
        send(exchange, 200, new JSONObject().put("data", out).toString());
    };

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length != 0) try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static Answer json(int code, String body) { return exchange -> send(exchange, code, body); }

    private static NetworkResponse reddit(String body) {
        return new NetworkResponse(200, body.getBytes(StandardCharsets.UTF_8), false, 1234,
                List.of(new Header("Content-Type", "application/json; charset=UTF-8"), new Header("x-ratelimit-remaining", "99")));
    }

    private static JSONObject listing(NetworkResponse response) {
        return new JSONObject(new String(response.data, StandardCharsets.UTF_8)).getJSONObject("data");
    }

    // ---- Messages fit a two-line toast ----

    private static void messages() {
        for (String message : List.of(ArchivedProfile.SHOWING, ArchivedProfile.UNAVAILABLE, ArchivedProfile.NO_MORE,
                ArchivedProfile.NOTHING, ArchivedProfile.SHOWING_REDDIT)) {
            // Android 12+ cuts a text toast after two lines; a username would make the length unbounded.
            check(message.length() <= ArchivedProfile.MAX_MESSAGE, "message fits two toast lines: " + message.length() + " " + message);
            check(!message.contains("u/"), "message names no user: " + message);
            check(message.contains(message == ArchivedProfile.SHOWING_REDDIT ? "Reddit" : "Arctic Shift"),
                    "message names the source: " + message);
        }
        check(ArchivedProfile.MAX_MESSAGE <= 45, "limit stays within two lines on a narrow phone");
    }

    // ---- Which URLs and answers ----

    private static void listings() {
        Object[][] good = {
            {OVERVIEW, USER, ArchivedProfile.Section.OVERVIEW, null},
            {"https://oauth.reddit.com/user/a-b_c/Comments.json?sort=top&t=all", "a-b_c", ArchivedProfile.Section.COMMENTS, null},
            {"https://oauth.reddit.com/user/x/submitted.json?sort=new&after=t3_1abc&limit=25", "x", ArchivedProfile.Section.SUBMITTED, "t3_1abc"},
            {"https://www.reddit.com/user/someone/overview.json?sort=hot&after=t1_zz9&limit=25", "someone", ArchivedProfile.Section.OVERVIEW, "t1_zz9"},
            {"https://oauth.reddit.com/user/someone/overview/?after=", "someone", ArchivedProfile.Section.OVERVIEW, null},
            {"https://reddit.com/user/someone/COMMENTS", "someone", ArchivedProfile.Section.COMMENTS, null},
        };
        for (Object[] row : good) {
            ArchivedProfile.Listing listing = ArchivedProfile.listing((String) row[0]);
            check(listing != null, "profile listing " + row[0]);
            equal(row[1], listing.user, "user of " + row[0]);
            equal(row[2], listing.section, "section of " + row[0]);
            equal(row[3], listing.after, "after of " + row[0]);
        }
        for (String bad : new String[] {null, "", "not a url", "https://oauth.reddit.com/user/x/upvoted.json?sort=new",
                "https://oauth.reddit.com/user/x/saved.json", "https://oauth.reddit.com/user/x/gilded.json",
                "https://oauth.reddit.com/user/x/hidden.json", "https://oauth.reddit.com/user/x/about.json",
                "https://oauth.reddit.com/r/pics/hot.json?sort=hot", "https://oauth.reddit.com/search.json?q=author:x",
                "https://oauth.reddit.com/user/x/m/multi/.json", "http://oauth.reddit.com/user/x/overview.json",
                "https://reddit.com.example.org/user/x/overview.json", "https://notreddit.com/user/x/overview.json",
                "https://arctic-shift.photon-reddit.com/user/x/overview.json", "https://oauth.reddit.com/user/x.y/overview.json",
                "https://oauth.reddit.com/user/abcdefghijklmnopqrstu/overview.json", "https://oauth.reddit.com/user//overview.json",
                "https://oauth.reddit.com/user/x/../y/overview.json", "https://oauth.reddit.com/user/x%2Fy/overview.json",
                "https://oauth.reddit.com/user/x/overview.json?after=t2_abc", "https://oauth.reddit.com/user/x/overview.json?after=t1_ABC",
                "https://oauth.reddit.com/user/x/overview.json?after=t1_a&after=t1_b",
                "https://oauth.reddit.com/user/x/overview.json?after=t1_abc%26x", "https://oauth.reddit.com/u/x/overview.json",
                "https://oauth.reddit.com/user/x/overview.json/extra"})
            equal(null, ArchivedProfile.listing(bad), "not an archived listing: " + bad);
    }

    private static void empties() {
        check(ArchivedProfile.empty(HIDDEN.getBytes(StandardCharsets.UTF_8)), "Reddit's empty listing");
        check(ArchivedProfile.empty("{\"data\":{\"children\":[]}}".getBytes(StandardCharsets.UTF_8)), "minimal empty listing");
        for (String body : new String[] {VISIBLE, "", "[]", "{}", "{\"data\":{}}", "{\"data\":{\"children\":{}}}", "<html>blocked</html>",
                "{\"error\":403,\"message\":\"Forbidden\"}", "{\"data\":{\"children\":[",
                "[{\"data\":{\"children\":[]}}]"})
            check(!ArchivedProfile.empty(body.getBytes(StandardCharsets.UTF_8)), "not an empty listing: " + body);
        check(!ArchivedProfile.empty(null), "no body");
    }

    private static void untouched() {
        answer = ARCHIVE;
        SEEN.clear();
        TOASTS.clear();
        for (String url : new String[] {"https://oauth.reddit.com/r/pics/hot.json?sort=hot",
                "https://oauth.reddit.com/user/x/upvoted.json?sort=new", null}) {
            NetworkResponse empty = reddit(HIDDEN);
            check(ArchivedProfile.response(url, empty) == empty, "other listing unchanged: " + url);
        }
        NetworkResponse visible = reddit(VISIBLE);
        check(ArchivedProfile.response(OVERVIEW, visible) == visible, "profile with items unchanged");
        NetworkResponse error = new NetworkResponse(403, HIDDEN.getBytes(StandardCharsets.UTF_8), false, 1, List.of());
        check(ArchivedProfile.response(OVERVIEW, error) == error, "non-200 unchanged");
        NetworkResponse html = reddit("<html>blocked</html>");
        check(ArchivedProfile.response(OVERVIEW, html) == html, "HTML unchanged");
        check(ArchivedProfile.response(OVERVIEW, null) == null, "no answer, no crash");
        check(SEEN.isEmpty(), "the archive is asked only for empty profile listings: " + SEEN);
        check(TOASTS.isEmpty(), "no message: " + TOASTS);
    }

    // ---- Paging ----

    /** Pages through a section; returns the fullnames in order and checks each page and request. */
    private static List<String> pages(String section, ArchivedProfile.Section expected) {
        return pages(section, expected, HIDDEN);
    }

    /** Pages through a section with this Reddit answer for every page. */
    private static List<String> pages(String section, ArchivedProfile.Section expected, String redditBody) {
        List<String> all = new ArrayList<>();
        String after = null;
        long last = Long.MAX_VALUE;
        for (int page = 0; page < 10; page++) {
            String url = "https://oauth.reddit.com/user/" + USER + "/" + section + ".json?sort=new"
                    + (after == null ? "" : "&after=" + after + "&limit=25");
            SEEN.clear();
            NetworkResponse reddit = reddit(redditBody);
            NetworkResponse result = ArchivedProfile.response(url, reddit);
            if (result == reddit) {
                check(after != null, section + ": first page archived");
                break;
            }
            equal(200, result.statusCode, "status");
            equal(1234L, result.networkTimeMs, "Reddit's network time kept");
            equal(reddit.allHeaders, result.allHeaders, "Reddit's headers kept");
            JSONObject root = new JSONObject(new String(result.data, StandardCharsets.UTF_8));
            equal("Listing", root.getString("kind"), "listing kind");
            JSONObject data = root.getJSONObject("data");
            JSONArray children = data.getJSONArray("children");
            check(children.length() > 0 && children.length() <= ArchivedProfile.PAGE, section + " page size " + children.length());
            equal(children.length(), data.getInt("dist"), "dist");
            for (int n = 0; n < children.length(); n++) {
                JSONObject child = children.getJSONObject(n), item = child.getJSONObject("data");
                String kind = child.getString("kind"), fullname = kind + "_" + item.getString("id");
                check(VALID.contains(fullname), "only valid archived items: " + fullname);
                check(expected != ArchivedProfile.Section.COMMENTS || kind.equals("t1"), "Comments shows comments: " + fullname);
                check(expected != ArchivedProfile.Section.SUBMITTED || kind.equals("t3"), "Submitted shows posts: " + fullname);
                check(!item.has("_meta"), "archive metadata removed");
                check(item.getLong("created_utc") <= last, "newest first: " + fullname);
                last = item.getLong("created_utc");
                check(!all.contains(fullname), "no repeats: " + fullname);
                all.add(fullname);
            }
            Object next = data.get("after");
            String lastName = all.get(all.size() - 1);
            if (next == JSONObject.NULL) {
                after = null;
            } else {
                equal(lastName, next, "after is the last item");
                after = (String) next;
            }
            // Requests: one per archive source, only username, limit, sort and before.
            Set<String> paths = new HashSet<>();
            for (Seen seen : SEEN) {
                paths.add(seen.path);
                check(seen.headers.stream().allMatch(h -> Set.of("accept", "user-agent", "host", "connection", "cache-control", "pragma",
                        "accept-encoding").contains(h)), "no Authorization, Cookie or other header: " + seen.headers);
                equal(USER, seen.query.get("author"), "author");
                equal("25", seen.query.get("limit"), "limit");
                equal("desc", seen.query.get("sort"), "sort");
                equal(page == 0 ? Set.of("author", "limit", "sort") : Set.of("author", "limit", "sort", "before"), seen.query.keySet(),
                        "parameters on page " + page);
            }
            equal(expected == ArchivedProfile.Section.OVERVIEW ? Set.of("/api/posts/search", "/api/comments/search")
                    : Set.of(expected == ArchivedProfile.Section.COMMENTS ? "/api/comments/search" : "/api/posts/search"), paths,
                    section + " sources on page " + page);
            equal(paths.size(), SEEN.size(), "one request per source (the paging item was remembered)");
            if (after == null) break;
        }
        check(after == null, section + " reached the end");
        return all;
    }

    private static void overview() {
        answer = ARCHIVE;
        ArchivedProfile.reset();
        TOASTS.clear();
        List<String> all = pages("Overview", ArchivedProfile.Section.OVERVIEW);
        equal(VALID, new HashSet<>(all), "every valid post and comment, once");
        equal(80, all.size(), "80 items over the pages");
        equal(List.of(ArchivedProfile.SHOWING), TOASTS,
                "message once, on the first page");

        TOASTS.clear();
        // Scrolling the same profile again (a reload) skips nothing from a second split by a page.
        equal(all, pages("overview", ArchivedProfile.Section.OVERVIEW), "a second scroll serves the same items");
        check(TOASTS.isEmpty(), "message only once per profile: " + TOASTS);
    }

    private static void sections() {
        answer = ARCHIVE;
        List<String> comments = pages("Comments", ArchivedProfile.Section.COMMENTS);
        equal(40, comments.size(), "every comment");
        List<String> posts = pages("Submitted", ArchivedProfile.Section.SUBMITTED);
        equal(40, posts.size(), "every post (author name case ignored)");

        // A user the archive does not know: Sync's own empty state, no message.
        SEEN.clear();
        TOASTS.clear();
        NetworkResponse empty = reddit(HIDDEN);
        check(ArchivedProfile.response("https://oauth.reddit.com/user/nobody/overview.json?sort=new", empty) == empty,
                "unknown user keeps Reddit's empty listing");
        equal(2, SEEN.size(), "asked both sources");
        check(TOASTS.isEmpty(), "no message for an unknown user: " + TOASTS);
    }

    /** After a restart the last page's item is looked up by ID to continue. */
    private static void restart() {
        answer = ARCHIVE;
        ArchivedProfile.reset();
        TOASTS.clear();
        JSONObject first = listing(ArchivedProfile.response(OVERVIEW, reddit(HIDDEN)));
        String after = first.getString("after");
        long boundary = 0;
        JSONArray children = first.getJSONArray("children");
        for (int n = 0; n < children.length(); n++) boundary = children.getJSONObject(n).getJSONObject("data").getLong("created_utc");

        ArchivedProfile.reset();
        SEEN.clear();
        TOASTS.clear();
        NetworkResponse second = ArchivedProfile.response(OVERVIEW + "&after=" + after + "&limit=25", reddit(HIDDEN));
        check(SEEN.size() == 3, "ID lookup, then both sources: " + SEEN);
        equal(after.startsWith("t1_") ? "/api/comments/ids" : "/api/posts/ids", SEEN.get(0).path, "looked up by kind");
        equal(Map.of("ids", after.substring(3), "fields", "id,created_utc"), SEEN.get(0).query, "only the ID");
        equal(String.valueOf(boundary + 1), SEEN.get(1).query.get("before"), "before from the looked-up time (same second included)");
        JSONArray next = listing(second).getJSONArray("children");
        Set<String> firstNames = new HashSet<>();
        for (int n = 0; n < children.length(); n++)
            firstNames.add(children.getJSONObject(n).getString("kind") + "_" + children.getJSONObject(n).getJSONObject("data").getString("id"));
        for (int n = 0; n < next.length(); n++) {
            String name = next.getJSONObject(n).getString("kind") + "_" + next.getJSONObject(n).getJSONObject("data").getString("id");
            check(!name.equals(after), "the paging item itself is not repeated");
        }
        equal("t3_pc", after, "the first page ends with post 12");
        Set<String> nextNames = new HashSet<>();
        for (int n = 0; n < next.length(); n++)
            nextNames.add(next.getJSONObject(n).getString("kind") + "_" + next.getJSONObject(n).getJSONObject("data").getString("id"));
        check(nextNames.contains("t1_cc"), "comment 12, from the same second as post 12, is on the next page: " + nextNames);
        check(TOASTS.isEmpty(), "no message on a later page: " + TOASTS);
        check(!firstNames.isEmpty(), "first page had items");

        // A t3 paging item and an item the archive does not have.
        SEEN.clear();
        ArchivedProfile.reset();
        ArchivedProfile.response("https://oauth.reddit.com/user/" + USER + "/submitted.json?sort=new&after=t3_p5&limit=25", reddit(HIDDEN));
        equal("/api/posts/ids", SEEN.get(0).path, "post looked up by ID");
        SEEN.clear();
        NetworkResponse unknown = reddit(HIDDEN);
        check(ArchivedProfile.response(OVERVIEW + "&after=t1_zzzz&limit=25", unknown) == unknown, "undatable paging item keeps Reddit's answer");
        equal(1, SEEN.size(), "only the lookup: " + SEEN);
    }

    // ---- Failures keep Reddit's answer ----

    private static void failures() {
        String unreachable = ArchivedProfile.UNAVAILABLE;
        Object[][] cases = {
            {"HTTP 503", json(503, "down")},
            {"HTTP 429", json(429, "{\"error\":\"Too many requests\"}")},
            {"HTTP 404", json(404, "{\"error\":\"Not found\"}")},
            {"HTML", json(200, "<html>maintenance</html>")},
            {"malformed", json(200, "{\"data\":[{\"id\":")},
            {"no data", json(200, "{\"data\":null,\"error\":\"Timeout\"}")},
            {"empty body", json(200, "")},
            {"redirect", (Answer) exchange -> {
                exchange.getResponseHeaders().add("Location", "https://example.org/api/posts/search?author=x");
                exchange.sendResponseHeaders(302, -1);
            }},
        };
        for (Object[] row : cases) {
            answer = (Answer) row[1];
            ArchivedProfile.reset();
            TOASTS.clear();
            NetworkResponse empty = reddit(HIDDEN);
            check(ArchivedProfile.response(OVERVIEW, empty) == empty, "Reddit's answer kept for " + row[0]);
            equal(List.of(unreachable), TOASTS, "message for " + row[0]);
        }
        check(OTHER_HOST.isEmpty(), "redirect targets never requested: " + OTHER_HOST);

        // A later page that fails.
        answer = ARCHIVE;
        ArchivedProfile.reset();
        String after = listing(ArchivedProfile.response(OVERVIEW, reddit(HIDDEN))).getString("after");
        answer = json(500, "oops");
        TOASTS.clear();
        NetworkResponse empty = reddit(HIDDEN);
        check(ArchivedProfile.response(OVERVIEW + "&after=" + after + "&limit=25", empty) == empty, "later page failure keeps Reddit's answer");
        equal(List.of(ArchivedProfile.NO_MORE), TOASTS, "later page message");

        // Nothing listening.
        answer = ARCHIVE;
        String route = OkHttpClient.ROUTES.get(ArchivedProfile.HOST);
        OkHttpClient.ROUTES.put(ArchivedProfile.HOST, "http://127.0.0.1:1");
        TOASTS.clear();
        empty = reddit(HIDDEN);
        check(ArchivedProfile.response(OVERVIEW, empty) == empty, "unreachable archive keeps Reddit's answer");
        equal(List.of(unreachable), TOASTS, "unreachable message");
        OkHttpClient.ROUTES.put(ArchivedProfile.HOST, route);

        // A misbehaving archive: items not older than before are dropped.
        answer = exchange -> send(exchange, 200, new JSONObject().put("data", new JSONArray().put(new JSONObject(POSTS.get(0).toString()))).toString());
        ArchivedProfile.reset();
        TOASTS.clear();
        empty = reddit(HIDDEN);
        check(ArchivedProfile.response(OVERVIEW + "&after=t3_p0&limit=25", empty) == empty, "only items before the paging item");
    }

    private static void limits() {
        int maxBytes = ArchivedProfile.MAX_RESPONSE_BYTES;
        long read = ArchivedProfile.READ_TIMEOUT_MS, call = ArchivedProfile.CALL_TIMEOUT_MS;
        try {
            ArchivedProfile.MAX_RESPONSE_BYTES = 4096;
            ArchivedProfile.reset();
            String big = new JSONObject().put("data", new JSONArray().put(new JSONObject(POSTS.get(0).toString()).put("selftext", "x".repeat(5000)))).toString();
            answer = json(200, big);
            TOASTS.clear();
            NetworkResponse empty = reddit(HIDDEN);
            check(ArchivedProfile.response(OVERVIEW, empty) == empty, "oversized (declared length) keeps Reddit's answer");
            answer = exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) { out.write(big.getBytes(StandardCharsets.UTF_8)); }
            };
            check(ArchivedProfile.response(OVERVIEW, empty) == empty, "oversized (chunked) keeps Reddit's answer");
            equal(2, TOASTS.size(), "oversized answers show the message");
            ArchivedProfile.MAX_RESPONSE_BYTES = maxBytes;

            ArchivedProfile.READ_TIMEOUT_MS = 400;
            ArchivedProfile.CALL_TIMEOUT_MS = 1_200;
            ArchivedProfile.reset();
            answer = exchange -> { Thread.sleep(3_000); send(exchange, 200, "{\"data\":[]}"); };
            long started = System.nanoTime();
            check(ArchivedProfile.response(OVERVIEW, empty) == empty, "slow archive keeps Reddit's answer");
            long slow = (System.nanoTime() - started) / 1_000_000;
            check(slow < 2_500, "gave up within the limit, took " + slow + " ms");

            answer = exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"data\":[".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    for (int n = 0; n < 40; n++) { Thread.sleep(150); out.write(' '); out.flush(); }
                    out.write("]}".getBytes(StandardCharsets.UTF_8));
                }
            };
            started = System.nanoTime();
            check(ArchivedProfile.response(OVERVIEW, empty) == empty, "trickling archive keeps Reddit's answer");
            long trickle = (System.nanoTime() - started) / 1_000_000;
            check(trickle < 3_500, "whole call bounded, took " + trickle + " ms");
        } finally {
            ArchivedProfile.MAX_RESPONSE_BYTES = maxBytes;
            ArchivedProfile.READ_TIMEOUT_MS = read;
            ArchivedProfile.CALL_TIMEOUT_MS = call;
            ArchivedProfile.reset();
        }
    }

    // ---- The archive on request, for a profile that hides only some items ----

    private static void onDemand() {
        answer = ARCHIVE;
        ArchivedProfile.reset();
        TOASTS.clear();
        SEEN.clear();
        String other = "https://oauth.reddit.com/user/someone/overview.json?sort=new";
        String upvoted = "https://oauth.reddit.com/user/" + USER + "/upvoted.json?sort=new";

        // The sheet's row: titled by state, only for a valid username.
        equal(ArchivedProfile.SHOW_ARCHIVE, ArchivedProfile.archiveRow(USER), "row offers the archive");
        equal(ArchivedProfile.SHOW_ARCHIVE, ArchivedProfile.archiveRow(" " + USER + " "), "Sync's username trimmed");
        for (String user : new String[] {null, "", "has space", "a/b", "x".repeat(21), "u/" + USER, "名前"})
            equal(null, ArchivedProfile.archiveRow(user), "no row for username " + user);
        check(!ArchivedProfile.archiveShown(ArchivedProfile.SHOW_ARCHIVE), "row not highlighted while off");
        check(!ArchivedProfile.archiveShown(null), "no title, not highlighted");
        equal(0, ArchivedProfile.archiveIcon(), "no icon without Sync's drawable");
        ArchivedProfile.Platform saved = ArchivedProfile.platform;
        ArchivedProfile.platform = new ArchivedProfile.Platform() {
            @Override public void toast(String message) { TOASTS.add(message); }
            @Override public int icon() { return 0x7f080123; }
        };
        equal(0x7f080123, ArchivedProfile.archiveIcon(), "Sync's restore drawable");
        ArchivedProfile.platform = new ArchivedProfile.Platform() {
            @Override public void toast(String message) { TOASTS.add(message); }
            @Override public int icon() { throw new IllegalStateException("no resources"); }
        };
        equal(0, ArchivedProfile.archiveIcon(), "a failing lookup shows no icon");
        ArchivedProfile.platform = saved;

        // Sync's own sections pass through and change nothing.
        for (String title : new String[] {"Overview", "Comments", "Submitted", "Upvoted", "Saved", null, ""})
            equal(title, ArchivedProfile.section(USER, title), "section " + title + " unchanged");
        equal(ArchivedProfile.SHOW_ARCHIVE, ArchivedProfile.archiveRow(USER), "still off");

        // Off: a profile with items is Reddit's, as before.
        NetworkResponse visible = reddit(VISIBLE);
        check(ArchivedProfile.response(OVERVIEW, visible) == visible, "off: Reddit's items");
        // The empty-profile message was already shown once for this user.
        check(ArchivedProfile.response(OVERVIEW, reddit(HIDDEN)) != null, "empty profile archived");
        equal(List.of(ArchivedProfile.SHOWING), TOASTS, "first automatic message");
        TOASTS.clear();

        // The section Sync last loaded is the one reloaded; another section reloads Overview.
        ArchivedProfile.response("https://oauth.reddit.com/user/" + USER.toLowerCase() + "/comments.json?sort=new&limit=25", reddit(VISIBLE));
        SEEN.clear();
        equal("Comments", ArchivedProfile.section(USER, ArchivedProfile.SHOW_ARCHIVE), "turned on: Comments reloaded");
        check(SEEN.isEmpty(), "turning it on asks the archive nothing by itself: " + SEEN);
        check(TOASTS.isEmpty(), "no message until the archived page loads: " + TOASTS);
        equal(ArchivedProfile.SHOW_REDDIT, ArchivedProfile.archiveRow(USER.toUpperCase()), "row offers Reddit, any case");
        check(ArchivedProfile.archiveShown(ArchivedProfile.archiveRow(USER)), "row highlighted while on");
        ArchivedProfile.response(upvoted, reddit(VISIBLE));
        equal("Overview", ArchivedProfile.section(USER, ArchivedProfile.SHOW_ARCHIVE), "from Upvoted: Overview");
        ArchivedProfile.response("https://oauth.reddit.com/user/" + USER + "/Submitted?sort=new", reddit(VISIBLE));
        equal("Submitted", ArchivedProfile.section(USER, ArchivedProfile.SHOW_ARCHIVE), "Submitted remembered");
        ArchivedProfile.response("https://evil.example/user/" + USER + "/comments.json", reddit(VISIBLE));
        equal("Submitted", ArchivedProfile.section(USER, ArchivedProfile.SHOW_ARCHIVE), "other hosts ignored");
        equal("Overview", ArchivedProfile.section(null, ArchivedProfile.SHOW_ARCHIVE), "no username: Overview, nothing turned on");
        equal("Overview", ArchivedProfile.section("bad name", ArchivedProfile.SHOW_REDDIT), "invalid username: Overview");
        TOASTS.clear();

        // On: every page of the three sections comes from the archive, though Reddit had items.
        List<String> all = pages("Overview", ArchivedProfile.Section.OVERVIEW, VISIBLE);
        equal(VALID, new HashSet<>(all), "on: every archived post and comment, including ones Reddit hides");
        equal(List.of(ArchivedProfile.SHOWING), TOASTS, "the message again after turning it on, once");
        TOASTS.clear();
        equal(40, pages("Comments", ArchivedProfile.Section.COMMENTS, VISIBLE).size(), "on: archived comments");
        equal(40, pages("Submitted", ArchivedProfile.Section.SUBMITTED, VISIBLE).size(), "on: archived posts");
        equal(80, pages("Overview", ArchivedProfile.Section.OVERVIEW, HIDDEN).size(), "on: an empty answer too");
        check(TOASTS.isEmpty(), "message once per turning on: " + TOASTS);

        // Left alone while on: other users, other sections, errors.
        SEEN.clear();
        NetworkResponse someone = reddit(VISIBLE), liked = reddit(VISIBLE);
        check(ArchivedProfile.response(other, someone) == someone, "another user's profile is Reddit's");
        check(ArchivedProfile.response(upvoted, liked) == liked, "Upvoted is Reddit's");
        NetworkResponse error = new NetworkResponse(500, VISIBLE.getBytes(StandardCharsets.UTF_8), false, 1, List.of());
        check(ArchivedProfile.response(OVERVIEW, error) == error, "non-200 unchanged");
        check(SEEN.isEmpty(), "the archive is not asked for them: " + SEEN);

        // On, but the archive has nothing: an empty list, not Reddit's partial one.
        equal("Overview", ArchivedProfile.section("nobody", ArchivedProfile.SHOW_ARCHIVE), "turned on for an unknown user");
        TOASTS.clear();
        NetworkResponse none = ArchivedProfile.response("https://oauth.reddit.com/user/nobody/overview.json?sort=new", reddit(VISIBLE));
        equal(0, listing(none).getJSONArray("children").length(), "nothing archived: empty list");
        equal(JSONObject.NULL, listing(none).get("after"), "and the list ends");
        equal(1234L, none.networkTimeMs, "Reddit's network time kept");
        equal(List.of(ArchivedProfile.NOTHING), TOASTS, "says the archive has nothing");
        TOASTS.clear();
        none = ArchivedProfile.response("https://oauth.reddit.com/user/nobody/overview.json?sort=new&after=t3_zzzz&limit=25", reddit(VISIBLE));
        equal(0, listing(none).getJSONArray("children").length(), "an undatable later page ends the list");
        check(TOASTS.isEmpty(), "no message on a later page: " + TOASTS);

        // On, but the archive is down: the first page keeps Reddit's answer, a later page ends the list.
        String after = listing(ArchivedProfile.response(OVERVIEW, reddit(VISIBLE))).getString("after");
        answer = json(503, "down");
        TOASTS.clear();
        NetworkResponse first = reddit(VISIBLE);
        check(ArchivedProfile.response(OVERVIEW, first) == first, "archive down: Reddit's first page");
        equal(List.of(ArchivedProfile.UNAVAILABLE), TOASTS, "archive down message");
        TOASTS.clear();
        NetworkResponse later = ArchivedProfile.response(OVERVIEW + "&after=" + after + "&limit=25", reddit(VISIBLE));
        equal(0, listing(later).getJSONArray("children").length(), "archive down later: list ends, no Reddit items mixed in");
        equal(List.of(ArchivedProfile.NO_MORE), TOASTS, "later page message");
        answer = ARCHIVE;

        // Off again: Reddit's profile, the section reloaded, a message; turning off twice says nothing.
        TOASTS.clear();
        ArchivedProfile.response("https://oauth.reddit.com/user/" + USER + "/comments.json?sort=new", reddit(VISIBLE));
        equal("Comments", ArchivedProfile.section(USER, ArchivedProfile.SHOW_REDDIT), "turned off: Comments reloaded");
        equal(List.of(ArchivedProfile.SHOWING_REDDIT), TOASTS, "says Reddit's profile is back");
        equal(ArchivedProfile.SHOW_ARCHIVE, ArchivedProfile.archiveRow(USER), "row offers the archive again");
        TOASTS.clear();
        ArchivedProfile.section(USER, ArchivedProfile.SHOW_REDDIT);
        check(TOASTS.isEmpty(), "already off: no message: " + TOASTS);
        SEEN.clear();
        visible = reddit(VISIBLE);
        check(ArchivedProfile.response(OVERVIEW, visible) == visible, "off: Reddit's items again");
        check(SEEN.isEmpty(), "off: archive not asked: " + SEEN);
        check(ArchivedProfile.response(OVERVIEW, reddit(HIDDEN)) != null && SEEN.size() == 2, "off: an empty profile still archived");

        ArchivedProfile.section(USER, ArchivedProfile.SHOW_ARCHIVE);
        ArchivedProfile.reset();
        equal(ArchivedProfile.SHOW_ARCHIVE, ArchivedProfile.archiveRow(USER), "a restart forgets the choice");
        visible = reddit(VISIBLE);
        check(ArchivedProfile.response(OVERVIEW, visible) == visible, "after a restart: Reddit's items");
    }
}
