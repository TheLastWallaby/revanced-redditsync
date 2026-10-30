package android.text;

/** Test adapter; the APK uses Android's implementation. */
public final class TextUtils {
    public static boolean isEmpty(CharSequence text) { return text == null || text.length() == 0; }
}
