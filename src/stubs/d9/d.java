package d9;

import com.android.volley.NetworkResponse;

/**
 * Compile signature only: Sync 23033's OAuthRateHelper. The APK keeps its own class;
 * the load-more patch checks these methods before installing its hooks. Tests use the
 * recording double in tests/d9/.
 */
public final class d {
    /** Stores x-ratelimit-reset/remaining from a response whose date header is newest. */
    public static void e(NetworkResponse response, String url) { }

    /** Remaining calls in the stored window (600 when nothing is stored). */
    public static int b() { return 600; }

    /** Stored reset time in wall-clock milliseconds (now when nothing is stored). */
    public static long c() { return System.currentTimeMillis(); }

    private d() { }
}
