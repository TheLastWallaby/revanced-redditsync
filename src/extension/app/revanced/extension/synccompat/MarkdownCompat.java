package app.revanced.extension.synccompat;

import java.util.regex.Matcher;

/** Literal replacements at Sync's user-content call sites; no logging or parsing changes. */
public final class MarkdownCompat {
    private MarkdownCompat() {}

    public static Matcher appendReplacement(Matcher matcher, StringBuffer buffer, String replacement) {
        return matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }

    public static String replaceAll(String text, String pattern, String replacement) {
        return text.replaceAll(pattern, Matcher.quoteReplacement(replacement));
    }
}
