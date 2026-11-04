package app.revanced.extension.synccompat;

import java.util.Objects;

/**
 * Checks the URLs ImagePeekCompat gives Sync's long-press peek: Reddit's HTML-escaped ampersands
 * are undone, and a text post's thumbnail is replaced by the body's link to the same Reddit
 * picture (signed preview.redd.it or i.redd.it), never by a different picture, an unsigned or
 * video rendition, a look-alike host or a link Sync's parser extended with "&height=".
 */
public final class ImagePeekCompatTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    // Shapes from Reddit listings of text posts with a picture (made-up IDs and signatures).
    private static final String ID = "7idyhcufrhzh1";
    private static final String SIG = "a896079fd1a37caf6052b9d4954a28f529f270bc";
    private static final String THUMB = "https://preview.redd.it/" + ID + ".png?width=140&height=140&crop=1:1,smart&auto=webp&s="
            + "0123456789abcdef0123456789abcdef01234567";
    private static final String FULL = "https://preview.redd.it/" + ID + ".png?width=714&format=png&auto=webp&s=" + SIG;

    private static String escaped(String url) {
        return url.replace("&", "&amp;");
    }

    public static void main(String[] args) {
        // imageUrl: escaping undone; anything else unchanged.
        equal(THUMB, ImagePeekCompat.imageUrl(escaped(THUMB)), "escaped thumbnail");
        equal(THUMB, ImagePeekCompat.imageUrl(THUMB), "plain thumbnail");
        equal(null, ImagePeekCompat.imageUrl(null), "null");
        equal("", ImagePeekCompat.imageUrl(""), "empty");
        equal("self", ImagePeekCompat.imageUrl("self"), "placeholder thumbnail");
        equal("https://b.thumbs.redditmedia.com/abc.jpg", ImagePeekCompat.imageUrl("https://b.thumbs.redditmedia.com/abc.jpg"), "legacy thumbnail");
        equal("https://example.com/a?x=1&y=2", ImagePeekCompat.imageUrl("https://example.com/a?x=1&amp;y=2"), "other host");

        // mediaId.
        equal(ID, ImagePeekCompat.mediaId(THUMB), "preview thumbnail ID");
        equal(ID, ImagePeekCompat.mediaId("https://i.redd.it/" + ID + ".png"), "i.redd.it ID");
        equal(ID, ImagePeekCompat.mediaId("https://preview.redd.it/subway-meal-prices-v0-" + ID + ".png?width=640&s=" + SIG), "titled preview ID");
        equal(ID, ImagePeekCompat.mediaId("https://PREVIEW.redd.it/" + ID.toUpperCase() + ".PNG?s=" + SIG), "case-insensitive ID");
        equal(null, ImagePeekCompat.mediaId("https://external-preview.redd.it/" + ID + ".png?s=" + SIG), "external preview is a website preview");
        equal(null, ImagePeekCompat.mediaId("https://preview.redd.it.example.com/" + ID + ".png"), "look-alike host");
        equal(null, ImagePeekCompat.mediaId("https://user@i.redd.it/" + ID + ".png"), "user info");
        equal(null, ImagePeekCompat.mediaId("http://i.redd.it/" + ID + ".png"), "plain http");
        equal(null, ImagePeekCompat.mediaId("https://i.redd.it:8443/" + ID + ".png"), "other port");
        equal(null, ImagePeekCompat.mediaId("https://i.redd.it/a/" + ID + ".png"), "nested path");
        equal(null, ImagePeekCompat.mediaId("https://i.redd.it/" + ID), "no extension");
        equal(null, ImagePeekCompat.mediaId("https://i.redd.it/abc.png"), "too short");
        equal(null, ImagePeekCompat.mediaId("https://i.redd.it/titled-v0-" + ID + ".png"), "titles only on preview.redd.it");
        equal(null, ImagePeekCompat.mediaId("https://b.thumbs.redditmedia.com/" + ID + ".jpg"), "legacy thumbnail host");
        equal(null, ImagePeekCompat.mediaId(null), "null ID");
        equal(null, ImagePeekCompat.mediaId("self"), "placeholder ID");

        // textPostImage: the body's link to the same picture.
        String body = FULL + "\n\n";
        equal(FULL, ImagePeekCompat.textPostImage(body, escaped(THUMB)), "escaped thumbnail to body picture");
        equal(FULL, ImagePeekCompat.textPostImage(body, THUMB), "thumbnail to body picture");
        equal(FULL, ImagePeekCompat.textPostImage(escaped(body), escaped(THUMB)), "escaped body");
        equal(FULL, ImagePeekCompat.textPostImage("Look at this:\n\n" + FULL + "\n\nWhy?", THUMB), "picture among text");
        equal(FULL, ImagePeekCompat.textPostImage("[the menu](" + FULL + ") and more", THUMB), "Markdown link");
        equal(FULL, ImagePeekCompat.textPostImage("<" + FULL + ">", THUMB), "angle brackets");
        equal(FULL, ImagePeekCompat.textPostImage("See " + FULL + ".", THUMB), "trailing period");
        equal(FULL, ImagePeekCompat.textPostImage("**" + FULL + "**", THUMB), "bold");
        equal(FULL, ImagePeekCompat.textPostImage(FULL + "&height=542", THUMB), "Sync's height suffix removed");
        equal(FULL, ImagePeekCompat.textPostImage(escaped(FULL) + "&height=542\n", escaped(THUMB)), "escaped, with height suffix");
        String titled = "https://preview.redd.it/the-menu-v0-" + ID + ".png?width=714&format=png&auto=webp&s=" + SIG;
        equal(titled, ImagePeekCompat.textPostImage(titled, THUMB), "titled body picture");
        String original = "https://i.redd.it/" + ID + ".png";
        equal(original, ImagePeekCompat.textPostImage(original + "\n\n", THUMB), "body link to the original");

        // Several pictures: the one the thumbnail shows, whichever comes first.
        String other = "https://preview.redd.it/aaaaaaaaaaaa1.jpg?width=1080&format=pjpg&auto=webp&s=" + SIG;
        equal(FULL, ImagePeekCompat.textPostImage(other + "\n\n" + FULL, THUMB), "matching picture, not the first");
        equal(other, ImagePeekCompat.textPostImage(other + "\n\n" + FULL,
                "https://preview.redd.it/aaaaaaaaaaaa1.jpg?width=140&amp;height=105&amp;auto=webp&amp;s=" + SIG),
                "first picture when the thumbnail shows it");

        // Kept: the thumbnail (escaping undone) when the body has no usable link to its picture.
        equal(THUMB, ImagePeekCompat.textPostImage(other, escaped(THUMB)), "different picture");
        equal(THUMB, ImagePeekCompat.textPostImage("no pictures here", escaped(THUMB)), "no picture");
        equal(THUMB, ImagePeekCompat.textPostImage("", escaped(THUMB)), "empty body");
        equal(THUMB, ImagePeekCompat.textPostImage(null, escaped(THUMB)), "null body");
        equal(THUMB, ImagePeekCompat.textPostImage("https://preview.redd.it/" + ID + ".png?width=714&format=png", escaped(THUMB)), "unsigned preview");
        equal(THUMB, ImagePeekCompat.textPostImage("https://preview.redd.it/" + ID + ".png?width=714&s=", escaped(THUMB)), "empty signature");
        equal(THUMB, ImagePeekCompat.textPostImage("https://preview.redd.it/" + ID + ".gif?width=640&format=mp4&s=" + SIG, escaped(THUMB)),
                "video rendition");
        equal(THUMB, ImagePeekCompat.textPostImage("https://i.redd.it/" + ID + ".mp4", escaped(THUMB)), "video file");
        equal(THUMB, ImagePeekCompat.textPostImage("http://i.redd.it/" + ID + ".png", escaped(THUMB)), "plain http body link");
        equal(THUMB, ImagePeekCompat.textPostImage("https://i.redd.it.example.com/" + ID + ".png", escaped(THUMB)), "look-alike body link");
        equal(THUMB, ImagePeekCompat.textPostImage("https://external-preview.redd.it/" + ID + ".png?s=" + SIG, escaped(THUMB)),
                "website preview");

        // Thumbnails that are not Reddit pictures are left alone (escaping undone).
        String legacy = "https://b.thumbs.redditmedia.com/abcdefghijk.jpg";
        equal(legacy, ImagePeekCompat.textPostImage(body, legacy), "legacy thumbnail kept");
        equal("https://external-preview.redd.it/x.png?a=1&s=2",
                ImagePeekCompat.textPostImage(body, "https://external-preview.redd.it/x.png?a=1&amp;s=2"), "website preview kept");
        equal(null, ImagePeekCompat.textPostImage(body, null), "no URL");
        equal("", ImagePeekCompat.textPostImage(body, ""), "empty URL");

        // Long bodies: only the start is searched.
        String filler = "x".repeat(ImagePeekCompat.MAX_BODY);
        equal(FULL, ImagePeekCompat.textPostImage(FULL + " " + filler, THUMB), "picture before the limit");
        equal(THUMB, ImagePeekCompat.textPostImage(filler + " " + FULL, THUMB), "picture past the limit");

        System.out.println("PASS: " + checks + " image peek URL checks");
    }
}
