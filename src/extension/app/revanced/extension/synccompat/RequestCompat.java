package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.VolleyError;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Experimental request compatibility for Sync v23.06.30-13:39. */
public final class RequestCompat {
    private static final Pattern POST_PATH = Pattern.compile(
            "(?:/comments/|/gallery/|redd\\.it/)([a-z0-9]+)", Pattern.CASE_INSENSITIVE);

    private RequestCompat() {}

    public static String galleryUrl(String input) {
        String id = null;
        if (input != null) {
            Matcher match = POST_PATH.matcher(input);
            if (match.find()) id = match.group(1);
            if (id == null) {
                String bare = input.replaceFirst("[?#].*$", "").replaceFirst("^t3_", "");
                if (bare.matches("[a-zA-Z0-9]+")) id = bare;
            }
        }
        // Do not throw on the UI thread for an unexpected URL.
        if (id == null) return input == null ? "https://oauth.reddit.com/api/info.json?id=t3_" : input;
        // No raw_json, like Sync's own requests (issue #55). Media URLs arrive HTML-escaped and
        // GalleryCompat.mediaUrl decodes only their ampersands; captions stay escaped because
        // Sync's caption renderer (RedditHelper d7.f.r) unescapes them with WebsafeUtils.
        return "https://oauth.reddit.com/comments/" + id.toLowerCase(Locale.ROOT)
                + ".json?limit=1&depth=1&always_show_media=1";
    }

    public static String commentsUrl(String original, Map<String, String> parameters) {
        // getUrl() is called from the superclass constructor, before parameters exist.
        if (original == null || parameters == null || parameters.isEmpty()) return original;
        List<String> children = requestedChildren(parameters);
        String post = parameters.get("link_id");
        if (post != null && post.startsWith("t3_")) post = post.substring(3);
        if (post != null && post.matches("[a-zA-Z0-9]+") && !children.isEmpty()) {
            try {
                URI origin = new URI(original);
                if ("https".equals(origin.getScheme()) && origin.getRawAuthority() != null) {
                    StringBuilder out = new StringBuilder("https://" + origin.getRawAuthority()
                            + "/comments/" + post + ".json");
                    append(out, "comment", children.get(0));
                    append(out, "context", "0");
                    append(out, "limit", "100");
                    append(out, "depth", "10");
                    append(out, "always_show_media", "1");
                    append(out, "profile_img", "true");
                    String sort = parameters.get("sort");
                    if (sort != null && !sort.isEmpty() && !"suggested".equalsIgnoreCase(sort)) {
                        append(out, "sort", "best".equalsIgnoreCase(sort) ? "confidence" : sort);
                    }
                    return out.toString();
                }
            } catch (Exception malformedUrl) {
                // Fall back to the existing request shape for an unexpected origin.
            }
        }
        return legacyCommentsUrl(original, parameters);
    }

    private static String legacyCommentsUrl(String original, Map<String, String> parameters) {
        // Keep Reddit's default escaping, as Sync's own request does (issue #55).
        StringBuilder out = new StringBuilder(original);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if ("sort".equals(key)) {
                if ("suggested".equalsIgnoreCase(value)) continue;
                if ("best".equalsIgnoreCase(value)) value = "confidence";
            }
            // Keep the full children list, including longer/new-format IDs.
            // Do not silently truncate children or mutate Sync's parameter map.
            append(out, key, value);
        }
        return out.toString();
    }

    private static List<String> requestedChildren(Map<String, String> parameters) {
        Set<String> unique = new LinkedHashSet<>();
        String values = parameters == null ? null : parameters.get("children");
        if (values != null) {
            for (String value : values.split(",")) {
                String id = value.trim();
                if (id.startsWith("t1_")) id = id.substring(3);
                if (!id.isEmpty()) unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    public static NetworkResponse normalizeMore(NetworkResponse response, Map<String, String> parameters) {
        if (response == null || response.data == null) return response;
        try {
            String text = new String(response.data, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("[")) return response; // Existing morechildren envelope.
            List<String> requested = requestedChildren(parameters);
            if (requested.isEmpty()) return response;
            JSONArray listings = new JSONArray(text);
            JSONArray roots = listings.getJSONObject(1).getJSONObject("data").getJSONArray("children");
            JSONObject selected = findComment(roots, requested.get(0));
            if (selected == null) return response; // Original parser reports an error; keep the old stub.
            JSONArray things = new JSONArray();
            Set<String> represented = new LinkedHashSet<>();
            flatten(selected, things, represented, 0);
            List<String> remaining = new ArrayList<>();
            for (String id : requested) if (!represented.contains(id)) remaining.add(id);
            if (!remaining.isEmpty()) {
                JSONObject parent = selected.getJSONObject("data");
                JSONObject more = new JSONObject();
                more.put("id", remaining.get(0));
                more.put("name", "t1_" + remaining.get(0));
                more.put("parent_id", parent.getString("parent_id"));
                more.put("children", new JSONArray(remaining));
                more.put("count", remaining.size());
                more.put("depth", 0);
                more.put("subreddit", parent.optString("subreddit"));
                things.put(new JSONObject().put("kind", "more").put("data", more));
            }
            JSONObject envelope = new JSONObject().put("json", new JSONObject()
                    .put("errors", new JSONArray()).put("data", new JSONObject().put("things", things)));
            return new NetworkResponse(response.statusCode, envelope.toString().getBytes(StandardCharsets.UTF_8),
                    response.headers, response.notModified, response.networkTimeMs);
        } catch (Exception malformed) {
            // An unadapted array causes the original parse-error path, before any rows are deleted.
            return response;
        }
    }

    private static JSONObject findComment(JSONArray children, String id) throws Exception {
        for (int i = 0; i < children.length(); i++) {
            JSONObject thing = children.getJSONObject(i);
            if (!"t1".equals(thing.optString("kind"))) continue;
            JSONObject data = thing.getJSONObject("data");
            if (id.equals(data.optString("id"))) return thing;
            JSONObject replies = data.optJSONObject("replies");
            if (replies != null) {
                JSONObject found = findComment(replies.getJSONObject("data").getJSONArray("children"), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void flatten(JSONObject thing, JSONArray out, Set<String> represented, int depth) throws Exception {
        String kind = thing.getString("kind");
        if (!"t1".equals(kind) && !"more".equals(kind)) return;
        JSONObject data = new JSONObject(thing.getJSONObject("data").toString());
        JSONObject replies = data.optJSONObject("replies");
        data.put("replies", ""); // Sync's morechildren parser expects a flat, parent-first sequence.
        data.put("depth", depth);
        out.put(new JSONObject().put("kind", kind).put("data", data));
        if ("t1".equals(kind)) {
            represented.add(data.getString("id"));
        } else {
            JSONArray pending = data.optJSONArray("children");
            if (pending != null) for (int i = 0; i < pending.length(); i++) represented.add(pending.getString(i));
        }
        if (replies != null) {
            JSONArray children = replies.getJSONObject("data").getJSONArray("children");
            for (int i = 0; i < children.length(); i++) flatten(children.getJSONObject(i), out, represented, depth + 1);
        }
    }

    private static void append(StringBuilder out, String key, String value) {
        try {
            out.append(out.indexOf("?") < 0 ? '?' : '&');
            out.append(URLEncoder.encode(key, "UTF-8"));
            out.append('=');
            out.append(URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String commentsError(VolleyError error) {
        String message = "Error loading more comments";
        if (error == null) return message;
        String reason = app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch.getFailureReason(error);
        if (reason != null) return message + " (" + reason + ")";
        if (error.networkResponse != null) {
            return message + " (HTTP " + error.networkResponse.statusCode + ")";
        }
        Throwable cause = error.getCause();
        String kind = (cause == null ? error : cause).getClass().getSimpleName();
        return kind.isEmpty() ? message : message + " (" + kind + ")";
    }

    /** Diagnostic build: explicit user action copies only the bounded counter report. */
    public static void showCommentsError(android.content.Context context, VolleyError error) {
        final String details = commentsError(error) + "\n\n"
                + app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch.getDiagnosticDetails(error);
        try {
            new android.app.AlertDialog.Builder(context)
                    .setTitle("Comment loading details")
                    .setMessage(details)
                    .setPositiveButton("Copy details", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Sync loading details", details));
                            android.widget.Toast.makeText(context, "Details copied", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (RuntimeException unavailableDialog) {
            android.widget.Toast.makeText(context, details, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
