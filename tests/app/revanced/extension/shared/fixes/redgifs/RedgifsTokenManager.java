package app.revanced.extension.shared.fixes.redgifs;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Test double for the inherited ReVanced RedgifsTokenManager in the prepared base, transcribed from
 * its build-23033 bytecode: a private static {@code tokenMap} keyed by User-Agent, a 23-hour token
 * lifetime with no invalidation, and {@code refreshToken} fetching under the map's lock. The base
 * fetches with HttpURLConnection; here the same request goes through the loopback test network.
 */
public class RedgifsTokenManager {
    private static final String GET_TEMPORARY_TOKEN = "https://api.redgifs.com/v2/auth/temporary";
    public static final String REDGIFS_API_HOST = "https://api.redgifs.com";
    private static final Map<String, RedgifsToken> tokenMap = new HashMap<>();

    /** Test-only: fetches made by this inherited code (not by the compatibility refresh). */
    public static final AtomicInteger inheritedFetches = new AtomicInteger();

    public static String getEmulatedOAuthResponseBody(RedgifsToken token) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("access_token", token.accessToken);
        body.put("expiry_time", token.getExpiryTimeInSeconds() - System.currentTimeMillis() / 1000);
        body.put("scope", "read");
        body.put("token_type", "Bearer");
        return body.toString();
    }

    private static String getToken(String userAgent) throws IOException, JSONException {
        inheritedFetches.incrementAndGet();
        Request request = new Request.Builder().url(GET_TEMPORARY_TOKEN).get()
                .header("User-Agent", userAgent).header("Content-Type", "application/json")
                .header("Accept", "application/json").build();
        try (Response response = new OkHttpClient.Builder().build().newCall(request).execute()) {
            if (response.code() != 200) throw new IOException("HTTP " + response.code());
            return new JSONObject(response.body().string()).getString("token");
        }
    }

    public static RedgifsToken refreshToken(String userAgent) throws IOException, JSONException {
        synchronized (tokenMap) {
            RedgifsToken token = tokenMap.get(userAgent);
            if (token != null && token.isValid()) return token;
            RedgifsToken fresh = new RedgifsToken(getToken(userAgent), System.currentTimeMillis() / 1000);
            tokenMap.put(userAgent, fresh);
            return fresh;
        }
    }

    /** Test-only: the cached token text for a User-Agent, or null. */
    public static String cached(String userAgent) {
        synchronized (tokenMap) {
            RedgifsToken token = tokenMap.get(userAgent);
            return token == null ? null : token.accessToken;
        }
    }

    /** Test-only: an empty cache, as after the process restarts. */
    public static void clear() {
        synchronized (tokenMap) { tokenMap.clear(); }
        inheritedFetches.set(0);
    }

    public static class RedgifsToken {
        private static final long EXPIRY_SECONDS = 82800;
        private final String accessToken;
        private final long refreshTimeInSeconds;

        public RedgifsToken(String accessToken, long refreshTimeInSeconds) {
            this.accessToken = accessToken;
            this.refreshTimeInSeconds = refreshTimeInSeconds;
        }

        public String getAccessToken() { return accessToken; }
        public long getExpiryTimeInSeconds() { return refreshTimeInSeconds + EXPIRY_SECONDS; }
        public boolean isValid() {
            return accessToken != null && getExpiryTimeInSeconds() >= System.currentTimeMillis() / 1000;
        }
    }
}
