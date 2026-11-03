package app.revanced.extension.synccompat;

import java.util.Locale;

/**
 * YouTube links for Android's ordinary link handling instead of Sync's embedded player.
 *
 * <p>Sync 23033 plays YouTube links with the retired YouTube Android Player API
 * ({@code CustomYouTubeVideoActivity}). That library binds to a service the YouTube app no longer
 * provides, so it fails with SERVICE_MISSING ("Get YouTube App", then "An error occurred while
 * initializing the YouTube player"). The patch "Sync: open YouTube in the YouTube app" hands the
 * link to Sync's own external opener ({@code LinkHandler.X}, a plain ACTION_VIEW), which Android
 * gives to the YouTube app when it is installed and enabled, and to the browser otherwise. Sync's
 * manifest claims no YouTube host, so the link never comes back to Sync.
 *
 * <p>{@link #appUrl} keeps the link as it was except where the YouTube app could not accept it:
 * a missing scheme gets https, the scheme and host become lower case (Android matches intent hosts
 * case-sensitively), {@code m.youtube.com} becomes {@code www.youtube.com}, and an HTML-escaped
 * {@code &amp;amp;} in the query becomes {@code &amp;}. Embed forms ({@code /embed/}, {@code /v/},
 * {@code /e/}, and anything on {@code youtube-nocookie.com}) become a standard watch or playlist
 * URL, keeping the video ID, timestamp ({@code t}, or {@code start} as {@code t}), {@code list}
 * and {@code index}. Nothing is logged.
 */
public final class YouTubeLinks {
    private static final int MAX_LENGTH = 4096;

    private YouTubeLinks() {
    }

    /**
     * The link to open with ACTION_VIEW, or null if it is not on a YouTube host (Sync then keeps
     * its own handling). Never throws.
     */
    public static String appUrl(String link) {
        try {
            Parsed p = parse(link);
            if (p == null || !isYouTubeHost(p.host)) return null;
            String rebuilt = rebuild(p);
            if (rebuilt != null) return rebuilt;
            String host = p.host.equals("m.youtube.com") ? "www.youtube.com" : p.host;
            StringBuilder out = new StringBuilder(link.length() + 8);
            out.append(p.scheme == null ? "https" : p.scheme).append("://").append(host);
            if (p.port != null) out.append(':').append(p.port);
            out.append(p.path);
            if (p.query != null) out.append('?').append(p.query);
            if (p.fragment != null) out.append('#').append(p.fragment);
            return out.toString();
        } catch (RuntimeException unexpected) {
            return null;
        }
    }

    /**
     * True for a link to one YouTube video or embedded playlist that Sync's own YouTube classifier
     * passes over: Shorts, youtube-nocookie.com embeds, and watch links whose text contains
     * "playlist". Channels, playlist pages, clips and other pages are not video links.
     */
    public static boolean isVideoLink(String link) {
        try {
            Parsed p = parse(link);
            return p != null && isYouTubeHost(p.host) && target(p) != null;
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    // ---- Targets ----

    /** {"v", id} for a video, {"list", id} for an embedded playlist, or null. */
    static String[] target(Parsed p) {
        String path = p.path;
        if (p.host.equals("youtu.be")) {
            String id = segment(path, "/");
            return isVideoId(id) ? new String[] {"v", id} : null;
        }
        if (path.equals("/watch") || path.equals("/watch/")) {
            String id = parameter(p.query, "v");
            return isVideoId(id) ? new String[] {"v", id} : null;
        }
        for (String prefix : new String[] {"/shorts/", "/live/", "/embed/", "/v/", "/e/"}) {
            String id = segment(path, prefix);
            if (id == null) continue;
            if (prefix.equals("/embed/") && id.equals("videoseries")) {
                String list = parameter(p.query, "list");
                return isListId(list) ? new String[] {"list", list} : null;
            }
            return isVideoId(id) ? new String[] {"v", id} : null;
        }
        return null;
    }

    /** A standard watch or playlist URL for embed forms and youtube-nocookie.com, else null. */
    private static String rebuild(Parsed p) {
        boolean nocookie = p.host.equals("youtube-nocookie.com") || p.host.equals("www.youtube-nocookie.com");
        boolean embed = p.path.startsWith("/embed/") || p.path.startsWith("/v/") || p.path.startsWith("/e/");
        if (!nocookie && !(embed && !p.host.equals("youtu.be"))) return null;
        String[] target = target(p);
        if (target == null) return null;
        if (target[0].equals("list")) return "https://www.youtube.com/playlist?list=" + target[1];
        StringBuilder out = new StringBuilder("https://www.youtube.com/watch?v=").append(target[1]);
        String time = parameter(p.query, "t");
        if (!isTime(time)) {
            String start = parameter(p.query, "start");
            time = start != null && start.matches("[0-9]{1,6}") ? start : null;
        }
        if (time == null && p.fragment != null && p.fragment.startsWith("t=")) time = p.fragment.substring(2);
        if (isTime(time)) out.append("&t=").append(time);
        String list = parameter(p.query, "list");
        if (isListId(list)) {
            out.append("&list=").append(list);
            String index = parameter(p.query, "index");
            if (index != null && index.matches("[0-9]{1,5}")) out.append("&index=").append(index);
        }
        return out.toString();
    }

    static boolean isYouTubeHost(String host) {
        return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")
                || host.equals("youtube-nocookie.com") || host.equals("www.youtube-nocookie.com");
    }

    private static boolean isVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    private static boolean isListId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{2,64}");
    }

    /** YouTube start times: 90, 90s, 1h2m3s, 2m, ... */
    private static boolean isTime(String value) {
        return value != null && !value.isEmpty()
                && (value.matches("[0-9]{1,6}s?") || value.matches("([0-9]{1,3}h)?([0-9]{1,4}m)?([0-9]{1,6}s)?"));
    }

    /** The single path segment after prefix (an optional trailing slash allowed), or null. */
    private static String segment(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        String rest = path.substring(prefix.length());
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return rest.isEmpty() || rest.indexOf('/') >= 0 ? null : rest;
    }

    /** The first value of a query parameter, or null. Values are used only after validation. */
    private static String parameter(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) return equals < 0 ? "" : pair.substring(equals + 1);
        }
        return null;
    }

    // ---- Parsing ----

    static final class Parsed {
        String scheme;   // lower case, or null when the link had none
        String host;     // lower case
        String port;     // null for none
        String path;     // "" or starting with "/"
        String query;    // without "?", "&amp;" decoded; null for none
        String fragment; // without "#"; null for none
    }

    /** An http(s) or scheme-less web link without user-info, or null. */
    static Parsed parse(String link) {
        if (link == null) return null;
        String rest = link.trim();
        if (rest.isEmpty() || rest.length() > MAX_LENGTH) return null;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c <= ' ' || c == 0x7f || c == '\\') return null;
        }
        Parsed p = new Parsed();
        int separator = rest.indexOf("://");
        if (separator >= 0) {
            String scheme = rest.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            p.scheme = scheme;
            rest = rest.substring(separator + 3);
        } else if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(c);
            if (at >= 0 && at < end) end = at;
        }
        String authority = rest.substring(0, end);
        rest = rest.substring(end);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        if (colon >= 0) {
            String port = authority.substring(colon + 1);
            if (!port.equals("443") && !port.equals("80")) return null;
            p.port = port;
        }
        if (!host.matches("[A-Za-z0-9.-]+")) return null;
        p.host = host.toLowerCase(Locale.ROOT);
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            p.fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        if (question >= 0) {
            p.query = rest.substring(question + 1).replace("&amp;", "&");
            rest = rest.substring(0, question);
        }
        p.path = rest;
        return p;
    }
}
