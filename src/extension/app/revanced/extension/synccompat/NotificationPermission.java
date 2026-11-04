package app.revanced.extension.synccompat;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Asks once for Android 13's notification permission when Sync's inbox checks are on.
 *
 * <p>Sync 23033 targets API 33 and declares POST_NOTIFICATIONS, but asks for it only in its
 * onboarding. A user who skipped or declined that step gets no inbox notifications (nor the
 * bundle's comment-error and Reddit Chat notifications) and no hint why. The patch "Sync: ask for
 * notification permission" calls {@link #onResume} when Sync's main screen resumes. It asks only
 * on Android 13 or later, only while Sync's "Periodically check for new messages" setting
 * ({@code message_preference_new}) is not Off, only while the permission is not granted, and only
 * once per install: a flag in the bundle's own preferences file records that it asked, before it
 * asks. Sync's own settings are only read. Nothing is logged.
 */
public final class NotificationPermission {
    static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    /** The bundle's own preferences file. Sync names its account files after Reddit usernames, which have no dots. */
    static final String PREFERENCES = "app.revanced.synccompat";
    static final String ASKED = "notification_permission_asked";
    /** Sync's inbox check setting and its default ("3": every 60 minutes), as MessageSchedulingHelper reads them. */
    static final String INBOX_CHECK = "message_preference_new";
    static final String INBOX_CHECK_DEFAULT = "3";
    static final String INBOX_CHECK_TAG = "MessagingService";
    /** A request code Sync does not use (its onboarding uses 10). */
    static final int REQUEST_CODE = 0x5C05;
    static final int TIRAMISU = 33;

    /** The device's API level; a test sets it. */
    static int sdk = Build.VERSION.SDK_INT;

    private NotificationPermission() {
    }

    /** Called after Sync's main screen resumes. Never throws. */
    public static void onResume(Activity activity) {
        try {
            if (activity == null || sdk < TIRAMISU) return;
            if (!inboxChecksOn(t7.z.f(activity, INBOX_CHECK_TAG))) return;
            if (activity.checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return;
            SharedPreferences own = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            if (own.getBoolean(ASKED, false)) return;
            // Record first, so a failed or interrupted request is never repeated.
            if (!own.edit().putBoolean(ASKED, true).commit()) return;
            // Activity.requestPermissions is API 23; the compile-time android.jar is older.
            activity.getClass().getMethod("requestPermissions", String[].class, int.class)
                    .invoke(activity, new String[] {PERMISSION}, REQUEST_CODE);
        } catch (Throwable ignored) {
            // Sync's screen keeps working without the prompt.
        }
    }

    /**
     * Whether Sync's inbox checks are on, by Sync's own rule: the setting, read as a number, is
     * above 0 ("0" is Off). An unreadable value counts as off, so nothing is asked.
     */
    static boolean inboxChecksOn(SharedPreferences settings) {
        if (settings == null) return false;
        try {
            String value = settings.getString(INBOX_CHECK, INBOX_CHECK_DEFAULT);
            return value != null && Integer.parseInt(value) > 0;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
