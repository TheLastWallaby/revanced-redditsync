package t7;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Test adapter for Sync 23033's PreferencesHelper: f returns the preferences a suite supplies (or
 * throws its failure) and records the tag it was asked for. The APK uses Sync's own class.
 */
public final class z {
    public static SharedPreferences settings;
    public static RuntimeException failure;
    public static final List<String> tags = new ArrayList<>();

    public static SharedPreferences f(Context context, String tag) {
        tags.add(tag);
        if (failure != null) throw failure;
        return settings;
    }

    private z() { }
}
