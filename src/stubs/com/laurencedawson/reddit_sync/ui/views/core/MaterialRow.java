package com.laurencedawson.reddit_sync.ui.views.core;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Compile signature only: Sync 23033's options-sheet row. The constructor inflates the row; k(title)
 * sets its title and d(icon) shows a drawable icon. The APK keeps its own class.
 */
public class MaterialRow extends LinearLayout {
    public MaterialRow(Context context) {
        super(context);
    }

    public void d(int icon) { }

    public void k(CharSequence title) { }
}
