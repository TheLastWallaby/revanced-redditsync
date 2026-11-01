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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONObject;

/**
 * Keeps Sync 23033's RedGifs lookups working with the current RedGifs API.
 *
 * <p>Sync resolves a RedGifs post with three requests: {@code POST /v2/oauth/client} (answered
 * locally by the prepared base's inherited {@code FixRedgifsApiPatch} from a cached
 * {@code /v2/auth/temporary} token), {@code GET /info} for the client address
 * ({@code remote-addr}), then {@code GET /v2/gifs/<id>} with that token. This interceptor runs
 * inside the inherited one, next to the network, and fixes two failures:
 * <ol>
 *   <li><b>{@code /info} is retired</b> (RedGifs answers HTTP 404). Sync's GET
 *       {@code https://api.redgifs.com/info} is answered locally with {@code {"remote-addr": addr}},
 *       where {@code addr} is the address RedGifs reports in a {@code /v2/auth/temporary} response.
 *       That response's token also replaces the inherited cache entry. The address is reused for
 *       {@link #ADDRESS_REUSE_MS}. If no address can be obtained the request goes to RedGifs as
 *       before and Sync shows its usual error.</li>
 *   <li><b>Rejected tokens.</b> The inherited cache keeps a token for 23 hours and its own retry
 *       resends the same token. For a GET to {@code https://api.redgifs.com} carrying a bearer
 *       token that RedGifs answers with HTTP 401 or 403, one bounded token request fetches a new
 *       token (concurrent rejections share it), the inherited cache entry is replaced and the
 *       request is sent once more. If RedGifs rejects that token too, it is marked bad and no new
 *       token is fetched for {@link #FAILURE_BACKOFF_MS}, so the inherited interceptor's own resend
 *       and later lookups surface Sync's normal error instead of looping.</li>
 * </ol>
 * Other hosts, plain HTTP, other methods and statuses, and the token endpoints pass through
 * untouched. Tokens and addresses are never logged, kept in diagnostics or sent anywhere but
 * {@code api.redgifs.com} (the address goes only to Sync, which sends it to RedGifs as before).
 */
public final class RedgifsTokenRefresh implements Interceptor {
    static final String HOST = "api.redgifs.com";
    static final String TOKEN_PATH = "/v2/auth/temporary";
    static final String CLIENT_PATH = "/v2/oauth/client";
    static final String INFO_PATH = "/info";
    private static final String TOKEN_URL = "https://" + HOST + TOKEN_PATH;
    private static final String TOKEN_MAP = "tokenMap";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]+");
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    // Limits are non-final so tests can shorten them (javac would inline static final primitives).
    /** Connect and per-read limits for the token request. */
    static long TOKEN_CONNECT_TIMEOUT_MS = 5_000;
    static long TOKEN_READ_TIMEOUT_MS = 5_000;
    /** Whole token request, including connecting and reading the body. */
    static long TOKEN_CALL_TIMEOUT_MS = 8_000;
    /** How long a request waits for a token request another request started. */
    static long REFRESH_WAIT_MS = 10_000;
    /** After a failed token request, or a rejected new token, no token request starts this long. */
    static long FAILURE_BACKOFF_MS = 30_000;
    /** A fetched token stays usable for requests still carrying an older one this long. */
    static long REUSE_MS = 10 * 60_000;
    /** How long an address from a token response answers Sync's /info lookups. */
    static long ADDRESS_REUSE_MS = 10 * 60_000;
    static int MAX_TOKEN_RESPONSE_BYTES = 16 * 1024;
    static int MAX_TOKEN_LENGTH = 4096;

    static final RedgifsTokenRefresh INSTANCE = new RedgifsTokenRefresh();

    /** Per User-Agent state (the inherited cache is keyed the same way). */
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
        if (isInfoLookup(request)) {
            String address = addressFor(request.header("User-Agent"));
            return address != null ? infoResponse(request, address) : chain.proceed(request);
        }
        String rejected = bearerToken(request);
        if (rejected == null) return chain.proceed(request);
        Response response = chain.proceed(request);
        if (!isRejection(response)) return response;
        String userAgent = request.header("User-Agent");
        String token = tokenAfterRejection(userAgent, rejected);
        if (token == null || token.equals(rejected)) return response;
        // OkHttp needs the rejected response closed before the same call sends again.
        response.close();
        Response retried = chain.proceed(request.newBuilder().header("Authorization", "Bearer " + token).build());
        if (isRejection(retried)) markRejected(userAgent, token);
        return retried;
    }

    private static boolean isRejection(Response response) {
        return response.code() == 401 || response.code() == 403;
    }

    /** Sync's GrabIpRequest: GET https://api.redgifs.com/info. */
    static boolean isInfoLookup(Request request) {
        HttpUrl url = request.url();
        return url.isHttps() && HOST.equals(url.host()) && "GET".equals(request.method())
                && INFO_PATH.equals(url.encodedPath());
    }

    /** What Sync's GrabIpRequest parses: a JSON object with the client's remote-addr. */
    private static Response infoResponse(Request request, String address) {
        String body = "{\"remote-addr\":\"" + address + "\"}";
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
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

    static boolean validAddress(String address) {
        return address != null && ADDRESS.matcher(address).matches()
                && (address.indexOf('.') >= 0 || address.indexOf(':') >= 0);
    }

    /**
     * The token to retry with after RedGifs rejected {@code rejected}, or null to return the
     * rejection. Starts at most one token request at a time per User-Agent; others wait for it.
     */
    static String tokenAfterRejection(String userAgent, String rejected) {
        if (userAgent == null || userAgent.isEmpty()) return null;
        Flight wait, mine;
        Slot slot;
        synchronized (SLOTS) {
            slot = slot(userAgent);
            long now = now();
            if (slot.token != null && !slot.token.equals(rejected) && !slot.token.equals(slot.bad)
                    && now - slot.tokenAt < REUSE_MS) {
                return slot.token;
            }
            wait = slot.flight;
            mine = wait == null ? start(slot, now) : null;
            if (wait == null && mine == null) return null;
        }
        Flight flight = wait != null ? await(wait) : run(slot, mine, userAgent);
        return flight == null ? null : flight.token;
    }

    /** The address for Sync's /info lookup, or null when none can be obtained now. */
    static String addressFor(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) return null;
        Flight wait, mine;
        Slot slot;
        synchronized (SLOTS) {
            slot = slot(userAgent);
            long now = now();
            if (slot.address != null && now - slot.addressAt < ADDRESS_REUSE_MS) return slot.address;
            wait = slot.flight;
            mine = wait == null ? start(slot, now) : null;
            if (wait == null && mine == null) return null;
        }
        Flight flight = wait != null ? await(wait) : run(slot, mine, userAgent);
        return flight == null ? null : flight.address;
    }

    /** A new token request for this slot, or null during the failure backoff. Holds SLOTS. */
    private static Flight start(Slot slot, long now) {
        if (slot.failedAt != 0 && now - slot.failedAt < FAILURE_BACKOFF_MS) return null;
        return slot.flight = new Flight();
    }

    private static Flight await(Flight flight) {
        try {
            return flight.done.await(REFRESH_WAIT_MS, TimeUnit.MILLISECONDS) ? flight : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Flight run(Slot slot, Flight mine, String userAgent) {
        try {
            fetch(userAgent, mine);
        } catch (Exception | LinkageError failed) {
            // Status, timeouts and parse failures are all a failed request; details may hold body text.
            mine.token = null;
            mine.address = null;
        }
        synchronized (SLOTS) {
            slot.flight = null;
            long now = now();
            if (mine.token != null) {
                slot.token = mine.token;
                slot.tokenAt = now;
                slot.bad = null;
                slot.failedAt = 0;
            } else {
                slot.failedAt = Math.max(1, now);
            }
            if (mine.address != null) {
                slot.address = mine.address;
                slot.addressAt = now;
            }
        }
        mine.done.countDown();
        if (mine.token != null) remember(userAgent, mine.token);
        return mine;
    }

    /** RedGifs rejected a token this interceptor supplied: back off instead of fetching again. */
    private static void markRejected(String userAgent, String token) {
        synchronized (SLOTS) {
            Slot slot = SLOTS.get(userAgent);
            if (slot != null && token.equals(slot.token)) {
                slot.bad = token;
                slot.failedAt = Math.max(1, now());
            }
        }
    }

    /** One bounded request for a temporary token, sent only to api.redgifs.com over HTTPS. */
    private static void fetch(String userAgent, Flight into) throws IOException {
        OkHttpClient client = tokenClient;
        if (client == null) return;
        Request request = new Request.Builder().url(TOKEN_URL).get()
                .header("User-Agent", userAgent).header("Accept", "application/json").build();
        Response response = client.newCall(request).execute();
        try {
            HttpUrl url = response.request().url();
            if (response.code() != 200 || !url.isHttps() || !HOST.equals(url.host())
                    || !TOKEN_PATH.equals(url.encodedPath())) return;
            ResponseBody body = response.body();
            if (body == null) return;
            String text = read(body);
            if (text == null) return;
            JSONObject json = new JSONObject(text);
            Object token = json.opt("token");
            if (!(token instanceof String) || !validToken((String) token)) return;
            into.token = (String) token;
            Object address = json.opt("addr");
            if (address instanceof String && validAddress((String) address)) into.address = (String) address;
        } catch (org.json.JSONException malformed) {
            into.token = null;
            into.address = null;
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
            // Retries still use the new token; only the inherited cache keeps the old one.
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

    /** Test hook: forget all state between scenarios. */
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
        String bad;
        long failedAt;
        String address;
        long addressAt;
        Flight flight;

        @Override
        public String toString() {
            // Never expose the token or address through debugging output.
            return String.format(Locale.ROOT, "Slot{token=%s, address=%s, refreshing=%s}",
                    token == null ? "none" : "set", address == null ? "none" : "set", flight != null);
        }
    }

    private static final class Flight {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String token;
        volatile String address;
    }
}
