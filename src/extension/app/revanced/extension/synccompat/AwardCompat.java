package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.VolleyError;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Award metadata compatibility for Sync v23.06.30-13:39.
 *
 * Sync reads legacy award fields (all_awardings, icon arrays) in two places: the
 * post/comment listing parser and the awards sheet's /api/info lookup. Missing or
 * changed fields used to throw. Here they are optional: listings stay usable, and the
 * sheet tells apart a failed lookup, an empty list and unavailable award data. When
 * Reddit doesn't list awards, the sheet offers the item on reddit.com instead.
 */
public final class AwardCompat {
    static final String LOAD_FAILED = "Couldn't load awards from Reddit.";
    static final String UNREADABLE = "Couldn't read Reddit's award details.";
    static final String NOT_FOUND = "Couldn't find this item's awards on Reddit.";
    static final String UNAVAILABLE = "Reddit didn't provide award details for this item.";
    static final String NONE = "Reddit lists no awards for this item.";
    static final String OPEN_UNAVAILABLE = "Award details unavailable. Open on Reddit";
    static final String OPEN_NONE = "No awards listed. Open on Reddit";

    static final int MAX_AWARDS = 100;
    static final int MAX_ICONS = 16;
    static final int MAX_TEXT = 500;
    static final int MAX_COUNT = 1_000_000;

    private static final Pattern FULLNAME = Pattern.compile("t([13])_([a-z0-9]{1,16})");
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

    /** Replaces OAuthAwardsRequest.parseNetworkResponse's body (y8.c, GET /api/info/?id=). */
    public static Result parseAwards(NetworkResponse response, String requestUrl) {
        try {
            if (response == null || response.data == null) return fail(LOAD_FAILED);
            Object root = parseJson(new String(response.data, StandardCharsets.UTF_8));
            if (!(root instanceof JSONObject)) return fail(UNREADABLE);
            JSONObject data = ((JSONObject) root).optJSONObject("data");
            JSONArray children = data == null ? null : data.optJSONArray("children");
            if (children == null) return fail(UNREADABLE);
            String requested = requestedFullname(requestUrl);
            JSONObject item = null;
            for (int i = 0; i < children.length() && item == null; i++) {
                JSONObject child = children.optJSONObject(i);
                JSONObject candidate = child == null ? null : child.optJSONObject("data");
                if (candidate == null) continue;
                // Only the requested item counts; never show another post's awards.
                if (requested == null || requested.equals(lower(text(candidate, "name")))) item = candidate;
            }
            if (item == null) return fail(NOT_FOUND);
            String open = openUrl(item, requested);
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

    private static Object parseJson(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception malformed) {
            return null;
        }
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

    static String requestedFullname(String url) {
        if (url == null) return null;
        int query = url.indexOf('?');
        if (query < 0) return null;
        for (String pair : url.substring(query + 1).split("[&#]")) {
            if (!pair.startsWith("id=")) continue;
            String id = lower(pair.substring(3));
            return FULLNAME.matcher(id).matches() ? id : null;
        }
        return null;
    }

    /** The item's reddit.com page, checked against the requested ID, or null. */
    static String openUrl(JSONObject item, String requested) {
        String permalink = text(item, "permalink");
        Matcher full = requested == null ? null : FULLNAME.matcher(requested);
        if (full == null || !full.matches() || permalink == null) return null;
        boolean post = "3".equals(full.group(1));
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
        if (post ? !parts[3].equals(id) : length != 6 || !parts[5].equals(id)) return null;
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
        NetworkResponse response = error == null ? null : error.networkResponse;
        if (response != null && response.statusCode >= 400 && response.statusCode < 600) {
            return "Couldn't load awards from Reddit (HTTP " + response.statusCode + ").";
        }
        return LOAD_FAILED;
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
