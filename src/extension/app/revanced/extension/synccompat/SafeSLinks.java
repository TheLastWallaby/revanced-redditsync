package app.revanced.extension.synccompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Origin checks and bounded transport for the inherited Reddit /s/ resolver. */
public final class SafeSLinks {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com",
            "sh.reddit.com", "m.reddit.com", "np.reddit.com"));
    // Subreddit and profile share links; the token is the last segment.
    private static final Pattern SHARE_PATH = Pattern.compile(
            "/(?:r/[A-Za-z0-9_]+|u(?:ser)?/[A-Za-z0-9_-]+)/s/[A-Za-z0-9]+/?");
    private static final int MAX_REQUESTS = 5;
    // Per-request socket timeouts, within one deadline for the whole redirect chain.
    // Not final, so tests can shorten them.
    private static int TIMEOUT_MS = 4000;
    private static long DEADLINE_MS = 8000;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private static final ConnectionFactory CONNECTIONS = url -> (HttpURLConnection) url.openConnection();

    private SafeSLinks() {}

    private static IOException failure() {
        // Do not put URLs, Location values, tokens, headers or transport errors in diagnostics.
        return new IOException("Reddit share link could not be resolved safely");
    }

    private static URI trustedUri(String value) throws IOException {
        if (value == null) throw failure();
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw failure();
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw failure();
        }
        // Reject ambiguous authorities, including an empty port, encoded host and trailing dot.
        String authority = host + (uri.getPort() == 443 ? ":443" : "");
        if (!authority.equalsIgnoreCase(uri.getRawAuthority())) throw failure();
        return uri;
    }

    private static URI shareUri(String value) throws IOException {
        URI uri = trustedUri(value);
        // Use the raw, anchored path: query text and encoded separators are never classifiers.
        if (!SHARE_PATH.matcher(uri.getRawPath()).matches()) throw failure();
        return uri;
    }

    public static boolean isShareLink(String value) {
        try {
            shareUri(value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isBypass(String value) {
        try {
            return "bypass".equals(shareUri(value).getRawFragment());
        } catch (IOException e) {
            return false;
        }
    }

    private static String withoutFragment(URI uri) {
        String value = uri.toASCIIString();
        int fragment = value.indexOf('#');
        return fragment < 0 ? value : value.substring(0, fragment);
    }

    /** Guard the old connection entry point as well as the new redirect loop. */
    public static HttpURLConnection openConnection(String value, String token) throws IOException {
        return openConnection(value, token, CONNECTIONS);
    }

    static HttpURLConnection openConnection(String value, String token, ConnectionFactory factory) throws IOException {
        return openConnection(value, token, factory, TIMEOUT_MS);
    }

    private static HttpURLConnection openConnection(String value, String token, ConnectionFactory factory,
            int timeout) throws IOException {
        URI uri = shareUri(value);
        if (isBypass(value)) throw failure();
        HttpURLConnection connection = factory.open(new URL(withoutFragment(uri)));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.disconnect();
            throw failure();
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        // trustedUri has already restricted both schemes and effective ports to HTTPS/443.
        return left.getHost().equalsIgnoreCase(right.getHost());
    }

    public static String resolve(String value, String token) throws IOException {
        return resolve(value, token, CONNECTIONS);
    }

    static String resolve(String value, String token, ConnectionFactory factory) throws IOException {
        try {
            URI current = shareUri(value);
            if (isBypass(value)) throw failure();
            boolean authenticated = true;
            Set<String> seen = new HashSet<>();
            long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
            for (int request = 0; request < MAX_REQUESTS; request++) {
                if (!seen.add(withoutFragment(current))) throw failure();
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) throw failure();
                HttpURLConnection connection = openConnection(current.toASCIIString(),
                        authenticated ? token : null, factory, (int) Math.min(TIMEOUT_MS, remaining));
                final String location;
                try {
                    int status = connection.getResponseCode();
                    if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                        throw failure();
                    }
                    location = connection.getHeaderField("Location");
                } finally {
                    connection.disconnect();
                }
                if (location == null || location.isEmpty()) throw failure();
                URI next = trustedUri(current.resolve(new URI(location)).toASCIIString());
                // A cross-origin hop permanently drops authorization, even between allowed hosts.
                authenticated = authenticated && sameOrigin(current, next);
                if (!isShareLink(next.toASCIIString()) || isBypass(next.toASCIIString())) {
                    // Hand the validated final URL to Sync; do not request the post with this client.
                    return next.toASCIIString();
                }
                current = next;
            }
            throw failure();
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            throw failure();
        }
    }

    /** Retain Sync's existing browser fallback, but only for a validated original share URL. */
    public static String resolveOrBypass(String value, String token) {
        if (!isShareLink(value)) return null;
        try {
            return resolve(value, token);
        } catch (IOException | RuntimeException e) {
            try {
                return withoutFragment(shareUri(value)) + "#bypass";
            } catch (IOException ignored) {
                return null;
            }
        }
    }
}
