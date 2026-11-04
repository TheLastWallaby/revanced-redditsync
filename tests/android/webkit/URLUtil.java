package android.webkit;

/** Test adapter; the APK uses Android's implementation. guessUrl only adds the http:// scheme. */
public final class URLUtil {
    public static boolean isHttpUrl(String url) {
        return url != null && url.length() > 6 && url.substring(0, 7).equalsIgnoreCase("http://");
    }
    public static boolean isHttpsUrl(String url) {
        return url != null && url.length() > 7 && url.substring(0, 8).equalsIgnoreCase("https://");
    }
    /** As Android: a non-empty URL with one of the schemes WebView loads. */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return isHttpUrl(url) || isHttpsUrl(url) || lower.startsWith("file://") || lower.startsWith("about:")
                || lower.startsWith("javascript:") || lower.startsWith("content:");
    }
    public static String guessUrl(String url) {
        return url == null || url.contains("://") ? url : "http://" + (url.startsWith("//") ? url.substring(2) : url);
    }
}
