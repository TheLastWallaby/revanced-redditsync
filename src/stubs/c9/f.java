package c9;

/**
 * Compile signature only: Sync 23033's TitleParserHelper. a(title) is how Sync's post parser cleans a
 * post title before storing it: unescaped with WebsafeUtils and ended with Sync's "\r" marker, or null
 * when empty. The APK keeps its own class.
 */
public final class f {
    public static String a(String title) {
        return title;
    }

    private f() { }
}
