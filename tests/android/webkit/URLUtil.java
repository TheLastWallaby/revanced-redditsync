package android.webkit;

/** Test adapter; the APK uses Android's implementation. guessUrl only adds the http:// scheme. */
public final class URLUtil {
    public static boolean isHttpUrl(String url) {
        return url != null && url.length() > 6 && url.substring(0, 7).equalsIgnoreCase("http://");
    }
    public static boolean isHttpsUrl(String url) {
        return url != null && url.length() > 7 && url.substring(0, 8).equalsIgnoreCase("https://");
    }
    public static String guessUrl(String url) {
        return url == null || url.contains("://") ? url : "http://" + (url.startsWith("//") ? url.substring(2) : url);
    }
}
