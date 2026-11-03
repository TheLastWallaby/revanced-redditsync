import app.revanced.extension.synccompat.ArcticShiftRestore;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;

/**
 * Applies "Sync: restore comments from Arctic Shift" and runs Sync's comment options sheet
 * (CommentMoreDialogBottomSheet, t9.s) before and after it: on hand-written models, or, given a
 * local stock Sync 23033 APK, on the real methods. Building the sheet (A4) decides whether
 * "Restore comment" is shown; tapping it (r0) builds RestoreCommentRequest (r8.h).
 *
 * Before: the entry needs Sync Ultra and Sync's ultra_restore server switch, and the tap queues the
 * Pushshift request. After: the entry is shown for every [deleted] or [removed] comment, with or
 * without Sync Ultra (which can no longer be activated), and the tap sends the comment ID to a
 * loopback Arctic Shift archive (no Authorization or Cookie header) and hands the answer to
 * RestoreCommentRequest's own parser, which stores the author and text; a comment the archive
 * lacks shows the message. Android and app services are stand-ins that record what Sync asked for.
 */
public final class ArcticShiftRestorePatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/ArcticShiftRestore;";
    private static final String SHEET = "Lt9/s;";
    private static final String ITEM = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;";
    private static final String NETWORK = "Lcom/android/volley/NetworkResponse;";
    private static final String MESSAGE = "This comment isn't in the Arctic Shift archive.";
    private static final String PUSHSHIFT = "https://api.pushshift.io/reddit/comment/search/?ids=";
    private static final List<String> DEFAULTED = List.of("Ld8/m;", "Lcom/laurencedawson/reddit_sync/singleton/a;", "Lu7/g;",
            "Lt7/z;", "Landroid/content/SharedPreferences;", "Landroidx/fragment/app/Fragment;", "Lorg/apache/commons/lang3/StringUtils;");
    private static final Set<String> TRANSPORT = Set.of("accept", "user-agent", "host", "connection", "cache-control", "pragma",
            "accept-encoding");
    private static int checks;
    private static final List<String> ARCHIVE = new CopyOnWriteArrayList<>();

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
            String query = exchange.getRequestURI().getRawQuery();
            List<String> headers = new ArrayList<>();
            exchange.getRequestHeaders().keySet().forEach(name -> headers.add(name.toLowerCase(Locale.ROOT)));
            Collections.sort(headers);
            ARCHIVE.add(exchange.getRequestURI().getRawPath() + "?" + query + " " + headers);
            String body = query != null && query.startsWith("ids=k0cydtb&")
                    ? "{\"data\":[{\"author\":\"someone\",\"body\":\"the archived text\",\"id\":\"k0cydtb\"}]}" : "{\"data\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        archive.start();
        OkHttpClient.ROUTES.put("arctic-shift.photon-reddit.com", "http://127.0.0.1:" + archive.getAddress().getPort());
        Path scratch = Files.createTempDirectory("sync-arctic-shift-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            List<String> inputs = new ArrayList<>(List.of("tests/fixtures/arcticshift"));
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

            menu(stock, patched);
            tap(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " Arctic Shift restore patch checks (restore sent to the archive and parsed by Sync; "
                    + "executed " + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            archive.stop(0);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- The sheet ----

    private static void menu(Sync stock, Sync patched) {
        for (Sync sync : List.of(stock, patched)) { sync.ultra = true; sync.serverSwitch = true; }
        check(stock.items("[deleted]").contains("Restore comment"), "stock shows Restore with Ultra and the server switch");
        equal(stock.items("[deleted]"), patched.items("[deleted]"), "patched sheet unchanged when the switch is on");

        for (Sync sync : List.of(stock, patched)) sync.serverSwitch = false;
        List<String> stockItems = stock.items("[removed]"), patchedItems = patched.items("[removed]");
        check(!stockItems.contains("Restore comment"), "stock hides Restore when Sync's server switch is off: " + stockItems);
        check(patchedItems.contains("Restore comment"), "patched shows Restore without the retired switch: " + patchedItems);
        List<String> others = new ArrayList<>(patchedItems);
        others.remove("Restore comment");
        equal(stockItems, others, "other items unchanged");
        check(!patched.items("an ordinary comment").contains("Restore comment"), "only for removed or deleted comments");

        for (Sync sync : List.of(stock, patched)) sync.ultra = false;
        for (boolean on : new boolean[] {false, true}) {
            for (Sync sync : List.of(stock, patched)) sync.serverSwitch = on;
            stockItems = stock.items("[deleted]");
            patchedItems = patched.items("[deleted]");
            check(!stockItems.contains("Restore comment"), "stock hides Restore without Ultra, switch " + on);
            check(patchedItems.contains("Restore comment"), "patched shows Restore without Ultra, switch " + on + ": " + patchedItems);
            others = new ArrayList<>(patchedItems);
            others.remove("Restore comment");
            equal(stockItems, others, "other items keep Sync's own Ultra checks, switch " + on);
        }
        check(!patched.items("an ordinary comment").contains("Restore comment"), "without Ultra, still only for removed or deleted comments");
        for (Sync sync : List.of(stock, patched)) { sync.ultra = true; sync.serverSwitch = false; }
    }

    private static void tap(Sync stock, Sync patched) {
        stock.serverSwitch = true;
        ARCHIVE.clear();
        List<String> events = stock.restore("k0cydtb");
        equal(List.of("request " + PUSHSHIFT + "k0cydtb", "toast Restoring comment", "queued " + PUSHSHIFT + "k0cydtb", "dismissed"),
                events, "stock queues the Pushshift request");
        check(ARCHIVE.isEmpty(), "stock never asks the archive");

        patched.serverSwitch = false;
        events = patched.restore("k0cydtb");
        check(events.stream().noneMatch(e -> e.startsWith("queued")), "patched queues nothing: " + events);
        equal("toast Restoring comment", events.get(1), "Sync's own progress message");
        equal("dismissed", events.get(events.size() - 1), "sheet dismissed");
        equal(1, ARCHIVE.size(), "one archive request");
        check(ARCHIVE.get(0).startsWith("/api/comments/ids?ids=k0cydtb&fields=id,author,body ["), "only the comment ID: " + ARCHIVE.get(0));
        // Accept and User-Agent come from the helper; the rest from the HTTP transport. No credentials.
        for (String header : ARCHIVE.get(0).replaceAll(".*\\[|\\]", "").split(", "))
            check(TRANSPORT.contains(header), "no Authorization, Cookie or other header: " + header);
        List<String> updates = patched.updates;
        equal(1, updates.size(), "Sync's parser stored the comment once: " + updates);
        check(updates.get(0).startsWith("k0cydtb "), "update for this comment: " + updates.get(0));
        check(updates.get(0).contains("author=someone"), "author stored: " + updates.get(0));
        check(updates.get(0).contains("body_raw=the archived text"), "text stored: " + updates.get(0));
        check(events.stream().noneMatch(e -> e.startsWith("toast ") && !e.equals("toast Restoring comment")), "no error message: " + events);
        check(events.stream().noneMatch(e -> e.startsWith("parse error")), "Sync's parser accepted the answer: " + events);

        ARCHIVE.clear();
        events = patched.restore("abc123");
        equal(1, ARCHIVE.size(), "missing comment asked once");
        check(ARCHIVE.get(0).startsWith("/api/comments/ids?ids=abc123&fields=id,author,body "), "by ID: " + ARCHIVE.get(0));
        check(patched.updates.isEmpty(), "nothing stored for a missing comment");
        check(events.contains("toast " + MESSAGE), "missing comment message: " + events);
        equal("dismissed", events.get(events.size() - 1), "sheet dismissed");

        ARCHIVE.clear();
        events = patched.restore("bad/id");
        check(ARCHIVE.isEmpty(), "an invalid ID is never sent");
        check(events.contains("toast " + ArcticShiftRestore.FAILED), "invalid ID message, no crash: " + events);
    }

    // ---- Sync with recording stand-ins ----

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();
        final List<String> updates = new ArrayList<>();
        final List<String> items = new ArrayList<>();
        final Map<Object, String> titles = new IdentityHashMap<>();
        final Map<Object, Map<String, Object>> values = new IdentityHashMap<>();
        final Object platform;
        boolean ultra = true, serverSwitch;

        Sync(BytecodePatchContext context) throws ReflectiveOperationException {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER);
            run.preset("Lcom/laurencedawson/reddit_sync/provider/RedditProvider;->q:Landroid/net/Uri;", "content://comments");
            run.preset("Lcom/laurencedawson/reddit_sync/provider/RedditProvider;->B:Landroid/net/Uri;", "content://comments/changed");
            Class<?> type = Class.forName("app.revanced.extension.synccompat.ArcticShiftRestore$Platform");
            platform = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
                switch (method.getName()) {
                    case "execute": ((Runnable) args[0]).run(); return null;
                    case "toast": events.add("toast " + args[0]); return null;
                    case "deliver": {
                        DexRunner.SyncObject response = new DexRunner.SyncObject(NETWORK);
                        response.fields.put("data", args[1]);
                        Object result = run.run(method(context, "Lr8/h;", "parseNetworkResponse", List.of(NETWORK)),
                                new Object[] {args[0], response});
                        return result instanceof DexRunner.SyncObject && Integer.valueOf(1).equals(((DexRunner.SyncObject) result).fields.get("success"));
                    }
                    case "hashCode": return System.identityHashCode(self);
                    case "equals": return self == args[0];
                    default: return "platform";
                }
            });
        }

        private Object intercept(String ref, Object[] a) {
            String owner = ref.substring(0, ref.indexOf("->"));
            String name = ref.substring(ref.indexOf("->") + 2, ref.indexOf('('));
            String returns = ref.substring(ref.indexOf(')') + 1);
            switch (ref) {
                case "Luc/b;->j()Z": return ultra ? 1 : 0;
                case "Lt7/d0;->e()Z": return serverSwitch ? 1 : 0;
                case "Lt7/d0;->d()Z": case "Lt7/d0;->f()Z": return 0;
                case ITEM + "-><init>(ILjava/lang/String;)V": titles.put(a[0], (String) a[2]); return null;
                case "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4("
                        + ITEM + ")" + ITEM: {
                    String title = titles.get(a[1]);
                    items.add(title);
                    DexRunner.SyncObject item = new DexRunner.SyncObject("Ltest/Item;");
                    item.fields.put("title", title);
                    return item;
                }
                case "Ljava/lang/Object;->equals(Ljava/lang/Object;)Z": return a[0] == a[1] ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return Objects.equals(String.valueOf(a[0]), String.valueOf(a[1])) && a[0] != null ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->equalsAnyIgnoreCase(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Z":
                    for (Object candidate : (Object[]) a[1])
                        if (a[0] != null && candidate != null && a[0].toString().equalsIgnoreCase(candidate.toString())) return 1;
                    return 0;
                case "Lgb/p;->d(Ljava/lang/String;)V": events.add("toast " + a[0]); return null;
                case "Lm8/a;->a(Lcom/android/volley/Request;)V":
                    events.add("queued " + ((DexRunner.SyncObject) a[0]).fields.get("test.url")); return null;
                case "Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V":
                    ((DexRunner.SyncObject) a[0]).fields.put("test.url", a[2]);
                    events.add("request " + a[2]);
                    return null;
                case "Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;": return a[0];
                case "Lcom/android/volley/Request;->getUrl()Ljava/lang/String;": return ((DexRunner.SyncObject) a[0]).fields.get("test.url");
                case "Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V": events.add("dismissed"); return null;
                case "Lwc/p;->a(Ljava/lang/String;)Ljava/lang/String;": return a[0];
                case "Ld7/f;->r(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;": return "processed " + a[1];
                case "Landroid/content/ContentValues;-><init>()V": values.put(a[0], new TreeMap<>()); return null;
                case "Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V": values.get(a[0]).put((String) a[1], a[2]); return null;
                case "Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;": return new DexRunner.SyncObject("Ltest/Context;");
                case "Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;": return new DexRunner.SyncObject("Ltest/Resolver;");
                case "Landroid/content/ContentResolver;->update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I":
                    equal("content://comments", a[1], "comments table");
                    StringJoiner joiner = new StringJoiner(" ", a[3] + " ", "");
                    values.get(a[2]).forEach((key, value) -> joiner.add(key + "=" + value));
                    updates.add(joiner.toString());
                    return 1;
                case "Landroid/content/ContentResolver;->notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V": return null;
                case "Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;":
                    return null;
                case "Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;": {
                    DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
                    response.fields.put("success", 1);
                    return response;
                }
                case "Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;": {
                    DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
                    response.fields.put("success", 0);
                    return response;
                }
                case "Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V": events.add("parse error " + a[1]); return null;
            }
            // Other app state the real sheet reads while building items: defaults.
            if (DEFAULTED.contains(owner) || (owner.equals("Lt7/d0;") && returns.equals("Z"))) {
                if (returns.equals("Z") || returns.equals("I")) return 0;
                if (returns.equals("V")) return null;
                if (returns.equals("Ljava/lang/String;")) return "";
                return new DexRunner.SyncObject(returns);
            }
            return DexRunner.PASS;
        }

        /** Builds the sheet for a comment with this text; the titles it shows. */
        List<String> items(String text) {
            items.clear();
            run.run(method(context, SHEET, "A4", List.of()), new Object[] {sheet("k0cydtb", text)});
            return new ArrayList<>(items);
        }

        /** Builds the sheet for a [deleted] comment and taps Restore comment; what Sync did. */
        List<String> restore(String id) {
            DexRunner.SyncObject sheet = sheet(id, "[deleted]");
            run.run(method(context, SHEET, "A4", List.of()), new Object[] {sheet});
            Object restore = sheet.fields.get("e1");
            check(restore instanceof DexRunner.SyncObject, "Restore comment shown");
            events.clear();
            updates.clear();
            try {
                Field field = ArcticShiftRestore.class.getDeclaredField("platform");
                field.setAccessible(true);
                field.set(null, platform);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            run.run(method(context, SHEET, "r0", List.of(ITEM)), new Object[] {sheet, restore});
            return new ArrayList<>(events);
        }

        private static DexRunner.SyncObject sheet(String id, String text) {
            DexRunner.SyncObject post = new DexRunner.SyncObject("Lxa/b;");
            post.fields.put("c", id);
            post.fields.put("J", text);
            post.fields.put("j0", "1690000000");
            DexRunner.SyncObject sheet = new DexRunner.SyncObject(SHEET);
            sheet.fields.put("Q0", post);
            return sheet;
        }
    }

    // ---- Hook structure and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("A4", keys(method(context, SHEET, "A4", List.of())));
        result.put("r0", keys(method(context, SHEET, "r0", List.of(ITEM))));
        result.put("parse", keys(method(context, "Lr8/h;", "parseNetworkResponse", List.of(NETWORK))));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        equal(before.get("parse"), after.get("parse"), "Sync's parser unchanged");

        List<String> menu = new ArrayList<>(before.get("A4"));
        int gate = menu.indexOf("INVOKE_STATIC:Lt7/d0;->e()Z");
        check(gate > 0 && menu.indexOf("INVOKE_STATIC:Lt7/d0;->e()Z") == menu.lastIndexOf("INVOKE_STATIC:Lt7/d0;->e()Z"), "one server switch");
        int ultra = menu.subList(0, gate).lastIndexOf("INVOKE_STATIC:Luc/b;->j()Z");
        check(ultra > 0 && gate - ultra < 25, "Ultra check before the switch");
        menu.add(gate + 2, "CONST_4:");
        menu.add(ultra + 2, "CONST_4:");
        equal(menu, after.get("A4"), "menu: only the Ultra and server switch results for Restore are overridden");

        List<String> click = new ArrayList<>(before.get("r0"));
        int queued = click.indexOf("INVOKE_STATIC:Lm8/a;->a(Lcom/android/volley/Request;)V");
        check(queued > 0, "stock queues the request");
        click.set(queued, "INVOKE_VIRTUAL:Lxa/d;->U()Ljava/lang/String;");
        click.addAll(queued + 1, List.of("MOVE_RESULT_OBJECT:", "INVOKE_STATIC:" + HELPER + "->restore(Ljava/lang/Object;Ljava/lang/String;)V"));
        equal(click, after.get("r0"), "tap: only the queue call is replaced");
        check(after.get("r0").contains("INVOKE_DIRECT:Lr8/h;-><init>(Lxa/d;)V"), "Sync still builds its request");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already restored"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown menu layout (no "Restore comment" item after the switch) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod menu = method(other, SHEET, "A4", List.of());
        List<Instruction> body = new ArrayList<>(menu.getImplementation().getInstructions());
        int label = -1;
        for (int i = 0; i < body.size(); i++) if ("Restore comment".equals(DexRunner.string(body.get(i)))) label = i;
        check(label >= 0, "menu label found");
        menu.getImplementation().replaceInstruction(label, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(label)).getRegisterA(), new ImmutableStringReference("Something else")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("restore comment menu"), "unknown layout rejected: " + expected.getMessage()); }
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
        SyncCompatPatchesKt.getRestoreSyncCommentsFromArcticShift().getApply$patcher().invoke(context, resources);
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
