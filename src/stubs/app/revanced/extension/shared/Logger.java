package app.revanced.extension.shared;

/** Compile/test signature only. The APK retains its existing ReVanced Logger. */
public final class Logger {
    public interface LogMessage { String buildMessageString(); }
    public static void printInfo(LogMessage message, Exception error) {}
}
