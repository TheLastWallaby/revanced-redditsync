package app.revanced.extension.synccompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runs equivalent Reddit links through the production normalizer, then through a
 * model of Sync 23033's LinkHelper (y7.b.c), its classifiers (y7.c) and ID parsers
 * (y7.a). The model's regular expressions are copied from j9.c in the supported
 * base; its branches follow the smali order. No network, Android runtime or account.
 */
public final class LinkNormalizerTest {
    private static int checks;

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected <" + expected + "> got <" + actual + ">");
    }

    /** Where Sync sends a link, with the IDs its own parsers extract. */
    static final class Route {
        final String kind;
        final List<String> ids;
        Route(String kind, String... ids) { this.kind = kind; this.ids = Arrays.asList(ids); }
        @Override public boolean equals(Object other) {
            return other instanceof Route && kind.equals(((Route) other).kind) && ids.equals(((Route) other).ids);
        }
        @Override public int hashCode() { return Objects.hash(kind, ids); }
        @Override public String toString() { return kind + ids; }
    }

    /** Sync 23033 link routing, transcribed from y7.b.c, y7.c, y7.a and j9.c. */
    static final class Sync {
        private static final int FLAGS = Pattern.CASE_INSENSITIVE;
        static final Pattern MULTI = Pattern.compile("(http(s)*:)*//((\\w)+\\.)*reddit\\.com(/u(ser)*/([\\.\\+a-zA-Z0-9_-])+)*/m/([\\.\\+a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern USER = Pattern.compile("http(s)*://(www\\.|m\\.)*reddit\\.com/u(ser)*/([\\.\\+a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern SUBREDDIT = Pattern.compile("(((http(s)|sync)*://(www\\.|m\\.)*reddit\\.com/r/)|r/|/r/)([\\.\\+a-zxA-Z0-9_-])+(/$|/hot(/)*$|/new(/)*$|/rising(/)*$|/top(/)*$|/comments(/)*$|/controversial(/)*$|/guilded(/)*$|$)", FLAGS);
        static final Pattern DUPLICATES = Pattern.compile("(http(s)*:)*//((\\w)+\\.)*reddit\\.com/r/([\\.\\+a-zA-Z0-9_-])+(/duplicates)+/([!:%&\\?=\\.\\+/a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern SUB_POST = Pattern.compile("(http(s)*:)*//((\\w)+\\.)*reddit\\.com/r/([\\.\\+a-zA-Z0-9_-])+(/comments)*/([!:%&\\?=\\.\\+/a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern COMMENTS = Pattern.compile("(http(s)*:)*//((\\w)+\\.)*reddit\\.com(/(r|user)/([\\.\\+a-zA-Z0-9_-])+)*/comments/([!:%&\\?=\\.\\+/a-zA-Z0-9_-])+((/\\?context=\\[([0-9])+\\])|(/#([!:%&\\?=\\.\\+/a-zA-Z0-9_-])+))*(\\b|/|\\?)*", FLAGS);
        static final Pattern RELATIVE_POST = Pattern.compile("/r/([\\.\\+a-zA-Z0-9_-])+/([!:%&\\?=\\.\\+/a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern ROOT_PAGE = Pattern.compile("http(s)*://((\\w)+\\.)*reddit\\.com/([\\.\\+a-zA-Z0-9_-])+(\\b|/|\\?)", FLAGS);
        static final Pattern WIKI = Pattern.compile("(((http(s)*:)*//((\\w)+\\.)*reddit\\.com)*/r/(\\w)+)*/w(iki)*(/(.*?))*", FLAGS);
        static final Pattern COMPOSE = Pattern.compile("(http(s)*://((\\w)+\\.)*reddit\\.com)*/message/compose([!:%&\\?=\\.\\+/a-zA-Z0-9_-])*(\\b|/|\\?)", FLAGS);
        static final Pattern DOMAIN = Pattern.compile("http(s)*://((\\w)+\\.)*reddit\\.com/domain/([!:%&\\?=\\.\\+/a-zA-Z0-9_-])*(\\b|/|\\?)", FLAGS);

        /** y7.c.a: lower-case host without www. */
        static String host(String url) {
            try {
                String host = java.net.URI.create(url).getHost();
                if (host == null) return null;
                if (host.startsWith("www.")) host = host.substring(4);
                return host.toLowerCase(Locale.ENGLISH);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        static boolean gallery(String url, boolean patched) {
            if (patched && LinkNormalizer.isGalleryLink(url)) return true;
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("https://www.reddit.com/r/") && lower.endsWith("?is_gallery=true");
        }

        static boolean search(String url) {
            return (url.contains("/search?") || url.contains("/search/?")) && url.contains("/r/");
        }

        static boolean shortened(String url) {
            if (url.contains("libredd.it") || url.contains("i.redd.it") || url.contains("v.redd.it")
                    || url.contains("vredd.it") || url.contains("preview.redd.it") || imageExtension(url)) return false;
            return url.contains("redd.it");
        }

        static boolean wiki(String url) {
            String stripped = url.replaceAll("\\?", "");
            return WIKI.matcher(stripped).matches() || (stripped.contains("reddit.com") && stripped.contains("/wiki/"));
        }

        static boolean subreddit(String url) {
            if (url.toLowerCase(Locale.ENGLISH).contains("about/rules")) return false;
            String stripped = url.replace("?utm_source=amp&utm_medium=top-posts-action", "").replaceAll("\\?", "");
            return SUBREDDIT.matcher(stripped).matches();
        }

        static boolean comments(String url) {
            if (url.contains("reddit.com/rules") || url.toLowerCase(Locale.ENGLISH).contains("about/rules")) return false;
            if ((url.startsWith("/r/") || url.startsWith("r/")) && url.contains("/comments/")) return true;
            String value = url.replace("?utm_source=amp&utm_medium=top-posts-action", "")
                    .replace("/?utm_source=share&amp;amp;amp;utm_medium=ios_app&amp;amp;amp;utm_name=iossmf", "");
            return COMMENTS.matcher(value).matches() || ROOT_PAGE.matcher(value).matches()
                    || RELATIVE_POST.matcher(value).matches() || SUB_POST.matcher(value).matches();
        }

        static boolean user(String url) {
            String stripped = url.replaceAll("\\?", "");
            if (USER.matcher(stripped).matches() && !stripped.contains("comments")) return true;
            return stripped.startsWith("/u/") || stripped.startsWith("u/");
        }

        static boolean imageExtension(String url) {
            String path = url.replaceFirst("[?#].*$", "");
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot < 0 || dot < slash) return false;
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            return Arrays.asList("jpg", "jpeg", "png", "gif", "webp").contains(ext);
        }

        static boolean redditVideo(String url) {
            String host = host(url);
            return host != null && host.endsWith("v.redd.it");
        }

        static boolean media(String url) {
            String lower = url.toLowerCase(Locale.ENGLISH);
            return lower.contains("i.reddituploads.com") || lower.contains("g.redditmedia.com")
                    || lower.contains("i.redditmedia.com") || lower.contains("v.redd.it")
                    || lower.contains("preview.redd.it") || (lower.contains("i.redd.it") && imageExtension(lower));
        }

        /** y7.a.b getCommentsId. */
        static String postId(String url) {
            try {
                if (url.contains("/comments")) return url.split("/comments/")[1].split("/")[0];
                String[] parts = url.split("/");
                return parts[parts.length - 1];
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** y7.a.a getCommentsCommentId. */
        static String commentId(String url) {
            String value = url.replaceAll("#", "");
            if (value.endsWith("?")) value = value.substring(0, value.length() - 1);
            if (!value.contains("/comments/")) return null;
            String[] parts = value.split("/comments/")[1].split("/");
            if (parts.length < 3) return null;
            return parts[2].contains("?") ? parts[2].split("\\?")[0] : parts[2];
        }

        /** y7.a.g subreddit. */
        static String subredditName(String url) {
            try {
                if (url.startsWith("r/")) return url.split("r/")[1].split("/")[0];
                return url.split("/r/")[1].split("/")[0];
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** y7.a.s username. */
        static String userName(String url) {
            String value = url.trim();
            for (String prefix : new String[] {"reddit.com/u/", "reddit.com/user/"}) {
                if (value.contains(prefix)) {
                    java.util.regex.Matcher match = Pattern.compile(prefix.replace(".", "\\.")
                            + "([a-zA-Z0-9_-]*)( |\\n|$|\\W)", Pattern.CASE_INSENSITIVE).matcher(value);
                    if (match.find()) return match.group(1);
                }
            }
            if (value.startsWith("u/")) return value.replace("u/", "");
            if (value.startsWith("/u/")) return value.replace("/u/", "");
            return value;
        }

        /** y7.a.o subreddit for subreddit links. */
        static String listingName(String url) {
            java.util.regex.Matcher match = Pattern.compile("reddit.com/r/([\\.\\+a-zA-Z0-9_-]*)( |\\n|$|\\W)",
                    Pattern.CASE_INSENSITIVE).matcher(url.trim());
            return match.find() ? match.group(1) : null;
        }

        static Route route(String input, boolean patched) {
            String url = patched ? LinkNormalizer.normalize(input) : input;
            if (url == null || url.isEmpty()) return new Route("none");
            if (patched && SafeSLinks.isShareLink(url)) {
                return new Route(SafeSLinks.isBypass(url) ? "share-browser" : "share", url);
            }
            if (url.contains("reddit.com/link/") && url.contains("/video/")) return new Route("reddit-video-page");
            if (url.contains("reddit.com/poll/")) return new Route("web", url);
            String amp = "https://www.google.com/amp/s/amp.reddit.com";
            if (url.startsWith(amp)) return route(url.replace(amp, "https://reddit.com"), patched);
            for (String suffix : new String[] {"?utm_source=amp&utm_medium=post_header", "?utm_source=amp"}) {
                if (url.endsWith(suffix)) return route(url.replace(suffix, ""), patched);
            }
            if (url.contains("reddit.com/contact") || url.contains("reddit.com/prefs") || url.contains("reddit.com/coins")) {
                return new Route("web", url);
            }
            if (url.regionMatches(true, 0, "https://old.reddit.com/over18?dest=", 0, 35)) return new Route("over18");
            if (url.contains("old.reddit.com")) return route(url.replace("old.reddit.com", "reddit.com"), patched);
            String link = url;
            if (link.toLowerCase(Locale.ENGLISH).startsWith("https")) link = "https" + link.substring(5);
            else if (link.toLowerCase(Locale.ENGLISH).startsWith("http")) link = "http" + link.substring(4);
            if (gallery(link, patched)) {
                // y8.q.b: strip the synthetic marker, then y7.a.b.
                return new Route("gallery", postId(link.replace("?is_gallery=true", "")));
            }
            if (search(link)) return new Route("search");
            if (shortened(link)) {
                // y7.a.R/l: the text after redd.it/ becomes the post ID.
                String id = link.contains("redd.it/") ? link.split("redd.it/")[1] : null;
                return commentsRoute("http://reddit.com/comments/" + id);
            }
            if (DOMAIN.matcher(link).matches()) return new Route("domain");
            if (MULTI.matcher(link).matches()) return new Route("multi");
            if (wiki(link)) return new Route("wiki");
            if (COMPOSE.matcher(link).matches()) return new Route("compose");
            if (DUPLICATES.matcher(link).matches()) return new Route("duplicates");
            if (subreddit(link)) return new Route("subreddit", listingName(link));
            if (comments(link)) return commentsRoute(link);
            if (user(link)) return new Route("user", userName(link));
            if (redditVideo(link)) return new Route("video", link);
            if (media(link)) return new Route("media", link);
            return new Route("web", link);
        }

        static Route commentsRoute(String link) {
            String comment = commentId(link);
            return new Route(link.contains("np.reddit.com") ? "comments-np" : "comments",
                    String.valueOf(postId(link)), String.valueOf(comment), String.valueOf(subredditName(link)));
        }
    }

    private static Route comments(String post, String comment, String subreddit) {
        return new Route("comments", post, String.valueOf(comment), String.valueOf(subreddit));
    }

    private static void routes(Route expected, String... inputs) {
        for (String input : inputs) {
            Route actual = Sync.route(input, true);
            equal(expected, actual, "route for " + input);
            String once = LinkNormalizer.normalize(input);
            equal(once, LinkNormalizer.normalize(once), "normalization is idempotent for " + input);
            equal(expected, Sync.route(once, true), "normalized link routes the same for " + input);
        }
    }

    private static void normalized(String expected, String... inputs) {
        for (String input : inputs) equal(expected, LinkNormalizer.normalize(input), "normalized " + input);
    }

    private static void unchanged(String... inputs) {
        for (String input : inputs) equal(input, LinkNormalizer.normalize(input), "left unchanged: " + input);
    }

    public static void main(String[] args) {
        // The model reproduces the base defects these rewrites address.
        equal(comments("abc123?context=3", null, null), Sync.route("https://www.reddit.com/comments/abc123?context=3", false),
                "original keeps the query in a short comments link");
        equal(comments("abc123?utm_source=share", null, null), Sync.route("https://redd.it/abc123?utm_source=share", false),
                "original keeps the query in a redd.it link");
        equal(comments("abc123", "", "test"), Sync.route("https://www.reddit.com/r/test/comments/abc123/a_title/?context=3", false),
                "original reads an empty comment ID from a titled link with a query");
        equal("web", Sync.route("https://www.reddit.com/gallery/abc123", false).kind, "original opens /gallery/ in a browser");
        equal("web", Sync.route("https://np.reddit.com/r/test", false).kind, "original opens an np subreddit in a browser");
        equal("comments", Sync.route("https://www.reddit.com/r/test/top/?t=week", false).kind,
                "original treats a subreddit with a query as a post");
        equal("web", Sync.route("https://sh.reddit.com/user/someone", false).kind, "original opens an sh profile in a browser");
        equal("web", Sync.route("https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc123.jpg", false).kind,
                "original opens the media wrapper as a web page");

        // Posts: every equivalent spelling reaches the same comments screen.
        routes(comments("abc123", null, "test"),
                "https://www.reddit.com/r/test/comments/abc123/a_title/",
                "https://www.reddit.com/r/test/comments/abc123",
                "https://reddit.com/r/test/comments/abc123/",
                "http://www.reddit.com/r/test/comments/abc123/a_title#comments",
                "HTTPS://WWW.REDDIT.COM/r/test/comments/ABC123/a_title/",
                "https://old.reddit.com/r/test/comments/abc123/a_title/?utm_source=share&utm_medium=web2x",
                "https://new.reddit.com/r/test/comments/abc123/a_title",
                "https://sh.reddit.com/r/test/comments/abc123/",
                "https://m.reddit.com/r/test/comments/abc123/a_title/?share_id=Xy12&rdt=123",
                "https://amp.reddit.com/r/test/comments/abc123/a_title/",
                "https://i.reddit.com/r/test/comments/abc123",
                "https://www.reddit.com:443/r/test/comments/abc123/a_title/",
                "https://www.reddit.com/r/test/comments/abc123/a_title/?utm_source=share&amp;utm_medium=ios_app&amp;utm_name=iossmf",
                "reddit.com/r/test/comments/abc123/a_title",
                "/r/test/comments/abc123/a_title/",
                "Look at this: https://www.reddit.com/r/test/comments/abc123/a_title/?utm_source=share.",
                "Post title\nhttps://reddit.com/r/test/comments/abc123/a_title/");
        routes(comments("abc123", null, null),
                "https://redd.it/abc123", "http://redd.it/abc123/", "https://redd.it/ABC123?utm_source=share",
                "redd.it/abc123", "https://www.reddit.com/comments/abc123",
                "https://www.reddit.com/user/someone/comments/abc123/a_title/",
                "https://www.reddit.com/u/someone/comments/abc123/");
        Route context = Sync.route("https://www.reddit.com/comments/abc123?context=3", true);
        equal(comments("abc123", null, null), context, "short link with context");
        normalized("https://www.reddit.com/comments/abc123/?context=3", "https://www.reddit.com/comments/abc123?context=3");
        normalized("https://www.reddit.com/r/test/comments/abc123/?context=3",
                "https://www.reddit.com/r/test/comments/abc123/a_title/?context=3");

        // Comments: old /title/ID and new /comment/ID forms, with context and sort kept.
        routes(comments("abc123", "def456", "test"),
                "https://www.reddit.com/r/test/comments/abc123/a_title/def456/",
                "https://www.reddit.com/r/test/comments/abc123/comment/def456/?context=3",
                "https://old.reddit.com/r/test/comments/abc123/a_title/def456/?context=3&sort=new#thing_t1_def456",
                "https://sh.reddit.com/r/test/comments/abc123/comment/def456",
                "/r/test/comments/abc123/_/DEF456");
        normalized("https://www.reddit.com/r/test/comments/abc123/_/def456/?context=3&sort=new",
                "https://old.reddit.com/r/test/comments/abc123/a_title/def456/?context=3&utm_source=share&sort=new#thing");
        normalized("https://www.reddit.com/r/test/comments/abc123/_/def456/?context=3",
                "https://www.reddit.com/r/test/comments/abc123/comment/def456/?context=3&utm_medium=web2x&share_id=abc");
        // np keeps Sync's no-participation flag.
        equal(new Route("comments-np", "abc123", "def456", "test"),
                Sync.route("https://np.reddit.com/r/test/comments/abc123/a_title/def456", true), "np comments");
        normalized("https://np.reddit.com/r/test/comments/abc123/_/def456/",
                "https://np.reddit.com/r/test/comments/abc123/a_title/def456");
        // Unusual query characters are encoded so Sync's comment classifier still matches.
        normalized("https://www.reddit.com/r/test/comments/abc123/?sort=new&x=a%2Cb%7Ec",
                "https://www.reddit.com/r/test/comments/abc123/t/?sort=new&x=a,b~c");
        // Sync's own synthetic gallery link is left for the gallery route.
        unchanged("https://www.reddit.com/r/test/comments/abc123?is_gallery=true");

        // Galleries reach Sync's gallery viewer, which loads them by post ID.
        routes(new Route("gallery", "abc123"),
                "https://www.reddit.com/gallery/abc123",
                "https://reddit.com/gallery/abc123/",
                "https://old.reddit.com/gallery/ABC123?utm_source=share&utm_medium=android_app",
                "https://sh.reddit.com/gallery/abc123#lightbox",
                "reddit.com/gallery/abc123",
                "https://www.reddit.com/r/test/comments/abc123?is_gallery=true");
        check(LinkNormalizer.isGalleryLink("https://www.reddit.com/gallery/abc123"), "canonical gallery accepted");
        for (String other : new String[] {null, "", "https://www.reddit.com/gallery/abc123/", "https://www.reddit.com/gallery/abc123?x=1",
                "http://www.reddit.com/gallery/abc123", "https://reddit.com/gallery/abc123", "https://www.reddit.com/gallery/ABC123",
                "https://evil.invalid/gallery/abc123", "https://www.reddit.com/gallery/abc_123", "https://www.reddit.com/gallery/"}) {
            check(!LinkNormalizer.isGalleryLink(other), "only the canonical gallery form is a gallery: " + other);
        }
        unchanged("https://www.reddit.com/gallery/abc_123", "https://www.reddit.com/gallery/abc123/extra");

        // Profiles.
        routes(new Route("user", "someone"),
                "https://www.reddit.com/user/someone", "https://www.reddit.com/user/someone/",
                "https://www.reddit.com/u/someone", "https://reddit.com/u/someone/?utm_source=share",
                "https://sh.reddit.com/user/someone/submitted/?sort=top", "https://new.reddit.com/user/someone/comments/",
                "https://np.reddit.com/u/someone", "/u/someone", "/user/someone/overview");
        routes(new Route("user", "some-one_2"), "https://www.reddit.com/user/some-one_2?utm_name=x");

        // Subreddit listings.
        routes(new Route("subreddit", "test"),
                "https://www.reddit.com/r/test", "https://reddit.com/r/test/", "https://np.reddit.com/r/test",
                "https://sh.reddit.com/r/test/?utm_source=share", "https://www.reddit.com/r/test/top/?t=week",
                "https://www.reddit.com/r/test/new", "/r/test/");
        normalized("https://www.reddit.com/r/test/top", "https://www.reddit.com/r/test/TOP/?t=week");

        // Share links reach the secured /s/ resolver, case preserved, browser fallback kept.
        routes(new Route("share", "https://www.reddit.com/r/test/s/AbCd12"),
                "https://www.reddit.com/r/test/s/AbCd12", "https://www.reddit.com/r/test/s/AbCd12/",
                "http://reddit.com/r/test/s/AbCd12", "https://sh.reddit.com/r/test/s/AbCd12?utm_source=share",
                "reddit.com/r/test/s/AbCd12", "Shared via Reddit: https://www.reddit.com/r/test/s/AbCd12");
        routes(new Route("share", "https://www.reddit.com/u/someone/s/AbCd12"),
                "https://www.reddit.com/u/someone/s/AbCd12", "https://reddit.com/user/someone/s/AbCd12/");
        routes(new Route("share-browser", "https://www.reddit.com/r/test/s/AbCd12#bypass"),
                "https://www.reddit.com/r/test/s/AbCd12#bypass");
        normalized("https://www.reddit.com/r/test/s/AbCd12", "https://www.reddit.com/r/test/s/AbCd12#other");

        // Direct Reddit media: signed parameters stay byte-for-byte and in order.
        String signed = "https://preview.redd.it/abc123.jpg?width=640&format=pjpg&auto=webp&s=0123abcd%2Bef";
        routes(new Route("media", signed), signed, signed + "#x",
                "https://preview.redd.it/abc123.jpg?width=640&utm_source=share&format=pjpg&auto=webp&s=0123abcd%2Bef");
        routes(new Route("media", "https://i.redd.it/abc123.jpg"),
                "https://i.redd.it/abc123.jpg", "http://i.redd.it/abc123.jpg", "HTTPS://I.REDD.IT/abc123.jpg#frag",
                "i.redd.it/abc123.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc123.jpg",
                "https://reddit.com/media?url=https://i.redd.it/abc123.jpg",
                "https://www.reddit.com/media?url=https%253A%252F%252Fi.redd.it%252Fabc123.jpg",
                "https://www.reddit.com/media?rdt=1&url=https%3A%2F%2Fi.redd.it%2Fabc123.jpg&utm_source=share");
        routes(new Route("media", "https://preview.redd.it/abc123.png?width=640&s=sig"),
                "https://www.reddit.com/media?url=https%3A%2F%2Fpreview.redd.it%2Fabc123.png%3Fwidth%3D640%26s%3Dsig");
        routes(new Route("media", "https://external-preview.redd.it/abc.jpg?auto=webp&s=sig"),
                "https://www.reddit.com/media?url=https%3A%2F%2Fexternal-preview.redd.it%2Fabc.jpg%3Fauto%3Dwebp%26s%3Dsig");
        routes(new Route("video", "https://v.redd.it/abc123"), "https://v.redd.it/abc123", "http://v.redd.it/abc123#t");

        // Wrapper decoding is bounded and only unwraps to direct Reddit media.
        String triple = "https://www.reddit.com/media?url=https%25253A%25252F%25252Fi.redd.it%25252Fabc123.jpg";
        String longUrl = "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2F" + "a".repeat(LinkNormalizer.MAX_WRAPPED) + ".jpg";
        unchanged(triple, longUrl,
                "https://www.reddit.com/media?url=https%3A%2F%2Fevil.invalid%2Fabc.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it.evil.invalid%2Fabc.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%40evil.invalid%2Fabc.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fuser%40i.redd.it%2Fabc.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%3A8443%2Fabc.jpg",
                "https://www.reddit.com/media?url=javascript%3Aalert(1)",
                "https://www.reddit.com/media?url=file%3A%2F%2F%2Fdata%2Fx",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc.jpg%0D%0AX%3A1",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2F%ZZ.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fwww.reddit.com%2Fmedia%3Furl%3Dhttps%253A%252F%252Fi.redd.it%252Fa.jpg",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fa.jpg&url=https%3A%2F%2Fevil.invalid%2F",
                "https://www.reddit.com/media", "https://www.reddit.com/media?url=",
                "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%5C%40evil.invalid%2F");

        // Everything else keeps Sync's existing behavior.
        unchanged(null, "", "   ", "r/test", "u/someone", "sync://reddit.com/r/test",
                "https://www.reddit.com/", "https://www.reddit.com/r/test/wiki/index",
                "https://www.reddit.com/r/test/about/rules", "https://www.reddit.com/message/compose?to=someone",
                "https://www.reddit.com/r/test/search?q=a", "https://www.reddit.com/domain/example.com",
                "https://www.reddit.com/user/someone/m/multi", "https://www.reddit.com/r/test/duplicates/abc123",
                "https://old.reddit.com/over18?dest=https%3A%2F%2Fold.reddit.com%2Fr%2Ftest",
                "https://www.reddit.com/link/abc123/video/def456/player", "https://www.reddit.com/poll/abc123",
                "https://www.google.com/amp/s/amp.reddit.com/r/test/comments/abc123/",
                "https://example.com/r/test/comments/abc123", "https://reddit.com.evil.invalid/r/test/comments/abc123",
                "https://evil.invalid/?u=https://www.reddit.com/r/test/comments/abc123",
                "https://user@www.reddit.com/r/test/comments/abc123", "https://www.reddit.com:8443/r/test/comments/abc123",
                "https://www.reddit.com./r/test/comments/abc123", "https://%72eddit.com/r/test/comments/abc123",
                "https://www.reddit.com/r/te%2Fst/comments/abc123", "https://www.reddit.com/r/test/comments/abc_123",
                "https://www.reddit.com/r/test/comments/abc123/a/b/c", "https://www.reddit.com/r/test/comments/abc123/a/bad_id",
                "https://www.reddit.com/r//comments/abc123", "https://www.reddit.com/r/test//comments/abc123",
                "https://www.reddit.com/r/test.dot/comments/abc123", "https://www.reddit.com/r/a+b",
                "https://www.reddit.com/r/test/s/Ab-Cd", "https://redd.it/abc_123", "https://redd.it/",
                "https://redd.it/abc123/extra", "ftp://www.reddit.com/r/test/comments/abc123",
                "javascript:alert(1)", "https://i.redd.it/", "https://www.reddit.com/r/test\\@evil.invalid/comments/abc123",
                "no link in this text");
        // Header-like text after a line break never survives into the link.
        normalized("https://www.reddit.com/r/test/comments/abc123/", "https://www.reddit.com/r/test/comments/abc123\r\nX: y");
        // Shared text with an unrecognized link is reduced to that link.
        equal("https://example.com/page", LinkNormalizer.normalize("Read https://example.com/page."), "first link from shared text");
        equal("https://example.com/a", LinkNormalizer.normalize("two https://example.com/a and https://redd.it/abc123"),
                "only the first link is used");
        // Oversized input is not parsed.
        String huge = "https://www.reddit.com/r/test/comments/abc123/" + "a".repeat(LinkNormalizer.MAX_INPUT);
        equal(huge, LinkNormalizer.normalize(huge), "oversized input unchanged");

        // Every share-link rewrite still passes the share-link security validation.
        List<String> shares = new ArrayList<>(Arrays.asList("http://reddit.com/r/test/s/AbCd12", "reddit.com/u/someone/s/AbCd12",
                "https://m.reddit.com/user/someone/s/AbCd12/?utm_source=share"));
        for (String share : shares) check(SafeSLinks.isShareLink(LinkNormalizer.normalize(share)), "normalized share is valid: " + share);
        System.out.println("PASS: " + checks + " Reddit link normalization checks (Sync 23033 routing model; no network)");
    }
}
