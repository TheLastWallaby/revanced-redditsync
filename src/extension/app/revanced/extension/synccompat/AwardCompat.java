package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.VolleyError;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Award metadata compatibility for Sync v23.06.30-13:39.
 *
 * Sync reads legacy award fields (all_awardings, icon arrays) in two places: the
 * post/comment listing parser and the awards sheet's lookup. Missing or changed fields
 * used to throw. Here they are optional: listings stay usable, and the sheet tells apart
 * a failed lookup, an empty list and unavailable award data. When Reddit doesn't list
 * awards, the sheet offers the item on reddit.com instead.
 * <p>
 * The sheet's lookup no longer uses /api/info (issue #48). A post is read from
 * /comments/{post}.json and a comment from /comments/{post}/comment/{comment}.json,
 * built only from validated IDs. Sync's comment sheet knows only the comment ID, so
 * its post ID is recorded from the comment Sync opens the sheet for. Without a valid
 * item (or a comment's post) no request is made and the sheet reports it.
 */
public final class AwardCompat {
    static final String LOAD_FAILED = "Couldn't load awards from Reddit.";
    static final String UNREADABLE = "Couldn't read Reddit's award details.";
    static final String NOT_FOUND = "Couldn't find this item's awards on Reddit.";
    static final String UNAVAILABLE = "Reddit didn't provide award details for this item.";
    static final String NONE = "Reddit lists no awards for this item.";
    static final String OPEN_UNAVAILABLE = "Award details unavailable. Open on Reddit";
    static final String OPEN_NONE = "No awards listed. Open on Reddit";
    static final String UNIDENTIFIED = "Couldn't identify this item for Reddit's award lookup. Reopen it and try again.";

    static final int MAX_AWARDS = 100;
    static final int MAX_ICONS = 16;
    static final int MAX_TEXT = 500;
    static final int MAX_COUNT = 1_000_000;
    static final int MAX_PARENTS = 64;

    private static final String API = "https://oauth.reddit.com";
    /**
     * Returned instead of a lookup URL when the item can't be identified. It is not an
     * HTTP(S) URL, so Sync's network stack fails it before any connection and reports a
     * VolleyError whose cause names this scheme; errorMessage turns that into UNIDENTIFIED.
     */
    static final String UNIDENTIFIED_SCHEME = "sync-compat-unidentified";
    static final String UNIDENTIFIED_URL = UNIDENTIFIED_SCHEME + ":awards";

    private static final Pattern FULLNAME = Pattern.compile("t([13])_([a-z0-9]{1,16})");
    private static final Pattern LOOKUP = Pattern.compile(
            "/comments/([a-z0-9]{1,16})(?:/comment/([a-z0-9]{1,16}))?\\.json");
    /** Comment ID to post ID, recorded when Sync opens a comment's awards sheet. */
    private static final Map<String, String> PARENTS = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_PARENTS;
        }
    };
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_%.~-]{1,300}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9]{1,16}");
    private static final String[] ICON_HOSTS = {"redd.it", "redditstatic.com", "redditmedia.com", "reddit.com"};

    private AwardCompat() {}

    /** Either awards for the sheet or an error to report. */
    public static final class Result {
        public final o8.a[] awards;
        public final VolleyError error;

        Result(o8.a[] awards, VolleyError error) {
            this.awards = awards;
            this.error = error;
        }
    }

    /** A lookup that reached Reddit but has nothing Sync can show; the message is shown as is. */
    public static final class AwardsError extends VolleyError {
        AwardsError(String message) {
            super(message);
        }
    }

    private static Result fail(String message) {
        return new Result(null, new AwardsError(message));
    }

    /**
     * Replaces OAuthAwardsRequest.b (y8.c), which built https://oauth.reddit.com/api/info/?id=
     * from Sync's "t3_"/"t1_" prefix and the item's ID.
     */
    public static String awardsUrl(String prefix, String id) {
        Matcher full = prefix == null || id == null ? null : FULLNAME.matcher(lower(prefix + id));
        if (full == null || !full.matches()) return UNIDENTIFIED_URL;
        String item = full.group(2);
        if ("3".equals(full.group(1))) return API + "/comments/" + item + ".json?limit=1&depth=1";
        String post;
        synchronized (PARENTS) {
            post = PARENTS.get(item);
        }
        return post == null ? UNIDENTIFIED_URL
                : API + "/comments/" + post + "/comment/" + item + ".json?context=0&limit=1&depth=1";
    }

    /**
     * Called where Sync opens a comment's awards sheet, with the comment's ID and its
     * link_id (Post.U and Post.j0, bare or prefixed). Invalid IDs are ignored.
     */
    public static void rememberComment(String comment, String post) {
        comment = bareId(comment, "t1_");
        post = bareId(post, "t3_");
        if (comment == null || post == null) return;
        synchronized (PARENTS) {
            PARENTS.put(comment, post);
        }
    }

    private static String bareId(String value, String prefix) {
        if (value == null) return null;
        String id = lower(value.trim());
        if (id.startsWith(prefix)) id = id.substring(prefix.length());
        return ID.matcher(id).matches() ? id : null;
    }

    /** Replaces OAuthAwardsRequest.parseNetworkResponse's body (y8.c); the response is a thread listing. */
    public static Result parseAwards(NetworkResponse response, String requestUrl) {
        try {
            if (response == null || response.data == null) return fail(LOAD_FAILED);
            Object root = parseJson(new String(response.data, StandardCharsets.UTF_8));
            JSONArray thread = root instanceof JSONArray ? (JSONArray) root : null;
            JSONArray posts = thread == null || thread.length() != 2 ? null : children(thread.optJSONObject(0));
            JSONArray comments = posts == null ? null : children(thread.optJSONObject(1));
            if (comments == null) return fail(UNREADABLE);
            String requested = requestedFullname(requestUrl), post = requestedPost(requestUrl);
            if (requested == null || post == null) return fail(NOT_FOUND);
            // Only the requested item counts; never show another post's or comment's awards.
            JSONObject postItem = find(posts, "t3_" + post);
            JSONObject item = requested.startsWith("t3_") ? postItem : postItem == null ? null : find(comments, requested);
            if (item == null) return fail(NOT_FOUND);
            String open = openUrl(item, requested, post);
            Object awardings = item.opt("all_awardings");
            if (awardings instanceof JSONArray) {
                JSONArray list = (JSONArray) awardings;
                o8.a[] awards = awards(list);
                if (awards.length > 0) return new Result(awards, null);
                if (list.length() == 0) return open == null ? fail(NONE) : handoff(OPEN_NONE, open);
            }
            // Absent, null or an unsupported shape: unavailable, not "no awards".
            return open == null ? fail(UNAVAILABLE) : handoff(OPEN_UNAVAILABLE, open);
        } catch (Exception | StackOverflowError unexpected) {
            return fail(UNREADABLE);
        }
    }

    /** One complete JSON value with nothing after it, or null. */
    private static Object parseJson(String text) {
        try {
            JSONTokener tokens = new JSONTokener(text);
            Object value = tokens.nextValue();
            return tokens.nextClean() == 0 ? value : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** The children of a Listing object, or null for any other shape. */
    private static JSONArray children(JSONObject listing) {
        JSONObject data = listing == null || !"Listing".equals(listing.optString("kind")) ? null : listing.optJSONObject("data");
        return data == null ? null : data.optJSONArray("children");
    }

    /** The data of the top-level child whose kind and name match fullname, ignoring case. */
    private static JSONObject find(JSONArray children, String fullname) {
        String kind = fullname.substring(0, 2);
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            JSONObject data = child == null || !kind.equals(child.optString("kind")) ? null : child.optJSONObject("data");
            if (data != null && fullname.equals(lower(text(data, "name")))) return data;
        }
        return null;
    }

    private static Result handoff(String label, String url) {
        o8.a award = new o8.a();
        // Real awards always have a count of at least 1; 0 marks this row.
        award.a = 0;
        award.b = label;
        award.c = "";
        award.q = url;
        award.r = null;
        return new Result(new o8.a[] {award}, null);
    }

    static o8.a[] awards(JSONArray list) {
        List<o8.a> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < MAX_AWARDS; i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry == null) continue;
            String name = text(entry, "name");
            if (name == null) continue;
            o8.a award = new o8.a();
            award.a = count(entry.opt("count"));
            award.b = name;
            String description = text(entry, "description");
            award.c = description == null ? "" : description;
            String icon = iconUrl(entry.opt("icon_url"));
            String resized = lastIcon(entry.optJSONArray("resized_icons"));
            if (resized == null) resized = lastIcon(entry.optJSONArray("resized_static_icons"));
            award.q = icon != null ? icon : resized;
            award.r = resized != null ? resized : icon;
            out.add(award);
        }
        return out.toArray(new o8.a[0]);
    }

    static int count(Object value) {
        long count = 1;
        if (value instanceof Integer || value instanceof Long) count = ((Number) value).longValue();
        else if (value instanceof String && ((String) value).matches("[0-9]{1,9}")) count = Long.parseLong((String) value);
        return (int) Math.max(1, Math.min(MAX_COUNT, count));
    }

    /** Trimmed string field, or null when absent, not a string, blank or oversized. */
    static String text(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.isEmpty() || text.length() > MAX_TEXT ? null : text;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String lastIcon(JSONArray icons) {
        if (icons == null) return null;
        for (int i = Math.min(icons.length(), MAX_ICONS) - 1; i >= 0; i--) {
            JSONObject icon = icons.optJSONObject(i);
            String url = icon == null ? null : iconUrl(icon.opt("url"));
            if (url != null) return url;
        }
        return null;
    }

    /** An unescaped HTTPS image URL on a Reddit image host, or null. */
    static String iconUrl(Object value) {
        if (!(value instanceof String)) return null;
        String url = ((String) value).trim().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        if (url.isEmpty() || url.length() > 2048) return null;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null) return null;
            if (uri.getPort() != -1 && uri.getPort() != 443) return null;
            host = host.toLowerCase(Locale.ROOT);
            for (String allowed : ICON_HOSTS) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) return url;
            }
        } catch (Exception invalid) {
            // Treated as no icon.
        }
        return null;
    }

    /** The lookup's path, from a URL built by awardsUrl, or null. */
    private static Matcher lookup(String url) {
        try {
            URI uri = url == null ? null : new URI(url);
            Matcher path = uri == null || !"https".equals(uri.getScheme()) || !"oauth.reddit.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawPath() == null
                    ? null : LOOKUP.matcher(uri.getRawPath());
            return path != null && path.matches() ? path : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    /** t3_{post} or t1_{comment} for a lookup URL built by awardsUrl, or null. */
    static String requestedFullname(String url) {
        Matcher path = lookup(url);
        return path == null ? null : path.group(2) == null ? "t3_" + path.group(1) : "t1_" + path.group(2);
    }

    /** The post ID of a lookup URL built by awardsUrl, or null. */
    static String requestedPost(String url) {
        Matcher path = lookup(url);
        return path == null ? null : path.group(1);
    }

    /** The item's reddit.com page, checked against the requested ID, or null. */
    static String openUrl(JSONObject item, String requested) {
        return openUrl(item, requested, null);
    }

    /** As above; a comment's page must also belong to the requested post when one is given. */
    static String openUrl(JSONObject item, String requested, String post) {
        String permalink = text(item, "permalink");
        Matcher full = requested == null ? null : FULLNAME.matcher(requested);
        if (full == null || !full.matches() || permalink == null) return null;
        boolean isPost = "3".equals(full.group(1));
        String id = full.group(2);
        if (!permalink.startsWith("/") || permalink.contains("//")) return null;
        String[] parts = permalink.substring(1).split("/", -1);
        int length = parts.length;
        if (length > 0 && parts[length - 1].isEmpty()) length--;
        // /r|u|user/<name>/comments/<post>[/<slug>[/<comment>]]
        if (length < 4 || length > 6) return null;
        if (!("r".equals(parts[0]) || "u".equals(parts[0]) || "user".equals(parts[0]))) return null;
        if (!NAME.matcher(parts[1]).matches() || !"comments".equals(parts[2])) return null;
        if (!ID.matcher(parts[3]).matches()) return null;
        for (int i = 4; i < length; i++) {
            if (!SEGMENT.matcher(parts[i]).matches() || parts[i].matches("\\.+")) return null;
        }
        if (isPost ? !parts[3].equals(id) : length != 6 || !parts[5].equals(id)) return null;
        if (post != null && !parts[3].equals(post)) return null;
        return "https://www.reddit.com" + permalink;
    }

    /** The reddit.com URL for a selected "Open on Reddit" row, or null for a real award. */
    public static String handoffUrl(Object selected) {
        if (!(selected instanceof o8.a)) return null;
        o8.a award = (o8.a) selected;
        if (award.a != 0 || award.q == null || !award.q.startsWith("https://www.reddit.com/")) return null;
        return award.q;
    }

    /** Message for the awards sheet's error toast. */
    public static String errorMessage(VolleyError error) {
        if (error instanceof AwardsError && error.getMessage() != null) return error.getMessage();
        if (unidentified(error)) return UNIDENTIFIED;
        NetworkResponse response = error == null ? null : error.networkResponse;
        // Reddit answers a missing or removed post with a JSON 404.
        if (response != null && response.statusCode == 404) return NOT_FOUND;
        if (response != null && response.statusCode >= 400 && response.statusCode < 600) {
            return "Couldn't load awards from Reddit (HTTP " + response.statusCode + ").";
        }
        return LOAD_FAILED;
    }

    /** Whether the network stack refused UNIDENTIFIED_URL (OkHttp names its scheme; Volley the URL). */
    static boolean unidentified(Throwable error) {
        for (int i = 0; i < 8 && error != null; i++, error = error.getCause()) {
            String message = error.getMessage();
            if (message != null && message.contains(UNIDENTIFIED_SCHEME)) return true;
        }
        return false;
    }

    /** gildings guard for the listing parser: Sync calls getJSONObject when has() is true. */
    public static boolean hasObject(JSONObject object, String key) {
        try {
            return object != null && object.optJSONObject(key) != null;
        } catch (Exception unexpected) {
            return false;
        }
    }

    /**
     * Listing parser input for all_awardings. Sync requires every entry to be an object with
     * resized_static_icons and resized_icons arrays of objects with a url, and throws otherwise.
     * Only entries and icons Sync can use are kept; the parser's size choice is unchanged.
     */
    public static JSONArray listingAwardings(JSONArray awardings) {
        if (awardings == null) return null;
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < awardings.length() && out.length() < MAX_AWARDS; i++) {
                JSONObject entry = awardings.optJSONObject(i);
                if (entry == null) continue;
                JSONObject safe = new JSONObject();
                safe.put("resized_static_icons", listingIcons(entry.optJSONArray("resized_static_icons")));
                safe.put("resized_icons", listingIcons(entry.optJSONArray("resized_icons")));
                out.put(safe);
            }
            return out;
        } catch (Exception unexpected) {
            return new JSONArray();
        }
    }

    private static JSONArray listingIcons(JSONArray icons) throws Exception {
        JSONArray out = new JSONArray();
        if (icons == null) return out;
        for (int i = 0; i < icons.length() && i < MAX_ICONS; i++) {
            JSONObject icon = icons.optJSONObject(i);
            Object url = icon == null ? null : icon.opt("url");
            // Keep the original text; Sync unescapes it itself.
            if (iconUrl(url) != null) out.put(new JSONObject().put("url", url));
        }
        return out;
    }
}
