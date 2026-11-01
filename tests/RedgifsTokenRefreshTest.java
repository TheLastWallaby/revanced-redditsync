package app.revanced.extension.synccompat;

import app.revanced.extension.shared.fixes.redgifs.RedgifsTokenManager;
import app.revanced.extension.syncforreddit.FixRedgifsApiPatch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

/**
 * Drives Sync 23033's RedGifs resolution (OAuthRedgifTokenRequest t8.c, GrabIpRequest t8.a,
 * GrabRedgifRequest t8.b) through the inherited ReVanced interceptor double and the compatibility
 * refresh, as installed by the patched OkHttpHelper, against loopback servers that stand in for
 * api.redgifs.com and an unrelated host. Tokens here are test-only strings.
 */
public final class RedgifsTokenRefreshTest {
    private static final String UA = FixRedgifsApiPatch.userAgent;
    private static int checks;

    // Loopback RedGifs state.
    private static final AtomicInteger tokenHits = new AtomicInteger(), infoHits = new AtomicInteger(),
            gifHits = new AtomicInteger(), clientHits = new AtomicInteger(), otherHits = new AtomicInteger(),
            issued = new AtomicInteger();
    private static final Set<String> accepted = ConcurrentHashMap.newKeySet();
    private static final List<String> issuedTokens = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> tokenRequestAuth = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> tokenRequestAgents = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> otherAuth = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> allOtherAuth = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger activeTokenRequests = new AtomicInteger();
    private static final List<String> apiLog = Collections.synchronizedList(new ArrayList<>());
    private static volatile String tokenMode = "ok";
    private static volatile long tokenDelayMs;
    private static volatile boolean acceptNew = true;
    private static volatile int rejectCode = 401;

    private static final String BAD_CHAR_TOKEN = "rgbad token\u0001secret";
    private static final String LONG_TOKEN = "rglong" + "x".repeat(5000);
    private static final String OVERSIZED_TOKEN = "rgoversized-token";

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
                message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new Tee(out, captured), true));
        System.setErr(new PrintStream(new Tee(err, captured), true));
        HttpServer redgifs = server(RedgifsTokenRefreshTest::redgifs);
        HttpServer other = server(RedgifsTokenRefreshTest::other);
        OkHttpClient.ROUTES.put("api.redgifs.com", "http://127.0.0.1:" + redgifs.getAddress().getPort());
        OkHttpClient.ROUTES.put("example.org", "http://127.0.0.1:" + other.getAddress().getPort());
        try {
            bearerTokenRules();
            installOrder();
            baselineUsesInheritedToken();
            rejectedTokenRefreshesOnce(401);
            rejectedTokenRefreshesOnce(403);
            secondRejectionSurfacesWithoutLoop();
            concurrentRejectionsShareOneRefresh();
            failedRefreshBacksOff();
            concurrentFailedRefreshRunsOnce();
            slowRefreshIsBounded();
            for (String mode : new String[]{"oversized", "oversizedChunked", "malformed", "missing", "badChars",
                    "tooLong", "empty", "notString", "status500", "status401", "redirect"}) {
                rejectedTokenResponse(mode);
            }
            otherRequestsUntouched();
            tokensStayOnRedgifs();
        } finally {
            redgifs.stop(0);
            other.stop(0);
            System.setOut(out);
            System.setErr(err);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);
        List<String> secrets = new ArrayList<>(issuedTokens);
        secrets.add(BAD_CHAR_TOKEN);
        secrets.add(LONG_TOKEN);
        secrets.add(OVERSIZED_TOKEN);
        for (String token : secrets) check(!logs.contains(token), "token text appeared in stdout/stderr");
        noLoggingReferences();
        System.out.println("PASS: " + checks + " RedGifs token refresh checks (loopback RedGifs; test-only tokens)");
    }

    // ---- scenarios ----

    private static void bearerTokenRules() {
        equal("abc.DEF-123_~+/=", RedgifsTokenRefresh.bearerToken(get("https://api.redgifs.com/v2/gifs/x", "Bearer abc.DEF-123_~+/=")),
                "Bearer token read");
        equal("tok", RedgifsTokenRefresh.bearerToken(get("https://api.redgifs.com/info", "bearer tok")), "Sync's lower-case scheme");
        equal("tok", RedgifsTokenRefresh.bearerToken(get("https://API.RedGifs.com/info", "BEARER tok")), "host and scheme case");
        for (String[] row : new String[][]{
                {"http://api.redgifs.com/v2/gifs/x", "Bearer tok"},
                {"https://redgifs.com/v2/gifs/x", "Bearer tok"},
                {"https://api.redgifs.com.example.org/v2/gifs/x", "Bearer tok"},
                {"https://example.org/v2/gifs/x", "Bearer tok"},
                {"https://api.redgifs.com/v2/auth/temporary", "Bearer tok"},
                {"https://api.redgifs.com/v2/oauth/client", "Bearer tok"},
                {"https://api.redgifs.com/v2/gifs/x", null},
                {"https://api.redgifs.com/v2/gifs/x", "Basic dXNlcjpwYXNz"},
                {"https://api.redgifs.com/v2/gifs/x", "Bearer "},
                {"https://api.redgifs.com/v2/gifs/x", "Bearer a b"},
                {"https://api.redgifs.com/v2/gifs/x", "Bearer " + LONG_TOKEN},
                {"https://api.redgifs.com/v2/gifs/x", "Bearer tok\u0000"}}) {
            equal(null, RedgifsTokenRefresh.bearerToken(get(row[0], row[1])), "not retried: " + row[0] + " " + (row[1] == null ? "" : row[1].length()));
        }
        Request post = new Request.Builder().url("https://api.redgifs.com/v2/gifs/x").header("Authorization", "Bearer tok")
                .method("POST", RequestBody.create("{}", MediaType.get("application/json"))).build();
        equal(null, RedgifsTokenRefresh.bearerToken(post), "POST not retried");
    }

    private static void installOrder() throws InterruptedException {
        reset();
        OkHttpClient sync = install();
        equal(2, sync.interceptors().size(), "two RedGifs interceptors installed");
        check(sync.interceptors().get(0) == FixRedgifsApiPatch.instance(), "inherited interceptor stays outermost");
        check(sync.interceptors().get(1) == RedgifsTokenRefresh.INSTANCE, "refresh runs next to the network");
        check(sync.followRedirects(), "Sync's client still follows redirects");
        equal(10_000, sync.readTimeoutMillis(), "Sync's own timeouts kept");
        OkHttpClient tokens = RedgifsTokenRefresh.tokenClient();
        check(tokens != null && tokens.interceptors().isEmpty(), "token client has no interceptors");
        check(!tokens.followRedirects() && !tokens.followSslRedirects(), "token client follows no redirects");
        equal((int) RedgifsTokenRefresh.TOKEN_CALL_TIMEOUT_MS, tokens.callTimeoutMillis(), "token call bounded");
        equal((int) RedgifsTokenRefresh.TOKEN_CONNECT_TIMEOUT_MS, tokens.connectTimeoutMillis(), "token connect bounded");
        equal((int) RedgifsTokenRefresh.TOKEN_READ_TIMEOUT_MS, tokens.readTimeoutMillis(), "token read bounded");
    }

    private static void baselineUsesInheritedToken() throws Exception {
        reset();
        OkHttpClient sync = install();
        equal("https://media.redgifs.com/FirstClip.mp4", resolve(sync, "firstclip"), "first resolution plays");
        equal(1, tokenHits.get(), "inherited fetched one token");
        equal(1, RedgifsTokenManager.inheritedFetches.get(), "fetched by the inherited cache");
        equal(0, clientHits.get(), "/v2/oauth/client answered locally");
        equal(List.of("GET /info Bearer " + issuedAt(0), "GET /v2/gifs/firstclip bearer " + issuedAt(0)),
                apiLog, "no retries while the token is accepted");
        equal("https://media.redgifs.com/FirstClip.mp4", resolve(sync, "firstclip"), "second resolution plays");
        equal(1, tokenHits.get(), "cached token reused");
    }

    private static void rejectedTokenRefreshesOnce(int code) throws Exception {
        reset();
        rejectCode = code;
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll(); // e.g. Wi-Fi to mobile data: RedGifs no longer accepts the cached token
        equal("https://media.redgifs.com/OtherClip.mp4", resolve(sync, "otherclip"), "HTTP " + code + ": resolution recovers");
        String fresh = issuedAt(1);
        equal(1, tokenHits.get(), "HTTP " + code + ": exactly one refresh");
        equal(0, RedgifsTokenManager.inheritedFetches.get(), "HTTP " + code + ": refresh is the bounded one");
        equal(List.of("GET /info Bearer " + old + " -> " + code, "GET /info Bearer " + fresh,
                "GET /v2/gifs/otherclip bearer " + old + " -> " + code, "GET /v2/gifs/otherclip Bearer " + fresh),
                apiLog, "HTTP " + code + ": each rejected request retried once with the new token");
        equal(fresh, RedgifsTokenManager.cached(UA), "HTTP " + code + ": inherited cache now holds the new token");
        equal(List.of(UA), tokenRequestAgents, "HTTP " + code + ": refresh sent Sync's User-Agent");
        apiLog.clear();
        equal("https://media.redgifs.com/ThirdClip.mp4", resolve(sync, "thirdclip"), "HTTP " + code + ": next resolution plays");
        equal(1, tokenHits.get(), "HTTP " + code + ": no further refresh");
        equal(List.of("GET /info Bearer " + fresh, "GET /v2/gifs/thirdclip bearer " + fresh), apiLog,
                "HTTP " + code + ": Sync now starts with the new token");
    }

    private static void secondRejectionSurfacesWithoutLoop() throws Exception {
        reset();
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        acceptNew = false; // RedGifs rejects every token
        long start = System.nanoTime();
        equal("ERROR 401", resolve(sync, "rejected"), "second rejection reaches Sync's error");
        equal(1, tokenHits.get(), "one refresh only");
        String fresh = issuedAt(1);
        equal(List.of("GET /info Bearer " + old + " -> 401", "GET /info Bearer " + fresh + " -> 401"), apiLog,
                "GrabIpRequest retried once");
        apiLog.clear();
        // A GrabRedgifRequest still carrying the old token: one retry with the new token, then the
        // inherited interceptor's own second attempt, which does not refresh again.
        equal(401, gif(sync, "rejected", "bearer " + old).code, "GrabRedgifRequest error surfaces");
        equal(List.of("GET /v2/gifs/rejected bearer " + old + " -> 401", "GET /v2/gifs/rejected Bearer " + fresh + " -> 401",
                "GET /v2/gifs/rejected Bearer " + fresh + " -> 401"), apiLog, "bounded attempts, no loop");
        equal(1, tokenHits.get(), "no refresh while the new token is recent");
        for (int i = 0; i < 5; i++) equal("ERROR 401", resolve(sync, "again" + i), "repeated failure " + i);
        equal(1, tokenHits.get(), "repeated failures do not refresh again");
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "failures finish promptly");
    }

    private static void concurrentRejectionsShareOneRefresh() throws Exception {
        for (boolean direct : new boolean[]{false, true}) {
            reset();
            OkHttpClient sync = install();
            String old = seed(sync);
            revokeAll();
            tokenDelayMs = 300; // keep the refresh in flight while every request is rejected
            int n = 8;
            CyclicBarrier barrier = new CyclicBarrier(n);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = "clip" + i;
                results.add(pool.submit(() -> {
                    barrier.await();
                    return direct ? gif(sync, id, "bearer " + old).body : resolve(sync, id);
                }));
            }
            for (int i = 0; i < n; i++) {
                String body = results.get(i).get(20, TimeUnit.SECONDS);
                check(body.contains("media.redgifs.com/Clip" + i + ".mp4"), (direct ? "direct " : "") + "concurrent request " + i + " recovers: " + body);
            }
            pool.shutdown();
            equal(1, tokenHits.get(), (direct ? "GrabRedgifRequest" : "full resolution") + ": " + n + " concurrent rejections, one refresh");
            equal(issuedAt(1), RedgifsTokenManager.cached(UA), "shared token cached");
        }
    }

    private static void failedRefreshBacksOff() throws Exception {
        reset();
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        tokenMode = "status500";
        equal("ERROR 401", resolve(sync, "down"), "failed refresh returns Sync's error");
        equal(1, tokenHits.get(), "one refresh attempt");
        equal(old, RedgifsTokenManager.cached(UA), "inherited cache untouched by a failed refresh");
        for (int i = 0; i < 5; i++) equal("ERROR 401", resolve(sync, "down" + i), "backoff " + i);
        equal(1, tokenHits.get(), "no refresh storm during backoff");
        RedgifsTokenRefresh.FAILURE_BACKOFF_MS = 200;
        Thread.sleep(300);
        tokenMode = "ok";
        equal("https://media.redgifs.com/Back.mp4", resolve(sync, "back"), "after backoff a refresh recovers");
        equal(2, tokenHits.get(), "one more refresh after backoff");
    }

    private static void concurrentFailedRefreshRunsOnce() throws Exception {
        reset();
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        tokenMode = "status500";
        tokenDelayMs = 300;
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "fail" + i;
            results.add(pool.submit(() -> { barrier.await(); return gif(sync, id, "bearer " + old).code; }));
        }
        for (Future<Integer> result : results) equal(401, result.get(20, TimeUnit.SECONDS), "concurrent failure surfaces");
        pool.shutdown();
        equal(1, tokenHits.get(), "concurrent failures share one refresh attempt");
    }

    private static void slowRefreshIsBounded() throws Exception {
        reset();
        RedgifsTokenRefresh.TOKEN_CALL_TIMEOUT_MS = 500;
        RedgifsTokenRefresh.REFRESH_WAIT_MS = 700;
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        tokenDelayMs = 3_000;
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<long[]>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "slow" + i;
            results.add(pool.submit(() -> {
                barrier.await();
                long start = System.nanoTime();
                int code = gif(sync, id, "bearer " + old).code;
                return new long[]{code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)};
            }));
        }
        for (Future<long[]> result : results) {
            long[] value = result.get(20, TimeUnit.SECONDS);
            equal(401L, value[0], "timed-out refresh surfaces the rejection");
            check(value[1] < 2_000, "request bounded by the refresh limits, took " + value[1] + " ms");
        }
        pool.shutdown();
        equal(1, tokenHits.get(), "one timed-out refresh");
        equal(old, RedgifsTokenManager.cached(UA), "timed-out refresh leaves the cache");
        long start = System.nanoTime();
        equal("ERROR 401", resolve(sync, "slowagain"), "backoff after a timeout");
        check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1_500), "no second slow refresh");
        equal(1, tokenHits.get(), "no refresh during backoff");
    }

    private static void rejectedTokenResponse(String mode) throws Exception {
        reset();
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        tokenMode = mode;
        equal("ERROR 401", resolve(sync, "bad"), mode + ": rejected token response surfaces Sync's error");
        equal(1, tokenHits.get(), mode + ": one refresh attempt");
        equal(old, RedgifsTokenManager.cached(UA), mode + ": cache keeps the previous token");
        equal(List.of("GET /info Bearer " + old + " -> 401"), apiLog, mode + ": nothing retried with an unusable token");
        equal(0, otherHits.get(), mode + ": no request left RedGifs");
    }

    private static void otherRequestsUntouched() throws Exception {
        reset();
        OkHttpClient sync = install();
        String old = seed(sync);
        revokeAll();
        int inherited = RedgifsTokenManager.inheritedFetches.get();

        Response other = sync.newCall(get("https://example.org/api/me", "Bearer reddit-test-token")).execute();
        equal(401, other.code(), "other host's 401 returned");
        other.close();
        equal(1, otherHits.get(), "other host requested once");
        equal(List.of("Bearer reddit-test-token"), otherAuth, "other host's Authorization unchanged");

        equal(401, call(sync, get("http://api.redgifs.com/v2/gifs/plain", "Bearer " + old)).code, "plain HTTP not refreshed");
        Request post = new Request.Builder().url("https://api.redgifs.com/v2/gifs/posted").header("Authorization", "Bearer " + old)
                .method("POST", RequestBody.create("{}", MediaType.get("application/json"))).build();
        equal(401, call(sync, post).code, "POST not refreshed");
        accepted.add(old);
        equal(404, call(sync, get("https://api.redgifs.com/v2/gifs/missing", "bearer " + old)).code, "404 not refreshed");
        equal(500, call(sync, get("https://api.redgifs.com/v2/gifs/broken", "bearer " + old)).code, "500 not refreshed");
        equal(0, tokenHits.get(), "no refresh for other hosts, schemes, methods or statuses");
        equal(inherited, RedgifsTokenManager.inheritedFetches.get(), "inherited cache untouched");
    }

    private static void tokensStayOnRedgifs() {
        for (String auth : tokenRequestAuth) equal(null, auth, "token request carried no Authorization");
        check(!tokenRequestAuth.isEmpty(), "token requests were observed");
        for (String auth : allOtherAuth) {
            for (String token : issuedTokens) check(auth == null || !auth.contains(token), "RedGifs token sent to another host");
        }
    }

    private static void noLoggingReferences() throws IOException {
        for (String name : new String[]{"RedgifsTokenRefresh", "RedgifsTokenRefresh$Slot", "RedgifsTokenRefresh$Flight"}) {
            byte[] bytes;
            try (InputStream in = RedgifsTokenRefresh.class.getResourceAsStream(name + ".class")) {
                check(in != null, "class file " + name);
                bytes = in.readAllBytes();
            }
            String pool = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[]{"android/util/Log", "app/revanced/extension/shared/Logger",
                    "printStackTrace", "java/io/PrintStream", "java/lang/System\u0001out"}) {
                check(!pool.contains(forbidden), name + " must not log (" + forbidden + ")");
            }
        }
    }

    // ---- Sync's requests ----

    /** Sync's chain from m8.a.e: t8.c POST /v2/oauth/client, t8.a GET /info, t8.b GET /v2/gifs. */
    private static String resolve(OkHttpClient sync, String id) throws Exception {
        Request client = new Request.Builder().url("https://api.redgifs.com/v2/oauth/client")
                .header("content-type", "application/x-www-form-urlencoded")
                .method("POST", RequestBody.create("grant_type=client_credentials&client_id=test", MediaType.get("text/plain"))).build();
        Result token = call(sync, client);
        if (token.code != 200) return "ERROR " + token.code;
        String access = new JSONObject(token.body).getString("access_token");
        Result info = call(sync, new Request.Builder().url("https://api.redgifs.com/info").build());
        if (info.code != 200) return "ERROR " + info.code;
        String addr = new JSONObject(info.body).getString("remote-addr");
        Result gif = gif(sync, id, "bearer " + access, addr);
        if (gif.code != 200) return "ERROR " + gif.code;
        return new JSONObject(gif.body).getJSONObject("gif").getJSONObject("urls").getString("hd");
    }

    private static Result gif(OkHttpClient sync, String id, String authorization) {
        return gif(sync, id, authorization, "192.0.2.7");
    }

    private static Result gif(OkHttpClient sync, String id, String authorization, String addr) {
        Request request = new Request.Builder().url("https://api.redgifs.com/v2/gifs/" + id + "?user-addr=" + addr + "&user-agent=sync")
                .header("User-Agent", UA).header("Authorization", authorization).build();
        return call(sync, request);
    }

    private static Request get(String url, String authorization) {
        Request.Builder builder = new Request.Builder().url(url);
        if (authorization != null) builder.header("Authorization", authorization);
        return builder.build();
    }

    private static Result call(OkHttpClient sync, Request request) {
        try (Response response = sync.newCall(request).execute()) {
            return new Result(response.code(), response.body() == null ? "" : response.body().string());
        } catch (IOException e) {
            return new Result(-1, e.getClass().getSimpleName());
        }
    }

    private static final class Result {
        final int code;
        final String body;
        Result(int code, String body) { this.code = code; this.body = body; }
    }

    // ---- fixtures ----

    /** The patched OkHttpHelper.k(): Sync's builder settings, then the replaced install call. */
    private static OkHttpClient install() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .followSslRedirects(true).followRedirects(true);
        return RedgifsTokenRefresh.install(builder);
    }

    /** A first resolution so the inherited cache holds an accepted token; returns it. */
    private static String seed(OkHttpClient sync) throws Exception {
        equal("https://media.redgifs.com/Seed.mp4", resolve(sync, "seed"), "seed resolution");
        String token = RedgifsTokenManager.cached(UA);
        check(token != null && accepted.contains(token), "seed token cached");
        tokenHits.set(0);
        tokenRequestAgents.clear();
        RedgifsTokenManager.inheritedFetches.set(0);
        apiLog.clear();
        return token;
    }

    private static void revokeAll() {
        accepted.clear();
    }

    private static void reset() throws InterruptedException {
        // A deliberately slow token response from the previous scenario must not issue tokens here.
        for (int i = 0; i < 100 && activeTokenRequests.get() > 0; i++) Thread.sleep(50);
        check(activeTokenRequests.get() == 0, "previous scenario's token requests finished");
        RedgifsTokenRefresh.reset();
        RedgifsTokenManager.clear();
        RedgifsTokenRefresh.TOKEN_CONNECT_TIMEOUT_MS = 1_000;
        RedgifsTokenRefresh.TOKEN_READ_TIMEOUT_MS = 1_000;
        RedgifsTokenRefresh.TOKEN_CALL_TIMEOUT_MS = 2_000;
        RedgifsTokenRefresh.REFRESH_WAIT_MS = 3_000;
        RedgifsTokenRefresh.MIN_REFRESH_INTERVAL_MS = 30_000;
        RedgifsTokenRefresh.FAILURE_BACKOFF_MS = 30_000;
        for (AtomicInteger counter : new AtomicInteger[]{tokenHits, infoHits, gifHits, clientHits, otherHits}) counter.set(0);
        accepted.clear();
        apiLog.clear();
        otherAuth.clear();
        tokenRequestAgents.clear();
        int before = issuedTokens.size();
        check(before == issued.get(), "issued token bookkeeping");
        tokenMode = "ok";
        tokenDelayMs = 0;
        acceptNew = true;
        rejectCode = 401;
        // issuedTokens keeps every token for the final log scan; scenarios index from here.
        scenarioStart = before;
    }

    private static int scenarioStart;

    /** The k-th token issued since the current scenario's reset. */
    private static String issuedAt(int k) {
        return issuedTokens.get(scenarioStart + k);
    }

    // ---- loopback servers ----

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception ignored) {
                // A client that timed out may already have closed the connection.
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "loopback-redgifs");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    private static void redgifs(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (path.equals("/v2/auth/temporary")) {
            tokenHits.incrementAndGet();
            tokenRequestAuth.add(auth);
            tokenRequestAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            String mode = tokenMode;
            long delay = tokenDelayMs;
            activeTokenRequests.incrementAndGet();
            try {
                if (delay > 0) Thread.sleep(delay);
                token(exchange, mode);
            } finally {
                activeTokenRequests.decrementAndGet();
            }
            return;
        }
        if (path.equals("/v2/oauth/client")) {
            clientHits.incrementAndGet();
            send(exchange, 500, "{}");
            return;
        }
        String token = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7) : null;
        boolean ok = token != null && accepted.contains(token);
        String status = ok ? "" : " -> " + rejectCode;
        if (path.equals("/info")) {
            infoHits.incrementAndGet();
            log(method + " /info " + auth + status);
            if (ok) send(exchange, 200, "{\"remote-addr\":\"192.0.2.7\"}");
            else send(exchange, rejectCode, "{\"error\":{\"code\":\"TokenExpired\"}}");
            return;
        }
        if (path.startsWith("/v2/gifs/")) {
            gifHits.incrementAndGet();
            String id = path.substring("/v2/gifs/".length());
            if (ok && id.equals("missing")) { log(method + " " + path + " " + auth + " -> 404"); send(exchange, 404, "{}"); return; }
            if (ok && id.equals("broken")) { log(method + " " + path + " " + auth + " -> 500"); send(exchange, 500, "{}"); return; }
            log(method + " " + path + " " + auth + status);
            if (!ok) { send(exchange, rejectCode, "{\"error\":{\"code\":\"InvalidToken\"}}"); return; }
            String name = Character.toUpperCase(id.charAt(0)) + id.substring(1);
            if (id.equals("firstclip")) name = "FirstClip";
            if (id.equals("otherclip")) name = "OtherClip";
            if (id.equals("thirdclip")) name = "ThirdClip";
            send(exchange, 200, "{\"gif\":{\"urls\":{\"hd\":\"https://media.redgifs.com/" + name + ".mp4\","
                    + "\"sd\":\"https://media.redgifs.com/" + name + "-mobile.mp4\"}}}");
            return;
        }
        send(exchange, 404, "{}");
    }

    private static void token(HttpExchange exchange, String mode) throws IOException {
        switch (mode) {
            case "ok": {
                String token = "rgtest-" + issued.incrementAndGet() + ".Ab9_x~y";
                issuedTokens.add(token);
                if (acceptNew) accepted.add(token);
                send(exchange, 200, "{\"token\":\"" + token + "\",\"addr\":\"192.0.2.7\",\"agent\":\"test\",\"session\":\"s\"}");
                return;
            }
            case "oversized":
                send(exchange, 200, "{\"token\":\"" + OVERSIZED_TOKEN + "\",\"pad\":\"" + "p".repeat(32 * 1024) + "\"}");
                return;
            case "oversizedChunked": {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(("{\"token\":\"" + OVERSIZED_TOKEN + "\",\"pad\":\"").getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < 32; i++) out.write("p".repeat(1024).getBytes(StandardCharsets.UTF_8));
                    out.write("\"}".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            case "malformed": send(exchange, 200, "{\"token\": "); return;
            case "missing": send(exchange, 200, "{\"session\":\"s\"}"); return;
            case "badChars": send(exchange, 200, new JSONObject().put("token", BAD_CHAR_TOKEN).toString()); return;
            case "tooLong": send(exchange, 200, "{\"token\":\"" + LONG_TOKEN + "\"}"); return;
            case "empty": send(exchange, 200, "{\"token\":\"\"}"); return;
            case "notString": send(exchange, 200, "{\"token\":12345}"); return;
            case "status500": send(exchange, 500, "{\"token\":\"rgserver-error-token\"}"); return;
            case "status401": send(exchange, 401, "{}"); return;
            case "redirect":
                exchange.getResponseHeaders().set("Location", "https://example.org/v2/auth/temporary");
                send(exchange, 302, "");
                return;
            default: throw new IllegalStateException(mode);
        }
    }

    private static void other(HttpExchange exchange) throws IOException {
        otherHits.incrementAndGet();
        otherAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        allOtherAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        send(exchange, 401, "{}");
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static void log(String line) {
        apiLog.add(line);
    }

    /** Copies test output to the console while keeping it for the token scan. */
    private static final class Tee extends OutputStream {
        private final OutputStream first, second;
        Tee(OutputStream first, OutputStream second) { this.first = first; this.second = second; }
        @Override public synchronized void write(int b) throws IOException { first.write(b); second.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }
        @Override public synchronized void flush() throws IOException { first.flush(); second.flush(); }
    }
}
