package app.revanced.extension.shared;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal helpers for this bundle and the official ReVanced Sync code it includes.
 *
 * <p>Written for this repository instead of copying ReVanced's shared Utils, which is mostly
 * settings and YouTube support. This bundle's extension step hooks
 * {@code RedditApplication.onCreate} to call {@link #setContext}, as the official shared extension does.
 */
public final class Utils {
    private static volatile Context context;

    private static final ExecutorService BACKGROUND = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 10, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), runnable -> {
                Thread thread = new Thread(runnable, "revanced-background");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private Utils() {
    }

    public static Context getContext() {
        return context;
    }

    public static void setContext(Context appContext) {
        if (appContext == null) return;
        Context application = appContext.getApplicationContext();
        context = application != null ? application : appContext;
    }

    public static void runOnBackgroundThread(Runnable task) {
        BACKGROUND.execute(task);
    }
}
