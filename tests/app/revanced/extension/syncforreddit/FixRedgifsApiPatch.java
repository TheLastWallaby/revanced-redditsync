package app.revanced.extension.syncforreddit;

import app.revanced.extension.shared.fixes.redgifs.BaseFixRedgifsApiPatch;

import okhttp3.OkHttpClient;

/**
 * Test double for the inherited Sync RedGifs installer, transcribed from the prepared base:
 * install adds the shared interceptor to Sync's OkHttpHelper builder and builds the client.
 */
public class FixRedgifsApiPatch extends BaseFixRedgifsApiPatch {
    /** Sync 23033's default User-Agent comes from its settings singleton; tests fix one here. */
    public static volatile String userAgent = "android:com.laurencedawson.reddit_sync:test";

    static {
        INSTANCE = new FixRedgifsApiPatch();
    }

    public static OkHttpClient install(OkHttpClient.Builder builder) {
        return builder.addInterceptor(INSTANCE).build();
    }

    /** Test-only: the inherited interceptor instance, to check the installed order. */
    public static Object instance() { return INSTANCE; }

    @Override
    public String getDefaultUserAgent() { return userAgent; }
}
