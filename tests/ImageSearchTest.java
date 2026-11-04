package app.revanced.extension.synccompat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Checks the Google Lens address ImageSearch builds for the image viewer's search button: the
 * image URL is one percent-encoded query value that decodes to the exact original bytes (signed
 * Reddit query, '&', '?', '#', '%', '+', spaces and non-ASCII included), and anything that is not
 * an http(s) URL is refused (null, so Sync keeps its own address).
 */
public final class ImageSearchTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Exception {
        String lens = "https://lens.google.com/uploadbyurl?url=";
        equal(lens, ImageSearch.LENS, "Lens address");

        // Exact encodings.
        equal(lens + "https%3A%2F%2Fi.redd.it%2Fabc123.jpg", ImageSearch.lensUrl("https://i.redd.it/abc123.jpg"), "i.redd.it");
        equal(lens + "https%3A%2F%2Fpreview.redd.it%2Fx.jpg%3Fwidth%3D640%26format%3Dpjpg%26auto%3Dwebp%26s%3D0a1B2c",
                ImageSearch.lensUrl("https://preview.redd.it/x.jpg?width=640&format=pjpg&auto=webp&s=0a1B2c"), "signed preview link");
        equal(lens + "http%3A%2F%2Fi.imgur.com%2FAbCdE12.png", ImageSearch.lensUrl("http://i.imgur.com/AbCdE12.png"), "plain http Imgur");
        equal(lens + "https%3A%2F%2Fexample.com%2Fa%20b%2B%25%23frag", ImageSearch.lensUrl("https://example.com/a b+%#frag"),
                "space, plus, percent and fragment");
        equal(lens + "https%3A%2F%2Fexample.com%2F%C3%A9t%C3%A9%2F%E2%98%83%F0%9F%90%88.png",
                ImageSearch.lensUrl("https://example.com/été/☃🐈.png"), "non-ASCII as UTF-8");
        equal(lens + "HTTPS%3A%2F%2FI.REDD.IT%2FX.JPG", ImageSearch.lensUrl("HTTPS://I.REDD.IT/X.JPG"), "upper-case scheme");
        equal(lens + "https%3A%2F%2Fa-b.c_d~e%2F", ImageSearch.lensUrl("https://a-b.c_d~e/"), "unreserved characters kept");

        // Every accepted URL is one query value on Lens' fixed address and decodes back exactly.
        String[] urls = {
            "https://i.redd.it/abc123.jpg",
            "https://preview.redd.it/some-title-v0-abc.png?width=1080&format=png&auto=webp&s=5f0e1d2c3b4a",
            "https://external-preview.redd.it/AbC.jpg?auto=webp&s=0123456789abcdef0123456789abcdef01234567",
            "https://i.imgur.com/AbCdE12.jpg",
            "https://example.com/path?q=a&b=c&d=%2F%26#x",
            "https://example.com/?a=1&a=2&empty=&=&&",
            "https://user@example.com:8443/x",
            "https://example.com/café?n=日本&e=😀",
            "http://[::1]/img.png",
            "https://example.com/a+b c%20d%zz",
        };
        for (String url : urls) {
            String out = ImageSearch.lensUrl(url);
            check(out != null && out.startsWith(lens), "accepted " + url);
            String value = out.substring(lens.length());
            check(value.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 0x80 || "-._~%".indexOf(c) >= 0),
                    "only unreserved characters and escapes in " + value);
            URI uri = URI.create(out);
            equal("https", uri.getScheme(), "Lens scheme for " + url);
            equal("lens.google.com", uri.getHost(), "Lens host for " + url);
            equal("/uploadbyurl", uri.getPath(), "Lens path for " + url);
            check(uri.getRawFragment() == null, "no fragment for " + url);
            check(uri.getRawQuery().startsWith("url=") && uri.getRawQuery().indexOf('&') < 0, "one query value for " + url);
            String decoded = URLDecoder.decode(value, "UTF-8");
            equal(url, decoded, "decodes back");
            check(Arrays.equals(url.getBytes(StandardCharsets.UTF_8), decoded.getBytes(StandardCharsets.UTF_8)), "same bytes for " + url);
            equal(decoded, URI.create("https://x/?u=" + value).getQuery().substring(2), "URI decoding agrees for " + url);
        }

        // Refused: Sync keeps its own address.
        String[] refused = {
            null, "", " ", "https://", "http://", "https:///path", "https://?q", "https://#x", "https://@host", "https://:443/",
            "ftp://example.com/x.jpg", "file:///sdcard/x.jpg", "content://media/external/images/1", "javascript:alert(1)",
            "data:image/png;base64,AAAA", "//i.redd.it/x.jpg", "i.redd.it/x.jpg", "/storage/emulated/0/x.jpg",
            " https://i.redd.it/x.jpg", "https://i.redd.it/x.jpg\n", "https://i.redd.it/x\t.jpg", "https://i.redd.it/x\u007f.jpg",
            "https://i.redd.it/\ud83d.jpg", "https://i.redd.it/\udc08x.jpg", "httpx://i.redd.it/x.jpg", "https:/i.redd.it/x.jpg",
        };
        for (String url : refused) equal(null, ImageSearch.lensUrl(url), "refused " + (url == null ? "null" : url.replace("\n", "\\n")));
        StringBuilder huge = new StringBuilder("https://i.redd.it/");
        while (huge.length() <= 8192) huge.append('a');
        equal(null, ImageSearch.lensUrl(huge.toString()), "refused over-long URL");
        check(ImageSearch.lensUrl(huge.substring(0, 8192)) != null, "accepted URL at the length limit");

        System.out.println("PASS: " + checks + " image search checks (" + urls.length + " URLs round-trip, " + (refused.length + 1)
                + " refused)");
    }
}
