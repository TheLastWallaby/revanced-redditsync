import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/**
 * Applies "Sync: upload images to Reddit" and runs the Sync methods it hooks before and after it:
 * on hand-written models, or, given a local stock Sync 23033 APK, on the real methods. Android and
 * Sync services are stand-ins that record what Sync asked for; RedditImageUpload runs for real,
 * with its own services replaced by recorders (no network).
 *
 * Before: an upload queues Sync's Imgur request; the editor offers the uploaded link in its Insert
 * image dialog; an image post is a link post, and Reddit's websocket reply to an image post fails
 * Sync's parser; a comment sent with Reddit's richtext reply fails Sync's parser. After: the upload
 * goes to the helper with Sync's own arguments and nothing reaches Imgur; the editor inserts Reddit's
 * media marker for this session's uploads only; a comment or text post with an image is converted
 * before Sync sends it on through its own token handling (m8.a.b or m8.a.g); an uploaded image's
 * post is Reddit's image post, identified through the websocket; Reddit's bare comment reaches
 * Sync's parser and its "media not allowed" error reaches Sync's error report. Everything else
 * runs as before. Only the expected instructions change; duplicate and unknown-layout application
 * are rejected without edits.
 */
public final class RedditImageUploadPatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/RedditImageUpload;";
    private static final String HELPER_CLASS = "app.revanced.extension.synccompat.RedditImageUpload";
    private static final String NETWORK = "Lcom/android/volley/NetworkResponse;";
    private static final String EDITOR = "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;";
    private static final String UPLOAD_ARGS = "Landroid/content/Context;Landroid/net/Uri;Lcom/android/volley/Response$Listener;"
            + "Lcom/android/volley/Response$ErrorListener;";
    private static final List<String> SUBMIT_INIT = List.of("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;",
            "Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z", "Z", "Lp8/a;",
            "Lcom/android/volley/Response$Listener;", "Lcom/android/volley/Response$ErrorListener;");
    private static final String HOST = "https://reddit-uploaded-media.s3-accelerate.amazonaws.com/";
    private static final String SOCKET = "k8s-lb.wss.redditmedia.com";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-image-upload-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, new String[] {"tests/fixtures/redditupload"}), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            hooks(patched.context, before);

            reset();
            uploads(stock, patched);
            editor(stock, patched);
            sending(stock, patched);
            imagePosts(stock, patched);
            commentReplies(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " Reddit image upload patch checks (upload, editor, send, image post and comment hooks; "
                    + "executed " + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- The helper's services, recorded ----

    private static final List<String> HELPER_EVENTS = new ArrayList<>();
    private static final List<Request<?>> QUEUED = new ArrayList<>();
    private static Sync resender;
    private static Object resendContext;

    private static void reset() throws ReflectiveOperationException {
        Class<?> type = Class.forName(HELPER_CLASS);
        Method reset = type.getDeclaredMethod("resetState");
        reset.setAccessible(true);
        reset.invoke(null);
        Class<?> platformType = Class.forName(HELPER_CLASS + "$Platform");
        Object platform = Proxy.newProxyInstance(platformType.getClassLoader(), new Class<?>[] {platformType}, (self, method, a) -> {
            switch (method.getName()) {
                case "background": case "main": ((Runnable) a[0]).run(); return null;
                case "later": return null;
                case "signedIn": return true;
                case "queue": QUEUED.add((Request<?>) a[1]); HELPER_EVENTS.add("queued " + ((Request<?>) a[1]).getUrl()); return null;
                case "params": return ((DexRunner.SyncObject) a[0]).fields.get("s");
                case "resend":
                    HELPER_EVENTS.add("resend " + a[2] + (Boolean.TRUE.equals(a[3]) ? " exchange" : " direct"));
                    if (Boolean.TRUE.equals(a[3])) resender.run.call("Lm8/a;", "g", resendContext, a[1], a[2]);
                    else resender.run.call("Lm8/a;", "b", resendContext, a[2], a[1]);
                    return null;
                case "fail": HELPER_EVENTS.add("fail " + a[1]); return null;
                case "toast": HELPER_EVENTS.add("toast " + a[0]); return null;
                case "hashCode": return System.identityHashCode(self);
                case "equals": return self == a[0];
                default: throw new AssertionError("Unexpected platform call " + method.getName());
            }
        });
        Field field = type.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
        HELPER_EVENTS.clear();
        QUEUED.clear();
    }

    /** Records an upload in the helper, as a finished upload of this session would. */
    @SuppressWarnings("unchecked")
    private static void uploaded(String url, String id) throws ReflectiveOperationException {
        Class<?> type = Class.forName(HELPER_CLASS);
        Field uploads = type.getDeclaredField("UPLOADS");
        uploads.setAccessible(true);
        Constructor<?> asset = Class.forName(HELPER_CLASS + "$Asset").getDeclaredConstructor(String.class, boolean.class);
        asset.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) uploads.get(null);
        synchronized (map) { map.put(url, asset.newInstance(id, false)); }
    }

    private static NetworkResponse reply(String body) {
        return new NetworkResponse(200, body.getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList());
    }

    // ---- Uploads: u8.d.a ----

    private static void uploads(Sync stock, Sync patched) {
        for (Sync sync : List.of(stock, patched)) sync.events.clear();
        Object[] arguments = {new DexRunner.SyncObject("Ltest/Context;"), android.net.Uri.parse("content://test.provider/image/1"),
                new DexRunner.SyncObject("Ltest/Listener;"), new DexRunner.SyncObject("Ltest/ErrorListener;")};
        stock.run.call("Lu8/d;", "a", arguments);
        equal(List.of("request https://imgur-apiv3.p.rapidapi.com/3/image", "read content://test.provider/image/1",
                "request https://raw.githubusercontent.com/laurencedawson/reddit-sync-development/master/api/upload.json",
                "queued Lu8/a; wrapping Lu8/c;"), stock.events, "stock queues the Imgur upload behind Sync's remote switch");

        patched.run.call("Lu8/d;", "a", arguments);
        equal(List.of("helper upload"), patched.events, "patched hands the upload to the helper; nothing reaches Imgur");
        check(patched.uploadArguments != null && patched.uploadArguments.length == 4, "four arguments");
        for (int i = 0; i < 4; i++) check(patched.uploadArguments[i] == arguments[i], "Sync's own argument " + i + " passed through");
    }

    // ---- The editor: EditorView.h ----

    private static void editor(Sync stock, Sync patched) throws ReflectiveOperationException {
        String url = HOST + "upl0ad1";
        uploaded(url, "upl0ad1");
        for (Sync sync : List.of(stock, patched)) {
            sync.events.clear();
            sync.run.run(method(sync.context, EDITOR, "h", List.of("Ljava/lang/String;")), new Object[] {sync.editor(), url});
        }
        equal(List.of("insert image dialog " + url), stock.events, "stock offers the link in Insert image");
        equal(List.of("insert at 5: \n\n![img](upl0ad1)\n\n"), patched.events, "patched inserts Reddit's media marker at the cursor");

        patched.events.clear();
        String other = "https://i.imgur.com/abc.png";
        patched.run.run(method(patched.context, EDITOR, "h", List.of("Ljava/lang/String;")), new Object[] {patched.editor(), other});
        equal(List.of("insert image dialog " + other), patched.events, "other links keep Sync's dialog");
    }

    // ---- Sending text: m8.a.b and m8.a.g ----

    private static void sending(Sync stock, Sync patched) throws Exception {
        String text = "Look\n\n![img](upl0ad1)\n\n";
        String document = "{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"Look\"}]},{\"e\":\"img\",\"id\":\"upl0ad1\",\"c\":\"\"}]}";
        DexRunner.SyncObject context = new DexRunner.SyncObject("Ltest/Context;");
        resendContext = context;
        resender = patched;

        // Stock: sent as Markdown text, the image as literal text.
        DexRunner.SyncObject comment = comment("t1_c1", text);
        stock.events.clear();
        stock.run.call("Lm8/a;", "b", context, null, comment);
        equal(List.of("fetch token for " + params(comment), "queued Lb9/d;"), stock.events, "stock sends the Markdown as it is");

        // Patched: Reddit's converter first; then Sync's own send, with richtext_json.
        patched.events.clear();
        HELPER_EVENTS.clear();
        QUEUED.clear();
        patched.run.call("Lm8/a;", "b", context, null, comment);
        check(patched.events.isEmpty(), "nothing sent before the conversion: " + patched.events);
        equal(List.of("queued https://oauth.reddit.com/api/convert_rte_body_format?raw_json=1"), HELPER_EVENTS, "Reddit's converter queued");
        deliver(QUEUED.get(0), document);
        equal(List.of("queued https://oauth.reddit.com/api/convert_rte_body_format?raw_json=1", "resend null direct"), HELPER_EVENTS,
                "Sync's request sent on through m8.a.b");
        Map<String, String> sent = params(comment);
        check("".equals(sent.get("text")) && document.equals(sent.get("richtext_json")) && "t1_c1".equals(sent.get("thing_id")),
                "sent with richtext_json and an empty text: " + sent);
        equal(List.of("fetch token for " + sent, "queued Lb9/d;"), patched.events, "then Sync's own token handling sends it");

        // A comment without a Reddit image is untouched.
        DexRunner.SyncObject plain = comment("t1_c2", "no image here");
        patched.events.clear();
        HELPER_EVENTS.clear();
        patched.run.call("Lm8/a;", "b", context, "known-token", plain);
        equal(List.of("token known-token", "queued Ly8/e; " + params(plain)), patched.events, "a plain comment is sent as before");
        check(HELPER_EVENTS.isEmpty(), "no conversion for a plain comment");

        // Another account's reply: m8.a.g, sent on through m8.a.g with its refresh token.
        DexRunner.SyncObject reply = comment("t3_p1", text);
        stock.events.clear();
        stock.run.call("Lm8/a;", "g", context, reply, "refresh-of-other-account");
        equal(List.of("exchange refresh-of-other-account for " + params(reply), "queued Lb9/c;"), stock.events, "stock exchanges the token and sends");
        patched.events.clear();
        HELPER_EVENTS.clear();
        QUEUED.clear();
        patched.run.call("Lm8/a;", "g", context, reply, "refresh-of-other-account");
        check(patched.events.isEmpty(), "exchange: nothing sent before the conversion");
        deliver(QUEUED.get(0), document);
        equal("resend refresh-of-other-account exchange", HELPER_EVENTS.get(HELPER_EVENTS.size() - 1), "sent on through m8.a.g");
        equal(List.of("exchange refresh-of-other-account for " + params(reply), "queued Lb9/c;"), patched.events,
                "then Sync exchanges the token and sends it");
        check(params(reply).containsKey("richtext_json") && "".equals(params(reply).get("text")), "exchange: richtext_json sent");

        // An edit with a new image is stopped with a message; Sync sends nothing.
        DexRunner.SyncObject edit = comment("t1_c3", "edited " + text);
        edit.fields.put("test.url", "https://oauth.reddit.com/api/editusertext");
        patched.events.clear();
        HELPER_EVENTS.clear();
        patched.run.call("Lm8/a;", "b", context, null, edit);
        check(patched.events.isEmpty(), "edit with a new image: nothing sent");
        equal(List.of("fail Reddit images can only be added to new comments, replies and text posts. Remove the image to send this."),
                HELPER_EVENTS, "edit with a new image: a clear message");

        // A null request still reaches Sync's own null check.
        patched.events.clear();
        patched.run.call("Lm8/a;", "b", context, null, null);
        check(patched.events.isEmpty(), "null request: Sync returns");
    }

    private static DexRunner.SyncObject comment(String thing, String text) {
        DexRunner.SyncObject comment = new DexRunner.SyncObject("Ly8/e;");
        Map<String, String> params = new HashMap<>();
        params.put("thing_id", thing);
        params.put("text", text);
        params.put("api_type", "json");
        comment.fields.put("s", params);
        comment.fields.put("test.url", "https://oauth.reddit.com/api/comment");
        comment.fields.put("t", "cursor1");
        comment.fields.put("u", 0);
        comment.fields.put("v", new DexRunner.SyncObject("Lxa/d;"));
        return comment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> params(DexRunner.SyncObject request) {
        return new TreeMap<>((Map<String, String>) request.fields.get("s"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void deliver(Request<?> request, String document) {
        ((x8.a) request).deliverResponse(document);
    }

    // ---- Image posts: y8.x0 ----

    private static DexRunner.SyncObject submit(Sync sync, String url, int kind) {
        DexRunner.SyncObject post = new DexRunner.SyncObject("Ly8/x0;");
        List<String> parameters = SUBMIT_INIT;
        sync.run.run(method(sync.context, "Ly8/x0;", "<init>", parameters), new Object[] {post, new DexRunner.SyncObject("Ltest/Context;"),
                "A title", url, "test", kind, null, null, 1, 0, 0, null, null, null});
        return post;
    }

    private static void imagePosts(Sync stock, Sync patched) throws Exception {
        String url = HOST + "upl0ad1";
        equal("link", params(submit(stock, url, 1)).get("kind"), "stock: an uploaded image is a link post");
        DexRunner.SyncObject post = submit(patched, url, 1);
        Map<String, String> sent = params(post);
        equal("image", sent.get("kind"), "patched: Reddit's image post");
        equal(url, sent.get("url"), "with the asset URL");
        equal("A title", sent.get("title"), "title kept");
        equal("test", sent.get("sr"), "subreddit kept");
        equal("true", sent.get("sendreplies"), "options kept");
        equal("link", params(submit(patched, HOST + "someone-else", 1)).get("kind"), "patched: a typed link stays a link post");
        Map<String, String> self = params(submit(patched, "hello", 0));
        equal("self", self.get("kind"), "patched: a text post stays a text post");
        equal("hello", self.get("text"), "text kept");
        equal(params(submit(stock, "https://example.com/", 1)), params(submit(patched, "https://example.com/", 1)), "ordinary link post unchanged");

        String socket = "wss://" + SOCKET + "/upl0ad1?m=test-only-signature";
        String websocketReply = "{\"json\": {\"errors\": [], \"data\": {\"user_submitted_page\": \"https://www.reddit.com/user/someone/submitted/\", "
                + "\"websocket_url\": \"" + socket + "\"}}}";
        OkHttpClient.SOCKETS.put(SOCKET, (request, listener) ->
                listener.onMessage(null, "{\"type\": \"success\", \"payload\": {\"redirect\": \"https://www.reddit.com/r/test/comments/abc123/a_title/\"}}"));
        MutableMethod parse = method(patched.context, "Ly8/x0;", "parseNetworkResponse", List.of(NETWORK));
        MutableMethod stockParse = method(stock.context, "Ly8/x0;", "parseNetworkResponse", List.of(NETWORK));
        DexRunner.SyncObject stockPost = submit(stock, url, 1);
        equal("parse error", stock.result(stock.run.run(stockParse, new Object[] {stockPost, reply(websocketReply)})),
                "stock: Reddit's image post reply fails Sync's parser");
        equal("success abc123", patched.result(patched.run.run(parse, new Object[] {post, reply(websocketReply)})),
                "patched: the new post's ID reaches Sync's parser");
        OkHttpClient.SOCKETS.put(SOCKET, (request, listener) -> listener.onMessage(null, "{\"type\": \"failed\", \"payload\": {}}"));
        equal("error Reddit couldn't process this image, so the post wasn't created.",
                patched.result(patched.run.run(parse, new Object[] {post, reply(websocketReply)})), "a failed image reaches Sync's error");
        String rejected = "{\"json\": {\"errors\": [[\"SUBREDDIT_NOTALLOWED\", \"you aren't allowed to post there.\", \"sr\"]]}}";
        equal("error you aren't allowed to post there.", patched.result(patched.run.run(parse, new Object[] {post, reply(rejected)})),
                "Reddit's own error unchanged");
        DexRunner.SyncObject linkPost = submit(patched, "https://example.com/", 1);
        String linked = "{\"json\": {\"errors\": [], \"data\": {\"id\": \"lnk1\", \"name\": \"t3_lnk1\"}}}";
        equal(stock.result(stock.run.run(stockParse, new Object[] {submit(stock, "https://example.com/", 1), reply(linked)})),
                patched.result(patched.run.run(parse, new Object[] {linkPost, reply(linked)})), "link post replies unchanged");
    }

    // ---- Comment replies: y8.e ----

    private static void commentReplies(Sync stock, Sync patched) throws Exception {
        // A comment the helper converted (sending, above, registers it).
        DexRunner.SyncObject comment = comment("t1_c9", "Look\n\n![img](upl0ad1)\n\n");
        HELPER_EVENTS.clear();
        QUEUED.clear();
        resender = patched;
        patched.run.call("Lm8/a;", "b", resendContext, null, comment);
        deliver(QUEUED.get(0), "{\"document\":[{\"e\":\"img\",\"id\":\"upl0ad1\"}]}");

        String bare = "{\"id\": \"c9\", \"name\": \"t1_c9\", \"body\": \"Look\\n\\nhttps://preview.redd.it/upl0ad1.png\", \"link_id\": \"t3_p1\"}";
        MutableMethod parse = method(patched.context, "Ly8/e;", "parseNetworkResponse", List.of(NETWORK));
        MutableMethod stockParse = method(stock.context, "Ly8/e;", "parseNetworkResponse", List.of(NETWORK));
        stock.events.clear();
        stock.result(stock.run.run(stockParse, new Object[] {comment("t1_c9", "x"), reply(bare)}));
        equal(List.of("comment error No error returned"), stock.events, "stock: Reddit's bare comment reply is reported as an error");

        patched.events.clear();
        try {
            patched.run.run(parse, new Object[] {comment, reply(bare)});
            throw new AssertionError("Sync's comment parser was not reached");
        } catch (Parsed parsed) {
            equal("Look\n\nhttps://preview.redd.it/upl0ad1.png", parsed.comment.getString("body"), "patched: the comment reaches Sync's parser");
            equal("t1_c9", parsed.comment.getString("name"), "the comment's own data");
        }
        check(patched.events.isEmpty(), "no error report: " + patched.events);

        String refused = "{\"json\": {\"errors\": [[\"META_MEDIA_TYPE_NOT_ALLOWED\", \"media not allowed\", \"richtext_json\"]]}}";
        patched.events.clear();
        patched.result(patched.run.run(parse, new Object[] {comment, reply(refused)}));
        equal(List.of("comment error This subreddit doesn't allow images in comments. Remove the image and try again."), patched.events,
                "media not allowed: Sync reports a clear message (its notification keeps the text with Retry)");
        stock.events.clear();
        stock.result(stock.run.run(stockParse, new Object[] {comment("t1_c9", "x"), reply(refused)}));
        patched.events.clear();
        patched.result(patched.run.run(parse, new Object[] {comment("t1_c9", "x"), reply(refused)}));
        equal(stock.events, patched.events, "other comments' errors unchanged");
        equal(List.of("toast This subreddit doesn't allow images in comments. Remove the image and try again."),
                HELPER_EVENTS.subList(HELPER_EVENTS.size() - 1, HELPER_EVENTS.size()), "and as a toast");

        // A network-level failure: Sync's notification as before, and for this comment Reddit's reason as a toast.
        MutableMethod error = method(patched.context, "Ly8/e;", "parseNetworkError", List.of("Lcom/android/volley/VolleyError;"));
        MutableMethod stockError = method(stock.context, "Ly8/e;", "parseNetworkError", List.of("Lcom/android/volley/VolleyError;"));
        com.android.volley.VolleyError badRequest = new com.android.volley.ClientError(new NetworkResponse(400,
                "{\"message\": \"Bad Request\", \"error\": 400}".getBytes(StandardCharsets.UTF_8), false, 0, Collections.emptyList()));
        stock.events.clear();
        check(stock.run.run(stockError, new Object[] {comment("t1_c9", "x"), badRequest}) == badRequest, "stock passes the error on");
        equal(List.of("comment error Could not connect to Reddit"), stock.events, "stock reports only that it couldn't connect");
        patched.events.clear();
        HELPER_EVENTS.clear();
        check(patched.run.run(error, new Object[] {comment, badRequest}) == badRequest, "patched passes the error on");
        equal(stock.events, patched.events, "Sync's own report unchanged");
        equal(List.of("toast Reddit refused the text with the image (HTTP 400). Reddit said: Bad Request"), HELPER_EVENTS,
                "the reason for this comment as a toast");
        HELPER_EVENTS.clear();
        patched.run.run(error, new Object[] {comment("t1_c11", "x"), badRequest});
        check(HELPER_EVENTS.isEmpty(), "other comments: Sync's report only");

        String wrapped = "{\"json\": {\"errors\": [], \"data\": {\"things\": [{\"kind\": \"t1\", \"data\": {\"body\": \"plain\", \"name\": \"t1_c10\"}}]}}}";
        try {
            patched.run.run(parse, new Object[] {comment("t1_c10", "plain"), reply(wrapped)});
            throw new AssertionError("Sync's comment parser was not reached");
        } catch (Parsed parsed) {
            equal("plain", parsed.comment.getString("body"), "other comments parse as before");
        }
    }

    /** Thrown where Sync's comment parser (c9.c.b) receives the comment, ending the run there. */
    static final class Parsed extends RuntimeException {
        final JSONObject comment;
        Parsed(JSONObject comment) { super(null, null, false, false); this.comment = comment; }
    }

    // ---- Sync with recording stand-ins ----

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();
        final Map<Object, String> errors = new IdentityHashMap<>();
        Object[] uploadArguments;

        Sync(BytecodePatchContext context) {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER);
            run.preset("Lcom/laurencedawson/reddit_sync/RedditApplication;->q:Lcom/android/volley/RequestQueue;", "queue q");
            run.preset("Lcom/laurencedawson/reddit_sync/RedditApplication;->b:Lcom/android/volley/RequestQueue;", "queue b");
            run.preset("Lcom/laurencedawson/reddit_sync/RedditApplication;->c:Lcom/android/volley/RequestQueue;", "queue c");
            run.preset("Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/UploadImageDialogFragment;->H0:Ljava/lang/String;", "UploadImageDialogFragment");
        }

        DexRunner.SyncObject editor() {
            DexRunner.SyncObject editor = new DexRunner.SyncObject(EDITOR);
            editor.fields.put("editor", new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/ui/views/editor/GifCustomEditText;"));
            return editor;
        }

        String result(Object response) {
            check(response instanceof DexRunner.SyncObject, "a Volley response");
            return (String) ((DexRunner.SyncObject) response).fields.get("test.result");
        }

        @SuppressWarnings("unchecked")
        private String describe(Object request) {
            if (request instanceof DexRunner.SyncObject) {
                DexRunner.SyncObject object = (DexRunner.SyncObject) request;
                if (object.type.equals("Lu8/a;")) return "Lu8/a; wrapping " + ((DexRunner.SyncObject) object.fields.get("a")).type;
                Object params = object.fields.get("s");
                return object.type + (params == null ? "" : " " + new TreeMap<>((Map<String, String>) params));
            }
            if (request instanceof DexRunner.Uninitialized) return ((DexRunner.Uninitialized) request).type;
            return String.valueOf(request);
        }

        private DexRunner.SyncObject response(String result) {
            DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
            response.fields.put("test.result", result);
            return response;
        }

        private Object intercept(String ref, Object[] a) {
            String owner = ref.substring(0, ref.indexOf("->"));
            switch (ref) {
                case "Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V":
                    ((DexRunner.SyncObject) a[0]).fields.put("test.url", a[2]);
                    if (((DexRunner.SyncObject) a[0]).type.startsWith("Lu8/")) events.add("request " + a[2]);
                    return null;
                case "Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;":
                case "Lcom/android/volley/Request;->setRetryPolicy(Lcom/android/volley/RetryPolicy;)Lcom/android/volley/Request;":
                    return a[0];
                case "Lcom/android/volley/Request;->getUrl()Ljava/lang/String;": return ((DexRunner.SyncObject) a[0]).fields.get("test.url");
                case "Lx8/b;-><init>()V": return null;
                case "Lx8/a;->parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;": return a[1];
                case "Ljava/lang/Object;->getClass()Ljava/lang/Class;": return Object.class;
                case "Lcom/laurencedawson/reddit_sync/singleton/a;->d()Lcom/laurencedawson/reddit_sync/singleton/a;":
                    return new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/singleton/a;");
                case "Lcom/laurencedawson/reddit_sync/singleton/a;->i()Z": return 1;
                case "Lcom/android/volley/RequestQueue;->add(Lcom/android/volley/Request;)Lcom/android/volley/Request;":
                    events.add("queued " + describe(a[1]));
                    return a[1];
                case "Lb9/d;-><init>(Landroid/content/Context;Lx8/a;)V": events.add("fetch token for " + describe(a[2]).replaceFirst("^Ly8/e; ", ""));
                    return null;
                case "Lb9/c;-><init>(Landroid/content/Context;Lx8/a;Ljava/lang/String;)V":
                    events.add("exchange " + a[3] + " for " + describe(a[2]).replaceFirst("^Ly8/e; ", ""));
                    return null;
                case "Lx8/a;->a(Ljava/lang/String;)V": events.add("token " + a[1]); ((DexRunner.SyncObject) a[0]).fields.put("r", a[1]); return null;
                case "Lwc/m;->a(Ljava/lang/CharSequence;)Z": return a[0] == null || a[0].toString().isEmpty() ? 1 : 0;
                case "Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;": return new DexRunner.SyncObject("Ltest/Resolver;");
                case "Landroid/content/ContentResolver;->openInputStream(Landroid/net/Uri;)Ljava/io/InputStream;":
                    events.add("read " + a[1]);
                    return new ByteArrayInputStream(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
                case "Lorg/apache/commons/io/IOUtils;->toByteArray(Ljava/io/InputStream;)[B": return new byte[] {(byte) 0x89, 'P', 'N', 'G'};
                case HELPER + "->upload(" + UPLOAD_ARGS + ")V": events.add("helper upload"); uploadArguments = a.clone(); return null;
                case HELPER + "->sendDirect(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Z":
                    // The helper takes Android's Context; the recorded services do not use it.
                    return app.revanced.extension.synccompat.RedditImageUpload.sendDirect(null, (String) a[1], a[2], (String) a[3]) ? 1 : 0;
                case HELPER + "->sendExchange(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z":
                    return app.revanced.extension.synccompat.RedditImageUpload.sendExchange(null, a[1], (String) a[2], (String) a[3]) ? 1 : 0;
                case "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;->N3(Ljava/lang/String;)"
                        + "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;": {
                    DexRunner.SyncObject dialog = new DexRunner.SyncObject("Ltest/InsertImageDialog;");
                    dialog.fields.put("url", a[0]);
                    return dialog;
                }
                case "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;->O3(" + EDITOR + ")V": return null;
                case EDITOR + "->c()Landroidx/fragment/app/FragmentManager;": return new DexRunner.SyncObject("Ltest/FragmentManager;");
                case "Landroidx/fragment/app/c;->L3(Landroidx/fragment/app/FragmentManager;Ljava/lang/String;)V":
                    events.add("insert image dialog " + ((DexRunner.SyncObject) a[0]).fields.get("url"));
                    return null;
                case "Landroid/widget/EditText;->getText()Landroid/text/Editable;": return new DexRunner.SyncObject("Ltest/Editable;");
                case "Landroid/widget/EditText;->getSelectionStart()I": return 5;
                case "Landroid/text/Editable;->insert(ILjava/lang/CharSequence;)Landroid/text/Editable;":
                    events.add("insert at " + a[1] + ": " + a[2]);
                    return a[0];
                case "Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;":
                    return null;
                case "Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;":
                    return response("success " + a[0]);
                case "Lcom/android/volley/VolleyError;-><init>(Ljava/lang/String;)V": errors.put(a[0], "error " + a[1]); return null;
                case "Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V": errors.put(a[0], "parse error"); return null;
                case "Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;":
                    return response(errors.getOrDefault(a[0], "error"));
                case "Lorg/apache/commons/lang3/StringUtils;->contains(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().contains(a[1].toString()) ? 1 : 0;
                case "Ly8/e;->d(Ljava/lang/String;Z)V": events.add("comment error " + a[1]); return null;
                case "Lc9/c;->b(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;ZLjava/lang/String;)V":
                    throw new Parsed((JSONObject) a[2]);
            }
            if (owner.equals("Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;") && ref.endsWith(")Ljava/lang/String;"))
                return "https://oauth.reddit.com/";
            return DexRunner.PASS;
        }
    }

    // ---- Hook structure and rejection ----

    private static final String[][] HOOKED = {
            {"Lu8/d;", "a", "Landroid/content/Context;,Landroid/net/Uri;,Lcom/android/volley/Response$Listener;,Lcom/android/volley/Response$ErrorListener;"},
            {EDITOR, "h", "Ljava/lang/String;"},
            {EDITOR, "i", "Ljava/lang/String;"},
            {"Lm8/a;", "b", "Landroid/content/Context;,Ljava/lang/String;,Lx8/a;"},
            {"Lm8/a;", "c", "Landroid/content/Context;,Lx8/a;"},
            {"Lm8/a;", "g", "Landroid/content/Context;,Lx8/a;,Ljava/lang/String;"},
            {"Ly8/x0;", "<init>", String.join(",", SUBMIT_INIT)},
            {"Ly8/x0;", "parseNetworkResponse", NETWORK},
            {"Ly8/e;", "parseNetworkResponse", NETWORK},
            {"Ly8/e;", "parseNetworkError", "Lcom/android/volley/VolleyError;"},
    };

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String[] m : HOOKED) result.put(m[0] + m[1], keys(method(context, m[0], m[1], List.of(m[2].split(",")))));
        return result;
    }

    private static List<String> with(List<String> body, int at, String... added) {
        List<String> result = new ArrayList<>(body);
        result.addAll(at, List.of(added));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        equal(List.of("registers=4", "INVOKE_STATIC:" + HELPER + "->upload(" + UPLOAD_ARGS + ")V", "RETURN_VOID:"), after.get("Lu8/d;a"),
                "upload: the whole body hands Sync's arguments to the helper");
        check(before.get("Lu8/d;a").contains("NEW_INSTANCE:Lu8/c;"), "stock builds the Imgur request");

        List<String> h = before.get(EDITOR + "h");
        equal(with(h, 1, "INVOKE_STATIC:" + HELPER + "->editorMarkdown(Ljava/lang/String;)Ljava/lang/String;", "MOVE_RESULT_OBJECT:",
                "IF_EQZ:", "INVOKE_VIRTUAL:" + EDITOR + "->i(Ljava/lang/String;)V", "RETURN_VOID:"), after.get(EDITOR + "h"),
                "editor: a check before Sync's dialog");
        equal(before.get(EDITOR + "i"), after.get(EDITOR + "i"), "EditorView.i unchanged");
        equal(before.get("Lm8/a;c"), after.get("Lm8/a;c"), "m8.a.c unchanged");
        equal(with(before.get("Lm8/a;b"), 1, "IF_EQZ:", "INVOKE_VIRTUAL:Lcom/android/volley/Request;->getUrl()Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:", "INVOKE_STATIC:" + HELPER + "->sendDirect(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;"
                        + "Ljava/lang/String;)Z", "MOVE_RESULT:", "IF_EQZ:", "RETURN_VOID:"), after.get("Lm8/a;b"), "m8.a.b: a check before Sync's send");
        equal(with(before.get("Lm8/a;g"), 1, "IF_EQZ:", "INVOKE_VIRTUAL:Lcom/android/volley/Request;->getUrl()Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:", "INVOKE_STATIC:" + HELPER + "->sendExchange(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/String;"
                        + "Ljava/lang/String;)Z", "MOVE_RESULT:", "IF_EQZ:", "RETURN_VOID:"), after.get("Lm8/a;g"), "m8.a.g: a check before Sync's send");
        List<String> init = before.get("Ly8/x0;<init>");
        equal(with(init, init.size() - 1, "IGET_OBJECT:Lx8/a;->s:Ljava/util/Map;",
                "INVOKE_STATIC:" + HELPER + "->submitParams(Ljava/lang/Object;Ljava/util/Map;)V"), after.get("Ly8/x0;<init>"),
                "submit: the parameters are passed on just before the constructor returns");
        equal(with(before.get("Ly8/x0;parseNetworkResponse"), 1, "INVOKE_STATIC_RANGE:" + HELPER + "->submitResponse(Ljava/lang/Object;" + NETWORK + ")"
                + NETWORK, "MOVE_RESULT_OBJECT:"), after.get("Ly8/x0;parseNetworkResponse"), "submit parser: the reply is passed first");
        equal(with(before.get("Ly8/e;parseNetworkResponse"), 1, "INVOKE_STATIC_RANGE:" + HELPER + "->commentResponse(Ljava/lang/Object;" + NETWORK + ")"
                + NETWORK, "MOVE_RESULT_OBJECT:"), after.get("Ly8/e;parseNetworkResponse"), "comment parser: the reply is passed first");
        equal(with(before.get("Ly8/e;parseNetworkError"), 1, "INVOKE_STATIC:" + HELPER + "->commentError(Ljava/lang/Object;"
                + "Lcom/android/volley/VolleyError;)V"), after.get("Ly8/e;parseNetworkError"), "comment error: reported first, Sync's report unchanged");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already uploaded to Reddit"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown comment parser (no literal "errors": [] check) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod parse = method(other, "Ly8/e;", "parseNetworkResponse", List.of(NETWORK));
        List<Instruction> body = new ArrayList<>(parse.getImplementation().getInstructions());
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if ("\"errors\": []".equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker >= 0, "comment parser marker found");
        parse.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("\"errors\":[]")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("OAuthCommentRequest"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");
    }

    // ---- Helpers ----

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getUploadSyncImagesToReddit().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name, List<String> parameters) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing " + type);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().equals(parameters)) return method;
        throw new AssertionError("Missing " + type + "->" + name + parameters);
    }
    private static String reference(Instruction i) {
        return i instanceof ReferenceInstruction ? ((ReferenceInstruction) i).getReference().toString() : "";
    }
    private static List<String> keys(MutableMethod method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        for (Instruction i : method.getImplementation().getInstructions()) result.add(i.getOpcode() + ":" + reference(i));
        return result;
    }
}
