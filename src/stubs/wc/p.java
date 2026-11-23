package wc;

/**
 * Compile signature only: Sync 23033's WebsafeUtils. a(text) unescapes &amp;amp;, &amp;lt; and
 * &amp;gt; and drops bidirectional override characters, as Sync's parsers do to Reddit's JSON text.
 * The APK keeps its own class.
 */
public final class p {
    public static String a(String text) {
        return text;
    }

    private p() { }
}
