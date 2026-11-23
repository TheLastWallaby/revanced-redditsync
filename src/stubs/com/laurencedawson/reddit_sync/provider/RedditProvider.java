package com.laurencedawson.reddit_sync.provider;

import android.net.Uri;

/**
 * Compile signature only: Sync 23033's content provider. Updating q ("restore_removed") with a row ID
 * as the selection updates that row of tbl_posts, which holds posts and comments; notifying B
 * ("multiplePosts") refreshes Sync's post and comment lists. The APK keeps its own class.
 */
public class RedditProvider {
    public static final Uri q = null;
    public static final Uri B = null;
}
