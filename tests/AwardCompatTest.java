package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.VolleyError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Award metadata handling (issues #11 and #48). The listing loop, sheet parser and detail
 * sheet below are ports of Sync 23033's own code (c9.c.a, y8.c, t9.g$a/t9.i$a, s9.c), so the
 * tests show both the original failure and that the patched input no longer fails. The sheet
 * now reads the item's comment-page listing instead of /api/info; lookup() requests it from
 * a loopback server with a test-only token.
 */
public final class AwardCompatTest {
    private static int checks;
    private static final String POST_URL = "https://oauth.reddit.com/comments/abc123.json?limit=1&depth=1";
    private static final String COMMENT_URL = "https://oauth.reddit.com/comments/abc123/comment/def456.json?context=0&limit=1&depth=1";
    private static final String POST_LINK = "/r/pics/comments/abc123/a_title/";
    private static final String COMMENT_LINK = "/r/pics/comments/abc123/a_title/def456/";

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static NetworkResponse response(String body) {
        return new NetworkResponse(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The item's comment-page response: [post listing, comment listing]. A post is the post
     * listing's child; a comment (with context=0) is the comment listing's child under post
     * abc123. awardings is raw JSON or null to omit the field.
     */
    private static String info(String name, String permalink, String awardings) {
        JSONObject data = new JSONObject().put("name", name).put("id", name.substring(3));
        if (permalink != null) data.put("permalink", permalink);
        String item = data.toString();
        if (awardings != null) item = item.substring(0, item.length() - 1) + ",\"all_awardings\":" + awardings + "}";
        String thing = "{\"kind\":\"" + name.substring(0, 2).toLowerCase() + "\",\"data\":" + item + "}";
        boolean post = name.toLowerCase().startsWith("t3_");
        String parent = "{\"kind\":\"t3\",\"data\":{\"name\":\"t3_abc123\",\"id\":\"abc123\",\"permalink\":\"" + POST_LINK + "\"}}";
        return thread(post ? thing : parent, post ? "" : thing);
    }

    private static String thread(String posts, String comments) {
        return "[{\"kind\":\"Listing\",\"data\":{\"children\":[" + posts + "]}},"
                + "{\"kind\":\"Listing\",\"data\":{\"children\":[" + comments + "]}}]";
    }

    private static String award(String name, int count, String extra) {
        return "{\"name\":" + JSONObject.quote(name) + ",\"count\":" + count + extra + "}";
    }

    private static final String ICONS = ",\"description\":\"Thanks %{coin_symbol}100\""
            + ",\"icon_url\":\"https://i.redd.it/award_images/t5_22cerq/abc.png\""
            + ",\"resized_icons\":[{\"url\":\"https://i.redd.it/award_images/t5_22cerq/abc_16.png\",\"width\":16},"
            + "{\"url\":\"https://preview.redd.it/award_images/t5_22cerq/abc.png?width=128&amp;height=128&amp;auto=webp&amp;s=f00\",\"width\":128}]";

    private static AwardCompat.Result parse(String body, String url) {
        return AwardCompat.parseAwards(response(body), url);
    }

    private static void error(AwardCompat.Result result, String message, String what) {
        check(result != null && result.awards == null && result.error instanceof AwardCompat.AwardsError,
                what + ": expected an error result");
        check(message.equals(result.error.getMessage()), what + ": message " + result.error.getMessage());
        check(message.equals(AwardCompat.errorMessage(result.error)), what + ": toast text");
    }

    private static void handoff(AwardCompat.Result result, String label, String url, String what) {
        check(result != null && result.error == null && result.awards != null && result.awards.length == 1, what + ": one row");
        o8.a row = result.awards[0];
        check(label.equals(row.b) && row.a == 0 && "".equals(row.c) && row.r == null, what + ": row fields");
        check(url.equals(AwardCompat.handoffUrl(row)), what + ": opens " + AwardCompat.handoffUrl(row));
        check(label.equals(sheetLabel(row)), what + ": no count suffix on the row");
    }

    public static void main(String[] args) throws Exception {
        AwardCompat.rememberComment("def456", "abc123");
        sheetLookup();
        failures();
        fields();
        permalinks();
        listing();
        lookupUrls();
        lookup();
        System.out.println("AwardCompatTest: " + checks + " checks passed");
    }

    private static void sheetLookup() {
        // Current supported metadata: entries with name, count, description and icons.
        String body = info("t3_abc123", POST_LINK, "[" + award("Helpful", 3, ICONS) + "," + award("Wholesome", 1, ICONS) + "]");
        AwardCompat.Result result = parse(body, POST_URL);
        check(result.error == null && result.awards.length == 2, "two supported awards");
        o8.a first = result.awards[0];
        check(first.a == 3 && "Helpful".equals(first.b) && "Thanks %{coin_symbol}100".equals(first.c), "award text fields");
        check("https://i.redd.it/award_images/t5_22cerq/abc.png".equals(first.q), "detail icon is icon_url");
        check("https://preview.redd.it/award_images/t5_22cerq/abc.png?width=128&height=128&auto=webp&s=f00".equals(first.r),
                "row icon is the last resized icon, unescaped like Sync's WebsafeUtils");
        check("Helpful \u00d7 3".equals(sheetLabel(first)) && "Wholesome".equals(sheetLabel(result.awards[1])), "sheet labels");
        check("Thanks 100".equals(detailDescription(first)), "detail sheet text");
        check(AwardCompat.handoffUrl(first) == null, "real awards keep the detail sheet");
        // The original parser handles this response too.
        check(originalSheetParse(body).length == 2, "baseline parser accepts complete metadata");

        // Optional icon fields: missing icons, icon arrays and descriptions no longer fail.
        body = info("t3_abc123", POST_LINK, "[" + award("Plain", 1, "") + "]");
        result = parse(body, POST_URL);
        o8.a plain = result.awards[0];
        check(result.error == null && plain.q == null && plain.r == null && "".equals(plain.c), "award without icons or text");
        check("".equals(detailDescription(plain)), "detail sheet no longer dereferences a null description");
        check(throwsOriginal(body), "baseline parser rejected an award without icon fields");
        body = info("t3_abc123", POST_LINK, "[" + award("Static", 2,
                ",\"resized_static_icons\":[{\"url\":\"https://www.redditstatic.com/gold/awards/icon/a_16.png\"}]") + "]");
        o8.a stat = parse(body, POST_URL).awards[0];
        check("https://www.redditstatic.com/gold/awards/icon/a_16.png".equals(stat.r) && stat.r.equals(stat.q),
                "static icons are used when animated ones are absent");
        body = info("t3_abc123", POST_LINK, "[" + award("Icon only", 1, ",\"icon_url\":\"https://i.redd.it/x.png\"") + "]");
        o8.a iconOnly = parse(body, POST_URL).awards[0];
        check("https://i.redd.it/x.png".equals(iconOnly.q) && "https://i.redd.it/x.png".equals(iconOnly.r), "icon_url fills the row icon");

        // Entries Sync can't show are skipped, not fatal.
        body = info("t3_abc123", POST_LINK, "[1,null,\"x\",{\"count\":4},{\"name\":\"\"},{\"name\":5}," + award("Kept", 1, "") + "]");
        result = parse(body, POST_URL);
        check(result.error == null && result.awards.length == 1 && "Kept".equals(result.awards[0].b), "unusable entries skipped");

        // Empty list: distinct from unavailable data, and offers the item on reddit.com.
        body = info("t3_abc123", POST_LINK, "[]");
        handoff(parse(body, POST_URL), AwardCompat.OPEN_NONE, "https://www.reddit.com" + POST_LINK, "empty list");
        check(originalSheetParse(body).length == 0, "baseline returned an empty sheet");
        error(parse(info("t3_abc123", null, "[]"), POST_URL), AwardCompat.NONE, "empty list without a permalink");

        // Missing all_awardings: unavailable, not empty, never "Could not load gallery".
        body = info("t3_abc123", POST_LINK, null);
        handoff(parse(body, POST_URL), AwardCompat.OPEN_UNAVAILABLE, "https://www.reddit.com" + POST_LINK, "missing all_awardings");
        check(throwsOriginal(body), "baseline failed on missing all_awardings");
        for (String shape : new String[] {"null", "{}", "\"none\"", "7", "[1,2]", "[{\"count\":1}]"}) {
            handoff(parse(info("t3_abc123", POST_LINK, shape), POST_URL), AwardCompat.OPEN_UNAVAILABLE,
                    "https://www.reddit.com" + POST_LINK, "unsupported all_awardings " + shape);
        }
        error(parse(info("t3_abc123", null, null), POST_URL), AwardCompat.UNAVAILABLE, "unavailable without a permalink");

        // Comments resolve to their own comment page.
        handoff(parse(info("t1_def456", COMMENT_LINK, null), COMMENT_URL), AwardCompat.OPEN_UNAVAILABLE,
                "https://www.reddit.com" + COMMENT_LINK, "comment handoff");
        check(parse(info("t1_def456", COMMENT_LINK, "[" + award("Gold", 1, ICONS) + "]"), COMMENT_URL).awards[0].a == 1,
                "comment awards");

        // Bounds: at most MAX_AWARDS rows and clamped counts.
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 250; i++) many.append(i == 0 ? "" : ",").append(award("A" + i, i, ""));
        result = parse(info("t3_abc123", POST_LINK, many + "]"), POST_URL);
        check(result.awards.length == AwardCompat.MAX_AWARDS && result.awards[0].a == 1, "award rows bounded, counts at least 1");
        check(AwardCompat.count(Long.MAX_VALUE) == AwardCompat.MAX_COUNT && AwardCompat.count(-5) == 1
                && AwardCompat.count("12") == 12 && AwardCompat.count("1e9") == 1 && AwardCompat.count(2.5) == 1
                && AwardCompat.count(null) == 1, "count parsing");
        for (o8.a row : result.awards) check(AwardCompat.handoffUrl(row) == null, "real award never mistaken for a handoff row");
    }

    private static void failures() {
        // Lookup failures report accurate errors instead of the gallery message.
        error(AwardCompat.parseAwards(null, POST_URL), AwardCompat.LOAD_FAILED, "no response");
        error(AwardCompat.parseAwards(new NetworkResponse((byte[]) null), POST_URL), AwardCompat.LOAD_FAILED, "no body");
        for (String bad : new String[] {"", "<html>blocked</html>", "[]", "{", "[", "{\"data\":5}", "[{\"data\":5},{}]",
                "{\"error\":403,\"message\":\"Forbidden\"}", "{\"message\":\"Not Found\",\"error\":404}",
                info("t3_abc123", POST_LINK, "[]").replace("\"Listing\"", "\"Other\""),
                "[" + new JSONObject().put("kind", "Listing").put("data", new JSONObject().put("children", new JSONArray())) + "]",
                thread("", "") + " trailing", "{\"kind\":\"Listing\",\"data\":{\"children\":[]}}"}) {
            error(parse(bad, POST_URL), AwardCompat.UNREADABLE, "unreadable " + bad);
        }
        error(parse(thread("", ""), POST_URL), AwardCompat.NOT_FOUND, "deleted or missing post");
        error(parse(thread("5,{\"kind\":\"t3\",\"data\":7}", ""), POST_URL), AwardCompat.NOT_FOUND, "non-object children");
        String parentOnly = info("t3_abc123", POST_LINK, "[" + award("Post", 1, "") + "]");
        error(parse(parentOnly, COMMENT_URL), AwardCompat.NOT_FOUND, "missing comment: the post's awards are never shown");
        error(parse(info("t1_zzz999", "/r/pics/comments/abc123/a_title/zzz999/", "[" + award("Other", 1, "") + "]"), COMMENT_URL),
                AwardCompat.NOT_FOUND, "another comment");
        error(parse(info("t1_def456", COMMENT_LINK, "[" + award("Gold", 1, "") + "]").replace("t3_abc123", "t3_zzz999"), COMMENT_URL),
                AwardCompat.NOT_FOUND, "the comment under another post");
        error(parse(thread("", "{\"kind\":\"t1\",\"data\":{\"name\":\"t1_def456\"}}"), COMMENT_URL),
                AwardCompat.NOT_FOUND, "comment without its post listing");
        error(parse(info("t1_abc123", COMMENT_LINK, "[" + award("Kind", 1, "") + "]").replace("\"kind\":\"t1\"", "\"kind\":\"t3\""),
                "https://oauth.reddit.com/comments/abc123/comment/abc123.json?context=0&limit=1&depth=1"),
                AwardCompat.NOT_FOUND, "kind must match the requested item");
        // Another item's awards are never shown, even if Reddit returns one.
        error(parse(info("t3_zzz999", "/r/pics/comments/zzz999/x/", "[" + award("Other", 1, "") + "]"), POST_URL),
                AwardCompat.NOT_FOUND, "mismatched item");
        check(parse(info("T3_ABC123", POST_LINK, "[" + award("Case", 1, "") + "]"), POST_URL).awards.length == 1,
                "fullname match ignores case");
        // Without a verified lookup URL no item is chosen, so nothing unverified is shown or opened.
        for (String url : new String[] {null, "https://oauth.reddit.com/api/info/?id=t3_abc123", AwardCompat.UNIDENTIFIED_URL,
                "http://oauth.reddit.com/comments/abc123.json", "https://example.com/comments/abc123.json"})
            error(parse(info("t3_abc123", POST_LINK, "[" + award("First", 1, "") + "]"), url), AwardCompat.NOT_FOUND, "unverified URL " + url);
        // Deeply nested input fails safely.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20000; i++) deep.append('[');
        AwardCompat.Result nested = parse(thread("{\"kind\":\"t3\",\"data\":{\"name\":\"t3_abc123\",\"all_awardings\":" + deep + "}}", ""), POST_URL);
        check(nested.awards == null || nested.awards.length <= 1, "deeply nested input handled");

        // Network/HTTP failures from Volley.
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new NoConnectionError())), "no connection");
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(null)), "null error");
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new VolleyError("Could not load gallery"))),
                "foreign error messages are not shown");
        NetworkResponse forbidden = new NetworkResponse(403, new byte[0], false, 0, Collections.emptyList());
        check("Couldn't load awards from Reddit (HTTP 403).".equals(AwardCompat.errorMessage(new VolleyError(forbidden))), "HTTP status");
        NetworkResponse missing = new NetworkResponse(404, "{\"message\":\"Not Found\",\"error\":404}".getBytes(StandardCharsets.UTF_8),
                false, 0, Collections.emptyList());
        check(AwardCompat.NOT_FOUND.equals(AwardCompat.errorMessage(new VolleyError(missing))), "JSON 404 is a missing item");
        // UNIDENTIFIED_URL is refused by Sync's network stack before any connection: Volley over
        // OkHttp (Sync 23033) reports the scheme; stock Volley reports "Bad URL <url>".
        check(AwardCompat.UNIDENTIFIED.equals(AwardCompat.errorMessage(new VolleyError(new IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but was 'sync-compat-unidentified'")))), "OkHttp refusal named");
        check(AwardCompat.UNIDENTIFIED.equals(AwardCompat.errorMessage(new VolleyError(new RuntimeException(
                "Bad URL " + AwardCompat.UNIDENTIFIED_URL)))), "Volley refusal named");
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new VolleyError(new IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but was 'ftp'")))), "other refusals stay generic");
        NetworkResponse odd = new NetworkResponse(200, new byte[0], false, 0, Collections.emptyList());
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new VolleyError(odd))), "non-error status");

        // Request ID parsing, from lookup URLs only.
        check("t3_abc123".equals(AwardCompat.requestedFullname(POST_URL)) && "abc123".equals(AwardCompat.requestedPost(POST_URL)), "post ID");
        check("t1_def456".equals(AwardCompat.requestedFullname(COMMENT_URL)) && "abc123".equals(AwardCompat.requestedPost(COMMENT_URL)),
                "comment and post IDs");
        for (String url : new String[] {null, "https://oauth.reddit.com/api/info/?id=t3_abc123", "https://oauth.reddit.com/api/info/",
                "https://oauth.reddit.com/comments/abc123", "https://oauth.reddit.com/comments/ABC123.json",
                "https://oauth.reddit.com/comments/abc123/comment/.json", "https://oauth.reddit.com/comments/abc123/x/def456.json",
                "https://oauth.reddit.com/comments/" + "a".repeat(17) + ".json", "https://oauth.reddit.com/r/x/comments/abc123.json",
                "https://www.reddit.com/comments/abc123.json", "http://oauth.reddit.com/comments/abc123.json",
                "https://oauth.reddit.com:8443/comments/abc123.json", AwardCompat.UNIDENTIFIED_URL, "not a url"}) {
            check(AwardCompat.requestedFullname(url) == null && AwardCompat.requestedPost(url) == null, "rejected lookup URL " + url);
        }
    }

    /** Issue #48: y8.c.b's replacement builds comment-page URLs from validated IDs only. */
    private static void lookupUrls() {
        check(POST_URL.equals(AwardCompat.awardsUrl("t3_", "abc123")), "post lookup URL");
        check(POST_URL.equals(AwardCompat.awardsUrl("t3_", "ABC123")), "IDs are lowercased");
        check(COMMENT_URL.equals(AwardCompat.awardsUrl("t1_", "def456")), "comment lookup URL uses the recorded post");
        check(AwardCompat.UNIDENTIFIED_URL.equals(AwardCompat.awardsUrl("t1_", "unseen1")), "comment without a recorded post");
        for (String[] bad : new String[][] {{null, "abc123"}, {"t3_", null}, {"t5_", "abc123"}, {"t3_", ""}, {"t3_", "abc/123"},
                {"t3_", "abc123?x=1"}, {"t3_", "a".repeat(17)}, {"", "t3_abc123,t3_def456"}, {"t3_", "../api/info"}}) {
            check(AwardCompat.UNIDENTIFIED_URL.equals(AwardCompat.awardsUrl(bad[0], bad[1])), "unidentified " + Arrays.toString(bad));
        }
        // Sync records Post.U() and Post.j0(); prefixes, case and invalid values are handled.
        AwardCompat.rememberComment("T1_GHI789", "t3_JKL012");
        check("https://oauth.reddit.com/comments/jkl012/comment/ghi789.json?context=0&limit=1&depth=1"
                .equals(AwardCompat.awardsUrl("t1_", "ghi789")), "prefixed and upper-case IDs recorded");
        for (String[] bad : new String[][] {{"bad1", null}, {null, "abc123"}, {"bad2", "a/b"}, {"bad3", "t5_abc"}, {"bad4", ""}}) {
            AwardCompat.rememberComment(bad[0], bad[1]);
            check(bad[0] == null || AwardCompat.UNIDENTIFIED_URL.equals(AwardCompat.awardsUrl("t1_", bad[0])), "invalid post ignored for " + bad[0]);
        }
        AwardCompat.rememberComment("def456", "abc123");
        // Bounded: the least recently used comments are forgotten first.
        for (int i = 0; i < AwardCompat.MAX_PARENTS + 5; i++) AwardCompat.rememberComment("c" + i, "p" + i);
        check(AwardCompat.UNIDENTIFIED_URL.equals(AwardCompat.awardsUrl("t1_", "c0")), "oldest comment forgotten");
        check(AwardCompat.awardsUrl("t1_", "c" + (AwardCompat.MAX_PARENTS + 4)).contains("/comments/p" + (AwardCompat.MAX_PARENTS + 4) + "/comment/"),
                "newest comment kept");
        AwardCompat.rememberComment("def456", "abc123");
        check(COMMENT_URL.equals(AwardCompat.awardsUrl("t1_", "def456")), "re-recorded comment");
        // The sentinel is not an HTTP(S) URL, so no network stack can send it anywhere.
        check(!AwardCompat.UNIDENTIFIED_URL.startsWith("http") && AwardCompat.UNIDENTIFIED_URL.startsWith(AwardCompat.UNIDENTIFIED_SCHEME + ":"),
                "unidentified lookup is not an HTTP URL");
        for (String url : new String[] {POST_URL, COMMENT_URL, AwardCompat.UNIDENTIFIED_URL})
            check(!url.contains("/api/info") && !url.contains("raw_json"), "no /api/info or raw_json in " + url);
    }

    /** Volley-style result of one lookup: the parsed sheet, or the sheet's error text. */
    private static final class Lookup {
        AwardCompat.Result result;
        String error;
    }

    /**
     * Requests a lookup URL like Sync 23033's network stack: HTTPS lookups go to the loopback
     * server with a test-only token, non-2xx statuses become VolleyErrors with the response, and
     * a non-HTTP(S) URL is refused before connecting, as OkHttp's Request.Builder.url does.
     */
    private static Lookup fetch(String url, int port) throws Exception {
        Lookup out = new Lookup();
        java.net.URI uri = new java.net.URI(url);
        VolleyError error = null;
        if (!"https".equals(uri.getScheme())) {
            error = new VolleyError(new IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '" + uri.getScheme() + "'"));
        } else {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + port + uri.getRawPath() + "?" + uri.getRawQuery()).openConnection();
            connection.setRequestProperty("Authorization", "Bearer TEST_ONLY");
            int status = connection.getResponseCode();
            java.io.InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
            connection.disconnect();
            NetworkResponse response = new NetworkResponse(status, body, false, 0, Collections.emptyList());
            if (status >= 200 && status < 300) out.result = AwardCompat.parseAwards(response, url);
            else error = new VolleyError(response);
        }
        if (out.result != null && out.result.error != null) error = out.result.error;
        out.error = error == null ? null : AwardCompat.errorMessage(error);
        return out;
    }

    /** Issue #48: post and comment awards in listing form over loopback HTTP; no /api/info. */
    private static void lookup() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.Map<String, String> bodies = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, Integer> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger info = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                requests.add(path + "?" + exchange.getRequestURI().getRawQuery());
                if (path.startsWith("/api/info")) info.incrementAndGet();
                check("Bearer TEST_ONLY".equals(exchange.getRequestHeaders().getFirst("Authorization")), "test-only token sent");
                byte[] body = bodies.getOrDefault(path, "{\"message\":\"Not Found\",\"error\":404}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(statuses.getOrDefault(path, bodies.containsKey(path) ? 200 : 404), body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            // Reddit's default (no raw_json) encoding escapes & in the icon URL; Sync unescapes it.
            String icon = ",\"icon_url\":\"https://i.redd.it/award_images/t5_22cerq/abc.png?width=64&amp;s=f00\"";
            bodies.put("/comments/abc123.json", info("t3_abc123", POST_LINK, "[" + award("Helpful", 2, icon) + "]"));
            Lookup post = fetch(AwardCompat.awardsUrl("t3_", "abc123"), port);
            check(post.error == null && post.result.awards.length == 1 && post.result.awards[0].a == 2
                    && "Helpful".equals(post.result.awards[0].b), "post awards in listing form");
            check("https://i.redd.it/award_images/t5_22cerq/abc.png?width=64&s=f00".equals(post.result.awards[0].q), "escaped icon URL decoded");
            check(requests.get(requests.size() - 1).equals("/comments/abc123.json?limit=1&depth=1"), "post lookup path and query");

            bodies.put("/comments/abc123/comment/def456.json", info("t1_def456", COMMENT_LINK, "[" + award("Gold", 1, "") + "]"));
            AwardCompat.rememberComment("def456", "abc123");
            Lookup comment = fetch(AwardCompat.awardsUrl("t1_", "def456"), port);
            check(comment.error == null && "Gold".equals(comment.result.awards[0].b), "comment awards in listing form");
            check(requests.get(requests.size() - 1).equals("/comments/abc123/comment/def456.json?context=0&limit=1&depth=1"),
                    "comment lookup path and query");

            // No awards listed or none provided: the Open on Reddit row, checked against the lookup.
            bodies.put("/comments/abc123/comment/def456.json", info("t1_def456", COMMENT_LINK, null));
            comment = fetch(COMMENT_URL, port);
            handoff(comment.result, AwardCompat.OPEN_UNAVAILABLE, "https://www.reddit.com" + COMMENT_LINK, "comment without award data");
            bodies.put("/comments/abc123.json", info("t3_abc123", POST_LINK, "[]"));
            handoff(fetch(POST_URL, port).result, AwardCompat.OPEN_NONE, "https://www.reddit.com" + POST_LINK, "post with no awards");
            bodies.put("/comments/abc123/comment/def456.json", info("t1_def456", "/r/pics/comments/zzz999/a_title/def456/", null));
            check(AwardCompat.UNAVAILABLE.equals(fetch(COMMENT_URL, port).error), "comment page under another post is never opened");

            // A missing item: Reddit's JSON 404, or a post page without the comment.
            AwardCompat.rememberComment("gone11", "gone22");
            check(AwardCompat.NOT_FOUND.equals(fetch(AwardCompat.awardsUrl("t3_", "gone22"), port).error), "missing post (JSON 404)");
            check(AwardCompat.NOT_FOUND.equals(fetch(AwardCompat.awardsUrl("t1_", "gone11"), port).error), "missing comment (JSON 404)");
            bodies.put("/comments/abc123/comment/def456.json", thread(
                    "{\"kind\":\"t3\",\"data\":{\"name\":\"t3_abc123\",\"all_awardings\":[" + award("Post", 1, "") + "]}}", ""));
            check(AwardCompat.NOT_FOUND.equals(fetch(COMMENT_URL, port).error), "removed comment: the post's awards are not shown");
            // An unrelated item never shows its awards.
            bodies.put("/comments/abc123.json", info("t3_zzz999", "/r/pics/comments/zzz999/x/", "[" + award("Other", 1, "") + "]"));
            check(AwardCompat.NOT_FOUND.equals(fetch(POST_URL, port).error), "unrelated post");
            bodies.put("/comments/abc123/comment/def456.json", info("t1_zzz999", "/r/pics/comments/abc123/a_title/zzz999/",
                    "[" + award("Other", 1, "") + "]"));
            check(AwardCompat.NOT_FOUND.equals(fetch(COMMENT_URL, port).error), "unrelated comment");
            // Malformed JSON and HTTP failures.
            for (String bad : new String[] {"{not json", "<html>blocked</html>", "", "{\"kind\":\"Listing\"}", "[{},{}]"}) {
                bodies.put("/comments/abc123.json", bad);
                check(AwardCompat.UNREADABLE.equals(fetch(POST_URL, port).error), "malformed response " + bad);
            }
            for (int status : new int[] {401, 403, 429, 500, 503}) {
                bodies.put("/comments/abc123.json", "<html>error</html>");
                statuses.put("/comments/abc123.json", status);
                check(("Couldn't load awards from Reddit (HTTP " + status + ").").equals(fetch(POST_URL, port).error), "HTTP " + status);
            }
            statuses.clear();
            // An unidentified item or comment post fails with no request at all.
            int before = requests.size();
            check(AwardCompat.UNIDENTIFIED.equals(fetch(AwardCompat.awardsUrl("t1_", "unseen2"), port).error), "unknown comment post");
            check(AwardCompat.UNIDENTIFIED.equals(fetch(AwardCompat.awardsUrl("t3_", "not/valid"), port).error), "invalid post ID");
            check(requests.size() == before, "unidentified lookups send no request");
            check(info.get() == 0 && requests.stream().noneMatch(r -> r.contains("/api/info") || r.contains("raw_json")),
                    "no request to /api/info and no raw_json across " + requests.size() + " lookups");
        } finally {
            server.stop(0);
        }
    }

    private static void fields() {
        check(AwardCompat.iconUrl("https://i.redd.it/a.png") != null, "i.redd.it icon");
        check(AwardCompat.iconUrl("https://www.redditstatic.com/a.png") != null, "redditstatic icon");
        check("https://preview.redd.it/a.png?width=1&s=2".equals(AwardCompat.iconUrl(" https://preview.redd.it/a.png?width=1&amp;s=2 ")), "unescaped");
        for (String bad : new String[] {null, "", "http://i.redd.it/a.png", "javascript:alert(1)", "https://example.com/a.png",
                "https://i.redd.it.example.com/a.png", "https://evilredd.it/a.png", "https://user@i.redd.it/a.png",
                "https://i.redd.it:8443/a.png", "https://i.redd.it/a b.png", "//i.redd.it/a.png", "https:///a.png"}) {
            check(AwardCompat.iconUrl(bad) == null, "rejected icon " + bad);
        }
        check(AwardCompat.iconUrl(7) == null && AwardCompat.iconUrl("https://i.redd.it/" + "a".repeat(2100)) == null, "non-string/oversized icon");
        JSONObject text = new JSONObject().put("a", "  x  ").put("b", "").put("c", 5).put("d", JSONObject.NULL).put("e", "y".repeat(501));
        check("x".equals(AwardCompat.text(text, "a")), "trimmed text");
        for (String key : new String[] {"b", "c", "d", "e", "missing"}) check(AwardCompat.text(text, key) == null, "rejected text " + key);
        check(AwardCompat.text(null, "a") == null, "null object");
    }

    private static void permalinks() {
        JSONObject item = new JSONObject();
        String[][] good = {
                {"t3_abc123", "/r/pics/comments/abc123/a_title/"},
                {"t3_abc123", "/r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/abc123"},
                {"t3_abc123", "/user/someone/comments/abc123/my_post/"},
                {"t3_abc123", "/r/u_someone/comments/abc123/x/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/def456/"},
                {"t1_def456", "/r/pics/comments/abc123/_/def456"},
        };
        for (String[] pair : good) {
            item.put("permalink", pair[1]);
            check(("https://www.reddit.com" + pair[1]).equals(AwardCompat.openUrl(item, pair[0])), "permalink " + pair[1]);
        }
        String[][] bad = {
                {"t3_abc123", "/r/pics/comments/zzz999/a_title/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/zzz999/"},
                {"t3_abc123", "https://evil.example/r/pics/comments/abc123/"},
                {"t3_abc123", "//evil.example/r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics//comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/abc123/../../../../logout"},
                {"t3_abc123", "/r/pics/comments/abc123/../x"},
                {"t1_def456", "/r/pics/comments/abc123/./def456/"},
                {"t3_abc123", "/r/pics/comments/abc123/a?b=1"},
                {"t3_abc123", "/r/pics/comments/abc123/a#b"},
                {"t3_abc123", "/r/pics/comments/abc123/a b/"},
                {"t3_abc123", "/r/pics/comments/abc123/a/b/c/d/"},
                {"t3_abc123", "/s/pics/comments/abc123/"},
                {"t3_abc123", "/r/pi.cs/comments/abc123/"},
                {"t3_abc123", "/r/pics/comment/abc123/"},
                {"t3_abc123", "r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/ABC123/"},
                {"t3_abc123", "/r/pics/comments/abc123/\u00e9/"},
        };
        for (String[] pair : bad) {
            item.put("permalink", pair[1]);
            check(AwardCompat.openUrl(item, pair[0]) == null, "rejected permalink " + pair[1]);
        }
        item.put("permalink", POST_LINK);
        check(AwardCompat.openUrl(item, null) == null && AwardCompat.openUrl(item, "t5_abc") == null, "requires a verified ID");
        check(AwardCompat.openUrl(new JSONObject(), "t3_abc123") == null, "requires a permalink");

        // Handoff detection uses only rows this patch built.
        o8.a forged = new o8.a();
        forged.q = "https://www.reddit.com/r/pics/";
        check("https://www.reddit.com/r/pics/".equals(AwardCompat.handoffUrl(forged)), "count 0 + reddit URL is a handoff row");
        forged.q = "https://evil.example/";
        check(AwardCompat.handoffUrl(forged) == null, "other hosts never open");
        forged.q = null;
        check(AwardCompat.handoffUrl(forged) == null && AwardCompat.handoffUrl("x") == null && AwardCompat.handoffUrl(null) == null,
                "non-rows never open");
    }

    private static void listing() {
        String full = "[{\"resized_static_icons\":[{\"url\":\"https://i.redd.it/s1.png\"},{\"url\":\"https://i.redd.it/s2.png\"},"
                + "{\"url\":\"https://i.redd.it/s3.png\"},{\"url\":\"https://i.redd.it/s4.png?a=1&amp;b=2\"}],"
                + "\"resized_icons\":[{\"url\":\"https://i.redd.it/r1.png\"}]}]";
        // Current complete metadata: identical icon choice to Sync's own loop.
        check(originalListing(new JSONArray(full)).equals(originalListing(AwardCompat.listingAwardings(new JSONArray(full)))),
                "complete metadata gives the same icons");
        check(Collections.singletonList("https://i.redd.it/s4.png?a=1&b=2").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(full)))),
                "fourth static icon chosen and unescaped by Sync");
        String animated = "[{\"resized_static_icons\":[{\"url\":\"https://external-preview.redd.it/a.png\"},{\"url\":\"https://i.redd.it/2\"},"
                + "{\"url\":\"https://i.redd.it/3\"},{\"url\":\"https://i.redd.it/4\"}],\"resized_icons\":[{\"url\":\"https://i.redd.it/a1\"},"
                + "{\"url\":\"https://i.redd.it/a2\"},{\"url\":\"https://i.redd.it/a3\"},{\"url\":\"https://i.redd.it/a4\"}]}]";
        check(Collections.singletonList("https://i.redd.it/a4").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(animated)))),
                "external-preview static icons still fall back to resized_icons");

        // Missing or changed fields: the original throws and fails the whole post/comment parse.
        String[] broken = {
                "[{\"name\":\"New award\"}]",
                "[{\"resized_static_icons\":null}]",
                "[{\"resized_static_icons\":[]}]",
                "[{\"resized_static_icons\":\"x\",\"resized_icons\":{}}]",
                "[5,\"x\",null]",
                "[{\"resized_static_icons\":[1,2,3,4,5]}]",
                "[{\"resized_static_icons\":[{},{},{},{\"width\":5}]}]",
        };
        for (String awardings : broken) {
            JSONArray original = new JSONArray(awardings);
            check(throwsListing(original), "baseline listing parse failed on " + awardings);
            List<String> icons = originalListing(AwardCompat.listingAwardings(new JSONArray(awardings)));
            check(icons.isEmpty(), "patched listing parse succeeds without icons for " + awardings);
        }
        // Untrusted icon URLs are dropped; valid ones keep Sync's index-3 choice among the rest.
        String mixed = "[{\"resized_static_icons\":[{\"url\":\"http://i.redd.it/1\"},{\"url\":\"https://i.redd.it/2\"},{\"url\":\"https://example.com/3\"},"
                + "{\"url\":\"https://i.redd.it/4\"},{\"url\":\"https://i.redd.it/5\"},{\"url\":\"https://i.redd.it/6\"}]}]";
        check(Collections.singletonList("https://i.redd.it/6").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(mixed)))),
                "non-HTTPS and third-party icons dropped");
        check(AwardCompat.listingAwardings(null) == null, "absent all_awardings stays absent");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 500; i++) many.append(i == 0 ? "" : ",").append("{}");
        check(AwardCompat.listingAwardings(new JSONArray(many + "]")).length() == AwardCompat.MAX_AWARDS, "listing entries bounded");

        // gildings: Sync calls getJSONObject whenever has() is true.
        JSONObject post = new JSONObject("{\"a\":{\"gid_1\":1},\"b\":null,\"c\":\"x\",\"d\":[]}");
        check(AwardCompat.hasObject(post, "a"), "gildings object read");
        for (String key : new String[] {"b", "c", "d", "missing"}) {
            check(!AwardCompat.hasObject(post, key), "gildings " + key + " skipped");
            check(!post.has(key) || throwsGetObject(post, key), "baseline gildings " + key + " would throw");
        }
        check(!AwardCompat.hasObject(null, "a"), "null post");
    }

    // ---- Ports of Sync 23033 code (behavior only) ----

    /** c9.c.a: the all_awardings loop, run when total_awards_received > 0. */
    private static List<String> originalListing(JSONArray awardings) {
        List<String> out = new ArrayList<>();
        if (awardings == null) return out;
        for (int i = 0; i < awardings.length(); i++) {
            JSONObject entry = awardings.getJSONObject(i);
            if (entry == null) continue;
            JSONArray icons = entry.getJSONArray("resized_static_icons");
            String url = null;
            if (icons != null && !icons.toString().contains("external-preview") && icons.length() > 3) {
                url = icons.getJSONObject(3).getString("url");
            } else {
                icons = entry.getJSONArray("resized_icons");
                if (icons != null && icons.length() > 3) url = icons.getJSONObject(3).getString("url");
            }
            if (url != null && !url.isEmpty()) out.add(websafe(url));
        }
        return out;
    }

    private static boolean throwsListing(JSONArray awardings) {
        try {
            originalListing(awardings);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static boolean throwsGetObject(JSONObject object, String key) {
        try {
            object.getJSONObject(key);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    /** y8.c.parseNetworkResponse before the patch, given the same item as /api/info returned it. */
    private static o8.a[] originalSheetParse(String body) {
        JSONArray thread = new JSONArray(body);
        JSONArray posts = thread.getJSONObject(0).getJSONObject("data").getJSONArray("children");
        JSONArray comments = thread.getJSONObject(1).getJSONObject("data").getJSONArray("children");
        JSONObject item = (comments.length() > 0 ? comments : posts).getJSONObject(0).getJSONObject("data");
        String info = new JSONObject().put("data", new JSONObject().put("children", new JSONArray()
                .put(new JSONObject().put("data", item)))).toString();
        JSONArray list = new JSONObject(info).getJSONObject("data").getJSONArray("children").getJSONObject(0)
                .getJSONObject("data").getJSONArray("all_awardings");
        o8.a[] out = new o8.a[list.length()];
        for (int i = 0; i < out.length; i++) {
            JSONObject entry = list.getJSONObject(i);
            out[i] = new o8.a();
            out[i].a = entry.getInt("count");
            out[i].b = entry.getString("name");
            out[i].c = entry.getString("description");
            out[i].q = entry.getString("icon_url");
            JSONArray resized = entry.getJSONArray("resized_icons");
            out[i].r = websafe(resized.getJSONObject(resized.length() - 1).getString("url"));
        }
        return out;
    }

    private static boolean throwsOriginal(String body) {
        try {
            originalSheetParse(body);
            return false;
        } catch (RuntimeException expected) {
            // Sync then reported "Could not load gallery".
            return true;
        }
    }

    /** t9.g$a / t9.i$a row label. */
    private static String sheetLabel(o8.a award) {
        return award.b + (award.a > 1 ? " \u00d7 " + award.a : "");
    }

    /** s9.c.o2: the detail sheet's description line. */
    private static String detailDescription(o8.a award) {
        return award.c.replaceAll("\\%\\{coin_symbol\\}", "");
    }

    /** wc.p.a (WebsafeUtils). */
    private static String websafe(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("\u202e", "").replaceAll("\u200f", "");
    }
}
