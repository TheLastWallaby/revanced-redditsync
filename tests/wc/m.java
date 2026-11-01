package wc;

/** Test stand-in for Sync's TextUtils-style empty check, used by executed fixture methods. */
public final class m {
    private m() {}

    public static boolean a(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
