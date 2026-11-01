package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Production gallery parsing and bounded metadata refresh, using real Volley and loopback HTTP. */
public final class GalleryCompatTest {
    static int checks;
    static final String URL = RequestCompat.galleryUrl("abc123");
    static final String SIGNED = "https://preview.redd.it/still.png?width=800&format=pjpg&auto=webp&s=a%2Fb%2Bcd";
    static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": " + actual);
    }
    static final class GalleryRequest extends Request<o8.b> {
        GalleryRequest() { this(URL); }
        GalleryRequest(String url) { super(Method.GET, url, null); }
        @Override public Map<String,String> getHeaders() {
            Map<String,String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer TEST_ONLY");
            headers.put("User-Agent", "gallery-test");
            headers.put("Cookie", "PRIVATE_FIXTURE");
            headers.put("X-Private", "PRIVATE_FIXTURE");
            return headers;
        }
        @Override protected Response<o8.b> parseNetworkResponse(NetworkResponse r) { return null; }
        @Override protected void deliverResponse(o8.b gallery) { }
    }
    static JSONObject listing(JSONArray children) {
        return new JSONObject().put("kind", "Listing").put("data", new JSONObject().put("children", children));
    }
    static JSONObject post(String id) {
        return new JSONObject().put("id", id).put("name", "t3_" + id);
    }
    static JSONObject item(String id, String caption, String link) {
        return new JSONObject().put("media_id", id).put("caption", caption).put("outbound_url", link);
    }
    static JSONObject image(String field, String url) {
        return new JSONObject().put("status", "valid").put("e", "Image")
                .put("s", new JSONObject().put(field, url));
    }
    static JSONObject gallery() {
        return post("abc123").put("gallery_data", new JSONObject().put("items", new JSONArray()
                .put(item("first", "First café $12 \\caption", "https://example.invalid/first"))
                .put(item("missing", "Missing caption", "https://example.invalid/middle"))
                .put(item("last", "Last", "https://example.invalid/last"))))
                .put("media_metadata", new JSONObject().put("first", image("u", SIGNED.replace("&", "&amp;")))
                        .put("last", image("gif", "https://i.redd.it/last.gif")));
    }
    static JSONObject envelope(JSONObject post) {
        return listing(new JSONArray().put(new JSONObject().put("kind", "t3").put("data", post)));
    }
    static JSONArray thread(JSONObject post) {
        return new JSONArray().put(envelope(post)).put(listing(new JSONArray()));
    }
    static NetworkResponse response(Object root) {
        return new NetworkResponse(200, root.toString().getBytes(StandardCharsets.UTF_8),
                Collections.singletonMap("ETag", "test"), true, 42);
    }
    static GalleryCompat.Result parse(Object root) {
        return GalleryCompat.parseGallery(response(root), new GalleryRequest());
    }
    static o8.b success(GalleryCompat.Result result) {
        check(result.error == null && result.gallery != null, "gallery parsed: " + (result.error == null ? "" : result.error.getMessage()));
        return result.gallery;
    }
    static void failure(Object root, String expected) {
        GalleryCompat.Result result = parse(root);
        check(result.gallery == null && result.response == null && result.error != null, "invalid response rejected");
        equal(expected, result.error.getMessage(), "bounded non-sensitive failure");
    }

    public static void main(String[] args) throws Exception {
        GalleryCompat.Connections original = GalleryCompat.connections;
        GalleryCompat.connections = url -> { throw new AssertionError("Unexpected refresh: " + url); };
        try {
            direct();
            malformedItems();
            crossposts();
            invalidResponses();
            urls();
            encoding();
            fallback();
            refresh();
        } finally { GalleryCompat.connections = original; }
        System.out.println("GalleryCompatTest: " + checks + " checks passed");
    }

    static void direct() throws Exception {
        JSONObject listing = envelope(gallery()).put("position", 2);
        NetworkResponse input = response(listing);
        byte[] before = input.data.clone();
        GalleryCompat.Result result = GalleryCompat.parseGallery(input, new GalleryRequest());
        o8.b value = success(result);
        equal(3, value.b.length, "missing entry retains its slot");
        equal(SIGNED, value.b[0], "signed query kept, HTML ampersands decoded once");
        equal("", value.b[1], "unavailable item uses Sync's existing empty-source state");
        equal("https://i.redd.it/last.gif", value.b[2], "GIF-only source");
        equal(value.b[0], value.c[0], "absent previews use source");
        equal("First café $12 \\caption\n\nhttps://example.invalid/first", value.d[0], "caption and outbound alignment");
        equal("Media unavailable\n\nMissing caption\n\nhttps://example.invalid/middle", value.d[1], "unavailable slot retains its own caption and link");
        equal("Last\n\nhttps://example.invalid/last", value.d[2], "last caption not shifted");
        equal(2, value.a, "saved index after missing entry retained");
        check(Arrays.equals(before, input.data), "original network bytes untouched");
        check(result.response.headers == input.headers && result.response.networkTimeMs == 42
                && result.response.notModified && result.response.statusCode == 200, "network metadata preserved");
        JSONObject cached = new JSONObject(new String(result.response.data, StandardCharsets.UTF_8));
        equal("Listing", cached.getString("kind"), "cache uses object listing for activity's saved-position writer");
        cached.put("position", 1);
        equal(1, success(parse(cached)).a, "cache reopen on unavailable slot");
        cached.put("position", 999);
        equal(2, success(parse(cached)).a, "out-of-range index clamped");
        cached.put("position", -5);
        equal(0, success(parse(cached)).a, "negative index clamped");
        equal(3, success(parse(thread(gallery()))).b.length, "thread response supported");
        NetworkResponse notModified = new NetworkResponse(304, response(thread(gallery())).data, input.headers, true, 12);
        equal(3, success(GalleryCompat.parseGallery(notModified, new GalleryRequest())).b.length, "Volley 304 with cached bytes supported");
    }

    static void malformedItems() {
        for (Object bad : new Object[] {JSONObject.NULL, "not an object", 123,
                new JSONObject().put("status", "failed").put("s", new JSONObject().put("u", SIGNED)),
                new JSONObject().put("status", "processing").put("s", new JSONObject().put("u", SIGNED)),
                image("u", SIGNED).put("e", "RedditVideo"), image("u", "https://evil.invalid/file.jpg"),
                image("u", SIGNED).put("status", 5), image("u", SIGNED).put("e", JSONObject.NULL),
                image("u", SIGNED).put("status", ""), image("u", SIGNED).put("e", ""),
                new JSONObject().put("s", "bad"), new JSONObject().put("s", new JSONObject())}) {
            JSONObject post = gallery();
            post.getJSONObject("media_metadata").put("first", bad);
            o8.b value = success(parse(thread(post)));
            equal("", value.b[0], "malformed media is unavailable");
            equal("https://i.redd.it/last.gif", value.b[2], "bad item cannot hide usable neighbor");
            equal(3, value.d.length, "parallel arrays retain length");
        }
        for (Object bad : new Object[] {JSONObject.NULL, "bad", 4, new JSONObject(),
                new JSONObject().put("media_id", 5)}) {
            JSONObject post = gallery();
            post.getJSONObject("gallery_data").getJSONArray("items").put(1, bad);
            o8.b value = success(parse(thread(post)));
            equal("", value.b[1], "malformed item retains slot");
            equal("https://i.redd.it/last.gif", value.b[2], "malformed item leaves later position intact");
        }
        for (String field : new String[] {"u", "gif", "mp4"}) {
            JSONObject post = gallery();
            post.getJSONObject("media_metadata").put("first", image(field, "https://preview.redd.it/file." + field)
                    .put("p", new JSONArray().put(3).put(new JSONObject().put("u", "bad"))
                            .put(new JSONObject().put("u", SIGNED))));
            o8.b value = success(parse(thread(post)));
            equal("https://preview.redd.it/file." + field, value.b[0], field + " accepted without requiring u");
            equal(SIGNED, value.c[0], "first valid preview used");
        }
        JSONObject post = gallery();
        post.getJSONObject("media_metadata").put("first", image("u", SIGNED).put("e", "AnimatedImage")
                .put("s", new JSONObject().put("mp4", "https://preview.redd.it/a.mp4")
                        .put("gif", "https://preview.redd.it/a.gif").put("u", SIGNED)));
        equal("https://preview.redd.it/a.mp4", success(parse(thread(post))).b[0], "animated media prefers mp4");
        post.remove("media_metadata");
        o8.b unavailable = success(parse(thread(post)));
        check(Arrays.stream(unavailable.b).allMatch(String::isEmpty), "all missing metadata yields navigable unavailable slots");
    }

    static void crossposts() {
        JSONObject parent = gallery().put("id", "parent1").put("name", "t3_parent1");
        JSONObject crosspost = post("abc123").put("crosspost_parent", "t3_parent1")
                .put("crosspost_parent_list", new JSONArray().put(gallery()).put(parent));
        GalleryCompat.Result result = parse(thread(crosspost));
        equal(SIGNED, success(result).b[0], "declared parent selected instead of first unrelated parent");
        equal(SIGNED, success(GalleryCompat.parseGallery(result.response, new GalleryRequest())).b[0], "crosspost cache can reopen");
        JSONObject own = gallery().put("crosspost_parent", "t3_parent1")
                .put("crosspost_parent_list", new JSONArray().put(parent));
        own.getJSONObject("media_metadata").remove("first");
        equal(SIGNED, success(parse(thread(own))).b[0], "matching parent's metadata fills absent own entry");
        own.getJSONObject("media_metadata").put("first", image("u", "https://i.redd.it/own.jpg"));
        equal("https://i.redd.it/own.jpg", success(parse(thread(own))).b[0], "own usable media takes priority");
        crosspost.put("crosspost_parent", "t3_wrong");
        failure(thread(crosspost), GalleryCompat.UNAVAILABLE);
        crosspost.remove("crosspost_parent");
        failure(thread(crosspost), GalleryCompat.UNAVAILABLE);
        crosspost.put("crosspost_parent", "t3_parent1");
        parent.put("id", "mismatch");
        failure(thread(crosspost), GalleryCompat.UNAVAILABLE);
        parent.put("id", "parent1");
        parent.getJSONObject("gallery_data").put("items", "bad");
        failure(thread(crosspost), GalleryCompat.UNAVAILABLE);
    }

    static void invalidResponses() {
        failure(thread(gallery().put("id", "wrong")), GalleryCompat.WRONG_POST);
        failure(thread(gallery().put("name", "t3_wrong")), GalleryCompat.WRONG_POST);
        failure(envelope(gallery()).put("kind", "t1"), GalleryCompat.UNREADABLE);
        failure(new JSONArray().put(envelope(gallery())).put(new JSONObject()), GalleryCompat.UNREADABLE);
        failure(new JSONArray().put(envelope(gallery())), GalleryCompat.UNREADABLE);
        failure(envelope(gallery()).put("data", new JSONObject()), GalleryCompat.UNREADABLE);
        failure(envelope(gallery()).put("data", new JSONObject().put("children", new JSONArray())), GalleryCompat.UNREADABLE);
        failure(listing(new JSONArray().put(new JSONObject().put("kind", "t1").put("data", gallery()))), GalleryCompat.UNREADABLE);
        failure(listing(new JSONArray().put(new JSONObject().put("kind", "t3").put("data", gallery()))
                .put(new JSONObject())), GalleryCompat.UNREADABLE);
        failure(thread(post("abc123")), GalleryCompat.UNAVAILABLE);
        JSONObject empty = gallery();
        empty.getJSONObject("gallery_data").put("items", new JSONArray());
        failure(thread(empty), GalleryCompat.UNAVAILABLE);
        JSONArray oversized = new JSONArray();
        for (int i = 0; i <= GalleryCompat.MAX_ITEMS; i++) oversized.put(item("first", "", ""));
        empty.getJSONObject("gallery_data").put("items", oversized);
        failure(thread(empty), GalleryCompat.UNAVAILABLE);
        failure(envelope(gallery()) + "PRIVATE trailing", GalleryCompat.UNREADABLE);
        failure("PRIVATE not JSON", GalleryCompat.UNREADABLE);
        NetworkResponse huge = new NetworkResponse(new byte[GalleryCompat.MAX_BYTES + 1]);
        equal(GalleryCompat.UNREADABLE, GalleryCompat.parseGallery(huge, new GalleryRequest()).error.getMessage(), "size bound");
        GalleryRequest canceled = new GalleryRequest(); canceled.cancel();
        equal("Gallery loading canceled.", GalleryCompat.parseGallery(response(thread(gallery())), canceled).error.getMessage(), "canceled request rejected");
        for (String url : new String[] {"https://evil.invalid/comments/abc123.json", "https://oauth.reddit.com.evil.invalid/comments/abc123.json",
                "http://oauth.reddit.com/comments/abc123.json", "https://user@oauth.reddit.com/comments/abc123.json",
                "https://oauth.reddit.com:8443/comments/abc123.json", "https://oauth.reddit.com/comments/abc123.json#fragment"})
            equal(GalleryCompat.UNREADABLE, GalleryCompat.parseGallery(response(thread(gallery())), new GalleryRequest(url)).error.getMessage(), "untrusted request rejected");
    }

    static void urls() {
        for (String url : new String[] {"javascript:alert(1)", "http://i.redd.it/x.jpg", "https://user@i.redd.it/x.jpg",
                "https://i.redd.it:123/x.jpg", "https://i.redd.it.evil.invalid/x.jpg", "https://i.redd.it/", "not a URL"})
            check(GalleryCompat.mediaUrl(url) == null, "unsafe media URL rejected");
        equal(SIGNED, GalleryCompat.mediaUrl(SIGNED), "signature bytes retained");
        equal("https://I.REDD.IT:443/file.jpg?x=a%2Bb", GalleryCompat.mediaUrl("https://I.REDD.IT:443/file.jpg?x=a%2Bb"), "default HTTPS port and host case accepted without rewriting");
        check(GalleryCompat.expired(SIGNED + "&expires=1", System.currentTimeMillis()), "expired seconds");
        check(GalleryCompat.expired(SIGNED + "&Expires=1000000000000", System.currentTimeMillis()), "expired milliseconds");
        check(!GalleryCompat.expired(SIGNED + "&expires=9999999999", System.currentTimeMillis()), "future expiry");
        check(!GalleryCompat.expired(SIGNED, System.currentTimeMillis()), "opaque signature not guessed expired");
    }

    /**
     * Reddit's default encoding of a JSON response (no raw_json). Keys and JSON punctuation
     * contain none of these characters, so escaping the text equals escaping every string value.
     */
    static String escapeLikeReddit(String json) {
        return json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    /** Port of Sync 23033's WebsafeUtils.a (wc.p.a), which RedditHelper.r applies to captions. */
    static String websafe(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("‮", "").replaceAll("‏", "");
    }
    static boolean sameBytes(String expected, String actual) {
        return actual != null && Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Issue #55: the gallery request keeps Reddit's default escaping, like Sync's own requests. */
    static void encoding() throws Exception {
        equal("https://oauth.reddit.com/comments/abc123.json?limit=1&depth=1&always_show_media=1", URL,
                "gallery URL uses Sync's own encoding (no raw_json)");
        for (String input : new String[] {"abc123", "t3_abc123", "https://www.reddit.com/gallery/abc123",
                "https://redd.it/abc123?utm_source=share", "https://www.reddit.com/r/x/comments/abc123/title/"})
            check(!RequestCompat.galleryUrl(input).contains("raw_json"), "no raw_json for " + input);

        String caption = "Fish & chips, 5 > 3 and a literal &amp; kept";
        String link = "https://example.invalid/menu?fish=1&chips=2";
        String preview = "https://preview.redd.it/still.png?width=320&crop=smart&format=pjpg&auto=webp&s=e%2Ff%3Dg";
        String animated = "https://preview.redd.it/move.gif?format=mp4&s=c%2Bd&v=enabled_once";
        JSONObject post = post("abc123").put("gallery_data", new JSONObject().put("items", new JSONArray()
                .put(item("first", caption, link)).put(item("moving", "Plain", null))))
                .put("media_metadata", new JSONObject()
                        .put("first", image("u", SIGNED).put("p", new JSONArray().put(new JSONObject().put("u", preview))))
                        .put("moving", new JSONObject().put("status", "valid").put("e", "AnimatedImage")
                                .put("s", new JSONObject().put("mp4", animated).put("gif", "https://i.redd.it/move.gif"))));
        String escaped = escapeLikeReddit(thread(post).toString());
        check(escaped.contains("&amp;s=a%2Fb%2Bcd") && escaped.contains("Fish &amp; chips")
                && escaped.contains("literal &amp;amp; kept"), "fixture escaped like Reddit's default response");
        GalleryCompat.Result result = GalleryCompat.parseGallery(new NetworkResponse(200,
                escaped.getBytes(StandardCharsets.UTF_8), Collections.emptyMap(), false, 0), new GalleryRequest());
        o8.b value = success(result);
        check(sameBytes(SIGNED, value.b[0]), "escaped signed source byte-identical to the original: " + value.b[0]);
        check(sameBytes(preview, value.c[0]), "escaped signed preview byte-identical to the original: " + value.c[0]);
        check(sameBytes(animated, value.b[1]), "escaped signed MP4 byte-identical to the original: " + value.b[1]);
        // Like Sync's own gallery parser, captions reach the viewer as Reddit sent them; Sync's
        // caption renderer (RedditHelper.r) unescapes them once with WebsafeUtils.
        equal(escapeLikeReddit(caption) + "\n\n" + escapeLikeReddit(link), value.d[0], "caption and link passed through unchanged");
        equal(caption + "\n\n" + link, websafe(value.d[0]), "caption with & renders as typed after Sync's unescaping");
        check(!websafe(caption).equals(caption), "raw (raw_json) caption would lose its literal &amp;");
        equal("Plain\n\n", value.d[1], "caption without entities unchanged (Sync's caption layout)");
        o8.b reopened = success(GalleryCompat.parseGallery(result.response, new GalleryRequest()));
        check(sameBytes(SIGNED, reopened.b[0]) && sameBytes(preview, reopened.c[0]) && sameBytes(animated, reopened.b[1])
                && value.d[0].equals(reopened.d[0]), "cached escaped listing reopens with the same bytes");
        // A listing cached by earlier versions (raw_json responses) still opens unchanged.
        o8.b legacy = success(parse(thread(post)));
        check(sameBytes(SIGNED, legacy.b[0]) && sameBytes(animated, legacy.b[1]), "earlier raw cache entries still load");
    }

    /** Issue #48: without a post ID the gallery request fails explicitly instead of using /api/info. */
    static void fallback() throws Exception {
        for (String input : new String[] {null, "", "not a url", "https://example.invalid/not-a-gallery",
                "https://www.reddit.com/r/pics/s/AbCdEf12", "https://www.reddit.com/r/pics/", "t3_", "?is_gallery=true"}) {
            equal(RequestCompat.UNIDENTIFIED_GALLERY_URL, RequestCompat.galleryUrl(input), "unidentified gallery for " + input);
        }
        java.net.URI unidentified = new java.net.URI(RequestCompat.UNIDENTIFIED_GALLERY_URL);
        check(!"http".equalsIgnoreCase(unidentified.getScheme()) && !"https".equalsIgnoreCase(unidentified.getScheme())
                && !RequestCompat.UNIDENTIFIED_GALLERY_URL.contains("/api/info"), "fallback is not an HTTP(S) URL and never /api/info");
        // Sync's Volley-over-OkHttp stack refuses it before connecting; the error names the scheme.
        check(AwardCompat.unidentified(new com.android.volley.VolleyError(new IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but was '" + unidentified.getScheme() + "'"))), "refusal identifies the fallback");
        GalleryCompat.Result result = GalleryCompat.parseGallery(response(thread(gallery())),
                new GalleryRequest(RequestCompat.UNIDENTIFIED_GALLERY_URL));
        equal(GalleryCompat.UNREADABLE, result.error == null ? null : result.error.getMessage(), "no gallery is read for the fallback");
        for (String input : new String[] {"abc123", "t3_abc123", "https://www.reddit.com/gallery/abc123", "https://redd.it/abc123",
                "https://www.reddit.com/r/pics/comments/abc123/title/"})
            equal(URL, RequestCompat.galleryUrl(input), "post ID still read from " + input);
    }

    static void refresh() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        List<String> requested = new ArrayList<>();
        int[] status = {200};
        boolean[] chunked = {false};
        Runnable[] onRequest = {() -> { }};
        String[] body = {thread(gallery()).toString()};
        server.createContext("/comments/abc123.json", exchange -> {
            calls.incrementAndGet();
            equal("Bearer TEST_ONLY", exchange.getRequestHeaders().getFirst("Authorization"), "current OAuth header stays on metadata origin");
            equal("gallery-test", exchange.getRequestHeaders().getFirst("User-Agent"), "User-Agent retained");
            equal("application/json", exchange.getRequestHeaders().getFirst("Accept"), "JSON requested");
            check(exchange.getRequestHeaders().getFirst("Cookie") == null
                    && exchange.getRequestHeaders().getFirst("X-Private") == null, "unrelated private headers omitted");
            if (status[0] == 302) exchange.getResponseHeaders().set("Location", "/redirect-must-not-run");
            onRequest[0].run();
            byte[] bytes = body[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], chunked[0] ? 0 : bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/slow", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(GalleryCompat.REFRESH_MS + 1000); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/redirect-must-not-run", exchange -> {
            redirects.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.start();
        GalleryCompat.connections = url -> {
            requested.add(url);
            return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort()
                    + new java.net.URI(url).getRawPath()).openConnection();
        };
        try {
            JSONObject expired = gallery();
            expired.getJSONObject("media_metadata").put("first", image("u", SIGNED + "&expires=1"));
            JSONObject original = envelope(expired).put("position", 2);
            // A refresh may reorder items; the original order/captions and index still win.
            JSONObject reordered = gallery();
            JSONArray list = reordered.getJSONObject("gallery_data").getJSONArray("items");
            reordered.getJSONObject("gallery_data").put("items", new JSONArray().put(list.get(2)).put(list.get(0)));
            body[0] = thread(reordered).toString();
            GalleryCompat.Result refreshed = parse(original);
            o8.b value = success(refreshed);
            equal(1, calls.get(), "one metadata refresh for an expired source");
            equal(List.of(URL), requested, "refresh uses fixed canonical OAuth URL");
            equal(SIGNED, value.b[0], "fresh signed URL returned without dropping parameters");
            equal(3, value.b.length, "refresh does not replace original item order");
            equal(2, value.a, "refresh retains saved position");
            check(value.d[0].startsWith("First"), "refresh retains original captions");
            success(GalleryCompat.parseGallery(refreshed.response, new GalleryRequest()));
            equal(1, calls.get(), "cache stores refreshed metadata");
            // Reddit answers the raw_json-free refresh URL with its default escaping (#55).
            JSONObject unescaped = gallery();
            unescaped.getJSONObject("media_metadata").put("first", image("u", SIGNED));
            body[0] = escapeLikeReddit(thread(unescaped).toString());
            value = success(parse(original));
            equal(2, calls.get(), "escaped refresh is one request");
            check(sameBytes(SIGNED, value.b[0]), "escaped refresh returns the exact signed URL: " + value.b[0]);
            check(value.d[0].startsWith("First"), "escaped refresh retains original captions");
            // Several expired entries still produce only one request; still-expired data is not retried.
            body[0] = thread(expired).toString();
            value = success(parse(original));
            equal(3, calls.get(), "still-expired refresh is not recursive");
            equal("", value.b[0], "expired source never sent to viewer");
            equal("https://i.redd.it/last.gif", value.b[2], "other media survives expired source");
            JSONObject partial = gallery();
            partial.getJSONObject("media_metadata").remove("last");
            body[0] = thread(partial).toString();
            equal("https://i.redd.it/last.gif", success(parse(original)).b[2], "partial refresh keeps usable original neighbor");
            for (int code : new int[] {302, 401, 403, 429, 500}) {
                status[0] = code;
                int before = calls.get();
                GalleryCompat.Result failed = parse(original);
                success(failed);
                equal(before + 1, calls.get(), "failed refresh is tried once");
                check(failed.response == null, "failed refresh not cached, explicit reopen can retry");
                equal("", failed.gallery.b[0], "failed expired item unavailable");
                equal("https://i.redd.it/last.gif", failed.gallery.b[2], "neighbor survives HTTP failure");
            }
            equal(0, redirects.get(), "redirect never followed with credentials");
            status[0] = 200;
            for (String bad : new String[] {"PRIVATE malformed", thread(gallery().put("id", "wrong")).toString(),
                    "x".repeat(GalleryCompat.MAX_BYTES + 1)}) {
                body[0] = bad;
                GalleryCompat.Result failed = parse(original);
                success(failed);
                check(failed.response == null, "malformed/wrong/oversized refresh cannot poison cache");
                equal("", failed.gallery.b[0], "bad refresh never substitutes unrelated media");
            }
            int before = calls.get();
            success(parse(thread(gallery())));
            equal(before, calls.get(), "missing metadata and opaque signatures cause no speculative network requests");
            body[0] = "x".repeat(GalleryCompat.MAX_BYTES + 1);
            chunked[0] = true;
            GalleryCompat.Result chunkedFailure = parse(original);
            success(chunkedFailure);
            check(chunkedFailure.response == null, "streamed response without Content-Length remains bounded");
            chunked[0] = false;
            body[0] = thread(gallery()).toString();
            GalleryRequest canceledDuringRefresh = new GalleryRequest();
            onRequest[0] = canceledDuringRefresh::cancel;
            GalleryCompat.Result canceled = GalleryCompat.parseGallery(response(original), canceledDuringRefresh);
            equal("Gallery loading canceled.", canceled.error.getMessage(), "cancellation during refresh is honored");
            onRequest[0] = () -> { };
            GalleryCompat.connections = url -> (HttpURLConnection) new URL("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/slow").openConnection();
            long start = System.nanoTime();
            GalleryCompat.Result timedOut = parse(original);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            success(timedOut);
            check(timedOut.response == null && elapsedMs < GalleryCompat.REFRESH_MS + 1000,
                    "slow refresh terminates within timeout budget, usable gallery remains: " + elapsedMs);
        } finally { server.stop(0); }
    }
}
