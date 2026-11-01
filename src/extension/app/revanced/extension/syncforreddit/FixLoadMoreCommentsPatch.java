package app.revanced.extension.syncforreddit;

import android.net.Uri;
import android.text.TextUtils;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
     * Whether a tap first tries Reddit's own /api/morechildren batch request (issue #3).
     * Off: there is no evidence that Reddit accepts it for Sync's client, so every tap goes
     * straight to the per-comment route and never sends a request to /api/morechildren.
     * Non-final so tests can exercise the retained batch path.
     */
    static boolean batchEndpoint = false;

    /**
     * Same limit as /api/morechildren. The remaining comments are returned as a new "more" item.
     */
    private static final int MAX_CHILDREN = 100;

    /** Fixed messages only: never expose JSON bodies, account headers, or server exception text. */
    private static final class CommentResponseException extends RuntimeException {
        final int reason;
        String diagnostic;
        CommentResponseException(int reason) { this.reason = reason; }
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
        int requests, budget, reused, deferred;
        String stop, batch = batchEndpoint ? "not_needed" : "disabled";
        final Map<Integer, Integer> http = new TreeMap<>();

        void failure(Throwable error) {
            for (int i = 0; i < 8 && error != null; i++, error = error.getCause()) {
                if (error instanceof CommentTransport.HttpStatusException) {
                    int status = ((CommentTransport.HttpStatusException) error).status;
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
                    + "http_failures=" + http + "\n"
                    + "transport: requests=" + requests + " budget=" + budget + " reused=" + reused
                    + " deferred=" + deferred + " stop=" + (stop == null ? "none" : stop) + " batch=" + batch;
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
                    case 4: return "rate limited, try again shortly";
                    case 5: return "sign-in rejected";
                    case 6: return "timed out";
                    case 7: return "canceled";
                    case 8: return "requests failed";
                    case 9: return "busy, try again";
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
        return getMoreChildrenResponse(response, url, params, headers, continuationParent, null);
    }

    /**
     * Injection point. Sync's own request supplies cancellation and, after a 401,
     * the headers carrying its refreshed token.
     */
    public static NetworkResponse getMoreChildrenResponse(
            NetworkResponse response,
            String url,
            Map<String, String> params,
            Map<String, String> headers,
            String continuationParent,
            Request<?> request
    ) {
        if (response == null || response.data == null || params == null) return response;
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.firstStatus = response.statusCode;
        diagnostic.parentKnown = continuationParent != null && continuationParent.matches("t[13]_[a-zA-Z0-9]+");
        // Sync's own request already reported Reddit's current rate window.
        CommentTransport.observe(response.headers);
        CommentTransport.Expansion expansion = new CommentTransport.Expansion(request, headers);
        diagnostic.budget = expansion.budget;
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
                    String fallback = expansion.fetch(getFallbackUrl(url, params, children.get(0)));
                    diagnostic.response(fallback, linkId, true);
                    if (response.statusCode == 200 && diagnostic.parentKnown
                            && isVerifiedEmpty(firstJson, params) && isVerifiedEmpty(fallback, params)) {
                        exhausted.add(children.get(0));
                        diagnostic.exhausted++;
                    } else {
                        parentId = addComments(fallback, children.get(0), things, addedIds, defaults);
                    }
                } catch (CommentTransport.Deferred deferred) {
                    firstFailure = stopFailure(expansion.stopReason());
                } catch (IOException unavailable) {
                    diagnostic.failure(unavailable);
                    firstFailure = expansion.aborted() || "auth".equals(expansion.stopReason())
                            || "rate_limited".equals(expansion.stopReason())
                            ? stopFailure(expansion.stopReason()) : new CommentResponseException(3);
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

            // IDs with at least one request sent for them. The rest are untouched.
            Set<String> touched = new HashSet<>();
            touched.add(children.get(0));
            List<String> siblings = fetchedChildren.subList(1, fetchedChildren.size());
            Set<String> loaded = new HashSet<>();
            addLoadedIds(things, 0, loaded);
            List<String> wanted = new ArrayList<>();
            for (String child : siblings) if (!loaded.contains(child)) wanted.add(child);
            if (loadBatch(expansion, url, params, wanted, things, addedIds, defaults, diagnostic)) {
                touched.addAll(wanted);
                addLoadedIds(things, 0, loaded);
            }
            fetchEach(expansion, url, params, siblings, loaded, touched, things, addedIds, defaults, exhausted, diagnostic);
            diagnostic.attempted = touched.size();

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
            // This prevents unavailable IDs from hiding all later batches forever.
            for (String child : children) {
                if (!represented.contains(child) && !exhausted.contains(child) && !touched.contains(child)) remainingChildren.add(child);
            }
            diagnostic.deferred = remainingChildren.size();
            for (String child : children) {
                if (!represented.contains(child) && !exhausted.contains(child) && touched.contains(child)) remainingChildren.add(child);
            }

            if (things.length() == 0 && exhausted.isEmpty()) {
                // Nothing loaded: keep Sync's row in place with a short reason.
                String stop = expansion.stopReason();
                if (stop != null && !"budget".equals(stop)) {
                    diagnostic.stage = "stopped";
                    throw stopFailure(stop);
                }
                if (diagnostic.deferred == 0) {
                    diagnostic.stage = "empty_batch";
                    if (firstFailure != null) throw firstFailure;
                    throw new CommentResponseException(1);
                }
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
            diagnostic.requests = expansion.requests.get();
            diagnostic.stop = expansion.stopReason();
            failure.diagnostic = diagnostic.format();
            throw failure;
        }
    }

    private static CommentResponseException stopFailure(String stop) {
        if (stop == null) return new CommentResponseException(3);
        switch (stop) {
            case "rate_limited": return new CommentResponseException(4);
            case "auth": return new CommentResponseException(5);
            case "deadline": return new CommentResponseException(6);
            case "canceled": return new CommentResponseException(7);
            case "failing": return new CommentResponseException(8);
            case "busy": return new CommentResponseException(9);
            default: return new CommentResponseException(3);
        }
    }

    /** IDs of the comments in things from index start on. */
    private static void addLoadedIds(JSONArray things, int start, Set<String> loaded) {
        for (int i = start; i < things.length(); i++) {
            JSONObject thing = things.optJSONObject(i);
            JSONObject data = thing == null ? null : thing.optJSONObject("data");
            if (data != null && "t1".equals(thing.optString("kind"))) loaded.add(data.optString("id"));
        }
    }

    /**
     * Reddit's own batch endpoint returns every ID in one request when the configured
     * client may use it. Its availability is cached, and failures fall back to the
     * per-comment route without losing any ID. Only tried when {@link #batchEndpoint} is on.
     */
    private static boolean loadBatch(CommentTransport.Expansion expansion, String url, Map<String, String> params,
                                     List<String> ids, JSONArray things, Set<String> addedIds,
                                     Map<String, String> defaults, Diagnostic diagnostic) {
        if (!batchEndpoint) { diagnostic.batch = "disabled"; return false; }
        if (ids.isEmpty()) return false;
        if (!CommentTransport.batchMayWork()) { diagnostic.batch = "unavailable"; return false; }
        String batchUrl = getBatchUrl(url, params, ids);
        if (batchUrl == null || !expansion.canStart()) { diagnostic.batch = "skipped"; return false; }
        String body;
        try {
            body = expansion.fetchBatch(batchUrl);
        } catch (CommentTransport.HttpStatusException status) {
            diagnostic.batch = "http_" + status.status;
            int code = status.status;
            if (code == 400 || code == 403 || code == 404 || code == 405 || code == 410) CommentTransport.recordBatch(false);
            return false;
        } catch (IOException failed) {
            diagnostic.batch = failed instanceof CommentTransport.Deferred ? "deferred" : "network";
            return false;
        }
        try {
            JSONObject json = new JSONObject(body).getJSONObject("json");
            JSONArray errors = json.optJSONArray("errors");
            if (errors != null && errors.length() > 0) throw new CommentResponseException(2);
            JSONArray batch = json.getJSONObject("data").getJSONArray("things");
            // An empty answer for IDs Sync listed proves nothing; use the per-comment route.
            if (batch.length() == 0) throw new CommentResponseException(1);
            JSONArray staged = new JSONArray();
            Set<String> stagedIds = new HashSet<>(addedIds);
            for (int i = 0; i < batch.length(); i++) flatten(batch.getJSONObject(i), staged, stagedIds, defaults);
            for (int i = 0; i < staged.length(); i++) things.put(staged.getJSONObject(i));
            addedIds.addAll(stagedIds);
            CommentTransport.recordBatch(true);
            diagnostic.batch = "used";
            return true;
        } catch (Exception invalid) {
            CommentTransport.recordBatch(false);
            diagnostic.batch = "invalid";
            return false;
        }
    }

    private static String getBatchUrl(String url, Map<String, String> params, List<String> ids) {
        Uri uri = Uri.parse(url);
        String linkId = params.get("link_id");
        if (TextUtils.isEmpty(linkId) || !MORE_CHILDREN_PATH.equals(uri.getPath())) return null;
        if (!linkId.startsWith("t3_")) linkId = "t3_" + linkId;
        StringBuilder joined = new StringBuilder();
        for (String id : ids) {
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        Uri.Builder builder = uri.buildUpon().appendQueryParameter("api_type", "json")
                .appendQueryParameter("link_id", linkId).appendQueryParameter("children", joined.toString())
                .appendQueryParameter("raw_json", "1");
        String sort = params.get("sort");
        if (!TextUtils.isEmpty(sort) && !"suggested".equalsIgnoreCase(sort)) {
            builder.appendQueryParameter("sort", "best".equalsIgnoreCase(sort) ? "confidence" : sort);
        }
        return builder.build().toString();
    }

    /**
     * Requests the remaining IDs a few at a time. An ID already returned inside an
     * earlier subtree is not requested again. Stops at the budget, the deadline,
     * cancellation, rate limiting, a rejected token or repeated request failures.
     */
    private static void fetchEach(CommentTransport.Expansion expansion, String url, Map<String, String> params,
                                  List<String> ids, Set<String> loaded, Set<String> touched, JSONArray things,
                                  Set<String> addedIds, Map<String, String> defaults, Set<String> exhausted,
                                  Diagnostic diagnostic) {
        ExecutorCompletionService<CommentFetch> service = new ExecutorCompletionService<>(CommentTransport.executor);
        Map<Future<CommentFetch>, Integer> running = new HashMap<>();
        // Trees are kept per ID and added in the requested order, preserving Reddit's sort.
        CommentFetch[] results = new CommentFetch[ids.size()];
        int next = 0, failures = 0;
        while (true) {
            while (running.size() < CommentTransport.THREADS && next < ids.size() && expansion.canStart()) {
                final String child = ids.get(next);
                if (loaded.contains(child)) {
                    if (!touched.contains(child)) diagnostic.reused++;
                    next++;
                    continue;
                }
                // Reserve each ID's first request in order, so the budget covers the earliest IDs.
                if (!expansion.reserve()) break;
                try {
                    running.put(service.submit(() -> fetchComment(expansion, url, params, child)), next);
                    next++;
                } catch (RejectedExecutionException busy) {
                    expansion.release();
                    expansion.stop("busy");
                }
            }
            if (running.isEmpty()) break;
            Future<CommentFetch> done;
            try {
                done = service.poll(Math.max(1, Math.min(100, expansion.remainingMs())), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                expansion.stop("canceled");
                break;
            }
            if (done == null) {
                if (expansion.aborted()) break;
                continue;
            }
            int index = running.remove(done);
            String child = ids.get(index);
            try {
                CommentFetch result = done.get();
                touched.add(child);
                failures = 0;
                results[index] = result;
                // Later IDs inside this subtree need no request of their own.
                if (!result.verifiedEmpty) addSubtreeIds(result.json, child, loaded);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof CommentTransport.Deferred) continue;
                touched.add(child);
                diagnostic.failure(cause);
                if (cause instanceof IOException && ++failures >= CommentTransport.failureLimit) expansion.stop("failing");
                Logger.printInfo(() -> "Failed to load comment " + child,
                        cause instanceof Exception ? (Exception) cause : new Exception(cause));
            } catch (Exception invalid) {
                touched.add(child);
                diagnostic.failure(invalid);
                Logger.printInfo(() -> "Failed to load comment " + child, invalid);
            }
        }
        // Canceled or out of time: requests still running are abandoned; their IDs stay pending.
        for (Map.Entry<Future<CommentFetch>, Integer> abandoned : running.entrySet()) {
            abandoned.getKey().cancel(true);
            touched.add(ids.get(abandoned.getValue()));
        }
        for (int i = 0; i < results.length; i++) {
            CommentFetch result = results[i];
            if (result == null) continue;
            String child = ids.get(i);
            if (result.verifiedEmpty) {
                exhausted.add(child);
                diagnostic.exhausted++;
                continue;
            }
            try {
                addComments(result.json, child, things, addedIds, defaults);
            } catch (Exception invalid) {
                diagnostic.failure(invalid);
                Logger.printInfo(() -> "Failed to load comment " + child, invalid);
            }
        }
    }

    /** IDs of the requested comment and its loaded replies. Unreadable responses add nothing. */
    private static void addSubtreeIds(String json, String child, Set<String> loaded) {
        try {
            collectIds(selectComment(json, child, new HashMap<>()), loaded, 0);
        } catch (Exception unreadable) { /* Reported when the tree is added. */ }
    }

    private static void collectIds(JSONObject thing, Set<String> loaded, int depth) {
        if (thing == null || depth > 64 || !"t1".equals(thing.optString("kind"))) return;
        JSONObject data = thing.optJSONObject("data");
        if (data == null) return;
        loaded.add(data.optString("id"));
        JSONObject replies = data.optJSONObject("replies");
        JSONObject nested = replies == null ? null : replies.optJSONObject("data");
        JSONArray children = nested == null ? null : nested.optJSONArray("children");
        if (children != null) for (int i = 0; i < children.length(); i++) collectIds(children.optJSONObject(i), loaded, depth + 1);
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

    private static CommentFetch fetchComment(CommentTransport.Expansion expansion, String url,
                                             Map<String, String> params, String child) throws Exception {
        String json = expansion.fetchReserved(getCommentUrl(url, params, child));
        try {
            selectComment(json, child, new HashMap<>());
            return new CommentFetch(json, false);
        } catch (Exception missing) {
            String fallback;
            try {
                fallback = expansion.fetch(getFallbackUrl(url, params, child));
            } catch (CommentTransport.Deferred deferred) {
                // The first route was already requested, so this ID counts as attempted.
                throw new CommentResponseException(1);
            }
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
