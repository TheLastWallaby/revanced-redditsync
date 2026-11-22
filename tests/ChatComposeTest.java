import app.revanced.extension.synccompat.ChatCompose;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Checks ChatCompose's subject rule: which recipients keep Sync's Subject field, and that a message to
 * a user is sent with the invisible chat subject (Reddit's chat shows the subject above the message).
 */
public final class ChatComposeTest {
    private static final String CHAT = "​";
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
        // A chat to a user: the invisible subject, whatever the message says.
        for (String text : new String[] {"Hi there\nSecond line", "\r\n  \n  Hi", "# Heading", "x".repeat(500), "😀"})
            equal(CHAT, ChatCompose.subject("alice", "", text), "chat subject for a " + text.length() + "-character message");
        equal(CHAT, ChatCompose.subject("alice", null, "Hi"), "no subject value");
        equal(CHAT, ChatCompose.subject("alice", "  ", "Hi"), "blank subject replaced");
        equal(CHAT, ChatCompose.subject("u/alice", "", "Hi"), "u/ recipient is a user");
        equal(CHAT, ChatCompose.subject("rbikes", "", "Hi"), "a username starting with r is a user");
        equal(CHAT, ChatCompose.subject("r_bikes", "", "Hi"), "a username starting with r_ is a user");
        equal(CHAT, ChatCompose.subject(null, "", "Hi"), "no recipient yet: Sync asks for the recipient first anyway");

        // Reddit's blank-subject check rejects whitespace only; the chat subject is one invisible character.
        char zeroWidth = CHAT.charAt(0);
        check(CHAT.length() == 1 && !Character.isWhitespace(zeroWidth) && !Character.isSpaceChar(zeroWidth), "not whitespace");
        check(!CHAT.trim().isEmpty() && !CHAT.strip().isEmpty(), "not blank after trimming");
        equal((int) Character.FORMAT, Character.getType(zeroWidth), "a zero-width format character");

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
