package app.revanced.extension.shared;

import android.util.Log;

/**
 * Minimal logger for this bundle and the official ReVanced Sync code it includes.
 *
 * <p>Written for this repository instead of copying ReVanced's shared Logger, which also carries
 * settings, a log buffer and debug toasts that Sync does not use. Debug messages are dropped:
 * the /s/ link code passes links to them, and those must not reach the system log.
 */
public final class Logger {
    private static final String TAG = "revanced";

    /** Builds the message only when it is logged. */
    public interface LogMessage {
        String buildMessageString();
    }

    private Logger() {
    }

    /** Debug details are never logged. */
    public static void printDebug(LogMessage message) {
    }

    public static void printInfo(LogMessage message) {
        printInfo(message, null);
    }

    public static void printInfo(LogMessage message, Exception error) {
        String text = build(message);
        if (error == null) Log.i(TAG, text);
        else Log.i(TAG, text, error);
    }

    public static void printException(LogMessage message) {
        printException(message, null);
    }

    public static void printException(LogMessage message, Throwable error) {
        String text = build(message);
        if (error == null) Log.e(TAG, text);
        else Log.e(TAG, text, error);
    }

    private static String build(LogMessage message) {
        try {
            return message.buildMessageString();
        } catch (RuntimeException e) {
            return "Log message failed: " + e.getClass().getSimpleName();
        }
    }
}
