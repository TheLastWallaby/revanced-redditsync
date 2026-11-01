package app.revanced.extension.synccompat;

import app.revanced.extension.shared.fixes.redgifs.RedgifsTokenManager;
import app.revanced.extension.syncforreddit.FixRedgifsApiPatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONObject;

/**
 * Refreshes the inherited RedGifs temporary token once RedGifs rejects it.
 *
 * <p>The prepared base's {@code FixRedgifsApiPatch} interceptor answers Sync's
 * {@code /v2/oauth/client} request with a token from {@code /v2/auth/temporary} and keeps it in
 * {@code RedgifsTokenManager.tokenMap} for 23 hours. When RedGifs stops accepting that token (for
 * example after the network address changes), its own retry sends the same cached token again and
 * Sync shows "Error connecting to Redgifs" until the process restarts.
 *
 * <p>This interceptor runs inside the inherited one, next to the network. For a GET to
 * {@code https://api.redgifs.com} carrying a bearer token that RedGifs answers with HTTP 401 or 403:
 * <ol>
 *   <li>one bounded {@code /v2/auth/temporary} request fetches a new token. Concurrent rejections
 *       share that single request and its result;</li>
 *   <li>the new token replaces the inherited cache entry, so Sync's next resolution starts with it;</li>
 *   <li>the rejected request is sent once more with the new token. Whatever RedGifs answers then is
 *       returned, so a second rejection surfaces Sync's normal error.</li>
 * </ol>
 * A token that was itself just fetched is not refreshed again for {@link #MIN_REFRESH_INTERVAL_MS},
 * and a failed refresh is not retried for {@link #FAILURE_BACKOFF_MS}. Other hosts, plain HTTP,
 * other methods and the token endpoints pass through untouched. Tokens are never logged, kept in
 * diagnostics or sent anywhere but {@code api.redgifs.com}; nothing here writes a log.
 */
public final class RedgifsTokenRefresh implements Interceptor {
    static final String HOST = "api.redgifs.com";
    static final String TOKEN_PATH = "/v2/auth/temporary";
    static final String CLIENT_PATH = "/v2/oauth/client";
    private static final String TOKEN_URL = "https://" + HOST + TOKEN_PATH;
    private static final String TOKEN_MAP = "tokenMap";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]+");

    // Limits are non-final so tests can shorten them (javac would inline static final primitives).
    /** Connect and per-read limits for the token request. */
    static long TOKEN_CONNECT_TIMEOUT_MS = 5_000;
    static long TOKEN_READ_TIMEOUT_MS = 5_000;
    /** Whole token request, including connecting and reading the body. */
    static long TOKEN_CALL_TIMEOUT_MS = 8_000;
    /** How long a rejected request waits for a refresh another request started. */
    static long REFRESH_WAIT_MS = 10_000;
    /** A token fetched this recently is not refreshed again when RedGifs rejects it too. */
    static long MIN_REFRESH_INTERVAL_MS = 30_000;
    /** After a failed refresh, rejections return Sync's error without another refresh this long. */
    static long FAILURE_BACKOFF_MS = 30_000;
    /** A refreshed token stays usable for requests still carrying the rejected one this long. */
    static long REUSE_MS = 10 * 60_000;
    static int MAX_TOKEN_RESPONSE_BYTES = 16 * 1024;
    static int MAX_TOKEN_LENGTH = 4096;

    static final RedgifsTokenRefresh INSTANCE = new RedgifsTokenRefresh();

    /** Per User-Agent refresh state (the inherited cache is keyed the same way). */
    private static final Map<String, Slot> SLOTS = new HashMap<>();
    private static final int MAX_SLOTS = 8;
    private static volatile OkHttpClient tokenClient;

    private RedgifsTokenRefresh() { }

    /**
     * Replaces {@code FixRedgifsApiPatch.install(builder)} in Sync's OkHttpHelper. The inherited
     * interceptor is installed first; this one is added after it, nearer the network.
     */
    public static OkHttpClient install(OkHttpClient.Builder builder) {
        OkHttpClient inherited = FixRedgifsApiPatch.install(builder);
        try {
            tokenClient = tokenClientFrom(inherited);
            return inherited.newBuilder().addInterceptor(INSTANCE).build();
        } catch (RuntimeException | LinkageError unavailable) {
            // Keep Sync's client exactly as the base built it.
            tokenClient = null;
            return inherited;
        }
    }

    /** Sync's client without interceptors or redirects, with a bound on the whole call. */
    static OkHttpClient tokenClientFrom(OkHttpClient client) {
        OkHttpClient.Builder builder = client.newBuilder();
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        return builder.followRedirects(false).followSslRedirects(false)
                .connectTimeout(TOKEN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TOKEN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TOKEN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TOKEN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String rejected = bearerToken(request);
        if (rejected == null) return chain.proceed(request);
        Response response = chain.proceed(request);
        if (response.code() != 401 && response.code() != 403) return response;
        String token = tokenAfterRejection(request.header("User-Agent"), rejected);
        if (token == null || token.equals(rejected)) return response;
        // OkHttp needs the rejected response closed before the same call sends again.
        response.close();
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer " + token).build());
    }

    /** The RedGifs bearer token of a request this interceptor may retry, or null. */
    static String bearerToken(Request request) {
        HttpUrl url = request.url();
        if (!url.isHttps() || !HOST.equals(url.host()) || !"GET".equals(request.method())) return null;
        String path = url.encodedPath();
        if (TOKEN_PATH.equals(path) || CLIENT_PATH.equals(path)) return null;
        String header = request.header("Authorization");
        if (header == null || header.length() <= 7
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7);
        return validToken(token) ? token : null;
    }

    static boolean validToken(String token) {
        return token != null && !token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH
                && TOKEN.matcher(token).matches();
    }

    /**
     * The token to retry with after RedGifs rejected {@code rejected}, or null to return the
     * rejection. Starts at most one refresh at a time per User-Agent; others wait for its result.
     */
    static String tokenAfterRejection(String userAgent, String rejected) {
        if (userAgent == null || userAgent.isEmpty()) return null;
        Flight wait = null, mine = null;
        Slot slot;
        synchronized (SLOTS) {
            slot = slot(userAgent);
            long now = now();
            if (slot.token != null && !slot.token.equals(rejected) && now - slot.tokenAt < REUSE_MS) {
                return slot.token;
            }
            if (slot.flight != null) {
                wait = slot.flight;
            } else if (slot.token != null && slot.token.equals(rejected)
                    && now - slot.tokenAt < MIN_REFRESH_INTERVAL_MS) {
                return null;
            } else if (slot.failedAt != 0 && now - slot.failedAt < FAILURE_BACKOFF_MS) {
                return null;
            } else {
                mine = slot.flight = new Flight();
            }
        }
        if (wait != null) {
            try {
                if (!wait.done.await(REFRESH_WAIT_MS, TimeUnit.MILLISECONDS)) return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            return wait.token;
        }
        String token = null;
        try {
            token = fetch(userAgent);
        } catch (Exception | LinkageError failed) {
            // Status, timeouts and parse failures are all a failed refresh; details may hold body text.
        }
        synchronized (SLOTS) {
            slot.flight = null;
            if (token != null) {
                slot.token = token;
                slot.tokenAt = now();
                slot.failedAt = 0;
            } else {
                slot.failedAt = Math.max(1, now());
            }
        }
        mine.token = token;
        mine.done.countDown();
        if (token != null) remember(userAgent, token);
        return token;
    }

    /** One bounded request for a new temporary token, sent only to api.redgifs.com over HTTPS. */
    private static String fetch(String userAgent) throws IOException {
        OkHttpClient client = tokenClient;
        if (client == null) return null;
        Request request = new Request.Builder().url(TOKEN_URL).get()
                .header("User-Agent", userAgent).header("Accept", "application/json").build();
        Response response = client.newCall(request).execute();
        try {
            HttpUrl url = response.request().url();
            if (response.code() != 200 || !url.isHttps() || !HOST.equals(url.host())
                    || !TOKEN_PATH.equals(url.encodedPath())) return null;
            ResponseBody body = response.body();
            if (body == null) return null;
            String text = read(body);
            if (text == null) return null;
            Object token = new JSONObject(text).opt("token");
            return token instanceof String && validToken((String) token) ? (String) token : null;
        } catch (org.json.JSONException malformed) {
            return null;
        } finally {
            response.close();
        }
    }

    /** The body as UTF-8, or null when it is larger than {@link #MAX_TOKEN_RESPONSE_BYTES}. */
    private static String read(ResponseBody body) throws IOException {
        long declared = body.contentLength();
        if (declared > MAX_TOKEN_RESPONSE_BYTES) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream in = body.byteStream()) {
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > MAX_TOKEN_RESPONSE_BYTES) return null;
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Replaces the inherited cache entry so Sync's next /v2/oauth/client answer uses the new token. */
    @SuppressWarnings("unchecked")
    private static void remember(String userAgent, String token) {
        try {
            Field field = RedgifsTokenManager.class.getDeclaredField(TOKEN_MAP);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Map.class) return;
            field.setAccessible(true);
            Map<String, Object> tokens = (Map<String, Object>) field.get(null);
            if (tokens == null) return;
            Object entry = new RedgifsTokenManager.RedgifsToken(token, System.currentTimeMillis() / 1000L);
            synchronized (tokens) {
                tokens.put(userAgent, entry);
            }
        } catch (Exception | LinkageError unavailable) {
            // Retries still use the refreshed token; only the inherited cache keeps the old one.
        }
    }

    private static Slot slot(String userAgent) {
        Slot slot = SLOTS.get(userAgent);
        if (slot == null) {
            if (SLOTS.size() >= MAX_SLOTS) {
                // Collection.removeIf needs API 24; Sync supports older releases.
                for (Iterator<Slot> it = SLOTS.values().iterator(); it.hasNext(); ) {
                    if (it.next().flight == null) it.remove();
                }
            }
            slot = new Slot();
            SLOTS.put(userAgent, slot);
        }
        return slot;
    }

    private static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    /** Test hook: forget refresh state between scenarios. */
    static void reset() {
        synchronized (SLOTS) {
            SLOTS.clear();
        }
    }

    /** Test hook: the token client built at install time. */
    static OkHttpClient tokenClient() {
        return tokenClient;
    }

    @Override
    public String toString() {
        return "RedgifsTokenRefresh";
    }

    private static final class Slot {
        String token;
        long tokenAt;
        long failedAt;
        Flight flight;

        @Override
        public String toString() {
            // Never expose the token through debugging output.
            return String.format(Locale.ROOT, "Slot{token=%s, refreshing=%s}",
                    token == null ? "none" : "set", flight != null);
        }
    }

    private static final class Flight {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String token;
    }
}
