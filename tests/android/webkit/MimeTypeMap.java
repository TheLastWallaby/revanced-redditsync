package android.webkit;

import java.util.regex.Pattern;

/** Test adapter with Android's extension rule; the APK uses Android's implementation. */
public final class MimeTypeMap {
    public static String getFileExtensionFromUrl(String url) {
        if (url != null && !url.isEmpty()) {
            int fragment = url.lastIndexOf('#');
            if (fragment > 0) url = url.substring(0, fragment);
            int query = url.lastIndexOf('?');
            if (query > 0) url = url.substring(0, query);
            int slash = url.lastIndexOf('/');
            String name = slash >= 0 ? url.substring(slash + 1) : url;
            if (!name.isEmpty() && Pattern.matches("[a-zA-Z_0-9.()%-]+", name)) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0) return name.substring(dot + 1);
            }
        }
        return "";
    }
}
