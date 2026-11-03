package androidx.core.app;

import android.app.PendingIntent;

/**
 * Compile signature only: Sync 23033's copy of androidx NotificationCompat (shrunk to g), whose
 * Builder is g$e. a(icon, title, intent) adds an action button. The APK keeps its own classes; the
 * messaging handoff patch checks this method before installing.
 */
public final class g {
    public static class e {
        public e a(int icon, CharSequence title, PendingIntent intent) { return this; }
    }

    private g() { }
}
