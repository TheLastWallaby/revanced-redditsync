package app.revanced.extension.shared.fixes.redgifs;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONException;

/**
 * Test double for the inherited ReVanced RedGifs interceptor, transcribed from the prepared base's
 * BaseFixRedgifsApiPatch.intercept. A request that already has Authorization is sent with the
 * default User-Agent; if it fails, it is sent again with the cached token. Sync's
 * /v2/oauth/client request is answered locally from that cache. The base's Logger call on a JSON
 * error is omitted; it never includes the token.
 */
public abstract class BaseFixRedgifsApiPatch implements Interceptor {
    protected static BaseFixRedgifsApiPatch INSTANCE;

    public abstract String getDefaultUserAgent();

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!request.url().host().equals("api.redgifs.com")) return chain.proceed(request);
        String userAgent = getDefaultUserAgent();
        if (request.header("Authorization") != null) {
            Response response = chain.proceed(request.newBuilder().header("User-Agent", userAgent).build());
            if (response.isSuccessful()) return response;
            userAgent = response.request().header("User-Agent");
            response.close();
        }
        try {
            RedgifsTokenManager.RedgifsToken token = RedgifsTokenManager.refreshToken(userAgent);
            if (request.url().encodedPath().equals("/v2/oauth/client")) {
                String body = RedgifsTokenManager.getEmulatedOAuthResponseBody(token);
                return new Response.Builder().message("OK").code(200).protocol(Protocol.HTTP_1_1)
                        .request(request).header("Content-Type", "application/json")
                        .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
            }
            return chain.proceed(request.newBuilder()
                    .header("Authorization", "Bearer " + token.getAccessToken())
                    .header("User-Agent", userAgent).build());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}
