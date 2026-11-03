package app.revanced.extension.synccompat;

import android.content.Context;
import android.net.Uri;
import com.android.volley.AuthFailureError;
import com.android.volley.ClientError;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.ServerError;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RedditImageUpload against loopback servers only, with a test-only token: oauth.reddit.com (the
 * lease, Reddit's converter, comments) and Reddit's upload host, routed to 127.0.0.1; Reddit's
 * media websocket is scripted. Sync's OAuth requests run through a stand-in of RedditRequest (x8.a)
 * and a small Volley runner that sends them as Volley's network stack does, with the test token as
 * OAuthRequestManager would set it.
 *
 * Covers: lease success in both reply shapes; lease errors (401, invalid_token, 403 with and
 * without insufficient_scope, 429, 5xx, malformed JSON, missing fields or key, timeout, offline);
 * the exact multipart upload (Reddit's fields in order, then the file); upload errors, redirects
 * and S3's EntityTooLarge; actions on other hosts, plain http, ports, user-info or paths refused
 * before any upload; the bearer token at the lease only, never Authorization or Cookie at the
 * upload host; size limit, unsupported and unreadable files, signed-out use; the editor text; the
 * comment and text-post conversion through Reddit's converter and the comment reply adapted for
 * Sync's parser; and image posts identified through the websocket, or a clear error.
 */
public final class RedditImageUploadTest {
    private static final String TOKEN = "test-only-token-7f3a";
    private static final String HOST = "reddit-uploaded-media.s3-accelerate.amazonaws.com";
    private static final String SOCKET_HOST = "k8s-lb.wss.redditmedia.com";
    private static final byte[] PNG = image(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] JPEG = image(0xFF, 0xD8, 0xFF, 0xE0);
    private static final byte[] GIF = image('G', 'I', 'F', '8', '9', 'a');
    private static final byte[] WEBP = image('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static byte[] image(int... header) {
        byte[] bytes = new byte[300];
        for (int i = 0; i < header.length; i++) bytes[i] = (byte) header[i];
        for (int i = header.length; i < bytes.length; i++) bytes[i] = (byte) (i * 7);
        return bytes;
    }

    // ---- Loopback servers ----

    /** One request a loopback server received. */
    static final class Seen {
        final String method, path, query;
        final Map<String, String> headers = new HashMap<>();
        final byte[] body;

        Seen(HttpExchange exchange, byte[] body) {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getRawPath();
            query = exchange.getRequestURI().getRawQuery();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
            this.body = body;
        }

        Map<String, String> form() {
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                try {
                    form.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (IOException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            return form;
        }
    }

    interface Answer { void send(HttpExchange exchange) throws Exception; }

    private static final List<Seen> REDDIT = new CopyOnWriteArrayList<>();
    private static final List<Seen> UPLOADS = new CopyOnWriteArrayList<>();
    private static final List<String> SOCKETS = new CopyOnWriteArrayList<>();
    private static final Map<String, Answer> ANSWERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Answer uploadAnswer;
    private static HttpServer reddit, host;

    private static void reply(HttpExchange exchange, int status, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    // ---- Reddit's lease, as PRAW's recorded leases (args, asset) ----

    static final String[] FIELD_NAMES = {"x-amz-algorithm", "key", "x-amz-storage-class", "success_action_status", "bucket",
            "acl", "x-amz-signature", "x-amz-security-token", "x-amz-date", "x-amz-meta-ext", "policy", "x-amz-credential", "Content-Type"};

    static JSONArray fields(String key, String mime) throws Exception {
        JSONArray fields = new JSONArray();
        for (String name : FIELD_NAMES) {
            String value = name.equals("key") ? key : name.equals("Content-Type") ? mime
                    : name.equals("success_action_status") ? "201" : "test-only-" + name + "-value";
            fields.put(new JSONObject().put("name", name).put("value", value));
        }
        return fields;
    }

    static String lease(String action, String key, String mime) throws Exception {
        JSONObject args = new JSONObject().put("fields", fields(key, mime));
        if (action != null) args.put("action", action);
        return new JSONObject().put("args", args)
                .put("asset", new JSONObject().put("asset_id", key).put("processing_state", "incomplete")
                        .put("websocket_url", "wss://" + SOCKET_HOST + "/" + key + "?m=test-only-signature"))
                .toString();
    }

    // ---- S3's reply ----

    static Answer created(String key) {
        return exchange -> reply(exchange, 201, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<PostResponse><Location>https://" + HOST + "/"
                + key + "</Location><Bucket>reddit-uploaded-media</Bucket><Key>" + key + "</Key><ETag>\"0\"</ETag></PostResponse>",
                "Content-Type", "application/xml");
    }

    // ---- Multipart as Reddit's upload host would read it ----

    static final class Part {
        final String name, filename, type;
        final byte[] content;
        Part(String name, String filename, String type, byte[] content) {
            this.name = name;
            this.filename = filename;
            this.type = type;
            this.content = content;
        }
    }

    static List<Part> multipart(Seen seen) {
        String type = seen.headers.get("content-type");
        check(type != null && type.startsWith("multipart/form-data; boundary="), "multipart/form-data: " + type);
        byte[] delimiter = ("--" + type.substring(type.indexOf("boundary=") + 9)).getBytes(StandardCharsets.UTF_8);
        List<Part> parts = new ArrayList<>();
        byte[] body = seen.body;
        int at = indexOf(body, delimiter, 0);
        check(at == 0, "body starts with the boundary");
        while (true) {
            int start = at + delimiter.length;
            if (body[start] == '-' && body[start + 1] == '-') break;
            start += 2;
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), start);
            String headers = new String(body, start, headersEnd - start, StandardCharsets.UTF_8);
            int next = indexOf(body, delimiter, headersEnd + 4);
            byte[] content = Arrays.copyOfRange(body, headersEnd + 4, next - 2);
            String name = null, filename = null, partType = null;
            for (String line : headers.split("\r\n")) {
                if (line.startsWith("Content-Disposition: form-data; name=\"")) {
                    name = line.substring(38, line.indexOf('"', 38));
                    int f = line.indexOf("filename=\"");
                    if (f >= 0) filename = line.substring(f + 10, line.indexOf('"', f + 10));
                } else if (line.startsWith("Content-Type: ")) partType = line.substring(14);
            }
            parts.add(new Part(name, filename, partType, content));
            at = next;
        }
        return parts;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ---- Sync, recorded ----

    /** Stand-in for Sync: a main thread, a worker, Sync's request queue (a small Volley runner). */
    static final class FakeSync implements RedditImageUpload.Platform {
        final ExecutorService main = Executors.newSingleThreadExecutor(r -> daemon(r, "test-main"));
        final ExecutorService worker = Executors.newFixedThreadPool(2, r -> daemon(r, "test-worker"));
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "test-timer"));
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
        final List<Object[]> resent = new CopyOnWriteArrayList<>();
        volatile boolean signedIn = true;
        volatile boolean offline;
        volatile CountDownLatch event = new CountDownLatch(1);

        private static Thread daemon(Runnable runnable, String name) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }

        @Override public void background(Runnable task) { worker.execute(task); }
        @Override public void main(Runnable task) { main.execute(task); }
        @Override public void later(long delayMs, Runnable task) { timer.schedule(() -> main.execute(task), delayMs, TimeUnit.MILLISECONDS); }
        @Override public boolean signedIn() { return signedIn; }

        @Override public InputStream open(Context context, Uri uri) throws IOException {
            byte[] data = files.get(uri.toString());
            if (data == null) throw new IOException("unreadable test file");
            return new ByteArrayInputStream(data);
        }

        @Override public void queue(Context context, Request<?> request) { worker.execute(() -> perform(request)); }

        @SuppressWarnings("unchecked")
        @Override public Map<String, String> params(Object request) {
            try {
                Field field = x8.a.class.getDeclaredField("s");
                field.setAccessible(true);
                return (Map<String, String>) field.get(request);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        @Override public void resend(Context context, Object request, String token, boolean exchange) {
            resent.add(new Object[] {request, token, exchange});
            event.countDown();
        }

        @Override public void fail(Object request, String message) {
            failures.add(message);
            event.countDown();
        }

        /** Sends a Sync OAuth request as Volley's BasicNetwork would, to the loopback oauth.reddit.com. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void perform(Request<?> request) {
            ((x8.a<?>) request).a(TOKEN);
            VolleyError failure;
            try {
                URI target = URI.create(request.getUrl());
                equal("oauth.reddit.com", target.getHost(), "Sync OAuth requests go to oauth.reddit.com");
                int port = offline ? closedPort() : reddit.getAddress().getPort();
                URL url = new URL("http://127.0.0.1:" + port + target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(request.getTimeoutMs());
                connection.setReadTimeout(request.getTimeoutMs());
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                for (Map.Entry<String, String> header : request.getHeaders().entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                connection.setRequestProperty("Content-Type", request.getBodyContentType());
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(request.getBody()); }
                int code = connection.getResponseCode();
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] data = stream == null ? new byte[0] : stream.readAllBytes();
                List<Header> headers = new ArrayList<>();
                connection.getHeaderFields().forEach((name, values) -> {
                    if (name != null) for (String value : values) headers.add(new Header(name, value));
                });
                NetworkResponse response = new NetworkResponse(code, data, false, 0, headers);
                if (code >= 200 && code < 300) {
                    Method parse = request.getClass().getDeclaredMethod("parseNetworkResponse", NetworkResponse.class);
                    parse.setAccessible(true);
                    Response<?> parsed = (Response<?>) parse.invoke(request, response);
                    main.execute(() -> {
                        if (parsed.isSuccess()) ((x8.a) request).deliverResponse(parsed.result);
                        else request.deliverError(parsed.error);
                    });
                    return;
                }
                failure = code == 401 || code == 403 ? new AuthFailureError(response)
                        : code >= 500 ? new ServerError(response) : new ClientError(response);
            } catch (SocketTimeoutException timeout) {
                failure = new TimeoutError();
            } catch (ConnectException refused) {
                failure = new NoConnectionError(refused);
            } catch (Exception unexpected) {
                throw new AssertionError(unexpected);
            }
            VolleyError mapped = ((x8.a<?>) request).parseNetworkError(failure);
            main.execute(() -> request.deliverError(mapped));
        }
    }

    private static int closedPort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static FakeSync sync;

    /** One upload's result: the URL handed to Sync, or the error Sync was given. */
    static final class Result {
        String url;
        VolleyError error;
        final AtomicInteger deliveries = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);

        String message() { return error == null ? null : error.getMessage(); }
    }

    static Result upload(byte[] file) throws Exception {
        String uri = "content://test.provider/image/" + System.nanoTime();
        if (file != null) sync.files.put(uri, file);
        return upload(uri);
    }

    static Result upload(String uri) throws Exception {
        Result result = new Result();
        sync.main.execute(() -> RedditImageUpload.upload(null, uri == null ? null : Uri.parse(uri), url -> {
            result.url = url;
            result.deliveries.incrementAndGet();
            result.done.countDown();
        }, error -> {
            result.error = error;
            result.deliveries.incrementAndGet();
            result.done.countDown();
        }));
        check(result.done.await(20, TimeUnit.SECONDS), "upload finished");
        return result;
    }

    private static void reset() {
        REDDIT.clear();
        UPLOADS.clear();
        SOCKETS.clear();
        ANSWERS.clear();
        uploadAnswer = null;
        sync.failures.clear();
        sync.resent.clear();
        sync.signedIn = true;
        sync.offline = false;
        RedditImageUpload.resetState();
    }

    // ---- The suite ----

    public static void main(String[] args) throws Exception {
        reddit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reddit.createContext("/", exchange -> {
            Seen seen = new Seen(exchange, body(exchange));
            REDDIT.add(seen);
            Answer answer = ANSWERS.get(seen.path);
            try {
                if (answer == null) reply(exchange, 404, "{\"message\": \"Not Found\", \"error\": 404}");
                else answer.send(exchange);
            } catch (Exception ignored) {
                exchange.close();
            }
        });
        reddit.setExecutor(Executors.newCachedThreadPool());
        reddit.start();
        host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        host.createContext("/", exchange -> {
            UPLOADS.add(new Seen(exchange, body(exchange)));
            try {
                Answer answer = uploadAnswer;
                if (answer == null) reply(exchange, 500, "");
                else answer.send(exchange);
            } catch (Exception ignored) {
                exchange.close();
            }
        });
        host.setExecutor(Executors.newCachedThreadPool());
        host.start();
        String origin = "http://127.0.0.1:" + host.getAddress().getPort();
        // Reddit's upload host, and others a lease might name: a request would arrive and be counted.
        for (String name : List.of(HOST, "evil.example", HOST + ".evil.example")) OkHttpClient.ROUTES.put(name, origin);

        sync = new FakeSync();
        RedditImageUpload.platform = sync;
        RedditImageUpload.UPLOAD_READ_MS = 2_000;
        RedditImageUpload.UPLOAD_CALL_MS = 5_000;
        RedditImageUpload.resetClients();
        try {
            uploads();
            leaseShapes();
            leaseErrors();
            actions();
            hostErrors();
            limits();
            timeouts();
            conversion();
            unsupportedPlaces();
            commentReplies();
            imagePosts();
            headers();
            System.out.println("PASS: " + checks + " Reddit image upload checks (lease, upload, conversion, replies and image posts "
                    + "against loopback Reddit and upload hosts)");
        } finally {
            reddit.stop(0);
            host.stop(0);
        }
    }

    private static void uploads() throws Exception {
        String[][] files = {{"png", "image/png"}, {"jpg", "image/jpeg"}, {"gif", "image/gif"}, {"webp", "image/webp"}};
        byte[][] bytes = {PNG, JPEG, GIF, WEBP};
        for (int n = 0; n < files.length; n++) {
            reset();
            String key = "abc12" + n + "def";
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, key, "image/x")));
            uploadAnswer = created(key);
            Result result = upload(bytes[n]);
            equal("https://" + HOST + "/" + key, result.url, files[n][1] + " uploaded; Sync gets the asset URL");
            equal(null, result.error, files[n][1] + " no error");

            equal(1, REDDIT.size(), "one lease request");
            Seen lease = REDDIT.get(0);
            equal("POST", lease.method, "lease is a POST");
            equal("/api/media/asset.json", lease.path, "lease path");
            equal("raw_json=1", lease.query, "lease query");
            equal("bearer " + TOKEN, lease.headers.get("authorization"), "lease carries Sync's bearer token");
            equal(x8.a.USER_AGENT, lease.headers.get("user-agent"), "lease carries Sync's User-Agent");
            Map<String, String> form = lease.form();
            equal(Map.of("api_type", "json", "mimetype", files[n][1]), form, "lease form: the image's MIME type only (no filepath)");

            equal(1, UPLOADS.size(), "one upload");
            Seen upload = UPLOADS.get(0);
            equal("POST", upload.method, "upload is a POST");
            equal("/", upload.path, "upload to the action's root");
            check(!upload.headers.containsKey("authorization"), "no Authorization at the upload host");
            check(!upload.headers.containsKey("cookie"), "no Cookie at the upload host");
            check(upload.headers.values().stream().noneMatch(v -> v.contains(TOKEN)), "the token never reaches the upload host");
            List<Part> parts = multipart(upload);
            List<String> names = new ArrayList<>();
            for (Part part : parts) names.add(part.name);
            List<String> expected = new ArrayList<>(Arrays.asList(FIELD_NAMES));
            expected.add("file");
            equal(expected, names, "Reddit's fields in lease order, then the file last");
            for (int i = 0; i < FIELD_NAMES.length; i++) {
                String value = new String(parts.get(i).content, StandardCharsets.UTF_8);
                String wanted = FIELD_NAMES[i].equals("key") ? key : FIELD_NAMES[i].equals("Content-Type") ? "image/x"
                        : FIELD_NAMES[i].equals("success_action_status") ? "201" : "test-only-" + FIELD_NAMES[i] + "-value";
                equal(wanted, value, "field " + FIELD_NAMES[i] + " sent unchanged");
                equal(null, parts.get(i).filename, "field " + FIELD_NAMES[i] + " is not a file");
            }
            Part file = parts.get(parts.size() - 1);
            equal("image." + files[n][0], file.filename, "file name");
            equal(files[n][1], file.type, "file part type");
            check(Arrays.equals(bytes[n], file.content), "the image bytes, unchanged");

            String markdown = RedditImageUpload.editorMarkdown(result.url);
            equal("\n\n![" + (files[n][1].equals("image/gif") ? "gif" : "img") + "](" + key + ")\n\n", markdown,
                    "the editor inserts Reddit's media marker for this upload");
        }
        equal(null, RedditImageUpload.editorMarkdown("https://i.imgur.com/abc.png"), "other links keep Sync's Insert image dialog");
        equal(null, RedditImageUpload.editorMarkdown(null), "no URL, no change");
    }

    private static void leaseShapes() throws Exception {
        // Infinity's image lease reply: the form without the args wrapper and without an asset.
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> {
            try {
                reply(exchange, 200, new JSONObject().put("action", "https://" + HOST).put("fields", fields("bare7key", "image/png")).toString());
            } catch (Exception e) { throw new IOException(e); }
        });
        uploadAnswer = created("bare7key");
        Result result = upload(PNG);
        equal("https://" + HOST + "/bare7key", result.url, "a bare form works too");
        equal("\n\n![img](bare7key)\n\n", RedditImageUpload.editorMarkdown(result.url), "the key is the media ID without an asset");

        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> {
            try {
                reply(exchange, 200, new JSONObject().put("fields", fields("noaction1", "image/png")).toString());
            } catch (Exception e) { throw new IOException(e); }
        });
        uploadAnswer = created("noaction1");
        result = upload(PNG);
        equal("https://" + HOST + "/noaction1", result.url, "no action: Reddit's known upload host");

        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "keyed1", "image/png")
                .replace("\"asset_id\":\"keyed1\"", "\"asset_id\":\"asset9\"")));
        uploadAnswer = exchange -> reply(exchange, 204, "");
        result = upload(PNG);
        equal("https://" + HOST + "/keyed1", result.url, "S3's 204 without a body is success");
        equal("\n\n![img](asset9)\n\n", RedditImageUpload.editorMarkdown(result.url), "asset_id is the media ID when Reddit gives one");
    }

    private static void leaseErrors() throws Exception {
        Object[][] cases = {
                {401, "", new String[0], RedditImageUpload.SIGN_IN_AGAIN},
                {401, "", new String[] {"www-authenticate", "Bearer realm=\"reddit\", error=\"invalid_token\""}, RedditImageUpload.SIGN_IN_AGAIN},
                {403, "{\"message\": \"Forbidden\", \"error\": 403}", new String[] {"www-authenticate", "Bearer realm=\"reddit\", error=\"insufficient_scope\""},
                        RedditImageUpload.NEEDS_PERMISSION},
                {403, "{\"message\": \"Forbidden\", \"error\": 403}", new String[0], "Reddit refused the image upload (HTTP 403)."},
                {400, "{\"message\": \"Bad Request\"}", new String[0], "Reddit refused the image upload (HTTP 400)."},
                {429, "", new String[] {"retry-after", "60"}, RedditImageUpload.RATE_LIMITED},
                {500, "", new String[0], "Reddit isn't available right now (HTTP 500). Try again later."},
                {503, "<html>busy</html>", new String[0], "Reddit isn't available right now (HTTP 503). Try again later."},
                {200, "{not json", new String[0], RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\"}}", new String[0], RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": []}}", new String[0], RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": [{\"name\": \"acl\", \"value\": \"private\"}]}}", new String[0],
                        RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": [{\"name\": \"key\", \"value\": \"../escape\"}]}}", new String[0],
                        RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": [{\"name\": \"key\", \"value\": \"k1\"}, {\"name\": \"file\", "
                        + "\"value\": \"x\"}]}}", new String[0], RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": [{\"name\": \"key\", \"value\": \"k1\"}, {\"name\": \"key\", "
                        + "\"value\": \"k2\"}]}}", new String[0], RedditImageUpload.MALFORMED},
                {200, "{\"args\": {\"action\": \"//" + HOST + "\", \"fields\": [{\"name\": \"key\", \"value\": 7}]}}", new String[0],
                        RedditImageUpload.MALFORMED},
                {200, "[]", new String[0], RedditImageUpload.MALFORMED},
        };
        for (Object[] c : cases) {
            reset();
            int status = (Integer) c[0];
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, status, (String) c[1], (String[]) c[2]));
            uploadAnswer = created("never");
            Result result = upload(PNG);
            String what = "lease HTTP " + status + " " + c[1] + " " + Arrays.toString((String[]) c[2]);
            equal(null, result.url, what + ": no URL");
            equal(c[3], result.message(), what + ": message");
            check(result.error instanceof u8.b, what + ": delivered as Sync's upload error type, whose message Sync shows");
            equal(0, UPLOADS.size(), what + ": nothing uploaded");
            equal(1, result.deliveries.get(), what + ": one result");
        }

        reset();
        sync.offline = true;
        Result result = upload(PNG);
        equal(RedditImageUpload.OFFLINE, result.message(), "offline: a clear message");
        equal(0, UPLOADS.size(), "offline: nothing uploaded");

        reset();
        sync.signedIn = false;
        result = upload(PNG);
        equal(RedditImageUpload.NOT_SIGNED_IN, result.message(), "signed out");
        equal(0, REDDIT.size() + UPLOADS.size(), "signed out: no request");

        reset();
        result = upload((String) null);
        equal(RedditImageUpload.NO_IMAGE, result.message(), "no image");
        equal(0, REDDIT.size(), "no image: no request");
    }

    private static void actions() throws Exception {
        String[] refused = {"//evil.example", "https://evil.example", "http://" + HOST, "//" + HOST + ":8443", "https://user@" + HOST,
                "https://" + HOST + "/upload", "https://" + HOST + "/?x=1", "https://" + HOST + "#f", "//" + HOST + ".evil.example",
                "ftp://" + HOST, "javascript:alert(1)", "", "//"};
        for (String action : refused) {
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease(action, "k1", "image/png")));
            uploadAnswer = created("k1");
            Result result = upload(PNG);
            equal(RedditImageUpload.BAD_HOST, result.message(), "action " + action + " refused");
            equal(0, UPLOADS.size(), "action " + action + ": nothing sent anywhere");
        }
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200,
                lease("//" + HOST, "k1", "image/png").replace("\"action\":\"//" + HOST + "\"", "\"action\":42")));
        equal(RedditImageUpload.BAD_HOST, upload(PNG).message(), "a non-text action refused");
        for (String accepted : new String[] {"//" + HOST, "https://" + HOST, "https://" + HOST + "/", "//" + HOST.toUpperCase(), "https://" + HOST + ":443"}) {
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease(accepted, "k2", "image/png")));
            uploadAnswer = created("k2");
            equal("https://" + HOST + "/k2", upload(PNG).url, "action " + accepted + " accepted");
        }
    }

    private static void hostErrors() throws Exception {
        Object[][] cases = {
                {403, "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code><Message>Invalid according to Policy</Message></Error>",
                        "Reddit's image host refused the upload (HTTP 403)."},
                {400, "<?xml version=\"1.0\"?><Error><Code>EntityTooLarge</Code><Message>Too large</Message><ProposedSize>30000000</ProposedSize>"
                        + "<MaxSizeAllowed>20971520</MaxSizeAllowed></Error>", RedditImageUpload.TOO_LARGE},
                {500, "", "Reddit's image host refused the upload (HTTP 500)."},
                {503, "Slow Down", "Reddit's image host refused the upload (HTTP 503)."},
                {201, "<PostResponse><Key>someone-else</Key></PostResponse>", RedditImageUpload.MALFORMED},
        };
        for (Object[] c : cases) {
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k3", "image/png")));
            uploadAnswer = exchange -> reply(exchange, (Integer) c[0], (String) c[1]);
            Result result = upload(PNG);
            equal(null, result.url, "upload HTTP " + c[0] + ": no URL");
            equal(c[2], result.message(), "upload HTTP " + c[0] + ": message");
            equal(null, RedditImageUpload.editorMarkdown("https://" + HOST + "/k3"), "upload HTTP " + c[0] + ": nothing registered");
        }
        // A redirect is not followed: the upload never goes anywhere else.
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k4", "image/png")));
        uploadAnswer = exchange -> reply(exchange, 307, "", "Location", "https://evil.example/");
        Result result = upload(PNG);
        equal("Reddit's image host refused the upload (HTTP 307).", result.message(), "redirect refused");
        equal(1, UPLOADS.size(), "the redirect target was never requested");
        // The host closes the connection.
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k5", "image/png")));
        uploadAnswer = exchange -> exchange.close();
        result = upload(PNG);
        equal(RedditImageUpload.OFFLINE, result.message(), "dropped connection: a clear message");
    }

    private static void limits() throws Exception {
        int saved = RedditImageUpload.MAX_IMAGE_BYTES;
        try {
            RedditImageUpload.MAX_IMAGE_BYTES = 300;
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k6", "image/png")));
            uploadAnswer = created("k6");
            equal("https://" + HOST + "/k6", upload(PNG).url, "exactly at the limit");
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k7", "image/png")));
            byte[] big = Arrays.copyOf(PNG, 301);
            equal(RedditImageUpload.TOO_LARGE, upload(big).message(), "one byte over the limit");
            equal(0, REDDIT.size() + UPLOADS.size(), "too large: no request");
        } finally {
            RedditImageUpload.MAX_IMAGE_BYTES = saved;
        }
        reset();
        equal(RedditImageUpload.UNSUPPORTED, upload("<html>not an image</html>".getBytes(StandardCharsets.UTF_8)).message(), "HTML refused");
        equal(RedditImageUpload.UNSUPPORTED, upload(image('B', 'M')).message(), "BMP refused");
        equal(RedditImageUpload.UNSUPPORTED, upload(image(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c')).message(), "HEIC refused");
        equal(RedditImageUpload.UNREADABLE, upload(new byte[0]).message(), "empty file");
        equal(RedditImageUpload.UNREADABLE, upload("content://test.provider/missing").message(), "unreadable file");
        equal(0, REDDIT.size() + UPLOADS.size(), "unusable files: no request");
        check(RedditImageUpload.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8}) == null, "a truncated header is not an image");
        check(RedditImageUpload.sniff(image('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E')) == null, "RIFF audio is not WebP");
    }

    private static void timeouts() throws Exception {
        int savedLease = RedditImageUpload.LEASE_TIMEOUT_MS;
        long savedRead = RedditImageUpload.UPLOAD_READ_MS, savedTotal = RedditImageUpload.TOTAL_TIMEOUT_MS;
        try {
            RedditImageUpload.LEASE_TIMEOUT_MS = 300;
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> {
                Thread.sleep(1_500);
                reply(exchange, 200, lease("//" + HOST, "k8", "image/png"));
            });
            long start = System.nanoTime();
            Result result = upload(PNG);
            equal(RedditImageUpload.TIMED_OUT, result.message(), "slow lease");
            check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1_400), "slow lease gave up at the limit");
            equal(0, UPLOADS.size(), "slow lease: nothing uploaded");
            RedditImageUpload.LEASE_TIMEOUT_MS = savedLease;

            RedditImageUpload.UPLOAD_READ_MS = 300;
            RedditImageUpload.resetClients();
            reset();
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k9", "image/png")));
            uploadAnswer = exchange -> {
                Thread.sleep(1_500);
                created("k9").send(exchange);
            };
            result = upload(PNG);
            equal(RedditImageUpload.TIMED_OUT, result.message(), "slow upload host");
            equal(null, RedditImageUpload.editorMarkdown("https://" + HOST + "/k9"), "slow upload: nothing registered");

            RedditImageUpload.UPLOAD_READ_MS = 5_000;
            RedditImageUpload.TOTAL_TIMEOUT_MS = 400;
            RedditImageUpload.resetClients();
            reset();
            CountDownLatch release = new CountDownLatch(1);
            ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "k10", "image/png")));
            uploadAnswer = exchange -> {
                release.await(3, TimeUnit.SECONDS);
                created("k10").send(exchange);
            };
            result = upload(PNG);
            equal(RedditImageUpload.TIMED_OUT, result.message(), "the whole upload is bounded");
            release.countDown();
            Thread.sleep(300);
            equal(1, result.deliveries.get(), "a late answer after the limit is not delivered again");
        } finally {
            RedditImageUpload.LEASE_TIMEOUT_MS = savedLease;
            RedditImageUpload.UPLOAD_READ_MS = savedRead;
            RedditImageUpload.TOTAL_TIMEOUT_MS = savedTotal;
            RedditImageUpload.resetClients();
        }
    }

    // ---- Comments and text posts ----

    /** Stand-in for one of Sync's text requests (OAuthCommentRequest, OAuthSubmitRequest, ...). */
    static class TextRequest extends x8.a<String> {
        TextRequest(String url, Map<String, String> params) {
            super(null, Request.Method.POST, url, null, null);
            s = params;
        }

        Map<String, String> params() { return s; }

        @Override protected Response<String> parseNetworkResponse(NetworkResponse response) {
            return Response.success(new String(response.data, StandardCharsets.UTF_8), null);
        }
    }

    static TextRequest comment(String thing, String text) {
        Map<String, String> params = new HashMap<>();
        params.put("thing_id", thing);
        params.put("text", text);
        params.put("api_type", "json");
        return new TextRequest("https://oauth.reddit.com/api/comment", params);
    }

    static String converted(String text, String... ids) throws Exception {
        JSONArray document = new JSONArray().put(new JSONObject().put("e", "par")
                .put("c", new JSONArray().put(new JSONObject().put("e", "text").put("t", "Look at this"))));
        for (String id : ids) document.put(new JSONObject().put("e", "img").put("id", id).put("c", ""));
        return new JSONObject().put("output", new JSONObject().put("document", document)).put("output_mode", "rtjson")
                .put("assets", new JSONArray()).toString();
    }

    private static void conversion() throws Exception {
        // Targets.
        Map<String, String> commentParams = Map.of("thing_id", "t1_abc", "text", "x");
        equal(RedditImageUpload.Target.CONVERT, RedditImageUpload.target("https://oauth.reddit.com/api/comment", commentParams), "reply to a comment");
        equal(RedditImageUpload.Target.CONVERT, RedditImageUpload.target("https://oauth.reddit.com/api/comment",
                Map.of("thing_id", "t3_abc")), "comment on a post");
        equal(RedditImageUpload.Target.UNSUPPORTED, RedditImageUpload.target("https://oauth.reddit.com/api/comment",
                Map.of("thing_id", "t4_abc")), "reply to a message");
        equal(RedditImageUpload.Target.CONVERT, RedditImageUpload.target("https://oauth.reddit.com/api/submit?validate_on_submit=True",
                Map.of("kind", "self")), "text post");
        equal(RedditImageUpload.Target.NONE, RedditImageUpload.target("https://oauth.reddit.com/api/submit?validate_on_submit=True",
                Map.of("kind", "link")), "link post");
        equal(RedditImageUpload.Target.UNSUPPORTED, RedditImageUpload.target("https://oauth.reddit.com/api/editusertext", commentParams), "edit");
        equal(RedditImageUpload.Target.UNSUPPORTED, RedditImageUpload.target("https://oauth.reddit.com/api/compose", Map.of()), "message");
        equal(RedditImageUpload.Target.NONE, RedditImageUpload.target("https://oauth.reddit.com/api/vote", Map.of()), "other request");
        equal(RedditImageUpload.Target.NONE, RedditImageUpload.target("http://oauth.reddit.com/api/comment", commentParams), "plain http");
        equal(RedditImageUpload.Target.NONE, RedditImageUpload.target("https://evil.example/api/comment", commentParams), "other host");
        equal(RedditImageUpload.Target.NONE, RedditImageUpload.target("https://oauth.reddit.com/api/comment", null), "no params");

        // Markers.
        equal(List.of("abc123"), new ArrayList<>(RedditImageUpload.markers("hi\n\n![img](abc123)\n\nbye")), "image marker");
        equal(List.of("g1", "i2"), new ArrayList<>(RedditImageUpload.markers("![gif](g1) and ![img](i2 \"a caption\")")), "gif and caption");
        check(RedditImageUpload.markers("![img](https://i.redd.it/abc.png) ![image](abc) [img](abc) ![img](a/b)").isEmpty(),
                "ordinary Markdown images and links are not markers");

        // No marker: Sync sends as before.
        reset();
        TextRequest plain = comment("t1_c1", "just text ![img](https://example.com/a.png)");
        check(!RedditImageUpload.sendDirect(null, TOKEN, plain, plain.getUrl()), "text without a Reddit image is untouched");
        equal(0, REDDIT.size(), "no conversion request");
        check(!RedditImageUpload.sendDirect(null, TOKEN, null, null), "null request untouched");

        // A comment with an image: Reddit's converter, then Sync's own send.
        reset();
        String text = "Look at this\n\n![img](media1)\n\n";
        ANSWERS.put("/api/convert_rte_body_format", exchange -> {
            try { reply(exchange, 200, converted(text, "media1")); } catch (Exception e) { throw new IOException(e); }
        });
        TextRequest request = comment("t1_c2", text);
        sync.event = new CountDownLatch(1);
        check(RedditImageUpload.sendDirect(null, "account-token", request, request.getUrl()), "the helper takes the comment over");
        check(sync.event.await(10, TimeUnit.SECONDS), "conversion finished");
        equal(1, REDDIT.size(), "one conversion request");
        Seen convert = REDDIT.get(0);
        equal("/api/convert_rte_body_format", convert.path, "Reddit's converter");
        equal("bearer " + TOKEN, convert.headers.get("authorization"), "converter carries Sync's bearer token");
        equal(Map.of("api_type", "json", "markdown_text", text, "output_mode", "rtjson"), convert.form(), "converter form");
        equal(1, sync.resent.size(), "Sync's request sent on");
        equal(request, sync.resent.get(0)[0], "the same request");
        equal("account-token", sync.resent.get(0)[1], "with Sync's token argument");
        equal(false, sync.resent.get(0)[2], "through m8.a.b");
        check(!request.params().containsKey("text"), "text replaced");
        JSONObject document = new JSONObject(request.params().get("richtext_json"));
        equal("img", document.getJSONArray("document").getJSONObject(1).getString("e"), "richtext_json holds the image");
        equal("media1", document.getJSONArray("document").getJSONObject(1).getString("id"), "with its media ID");
        equal("t1_c2", request.params().get("thing_id"), "other parameters unchanged");
        check(sync.failures.isEmpty(), "no failure");
        check(!RedditImageUpload.sendDirect(null, "account-token", request, request.getUrl()), "the sent-on request passes straight through");

        // Another account's reply (m8.a.g) and a text post.
        reset();
        ANSWERS.put("/api/convert_rte_body_format", exchange -> {
            try { reply(exchange, 200, converted(text, "media1")); } catch (Exception e) { throw new IOException(e); }
        });
        request = comment("t3_p1", text);
        sync.event = new CountDownLatch(1);
        check(RedditImageUpload.sendExchange(null, request, "refresh-token-of-other-account", request.getUrl()), "exchange path taken over");
        check(sync.event.await(10, TimeUnit.SECONDS), "exchange conversion finished");
        equal(true, sync.resent.get(0)[2], "sent on through m8.a.g");
        equal("refresh-token-of-other-account", sync.resent.get(0)[1], "with the account's refresh token");
        reset();
        ANSWERS.put("/api/convert_rte_body_format", exchange -> {
            try { reply(exchange, 200, converted(text, "media1")); } catch (Exception e) { throw new IOException(e); }
        });
        Map<String, String> post = new HashMap<>(Map.of("kind", "self", "text", text, "title", "A post", "sr", "test"));
        TextRequest selfPost = new TextRequest("https://oauth.reddit.com/api/submit?validate_on_submit=True", post);
        sync.event = new CountDownLatch(1);
        check(RedditImageUpload.sendDirect(null, null, selfPost, selfPost.getUrl()), "text post taken over");
        check(sync.event.await(10, TimeUnit.SECONDS), "text post converted");
        check(post.containsKey("richtext_json") && !post.containsKey("text") && post.get("kind").equals("self"), "text post sends richtext_json");

        // Converter failures keep the request unsent, with its text, and report a clear message.
        Object[][] failures = {
                {500, "", "Reddit isn't available right now (HTTP 500). Try again later."},
                {401, "", RedditImageUpload.SIGN_IN_AGAIN},
                {429, "", RedditImageUpload.RATE_LIMITED},
                {200, "{\"output\": {}}", RedditImageUpload.MALFORMED},
                {200, "nope", RedditImageUpload.MALFORMED},
                {200, null, RedditImageUpload.IMAGE_NOT_RECOGNIZED},
        };
        for (Object[] f : failures) {
            reset();
            ANSWERS.put("/api/convert_rte_body_format", exchange -> {
                try { reply(exchange, (Integer) f[0], f[1] == null ? converted(text, "someone-else") : (String) f[1]); }
                catch (Exception e) { throw new IOException(e); }
            });
            request = comment("t1_c3", text);
            sync.event = new CountDownLatch(1);
            check(RedditImageUpload.sendDirect(null, TOKEN, request, request.getUrl()), "taken over");
            check(sync.event.await(10, TimeUnit.SECONDS), "conversion failed");
            equal(List.of(f[2]), sync.failures, "converter " + f[0] + " " + f[1] + ": message");
            check(sync.resent.isEmpty(), "converter failure: not sent");
            equal(text, request.params().get("text"), "converter failure: the text is kept");
            check(!request.params().containsKey("richtext_json"), "converter failure: no richtext_json");
        }
        reset();
        sync.offline = true;
        request = comment("t1_c4", text);
        sync.event = new CountDownLatch(1);
        check(RedditImageUpload.sendDirect(null, TOKEN, request, request.getUrl()), "offline: taken over");
        check(sync.event.await(10, TimeUnit.SECONDS), "offline conversion failed");
        equal(List.of(RedditImageUpload.OFFLINE), sync.failures, "offline: a clear message, text kept");
        equal(text, request.params().get("text"), "offline: the text is kept");
    }

    private static void unsupportedPlaces() throws Exception {
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "fresh1", "image/png")));
        uploadAnswer = created("fresh1");
        equal("https://" + HOST + "/fresh1", upload(PNG).url, "uploaded for the editor");
        Map<String, String> edit = new HashMap<>(Map.of("thing_id", "t1_c5", "text", "edited\n\n![img](fresh1)\n\n", "api_type", "json"));
        TextRequest request = new TextRequest("https://oauth.reddit.com/api/editusertext", edit);
        check(RedditImageUpload.sendDirect(null, TOKEN, request, request.getUrl()), "an edit with a new image is stopped");
        equal(List.of(RedditImageUpload.UNSUPPORTED_PLACE), sync.failures, "edit: a clear message");
        equal("edited\n\n![img](fresh1)\n\n", edit.get("text"), "edit: text kept");
        sync.failures.clear();
        TextRequest message = comment("t4_m1", "hello ![img](fresh1)");
        check(RedditImageUpload.sendDirect(null, TOKEN, message, message.getUrl()), "a message reply with a new image is stopped");
        equal(List.of(RedditImageUpload.UNSUPPORTED_PLACE), sync.failures, "message reply: a clear message");
        sync.failures.clear();
        TextRequest old = new TextRequest("https://oauth.reddit.com/api/editusertext",
                new HashMap<>(Map.of("thing_id", "t1_c6", "text", "an old ![img](earlier9) marker")));
        check(!RedditImageUpload.sendDirect(null, TOKEN, old, old.getUrl()), "existing text in an edit is never touched");
        check(sync.failures.isEmpty() && REDDIT.size() == 1, "no message, no request");
    }

    /** Sync's OAuthCommentRequest parser, transcribed: the literal "errors": [] check, then things[0].data. */
    static String syncComment(NetworkResponse response) throws Exception {
        String text = new String(response.data, StandardCharsets.UTF_8);
        if (!text.contains("\"errors\": []")) {
            JSONArray errors = new JSONObject(text).getJSONObject("json").getJSONArray("errors");
            return "error: " + errors.getJSONArray(0).get(1);
        }
        return new JSONObject(text).getJSONObject("json").getJSONObject("data").getJSONArray("things").getJSONObject(0)
                .getJSONObject("data").getString("body");
    }

    private static void commentReplies() throws Exception {
        reset();
        String text = "Look at this\n\n![img](media2)\n\n";
        ANSWERS.put("/api/convert_rte_body_format", exchange -> {
            try { reply(exchange, 200, converted(text, "media2")); } catch (Exception e) { throw new IOException(e); }
        });
        String bare = "{\"id\": \"c9\", \"name\": \"t1_c9\", \"body\": \"Look at this\\n\\nhttps://preview.redd.it/media2.png\", "
                + "\"link_id\": \"t3_p1\", \"media_metadata\": {\"media2\": {\"status\": \"valid\"}}}";
        ANSWERS.put("/api/comment", exchange -> reply(exchange, 200, bare));
        TextRequest request = comment("t1_c8", text);
        sync.event = new CountDownLatch(1);
        check(RedditImageUpload.sendDirect(null, TOKEN, request, request.getUrl()), "comment taken over");
        check(sync.event.await(10, TimeUnit.SECONDS), "converted");
        // Sync sends it on; run the comment request itself.
        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        TextRequest send = new TextRequest(request.getUrl(), request.params()) {
            @Override protected Response<String> parseNetworkResponse(NetworkResponse response) {
                NetworkResponse adapted = RedditImageUpload.commentResponse(request, response);
                try {
                    delivered.set(syncComment(adapted));
                } catch (Exception e) {
                    delivered.set("parse failed: " + e);
                }
                sent.countDown();
                return Response.success("", null);
            }
        };
        sync.queue(null, send);
        check(sent.await(10, TimeUnit.SECONDS), "comment sent");
        Seen posted = REDDIT.get(REDDIT.size() - 1);
        equal("/api/comment", posted.path, "comment endpoint");
        equal("bearer " + TOKEN, posted.headers.get("authorization"), "comment carries Sync's bearer token");
        Map<String, String> form = posted.form();
        check(!form.containsKey("text"), "no Markdown text sent");
        equal("t1_c8", form.get("thing_id"), "parent kept");
        check(form.get("richtext_json").contains("\"id\":\"media2\""), "richtext_json with the image sent");
        equal("Look at this\n\nhttps://preview.redd.it/media2.png", delivered.get(), "Reddit's bare comment reaches Sync's parser");

        // The kind/data shape is wrapped as it is.
        NetworkResponse kinded = new NetworkResponse(200, ("{\"kind\": \"t1\", \"data\": {\"id\": \"c10\", \"body\": \"hi\"}}")
                .getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
        equal("hi", syncComment(RedditImageUpload.commentResponse(request, kinded)), "kind/data reply adapted");
        // Reddit's refusal for a subreddit without comment images.
        NetworkResponse refused = new NetworkResponse(200, ("{\"json\": {\"errors\": [[\"META_MEDIA_TYPE_NOT_ALLOWED\", "
                + "\"media type not allowed\", \"richtext_json\"]]}}").getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
        equal("error: " + RedditImageUpload.MEDIA_NOT_ALLOWED, syncComment(RedditImageUpload.commentResponse(request, refused)),
                "comment media not allowed: a clear message");
        NetworkResponse archived = new NetworkResponse(200, ("{\"json\": {\"errors\": [[\"TOO_OLD\", \"that's a piece of history\", "
                + "\"parent\"]]}}").getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
        check(RedditImageUpload.commentResponse(request, archived) == archived, "other Reddit errors unchanged");
        NetworkResponse wrapped = new NetworkResponse(200, "{\"json\": {\"errors\": [], \"data\": {\"things\": []}}}".getBytes(StandardCharsets.UTF_8),
                false, 0, Collections.emptyList());
        check(RedditImageUpload.commentResponse(request, wrapped) == wrapped, "a wrapped success unchanged");
        NetworkResponse junk = new NetworkResponse(200, "<html>".getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
        check(RedditImageUpload.commentResponse(request, junk) == junk, "malformed reply unchanged");
        // Comments without images are never adapted.
        TextRequest other = comment("t1_x", "no image");
        check(RedditImageUpload.commentResponse(other, refused) == refused, "other comments' replies unchanged");
        check(RedditImageUpload.commentResponse(null, refused) == refused, "null request unchanged");
    }

    // ---- Image posts ----

    static TextRequest linkPost(String url) {
        Map<String, String> params = new HashMap<>(Map.of("kind", "link", "url", url, "title", "A title", "sr", "test", "api_type", "json"));
        return new TextRequest("https://oauth.reddit.com/api/submit?validate_on_submit=True", params);
    }

    static NetworkResponse submitted(String socket) {
        return new NetworkResponse(200, ("{\"json\": {\"errors\": [], \"data\": {\"user_submitted_page\": "
                + "\"https://www.reddit.com/user/someone/submitted/\", \"websocket_url\": \"" + socket + "\"}}}").getBytes(StandardCharsets.UTF_8),
                false, 0, Collections.emptyList());
    }

    /** Sync's OAuthSubmitRequest parser, transcribed: errors[0][1] as the error, else data.id. */
    static String syncSubmit(NetworkResponse response) throws Exception {
        JSONObject json = new JSONObject(new String(response.data, StandardCharsets.UTF_8)).getJSONObject("json");
        if (json.getJSONArray("errors").length() > 0) return "error: " + json.getJSONArray("errors").getJSONArray(0).getString(1);
        return json.getJSONObject("data").getString("id");
    }

    static void script(String... messages) {
        OkHttpClient.SOCKETS.put(SOCKET_HOST, (request, listener) -> {
            SOCKETS.add(request.url().toString());
            for (String message : messages) {
                if (message.equals("fail")) listener.onFailure(null, new IOException("dropped"), null);
                else if (message.equals("close")) listener.onClosed(null, 1000, "");
                else listener.onMessage(null, message);
            }
        });
    }

    private static void imagePosts() throws Exception {
        reset();
        ANSWERS.put("/api/media/asset.json", exchange -> reply(exchange, 200, lease("//" + HOST, "post1", "image/png")));
        uploadAnswer = created("post1");
        String url = upload(PNG).url;
        TextRequest post = linkPost(url);
        RedditImageUpload.submitParams(post, post.params());
        equal("image", post.params().get("kind"), "an uploaded image becomes Reddit's image post");
        equal(url, post.params().get("url"), "with the asset URL");
        TextRequest typed = linkPost("https://" + HOST + "/someone-elses");
        RedditImageUpload.submitParams(typed, typed.params());
        equal("link", typed.params().get("kind"), "a typed link stays a link post");
        Map<String, String> self = new HashMap<>(Map.of("kind", "self", "text", "hi", "url", url));
        RedditImageUpload.submitParams(new Object(), self);
        equal("self", self.get("kind"), "a text post stays a text post");
        RedditImageUpload.submitParams(post, null);
        RedditImageUpload.submitParams(null, new HashMap<>());

        String socket = "wss://" + SOCKET_HOST + "/post1?m=test-only-signature";
        script("{\"type\": \"processing\"}", "{\"type\": \"success\", \"payload\": {\"redirect\": "
                + "\"https://www.reddit.com/r/test/comments/abc123/a_title/\"}}");
        equal("abc123", syncSubmit(RedditImageUpload.submitResponse(post, submitted(socket))), "the post is identified through the websocket");
        equal(List.of("https://" + SOCKET_HOST + "/post1?m=test-only-signature"), SOCKETS, "Reddit's websocket was asked, once");

        Object[][] outcomes = {
                {new String[] {"{\"type\": \"failed\", \"payload\": {}}"}, RedditImageUpload.POST_FAILED},
                {new String[] {"fail"}, RedditImageUpload.POST_UNCONFIRMED},
                {new String[] {"close"}, RedditImageUpload.POST_UNCONFIRMED},
                {new String[] {"{\"type\": \"success\", \"payload\": {\"redirect\": \"https://evil.example/r/x/comments/abc123/\"}}"},
                        RedditImageUpload.POST_UNCONFIRMED},
                {new String[] {"{\"type\": \"success\", \"payload\": {\"redirect\": \"https://www.reddit.com/r/test/\"}}"},
                        RedditImageUpload.POST_UNCONFIRMED},
                {new String[] {"not json", "{\"payload\": {\"redirect\": \"https://reddit.com/r/test/comments/z9/t/\"}}"}, "z9"},
                {new String[] {"{\"payload\": {\"redirect\": \"https://www.reddit.com/user/someone/comments/u1/t/\"}}"}, "u1"},
        };
        for (Object[] outcome : outcomes) {
            script((String[]) outcome[0]);
            String result = syncSubmit(RedditImageUpload.submitResponse(post, submitted(socket)));
            String wanted = ((String) outcome[1]).length() <= 6 ? (String) outcome[1] : "error: " + outcome[1];
            equal(wanted, result, "websocket " + Arrays.toString((String[]) outcome[0]));
        }

        long saved = RedditImageUpload.SOCKET_WAIT_MS;
        try {
            RedditImageUpload.SOCKET_WAIT_MS = 300;
            script();
            long start = System.nanoTime();
            equal("error: " + RedditImageUpload.POST_UNCONFIRMED, syncSubmit(RedditImageUpload.submitResponse(post, submitted(socket))),
                    "a silent websocket: a clear message");
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "the wait is bounded");
        } finally {
            RedditImageUpload.SOCKET_WAIT_MS = saved;
        }

        SOCKETS.clear();
        script("{\"payload\": {\"redirect\": \"https://www.reddit.com/r/test/comments/abc123/t/\"}}");
        for (String bad : new String[] {"ws://" + SOCKET_HOST + "/post1", "wss://evil.example/post1", "wss://wss.redditmedia.com.evil.example/x",
                "https://" + SOCKET_HOST + "/post1", "wss://user@" + SOCKET_HOST + "/x", "wss://" + SOCKET_HOST + ":8443/x", "wss://.wss.redditmedia.com/x"}) {
            equal("error: " + RedditImageUpload.POST_UNCONFIRMED, syncSubmit(RedditImageUpload.submitResponse(post, submitted(bad))),
                    "websocket " + bad + " refused");
        }
        check(SOCKETS.isEmpty(), "refused websockets were never opened");
        NetworkResponse noSocket = new NetworkResponse(200, "{\"json\": {\"errors\": [], \"data\": {}}}".getBytes(StandardCharsets.UTF_8),
                false, 0, Collections.emptyList());
        equal("error: " + RedditImageUpload.POST_UNCONFIRMED, syncSubmit(RedditImageUpload.submitResponse(post, noSocket)), "no websocket named");

        NetworkResponse withId = new NetworkResponse(200, "{\"json\": {\"errors\": [], \"data\": {\"id\": \"direct1\"}}}".getBytes(StandardCharsets.UTF_8),
                false, 0, Collections.emptyList());
        check(RedditImageUpload.submitResponse(post, withId) == withId, "a reply with an ID unchanged");
        NetworkResponse rejected = new NetworkResponse(200, "{\"json\": {\"errors\": [[\"SUBREDDIT_NOTALLOWED\", \"you aren't allowed to post there.\", \"sr\"]]}}"
                .getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
        check(RedditImageUpload.submitResponse(post, rejected) == rejected, "Reddit's own errors unchanged");
        NetworkResponse original = submitted(socket);
        check(RedditImageUpload.submitResponse(typed, original) == original, "other posts' replies unchanged");
        check(RedditImageUpload.submitResponse(null, original) == original, "null request unchanged");
    }

    private static void headers() {
        Map<String, String> given = Map.of("Authorization", "bearer " + TOKEN, "User-Agent", "ua", "Cookie", "c=1");
        equal(given, RedditImageUpload.onlyForReddit("https://oauth.reddit.com/api/media/asset.json", given), "oauth.reddit.com keeps Sync's headers");
        for (String url : new String[] {"http://oauth.reddit.com/api/x", "https://evil.example/api/x", "https://oauth.reddit.com.evil.example/x",
                "https://user@oauth.reddit.com/x", "https://oauth.reddit.com:8443/x", "https://" + HOST + "/"}) {
            equal(Map.of("User-Agent", "ua"), RedditImageUpload.onlyForReddit(url, given), url + ": no Authorization or Cookie");
        }
    }
}
