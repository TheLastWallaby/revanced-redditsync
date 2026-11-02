package app.revanced.extension.synccompat;

import android.view.Menu;
import android.view.MenuItem;
import java.util.List;
import java.util.Locale;

/**
 * Reddit retired its random-subreddit feature. Sync 23033 still offers it as a toolbar chip, two
 * actions (Random, Random NSFW), their "Action visibility" toggles, and the r/random,
 * r/random_nsfw and r/randnsfw links; each opened RandomProgressDialogBottomSheet (t9.m1), which
 * asked Reddit for r/random.json or r/randnsfw.json (RandomRequest, r8.d).
 *
 * <p>The patch "Sync: hide random subreddit" hides the chip, the actions and their toggles, and
 * shows {@link #MESSAGE} instead of the sheet for random links and selections, without a request.
 */
public final class RandomSubreddit {
    public static final String MESSAGE = "Random subreddit is no longer available from Reddit.";
    /** ActionsManager (t7.b) action IDs for Random and Random NSFW. */
    static final int RANDOM_ACTION = 5;
    static final int RANDOM_NSFW_ACTION = 6;

    private RandomSubreddit() {
    }

    /** True for Sync's random names (random, random_nsfw) and Reddit's randnsfw, in any case. */
    public static boolean isRandom(String name) {
        if (name == null) return false;
        String value = name.trim().toLowerCase(Locale.ROOT);
        return value.equals("random") || value.equals("random_nsfw") || value.equals("randnsfw");
    }

    /** True for the Random and Random NSFW actions. */
    public static boolean isRandomAction(int action) {
        return action == RANDOM_ACTION || action == RANDOM_NSFW_ACTION;
    }

    /** Removes the Random actions from Sync's action list in place (it is saved and edited as is). */
    public static void removeRandomActions(List<Integer> actions) {
        if (actions == null) return;
        try {
            for (int i = actions.size() - 1; i >= 0; i--) {
                Integer action = actions.get(i);
                if (action != null && isRandomAction(action)) actions.remove(i);
            }
        } catch (RuntimeException unexpected) {
            // Leave Sync's list as it is; the actions themselves still show the message.
        }
    }

    /** Hides the "Random" and "Random nsfw" items of the action-visibility menu. */
    public static void hideRandomItems(Menu menu) {
        if (menu == null) return;
        try {
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item == null) continue;
                if (item.hasSubMenu()) hideRandomItems(item.getSubMenu());
                CharSequence title = item.getTitle();
                if (title != null && (title.toString().equals("Random") || title.toString().equals("Random nsfw"))) {
                    item.setVisible(false);
                }
            }
        } catch (RuntimeException unexpected) {
            // A menu Sync did not build as expected stays as it is.
        }
    }
}
