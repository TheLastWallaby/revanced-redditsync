package app.revanced.extension.syncforreddit;

import android.net.Uri;
import android.text.TextUtils;

import com.android.volley.NetworkResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.revanced.extension.shared.Logger;

/**
 * Reddit blocks /api/morechildren, so "View more" fails with HTTP 403.
 * <p>
 * Like RedReader, fetch every hidden comment from /comments/{post}/comment/{comment} instead.
 * The first comment is fetched by the original request, the rest are fetched here.
 * All comment trees are then flattened into the /api/morechildren response format Sync parses.
 *
 * @noinspection unused
 */
public final class FixLoadMoreCommentsPatch {
    private static final String MORE_CHILDREN_PATH = "/api/morechildren";

    /**
     * Same limit as /api/morechildren. The remaining comments are returned as a new "more" item.
     */
    private static final int MAX_CHILDREN = 100;

    private static final int TIMEOUT_MILLISECONDS = 15_000;

    private static final ExecutorService executor = Executors.newFixedThreadPool(8);

    /**
     * Injection point.
     *
     * @param url    The original /api/morechildren URL.
     * @param params The /api/morechildren parameters.
     * @return The URL of the first hidden comment.
     */
    public static String getUrl(String url, Map<String, String> params) {
        // Called by the superclass constructor before the parameters are set.
        if (params == null) return url;

        List<String> children = getChildren(params);
        if (children.isEmpty()) return url;

        return getCommentUrl(url, params, children.get(0));
    }

    /**
     * Injection point.
     *
     * @param response The response of the first hidden comment.
     * @param url      The original /api/morechildren URL.
     * @param params   The /api/morechildren parameters.
     * @param headers  The request headers, including authorization.
     * @return A response in the /api/morechildren format.
     */
    public static NetworkResponse getMoreChildrenResponse(
            NetworkResponse response,
            String url,
            Map<String, String> params,
            Map<String, String> headers
    ) {
        try {
            List<String> children = getChildren(params);
            if (children.isEmpty()) return response;

            List<String> fetchedChildren = children.subList(0, Math.min(children.size(), MAX_CHILDREN));

            List<Future<String>> futures = new ArrayList<>(fetchedChildren.size());
            for (String child : fetchedChildren.subList(1, fetchedChildren.size())) {
                String commentUrl = getCommentUrl(url, params, child);
                futures.add(executor.submit(() -> fetch(commentUrl, headers)));
            }

            JSONArray things = new JSONArray();
            Set<String> addedIds = new HashSet<>();

            String firstJson = new String(response.data, StandardCharsets.UTF_8);
            String parentId = addComments(firstJson, things, addedIds);

            for (int i = 0; i < futures.size(); i++) {
                try {
                    addComments(futures.get(i).get(), things, addedIds);
                } catch (Exception ex) {
                    String child = fetchedChildren.get(i + 1);
                    Logger.printInfo(() -> "Failed to load comment " + child, ex);
                }
            }

            List<String> remainingChildren = children.subList(fetchedChildren.size(), children.size());
            if (!remainingChildren.isEmpty() && parentId != null) {
                things.put(createMore(remainingChildren, parentId));
            }

            JSONObject result = new JSONObject().put("json", new JSONObject()
                    .put("errors", new JSONArray())
                    .put("data", new JSONObject().put("things", things)));

            return new NetworkResponse(
                    response.statusCode,
                    result.toString().getBytes(StandardCharsets.UTF_8),
                    response.headers,
                    response.notModified,
                    response.networkTimeMs
            );
        } catch (Exception ex) {
            Logger.printInfo(() -> "Failed to load more comments", ex);
            return response;
        }
    }

    private static List<String> getChildren(Map<String, String> params) {
        String children = params.get("children");
        if (TextUtils.isEmpty(children)) return Collections.emptyList();

        return Arrays.asList(children.split(","));
    }

    private static String getCommentUrl(String url, Map<String, String> params, String child) {
        Uri uri = Uri.parse(url);
        if (!MORE_CHILDREN_PATH.equals(uri.getPath())) return url;

        String postId = params.get("link_id").replace("t3_", "");
        Uri.Builder builder = uri.buildUpon().encodedPath("/comments/" + postId + "/comment/" + child);

        String sort = params.get("sort");
        if (!TextUtils.isEmpty(sort)) builder.appendQueryParameter("sort", sort);

        return builder.build().toString();
    }

    private static String fetch(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(TIMEOUT_MILLISECONDS);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected response code " + responseCode + " for " + url);
            }

            try (InputStream inputStream = connection.getInputStream()) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                return outputStream.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Adds the comments of a /comments/{post}/comment/{comment} response to things.
     *
     * @return The parent id of the first comment, or null if there is none.
     */
    private static String addComments(String json, JSONArray things, Set<String> addedIds) throws Exception {
        // The response is [post listing, comment listing].
        JSONArray comments = new JSONArray(json)
                .getJSONObject(1)
                .getJSONObject("data")
                .getJSONArray("children");

        String parentId = null;
        for (int i = 0; i < comments.length(); i++) {
            JSONObject thing = comments.getJSONObject(i);
            if (parentId == null) parentId = thing.getJSONObject("data").optString("parent_id", null);
            flatten(thing, things, addedIds);
        }

        return parentId;
    }

    /**
     * Flattens a comment tree in pre-order like /api/morechildren does.
     * Sync derives the indentation of each comment from its parent preceding it.
     */
    private static void flatten(JSONObject thing, JSONArray things, Set<String> addedIds) throws Exception {
        JSONObject data = thing.getJSONObject("data");

        // The hidden comments may include replies of each other.
        // "Continue this thread" items all have the id "_", so they are told apart by their parent.
        String key = thing.getString("kind") + data.optString("parent_id") + data.optString("id");
        if (!addedIds.add(key)) return;

        JSONObject replies = data.optJSONObject("replies");
        if (replies != null) data.put("replies", "");

        things.put(thing);

        if (replies == null) return;

        JSONArray children = replies.getJSONObject("data").getJSONArray("children");
        for (int i = 0; i < children.length(); i++) {
            flatten(children.getJSONObject(i), things, addedIds);
        }
    }

    private static JSONObject createMore(List<String> children, String parentId) throws Exception {
        String id = children.get(0);

        return new JSONObject()
                .put("kind", "more")
                .put("data", new JSONObject()
                        .put("count", children.size())
                        .put("name", "t1_" + id)
                        .put("id", id)
                        .put("parent_id", parentId)
                        .put("depth", 0)
                        .put("children", new JSONArray(children)));
    }
}
