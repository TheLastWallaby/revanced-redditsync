package app.revanced.extension.synccompat;

import java.util.Locale;
import org.json.JSONObject;

/**
 * Media hosts that have shut down. Sync 23033 still treats their links as media: it opens them in
 * its image viewer, builds image previews from them (picsarus as plain HTTP), and looks them up
 * through resolvers that no longer answer (vid.me, sli.mg, Eroshare's api.eroshare.com,
 * Tumblr's api.tumblr.com/v2/blog/, and DeviantArt's plain-HTTP oEmbed).
 *
 * <p>The patch "Sync: skip retired media hosts" turns off Sync's vid.me, sli.mg, DeviantArt and
 * Tumblr-post classifiers outright and asks {@link #isRetired} before Sync's media classifier,
 * post-type helper and image-URL helper. A retired link is then an ordinary link: a link card that
 * opens the original URL in the browser, as Sync already does for links it does not recognize.
 */
public final class RetiredMediaHosts {
    /** Hosts whose media service is gone, with all their subdomains (www., m., i., ...). */
    private static final String[] HOSTS = {"vid.me", "sli.mg", "eroshare.com", "picsarus.com"};
    /** Sync 23033's post kind for an ordinary link post (SyncTypeHelper.h). */
    static final int LINK_POST = 3;

    private RetiredMediaHosts() {
    }

    /** True if the link is an http(s) or scheme-less link to a retired media host. */
    public static boolean isRetired(String link) {
        try {
            String host = host(link);
            if (host == null) return false;
            for (String retired : HOSTS) {
                if (host.equals(retired) || host.endsWith("." + retired)) return true;
            }
            return false;
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    /**
     * Sync's post kind for a link post whose URL is on a retired host (an ordinary link), or -1 to
     * let Sync decide. Sync would otherwise call such a post an image or GIF from its file
     * extension and load a preview from the dead host.
     */
    public static int postType(JSONObject post, int kind) {
        try {
            if (kind != LINK_POST || post == null) return -1;
            return isRetired(post.optString("url", null)) ? LINK_POST : -1;
        } catch (RuntimeException unexpected) {
            return -1;
        }
    }

    /** The lower-case host of an http(s) or scheme-less link, or null. */
    static String host(String link) {
        if (link == null) return null;
        String rest = link.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            String name = rest.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (!name.equals("http") && !name.equals("https")) return null;
            rest = rest.substring(scheme + 3);
        } else if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        int end = rest.length();
        for (char delimiter : new char[] {'/', '?', '#', '\\'}) {
            int at = rest.indexOf(delimiter);
            if (at >= 0 && at < end) end = at;
        }
        String authority = rest.substring(0, end);
        int user = authority.lastIndexOf('@');
        // Without a scheme, "name:x@host" is another URI scheme (mailto:), not a web link.
        if (user >= 0 && scheme < 0) return null;
        if (user >= 0) authority = authority.substring(user + 1);
        int port = authority.indexOf(':');
        if (port >= 0) {
            String digits = authority.substring(port + 1);
            for (int i = 0; i < digits.length(); i++) {
                if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return null;
            }
            authority = authority.substring(0, port);
        }
        while (authority.endsWith(".")) authority = authority.substring(0, authority.length() - 1);
        if (authority.isEmpty()) return null;
        return authority.toLowerCase(Locale.ROOT);
    }
}
