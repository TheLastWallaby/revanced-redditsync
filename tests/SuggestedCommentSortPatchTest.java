import android.content.ContentValues;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/**
 * Applies "Sync: use subreddit suggested comment sort" and runs Sync's comments request URL
 * (OAuthCommentsRequest, y8.g: the static URL builder f, then getUrl) and the post parser
 * (PostParserHelper.a, c9.c.a) before and after it: on hand-written models, or, given a local stock
 * Sync 23033 APK, on the real methods. Subreddit about pages come from a loopback server.
 *
 * With the "Suggested" sort, a post with no suggested sort of its own: stock Sync asks for its
 * comments without a sort and stores no suggested sort, so it shows "Top"; patched, the request
 * asks for the subreddit's suggested sort and the parser stores it as the post's. A post's own
 * suggested sort wins over its subreddit's. Explicit sorts, comment threads, subreddits that
 * suggest nothing and failed lookups are unchanged; the main thread never waits for a lookup.
 */
public final class SuggestedCommentSortPatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/SuggestedCommentSort;";
    private static final String HELPER_CLASS = "app.revanced.extension.synccompat.SuggestedCommentSort";
    private static final String REQUEST = "Ly8/g;";
    private static final String PARSER = "Lc9/c;";
    private static final String SETTINGS = "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;";
    private static final List<String> PARSE = List.of("Landroid/content/Context;", "I", "Lorg/json/JSONObject;",
            "Ljava/util/ArrayList;", "I", "Z", "Z", "Ljava/lang/String;");
    private static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();
    private static volatile boolean main;
    private static boolean real;
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        HttpServer reddit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reddit.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            LOOKUPS.add(path + "?" + exchange.getRequestURI().getRawQuery());
            String sub = path.replaceFirst("^/r/([^/]+)/about\\.json$", "$1").toLowerCase(Locale.ROOT);
            Object sort = switch (sub) {
                case "asknew" -> "new";
                case "askqa" -> "qa";
                case "plain" -> JSONObject.NULL;
                default -> null;
            };
            byte[] body = (sort == null ? "{\"error\":500}" : new JSONObject().put("kind", "t5")
                    .put("data", new JSONObject().put("display_name", sub).put("suggested_comment_sort", sort)).toString())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(sort == null ? 500 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        reddit.start();
        Path scratch = Files.createTempDirectory("sync-suggested-sort-test");
        try {
            seams(reddit.getAddress().getPort());
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/suggestedsort"), "assemble Sync models");
            real = args.length != 0;
            File source = real ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stock = context(source, scratch, "stock");
            BytecodePatchContext patched = context(source, scratch, "patched");
            List<String> parserBefore = keys(parser(patched));
            List<String> builderBefore = keys(method(patched, REQUEST, "f"));
            check(method(stock, REQUEST, "getUrl") == null, "stock comments request inherits getUrl");
            apply(patched);
            hooks(patched, parserBefore, builderBefore);

            urls(stock, patched);
            parsing(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " suggested comment sort patch checks (subreddit sort requested and stored "
                    + "for posts without their own; other requests unchanged; executed "
                    + (real ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            reddit.stop(0);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Hooks ----

    private static void hooks(BytecodePatchContext context, List<String> parserBefore, List<String> builderBefore) {
        List<String> builder = keys(method(context, REQUEST, "f"));
        List<String> builderAdded = new ArrayList<>(builder);
        for (String key : builderBefore) builderAdded.remove(key);
        equal(List.of("INVOKE_STATIC:" + HELPER + "->threadUrl(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:"), builderAdded, "URL builder gains one call and its result");
        equal(builderBefore.size() + 2, builder.size(), "nothing else changes in the URL builder");
        equal("RETURN_OBJECT:", builder.get(builder.size() - 1), "URL builder still ends with its return");
        check(builder.get(builder.size() - 3).contains("->threadUrl("), "the call sits right before the return");
        MutableMethod getUrl = method(context, REQUEST, "getUrl");
        check(getUrl != null && getUrl.getParameterTypes().isEmpty(), "getUrl override added");
        List<String> url = keys(getUrl);
        check(url.contains("INVOKE_SUPER:Lcom/android/volley/Request;->getUrl()Ljava/lang/String;"), "override asks Volley for the URL");
        check(url.contains("INVOKE_STATIC:" + HELPER + "->commentsUrl(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"),
                "override hands the URL to the helper");

        List<String> after = keys(parser(context));
        List<String> added = new ArrayList<>(after);
        for (String key : parserBefore) added.remove(key);
        equal(List.of("INVOKE_STATIC:" + HELPER + "->postSort(Lorg/json/JSONObject;Ljava/lang/String;)Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:"), added, "parser gains one call and its result");
        equal(parserBefore.size() + 2, after.size(), "nothing else changes in the parser");
        List<? extends Instruction> code = toList(parser(context).getImplementation().getInstructions());
        int call = -1;
        for (int i = 0; i < code.size(); i++) if (reference(code.get(i)).startsWith(HELPER)) call = i;
        FiveRegisterInstruction hook = (FiveRegisterInstruction) code.get(call);
        equal("Lc8/d;->e(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(call - 2)), "hook follows Sync's text cleaner");
        int value = ((OneRegisterInstruction) code.get(call - 1)).getRegisterA();
        equal(value, hook.getRegisterD(), "hook reads the cleaned sort");
        equal(value, ((OneRegisterInstruction) code.get(call + 1)).getRegisterA(), "hook result replaces the sort");
        equal("Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(call - 4)),
                "Sync reads the post's suggested_sort");
        equal(((FiveRegisterInstruction) code.get(call - 4)).getRegisterC(), hook.getRegisterC(), "hook reads the post JSON Sync read");
        equal("suggested_sort", string(code.get(call + 2)), "the column name follows");
        FiveRegisterInstruction put = (FiveRegisterInstruction) code.get(call + 3);
        check(reference(put).equals("Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V")
                && put.getRegisterE() == value, "Sync stores the hook's result");
    }

    // ---- Comments request URL ----

    private static String syncUrl(BytecodePatchContext context, String post, String sub, String comment, String sort) {
        return (String) runner(context).call(REQUEST, "f", "context", post, sub, comment, sort, 0);
    }

    /** The URL Volley sends: Sync's URL as stock, or through the patched getUrl override. */
    private static String sent(BytecodePatchContext context, String post, String sub, String comment, String sort) {
        String built = syncUrl(context, post, sub, comment, sort);
        MutableMethod getUrl = method(context, REQUEST, "getUrl");
        if (getUrl == null) return built;
        DexRunner.SyncObject request = new DexRunner.SyncObject(REQUEST);
        DexRunner runner = new DexRunner(context, (ref, a) -> {
            if (ref.equals("Lcom/android/volley/Request;->getUrl()Ljava/lang/String;")) {
                check(a[0] == request, "Volley's getUrl is asked for this request");
                return built;
            }
            return DexRunner.PASS;
        }, HELPER);
        return (String) runner.run(getUrl, new Object[] {request});
    }

    private static void urls(BytecodePatchContext stock, BytecodePatchContext patched) throws Exception {
        reset();
        String base = syncUrl(stock, "abc12", "AskNew", null, "suggested");
        check(base.contains("/r/AskNew/comments/abc12.json?") && !base.contains("sort="), "stock asks without a sort: " + base);
        equal(base, sent(stock, "abc12", "AskNew", null, "suggested"), "stock sends Sync's URL");

        // Main thread (Sync logs the URL while building the request): nothing is looked up yet.
        main = true;
        equal(base, sent(patched, "abc12", "AskNew", null, "suggested"), "main thread: unknown sort leaves the URL");
        equal(0, LOOKUPS.size(), "main thread never looks up");
        main = false;

        equal(base + "&sort=new", sent(patched, "abc12", "AskNew", null, "suggested"), "subreddit's suggested sort requested");
        equal(List.of("/r/AskNew/about.json?raw_json=1"), LOOKUPS, "one about lookup, at the request's host");
        main = true;
        equal(base + "&sort=new", sent(patched, "abc12", "AskNew", null, "suggested"), "known sort used on the main thread");
        main = false;
        String other = syncUrl(patched, "def34", "asknew", null, "suggested");
        equal(other + "&sort=new", sent(patched, "def34", "asknew", null, "suggested"), "same subreddit, any case: cached");
        equal(1, LOOKUPS.size(), "no second lookup for a known subreddit");
        equal("new", requestSort("suggested", "t3_abc12"), "load more uses the sort sent for the post");

        // Left alone: explicit sorts, comment threads, other subreddits.
        for (String sort : List.of("new", "top", "confidence", "old")) {
            String explicit = syncUrl(stock, "abc12", "AskNew", null, sort);
            check(explicit.contains("&sort=" + sort), "Sync sends an explicit sort");
            equal(explicit, sent(patched, "abc12", "AskNew", null, sort), "explicit " + sort + " unchanged");
        }
        // Comment threads: Sync never asked for a sort, whatever was chosen.
        String thread = syncUrl(stock, "abc12", "AskNew", "c0ffee", "suggested");
        check(thread.contains("/comments/abc12/_/c0ffee.json?") && !thread.contains("sort="), "stock thread has no sort: " + thread);
        equal(thread, sent(stock, "abc12", "AskNew", "c0ffee", "suggested"), "stock thread sent as built");
        equal(thread + "&sort=new", sent(patched, "abc12", "AskNew", "c0ffee", "suggested"), "suggested thread: subreddit's sort");
        for (String sort : List.of("new", "top", "old", "controversial")) {
            String stockThread = syncUrl(stock, "abc12", "AskNew", "c0ffee", sort);
            check(!stockThread.contains("sort="), "stock thread ignores the chosen " + sort);
            equal(stockThread + "&sort=" + sort, sent(patched, "abc12", "AskNew", "c0ffee", sort), "thread asks for the chosen " + sort);
        }
        String plain = syncUrl(stock, "xyz9", "Plain", null, "suggested");
        equal(plain, sent(patched, "xyz9", "Plain", null, "suggested"), "subreddit with no suggested sort unchanged");
        equal(plain, sent(patched, "xyz9", "Plain", null, "suggested"), "no suggestion is remembered");
        equal(2, LOOKUPS.size(), "subreddit without a suggestion looked up once");
        String plainThread = syncUrl(stock, "t9", "Plain", "beef1", "suggested");
        equal(plainThread, sent(patched, "t9", "Plain", "beef1", "suggested"), "suggested thread, no suggestion: unchanged");
        equal(2, LOOKUPS.size(), "thread reuses the known answer");
        String broken = syncUrl(stock, "q1", "Broken", null, "suggested");
        equal(broken, sent(patched, "q1", "Broken", null, "suggested"), "failed lookup leaves the URL");
        equal(3, LOOKUPS.size(), "failed lookup made once");
        equal(broken, sent(patched, "q1", "Broken", null, "suggested"), "failure is not retried at once");
        equal(3, LOOKUPS.size(), "no retry storm after a failure");
        String popular = syncUrl(stock, "p1", "popular", null, "suggested");
        equal(popular, sent(patched, "p1", "popular", null, "suggested"), "r/popular is not a subreddit with an about page");
        equal(3, LOOKUPS.size(), "r/popular is not looked up");
        String bare = syncUrl(stock, "nosub1", null, null, "suggested");
        equal(bare, sent(patched, "nosub1", null, null, "suggested"), "no subreddit in the URL: unchanged");
        String qa = syncUrl(stock, "a1", "AskQa", null, "suggested");
        equal(qa + "&sort=qa", sent(patched, "a1", "AskQa", null, "suggested"), "other suggested sorts are requested too");
        equal(4, LOOKUPS.size(), "one lookup per subreddit");
    }

    // ---- Post parser ----

    @SuppressWarnings("unchecked")
    private static ContentValues parse(BytecodePatchContext context, JSONObject post) throws Exception {
        ArrayList<Object> list = new ArrayList<>();
        Method realParser = null;
        if (real) {
            realParser = Class.forName("PollPatchTest$RealParser").getDeclaredMethod("call", String.class, Object[].class, boolean.class);
            realParser.setAccessible(true);
        }
        Method stand = realParser;
        DexRunner runner = new DexRunner(context, (ref, a) -> {
            if (ref.equals("Ld7/f;->r(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
                return a[1] == null ? "" : "<p>" + a[1] + "</p>";
            if (stand == null) return DexRunner.PASS;
            try { return stand.invoke(null, ref, a, false); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }, HELPER);
        runner.preset("Landroid/util/Patterns;->WEB_URL:Ljava/util/regex/Pattern;",
                java.util.regex.Pattern.compile("https?://[^\\s)\\]]+"));
        runner.run(parser(context), new Object[] {"context", 3, new JSONObject(post.toString()), list, 0, 0, 0, "Title"});
        equal(1, list.size(), "one post parsed");
        return (ContentValues) list.get(0);
    }

    private static JSONObject post(String id, String sub, Object suggested) {
        JSONObject post = PollPatchTest.post(id, "Some text", null).put("subreddit", sub)
                .put("subreddit_name_prefixed", "r/" + sub).put("permalink", "/r/" + sub + "/comments/" + id + "/x/")
                .put("url", "https://www.reddit.com/r/" + sub + "/comments/" + id + "/x/");
        if (suggested != null) post.put("suggested_sort", suggested);
        return post;
    }

    private static void parsing(BytecodePatchContext stock, BytecodePatchContext patched) throws Exception {
        reset();
        // Subreddit sort known from a comments request.
        sent(patched, "warm1", "AskNew", null, "suggested");
        equal(1, LOOKUPS.size(), "subreddit looked up by the comments request");

        Object[][] cases = {
                {"n1", "AskNew", null, null, "new", "no own sort, subreddit suggests new"},
                {"n2", "AskNew", JSONObject.NULL, null, "new", "null own sort, subreddit suggests new"},
                {"n3", "AskNew", "", null, "new", "empty own sort, subreddit suggests new"},
                {"o1", "AskNew", "qa", "qa", "qa", "the post's own sort wins"},
                {"o2", "AskNew", "blank", "blank", "blank", "the post's own blank choice is kept"},
                {"u1", "Unvisited", null, null, null, "unknown subreddit: nothing guessed"},
        };
        for (Object[] c : cases) {
            ContentValues before = parse(stock, post((String) c[0], (String) c[1], c[2]));
            ContentValues after = parse(patched, post((String) c[0], (String) c[1], c[2]));
            equal(c[3], before.get("suggested_sort"), "stock: " + c[5]);
            equal(c[4], after.get("suggested_sort"), "patched: " + c[5]);
            Map<String, Object> rest = new HashMap<>(after.asMap());
            Map<String, Object> stockRest = new HashMap<>(before.asMap());
            rest.remove("suggested_sort");
            stockRest.remove("suggested_sort");
            rest.remove("age");
            stockRest.remove("age");
            equal(stockRest, rest, "other columns unchanged: " + c[5]);
        }
        equal(1, LOOKUPS.size(), "the parser never looks anything up");

        // A parsed post's own sort is requested without a lookup; "blank" leaves the request alone.
        String own = syncUrl(stock, "o1", "AskNew", null, "suggested");
        equal(own + "&sort=qa", sent(patched, "o1", "AskNew", null, "suggested"), "post's own sort requested");
        String blank = syncUrl(stock, "o2", "AskNew", null, "suggested");
        equal(blank, sent(patched, "o2", "AskNew", null, "suggested"), "post's blank choice: no sort added");
        String unvisited = syncUrl(stock, "u1", "Unvisited", null, "suggested");
        equal(unvisited, sent(patched, "u1", "Unvisited", null, "suggested"), "failed lookup for an unvisited subreddit");
        equal(2, LOOKUPS.size(), "only the unvisited subreddit is looked up");
    }

    // ---- Rejections ----

    private static void rejections(File source, Path scratch) throws Exception {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        List<String> once = keys(parser(twice));
        try { apply(twice); throw new AssertionError("second application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("getUrl"), "second application rejected"); }
        equal(once, keys(parser(twice)), "rejected second application leaves the parser");

        BytecodePatchContext noBuilder = context(source, scratch, "no-builder");
        MutableClassDef request = noBuilder.proxy(noBuilder.getClassDefs().get(REQUEST)).getMutableClass();
        request.getMethods().removeIf(m -> m.getName().equals("f"));
        List<String> before = keys(parser(noBuilder));
        try { apply(noBuilder); throw new AssertionError("missing URL builder accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("URL builder"), "missing URL builder rejected"); }
        equal(before, keys(parser(noBuilder)), "rejection makes no partial edit to the parser");
        check(method(noBuilder, REQUEST, "getUrl") == null, "rejection adds no override");

        BytecodePatchContext noColumn = context(source, scratch, "no-column");
        MutableMethod parser = parser(noColumn);
        var code = parser.getImplementation().getInstructions();
        int read = -1;
        for (int i = 0; i < code.size(); i++) if ("suggested_sort".equals(string(code.get(i)))) { read = i; break; }
        int register = ((OneRegisterInstruction) code.get(read)).getRegisterA();
        parser.getImplementation().replaceInstruction(read, new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c(
                Opcode.CONST_STRING, register, new com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference("domain")));
        try { apply(noColumn); throw new AssertionError("parser without the suggested sort read accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("suggested sort"), "changed parser rejected"); }
        check(method(noColumn, REQUEST, "getUrl") == null, "parser rejection adds no override");
    }

    // ---- Helpers ----

    private static DexRunner runner(BytecodePatchContext context) {
        return new DexRunner(context, (ref, a) -> {
            if (ref.startsWith(SETTINGS + "->g(")) return "https://oauth.reddit.com/";
            if (ref.startsWith(SETTINGS + "->x()")) {
                DexRunner.SyncObject settings = new DexRunner.SyncObject(SETTINGS + "$Settings");
                settings.fields.put("commentsCount", 0);
                return settings;
            }
            return DexRunner.PASS;
        }, HELPER);
    }

    /** Routes the helper's Reddit lookups to the loopback server and lets the suite choose the thread. */
    private static void seams(int port) throws Exception {
        Class<?> helper = Class.forName(HELPER_CLASS);
        Field connections = helper.getDeclaredField("connections");
        connections.setAccessible(true);
        connections.set(null, Proxy.newProxyInstance(helper.getClassLoader(), new Class<?>[] {connections.getType()}, (proxy, m, a) -> {
            if (!m.getName().equals("open")) return m.invoke(this0(), a);
            URI uri = URI.create((String) a[0]);
            check("oauth.reddit.com".equals(uri.getHost()) && "https".equals(uri.getScheme()), "lookup goes to the request's Reddit host");
            return (HttpURLConnection) new URL("http://127.0.0.1:" + port + uri.getRawPath() + "?" + uri.getRawQuery()).openConnection();
        }));
        Field thread = helper.getDeclaredField("mainThread");
        thread.setAccessible(true);
        thread.set(null, Proxy.newProxyInstance(helper.getClassLoader(), new Class<?>[] {thread.getType()}, (proxy, m, a) -> {
            if (m.getName().equals("current")) return main;
            return m.invoke(this0(), a);
        }));
    }
    private static Object this0() { return new Object(); }

    private static void reset() throws Exception {
        Method reset = Class.forName(HELPER_CLASS).getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
        LOOKUPS.clear();
        main = false;
    }
    private static String requestSort(String sort, String link) throws Exception {
        return (String) Class.forName(HELPER_CLASS).getMethod("requestSort", String.class, String.class).invoke(null, sort, link);
    }

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getUseSyncSubredditSuggestedCommentSort().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing " + type);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name)) return method;
        return null;
    }
    private static MutableMethod parser(BytecodePatchContext context) {
        ClassDef clazz = context.getClassDefs().get(PARSER);
        if (clazz == null) throw new AssertionError("Missing " + PARSER);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals("a") && method.getParameterTypes().equals(PARSE)) return method;
        throw new AssertionError("Missing " + PARSER + "->a" + PARSE);
    }
    private static List<? extends Instruction> toList(Iterable<? extends Instruction> code) {
        List<Instruction> list = new ArrayList<>();
        for (Instruction i : code) list.add(i);
        return list;
    }
    private static String reference(Instruction i) {
        return i instanceof ReferenceInstruction ? ((ReferenceInstruction) i).getReference().toString() : "";
    }
    private static String string(Instruction i) {
        return i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof StringReference
                ? ((StringReference) ((ReferenceInstruction) i).getReference()).getString() : null;
    }
    private static List<String> keys(MutableMethod method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        for (Instruction i : method.getImplementation().getInstructions()) result.add(i.getOpcode() + ":" + reference(i));
        return result;
    }
}
