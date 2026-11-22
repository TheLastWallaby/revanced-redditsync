import app.revanced.extension.synccompat.ChatCompose;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Checks ChatCompose's subject rule: which recipients keep Sync's Subject field, which subject is sent
 * for a message to a user, and that Reddit's 100-character limit is kept.
 */
public final class ChatComposeTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static Object hidden(String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = ChatCompose.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static boolean show(String to, String subject) throws ReflectiveOperationException {
        return (Boolean) hidden("showSubject", new Class<?>[] {String.class, String.class}, to, subject);
    }

    public static void main(String[] args) throws Throwable {
        // Subject made from the first line with words in it.
        equal("Hi there", ChatCompose.subject("alice", "", "Hi there\nSecond line"), "first line");
        equal("Hi there", ChatCompose.subject("alice", null, "\r\n  \n  Hi   there  \r\nmore"), "blank lines and spacing skipped");
        equal("Hi there", ChatCompose.subject("alice", "  ", "Hi there"), "blank subject replaced");
        equal("Heading", ChatCompose.subject("alice", "", "## Heading\nbody"), "heading marker dropped");
        equal("quoted text", ChatCompose.subject("alice", "", "> > quoted text"), "quote markers dropped");
        equal("#1 question", ChatCompose.subject("alice", "", "#1 question"), "a hash that is not a heading is kept");
        equal("Message", ChatCompose.subject("alice", "", "#\n>\n"), "no words: fallback subject");
        equal("café 😀", ChatCompose.subject("alice", "", "café 😀\nx"), "non-ASCII kept");

        // Reddit's limit: at most 100 characters, cut at a word where possible, never inside a surrogate pair.
        String words = "word ".repeat(60).trim();
        String cut = ChatCompose.subject("alice", "", words);
        check(cut.length() <= 100 && cut.endsWith("…") && !cut.contains(" …"), "cut at a word: " + cut);
        check(words.startsWith(cut.substring(0, cut.length() - 1)), "cut keeps the start");
        String oneWord = "x".repeat(250);
        equal("x".repeat(99) + "…", ChatCompose.subject("alice", "", oneWord), "a long word is cut at 99");
        String emoji = "😀".repeat(150);
        String emojiCut = ChatCompose.subject("alice", "", emoji);
        equal(100, emojiCut.codePointCount(0, emojiCut.length()), "100 characters, counted as code points");
        check(emojiCut.endsWith("…") && Character.isLowSurrogate(emojiCut.charAt(emojiCut.length() - 2)), "cut after a whole emoji");
        String exact = "y".repeat(100);
        equal(exact, ChatCompose.subject("alice", "", exact), "exactly 100 characters kept whole");

        // Unchanged: entered subjects, subreddit recipients, missing values.
        equal("Bike", ChatCompose.subject("alice", "Bike", "Hi"), "entered subject kept");
        equal(" Bike ", ChatCompose.subject("alice", " Bike ", "Hi"), "entered subject sent exactly as typed");
        for (String to : new String[] {"r/bikes", "/r/bikes", "R/Bikes", " /R/bikes "}) {
            equal("", ChatCompose.subject(to, "", "Hi"), to + ": Sync still asks for a subject");
            equal("Question", ChatCompose.subject(to, "Question", "Hi"), to + ": subject kept");
        }
        equal("", ChatCompose.subject("alice", "", ""), "no message: Sync's own check decides");
        equal(null, ChatCompose.subject("alice", null, null), "null message");
        equal("", ChatCompose.subject("alice", "", "   \n "), "blank message");
        equal("Hi", ChatCompose.subject(null, "", "Hi"), "no recipient yet: Sync asks for the recipient first anyway");
        equal("Hi", ChatCompose.subject("u/alice", "", "Hi"), "u/ recipient is a user");
        equal("Hi", ChatCompose.subject("rbikes", "", "Hi"), "a username starting with r is a user");
        equal("Hi", ChatCompose.subject("r_bikes", "", "Hi"), "a username starting with r_ is a user");

        // The Subject field is shown for subreddit recipients or when it already has text.
        check(!show("alice", ""), "hidden for a user");
        check(!show("", ""), "hidden before a recipient is typed");
        check(!show(null, null), "hidden for null values");
        check(!show("alice", "  "), "hidden for a blank subject");
        check(show("r/bikes", ""), "shown for r/");
        check(show("/r/bikes", ""), "shown for /r/");
        check(show("alice", "Re: bike"), "shown when a subject is already filled in");
        check(!show("r", ""), "shown only once r/ is complete");
        equal(true, hidden("isSubreddit", new Class<?>[] {String.class}, "/R/x"), "isSubreddit /R/x");

        System.out.println("PASS: " + checks + " chat compose subject checks");
    }
}
