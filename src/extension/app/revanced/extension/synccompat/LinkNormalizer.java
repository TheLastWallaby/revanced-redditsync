package app.revanced.extension.synccompat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites equivalent Reddit links into the forms that Sync 23033's own link
 * classifiers already open natively. Anything it does not recognize is returned
 * unchanged, so Sync keeps its existing behavior for those links.
 */
public final class LinkNormalizer {
    private static final String WEB = "https://www.reddit.com";
    private static final Set<String> WEB_HOSTS = set("reddit.com", "www.reddit.com", "old.reddit.com",
            "new.reddit.com", "sh.reddit.com", "m.reddit.com", "amp.reddit.com", "np.reddit.com", "i.reddit.com");
    private static final Set<String> MEDIA_HOSTS = set("i.redd.it", "preview.redd.it",
            "external-preview.redd.it", "v.redd.it");
    private static final String SHORT_HOST = "redd.it";
    // Exact, unambiguous query parameters that only identify the share or campaign.
    private static final Set<String> TRACKING = set("share_id", "ref", "ref_source", "ref_campaign", "rdt",
            "correlation_id", "_branch_match_id", "_branch_referrer", "$deep_link", "$3p");
    private static final Set<String> SORTS = set("hot", "new", "rising", "top", "controversial");
    private static final Set<String> PROFILE_SECTIONS = set("overview", "submitted", "comments", "gilded");

    private static final Pattern ID = Pattern.compile("[a-z0-9]{1,13}");
    private static final Pattern SUBREDDIT = Pattern.compile("[A-Za-z0-9_]{1,21}");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern SHARE = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_%.~!$'()*+,;=:@-]{1,300}");
    private static final Pattern GALLERY = Pattern.compile("https://www\\.reddit\\.com/gallery/[a-z0-9]{1,13}");
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:.*", Pattern.DOTALL);
    private static final Pattern EMBEDDED_URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_PUNCTUATION = ".,;:!?'\")]}>";

    static final int MAX_INPUT = 4096;
    static final int MAX_WRAPPED = 2048;
    private static final int MAX_DECODE_ROUNDS = 2;

    private LinkNormalizer() {}

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /**
     * Returns the canonical link for a recognized Reddit post, comment, share, gallery,
     * profile, subreddit, short or media link. Shared text is reduced to its first
     * http(s) link. Never throws; unknown input is returned unchanged.
     */
    public static String normalize(String input) {
        try {
            if (input == null) return null;
            String trimmed = input.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_INPUT) return input;
            String candidate = trimmed;
            if (hasWhitespace(trimmed)) {
                candidate = firstUrl(trimmed);
                if (candidate == null) return input;
            }
            String result = canonical(candidate, false);
            if (result != null) return result;
            return candidate == trimmed ? input : candidate;
        } catch (RuntimeException unexpected) {
            return input;
        }
    }

    /** The canonical gallery form, which Sync's gallery request already understands. */
    public static boolean isGalleryLink(String value) {
        return value != null && GALLERY.matcher(value).matches();
    }

    private static boolean hasWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return true;
        return false;
    }

    private static String firstUrl(String text) {
        Matcher match = EMBEDDED_URL.matcher(text);
        if (!match.find()) return null;
        String url = match.group();
        int end = url.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) end--;
        return end == 0 ? null : url.substring(0, end);
    }

    /** @param wrapped true for a media-wrapper destination: only direct Reddit media is accepted. */
    private static String canonical(String value, boolean wrapped) {
        String absolute = value;
        if (!wrapped && value.startsWith("/") && !value.startsWith("//")) {
            absolute = WEB + value; // Reddit-relative link from Markdown.
        } else if (!SCHEME.matcher(value).matches()) {
            if (wrapped || !startsWithKnownHost(value)) return null;
            absolute = "https://" + value;
        }
        for (int i = 0; i < absolute.length(); i++) {
            char c = absolute.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') return null;
        }
        final URI uri;
        try {
            uri = new URI(absolute);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (uri.isOpaque() || host == null || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
        int port = uri.getPort();
        if (port != -1 && port != 443 && !(port == 80 && "http".equalsIgnoreCase(scheme))) return null;
        // Reject ambiguous authorities such as an encoded host, an empty port or a trailing dot.
        if (!(host + (port == -1 ? "" : ":" + port)).equalsIgnoreCase(uri.getRawAuthority())) return null;
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.contains("//")) return null;
        if (MEDIA_HOSTS.contains(host)) return media(host, path, uri.getRawQuery());
        if (wrapped) return null;
        if (WEB_HOSTS.contains(host)) return web(uri, host, segments(path));
        if (SHORT_HOST.equals(host)) return shortLink(uri, segments(path));
        return null;
    }

    private static boolean startsWithKnownHost(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        int slash = lower.indexOf('/');
        if (slash <= 0) return false;
        String host = lower.substring(0, slash);
        return WEB_HOSTS.contains(host) || MEDIA_HOSTS.contains(host) || SHORT_HOST.equals(host);
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) if (!segment.isEmpty()) result.add(segment);
        return result;
    }

    private static String web(URI uri, String host, List<String> path) {
        if (path.isEmpty()) return null;
        String first = path.get(0);
        if (path.size() == 1 && "media".equals(first)) return mediaWrapper(uri.getRawQuery());
        if (path.size() == 2 && "gallery".equals(first)) {
            String id = path.get(1).toLowerCase(Locale.ROOT);
            return ID.matcher(id).matches() ? WEB + "/gallery/" + id : null;
        }
        String subreddit = null;
        String user = null;
        int rest = 0;
        if (path.size() >= 2 && "r".equals(first)) {
            subreddit = path.get(1);
            if (!SUBREDDIT.matcher(subreddit).matches()) return null;
            rest = 2;
        } else if (path.size() >= 2 && ("u".equals(first) || "user".equals(first))) {
            user = path.get(1);
            if (!USER.matcher(user).matches()) return null;
            rest = 2;
        }
        List<String> tail = path.subList(rest, path.size());
        if (tail.isEmpty()) {
            if (subreddit != null) return WEB + "/r/" + subreddit;
            if (user != null) return WEB + "/user/" + user;
            return null;
        }
        String section = tail.get(0);
        if ("comments".equals(section) && tail.size() >= 2) {
            return comments(uri, host, subreddit, tail);
        }
        if ("s".equals(section) && tail.size() == 2 && (subreddit != null || user != null)) {
            if (!SHARE.matcher(tail.get(1)).matches()) return null; // Share tokens are case-sensitive.
            String base = WEB + (subreddit != null ? "/r/" + subreddit : "/u/" + user) + "/s/" + tail.get(1);
            String fragment = "bypass".equals(uri.getRawFragment()) ? "#bypass" : "";
            return base + query(uri.getRawQuery(), false) + fragment;
        }
        if (tail.size() == 1 && subreddit != null && SORTS.contains(section.toLowerCase(Locale.ROOT))) {
            // Sync's subreddit classifier accepts no query, so time ranges cannot be kept.
            return WEB + "/r/" + subreddit + "/" + section.toLowerCase(Locale.ROOT);
        }
        if (tail.size() == 1 && user != null && PROFILE_SECTIONS.contains(section.toLowerCase(Locale.ROOT))) {
            return WEB + "/user/" + user;
        }
        return null;
    }

    /** Builds /r/SUB/comments/POST/[_/COMMENT/] so Sync's string splits find each ID. */
    private static String comments(URI uri, String host, String subreddit, List<String> tail) {
        if (tail.size() > 4) return null;
        String post = tail.get(1).toLowerCase(Locale.ROOT);
        if (!ID.matcher(post).matches()) return null;
        if (tail.size() >= 3 && !SLUG.matcher(tail.get(2)).matches()) return null;
        String comment = null;
        if (tail.size() == 4) {
            comment = tail.get(3).toLowerCase(Locale.ROOT);
            if (!ID.matcher(comment).matches()) return null;
        }
        String rawQuery = uri.getRawQuery();
        // Sync's own gallery taps use this synthetic form; leave it to the gallery route.
        if (rawQuery != null && keys(rawQuery).contains("is_gallery")) return null;
        StringBuilder out = new StringBuilder("https://")
                .append("np.reddit.com".equals(host) ? "np.reddit.com" : "www.reddit.com");
        if (subreddit != null) out.append("/r/").append(subreddit);
        out.append("/comments/").append(post).append('/');
        if (comment != null) out.append("_/").append(comment).append('/');
        return out.append(query(rawQuery, false)).toString();
    }

    private static String shortLink(URI uri, List<String> path) {
        if (path.size() != 1) return null;
        String post = path.get(0).toLowerCase(Locale.ROOT);
        if (!ID.matcher(post).matches()) return null;
        return WEB + "/comments/" + post + "/" + query(uri.getRawQuery(), false);
    }

    private static String media(String host, String path, String rawQuery) {
        if (path.length() <= 1 || path.contains("/../") || path.endsWith("/..")) return null;
        // Signed CDN parameters stay byte-for-byte and in order.
        return "https://" + host + path + query(rawQuery, true);
    }

    /** Unwraps reddit.com/media?url=... to a validated direct Reddit media URL. */
    private static String mediaWrapper(String rawQuery) {
        if (rawQuery == null) return null;
        String wrapped = null;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && "url".equals(pair.substring(0, equals))) {
                if (wrapped != null) return null; // Ambiguous wrapper.
                wrapped = pair.substring(equals + 1);
            }
        }
        if (wrapped == null || wrapped.isEmpty() || wrapped.length() > MAX_WRAPPED) return null;
        String target = wrapped;
        for (int round = 0; round < MAX_DECODE_ROUNDS && !isHttpUrl(target); round++) {
            target = percentDecode(target);
            if (target == null) return null;
        }
        if (!isHttpUrl(target)) return null;
        return canonical(target, true);
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** Strict percent-decoding; '+' stays literal and malformed or control bytes are rejected. */
    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) return null;
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) return null;
                int b = (high << 4) | low;
                if (b < 0x20 || b == 0x7f) return null;
                bytes.write(b);
                i += 2;
            } else if (c > 0x7e || c <= 0x20) {
                return null;
            } else {
                bytes.write(c);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Set<String> keys(String rawQuery) {
        Set<String> result = new HashSet<>();
        for (String pair : rawQuery.split("&")) result.add(key(pair));
        return result;
    }

    private static String key(String pair) {
        int equals = pair.indexOf('=');
        String key = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
        while (key.startsWith("amp;")) key = key.substring(4); // HTML-escaped "&amp;" separators.
        String decoded = percentDecode(key);
        return decoded == null ? key : decoded;
    }

    private static boolean tracking(String key) {
        return key.startsWith("utm_") || TRACKING.contains(key);
    }

    /**
     * Keeps every non-tracking parameter in its original order. For media the raw text is
     * kept exactly; for Reddit pages it is re-encoded to the characters Sync's comment
     * classifier accepts.
     */
    private static String query(String rawQuery, boolean exact) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty() || tracking(key(pair))) continue;
            String kept = exact ? pair : safe(stripAmp(pair));
            if (kept.isEmpty()) continue;
            out.append(out.length() == 0 ? '?' : '&').append(kept);
        }
        return out.toString();
    }

    private static String stripAmp(String pair) {
        String result = pair;
        while (result.toLowerCase(Locale.ROOT).startsWith("amp;")) result = result.substring(4);
        return result;
    }

    private static String safe(String pair) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = pair.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            boolean escape = b == '%' && i + 2 < bytes.length
                    && Character.digit(bytes[i + 1], 16) >= 0 && Character.digit(bytes[i + 2], 16) >= 0;
            if (escape || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || "._-+=!:".indexOf(b) >= 0) {
                out.append((char) b);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
