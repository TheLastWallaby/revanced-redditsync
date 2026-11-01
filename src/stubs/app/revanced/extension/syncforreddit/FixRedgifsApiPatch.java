package app.revanced.extension.syncforreddit;

import okhttp3.OkHttpClient;

/** Compile signature only: the inherited ReVanced RedGifs interceptor installer in the prepared base. */
public class FixRedgifsApiPatch {
    public static OkHttpClient install(OkHttpClient.Builder builder) { return null; }
}
