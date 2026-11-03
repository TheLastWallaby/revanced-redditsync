package app.revanced.extension.synccompat;

import android.content.Context;
import com.android.volley.AuthFailureError;
import com.android.volley.ClientError;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Response;
import com.android.volley.ServerError;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Checks MessagingHandoff against a loopback Reddit (com.sun.net.httpserver, test-only token):
 * Reddit's RESTRICTED_TO_PM answer for a new message and for a message reply starts the handoff
 * (dialog for a new message; saved draft, toast and Chat/archive notification actions for a
 * reply), while successful answers, other Reddit errors, comment replies, network failures, HTTP
 * 401 (token refresh), 429 and 5xx keep Sync's handling. Also the fixed HTTPS handoff addresses,
 * malformed and oversized answers, and when a browser is asked instead of Sync itself.
 */
public final class MessagingHandoffTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    static final String RETIRED = "{\"json\": {\"errors\": [[\"RESTRICTED_TO_PM\", "
            + "\"User doesn't accept direct messages. Try sending a chat request instead.\", \"to\"]]}}";
    static final String SENT = "{\"json\": {\"errors\": []}}";
    static final String REPLIED = "{\"json\": {\"errors\": [], \"data\": {\"things\": [{\"kind\": \"t4\", \"data\": "
            + "{\"id\": \"2abc\", \"name\": \"t4_2abc\", \"body\": \"Thanks\"}}]}}}";
    static final String NO_USER = "{\"json\": {\"errors\": [[\"USER_DOESNT_EXIST\", \"that user doesn't exist\", \"to\"]]}}";
    static final String RATELIMIT = "{\"json\": {\"errors\": [[\"RATELIMIT\", \"Take a break for 3 minutes.\", \"ratelimit\"]]}}";
    static final String TOKEN = "test-only-token-7f3a";

    /** Records what the handoff asks of the phone. */
    static final class Recorder implements MessagingHandoff.Platform {
        final List<String> events = new ArrayList<>();
        @Override public void main(Runnable task) { events.add("main"); task.run(); }
        @Override public void toast(Context context, String message) { events.add("toast " + message); }
        @Override public void saveDraft(Context context, String text) { events.add("draft " + text); }
        @Override public void composeDialog(Context context, String title, String message) { events.add("dialog " + title + " | " + message); }
        @Override public void addActions(Object builder, Context context) { events.add("actions " + builder); }
    }

    private static Context context() throws ReflectiveOperationException {
        // android.jar's constructors throw "Stub!"; the handoff only hands the context to the platform.
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Context) ((sun.misc.Unsafe) field.get(null)).allocateInstance(android.content.ContextWrapper.class);
    }

    public static void main(String[] args) throws Exception {
        Recorder recorder = new Recorder();
        MessagingHandoff.platform = recorder;
        Context context = context();
        detection();
        addresses();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        Map<String, Object[]> answers = new LinkedHashMap<>();
        answers.put("/compose/retired", new Object[] {200, RETIRED});
        answers.put("/compose/sent", new Object[] {200, SENT});
        answers.put("/compose/no-user", new Object[] {200, NO_USER});
        answers.put("/compose/ratelimit", new Object[] {200, RATELIMIT});
        answers.put("/comment/retired", new Object[] {200, RETIRED});
        answers.put("/comment/replied", new Object[] {200, REPLIED});
        answers.put("/status/401", new Object[] {401, "{\"message\": \"Unauthorized\", \"error\": 401}"});
        answers.put("/status/403-retired", new Object[] {403, RETIRED});
        answers.put("/status/403", new Object[] {403, "{\"message\": \"Forbidden\", \"error\": 403}"});
        answers.put("/status/429", new Object[] {429, "{\"message\": \"Too Many Requests\", \"error\": 429}"});
        answers.put("/status/500", new Object[] {500, "<html>Internal Server Error</html>"});
        answers.put("/status/503", new Object[] {503, ""});
        List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Object[] answer = answers.get(exchange.getRequestURI().getPath());
            byte[] body = answer == null ? new byte[0] : ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(answer == null ? 404 : (Integer) answer[0], body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            compose(base, context, recorder);
            reply(base, context, recorder);
            check(authorizations.size() >= answers.size(), "loopback answers were requested: " + authorizations.size());
            check(authorizations.stream().allMatch(("bearer " + TOKEN)::equals), "only the test-only token was sent");
        } finally {
            server.stop(0);
        }
        System.out.println("PASS: " + checks + " messaging handoff checks (loopback Reddit; test-only token)");
    }

    // ---- Loopback ----

    private static NetworkResponse fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "bearer " + TOKEN);
        try (OutputStream out = connection.getOutputStream()) {
            out.write("api_type=json&to=someone&subject=Hi&text=Hello".getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (in != null) try (in) { in.transferTo(body); }
        List<Header> headers = new ArrayList<>();
        connection.getHeaderFields().forEach((name, values) -> {
            if (name != null) for (String value : values) headers.add(new Header(name, value));
        });
        return new NetworkResponse(status, body.toByteArray(), false, 0, headers);
    }

    /** What Sync's Volley turns an error status into. */
    private static VolleyError statusError(NetworkResponse response) {
        if (response.statusCode == 401 || response.statusCode == 403) return new AuthFailureError(response);
        if (response.statusCode >= 500) return new ServerError(response);
        return new ClientError(response);
    }

    private static void compose(String base, Context context, Recorder recorder) throws Exception {
        // RESTRICTED_TO_PM: Sync's parser gets a RetiredRouteError with Reddit's text; the listener shows the dialog.
        Response<Boolean> retired = MessagingHandoff.composeResponse(fetch(base + "/compose/retired"));
        check(retired != null && !retired.isSuccess() && retired.error instanceof MessagingHandoff.RetiredRouteError,
                "retired compose answer becomes the handoff error");
        equal("User doesn't accept direct messages. Try sending a chat request instead.", retired.error.getMessage(),
                "Reddit's text kept in the error");
        recorder.events.clear();
        check(MessagingHandoff.composeFailed(context, retired.error), "listener shows the handoff");
        equal(List.of("dialog Message not sent | Reddit now sends direct messages through Reddit Chat. "
                + "Your message is still here, so you can copy it into a chat."), recorder.events, "compose dialog");
        // Successful answers and other Reddit errors: Sync parses them itself.
        for (String path : List.of("/compose/sent", "/compose/no-user", "/compose/ratelimit"))
            check(MessagingHandoff.composeResponse(fetch(base + path)) == null, "Sync parses " + path);
        // Sync's own errors for other answers keep Sync's toast.
        recorder.events.clear();
        for (VolleyError error : List.of(new VolleyError("that user doesn't exist"), new VolleyError("Take a break for 3 minutes."),
                new NoConnectionError(), new TimeoutError(), new VolleyError()))
            check(!MessagingHandoff.composeFailed(context, error), "Sync handles " + error);
        // Error statuses: only the retired answer hands off; 401 (Sync refreshes the token), 403, 429, 5xx don't.
        for (String status : List.of("401", "403", "429", "500", "503")) {
            NetworkResponse response = fetch(base + "/status/" + status);
            equal(Integer.parseInt(status), response.statusCode, "loopback status " + status);
            check(MessagingHandoff.composeResponse(response) == null, "no handoff for the body of HTTP " + status);
            check(!MessagingHandoff.composeFailed(context, statusError(response)), "Sync handles HTTP " + status);
        }
        check(recorder.events.isEmpty(), "no handoff for other errors: " + recorder.events);
        check(MessagingHandoff.composeFailed(context, statusError(fetch(base + "/status/403-retired"))), "retired answer with an error status");
        check(!MessagingHandoff.composeFailed(null, retired.error), "no dialog without the editor's activity");
        check(MessagingHandoff.composeResponse(null) == null, "no response");
    }

    private static void reply(String base, Context context, Recorder recorder) throws Exception {
        // A reply to a message (type 2) refused as a retired route: draft saved, toast, notification text, actions.
        Object request = new Object();
        recorder.events.clear();
        MessagingHandoff.replyResponse(request, 2, new String(fetch(base + "/comment/retired").data, StandardCharsets.UTF_8));
        String notice = MessagingHandoff.replyNotice(request, "My reply\n\nwith two lines", context);
        equal("Reddit now sends direct messages through Reddit Chat. Old private messages are read-only. "
                + "Your reply was saved as a draft.", notice, "notification text");
        MessagingHandoff.replyActions(request, "builder", context);
        equal(List.of("main", "draft My reply\n\nwith two lines", "toast " + notice, "actions builder"), recorder.events,
                "reply handoff");
        recorder.events.clear();
        MessagingHandoff.replyActions(request, "builder", context);
        check(MessagingHandoff.replyNotice(request, "again", context) == null && recorder.events.isEmpty(), "handoff used once");

        // The same answer with an error status (parseNetworkError).
        Object refused = new Object();
        MessagingHandoff.replyError(refused, 2, statusError(fetch(base + "/status/403-retired")));
        check(MessagingHandoff.replyNotice(refused, "Reply", context) != null, "retired error status hands off");
        MessagingHandoff.replyActions(refused, "b2", context);

        // Unchanged: comment and post replies (types 0, 1), edits (3), a successful reply, other errors,
        // Sync's network failures and error statuses, a blank draft is not saved.
        recorder.events.clear();
        for (int type : new int[] {0, 1, 3}) {
            Object other = new Object();
            MessagingHandoff.replyResponse(other, type, RETIRED);
            MessagingHandoff.replyError(other, type, statusError(fetch(base + "/status/403-retired")));
            check(MessagingHandoff.replyNotice(other, "Comment text", context) == null, "type " + type + " keeps Sync's report");
        }
        for (String path : List.of("/comment/replied", "/compose/no-user", "/compose/ratelimit")) {
            Object other = new Object();
            MessagingHandoff.replyResponse(other, 2, new String(fetch(base + path).data, StandardCharsets.UTF_8));
            check(MessagingHandoff.replyNotice(other, "Reply", context) == null, "Sync reports " + path);
        }
        for (VolleyError error : List.of(new NoConnectionError(), new TimeoutError(), new VolleyError("x"))) {
            Object other = new Object();
            MessagingHandoff.replyError(other, 2, error);
            check(MessagingHandoff.replyNotice(other, "Reply", context) == null, "Sync reports " + error);
        }
        for (String status : List.of("401", "403", "429", "500", "503")) {
            Object other = new Object();
            MessagingHandoff.replyError(other, 2, statusError(fetch(base + "/status/" + status)));
            check(MessagingHandoff.replyNotice(other, "Reply", context) == null, "Sync reports HTTP " + status);
        }
        MessagingHandoff.replyActions(new Object(), "b3", context);
        check(recorder.events.isEmpty(), "no handoff for other replies: " + recorder.events);
        Object blank = new Object();
        MessagingHandoff.replyResponse(blank, 2, RETIRED);
        check(MessagingHandoff.replyNotice(blank, "  ", context) != null, "blank reply still hands off");
        equal(List.of("main", "toast " + MessagingHandoff.MESSAGE + " " + MessagingHandoff.REPLY_DETAIL), recorder.events,
                "blank reply is not saved as a draft");
        MessagingHandoff.replyResponse(null, 2, RETIRED);
        MessagingHandoff.replyResponse(new Object(), 2, null);
        MessagingHandoff.replyError(new Object(), 2, null);
        check(MessagingHandoff.replyNotice(null, "x", context) == null, "null request");
    }

    // ---- Answers ----

    private static boolean retired(String body) {
        return MessagingHandoff.isRetired(body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static void detection() {
        check(retired(RETIRED), "Reddit's answer");
        check(retired("{\"json\":{\"errors\":[[\"RESTRICTED_TO_PM\",\"\",\"to\"]]}}"), "compact, empty text");
        check(retired("{\"json\": {\"errors\": [[\"BAD_CAPTCHA\", \"x\", \"captcha\"], [\"RESTRICTED_TO_PM\", \"y\", \"to\"]]}}"),
                "among other errors");
        check(retired("{\"json\": {\"errors\": [[\"RESTRICTED_TO_PM\"]]}}"), "code alone");
        equal("", MessagingHandoff.retiredReason("{\"json\":{\"errors\":[[\"RESTRICTED_TO_PM\"]]}}".getBytes(StandardCharsets.UTF_8)),
                "code alone has no text");
        for (String body : new String[] {
            SENT, REPLIED, NO_USER, RATELIMIT,
            "{\"json\": {\"errors\": [[\"NOT_WHITELISTED_BY_USER_MESSAGE\", \"Can't send a message to that user.\", \"to\"]]}}",
            "{\"json\": {\"errors\": [[\"DELETED_COMMENT\", \"that comment has been deleted\", \"parent\"]]}}",
            "{\"json\": {\"errors\": [[\"TOO_OLD\", \"that's a piece of history now\", \"parent\"]]}}",
            "{\"json\": {\"errors\": [[\"restricted_to_pm\", \"x\", \"to\"]]}}",
            "{\"json\": {\"errors\": [[\"OTHER\", \"RESTRICTED_TO_PM\", \"to\"]]}}",
            "{\"json\": {\"errors\": [\"RESTRICTED_TO_PM\"]}}",
            "{\"errors\": [[\"RESTRICTED_TO_PM\", \"x\", \"to\"]]}",
            "{\"json\": {\"errors\": \"RESTRICTED_TO_PM\"}}",
            "{\"json\": {\"data\": {\"errors\": [[\"RESTRICTED_TO_PM\"]]}}}",
            "[[\"RESTRICTED_TO_PM\"]]", "RESTRICTED_TO_PM", "<html>RESTRICTED_TO_PM</html>", "{\"json\": ", "", "null", null,
        }) check(!retired(body), "not the retired answer: " + body);
        int saved = MessagingHandoff.maxBody;
        MessagingHandoff.maxBody = RETIRED.length() - 1;
        try {
            check(!retired(RETIRED), "an answer over the size limit is not inspected");
        } finally {
            MessagingHandoff.maxBody = saved;
        }
        StringBuilder huge = new StringBuilder("{\"json\": {\"errors\": [[\"RESTRICTED_TO_PM\", \"");
        while (huge.length() < 70_000) huge.append("x");
        check(!retired(huge.append("\"]]}}").toString()), "70 KB answer not inspected");
    }

    private static void addresses() {
        for (String url : List.of(MessagingHandoff.CHAT_URL, MessagingHandoff.ARCHIVE_URL)) {
            URI uri = URI.create(url);
            equal("https", uri.getScheme(), "HTTPS " + url);
            equal("www.reddit.com", uri.getHost(), "Reddit host " + url);
            check(uri.getPort() == -1 && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                    "plain address " + url);
        }
        equal("https://www.reddit.com/chat", MessagingHandoff.CHAT_URL, "Reddit Chat address");
        equal("https://www.reddit.com/notifications", MessagingHandoff.ARCHIVE_URL, "message archive (notifications inbox)");
        check(MessagingHandoff.needsBrowser("com.laurencedawson.reddit_sync", "com.laurencedawson.reddit_sync"), "Sync itself: browser");
        check(!MessagingHandoff.needsBrowser("com.reddit.frontpage", "com.laurencedawson.reddit_sync"), "Reddit app kept");
        check(!MessagingHandoff.needsBrowser("android", "com.laurencedawson.reddit_sync"), "chooser kept");
        check(!MessagingHandoff.needsBrowser(null, "com.laurencedawson.reddit_sync"), "unresolved kept");
    }
}
