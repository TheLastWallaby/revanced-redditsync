package t7;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Compile signature only: Sync 23033's PreferencesHelper. f(context, tag) returns the signed-in
 * account's own preferences when it has them, otherwise the default shared preferences; Sync's
 * inbox check (MessageSchedulingHelper) reads "message_preference_new" from it. The APK keeps its
 * own class.
 */
public final class z {
    public static SharedPreferences f(Context context, String tag) {
        return null;
    }

    private z() { }
}
