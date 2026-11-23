package app.revanced.extension.synccompat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ArcticShiftRestore against a loopback archive (the fixed host routed to 127.0.0.1 by the
 * OkHttp test stand-in): found, missing, 404, empty, malformed, oversized, slow and trickling
 * answers, redirects and other statuses; comment ID validation; the exact request sent (no
 * Authorization or Cookie header); the adapted JSON handed to Sync's parser; and the messages shown.
 * Posts: which posts are offered "Restore post", post ID validation, the archived author and
 * title and selftext kept, Sync's list preview, the columns stored, the same HTTP outcomes and the tap.
 */
public final class ArcticShiftRestoreTest {
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
        final String method, path, query;
        final Map<String, List<String>> headers;
        Seen(HttpExchange exchange) {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getRawPath();
            query = exchange.getRequestURI().getRawQuery();
            headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values));
        }
    }

    private interface Answer { void send(HttpExchange exchange) throws IOException, InterruptedException; }

    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static final List<String> OTHER_HOST = new CopyOnWriteArrayList<>();
    private static volatile Answer answer;

    /** Recording stand-in for Sync: synchronous jobs, recorded toasts, parser input. */
    private static final class FakeSync implements ArcticShiftRestore.Platform {
        final List<String> toasts = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();
        final List<String> rows = new ArrayList<>();
        final List<Runnable> taps = new ArrayList<>();
        final List<String> stored = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        boolean busy, parserSucceeds = true, parserThrows, storeThrows, rowThrows, dismissThrows;
        @Override public void execute(Runnable task) {
            if (busy) throw new RejectedExecutionException("busy");
            task.run();
        }
        @Override public void toast(String message) { toasts.add(message); }
        @Override public boolean deliver(Object request, byte[] json) throws Exception {
            equal("request", request, "Sync's own request is handed to its parser");
            delivered.add(new String(json, StandardCharsets.UTF_8));
            if (parserThrows) throw new IllegalStateException("parser failed");
            return parserSucceeds;
        }
        @Override public void addRow(Object anchor, String title, Runnable tap) {
            if (rowThrows) throw new IllegalStateException("no row");
            equal("share", anchor, "the row is placed by Sync's Share row");
            rows.add(title);
            taps.add(tap);
        }
        @Override public void dismiss(Object sheet) {
            equal("sheet", sheet, "Sync's own sheet is dismissed");
            events.add("dismissed");
            if (dismissThrows) throw new IllegalStateException("already gone");
        }
        // Stand-ins for Sync's WebsafeUtils and markdown processing, and Android's Html.
        @Override public String unescape(String text) { return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"); }
        @Override public String process(String raw) {
            StringBuilder html = new StringBuilder();
            for (String paragraph : raw.split("\n\n")) html.append(html.length() == 0 ? "" : "\n").append("<p>").append(paragraph).append("</p>");
            return html.toString();
        }
        @Override public String title(String text) { return unescape(text) + "\r"; }
        @Override public String htmlText(String html) {
            return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        }
        @Override public void store(String syncId, Map<String, String> values) {
            if (storeThrows) throw new IllegalStateException("database closed");
            stored.add(syncId + " " + values);
        }
    }

    public static void main(String[] args) throws Exception {
        ids();
        notice();
        adapt();
        postIds();
        postOffered();
        adaptPost();
        preview();
        postValues();

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
        OkHttpClient.ROUTES.put(ArcticShiftRestore.HOST, "http://127.0.0.1:" + archive.getAddress().getPort());
        OkHttpClient.ROUTES.put("example.org", "http://127.0.0.1:" + other.getAddress().getPort());
        try {
            client();
            request();
            outcomes();
            limits();
            restore();
            postRequest();
            postOutcomes();
            restorePost();
            offerPost();
        } finally {
            archive.stop(0);
            other.stop(0);
        }
        System.out.println("PASS: " + checks + " Arctic Shift restore checks (comments and posts; loopback archive: found, missing, "
                + "empty, malformed, oversized, slow, redirect; ID validation; no Authorization or Cookie header)");
    }

    // ---- IDs and the adapted parser input ----

    private static void ids() {
        equal("k0cydtb", ArcticShiftRestore.commentId("k0cydtb"), "plain ID");
        equal("k0cydtb", ArcticShiftRestore.commentId("t1_k0cydtb"), "t1_ prefix removed");
        equal("1", ArcticShiftRestore.commentId("1"), "one character");
        equal("zzzzzzzzzzzzz", ArcticShiftRestore.commentId("zzzzzzzzzzzzz"), "13 characters (longer than any 64-bit ID)");
        for (String bad : new String[] {null, "", "t1_", "zzzzzzzzzzzzzz", "t1_zzzzzzzzzzzzzz", "K0CYDTB", "k0c-ydtb", "k0c ydtb",
                "k0cydtb&fields=all", "k0cydtb,abc", "../k0cydtb", "t3_k0cydtb", "T1_k0cydtb", "k0cydtb\n", "ｋ0cydtb", " k0cydtb"})
            equal(null, ArcticShiftRestore.commentId(bad), "rejected ID " + bad);
        equal("https://arctic-shift.photon-reddit.com/api/comments/ids?ids=k0cydtb&fields=id,author,body",
                ArcticShiftRestore.url("k0cydtb"), "the only URL requested");
    }

    /** Reddit's own removal notice, which Sync's comment sheet now also offers Restore for. */
    private static void notice() {
        for (String text : new String[] {"[ Removed by Reddit ]", "[Removed by Reddit]", "[removed by reddit]", " [ Removed by Reddit ] ",
                "[ Removed by Reddit on account of violating the content policy. ]",
                "[ Removed by Reddit in response to a copyright notice. ]", "[\tRemoved by Reddit\n]"})
            check(ArcticShiftRestore.removedByReddit(text), "notice: " + text);
        for (String text : new String[] {null, "", "[removed]", "[deleted]", "Removed by Reddit", "[ Removed by Redditors ]",
                "[ Removed by Reddit ] but here is more", "quoting [ Removed by Reddit ]", "[ Removed by Reddit", "Removed by Reddit ]",
                "[ Removed by Reddit ] ]x", "[ removed ]"})
            check(!ArcticShiftRestore.removedByReddit(text), "not the notice: " + text);
    }

    private static void adapt() {
        String found = "{\"data\":[{\"author\":\"someone\",\"body\":\"Spending over $60k \\u00e9 \\\"quoted\\\"\\n[link](https://x.y)\",\"id\":\"k0cydtb\"}]}";
        JSONObject parsed = new JSONObject(new String(ArcticShiftRestore.adapt("k0cydtb", "k0cydtb", found), StandardCharsets.UTF_8));
        JSONArray data = parsed.getJSONArray("data");
        equal(1, data.length(), "one entry");
        equal(1, parsed.length(), "only data");
        JSONObject entry = data.getJSONObject(0);
        equal("k0cydtb", entry.getString("id"), "Sync's ID");
        equal("someone", entry.getString("author"), "author");
        equal("Spending over $60k \u00e9 \"quoted\"\n[link](https://x.y)", entry.getString("body"), "body, unescaped and unchanged");
        equal(3, entry.length(), "only the fields Sync reads");
        // Sync compares the answer's id with Post.U(); the entry carries Sync's own value.
        entry = new JSONObject(new String(ArcticShiftRestore.adapt("k0cydtb", "t1_k0cydtb", found), StandardCharsets.UTF_8))
                .getJSONArray("data").getJSONObject(0);
        equal("t1_k0cydtb", entry.getString("id"), "entry uses Sync's ID as it was given");

        String other = "{\"data\":[{\"author\":\"a\",\"body\":\"other\",\"id\":\"zzz\"},{\"author\":\"b\",\"body\":\"mine\",\"id\":\"k0cydtb\",\"extra\":1}]}";
        entry = new JSONObject(new String(ArcticShiftRestore.adapt("k0cydtb", "k0cydtb", other), StandardCharsets.UTF_8))
                .getJSONArray("data").getJSONObject(0);
        equal("mine", entry.getString("body"), "the requested comment among others");

        String[] none = {
            "{\"data\":[]}", "{}", "{\"data\":null}", "{\"data\":{}}", "{\"data\":[null,1,\"x\"]}", "[]", "", "null", "not json",
            "{\"data\":[{\"author\":\"a\",\"body\":\"text\",\"id\":\"other\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"text\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"text\",\"id\":\"K0CYDTB\"}]}",
            "{\"data\":[{\"author\":null,\"body\":\"text\",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":null,\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":7,\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"[deleted]\",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\" [Removed] \",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"   \",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"[ Removed by Reddit ]\",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\" [ removed by reddit in response to a legal request. ] \",\"id\":\"k0cydtb\"}]}",
            "{\"data\":[{\"author\":\"a\",\"body\":\"text\",\"id\":\"k0cydtb\"}",
            "{\"error\":\"Too many requests\"}",
        };
        for (String text : none) equal(null, ArcticShiftRestore.adapt("k0cydtb", "k0cydtb", text), "no usable copy in " + text);
    }

    // ---- HTTP ----

    private static void client() {
        OkHttpClient client = ArcticShiftRestore.newClient();
        check(!client.followRedirects() && !client.followSslRedirects(), "redirects are not followed");
        check(client.interceptors().isEmpty() && client.networkInterceptors().isEmpty(), "a client of its own, without Sync's interceptors");
        equal((int) ArcticShiftRestore.CONNECT_TIMEOUT_MS, client.connectTimeoutMillis(), "connect bounded");
        equal((int) ArcticShiftRestore.READ_TIMEOUT_MS, client.readTimeoutMillis(), "read bounded");
        equal((int) ArcticShiftRestore.CALL_TIMEOUT_MS, client.callTimeoutMillis(), "whole call bounded");
    }

    private static void request() throws IOException {
        SEEN.clear();
        answer = json(200, "{\"data\":[{\"author\":\"a\",\"body\":\"b\",\"id\":\"k0cydtb\"}]}");
        check(ArcticShiftRestore.fetch("k0cydtb") != null, "answer read");
        equal(1, SEEN.size(), "one request");
        Seen seen = SEEN.get(0);
        equal("GET", seen.method, "method");
        equal("/api/comments/ids", seen.path, "path");
        equal("ids=k0cydtb&fields=id,author,body", seen.query, "only the comment ID and the fields Sync reads");
        for (String header : new String[] {"authorization", "cookie", "cookie2", "proxy-authorization", "x-reddit-session"})
            check(!seen.headers.containsKey(header), "no " + header + " header: " + seen.headers.keySet());
        equal(List.of("application/json"), seen.headers.get("accept"), "Accept");
        equal(List.of("sync_for_reddit"), seen.headers.get("user-agent"), "User-Agent");
        for (String name : seen.headers.keySet())
            check(List.of("accept", "user-agent", "host", "connection", "cache-control", "pragma", "accept-encoding").contains(name),
                    "no other header (the rest come from the HTTP transport): " + name);
    }

    private static void outcomes() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        String found = "{\"data\":[{\"author\":\"someone\",\"body\":\"the text\",\"id\":\"k0cydtb\"}]}";
        Object[][] cases = {
            {"found", json(200, found), ArcticShiftRestore.Outcome.RESTORED},
            {"missing", json(200, "{\"data\":[]}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"another comment", json(200, "{\"data\":[{\"author\":\"x\",\"body\":\"y\",\"id\":\"abc\"}]}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"archived as deleted", json(200, "{\"data\":[{\"author\":\"[deleted]\",\"body\":\"[deleted]\",\"id\":\"k0cydtb\"}]}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"HTTP 404", json(404, "{\"error\":\"Not found\"}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"empty body", json(200, ""), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"malformed JSON", json(200, "{\"data\":[{\"author\":"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"HTML", json(200, "<html>maintenance</html>"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"HTTP 429", json(429, "{\"error\":\"Too many requests\"}"), ArcticShiftRestore.Outcome.UNAVAILABLE},
            {"HTTP 500", json(500, "oops"), ArcticShiftRestore.Outcome.UNAVAILABLE},
            {"HTTP 400", json(400, "{\"error\":\"bad\"}"), ArcticShiftRestore.Outcome.UNAVAILABLE},
            {"redirect", (Answer) exchange -> {
                exchange.getResponseHeaders().add("Location", "https://example.org/api/comments/ids?ids=k0cydtb");
                exchange.sendResponseHeaders(302, -1);
            }, ArcticShiftRestore.Outcome.UNAVAILABLE},
            {"plain-HTTP redirect", (Answer) exchange -> {
                exchange.getResponseHeaders().add("Location", "http://example.org/");
                exchange.sendResponseHeaders(301, -1);
            }, ArcticShiftRestore.Outcome.UNAVAILABLE},
        };
        for (Object[] row : cases) {
            answer = (Answer) row[1];
            sync.delivered.clear();
            equal(row[2], ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "outcome for " + row[0]);
            equal(row[2] == ArcticShiftRestore.Outcome.RESTORED ? 1 : 0, sync.delivered.size(), "parser called for " + row[0] + " only when found");
        }
        check(OTHER_HOST.isEmpty(), "redirect targets never requested: " + OTHER_HOST);

        answer = json(200, found);
        sync.delivered.clear();
        ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb");
        JSONObject entry = new JSONObject(sync.delivered.get(0)).getJSONArray("data").getJSONObject(0);
        equal("someone", entry.getString("author"), "parser gets the author");
        equal("the text", entry.getString("body"), "parser gets the body");

        sync.parserSucceeds = false;
        equal(ArcticShiftRestore.Outcome.FAILED, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "Sync's parser reports an error");
        sync.parserSucceeds = true;
        sync.parserThrows = true;
        equal(ArcticShiftRestore.Outcome.FAILED, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "Sync's parser throws");
        sync.parserThrows = false;

        // Nothing listening: connection refused.
        String route = OkHttpClient.ROUTES.get(ArcticShiftRestore.HOST);
        OkHttpClient.ROUTES.put(ArcticShiftRestore.HOST, "http://127.0.0.1:1");
        equal(ArcticShiftRestore.Outcome.UNAVAILABLE, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "archive unreachable");
        OkHttpClient.ROUTES.put(ArcticShiftRestore.HOST, route);
    }

    private static void limits() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        int maxBytes = ArcticShiftRestore.MAX_RESPONSE_BYTES;
        long read = ArcticShiftRestore.READ_TIMEOUT_MS, call = ArcticShiftRestore.CALL_TIMEOUT_MS;
        try {
            ArcticShiftRestore.MAX_RESPONSE_BYTES = 4096;
            String padding = "x".repeat(5000);
            String big = "{\"data\":[{\"author\":\"a\",\"body\":\"" + padding + "\",\"id\":\"k0cydtb\"}]}";
            answer = json(200, big);
            equal(ArcticShiftRestore.Outcome.NOT_ARCHIVED, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "oversized (declared length)");
            answer = exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) { out.write(big.getBytes(StandardCharsets.UTF_8)); }
            };
            equal(ArcticShiftRestore.Outcome.NOT_ARCHIVED, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "oversized (chunked)");
            String fits = "{\"data\":[{\"author\":\"a\",\"body\":\"" + "x".repeat(3000) + "\",\"id\":\"k0cydtb\"}]}";
            answer = json(200, fits);
            equal(ArcticShiftRestore.Outcome.RESTORED, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "just under the limit");
            check(sync.delivered.isEmpty() || sync.delivered.size() == 1, "only the fitting answer reached the parser");

            ArcticShiftRestore.READ_TIMEOUT_MS = 400;
            ArcticShiftRestore.CALL_TIMEOUT_MS = 1_200;
            ArcticShiftRestore.resetClient();
            answer = exchange -> {
                Thread.sleep(3_000);
                byte[] body = "{\"data\":[{\"author\":\"a\",\"body\":\"late\",\"id\":\"k0cydtb\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            };
            long started = System.nanoTime();
            equal(ArcticShiftRestore.Outcome.UNAVAILABLE, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "slow server past the read limit");
            long slow = (System.nanoTime() - started) / 1_000_000;
            check(slow < 2_500, "gave up within the limit, took " + slow + " ms");

            // Headers at once, then one byte every 150 ms: each read is quick, the whole call is not.
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
            equal(ArcticShiftRestore.Outcome.UNAVAILABLE, ArcticShiftRestore.run("request", "k0cydtb", "k0cydtb"), "trickling server past the call limit");
            long trickle = (System.nanoTime() - started) / 1_000_000;
            check(trickle < 3_500, "whole call bounded, took " + trickle + " ms");
            check(sync.delivered.size() <= 1, "late answers never reach the parser");
        } finally {
            ArcticShiftRestore.MAX_RESPONSE_BYTES = maxBytes;
            ArcticShiftRestore.READ_TIMEOUT_MS = read;
            ArcticShiftRestore.CALL_TIMEOUT_MS = call;
            ArcticShiftRestore.resetClient();
        }
    }

    /** The entry point the patch calls: messages and which requests reach the archive. */
    private static void restore() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        String found = "{\"data\":[{\"author\":\"someone\",\"body\":\"the text\",\"id\":\"k0cydtb\"}]}";

        answer = json(200, found);
        SEEN.clear();
        ArcticShiftRestore.restore("request", "k0cydtb");
        equal(List.of(), sync.toasts, "restored silently (Sync updates the comment)");
        equal(1, sync.delivered.size(), "Sync's parser updated the comment");
        equal("ids=k0cydtb&fields=id,author,body", SEEN.get(0).query, "requested by ID");

        SEEN.clear();
        ArcticShiftRestore.restore("request", "t1_k0cydtb");
        equal("ids=k0cydtb&fields=id,author,body", SEEN.get(0).query, "prefix not sent");

        answer = json(200, "{\"data\":[]}");
        sync.toasts.clear();
        ArcticShiftRestore.restore("request", "k0cydtb");
        equal(List.of("This comment isn't in the Arctic Shift archive."), sync.toasts, "missing comment message");
        equal(ArcticShiftRestore.NOT_ARCHIVED, sync.toasts.get(0), "message constant");

        answer = json(503, "down");
        sync.toasts.clear();
        ArcticShiftRestore.restore("request", "k0cydtb");
        equal(List.of(ArcticShiftRestore.UNAVAILABLE), sync.toasts, "archive down message");

        SEEN.clear();
        sync.toasts.clear();
        for (String bad : new String[] {null, "", "bad id", "k0cydtb&x=1", "zzzzzzzzzzzzzz"}) ArcticShiftRestore.restore("request", bad);
        ArcticShiftRestore.restore(null, "k0cydtb");
        equal(Collections.nCopies(6, ArcticShiftRestore.FAILED), sync.toasts, "invalid input: message, no crash");
        check(SEEN.isEmpty(), "invalid IDs are never sent: " + SEEN.size());

        sync.busy = true;
        sync.toasts.clear();
        ArcticShiftRestore.restore("request", "k0cydtb");
        equal(List.of(ArcticShiftRestore.FAILED), sync.toasts, "busy worker: message, no crash");
    }

    // ---- Posts ----

    private static void postIds() {
        equal("1abcdef", ArcticShiftRestore.postId("1abcdef"), "plain post ID");
        equal("1abcdef", ArcticShiftRestore.postId("t3_1abcdef"), "t3_ prefix removed");
        for (String bad : new String[] {null, "", "t3_", "t1_1abcdef", "1ABCDEF", "1abc def", "1abcdef&fields=all", "../1abcdef",
                "1abcdef,abc", "zzzzzzzzzzzzzz", "T3_1abcdef", "1abcdef\n"})
            equal(null, ArcticShiftRestore.postId(bad), "rejected post ID " + bad);
        equal("https://arctic-shift.photon-reddit.com/api/posts/ids?ids=1abcdef&fields=id,author,title,selftext,spoiler,link_flair_text",
                ArcticShiftRestore.postUrl("1abcdef"), "the only post URL requested");
    }

    private static void postOffered() {
        String[][] offered = {{"[deleted]", "[deleted]"}, {"[deleted]", ""}, {"[deleted]", null}, {"[removed]", "text"},
            {"someone", "[removed]"}, {"someone", " [Deleted] "}, {"someone", "[ Removed by Reddit ]"},
            {"someone", "[ Removed by Reddit on account of violating the content policy. ]"}, {null, "[deleted]"}};
        for (String[] post : offered) check(ArcticShiftRestore.postOffered(post[0], post[1]), "offered for " + Arrays.toString(post));
        String[][] ordinary = {{"someone", "text"}, {"someone", ""}, {"someone", null}, {null, null}, {"", ""},
            {"someone", "quoting [deleted]"}, {"someone", "[ Removed by Reddit ] and more"}, {"deleted", "removed"}};
        for (String[] post : ordinary) check(!ArcticShiftRestore.postOffered(post[0], post[1]), "not offered for " + Arrays.toString(post));
    }

    private static void adaptPost() {
        ArcticShiftRestore.ArchivedPost post = ArcticShiftRestore.adaptPost("1abcdef",
                "{\"data\":[{\"author\":\"x\",\"id\":\"zzz\",\"selftext\":\"other\"},"
                + "{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"the text &amp; more\",\"spoiler\":false,\"extra\":1}]}");
        equal("someone", post.author, "archived author");
        equal("the text &amp; more", post.selftext, "archived selftext, as the archive escapes it");
        equal(null, post.title, "no title field: no title");
        check(!post.spoiler, "not a spoiler");

        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"\"}]}");
        equal("someone", post.author, "link post author");
        equal("", post.selftext, "a post without text keeps empty text");
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"  \"}]}");
        equal("", post.selftext, "blank text is empty text");
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\"}]}");
        equal(null, post.selftext, "no selftext field: the author only");
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"selftext\":\"saved text\"}]}");
        equal(null, post.author, "an author archived as [deleted] is not stored");
        equal("saved text", post.selftext, "the text still is");
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"[removed]\"}]}");
        equal("someone", post.author, "author kept when the text was archived as removed");
        equal(null, post.selftext, "removed text is not stored");
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"[ Removed by Reddit ]\"}]}");
        equal(null, post.selftext, "Reddit's notice is not stored");

        // Titles: Reddit writes over a deleted post's title; the archive saved the original.
        post = ArcticShiftRestore.adaptPost("1abcdef",
                "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"selftext\":\"t\",\"title\":\"What [deleted] means &amp; more\"}]}");
        equal("What [deleted] means &amp; more", post.title, "archived title, as the archive escapes it");
        for (String replaced : new String[] {"[deleted by user]", " [Deleted by user] ", "[deleted]", "[removed]", "[ Removed by moderator ]",
                "[ Removed by Reddit ]", "", "  "}) {
            post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"title\":"
                    + JSONObject.quote(replaced) + "}]}");
            equal(null, post.title, "a replaced or empty title is not stored: '" + replaced + "'");
        }
        post = ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"title\":\"Only the title\",\"selftext\":\"\"}]}");
        equal("Only the title", post.title, "a title alone is worth restoring");
        equal("", post.selftext, "beside it, empty text replaces [deleted]");
        equal(null, post.author, "the author stays out");

        check(ArcticShiftRestore.adaptPost("1abcdef", "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\",\"spoiler\":true}]}").spoiler,
                "spoiler flag");
        check(ArcticShiftRestore.adaptPost("1abcdef",
                "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\",\"link_flair_text\":\"Big SPOILERS\"}]}").spoiler,
                "spoiler flair, as Sync's parser decides");
        check(!ArcticShiftRestore.adaptPost("1abcdef",
                "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\",\"spoiler\":\"true\",\"link_flair_text\":7}]}").spoiler,
                "only a real flag or flair text");

        String[] none = {
            "{\"data\":[]}", "{}", "{\"data\":null}", "[]", "", "not json", "{\"data\":[null,1]}",
            "{\"data\":[{\"author\":\"a\",\"id\":\"other\",\"selftext\":\"t\"}]}",
            "{\"data\":[{\"author\":\"a\",\"selftext\":\"t\"}]}",
            "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"selftext\":\"[deleted]\"}]}",
            "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"selftext\":\"\"}]}",
            "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"selftext\":\"[deleted]\",\"title\":\"[deleted by user]\"}]}",
            "{\"data\":[{\"author\":null,\"id\":\"1abcdef\",\"selftext\":null}]}",
            "{\"data\":[{\"author\":7,\"id\":\"1abcdef\",\"selftext\":8}]}",
            "{\"data\":[{\"author\":\" \",\"id\":\"1abcdef\",\"selftext\":\"[removed]\"}]}",
            "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}",
        };
        for (String text : none) equal(null, ArcticShiftRestore.adaptPost("1abcdef", text), "no usable copy in " + text);
    }

    private static void preview() {
        ArcticShiftRestore.platform = new FakeSync();
        equal("one line", ArcticShiftRestore.preview("<p>one line</p>", false), "one paragraph");
        equal("first\n…", ArcticShiftRestore.preview("<p>first</p>\n<p>second</p>", false), "first paragraph, then an ellipsis");
        equal("a & b", ArcticShiftRestore.preview("<p>a &amp; b</p>", false), "HTML text");
        equal(null, ArcticShiftRestore.preview("<table><tr><td>x</td></tr></table>", false), "no preview for a table");
        equal("<Spoiler hidden>", ArcticShiftRestore.preview("<p>the ending</p>", true), "spoiler posts are hidden");
        equal("<Spoiler hidden>", ArcticShiftRestore.preview("<p><abbr>secret</abbr></p>\n<p>more</p>", false), "spoiler text hidden");
        equal(null, ArcticShiftRestore.preview("&#x200B;", false), "only a zero-width space entity");
        equal("ab", ArcticShiftRestore.preview("<p>a​b</p>", false), "zero-width spaces dropped");
        equal(null, ArcticShiftRestore.preview("", false), "empty");
        equal(null, ArcticShiftRestore.preview(null, false), "none");
        equal("<Spoiler hidden>", ArcticShiftRestore.preview(null, true), "an empty spoiler post, as Sync stores it");
    }

    private static void postValues() {
        ArcticShiftRestore.platform = new FakeSync();
        Map<String, String> values = ArcticShiftRestore.postValues(new ArcticShiftRestore.ArchivedPost("someone", "T &amp; t", "a &amp; b\n\nmore", false));
        equal(List.of("author", "title", "selftext_raw", "selftext_processed", "selftext_processed_preview"), new ArrayList<>(values.keySet()),
                "the columns Sync's post parser fills");
        equal("someone", values.get("author"), "author");
        equal("T & t\r", values.get("title"), "title through Sync's title cleanup");
        equal("a & b\n\nmore", values.get("selftext_raw"), "raw text unescaped by Sync");
        equal("<p>a & b</p>\n<p>more</p>", values.get("selftext_processed"), "processed by Sync");
        equal("a & b\n…", values.get("selftext_processed_preview"), "list preview");
        values = ArcticShiftRestore.postValues(new ArcticShiftRestore.ArchivedPost("someone", null, null, false));
        equal(Map.of("author", "someone"), values, "author only");
        values = ArcticShiftRestore.postValues(new ArcticShiftRestore.ArchivedPost(null, null, "text", false));
        check(!values.containsKey("author") && "text".equals(values.get("selftext_raw")), "text only: " + values);
        values = ArcticShiftRestore.postValues(new ArcticShiftRestore.ArchivedPost("someone", null, "", false));
        equal("", values.get("selftext_raw"), "empty text replaces [deleted]");
        check(values.containsKey("selftext_processed_preview") && values.get("selftext_processed_preview") == null, "and clears the preview");
    }

    private static void postRequest() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        SEEN.clear();
        answer = json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\",\"title\":\"T\"}]}");
        equal(ArcticShiftRestore.Outcome.RESTORED, ArcticShiftRestore.runPost("1abcdef", "1abcdef"), "post found");
        equal(1, SEEN.size(), "one request");
        Seen seen = SEEN.get(0);
        equal("GET", seen.method, "method");
        equal("/api/posts/ids", seen.path, "path");
        equal("ids=1abcdef&fields=id,author,title,selftext,spoiler,link_flair_text", seen.query, "only the post ID and the fields used");
        for (String name : seen.headers.keySet())
            check(List.of("accept", "user-agent", "host", "connection", "cache-control", "pragma", "accept-encoding").contains(name),
                    "no Authorization, Cookie or other header: " + name);
        equal(List.of("1abcdef {author=a, title=T\r, selftext_raw=t, selftext_processed=<p>t</p>, selftext_processed_preview=t}"), sync.stored,
                "stored in the post's own row");
    }

    private static void postOutcomes() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        Object[][] cases = {
            {"found", json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}]}"), ArcticShiftRestore.Outcome.RESTORED},
            {"missing", json(200, "{\"data\":[]}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"archived as deleted", json(200, "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1abcdef\",\"selftext\":\"[deleted]\"}]}"),
                ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"HTTP 404", json(404, "{\"error\":\"Not found\"}"), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"malformed", json(200, "{\"data\":["), ArcticShiftRestore.Outcome.NOT_ARCHIVED},
            {"HTTP 500", json(500, "oops"), ArcticShiftRestore.Outcome.UNAVAILABLE},
            {"redirect", (Answer) exchange -> {
                exchange.getResponseHeaders().add("Location", "https://example.org/api/posts/ids?ids=1abcdef");
                exchange.sendResponseHeaders(302, -1);
            }, ArcticShiftRestore.Outcome.UNAVAILABLE},
        };
        for (Object[] row : cases) {
            answer = (Answer) row[1];
            sync.stored.clear();
            equal(row[2], ArcticShiftRestore.runPost("1abcdef", "1abcdef"), "post outcome for " + row[0]);
            equal(row[2] == ArcticShiftRestore.Outcome.RESTORED ? 1 : 0, sync.stored.size(), "stored for " + row[0] + " only when found");
        }
        check(OTHER_HOST.isEmpty(), "redirect targets never requested: " + OTHER_HOST);
        answer = json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}]}");
        sync.storeThrows = true;
        equal(ArcticShiftRestore.Outcome.FAILED, ArcticShiftRestore.runPost("1abcdef", "1abcdef"), "Sync's database fails");
        sync.storeThrows = false;
        sync.stored.clear();
        ArcticShiftRestore.runPost("1abcdef", "t3_1abcdef");
        check(sync.stored.get(0).startsWith("t3_1abcdef "), "stored under Sync's own ID as given: " + sync.stored);
    }

    private static void restorePost() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        answer = json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}]}");
        ArcticShiftRestore.restorePost("1abcdef");
        equal(List.of(ArcticShiftRestore.RESTORING_POST), sync.toasts, "progress, then Sync shows the restored post");
        equal(1, sync.stored.size(), "stored");

        answer = json(200, "{\"data\":[]}");
        sync.toasts.clear();
        ArcticShiftRestore.restorePost("1abcdef");
        equal(List.of(ArcticShiftRestore.RESTORING_POST, "This post isn't in the Arctic Shift archive."), sync.toasts, "missing post message");
        equal(ArcticShiftRestore.POST_NOT_ARCHIVED, sync.toasts.get(1), "message constant");

        answer = json(503, "down");
        sync.toasts.clear();
        ArcticShiftRestore.restorePost("1abcdef");
        equal(List.of(ArcticShiftRestore.RESTORING_POST, ArcticShiftRestore.UNAVAILABLE), sync.toasts, "archive down message");

        answer = json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}]}");
        sync.storeThrows = true;
        sync.toasts.clear();
        ArcticShiftRestore.restorePost("1abcdef");
        equal(List.of(ArcticShiftRestore.RESTORING_POST, "Couldn't restore this post."), sync.toasts, "failed store message");
        sync.storeThrows = false;

        SEEN.clear();
        sync.toasts.clear();
        for (String bad : new String[] {null, "", "bad id", "1abc&x=1", "t1_1abcdef"}) ArcticShiftRestore.restorePost(bad);
        equal(Collections.nCopies(5, ArcticShiftRestore.POST_FAILED), sync.toasts, "invalid post ID: message, no crash");
        check(SEEN.isEmpty(), "invalid post IDs are never sent");

        sync.busy = true;
        sync.toasts.clear();
        ArcticShiftRestore.restorePost("1abcdef");
        equal(List.of(ArcticShiftRestore.RESTORING_POST, ArcticShiftRestore.POST_FAILED), sync.toasts, "busy worker: message, no crash");
    }

    /** The entry point the post sheet calls once Sync has bound its rows. */
    private static void offerPost() {
        FakeSync sync = new FakeSync();
        ArcticShiftRestore.platform = sync;
        ArcticShiftRestore.offerPost("sheet", "share", "1abcdef", "[deleted]", "[deleted]", 2);
        equal(List.of("Restore post"), sync.rows, "row for a deleted post");
        ArcticShiftRestore.offerPost("sheet", "share", "1abcdef", "someone", "ordinary", 2);
        ArcticShiftRestore.offerPost("sheet", "share", "k0cydtb", "[deleted]", "[deleted]", 11);
        ArcticShiftRestore.offerPost(null, "share", "1abcdef", "[deleted]", "[deleted]", 2);
        ArcticShiftRestore.offerPost("sheet", null, "1abcdef", "[deleted]", "[deleted]", 2);
        equal(1, sync.rows.size(), "no row for an ordinary post, a comment, or a missing sheet or anchor");

        answer = json(200, "{\"data\":[{\"author\":\"a\",\"id\":\"1abcdef\",\"selftext\":\"t\"}]}");
        SEEN.clear();
        sync.taps.get(0).run();
        equal(List.of("dismissed"), sync.events, "the tap closes the sheet");
        equal(1, SEEN.size(), "and asks the archive once");
        equal(1, sync.stored.size(), "and stores the post");

        sync.dismissThrows = true;
        SEEN.clear();
        sync.taps.get(0).run();
        equal(1, SEEN.size(), "the restore runs even when the sheet cannot be dismissed");
        sync.dismissThrows = false;

        sync.rowThrows = true;
        int before = sync.rows.size();
        ArcticShiftRestore.offerPost("sheet", "share", "1abcdef", "[deleted]", "[deleted]", 2);
        equal(before, sync.rows.size(), "a failing row is contained, so Sync's sheet still opens");
    }


    private static Answer json(int code, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length != 0) try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        };
    }
}
