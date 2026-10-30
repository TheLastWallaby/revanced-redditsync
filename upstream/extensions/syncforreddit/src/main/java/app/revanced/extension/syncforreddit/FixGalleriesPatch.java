package app.revanced.extension.syncforreddit;

import com.android.volley.NetworkResponse;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;

import app.revanced.extension.shared.Logger;

/**
 * Reddit blocks /api/info, so galleries fail to load with HTTP 403.
 * <p>
 * Load the post from /comments/{post} instead, whose first element is the same listing /api/info returns.
 *
 * @noinspection unused
 */
public final class FixGalleriesPatch {
    private static final String INFO_URL_PREFIX = "https://oauth.reddit.com/api/info.json?id=t3_";

    /**
     * Injection point.
     *
     * @param url The /api/info URL of the gallery post.
     * @return The /comments URL of the gallery post.
     */
    public static String getUrl(String url) {
        if (!url.startsWith(INFO_URL_PREFIX)) return url;

        String postId = url.substring(INFO_URL_PREFIX.length());

        // The comments are not needed, so request as few as possible.
        return "https://oauth.reddit.com/comments/" + postId + ".json?limit=1&depth=1";
    }

    /**
     * Injection point.
     *
     * @param response The /comments response.
     * @return The response in the /api/info format.
     */
    public static NetworkResponse getResponse(NetworkResponse response) {
        try {
            String json = new String(response.data, StandardCharsets.UTF_8);

            // The response is [post listing, comment listing].
            String postListing = new JSONArray(json).getJSONObject(0).toString();

            return new NetworkResponse(
                    response.statusCode,
                    postListing.getBytes(StandardCharsets.UTF_8),
                    response.headers,
                    response.notModified,
                    response.networkTimeMs
            );
        } catch (Exception ex) {
            Logger.printInfo(() -> "Failed to convert gallery response", ex);
            return response;
        }
    }
}
