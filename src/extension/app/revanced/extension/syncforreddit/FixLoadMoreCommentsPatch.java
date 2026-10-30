package app.revanced.extension.syncforreddit;

import android.net.Uri;
import android.text.TextUtils;

import com.android.volley.NetworkResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /** Fixed messages only: never expose JSON bodies, account headers, or server exception text. */
    private static final class CommentResponseException extends RuntimeException {
        final int reason;
        String diagnostic;
        CommentResponseException(int reason) { this.reason = reason; }
    }

    private static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) { super("HTTP " + status); this.status = status; }
    }

    private static final class CommentFetch {
        final String json;
        final boolean verifiedEmpty;
        CommentFetch(String json, boolean verifiedEmpty) { this.json = json; this.verifiedEmpty = verifiedEmpty; }
    }

    /** Per-request counters only. No response bodies, URLs, IDs, headers, or accounts are retained. */
    private static final class Diagnostic {
        String stage = "initial";
        int requested, attempted = 1, firstStatus;
        boolean parentKnown;
        int firstComments = -1, fallbackComments = -1, firstPostMatch = -1, fallbackPostMatch = -1;
        int firstRoots = -1, fallbackRoots = -1, firstMore = -1, fallbackMore = -1, exhausted;
        int missing, invalid, network;
        final Map<Integer, Integer> http = new TreeMap<>();

        void failure(Throwable error) {
            for (int i = 0; i < 8 && error != null; i++, error = error.getCause()) {
                if (error instanceof HttpStatusException) {
                    int status = ((HttpStatusException) error).status;
                    Integer previous = http.get(status);
                    http.put(status, previous == null ? 1 : previous + 1);
                    return;
                }
                if (error instanceof CommentResponseException) {
                    if (((CommentResponseException) error).reason == 1) missing++; else invalid++;
                    return;
                }
                if (error instanceof IOException) { network++; return; }
            }
            invalid++;
        }

        void response(String body, String expectedPost, boolean fallback) {
            int count = -1, matches = -1, roots = -1, more = -1;
            try {
                Object value = new JSONTokener(body).nextValue();
                JSONObject listing = null;
                if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    JSONArray posts = array.getJSONObject(0).getJSONObject("data").getJSONArray("children");
                    if (posts.length() > 0 && expectedPost != null) {
                        String expected = expectedPost.startsWith("t3_") ? expectedPost : "t3_" + expectedPost;
                        matches = expected.equals("t3_" + posts.getJSONObject(0).getJSONObject("data").optString("id")) ? 1 : 0;
                    }
                    listing = array.getJSONObject(1);
                } else if (value instanceof JSONObject && "Listing".equals(((JSONObject) value).optString("kind"))) {
                    listing = (JSONObject) value;
                }
                if (listing != null) {
                    JSONArray children = listing.getJSONObject("data").getJSONArray("children");
                    count = countComments(children, 0);
                    roots = children.length();
                    more = countMore(children, 0);
                }
            } catch (Exception ignored) { /* Report an unknown shape without exporting its contents. */ }
            if (fallback) { fallbackComments = count; fallbackPostMatch = matches; fallbackRoots = roots; fallbackMore = more; }
            else { firstComments = count; firstPostMatch = matches; firstRoots = roots; firstMore = more; }
        }

        String format() {
            return "Sync comments diagnostic v0.6.0\n"
                    + "stage=" + stage + "\n"
                    + "requested=" + requested + " attempted=" + attempted + " parent=" + (parentKnown ? "valid" : "missing") + "\n"
                    + "first_http=" + firstStatus + "\n"
                    + "first_comments=" + firstComments + " fallback_comments=" + fallbackComments + "\n"
                    + "first_roots=" + firstRoots + " fallback_roots=" + fallbackRoots + "\n"
                    + "first_more=" + firstMore + " fallback_more=" + fallbackMore + " exhausted=" + exhausted + "\n"
                    + "first_post_match=" + firstPostMatch + " fallback_post_match=" + fallbackPostMatch + "\n"
                    + "failed_ids: missing=" + missing + " invalid=" + invalid + " network=" + network + "\n"
                    + "http_failures=" + http;
        }
    }

    private static int countMore(JSONArray children, int depth) {
        if (depth > 64) return 0;
        int count = 0;
        for (int i = 0; i < children.length(); i++) {
            JSONObject thing = children.optJSONObject(i);
            if (thing == null) continue;
            if ("more".equals(thing.optString("kind"))) count++;
            JSONObject data = thing.optJSONObject("data");
            JSONObject replies = data == null ? null : data.optJSONObject("replies");
            JSONObject nested = replies == null ? null : replies.optJSONObject("data");
            JSONArray next = nested == null ? null : nested.optJSONArray("children");
            if (next != null) count += countMore(next, depth + 1);
        }
        return count;
    }

    private static int countComments(JSONArray children, int depth) {
        if (depth > 64) return 0;
        int count = 0;
        for (int i = 0; i < children.length(); i++) {
            JSONObject thing = children.optJSONObject(i);
            if (thing == null || !"t1".equals(thing.optString("kind"))) continue;
            count++;
            JSONObject data = thing.optJSONObject("data");
            JSONObject replies = data == null ? null : data.optJSONObject("replies");
            JSONObject nested = replies == null ? null : replies.optJSONObject("data");
            JSONArray next = nested == null ? null : nested.optJSONArray("children");
            if (next != null) count += countComments(next, depth + 1);
        }
        return count;
    }

    public static String getDiagnosticDetails(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
            if (current instanceof CommentResponseException) {
                CommentResponseException failure = (CommentResponseException) current;
                if (failure.diagnostic != null) return failure.diagnostic + "\nreason=" + getFailureReason(error);
            }
        }
        int status = error instanceof com.android.volley.VolleyError
                && ((com.android.volley.VolleyError) error).networkResponse != null
                ? ((com.android.volley.VolleyError) error).networkResponse.statusCode : -1;
        return "Sync comments diagnostic v0.6.0\nstage=outside_adapter\nhttp=" + status;
    }

    public static String getFailureReason(Throwable error) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof CommentResponseException) {
                switch (((CommentResponseException) error).reason) {
                    case 1: return "comment unavailable";
                    case 3: return "fallback request failed";
                    default: return "invalid comment response";
                }
            }
        }
        return null;
    }

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
        return getMoreChildrenResponse(response, url, params, headers, null);
    }

    /** The continuation parent is supplied from Sync's clicked row, before it is enqueued. */
    public static NetworkResponse getMoreChildrenResponse(
            NetworkResponse response,
            String url,
            Map<String, String> params,
            Map<String, String> headers,
            String continuationParent
    ) {
        if (response == null || response.data == null || params == null) return response;
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.firstStatus = response.statusCode;
        diagnostic.parentKnown = continuationParent != null && continuationParent.matches("t[13]_[a-zA-Z0-9]+");
        try {
            List<String> children = getChildren(params);
            diagnostic.requested = children.size();
            if (children.isEmpty()) return response;

            List<String> fetchedChildren = children.subList(0, Math.min(children.size(), MAX_CHILDREN));

            String firstJson = new String(response.data, StandardCharsets.UTF_8);
            // Existing morechildren responses are already in the format Sync expects.
            if (firstJson.trim().startsWith("{")) {
                try {
                    JSONObject object = new JSONObject(firstJson);
                    JSONObject json = object.optJSONObject("json");
                    JSONObject data = json == null ? null : json.optJSONObject("data");
                    if (data != null && data.optJSONArray("things") != null) return response;
                } catch (Exception malformedObject) {
                    // The fallback below also covers malformed object responses.
                }
            }

            // A missing focal comment used to pass an array into Sync's object parser.
            // Try the canonical comment query once before reporting an unavailable row.
            JSONArray things = new JSONArray();
            Set<String> addedIds = new HashSet<>();
            Set<String> exhausted = new HashSet<>();
            Map<String, String> defaults = new HashMap<>();
            String linkId = params.get("link_id");
            if (linkId != null) defaults.put("link_id", linkId.startsWith("t3_") ? linkId : "t3_" + linkId);
            String parentId = null;
            Exception firstFailure = null;
            try {
                parentId = addComments(firstJson, children.get(0), things, addedIds, defaults);
            } catch (Exception invalidFirst) {
                diagnostic.response(firstJson, linkId, false);
                things = new JSONArray();
                addedIds.clear();
                try {
                    String fallback = fetch(getFallbackUrl(url, params, children.get(0)), headers);
                    diagnostic.response(fallback, linkId, true);
                    if (response.statusCode == 200 && diagnostic.parentKnown
                            && isVerifiedEmpty(firstJson, params) && isVerifiedEmpty(fallback, params)) {
                        exhausted.add(children.get(0));
                        diagnostic.exhausted++;
                    } else {
                        parentId = addComments(fallback, children.get(0), things, addedIds, defaults);
                    }
                } catch (IOException unavailable) {
                    diagnostic.failure(unavailable);
                    firstFailure = new CommentResponseException(3);
                } catch (Exception unavailable) {
                    diagnostic.failure(unavailable);
                    firstFailure = unavailable;
                }
            }

            // The first hidden comment can be absent while later ones remain available.
            // The actual clicked row supplies its parent; never infer a retry's parent
            // from an unrelated sibling, or declare the thread complete on an empty result.
            boolean knownParent = continuationParent != null && continuationParent.matches("t[13]_[a-zA-Z0-9]+");
            if (knownParent) parentId = continuationParent;
            if (firstFailure != null && !knownParent) {
                diagnostic.stage = "parent_missing";
                throw firstFailure;
            }

            diagnostic.attempted = fetchedChildren.size();
            List<Future<CommentFetch>> futures = new ArrayList<>(fetchedChildren.size());
            for (String child : fetchedChildren.subList(1, fetchedChildren.size())) {
                futures.add(executor.submit(() -> fetchComment(url, params, child, headers)));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    CommentFetch result = futures.get(i).get();
                    if (result.verifiedEmpty) {
                        exhausted.add(fetchedChildren.get(i + 1));
                        diagnostic.exhausted++;
                    } else {
                        addComments(result.json, fetchedChildren.get(i + 1), things, addedIds, defaults);
                    }
                } catch (Exception ex) {
                    diagnostic.failure(ex);
                    String child = fetchedChildren.get(i + 1);
                    Logger.printInfo(() -> "Failed to load comment " + child, ex);
                }
            }

            // Decide what is still pending from the returned content, not from the
            // attempted batch. This retains failed/empty requests and avoids adding
            // a second retry row for IDs already present in a nested continuation.
            things = reconcileMore(things, exhausted);
            Set<String> represented = new HashSet<>();
            for (int i = 0; i < things.length(); i++) {
                JSONObject thing = things.getJSONObject(i);
                JSONObject data = thing.getJSONObject("data");
                if ("t1".equals(thing.optString("kind"))) {
                    represented.add(data.optString("id"));
                } else if ("more".equals(thing.optString("kind"))) {
                    JSONArray pending = data.optJSONArray("children");
                    if (pending != null) for (int j = 0; j < pending.length(); j++) {
                        represented.add(pending.getString(j));
                    }
                }
            }
            List<String> remainingChildren = new ArrayList<>();
            // Advance to untouched IDs before retrying IDs already attempted this tap.
            // This prevents 100 unavailable IDs from hiding all later batches forever.
            for (String child : children.subList(fetchedChildren.size(), children.size())) {
                if (!represented.contains(child) && !exhausted.contains(child)) remainingChildren.add(child);
            }
            for (String child : fetchedChildren) if (!represented.contains(child) && !exhausted.contains(child)) remainingChildren.add(child);

            if (things.length() == 0 && exhausted.isEmpty() && children.size() <= fetchedChildren.size()) {
                diagnostic.stage = "empty_batch";
                if (firstFailure != null) throw firstFailure;
                throw new CommentResponseException(1);
            }
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
            CommentResponseException failure = ex instanceof CommentResponseException
                    ? (CommentResponseException) ex : new CommentResponseException(2);
            failure.diagnostic = diagnostic.format();
            throw failure;
        }
    }

    private static List<String> getChildren(Map<String, String> params) {
        if (params == null) return Collections.emptyList();
        String children = params.get("children");
        if (TextUtils.isEmpty(children)) return Collections.emptyList();

        Set<String> unique = new LinkedHashSet<>();
        for (String child : children.split(",")) {
            child = child.trim();
            if (child.startsWith("t1_")) child = child.substring(3);
            if (!child.isEmpty()) unique.add(child);
        }
        return new ArrayList<>(unique);
    }

    private static String getCommentUrl(String url, Map<String, String> params, String child) {
        Uri uri = Uri.parse(url);
        if (!MORE_CHILDREN_PATH.equals(uri.getPath())) return url;

        String postId = params.get("link_id");
        if (TextUtils.isEmpty(postId)) return url;
        if (postId.startsWith("t3_")) postId = postId.substring(3);
        Uri.Builder builder = uri.buildUpon().encodedPath("/comments/" + postId + "/comment/" + child);

        String sort = params.get("sort");
        if (!TextUtils.isEmpty(sort) && !"suggested".equalsIgnoreCase(sort)) {
            builder.appendQueryParameter("sort", "best".equalsIgnoreCase(sort) ? "confidence" : sort);
        }

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
                throw new HttpStatusException(responseCode);
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

    private static String getFallbackUrl(String url, Map<String, String> params, String child) {
        Uri uri = Uri.parse(url);
        String postId = params.get("link_id");
        if (postId == null || !MORE_CHILDREN_PATH.equals(uri.getPath())) throw new CommentResponseException(2);
        if (postId.startsWith("t3_")) postId = postId.substring(3);
        Uri.Builder builder = uri.buildUpon().encodedPath("/comments/" + postId + ".json")
                .appendQueryParameter("comment", child).appendQueryParameter("context", "0")
                .appendQueryParameter("limit", "100").appendQueryParameter("depth", "10")
                .appendQueryParameter("raw_json", "1");
        String sort = params.get("sort");
        if (!TextUtils.isEmpty(sort) && !"suggested".equalsIgnoreCase(sort)) {
            builder.appendQueryParameter("sort", "best".equalsIgnoreCase(sort) ? "confidence" : sort);
        }
        return builder.build().toString();
    }

    private static CommentFetch fetchComment(String url, Map<String, String> params, String child,
                                       Map<String, String> headers) throws Exception {
        String json = fetch(getCommentUrl(url, params, child), headers);
        try {
            selectComment(json, child, new HashMap<>());
            return new CommentFetch(json, false);
        } catch (Exception missing) {
            String fallback = fetch(getFallbackUrl(url, params, child), headers);
            boolean empty = isVerifiedEmpty(json, params) && isVerifiedEmpty(fallback, params);
            return new CommentFetch(empty ? null : fallback, empty);
        }
    }

    /** Empty means no nodes at all, not merely zero t1 objects or a missing focal ID. */
    private static boolean isVerifiedEmpty(String json, Map<String, String> params) {
        try {
            String expected = params.get("link_id");
            if (expected == null) return false;
            if (expected.startsWith("t3_")) expected = expected.substring(3);
            JSONArray thread = new JSONArray(json);
            if (thread.length() != 2) return false;
            JSONObject postListing = thread.getJSONObject(0), commentListing = thread.getJSONObject(1);
            if (!"Listing".equals(postListing.optString("kind")) || !"Listing".equals(commentListing.optString("kind"))) return false;
            JSONArray posts = postListing.getJSONObject("data").getJSONArray("children");
            if (posts.length() != 1 || !"t3".equals(posts.getJSONObject(0).optString("kind"))) return false;
            if (!expected.equals(posts.getJSONObject(0).getJSONObject("data").optString("id"))) return false;
            return commentListing.getJSONObject("data").getJSONArray("children").length() == 0;
        } catch (Exception unrecognized) {
            return false;
        }
    }

    /**
     * Adds the comments of a /comments/{post}/comment/{comment} response to things.
     *
     * @return The parent id of the first comment, or null if there is none.
     */
    private static String addComments(String json, String child, JSONArray things, Set<String> addedIds,
                                      Map<String, String> defaults) throws Exception {
        JSONObject selected = selectComment(json, child, defaults);
        String parentId = selected.getJSONObject("data").optString("parent_id", "");
        if (parentId.isEmpty()) throw new CommentResponseException(2);
        JSONArray branch = new JSONArray();
        Set<String> stagedIds = new HashSet<>(addedIds);
        flatten(selected, branch, stagedIds, defaults);
        for (int i = 0; i < branch.length(); i++) things.put(branch.getJSONObject(i));
        addedIds.addAll(stagedIds);
        return parentId;
    }

    private static JSONObject selectComment(String json, String child, Map<String, String> defaults) throws Exception {
        Object value = new JSONTokener(json).nextValue();
        JSONObject listing;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() < 2) throw new CommentResponseException(2);
            JSONArray posts = array.getJSONObject(0).getJSONObject("data").getJSONArray("children");
            if (posts.length() > 0) {
                JSONObject post = posts.getJSONObject(0).getJSONObject("data");
                String expected = defaults.get("link_id");
                if (expected != null && expected.equals("t3_" + post.optString("id"))) {
                    String subreddit = post.optString("subreddit", "");
                    if (!subreddit.isEmpty()) defaults.put("subreddit", subreddit);
                }
            }
            listing = array.getJSONObject(1);
        } else if (value instanceof JSONObject && "Listing".equals(((JSONObject) value).optString("kind"))) {
            listing = (JSONObject) value;
        } else {
            throw new CommentResponseException(2);
        }
        JSONObject selected = findComment(listing.getJSONObject("data").getJSONArray("children"), child);
        if (selected == null) throw new CommentResponseException(1);
        return selected;
    }

    private static JSONObject findComment(JSONArray children, String id) throws Exception {
        for (int i = 0; i < children.length(); i++) {
            JSONObject thing = children.optJSONObject(i);
            if (thing == null || !"t1".equals(thing.optString("kind"))) continue;
            JSONObject data = thing.optJSONObject("data");
            if (data == null) continue;
            if (id.equals(data.optString("id"))) return thing;
            JSONObject replies = data.optJSONObject("replies");
            JSONObject replyData = replies == null ? null : replies.optJSONObject("data");
            JSONArray nested = replyData == null ? null : replyData.optJSONArray("children");
            if (nested != null) {
                JSONObject found = findComment(nested, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Flattens a comment tree in pre-order like /api/morechildren does.
     * Sync derives the indentation of each comment from its parent preceding it.
     */
    private static void flatten(JSONObject thing, JSONArray things, Set<String> addedIds,
                                Map<String, String> defaults) throws Exception {
        JSONObject data = thing.getJSONObject("data");

        if ("t1".equals(thing.optString("kind"))) {
            // Sync requires both fields even for deleted and otherwise sparse replies.
            // Reconstruct metadata from the request and its matching post listing only.
            for (String key : new String[]{"link_id", "subreddit"}) {
                if ((data.isNull(key) || data.optString(key).isEmpty()) && defaults.containsKey(key)) {
                    data.put(key, defaults.get(key));
                }
            }
            if (!defaults.containsKey("subreddit") && !data.isNull("subreddit")
                    && !data.optString("subreddit").isEmpty()) defaults.put("subreddit", data.optString("subreddit"));
            for (String key : new String[]{"id", "parent_id", "link_id", "subreddit", "body"}) {
                if (!(data.opt(key) instanceof String)) throw new CommentResponseException(2);
            }
        } else if ("more".equals(thing.optString("kind"))) {
            if (!(data.opt("id") instanceof String) || !(data.opt("parent_id") instanceof String)
                    || data.optJSONArray("children") == null) throw new CommentResponseException(2);
        }
        if (data.has("gildings") && data.isNull("gildings")) data.remove("gildings");

        // The hidden comments may include replies of each other.
        // "Continue this thread" items all have the id "_", so they are told apart by their parent.
        String key = thing.getString("kind") + ":" + data.optString("parent_id") + ":" + data.optString("id");
        if ("more".equals(thing.optString("kind"))) key += ":" + data.optJSONArray("children");
        boolean added = addedIds.add(key);

        JSONObject replies = data.optJSONObject("replies");
        if (replies != null) data.put("replies", "");

        if (added) things.put(thing);

        if (replies == null) return;

        JSONArray children = replies.getJSONObject("data").getJSONArray("children");
        for (int i = 0; i < children.length(); i++) {
            flatten(children.getJSONObject(i), things, addedIds, defaults);
        }
    }

    /** Remove loaded IDs and overlapping continuations after every tree has arrived. */
    private static JSONArray reconcileMore(JSONArray things, Set<String> exhausted) throws Exception {
        Set<String> loaded = new HashSet<>();
        loaded.addAll(exhausted);
        for (int i = 0; i < things.length(); i++) {
            JSONObject thing = things.getJSONObject(i);
            if ("t1".equals(thing.optString("kind"))) loaded.add(thing.getJSONObject("data").optString("id"));
        }
        Set<String> pendingIds = new HashSet<>();
        JSONArray result = new JSONArray();
        for (int i = 0; i < things.length(); i++) {
            JSONObject thing = things.getJSONObject(i);
            JSONObject data = thing.getJSONObject("data");
            JSONArray pending = data.optJSONArray("children");
            if ("more".equals(thing.optString("kind")) && pending != null && pending.length() > 0) {
                JSONArray remaining = new JSONArray();
                for (int j = 0; j < pending.length(); j++) {
                    String id = pending.getString(j);
                    if (!loaded.contains(id) && pendingIds.add(id)) remaining.put(id);
                }
                if (remaining.length() == 0) continue;
                data.put("children", remaining).put("count", remaining.length());
                if (!"_".equals(data.optString("id"))) {
                    data.put("id", remaining.getString(0)).put("name", "t1_" + remaining.getString(0));
                }
            }
            result.put(thing);
        }
        return result;
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
