package d9;

import com.android.volley.NetworkResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for Sync 23033's OAuthRateHelper (d9.d). Records the headers Sync's real
 * method reads and reports a configurable stored window, as the APK's SharedPreferences would.
 */
public final class d {
    public static final List<Map<String, String>> recorded = Collections.synchronizedList(new ArrayList<>());
    public static volatile int remaining = 600;
    public static volatile long resetAt;
    public static volatile boolean broken;

    public static void e(NetworkResponse response, String url) {
        if (broken) throw new IllegalStateException("helper unavailable");
        if (response == null || response.headers == null) return;
        Map<String, String> seen = new HashMap<>();
        // The real helper looks these up with exact lower-case keys.
        for (String key : new String[]{"date", "x-ratelimit-reset", "x-ratelimit-remaining"}) {
            if (response.headers.get(key) != null) seen.put(key, response.headers.get(key));
        }
        seen.put("url", url);
        recorded.add(seen);
    }

    public static int b() {
        if (broken) throw new IllegalStateException("helper unavailable");
        return remaining;
    }

    public static long c() {
        if (broken) throw new IllegalStateException("helper unavailable");
        return resetAt == 0 ? System.currentTimeMillis() : resetAt;
    }

    public static void reset() {
        recorded.clear();
        remaining = 600;
        resetAt = 0;
        broken = false;
    }

    private d() { }
}
