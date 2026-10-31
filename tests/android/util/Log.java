package android.util;

/** Test adapter so Volley's Request can be constructed on the JVM; the APK uses Android's implementation. */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static int wtf(String tag, String message) { return 0; }
    public static int wtf(String tag, String message, Throwable error) { return 0; }
    private Log() { }
}
