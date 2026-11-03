package android.util;

/** Test stand-in for Android's Pair (the bundled FixRedditVideoDownloadPatch uses it). */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }
}
