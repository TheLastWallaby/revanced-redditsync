import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import android.content.ContentValues;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Applies "Sync: show hidden profile history from Arctic Shift" and runs Sync's OAuthPostsRequest
 * (y8.k0) before and after it: on a hand-written model, or, given a local stock Sync 23033 APK, on
 * the real methods. Sync's own URL builder (b) makes the profile URLs the helper reads; Sync's own
 * parser (parseNetworkResponse) then reads Reddit's answer, or the archived page that replaces it.
 *
 * Before and after for answers the patch must leave alone (a profile with items, a subreddit, an
 * error page): Sync parses exactly the same. For a profile Reddit answers with no children: stock
 * parses nothing; patched, the loopback archive is asked (no Authorization or Cookie header) and
 * Sync's parser hands each archived post (t3) and comment (t1) to PostParserHelper, stores the
 * archive's after for the next page, and builds its cache entry from the archived answer. Android
 * and app services are stand-ins that record what Sync did; PostParserHelper itself is recorded,
 * not run.
 */
public final class ArchivedProfilePatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/ArchivedProfile;";
    private static final String REQUEST = "Ly8/k0;";
    private static final String NETWORK = "Lcom/android/volley/NetworkResponse;";
    private static final String USER = "Hidden_User";
    private static final long POST_TIME = 1_790_000_000L, COMMENT_TIME = POST_TIME - 250;
    private static final String HIDDEN = "{\"kind\":\"Listing\",\"data\":{\"after\":null,\"dist\":0,\"modhash\":\"\",\"geo_filter\":\"\",\"children\":[],\"before\":null}}";
    private static final String VISIBLE = "{\"kind\":\"Listing\",\"data\":{\"after\":\"t3_live2\",\"children\":["
            + "{\"kind\":\"t3\",\"data\":{\"id\":\"live1\",\"title\":\"A live post\"}},"
            + "{\"kind\":\"t1\",\"data\":{\"id\":\"live2\",\"body\":\"A live comment\",\"link_id\":\"t3_x\"}}],\"before\":null}}";
    private static final Set<String> TRANSPORT = Set.of("accept", "user-agent", "host", "connection", "cache-control", "pragma",
            "accept-encoding");
    private static int checks;
    private static final List<String> ARCHIVE = new CopyOnWriteArrayList<>();
    private static volatile boolean archiveDown;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        HttpServer archive = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        archive.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath(), query = exchange.getRequestURI().getRawQuery();
            List<String> headers = new ArrayList<>();
            exchange.getRequestHeaders().keySet().forEach(name -> headers.add(name.toLowerCase(Locale.ROOT)));
            Collections.sort(headers);
            ARCHIVE.add(path + "?" + query + " " + headers);
            // 30 posts, one every 100 s, and one comment between the 3rd and 4th newest; exclusive before.
            JSONArray data = new JSONArray();
            // The author is matched ignoring case, as Arctic Shift does.
            if (query != null && query.toLowerCase(Locale.ROOT).startsWith("author=" + USER.toLowerCase(Locale.ROOT) + "&")) {
                java.util.regex.Matcher before = java.util.regex.Pattern.compile("&before=([0-9]+)").matcher(query);
                long limit = before.find() ? Long.parseLong(before.group(1)) : Long.MAX_VALUE;
                if (path.equals("/api/posts/search")) {
                    for (int n = 0; n < 30 && data.length() < 25; n++)
                        if (POST_TIME - n * 100L < limit) data.put(post(String.format("p%02d", n), POST_TIME - n * 100L, "Post " + n));
                } else if (path.equals("/api/comments/search") && COMMENT_TIME < limit) {
                    data.put(comment("c2", COMMENT_TIME, "A hidden comment"));
                }
            }
            byte[] bytes = archiveDown ? "down".getBytes(StandardCharsets.UTF_8)
                    : new JSONObject().put("data", data).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(archiveDown ? 503 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        archive.start();
        OkHttpClient.ROUTES.put("arctic-shift.photon-reddit.com", "http://127.0.0.1:" + archive.getAddress().getPort());
        Path scratch = Files.createTempDirectory("sync-archived-profile-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            List<String> inputs = new ArrayList<>(List.of("tests/fixtures/archivedprofile"));
            try (var skeletons = Files.newDirectoryStream(Path.of("tests/fixtures/redgifs"), "okhttp3-*.smali")) {
                for (Path skeleton : skeletons) inputs.add(skeleton.toString());
            }
            check(Smali.assemble(options, inputs.toArray(new String[0])), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            hooks(patched.context, before);

            urls(stock, patched);
            unchanged(stock, patched);
            hidden(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " archived profile patch checks (empty profile listings filled from the archive and "
                    + "parsed by Sync; other answers unchanged; executed "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            archive.stop(0);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static JSONObject post(String id, long created, String title) {
        return new JSONObject().put("id", id).put("name", "t3_" + id).put("author", USER).put("created_utc", created)
                .put("title", title).put("selftext", "").put("subreddit", "pics").put("permalink", "/r/pics/comments/" + id + "/x/")
                .put("_meta", new JSONObject().put("retrieved_2nd_on", 1));
    }
    private static JSONObject comment(String id, long created, String body) {
        return new JSONObject().put("id", id).put("name", "t1_" + id).put("author", USER).put("created_utc", created)
                .put("body", body).put("link_id", "t3_x").put("link_title", "A thread").put("subreddit", "news")
                .put("permalink", "/r/news/comments/x/y/" + id + "/");
    }

    // ---- Sync, run instruction by instruction ----

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        /** What PostParserHelper was given: kind number and id. */
        final List<String> parsed = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> toasts = new ArrayList<>();
        NetworkResponse cached;

        Sync(BytecodePatchContext context) throws ReflectiveOperationException {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER);
            run.preset("Lcom/laurencedawson/reddit_sync/provider/RedditProvider;->B:Landroid/net/Uri;", "content://posts");
            run.preset("Lcom/laurencedawson/reddit_sync/provider/RedditProvider;->E:Landroid/net/Uri;", "content://lists");
        }

        private boolean model(String type) { return context.getClassDefs().get(type) == null; }

        @SuppressWarnings("unchecked")
        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Lcom/android/volley/Request;->getUrl()Ljava/lang/String;": return ((DexRunner.SyncObject) a[0]).fields.get("test.url");
                case "Lcom/android/volley/Request;->isCanceled()Z": return 0;
                case "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->g(Landroid/content/Context;)Ljava/lang/String;":
                    return "https://oauth.reddit.com/";
                case "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;":
                    return new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;");
                case "Lorg/apache/commons/lang3/StringUtils;->isNotEmpty(Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[0].toString().length() > 0 ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->replaceAll(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;":
                    return a[0] == null ? null : ((String) a[0]).replaceAll((String) a[1], (String) a[2]);
                case "Lc9/c;->b(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;ZLjava/lang/String;)V": {
                    JSONObject item = (JSONObject) a[2];
                    parsed.add(a[1] + ":" + item.optString("id"));
                    check(!item.has("_meta"), "archive metadata never reaches Sync's parser");
                    ContentValues values = new ContentValues();
                    values.put("_id", item.optString("id"));
                    ((List<Object>) a[3]).add(values);
                    return null;
                }
                case "Lc9/b;->a(Ljava/util/List;Ljava/lang/String;I)[Landroid/content/ContentValues;":
                    return ((List<Object>) a[0]).toArray(new ContentValues[0]);
                case "Ll8/a;->a()Ljava/lang/String;": return "age";
                case "Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;": return new DexRunner.SyncObject("Ltest/Resolver;");
                case "Landroid/content/ContentResolver;->update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I":
                    events.add("update " + a[1]); return 0;
                case "Landroid/content/ContentResolver;->bulkInsert(Landroid/net/Uri;[Landroid/content/ContentValues;)I":
                    events.add("insert " + a[1] + " " + ((Object[]) a[2]).length); return ((Object[]) a[2]).length;
                case "Landroid/content/ContentResolver;->notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V":
                    events.add("notify " + a[1]); return null;
                case "Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;":
                    cached = (NetworkResponse) a[0]; return null;
                case "Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;": {
                    DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
                    response.fields.put("success", 1);
                    response.fields.put("result", a[0]);
                    return response;
                }
                case "Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;": {
                    DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
                    response.fields.put("success", 0);
                    return response;
                }
                case "Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V": events.add("parse error " + a[1]); return null;
            }
            // Sync helpers the model only names; the real APK runs Sync's own.
            if (ref.startsWith("Ld7/d;->") && model("Ld7/d;")) {
                String name = (String) a[0];
                switch (ref) {
                    case "Ld7/d;->J(Ljava/lang/String;)Z": return name.startsWith("user###") ? 1 : 0;
                    case "Ld7/d;->u(Ljava/lang/String;)Ljava/lang/String;": return name.split("###")[1];
                    case "Ld7/d;->v(Ljava/lang/String;)Ljava/lang/String;": return name.split("###")[2];
                }
            }
            if (ref.equals("Lo8/c;->b(Ljava/lang/String;)I") && model("Lo8/c;"))
                return List.of("t1", "t2", "t3").indexOf((String) a[0]) + 1;
            return DexRunner.PASS;
        }

        /** Sync's URL for a listing name, sort and paging position. */
        String url(String name, String sort, String after) {
            DexRunner.SyncObject paging = new DexRunner.SyncObject("Lo8/g;");
            if (after != null) paging.fields.put("a", after);
            Object url = run.run(method(context, REQUEST, "b", List.of("Landroid/content/Context;", "Ljava/lang/String;",
                    "Ljava/lang/String;", "Lo8/g;", "Ljava/lang/String;", "Ljava/lang/String;")),
                    new Object[] {new DexRunner.SyncObject("Ltest/Context;"), name, name, paging, sort, null});
            return (String) url;
        }

        /** Runs parseNetworkResponse for this URL and Reddit answer; the paging state it left. */
        DexRunner.SyncObject parse(String url, int status, String body) {
            parsed.clear();
            events.clear();
            cached = null;
            DexRunner.SyncObject request = new DexRunner.SyncObject(REQUEST);
            DexRunner.SyncObject paging = new DexRunner.SyncObject("Lo8/g;");
            request.fields.put("test.url", url);
            request.fields.put("u", paging);
            request.fields.put("t", "list");
            request.fields.put("c", new DexRunner.SyncObject("Ltest/Context;"));
            NetworkResponse reddit = new NetworkResponse(status, body.getBytes(StandardCharsets.UTF_8), false, 42,
                    List.of(new Header("Content-Type", "application/json; charset=UTF-8")));
            Object result = run.run(method(context, REQUEST, "parseNetworkResponse", List.of(NETWORK)), new Object[] {request, reddit});
            check(result instanceof DexRunner.SyncObject, "Sync returned a Volley response");
            events.add("success " + ((DexRunner.SyncObject) result).fields.get("success"));
            return paging;
        }
    }

    // ---- The URL Sync builds is the one the helper reads ----

    private static void urls(Sync stock, Sync patched) throws ReflectiveOperationException {
        Method listing = Class.forName("app.revanced.extension.synccompat.ArchivedProfile").getDeclaredMethod("listing", String.class);
        listing.setAccessible(true);
        for (String section : List.of("Overview", "Comments", "Submitted")) {
            String name = "user###" + section + "###" + USER + "###null";
            String first = stock.url(name, "new", null), next = stock.url(name, "new", "t1_c2");
            equal(first, patched.url(name, "new", null), "the patch leaves Sync's URL builder alone");
            Object parsed = listing.invoke(null, first);
            check(parsed != null, "helper reads Sync's " + section + " URL: " + first);
            // Sync's real builder lowercases the URL; the archive's author search ignores case.
            check(USER.equalsIgnoreCase((String) field(parsed, "user")), section + " user in " + first);
            equal(section.toUpperCase(Locale.ROOT), String.valueOf(field(parsed, "section")), section + " section in " + first);
            equal(null, field(parsed, "after"), section + " first page");
            Object paged = listing.invoke(null, next);
            check(paged != null, "helper reads Sync's paged " + section + " URL: " + next);
            equal("t1_c2", field(paged, "after"), section + " paging item");
        }
        String upvoted = stock.url("user###Upvoted###" + USER + "###null", "new", null);
        equal(null, listing.invoke(null, upvoted), "Upvoted is not archived: " + upvoted);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // ---- Parsing ----

    private static final String PROFILE = "https://oauth.reddit.com/user/" + USER + "/Overview.json?sort=new&always_show_media=1";

    private static void unchanged(Sync stock, Sync patched) throws ReflectiveOperationException {
        setPlatform(patched);
        Object[][] cases = {
            {"profile with items", PROFILE, 200, VISIBLE},
            {"subreddit with no items", "https://oauth.reddit.com/r/pics/hot.json?sort=hot", 200, HIDDEN},
            {"profile upvoted with no items", "https://oauth.reddit.com/user/" + USER + "/upvoted.json?sort=new", 200, HIDDEN},
            {"profile error page", PROFILE, 403, "{\"message\":\"Forbidden\",\"error\":403}"},
            {"profile HTML", PROFILE, 200, "<html>blocked</html>"},
        };
        for (Object[] row : cases) {
            ARCHIVE.clear();
            DexRunner.SyncObject stockPaging = stock.parse((String) row[1], (Integer) row[2], (String) row[3]);
            List<String> stockParsed = new ArrayList<>(stock.parsed), stockEvents = new ArrayList<>(stock.events);
            DexRunner.SyncObject patchedPaging = patched.parse((String) row[1], (Integer) row[2], (String) row[3]);
            equal(stockParsed, patched.parsed, "same items parsed for " + row[0]);
            equal(stockEvents, patched.events, "same storage for " + row[0]);
            equal(state(stockPaging), state(patchedPaging), "same paging state for " + row[0]);
            check(ARCHIVE.isEmpty(), "archive not asked for " + row[0] + ": " + ARCHIVE);
            check(patched.toasts.isEmpty(), "no message for " + row[0]);
        }
        stock.parse(PROFILE, 200, VISIBLE);
        equal(List.of("3:live1", "1:live2"), stock.parsed, "Sync parses a visible profile's post and comment");
    }

    private static void hidden(Sync stock, Sync patched) throws ReflectiveOperationException {
        setPlatform(patched);
        ARCHIVE.clear();
        DexRunner.SyncObject stockPaging = stock.parse(PROFILE, 200, HIDDEN);
        check(stock.parsed.isEmpty(), "stock shows nothing for a hidden profile: " + stock.parsed);
        check(ARCHIVE.isEmpty(), "stock never asks the archive");

        DexRunner.SyncObject paging = patched.parse(PROFILE, 200, HIDDEN);
        List<String> first = new ArrayList<>(List.of("3:p00", "3:p01", "3:p02", "1:c2"));
        for (int n = 3; n < 24; n++) first.add(String.format("3:p%02d", n));
        equal(first, patched.parsed, "25 archived posts and comments, newest first, through Sync's parser");
        check(patched.events.contains("success 1"), "Sync reports success: " + patched.events);
        if (!patched.model("Lc9/b;")) // the model has no storage step
            check(patched.events.contains("insert content://posts 25"), "Sync stored the archived items: " + patched.events);
        check(patched.events.stream().noneMatch(e -> e.startsWith("parse error")), "no parse error: " + patched.events);
        check(patched.cached != null && new String(patched.cached.data, StandardCharsets.UTF_8).contains("\"p00\""),
                "Sync's cache entry is built from the archived page");
        equal(42L, patched.cached.networkTimeMs, "Reddit's network time kept");
        equal("t3_p23", paging.fields.get("a"), "Sync keeps the archive's after for the next page");
        equal(25, paging.fields.get("b"), "Sync counted the archived items");
        check(!Objects.equals(stockPaging.fields.get("b"), paging.fields.get("b")), "stock counted none: " + stockPaging.fields);
        equal(2, ARCHIVE.size(), "posts and comments asked once each: " + ARCHIVE);
        for (String request : ARCHIVE) {
            check(request.startsWith("/api/posts/search?author=" + USER + "&limit=25&sort=desc [")
                    || request.startsWith("/api/comments/search?author=" + USER + "&limit=25&sort=desc ["), "only the username: " + request);
            for (String header : request.replaceAll(".*\\[|\\]", "").split(", "))
                check(TRANSPORT.contains(header), "no Authorization, Cookie or other header: " + header);
        }
        equal(List.of("Showing archived history from Arctic Shift."), patched.toasts,
                "where the items come from");

        // The next page: Sync's own URL with the archive's after; the rest, then the end of the list.
        String next = patched.url("user###Overview###" + USER + "###null", "new", "t3_p23");
        ARCHIVE.clear();
        patched.toasts.clear();
        paging = patched.parse(next, 200, HIDDEN);
        equal(List.of("3:p24", "3:p25", "3:p26", "3:p27", "3:p28", "3:p29"), patched.parsed, "the rest, without repeats");
        equal(2, ARCHIVE.size(), "both sources asked once: " + ARCHIVE);
        for (String request : ARCHIVE)
            check(request.contains("&before=" + (POST_TIME - 2300 + 1) + " "), "before from the last item: " + request);
        check(patched.toasts.isEmpty(), "message only on the first page: " + patched.toasts);
        // The archive's last page ends with "after": null like Reddit's own last page, so Sync ends the list
        // the same way. (This JVM's org.json rejects getString on null where Android's returns "null";
        // both answers meet that same difference.)
        List<String> archivedEnd = new ArrayList<>(patched.events);
        stock.parse(PROFILE, 200, VISIBLE.replace("\"after\":\"t3_live2\"", "\"after\":null"));
        archivedEnd.removeIf(e -> e.startsWith("insert"));
        List<String> redditEnd = new ArrayList<>(stock.events);
        redditEnd.removeIf(e -> e.startsWith("insert"));
        equal(redditEnd, archivedEnd, "the archive's last page ends like Reddit's own last page");

        // A user the archive does not know, and an archive that is down: Sync's own empty result.
        for (boolean down : new boolean[] {false, true}) {
            archiveDown = down;
            patched.toasts.clear();
            String url = down ? PROFILE.replace("/Overview", "/Submitted") : PROFILE.replace(USER, "nobody");
            stockPaging = stock.parse(url, 200, HIDDEN);
            List<String> stockEvents = new ArrayList<>(stock.events);
            paging = patched.parse(url, 200, HIDDEN);
            check(patched.parsed.isEmpty(), "nothing parsed, archive down " + down);
            equal(stockEvents, patched.events, "Sync's own empty result, archive down " + down);
            equal(state(stockPaging), state(paging), "Sync's own paging state, archive down " + down);
            equal(down ? 1 : 0, patched.toasts.size(), "message only when the archive is down: " + patched.toasts);
        }
        archiveDown = false;
    }

    /** The paging state Sync left, without u, the milliseconds its own parsing took. */
    private static Map<String, Object> state(DexRunner.SyncObject paging) {
        Map<String, Object> fields = new TreeMap<>(paging.fields);
        fields.remove("u");
        return fields;
    }

    private static void setPlatform(Sync sync) throws ReflectiveOperationException {
        Class<?> helper = Class.forName("app.revanced.extension.synccompat.ArchivedProfile");
        Class<?> type = Class.forName("app.revanced.extension.synccompat.ArchivedProfile$Platform");
        Object platform = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "toast": sync.toasts.add((String) args[0]); return null;
                case "hashCode": return System.identityHashCode(self);
                case "equals": return self == args[0];
                default: return "platform";
            }
        });
        Field field = helper.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
        Method reset = helper.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
        sync.toasts.clear();
    }

    // ---- Hook structure and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MutableMethod method : context.proxy(context.getClassDefs().get(REQUEST)).getMutableClass().getMethods())
            result.put(DexRunner.signature(method), method.getImplementation() == null ? List.of() : keys(method));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        String parse = REQUEST + "->parseNetworkResponse(" + NETWORK + ")Lcom/android/volley/Response;";
        equal(before.keySet(), after.keySet(), "no method added or removed");
        for (String method : before.keySet())
            if (!method.equals(parse)) equal(before.get(method), after.get(method), "unchanged: " + method);
        List<String> expected = new ArrayList<>(before.get(parse));
        expected.addAll(1, List.of("INVOKE_VIRTUAL_RANGE:Lcom/android/volley/Request;->getUrl()Ljava/lang/String;", "MOVE_RESULT_OBJECT:",
                "MOVE_OBJECT_FROM16:", "INVOKE_STATIC:" + HELPER + "->response(Ljava/lang/String;" + NETWORK + ")" + NETWORK,
                "MOVE_RESULT_OBJECT:", "MOVE_OBJECT_FROM16:"));
        equal(expected, after.get(parse), "parser: Reddit's answer passes through the helper first, the rest unchanged");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already shown"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A parser that no longer reads "after" is not the layout the patch knows: no edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod parse = method(other, REQUEST, "parseNetworkResponse", List.of(NETWORK));
        List<Instruction> body = new ArrayList<>(parse.getImplementation().getInstructions());
        int found = 0;
        for (int i = 0; i < body.size(); i++) if ("after".equals(DexRunner.string(body.get(i)))) {
            parse.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(i)).getRegisterA(), new ImmutableStringReference("later")));
            found++;
        }
        check(found > 0, "after read found");
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("OAuthPostsRequest parser"), "unknown layout rejected: " + expected.getMessage()); }
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
        SyncCompatPatchesKt.getShowHiddenSyncProfilesFromArcticShift().getApply$patcher().invoke(context, resources);
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
