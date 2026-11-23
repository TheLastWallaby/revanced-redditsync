import app.revanced.extension.synccompat.ArcticShiftRestore;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;

/**
 * Applies "Sync: restore posts from Arctic Shift" and runs Sync's post options sheet
 * (PostMoreBottomSheetFragment.o2, which binds the sheet's rows) before and after it: on hand-written
 * models, or, given a local stock Sync 23033 APK, on the real method.
 *
 * <p>Before: Sync has no restore entry for posts. After: Sync binds its rows exactly as before, and a
 * "Restore post" row is added above Share for a post whose author or text is [deleted] or [removed],
 * or whose text is Reddit's "[ Removed by Reddit ]" notice, never for an ordinary post or a comment.
 * Tapping it dismisses the sheet, sends only the post ID to a loopback Arctic Shift archive (no
 * Authorization or Cookie header), and stores the archived author and selftext in the post's own row,
 * with Sync's processing and list preview; a post the archive lacks shows the message. Android and
 * app services are stand-ins that record what Sync and the helper asked for.
 */
public final class ArcticShiftPostRestorePatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/ArcticShiftRestore;";
    private static final String SHEET = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;";
    private static final String ROW = "Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;";
    private static final String MESSAGE = "This post isn't in the Arctic Shift archive.";
    private static final String NOTICE = "[ Removed by Reddit ]";
    private static final String FIELDS = "&fields=id,author,selftext,spoiler,link_flair_text";
    /** App state the real sheet reads while binding rows; defaults stand in for it. */
    private static final List<String> DEFAULTED = List.of("Lbutterknife/ButterKnife;", "Landroidx/fragment/app/Fragment;",
            "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;", "Lcom/laurencedawson/reddit_sync/singleton/a;",
            "Lcom/laurencedawson/reddit_sync/singleton/c;", "Ld8/a0;", "Luc/b;", "Lt7/d0;", "Lorg/apache/commons/lang3/StringUtils;");
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
            String body = query != null && query.startsWith("ids=1abcdef&")
                    ? "{\"data\":[{\"author\":\"someone\",\"id\":\"1abcdef\",\"link_flair_text\":null,"
                        + "\"selftext\":\"The archived &amp; original text\\n\\nSecond paragraph\",\"spoiler\":false}]}"
                    : query != null && query.startsWith("ids=1link&")
                    ? "{\"data\":[{\"author\":\"poster\",\"id\":\"1link\",\"selftext\":\"\",\"spoiler\":false}]}"
                    : query != null && query.startsWith("ids=1gone&")
                    ? "{\"data\":[{\"author\":\"[deleted]\",\"id\":\"1gone\",\"selftext\":\"[deleted]\",\"spoiler\":false}]}"
                    : "{\"data\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        archive.start();
        OkHttpClient.ROUTES.put("arctic-shift.photon-reddit.com", "http://127.0.0.1:" + archive.getAddress().getPort());
        Path scratch = Files.createTempDirectory("sync-arctic-shift-post-test");
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
            List<String> before = keys(method(patched.context, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;")));
            apply(patched.context);
            hooks(patched.context, before);

            sheet(stock, patched);
            tap(patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " Arctic Shift post restore patch checks (Restore post added to Sync's post sheet, "
                    + "archived text stored in the post's row; executed " + (args.length != 0
                    ? "the real Sync method from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            archive.stop(0);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- The sheet ----

    private static void sheet(Sync stock, Sync patched) {
        String[][] offered = {
            {"[deleted]", "[deleted]", "a deleted self post"},
            {"[deleted]", "", "a deleted link post"},
            {"someone", "[removed]", "a removed self post"},
            {"[removed]", "", "a post with a removed author"},
            {"someone", NOTICE, "Reddit's notice"},
            {"someone", " [ Removed by Reddit on account of violating the content policy. ] ", "a longer notice"},
        };
        for (String[] post : offered) {
            Sheet before = stock.build("1abcdef", post[0], post[1], 2);
            Sheet after = patched.build("1abcdef", post[0], post[1], 2);
            check(before.added.isEmpty(), "stock adds no row for " + post[2]);
            equal(List.of("Restore post"), after.added, "Restore post for " + post[2]);
            equal(before.sync, after.sync, "Sync's own rows unchanged for " + post[2]);
            check(after.anchor == after.share, "the row goes above Share");
        }
        String[][] ordinary = {
            {"someone", "ordinary text", "an ordinary self post"},
            {"someone", "", "an ordinary link post"},
            {"someone", null, "a post without text"},
            {null, null, "a post without author or text"},
            {"someone", "quoting [deleted] here", "text that mentions [deleted]"},
            {"someone", "[ Removed by Reddit ] and more", "text that starts with the notice"},
        };
        for (String[] post : ordinary) {
            Sheet before = stock.build("1abcdef", post[0], post[1], 2);
            Sheet after = patched.build("1abcdef", post[0], post[1], 2);
            check(after.added.isEmpty(), "no Restore post for " + post[2] + ": " + after.added);
            equal(before.sync, after.sync, "Sync's own rows unchanged for " + post[2]);
        }
        Sheet comment = patched.build("k0cydtb", "[deleted]", "[deleted]", 11);
        check(comment.added.isEmpty(), "never for a comment shown in the post sheet: " + comment.added);
        check(patched.build("1abcdef", "[deleted]", "[deleted]", 1).added.contains("Restore post"), "an image or link post type too");

        // A failing row (for example an unexpected sheet layout) leaves Sync's sheet as Sync built it.
        patched.rowFails = true;
        Sheet failed = patched.build("1abcdef", "[deleted]", "[deleted]", 2);
        patched.rowFails = false;
        equal(stock.build("1abcdef", "[deleted]", "[deleted]", 2).sync, failed.sync, "a failing row changes nothing else");
    }

    private static void tap(Sync patched) {
        // Found: the archived author and text are stored in the post's own row.
        ARCHIVE.clear();
        List<String> events = patched.restore("1abcdef", "[deleted]", "[deleted]");
        equal("dismissed", events.get(0), "the sheet closes first");
        equal("toast Restoring post", events.get(1), "progress message");
        equal(1, ARCHIVE.size(), "one archive request: " + ARCHIVE);
        check(ARCHIVE.get(0).startsWith("/api/posts/ids?ids=1abcdef" + FIELDS + " ["), "only the post ID: " + ARCHIVE.get(0));
        // Accept and User-Agent come from the helper; the rest from the HTTP transport. No credentials.
        for (String header : ARCHIVE.get(0).replaceAll(".*\\[|\\]", "").split(", "))
            check(TRANSPORT.contains(header), "no Authorization, Cookie or other header: " + header);
        equal(1, patched.updates.size(), "stored once: " + patched.updates);
        String update = patched.updates.get(0);
        check(update.startsWith("1abcdef "), "the post's own row: " + update);
        check(update.contains("author=someone"), "author stored: " + update);
        check(update.contains("selftext_raw=The archived & original text\n\nSecond paragraph"), "text unescaped by Sync: " + update);
        check(update.contains("selftext_processed=<p>The archived & original text</p>\n<p>Second paragraph</p>"),
                "text processed by Sync: " + update);
        check(update.contains("selftext_processed_preview=The archived & original text\n…"), "list preview: " + update);
        check(events.stream().noneMatch(e -> e.startsWith("toast ") && !e.equals("toast Restoring post")), "no error message: " + events);

        // A link post the author deleted: the author comes back and the text stays empty.
        events = patched.restore("1link", "[deleted]", "");
        equal(1, patched.updates.size(), "link post stored once: " + patched.updates);
        check(patched.updates.get(0).contains("author=poster"), "link post author: " + patched.updates.get(0));
        check(patched.updates.get(0).contains("selftext_raw= "), "empty text kept empty: " + patched.updates.get(0));

        // Not archived, or archived only after it was deleted: the message, nothing stored.
        for (String id : List.of("1missing", "1gone")) {
            ARCHIVE.clear();
            events = patched.restore(id, "[deleted]", "[deleted]");
            equal(1, ARCHIVE.size(), id + " asked once");
            check(patched.updates.isEmpty(), "nothing stored for " + id + ": " + patched.updates);
            check(events.contains("toast " + MESSAGE), "not-archived message for " + id + ": " + events);
        }

        // An ID Sync could never send is never sent.
        ARCHIVE.clear();
        events = patched.restore("bad/id", "[deleted]", "[deleted]");
        check(ARCHIVE.isEmpty(), "an invalid ID is never sent");
        check(events.contains("toast " + ArcticShiftRestore.POST_FAILED), "invalid ID message, no crash: " + events);
    }

    // ---- Sync with recording stand-ins ----

    /** What one build of the sheet did: Sync's own row calls, and the helper's added rows. */
    private static final class Sheet {
        final List<String> sync = new ArrayList<>();
        final List<String> added = new ArrayList<>();
        final List<Runnable> taps = new ArrayList<>();
        Object anchor, share;
    }

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();
        final List<String> updates = new ArrayList<>();
        final Object platform;
        Sheet sheet;
        boolean rowFails;

        Sync(BytecodePatchContext context) throws ReflectiveOperationException {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER);
            Class<?> type = Class.forName("app.revanced.extension.synccompat.ArcticShiftRestore$Platform");
            platform = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
                switch (method.getName()) {
                    case "execute": ((Runnable) args[0]).run(); return null;
                    case "toast": events.add("toast " + args[0]); return null;
                    case "addRow":
                        if (rowFails) throw new IllegalStateException("no row");
                        sheet.added.add((String) args[1]);
                        sheet.anchor = args[0];
                        sheet.taps.add((Runnable) args[2]);
                        return null;
                    case "dismiss": events.add("dismissed"); return null;
                    // Sync's WebsafeUtils and markdown processing, and Android's Html, as small stand-ins.
                    case "unescape": return ((String) args[0]).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                    case "process": {
                        StringJoiner html = new StringJoiner("\n");
                        for (String paragraph : ((String) args[0]).split("\n\n")) html.add("<p>" + paragraph + "</p>");
                        return html.toString();
                    }
                    case "htmlText": return ((String) args[0]).replaceAll("<[^>]+>", "");
                    case "store": {
                        StringJoiner joiner = new StringJoiner(" ", args[0] + " ", "");
                        @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) args[1];
                        values.forEach((key, value) -> joiner.add(key + "=" + (value == null || value.isEmpty() ? " " : value)));
                        updates.add(joiner.toString());
                        return null;
                    }
                    case "deliver": throw new AssertionError("The post restore never uses the comment parser");
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
            if (owner.equals(ROW) || ref.startsWith("Landroid/widget/LinearLayout;->")) {
                Object row = a[0] instanceof DexRunner.SyncObject ? ((DexRunner.SyncObject) a[0]).fields.get("test.name") : a[0];
                StringJoiner call = new StringJoiner(" ", row + "." + name + "(", ")");
                for (int n = 1; n < a.length; n++) call.add(a[n] == null || a[n] instanceof CharSequence || a[n] instanceof Number ? String.valueOf(a[n]) : "listener");
                sheet.sync.add(call.toString());
                return null;
            }
            switch (ref) {
                case "Ls9/f;->o2(Landroid/view/View;Landroid/os/Bundle;)V": return null;
                case "Ls9/i;-><init>(" + SHEET + ")V": return null;
                case "Lorg/apache/commons/lang3/StringUtils;->equalsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().equalsIgnoreCase(a[1].toString()) ? 1 : 0;
            }
            if (DEFAULTED.contains(owner)) {
                if (returns.equals("Z") || returns.equals("I")) return 0;
                if (returns.equals("V")) return null;
                if (returns.equals("Ljava/lang/String;")) return "";
                return new DexRunner.SyncObject(returns);
            }
            return DexRunner.PASS;
        }

        /** Builds the sheet for a post with this ID, author, selftext and type. */
        Sheet build(String id, String author, String selftext, int type) {
            sheet = new Sheet();
            DexRunner.SyncObject post = new DexRunner.SyncObject("Lxa/b;");
            post.fields.put("c", id);
            post.fields.put("J", author);
            post.fields.put("U0", selftext);
            post.fields.put("u", type);
            DexRunner.SyncObject fragment = new DexRunner.SyncObject(SHEET);
            fragment.fields.put("P0", post);
            for (Field field : context.getClassDefs().get(SHEET).getFields()) {
                if (!field.getType().equals(ROW)) continue;
                DexRunner.SyncObject row = new DexRunner.SyncObject(ROW);
                row.fields.put("test.name", field.getName());
                fragment.fields.put(field.getName(), row);
            }
            sheet.share = fragment.fields.get("mShare");
            setPlatform();
            run.run(method(context, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;")),
                    new Object[] {fragment, new DexRunner.SyncObject("Landroid/view/View;"), null});
            return sheet;
        }

        /** Builds the sheet and taps Restore post; what Sync and the helper did. */
        List<String> restore(String id, String author, String selftext) {
            Sheet built = build(id, author, selftext, 2);
            check(built.taps.size() == 1, "Restore post shown for " + id);
            events.clear();
            updates.clear();
            built.taps.get(0).run();
            return new ArrayList<>(events);
        }

        private void setPlatform() {
            try {
                java.lang.reflect.Field field = ArcticShiftRestore.class.getDeclaredField("platform");
                field.setAccessible(true);
                field.set(null, platform);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    // ---- Hook structure and rejection ----

    private static void hooks(BytecodePatchContext context, List<String> before) {
        List<String> after = keys(method(context, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;")));
        List<String> expected = new ArrayList<>(before);
        int end = expected.lastIndexOf("RETURN_VOID:");
        check(end == expected.size() - 1 && expected.indexOf("RETURN_VOID:") == end, "o2 ends in its only return");
        expected.addAll(end, List.of("MOVE_OBJECT_FROM16:", "IGET_OBJECT:" + SHEET + "->mShare:" + ROW, "IGET_OBJECT:" + SHEET + "->P0:Lxa/d;",
                "INVOKE_VIRTUAL:Lxa/d;->U()Ljava/lang/String;", "MOVE_RESULT_OBJECT:", "INVOKE_VIRTUAL:Lxa/d;->e()Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:", "INVOKE_VIRTUAL:Lxa/d;->N0()Ljava/lang/String;", "MOVE_RESULT_OBJECT:", "INVOKE_VIRTUAL:Lxa/d;->Y0()I",
                "MOVE_RESULT:", "INVOKE_STATIC_RANGE:" + HELPER
                        + "->offerPost(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V"));
        equal(expected, after, "o2: only the Restore post offer is added, after Sync binds every row");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        List<String> applied = keys(method(twice, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;")));
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already restored"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, keys(method(twice, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;"))), "duplicate makes no edits");

        // An unknown sheet layout (o2 no longer ends with Share's listener) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod built = method(other, SHEET, "o2", List.of("Landroid/view/View;", "Landroid/os/Bundle;"));
        List<Instruction> body = new ArrayList<>(built.getImplementation().getInstructions());
        int share = -1;
        for (int i = 0; i < body.size(); i++) if (reference(body.get(i)).equals(SHEET + "->mShare:" + ROW)) share = i;
        check(share >= 0, "Share row read found");
        TwoRegisterInstruction read = (TwoRegisterInstruction) body.get(share);
        built.getImplementation().replaceInstruction(share, new BuilderInstruction22c(Opcode.IGET_OBJECT, read.getRegisterA(),
                read.getRegisterB(), new ImmutableFieldReference(SHEET, "mProfile", ROW)));
        List<String> unchanged = keys(built);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("post options sheet"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, keys(built), "rejection makes no edits");
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
        SyncCompatPatchesKt.getRestoreSyncPostsFromArcticShift().getApply$patcher().invoke(context, resources);
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
