package app.revanced.extension.synccompat;

import java.util.Locale;

/**
 * Sync 23033 reads a RedGifs video ID with LinkHandler.d (y7.a.d, "getGfycatId"): it takes the
 * text after the last "/" and then drops anything from "?" or "#". A link with a slash before its
 * query or fragment, such as {@code https://www.redgifs.com/watch/abcdef/?92}, therefore yields an
 * empty ID, and every RedGifs lookup (full screen, peek, download, enhanced autoplay) fails.
 *
 * <p>The patch passes the link through {@link #normalize} first, so Sync's own parser sees the
 * path only. Only RedGifs links change; the result is the ID Sync already read from links without
 * that slash.
 */
public final class RedgifsLinkCompat {
    private RedgifsLinkCompat() {
    }

    /** A RedGifs link without its query, fragment and trailing slashes; any other value unchanged. */
    public static String normalize(String link) {
        try {
            if (link == null || !link.toLowerCase(Locale.ROOT).contains("redgifs.com")) return link;
            int end = link.length();
            int query = link.indexOf('?');
            if (query >= 0) end = query;
            int fragment = link.indexOf('#');
            if (fragment >= 0 && fragment < end) end = fragment;
            while (end > 0 && link.charAt(end - 1) == '/') end--;
            String path = link.substring(0, end);
            // Keep Sync's own handling if nothing of the path remains.
            return path.isEmpty() || path.endsWith(":") ? link : path;
        } catch (RuntimeException unexpected) {
            return link;
        }
    }
}
