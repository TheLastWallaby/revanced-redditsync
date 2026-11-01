import app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch;
import app.revanced.extension.synccompat.RequestCompat;
import com.android.volley.NetworkResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issue #55: load-more requests keep Reddit's default HTML escaping, as Sync's own requests do.
 * The loopback server encodes like Reddit: &amp;, &lt; and &gt; in string values are escaped
 * unless the query has raw_json=1. A comment holding literal entity text must reach Sync's
 * parser with the same bytes on every load-more route as on Sync's own route.
 * Real Volley types and HTTP to a loopback server; no Reddit account or credentials.
 */
public final class CommentEncodingTest {
    static int checks;
    /** Text as a user typed it: entity text, markup characters and a nested entity. */
    static final String TYPED = "I typed &lt;3 and &amp; and &amp;amp; around <b>not bold</b> & 5 > 3";
    static final String TYPED_HTML = "<div class=\"md\"><p>I typed &amp;lt;3 &amp;amp; &lt;b&gt;</p></div>";
    static final String SIGNED = "https://preview.redd.it/pic.png?width=640&format=png&auto=webp&s=a%2Fb%2Bcd";
    static final String POST = "post123";
    static final Map<String,String> headers = Collections.singletonMap("Authorization", "Bearer TEST_ONLY");
    static final List<String> queries = new CopyOnWriteArrayList<>();
    static final Set<String> focalMissing = ConcurrentHashMap.newKeySet();
    static final AtomicInteger rawServed = new AtomicInteger(), batchCalls = new AtomicInteger();
    static String base;

    static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected <" + expected + "> got <" + actual + ">");
    }

    /**
     * Reddit's default encoding of a JSON response. Keys and JSON punctuation contain none of
     * these characters, so escaping the serialized text equals escaping every string value.
     */
    static String escapeLikeReddit(String json) {
        return json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    /** Port of Sync 23033's WebsafeUtils.a (wc.p.a), read from the prepared base. */
    static String websafe(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("‮", "").replaceAll("‏", "");
    }
    /**
     * Sync's comment text pipeline: PostParserHelper (c9.c.a) stores body_raw = websafe(body),
     * then RedditHelper.r (d7.f.r) applies websafe again before building body_processed.
     */
    static String syncText(String body) { return websafe(websafe(body)); }

    static JSONObject comment(String id) {
        JSONObject media = new JSONObject().put("img1", new JSONObject().put("status", "valid").put("e", "Image")
                .put("s", new JSONObject().put("u", SIGNED).put("x", 640).put("y", 480)));
        JSONObject data = new JSONObject().put("id", id).put("name", "t1_" + id).put("parent_id", "t3_" + POST)
                .put("link_id", "t3_" + POST).put("subreddit", "cats").put("body", TYPED).put("body_html", TYPED_HTML)
                .put("author_flair_text", "Fish & chips").put("media_metadata", media).put("replies", "");
        return new JSONObject().put("kind", "t1").put("data", data);
    }
    static JSONObject listing(JSONArray children) {
        return new JSONObject().put("kind", "Listing").put("data", new JSONObject().put("children", children));
    }
    static String thread(JSONObject... comments) {
        JSONObject post = new JSONObject().put("kind", "t3").put("data", new JSONObject().put("id", POST).put("subreddit", "cats"));
        return new JSONArray().put(listing(new JSONArray().put(post)))
                .put(listing(new JSONArray(Arrays.asList(comments)))).toString();
    }
    static Map<String,String> query(String raw) throws IOException {
        Map<String,String> out = new LinkedHashMap<>();
        if (raw != null) for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], "UTF-8"), kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "");
        }
        return out;
    }
    static void send(HttpExchange exchange, Map<String,String> query, String json) throws IOException {
        boolean raw = "1".equals(query.get("raw_json"));
        if (raw) rawServed.incrementAndGet();
        byte[] body = (raw ? json : escapeLikeReddit(json)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    /** Sync's own Volley request for the first hidden comment, after the patch rewrote its URL. */
    static NetworkResponse syncOwnRequest(Map<String,String> params) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(FixLoadMoreCommentsPatch.getUrl(base, params)).openConnection();
        connection.setRequestProperty("Authorization", "Bearer TEST_ONLY");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return new NetworkResponse(connection.getResponseCode(), out.toByteArray(), Collections.emptyMap(), false, 0);
        } finally { connection.disconnect(); }
    }
    static Map<String,String> params(String ids) {
        Map<String,String> out = new HashMap<>();
        out.put("children", ids);
        out.put("link_id", "t3_" + POST);
        out.put("sort", "new");
        return out;
    }
    /** Loads ids the way Sync does and returns the comments Sync's parser receives, by ID. */
    static Map<String,JSONObject> load(String ids) throws IOException {
        Map<String,String> params = params(ids);
        NetworkResponse result = FixLoadMoreCommentsPatch.getMoreChildrenResponse(syncOwnRequest(params), base, params,
                headers, "t3_" + POST);
        JSONArray things = new JSONObject(new String(result.data, StandardCharsets.UTF_8))
                .getJSONObject("json").getJSONObject("data").getJSONArray("things");
        Map<String,JSONObject> out = new LinkedHashMap<>();
        for (int i = 0; i < things.length(); i++) {
            JSONObject thing = things.getJSONObject(i);
            if ("t1".equals(thing.getString("kind"))) out.put(thing.getJSONObject("data").getString("id"), thing.getJSONObject("data"));
        }
        return out;
    }
    static int requestsFor(String fragment) {
        int count = 0;
        for (String request : queries) if (request.contains(fragment)) count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/comments/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
                queries.add(path + "?" + exchange.getRequestURI().getRawQuery());
                String json;
                if (path.equals("/comments/" + POST + ".json")) {
                    json = thread(comment(query.get("comment"))); // canonical fallback route
                } else {
                    String id = path.substring(path.lastIndexOf('/') + 1); // per-comment route
                    json = focalMissing.contains(id) ? thread() : thread(comment(id));
                }
                send(exchange, query, json);
            } finally { exchange.close(); }
        });
        server.createContext("/api/morechildren", exchange -> {
            try {
                batchCalls.incrementAndGet();
                Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
                queries.add(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
                JSONArray things = new JSONArray();
                for (String id : query.get("children").split(",")) things.put(comment(id));
                send(exchange, query, new JSONObject().put("json", new JSONObject().put("errors", new JSONArray())
                        .put("data", new JSONObject().put("things", things))).toString());
            } finally { exchange.close(); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/morechildren?always_show_media=1&profile_img=true";
        Class<?> transport = Class.forName("app.revanced.extension.syncforreddit.CommentTransport");
        java.lang.reflect.Method shared = transport.getDeclaredMethod("resetSharedState");
        shared.setAccessible(true);
        java.lang.reflect.Field batchSwitch = FixLoadMoreCommentsPatch.class.getDeclaredField("batchEndpoint");
        batchSwitch.setAccessible(true);
        try {
            shared.invoke(null);
            check(!batchSwitch.getBoolean(null), "batch attempt off by default (#51)");

            // Reference: the comment exactly as Sync's own request receives it.
            Map<String,JSONObject> own = load("own0");
            JSONObject reference = own.get("own0");
            check(reference != null, "Sync's own route loads the comment");
            equal(escapeLikeReddit(TYPED), reference.getString("body"), "Sync's own route keeps Reddit's default escaping");
            equal("I typed <3 and & and &amp; around <b>not bold</b> & 5 > 3", syncText(reference.getString("body")),
                    "Sync's own decoding of the fixture (it decodes &amp; before &lt;, twice)");

            Map<String,JSONObject> routes = new LinkedHashMap<>();
            // Per-comment route for a sibling.
            routes.put("per-comment sibling", load("own1,pc1").get("pc1"));
            check(requestsFor("/comments/" + POST + "/comment/pc1?") == 1, "sibling loaded through the per-comment route");
            // Canonical fallback for the first comment, then for a sibling.
            focalMissing.add("fb0");
            routes.put("fallback first comment", load("fb0").get("fb0"));
            check(requestsFor(".json?") > 0 && requestsFor("comment=fb0&context=0") == 1, "first comment loaded through the fallback route");
            focalMissing.add("fb1");
            routes.put("fallback sibling", load("own2,fb1").get("fb1"));
            check(requestsFor("comment=fb1&context=0") == 1, "sibling loaded through the fallback route");
            // The retained /api/morechildren route, turned on by reflection.
            shared.invoke(null);
            batchSwitch.setBoolean(null, true);
            try {
                Map<String,JSONObject> batch = load("own3,bt1,bt2");
                routes.put("batch sibling", batch.get("bt1"));
                routes.put("second batch sibling", batch.get("bt2"));
                check(batchCalls.get() == 1 && requestsFor("/comment/bt1?") == 0 && requestsFor("/comment/bt2?") == 0,
                        "siblings loaded through the batch route");
            } finally { batchSwitch.setBoolean(null, false); shared.invoke(null); }

            for (Map.Entry<String,JSONObject> route : routes.entrySet()) {
                JSONObject data = route.getValue();
                String name = route.getKey();
                check(data != null, name + " returns the comment");
                equal(reference.getString("body"), data.getString("body"), name + ": body bytes match Sync's own route");
                equal(reference.getString("body_html"), data.getString("body_html"), name + ": body_html bytes match Sync's own route");
                equal(reference.getString("author_flair_text"), data.getString("author_flair_text"), name + ": flair bytes match");
                equal(reference.getJSONObject("media_metadata").toString(), data.getJSONObject("media_metadata").toString(),
                        name + ": media metadata matches Sync's own route");
                equal(syncText(reference.getString("body")), syncText(data.getString("body")), name + ": renders identically");
            }
            String media = reference.getJSONObject("media_metadata").getJSONObject("img1").getJSONObject("s").getString("u");
            equal(SIGNED, websafe(media), "escaped signed comment media URL decodes to the exact original");

            // Every request kept Reddit's default encoding, so the comparisons above discriminate:
            // the raw form of the fixture would render differently through Sync's decoding.
            check(rawServed.get() == 0, "no response was requested in raw form");
            for (String request : queries) check(!request.contains("raw_json"), "no raw_json in " + request);
            check(!syncText(TYPED).equals(syncText(escapeLikeReddit(TYPED))), "fixture tells raw and escaped text apart");
            check(queries.size() >= 8, "every route was requested: " + queries.size());

            // RequestCompat's older comment URL helpers use the same encoding.
            Map<String,String> legacy = params("a1,b2");
            String canonical = RequestCompat.commentsUrl("https://oauth.reddit.com/api/morechildren?always_show_media=1", legacy);
            check(canonical.startsWith("https://oauth.reddit.com/comments/" + POST + ".json?comment=a1&context=0&limit=100&depth=10")
                    && !canonical.contains("raw_json"), "canonical comment URL without raw_json: " + canonical);
            String fallback = RequestCompat.commentsUrl("http://127.0.0.1/api/morechildren?always_show_media=1", legacy);
            check(fallback.startsWith("http://127.0.0.1/api/morechildren?always_show_media=1&") && !fallback.contains("raw_json"),
                    "legacy comment URL without raw_json: " + fallback);
            check(!FixLoadMoreCommentsPatch.getUrl(base, legacy).contains("raw_json"), "per-comment URL without raw_json");

            System.out.println("PASS: " + checks + " comment encoding checks; " + routes.size()
                    + " load-more routes match Sync's own route byte for byte; " + queries.size()
                    + " loopback requests, none with raw_json");
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
            java.lang.reflect.Field field = transport.getDeclaredField("executor");
            field.setAccessible(true);
            ((ExecutorService) field.get(null)).shutdownNow();
        }
    }
}
