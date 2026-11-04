package app.revanced.extension.synccompat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URLs for Sync's long-press image peek (ImagePeekDialogFragment).
 *
 * Sync requests Reddit's default JSON (no raw_json), so a post's thumbnail and preview URLs keep
 * Reddit's HTML escaping: "?width=140&amp;height=140&amp;...&amp;s=...". Reddit's image CDN
 * signs the whole query, so the escaped form is refused (403) and Glide reports "Glide failed to
 * grab this image". Sync's feed loader is already fixed by the bundled "Fix post thumbnails"
 * port; the peek hands the same strings to Glide directly.
 *
 * Text posts with a picture have no preview block, only a 140px preview.redd.it thumbnail of the
 * picture. The peek shows the picture's own link from the post body instead, the same signed
 * link the opened post loads, when it names the same Reddit media ID.
 */
public final class ImagePeekCompat {
    private ImagePeekCompat() {}

    /** Bodies are at most 40,000 characters on Reddit; anything far longer is not searched. */
    static final int MAX_BODY = 100_000;

    private static final Pattern BODY_LINK = Pattern.compile(
            "https://(?:preview|i)\\.redd\\.it/[^\\s<>\"'()\\[\\]\\\\]+", Pattern.CASE_INSENSITIVE);
    // PostParserHelper appends "&height=<y>" to signed body images; Reddit refuses that form.
    private static final Pattern SYNC_HEIGHT = Pattern.compile("(&s=[0-9A-Fa-f]+)&height=[0-9]+$");
    private static final Pattern MEDIA_ID = Pattern.compile("[a-z0-9]{5,20}");
    private static final Pattern FILE = Pattern.compile("/([A-Za-z0-9_-]{1,300})\\.([A-Za-z0-9]{2,5})");
    private static final String TRAILING_PUNCTUATION = ".,;:!?*_~";

    /** Reverses Reddit's HTML escaping of '&' in an image URL. Null and other input pass through. */
    public static String imageUrl(String url) {
        if (url == null || !url.contains("&amp;")) return url;
        return url.replace("&amp;", "&");
    }

    /**
     * The URL the peek shows for a text post: the body's link to the picture that current (the
     * post's preview or thumbnail) shows, else current with its escaping undone. Never throws.
     */
    public static String textPostImage(String body, String current) {
        String url = imageUrl(current);
        try {
            String id = mediaId(url);
            if (id == null || body == null || body.isEmpty()) return url;
            String text = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
            Matcher match = BODY_LINK.matcher(text);
            while (match.find()) {
                String link = bodyLink(match.group());
                if (link != null && id.equals(mediaId(link))) return link;
            }
        } catch (RuntimeException unexpected) {
            // Keep the peek's own URL.
        }
        return url;
    }

    /** A picture link from the body, or null for one the peek cannot load as an image. */
    static String bodyLink(String found) {
        String link = imageUrl(found);
        int end = link.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(link.charAt(end - 1)) >= 0) end--;
        link = SYNC_HEIGHT.matcher(link.substring(0, end)).replaceFirst("$1");
        URI uri = uri(link);
        if (uri == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        // preview.redd.it serves only signed links; a format=mp4 rendition is a video, not a picture.
        if ("preview.redd.it".equals(host) && !hasParameter(query, "s")) return null;
        if (hasValue(query, "format", "mp4") || uri.getRawPath().toLowerCase(Locale.ROOT).endsWith(".mp4")) return null;
        return link;
    }

    /**
     * Reddit's media ID for an i.redd.it or preview.redd.it file ("ID.ext", or "title-v0-ID.ext"
     * on preview.redd.it), lower case; null for anything else.
     */
    static String mediaId(String url) {
        URI uri = uri(url);
        if (uri == null) return null;
        Matcher file = FILE.matcher(uri.getRawPath());
        if (!file.matches()) return null;
        String name = file.group(1);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("preview.redd.it".equals(host)) name = name.substring(name.lastIndexOf('-') + 1);
        else if (!"i.redd.it".equals(host)) return null;
        String id = name.toLowerCase(Locale.ROOT);
        return MEDIA_ID.matcher(id).matches() ? id : null;
    }

    private static URI uri(String value) {
        if (value == null || value.length() > 4096) return null;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawPath() == null) return null;
            String lower = host.toLowerCase(Locale.ROOT);
            return "i.redd.it".equals(lower) || "preview.redd.it".equals(lower) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean hasParameter(String query, String name) {
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(name) && equals + 1 < part.length()) return true;
        }
        return false;
    }

    private static boolean hasValue(String query, String name, String value) {
        for (String part : query.split("&")) if (part.equals(name + "=" + value)) return true;
        return false;
    }
}
