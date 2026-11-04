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
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: don't report Amazon links" and taps links through Sync's link span
 * (CustomUrlSpan.onClick, mb.d) before and after it, with a recording request queue and link
 * opener: on hand-written models, or, given a local stock Sync 23033 APK, on the real methods.
 *
 * Before: an amazon.com product link (/dp/) queues AmazonAnalyticsRequest (e7.e), a POST of the
 * link to Sync's former ad server, then opens. After: nothing is queued and the same link still
 * reaches the link router. Every other link, flair and table tap is unchanged. As a backstop the
 * request's own address is a non-network URL. Also checks the exact edit, duplicate and
 * unsupported-layout rejection.
 */
public final class AmazonLinkPatchTest {
    private static final String SPAN = "Lmb/d;";
    private static final String REPORT = "Le7/e;";
    private static final String AD_SERVER = "https://us-central1-ad-server-f1010.cloudfunctions.net/add";
    private static final String DISABLED = "sync-compat-disabled:amazon";
    private static final String TABLE = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/TableDialogBottomSheet;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-amazon-link-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/amazonlinks"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            edit(before, snapshot(patched.context));
            taps(stock, patched);
            backstop(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " Amazon link report checks (no report queued, links open as before; executed "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Taps ----

    private static void taps(Sync stock, Sync patched) {
        // Amazon product pages: stock Sync reports the whole link (query included) before opening it.
        String[] products = {
            "https://www.amazon.com/dp/B08N5WRWNW",
            "https://www.amazon.com/Some-Product-Name/dp/B08N5WRWNW/ref=sr_1_3?keywords=usb+c+cable&qid=1&sr=8-3",
            "https://smile.amazon.com/dp/B000000000?tag=someone-20",
            "http://amazon.com/gp/aw/d/x/dp/B0C1234567#reviews",
        };
        for (String link : products) {
            List<String> before = stock.tap(link);
            equal(List.of("queue " + REPORT + " POST " + AD_SERVER + " cache=false params={url=" + link + "}", "open " + link),
                    before, "stock Sync reports and opens " + link);
            equal(List.of("open " + link), patched.tap(link), "patched Sync only opens " + link);
        }
        // Everything else: identical before and after, and stock Sync never reported it.
        String[][] others = {
            {"https://www.amazon.com/s?k=usb+cable", "open https://www.amazon.com/s?k=usb+cable"},
            {"https://www.amazon.de/dp/B08N5WRWNW", "open https://www.amazon.de/dp/B08N5WRWNW"},
            {"https://www.amazon.com/gp/product/B08N5WRWNW", "open https://www.amazon.com/gp/product/B08N5WRWNW"},
            {"https://www.reddit.com/r/pics/comments/abc123/a_title/", "open https://www.reddit.com/r/pics/comments/abc123/a_title/"},
            {"https://i.redd.it/abcdef123.jpg", "open https://i.redd.it/abcdef123.jpg"},
            {"https://preview.redd.it/x.png?width=640&format=png&auto=webp&s=0123abcd", "open https://preview.redd.it/x.png?width=640&format=png&auto=webp&s=0123abcd"},
            {"https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42", "open https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"},
            {"https://imgur.com/gallery/abc", "open https://imgur.com/gallery/abc"},
            {"/u/someone", "open https://reddit.com/u/someone"},
            {"sync-flair://Discussion", "toast Discussion"},
            {"<table><tr><td>a</td></tr></table>", "sheet " + TABLE + " <table><tr><td>a</td></tr></table>"},
        };
        for (String[] row : others) {
            equal(List.of(row[1]), stock.tap(row[0]), "stock Sync for " + row[0]);
            equal(List.of(row[1]), patched.tap(row[0]), "patched Sync for " + row[0]);
        }
    }

    private static void backstop(Sync stock, Sync patched) {
        String link = "https://www.amazon.com/dp/B08N5WRWNW";
        equal(AD_SERVER, stock.request(link).fields.get("volley.url"), "stock request address");
        DexRunner.SyncObject request = patched.request(link);
        equal(DISABLED, request.fields.get("volley.url"), "patched request address");
        equal(1, request.fields.get("volley.method"), "request method unchanged (POST)");
        equal(link, request.fields.get("a"), "request still stores its link");
        try { new URL(DISABLED); throw new AssertionError("Backstop address is a network URL"); }
        catch (MalformedURLException expected) { check(true, "backstop address is not a network URL"); }
    }

    /** Stand-ins for Android and Sync's services that record what a tap asks for. */
    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();

        Sync(BytecodePatchContext context) {
            this.context = context;
            this.run = new DexRunner(context, this::intercept);
        }

        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Landroid/view/View;->getContext()Landroid/content/Context;": return "context";
                case "Lorg/apache/commons/lang3/StringUtils;->contains(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().contains(a[1].toString()) ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->startsWith(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().startsWith(a[1].toString()) ? 1 : 0;
                case "Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V": {
                    DexRunner.SyncObject request = (DexRunner.SyncObject) a[0];
                    request.fields.put("volley.method", a[1]);
                    request.fields.put("volley.url", a[2]);
                    request.fields.put("volley.errors", a[3]);
                    return null;
                }
                case "Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;":
                    ((DexRunner.SyncObject) a[0]).fields.put("volley.cache", a[1]);
                    return a[0];
                case "Lm8/a;->a(Lcom/android/volley/Request;)V": {
                    DexRunner.SyncObject request = (DexRunner.SyncObject) a[0];
                    Object params = run.run(method(context, request.type, "getParams", List.of()), new Object[] {request});
                    events.add("queue " + request.type + " " + (Integer.valueOf(1).equals(request.fields.get("volley.method")) ? "POST" : "?")
                            + " " + request.fields.get("volley.url") + " cache=" + !Integer.valueOf(0).equals(request.fields.get("volley.cache"))
                            + " params=" + params);
                    return null;
                }
                case "Ly7/b;->b(Landroid/content/Context;Ljava/lang/String;)Z":
                    equal("context", a[0], "router gets the view's context");
                    events.add("open " + a[1]);
                    return 1;
                case "Lgb/p;->d(Ljava/lang/String;)V": events.add("toast " + a[0]); return null;
                case "Lt7/j;->g(Landroid/content/Context;)Landroidx/fragment/app/FragmentManager;": return "fragments";
                case "Ls9/g;->j(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Ljava/lang/String;)V":
                    events.add("sheet " + a[0] + " " + a[2]);
                    return null;
                default: return DexRunner.PASS;
            }
        }

        List<String> tap(String link) {
            events.clear();
            DexRunner.SyncObject span = new DexRunner.SyncObject(SPAN);
            span.fields.put("a", link);
            run.run(method(context, SPAN, "onClick", List.of("Landroid/view/View;")), new Object[] {span, "view"});
            return new ArrayList<>(events);
        }

        DexRunner.SyncObject request(String link) {
            DexRunner.SyncObject request = new DexRunner.SyncObject(REPORT);
            run.run(method(context, REPORT, "<init>", List.of("Ljava/lang/String;")), new Object[] {request, link});
            return request;
        }
    }

    // ---- The edit and rejection ----

    /** Every method of both classes, so a check also shows what did not change. */
    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String type : List.of(SPAN, REPORT)) {
            ClassDef clazz = context.getClassDefs().get(type);
            if (clazz == null) throw new AssertionError("Missing " + type);
            for (MutableMethod m : context.proxy(clazz).getMutableClass().getMethods())
                result.put(type + "->" + m.getName() + m.getParameterTypes(), m.getImplementation() == null ? List.of() : keys(m));
        }
        return result;
    }

    private static void edit(Map<String, List<String>> before, Map<String, List<String>> after) {
        String click = SPAN + "->onClick[Landroid/view/View;]", init = REPORT + "-><init>[Ljava/lang/String;]";
        equal(before.keySet(), after.keySet(), "no method added or removed");
        for (String name : before.keySet())
            if (!name.equals(click) && !name.equals(init)) equal(before.get(name), after.get(name), name + " unchanged");
        // onClick: exactly the report's five instructions are gone.
        List<String> stock = before.get(click);
        int made = stock.indexOf("NEW_INSTANCE:" + REPORT);
        check(made > 0 && stock.lastIndexOf("NEW_INSTANCE:" + REPORT) == made, "stock onClick builds the report once");
        equal(List.of("NEW_INSTANCE:" + REPORT, "INVOKE_VIRTUAL:" + SPAN + "->a()Ljava/lang/String;", "MOVE_RESULT_OBJECT:",
                "INVOKE_DIRECT:" + REPORT + "-><init>(Ljava/lang/String;)V", "INVOKE_STATIC:Lm8/a;->a(Lcom/android/volley/Request;)V"),
                stock.subList(made, made + 5), "stock report instructions");
        List<String> expected = new ArrayList<>(stock);
        expected.subList(made, made + 5).clear();
        equal(expected, after.get(click), "only the report is removed from onClick");
        check(after.get(click).stream().noneMatch(k -> k.contains(REPORT) || k.contains("Lm8/a;")), "onClick queues nothing");
        check(after.get(click).contains("CONST_STRING:amazon.com/"), "the rest of onClick is kept");
        // The request: only its address constant changes.
        List<String> request = new ArrayList<>(before.get(init));
        int address = request.indexOf("CONST_STRING:" + AD_SERVER);
        check(address > 0, "stock request has the ad-server address");
        request.set(address, "CONST_STRING:" + DISABLED);
        equal(request, after.get(init), "request address replaced, nothing else");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already removed"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown layout (the product check no longer reads "/dp/") fails before any edit.
        rejected(source, scratch, "unsupported", SPAN, "onClick", "/dp/", "/xx/", "CustomUrlSpan Amazon report");
        // So does a request with another address.
        rejected(source, scratch, "address", REPORT, "<init>", AD_SERVER, "https://example.invalid/add", "Amazon report address");
    }

    private static void rejected(File source, Path scratch, String name, String type, String methodName, String from, String to,
                                 String reason) {
        BytecodePatchContext other = context(source, scratch, name);
        MutableMethod method = null;
        for (MutableMethod m : other.proxy(other.getClassDefs().get(type)).getMutableClass().getMethods())
            if (m.getName().equals(methodName)) method = m;
        List<Instruction> body = new ArrayList<>(method.getImplementation().getInstructions());
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if (from.equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker >= 0, name + ": marker found");
        method.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference(to)));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError(name + ": unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains(reason), name + ": rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), name + ": rejection makes no edits");
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
        SyncCompatPatchesKt.getStopSyncAmazonLinkReports().getApply$patcher().invoke(context, resources);
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
