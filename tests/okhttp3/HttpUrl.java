package okhttp3;

import java.net.URI;
import java.util.Locale;

/** Test stand-in for OkHttp's HttpUrl: scheme and host are lower case, as OkHttp canonicalizes them. */
public final class HttpUrl {
    private final URI uri;

    private HttpUrl(URI uri) { this.uri = uri; }

    public static HttpUrl get(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("Expected URL scheme http or https");
        }
        return new HttpUrl(uri);
    }

    public String scheme() { return uri.getScheme().toLowerCase(Locale.ROOT); }
    public String host() { return uri.getHost().toLowerCase(Locale.ROOT); }
    public String encodedPath() {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }
    public String encodedQuery() { return uri.getRawQuery(); }
    public boolean isHttps() { return scheme().equals("https"); }
    @Override public String toString() { return uri.toString(); }
}
