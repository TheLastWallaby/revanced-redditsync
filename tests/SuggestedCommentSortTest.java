package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * SuggestedCommentSort against a loopback Reddit with test-only tokens: which comments URLs gain a
 * sort, the subreddit lookup (Sync's Authorization and User-Agent only, at the request's host),
 * caching, failure, timeout, size and cancel handling, the main-thread rule, the parser hook, and
 * "load more" requests using the sort sent for their post.
 */
public final class SuggestedCommentSortTest {
    private static int checks;
    private static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, List<String>>> HEADERS = new CopyOnWriteArrayList<>();
    private static volatile boolean main;
    private static volatile int delayMs;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    static final class CommentsRequest extends Request<Void> {
        volatile boolean failHeaders;
        CommentsRequest(String url) { super(Method.GET, url, null); }
        @Override public Map<String, String> getHeaders() throws com.android.volley.AuthFailureError {
            if (failHeaders) throw new com.android.volley.AuthFailureError("no token");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "bearer test-only-token");
            headers.put("User-Agent", "android:sync-test:v1");
            headers.put("Cookie", "session=test-only-cookie");
            headers.put("X-Extra", "dropped");
            return headers;
        }
        @Override protected Response<Void> parseNetworkResponse(NetworkResponse response) { return null; }
        @Override protected void deliverResponse(Void response) {}
    }

    static String url(String sub, String post) {
        return "https://oauth.reddit.com/" + (sub == null ? "" : "r/" + sub + "/") + "comments/" + post
                + ".json?always_show_media=1&profile_img=true&limit=500";
    }
    static String sent(String url) { return SuggestedCommentSort.commentsUrl(new CommentsRequest(url), url); }

    public static void main(String[] args) throws Exception {
        HttpServer reddit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reddit.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            LOOKUPS.add(uri.getRawPath() + "?" + uri.getRawQuery());
            HEADERS.add(new TreeMap<>(exchange.getRequestHeaders()));
            String sub = uri.getRawPath().replaceFirst("^/r/([^/]+)/about\\.json$", "$1").toLowerCase(Locale.ROOT);
            int status = 200;
            String body;
            switch (sub) {
                case "asknew": body = about("new"); break;
                case "askqa": body = about("QA "); break;
                case "plain": body = about(null); break;
                case "odd": body = about("hot"); break;
                case "notsub": body = new JSONObject().put("kind", "Listing").put("data", new JSONObject()).toString(); break;
                case "gone": status = 404; body = "{\"error\":404}"; break;
                case "private": status = 403; body = "{\"error\":403}"; break;
                case "huge": body = about("new").replace("}", ",\"description\":\"" + "x".repeat(SuggestedCommentSort.MAX_BYTES) + "\"}"); break;
                case "garbled": body = "<html>"; break;
                case "slow":
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                    body = about("new");
                    break;
                default: status = 500; body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            catch (java.io.IOException closed) { /* The client gave up (timeout test). */ }
        });
        reddit.setExecutor(Executors.newCachedThreadPool());
        reddit.start();
        int port = reddit.getAddress().getPort();
        SuggestedCommentSort.Connections originalConnections = SuggestedCommentSort.connections;
        SuggestedCommentSort.MainThread originalThread = SuggestedCommentSort.mainThread;
        int originalLookup = SuggestedCommentSort.lookupMs;
        long originalFailed = SuggestedCommentSort.failedMs, originalKnown = SuggestedCommentSort.knownMs;
        SuggestedCommentSort.connections = url -> {
            URI uri = URI.create(url);
            check("https".equals(uri.getScheme()), "lookup uses https: " + url);
            return (HttpURLConnection) new URL("http://127.0.0.1:" + port + uri.getRawPath() + "?" + uri.getRawQuery()).openConnection();
        };
        SuggestedCommentSort.mainThread = () -> main;
        try {
            sorts();
            urls();
            threadUrls();
            lookups();
            failures();
            threads();
            parser();
            loadMore();
            System.out.println("PASS: " + checks + " suggested comment sort checks (URLs, comment threads, lookups, "
                    + "caching, failures, threads, parser hook, load more)");
        } finally {
            SuggestedCommentSort.connections = originalConnections;
            SuggestedCommentSort.mainThread = originalThread;
            SuggestedCommentSort.lookupMs = originalLookup;
            SuggestedCommentSort.failedMs = originalFailed;
            SuggestedCommentSort.knownMs = originalKnown;
            SuggestedCommentSort.reset();
            reddit.stop(0);
        }
    }

    static String about(String sort) {
        JSONObject data = new JSONObject().put("display_name", "x").put("suggested_comment_sort", sort == null ? JSONObject.NULL : sort);
        return new JSONObject().put("kind", "t5").put("data", data).toString();
    }

    static void reset() {
        SuggestedCommentSort.reset();
        LOOKUPS.clear();
        HEADERS.clear();
        main = false;
        delayMs = 0;
        SuggestedCommentSort.lookupMs = 2500;
        SuggestedCommentSort.failedMs = 5 * 60_000L;
        SuggestedCommentSort.knownMs = 6 * 3600_000L;
    }

    static void sorts() {
        for (String sort : List.of("confidence", "top", "new", "controversial", "old", "random", "qa", "live"))
            equal(sort, SuggestedCommentSort.sort(sort), "Reddit sort " + sort);
        equal("new", SuggestedCommentSort.sort(" NEW "), "trimmed, any case");
        for (String other : Arrays.asList(null, "", "hot", "best", "blank", "suggested", "new&x=1"))
            equal(null, SuggestedCommentSort.sort(other), "not a suggested sort: " + other);
    }

    static void urls() {
        reset();
        String base = url("AskNew", "abc12");
        equal(base + "&sort=new", sent(base), "subreddit sort added");
        equal(base + "&sort=new", sent(base), "again, from the cache");
        equal(1, LOOKUPS.size(), "one lookup");
        // Left alone.
        String[] unchanged = {
                base + "&sort=top", base.replace("?", "?sort=old&"), base + "&SORT=top",
                "https://oauth.reddit.com/r/AskNew/comments/abc12/_/c0ffee.json?context=100&sort=top",
                "https://oauth.reddit.com/r/AskNew/comments/abc12/_/C0FFEE.json?context=100",
                "https://oauth.reddit.com/r/AskNew/comments/abc12/_/.json?context=100",
                base + "&comment=c0ffee",
                "http://oauth.reddit.com/r/AskNew/comments/abc12.json?limit=1",
                "https://evil.example/r/AskNew/comments/abc12.json?limit=1",
                "https://reddit.com.evil.example/r/AskNew/comments/abc12.json?limit=1",
                "https://oauth.reddit.com/r/AskNew/comments/abc12/title.json?limit=1",
                "https://oauth.reddit.com/r/AskNew/about.json",
                "https://oauth.reddit.com/r/AskNew/comments/ABC12.json?limit=1",
                "https://oauth.reddit.com/r/a/comments/abc12.json?limit=1",
                url(null, "nosub1"),
                url("popular", "p1"), url("all", "p2"), url("random", "p3"),
        };
        for (String url : unchanged) equal(url, sent(url), "unchanged: " + url);
        String thread = "https://oauth.reddit.com/r/AskNew/comments/abc12/_/c0ffee.json?always_show_media=1&context=100";
        equal(thread + "&sort=new", sent(thread), "comment thread with no sort: subreddit's sort");
        String malformed = "not a url at all %%%";
        equal(malformed, SuggestedCommentSort.commentsUrl(new CommentsRequest(base), malformed), "malformed URL unchanged");
        equal(null, SuggestedCommentSort.commentsUrl(null, null), "null URL");
        equal(1, LOOKUPS.size(), "none of those looked anything up");
        // Another Reddit host is asked at that host.
        String www = "https://www.reddit.com/r/AskQa/comments/q1.json";
        String wwwSent = sent(www);
        equal(www + "?sort=qa", wwwSent, "URL without a query gains one; the about page's sort is normalized");
        equal("/r/AskQa/about.json?raw_json=1", LOOKUPS.get(1), "about page of the subreddit as written");
    }

    static void threadUrls() {
        String thread = "https://oauth.reddit.com/r/AskNew/comments/abc12/_/c0ffee.json?always_show_media=1&context=100&limit=500";
        String post = url("AskNew", "abc12");
        equal(thread + "&sort=new", SuggestedCommentSort.threadUrl(thread, "c0ffee", "new"), "chosen sort added to a thread");
        equal(thread + "&sort=top", SuggestedCommentSort.threadUrl(thread, "c0ffee", "Top"), "lower-cased as Sync does");
        equal(thread + "&sort=qa", SuggestedCommentSort.threadUrl(thread, "c0ffee", "Q&A"), "ampersand removed as Sync does");
        equal(thread, SuggestedCommentSort.threadUrl(thread, "c0ffee", "suggested"), "suggested left to the request");
        equal(thread, SuggestedCommentSort.threadUrl(thread, "c0ffee", "SUGGESTED"), "suggested in any case");
        equal(thread, SuggestedCommentSort.threadUrl(thread, "c0ffee", null), "no sort");
        equal(thread, SuggestedCommentSort.threadUrl(thread, "c0ffee", ""), "empty sort");
        equal(thread, SuggestedCommentSort.threadUrl(thread, "c0ffee", "new&x=1/"), "unsafe sort text not added");
        equal(post, SuggestedCommentSort.threadUrl(post, null, "new"), "a post's comments: Sync adds the sort itself");
        equal(post, SuggestedCommentSort.threadUrl(post, "", "new"), "empty comment: not a thread");
        equal(thread + "&sort=old", SuggestedCommentSort.threadUrl(thread + "&sort=old", "c0ffee", "new"), "existing sort kept");
        equal("https://x/comments/a/_/b.json?sort=new", SuggestedCommentSort.threadUrl("https://x/comments/a/_/b.json", "b", "new"),
                "URL without a query gains one");
        equal(null, SuggestedCommentSort.threadUrl(null, "c0ffee", "new"), "no URL");
    }

    static void lookups() {
        reset();
        sent(url("AskNew", "h1"));
        Map<String, List<String>> headers = HEADERS.get(0);
        equal(List.of("bearer test-only-token"), headers.get("Authorization"), "Sync's Authorization sent");
        equal(List.of("android:sync-test:v1"), headers.get("User-agent"), "Sync's User-Agent sent");
        check(!headers.containsKey("Cookie") && !headers.containsKey("X-extra"), "no other Sync header sent: " + headers.keySet());
        equal(List.of("application/json"), headers.get("Accept"), "asks for JSON");

        equal(url("Plain", "h2"), sent(url("Plain", "h2")), "no suggested sort: unchanged");
        equal(url("Odd", "h3"), sent(url("Odd", "h3")), "unknown sort name: unchanged");
        equal(url("NotSub", "h4"), sent(url("NotSub", "h4")), "not a subreddit answer: unchanged");
        equal(url("Gone", "h5"), sent(url("Gone", "h5")), "missing subreddit: unchanged");
        int made = LOOKUPS.size();
        sent(url("Plain", "h6")); sent(url("Odd", "h7")); sent(url("NotSub", "h8")); sent(url("Gone", "h9"));
        equal(made, LOOKUPS.size(), "answers without a sort are kept");

        // Kept for knownMs, then asked again.
        SuggestedCommentSort.knownMs = 0;
        SuggestedCommentSort.reset();
        LOOKUPS.clear();
        sent(url("AskNew", "k1"));
        sent(url("AskNew", "k2"));
        equal(2, LOOKUPS.size(), "an expired answer is looked up again");
    }

    static void failures() throws Exception {
        reset();
        for (String sub : List.of("Private", "Broken", "Huge", "Garbled")) {
            equal(url(sub, "f1"), sent(url(sub, "f1")), sub + ": unchanged");
        }
        int made = LOOKUPS.size();
        equal(4, made, "each failure asked once");
        for (String sub : List.of("Private", "Broken", "Huge", "Garbled")) sent(url(sub, "f2"));
        equal(made, LOOKUPS.size(), "failures wait before asking again");
        SuggestedCommentSort.failedMs = 0;
        SuggestedCommentSort.reset();
        LOOKUPS.clear();
        sent(url("Broken", "f3"));
        sent(url("Broken", "f4"));
        equal(2, LOOKUPS.size(), "after the wait a failure is retried");

        // A slow answer does not hold the request past the lookup time.
        reset();
        SuggestedCommentSort.lookupMs = 300;
        delayMs = 2000;
        long start = System.nanoTime();
        equal(url("Slow", "s1"), sent(url("Slow", "s1")), "slow lookup: unchanged");
        long took = (System.nanoTime() - start) / 1_000_000L;
        check(took < 1500, "slow lookup gave up in time: " + took + " ms");

        // No token: nothing is sent without Sync's headers.
        reset();
        CommentsRequest noToken = new CommentsRequest(url("AskNew", "t1"));
        noToken.failHeaders = true;
        equal(url("AskNew", "t1"), SuggestedCommentSort.commentsUrl(noToken, url("AskNew", "t1")), "no headers: unchanged");
        equal(0, LOOKUPS.size(), "no lookup without Sync's headers");

        // A cancelled request does not look anything up.
        reset();
        CommentsRequest cancelled = new CommentsRequest(url("AskNew", "c1"));
        cancelled.cancel();
        equal(url("AskNew", "c1"), SuggestedCommentSort.commentsUrl(cancelled, url("AskNew", "c1")), "cancelled: unchanged");
        equal(0, LOOKUPS.size(), "no lookup for a cancelled request");
        // Not a Volley request (no headers): looked up without Authorization.
        equal(url("AskNew", "o1") + "&sort=new", SuggestedCommentSort.commentsUrl("not a request", url("AskNew", "o1")),
                "plain lookup still works");
        check(!HEADERS.get(0).containsKey("Authorization"), "nothing invented for a non-Volley caller");
    }

    static void threads() throws Exception {
        reset();
        main = true;
        equal(url("AskNew", "m1"), sent(url("AskNew", "m1")), "main thread: unknown sort not waited for");
        equal(0, LOOKUPS.size(), "main thread never looks up");
        main = false;
        sent(url("AskNew", "m2"));
        main = true;
        equal(url("AskNew", "m3") + "&sort=new", sent(url("AskNew", "m3")), "main thread: known sort used");
        main = false;

        // Several network threads asking at once share one lookup.
        reset();
        delayMs = 200;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<String>> results = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int n = 0; n < 6; n++) {
            String post = "z" + n;
            results.add(pool.submit(() -> { go.await(); return sent(url("Slow", post)); }));
        }
        go.countDown();
        for (int n = 0; n < 6; n++) equal(url("Slow", "z" + n) + "&sort=new", results.get(n).get(5, TimeUnit.SECONDS), "concurrent " + n);
        pool.shutdown();
        equal(1, LOOKUPS.size(), "one lookup for concurrent requests");
    }

    static JSONObject post(String id, String sub, Object sort) {
        JSONObject post = new JSONObject().put("id", id).put("name", "t3_" + id).put("title", "A post").put("subreddit", sub);
        if (sort != null) post.put("suggested_sort", sort);
        return post;
    }

    static void parser() {
        reset();
        equal(null, SuggestedCommentSort.postSort(post("p1", "AskNew", null), null), "unknown subreddit: nothing guessed");
        sent(url("AskNew", "warm"));
        equal(1, LOOKUPS.size(), "warmed by a comments request");
        equal("new", SuggestedCommentSort.postSort(post("p1", "AskNew", null), null), "known subreddit sort stored");
        equal("new", SuggestedCommentSort.postSort(post("p2", "asknew", null), null), "subreddit name in any case");
        equal("qa", SuggestedCommentSort.postSort(post("p3", "AskNew", "qa"), "qa"), "post's own sort kept");
        equal("blank", SuggestedCommentSort.postSort(post("p4", "AskNew", "blank"), "blank"), "post's own blank kept");
        equal(null, SuggestedCommentSort.postSort(new JSONObject().put("id", "c1").put("subreddit", "AskNew"), null),
                "not a post (no title): unchanged");
        equal(null, SuggestedCommentSort.postSort(null, null), "no JSON");
        equal(null, SuggestedCommentSort.postSort(post("p5", "Plain!", null), null), "invalid subreddit name");
        equal(1, LOOKUPS.size(), "the parser never looks anything up");

        // The parsed post's own sort is requested; a blank choice and its absence behave as Reddit's own.
        equal(url("AskNew", "p3") + "&sort=qa", sent(url("AskNew", "p3")), "own sort requested");
        equal(url("AskNew", "p4"), sent(url("AskNew", "p4")), "blank: no sort added");
        equal(url("AskNew", "p1") + "&sort=new", sent(url("AskNew", "p1")), "post without own sort: subreddit's");
        // A post whose own sort was parsed is requested with it even where the subreddit has none.
        SuggestedCommentSort.postSort(post("own1", "Plain", "old"), "old");
        equal(url(null, "own1") + "&sort=old", sent(url(null, "own1")), "own sort without a subreddit in the URL");
        equal(1, LOOKUPS.size(), "own sorts need no lookup");
    }

    static void loadMore() throws Exception {
        reset();
        equal("confidence", SuggestedCommentSort.requestSort("best", "t3_a"), "best is Reddit's confidence");
        equal("new", SuggestedCommentSort.requestSort("new", "t3_a"), "explicit sort kept");
        equal(null, SuggestedCommentSort.requestSort("suggested", "t3_a"), "nothing sent yet: left out");
        equal(null, SuggestedCommentSort.requestSort("", null), "no sort, no post");
        sent(url("AskNew", "lm1"));
        equal("new", SuggestedCommentSort.requestSort("suggested", "t3_lm1"), "suggested: the sort sent for the post");
        equal("new", SuggestedCommentSort.requestSort("SUGGESTED", "lm1"), "bare post ID");
        equal("new", SuggestedCommentSort.requestSort(null, "t3_lm1"), "no sort: the sort sent for the post");
        equal("top", SuggestedCommentSort.requestSort("top", "t3_lm1"), "an explicit choice wins");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("api_type", "json");
        params.put("link_id", "t3_lm1");
        params.put("children", "c1,c2");
        params.put("sort", "suggested");
        String legacy = RequestCompat.commentsUrl("http://127.0.0.1/api/morechildren?raw=1", params);
        check(legacy.contains("&sort=new") && !legacy.contains("suggested"), "legacy morechildren uses the post's sort: " + legacy);
        String canonical = RequestCompat.commentsUrl("https://oauth.reddit.com/api/morechildren?raw=1", params);
        check(canonical.contains("/comments/lm1.json?comment=c1") && canonical.contains("&sort=new"),
                "comment route uses the post's sort: " + canonical);
        params.put("link_id", "t3_other");
        String other = RequestCompat.commentsUrl("https://oauth.reddit.com/api/morechildren?raw=1", params);
        check(!other.contains("sort="), "another post: sort left out as before: " + other);

        Class<?> batch = Class.forName("app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch");
        Map<String, String> more = new HashMap<>();
        more.put("link_id", "t3_lm1");
        more.put("sort", "suggested");
        String endpoint = "https://oauth.reddit.com/api/morechildren";
        Method batchUrl = batch.getDeclaredMethod("getBatchUrl", String.class, Map.class, List.class);
        batchUrl.setAccessible(true);
        check(((String) batchUrl.invoke(null, endpoint, more, List.of("c1", "c2"))).contains("sort=new"), "batch request uses the post's sort");
        Method commentUrl = batch.getDeclaredMethod("getCommentUrl", String.class, Map.class, String.class);
        commentUrl.setAccessible(true);
        check(((String) commentUrl.invoke(null, endpoint, more, "c1")).contains("sort=new"), "single comment request uses the post's sort");
        Method fallbackUrl = batch.getDeclaredMethod("getFallbackUrl", String.class, Map.class, String.class);
        fallbackUrl.setAccessible(true);
        check(((String) fallbackUrl.invoke(null, endpoint, more, "c1")).contains("sort=new"), "fallback request uses the post's sort");
        more.put("sort", "best");
        check(((String) commentUrl.invoke(null, endpoint, more, "c1")).contains("sort=confidence"), "best still sent as confidence");
        more.put("sort", "suggested");
        more.put("link_id", "t3_unseen");
        check(!((String) commentUrl.invoke(null, endpoint, more, "c1")).contains("sort="), "unseen post: suggested left out as before");
    }
}
