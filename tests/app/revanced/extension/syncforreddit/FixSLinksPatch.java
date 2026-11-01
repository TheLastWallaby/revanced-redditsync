package app.revanced.extension.syncforreddit;

/** Test stand-in for the base's inherited share-link hook; records what LinkHelper passes it. */
public final class FixSLinksPatch {
    public static String received;

    private FixSLinksPatch() {}

    public static boolean patchResolveSLink(String link) {
        received = link;
        return true;
    }
}
