package f9;

import android.content.Context;

/**
 * Compile signature only: Sync 23033's DraftManager. e(context, text) adds the text to the
 * editor drafts Sync offers when an editor opens (and shows "Draft saved"). The APK keeps its own
 * class; the messaging handoff patch checks it before installing.
 */
public final class a {
    public static void e(Context context, String text) { }

    private a() { }
}
