package app.revanced.extension.synccompat;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The address the full-screen image viewer's search button opens.
 *
 * <p>Sync 23033 opened {@code https://images.google.com/searchbyimage?image_url=} followed by the
 * image URL as it was. That address now answers HTTP 404, and the unencoded URL lost everything
 * after its first {@code &}, such as the signature of Reddit's {@code preview.redd.it} links. The
 * patch "Sync: fix image search" opens Google Lens' by-URL address instead, with the image URL
 * percent-encoded as one query value. Google receives the image's URL, as before, and only when the
 * user taps the button. Nothing is logged.
 */
public final class ImageSearch {
    static final String LENS = "https://lens.google.com/uploadbyurl?url=";
    private static final int MAX_LENGTH = 8192;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ImageSearch() {
    }

    /**
     * The Google Lens address for an http or https image URL, or null for anything else (empty, too
     * long, another scheme, no host, control characters or text that isn't valid UTF-16), in which
     * case Sync keeps its own address. Never throws.
     */
    public static String lensUrl(String image) {
        try {
            if (!isWebUrl(image)) return null;
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(image));
            StringBuilder out = new StringBuilder(LENS.length() + image.length() * 3).append(LENS);
            while (bytes.hasRemaining()) {
                int b = bytes.get() & 0xff;
                if (unreserved(b)) out.append((char) b);
                else out.append('%').append(HEX[b >> 4]).append(HEX[b & 0xf]);
            }
            return out.toString();
        } catch (CharacterCodingException | RuntimeException e) {
            return null;
        }
    }

    /** An absolute http or https URL with a host, without control characters. */
    static boolean isWebUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH) return false;
        int start;
        if (url.regionMatches(true, 0, "https://", 0, 8)) start = 8;
        else if (url.regionMatches(true, 0, "http://", 0, 7)) start = 7;
        else return false;
        if (start >= url.length() || "/?#@:".indexOf(url.charAt(start)) >= 0) return false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /** RFC 3986 unreserved characters, the only ones left as they are. */
    private static boolean unreserved(int b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                || b == '-' || b == '.' || b == '_' || b == '~';
    }
}
