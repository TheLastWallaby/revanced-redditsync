import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderExceptionHandler;
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction;
import com.android.tools.smali.dexlib2.builder.BuilderTryBlock;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/**
 * Applies "Sync: skip retired media hosts" and executes Sync's link classifiers (LinkTypeHelper,
 * y7.c), post-type helper (SyncTypeHelper.h, c8.e.h), image-URL helper (ImageHelper.b, c8.a.b) and
 * resolver request constructors (r8.a, r8.c, r8.j) before and after it: on hand-written models,
 * or, given a local stock Sync 23033 APK, on the real methods.
 *
 * The media part of LinkHelper (y7.b.c) is walked as a model that calls the executed classifiers
 * in Sync's order (YouTube, Tumblr post, Imgur album, Streamable, mixtape, vid.me, Gfycat, RedGifs,
 * Reddit video, media, otherwise the browser with the link). Links on retired hosts reach the
 * browser with the original link; other media hosts keep their route, type and image URL.
 */
public final class RetiredMediaPatchTest {
    private static final String RETIRED = "Lapp/revanced/extension/synccompat/RetiredMediaHosts;";
    private static final String TYPES = "Ly7/c;";
    private static final String S = "Ljava/lang/String;";
    private static final String REQUEST_INIT = "Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V";
    private static final String INIT_PARAMS = "Ljava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    /** Retired-host links; true where stock Sync sends the tap to a viewer or resolver, not the browser. */
    private static final Object[][] DEAD = {
        {"https://vid.me/abc12", true}, {"HTTP://VID.ME/Abc12", true}, {"https://m.vid.me/abc12", true},
        {"https://www.vid.me/abc12?autoplay=1#t=3", true}, {"vid.me/abc12", true},
        {"https://sli.mg/AbCd", true}, {"https://i.sli.mg/AbCd.gif", true}, {"http://www.SLI.MG/AbCd", true},
        {"https://m.sli.mg/AbCd.jpg", true},
        {"https://i.eroshare.com/abcd.jpg", true}, {"https://www.eroshare.com/i/abcd1234", false},
        {"http://eroshare.com/abcd1234", false}, {"https://v.EroShare.com/abcd.gif", true},
        {"https://picsarus.com/AbCd.jpg", true}, {"http://picsarus.com/AbCd", false},
        {"https://m.picsarus.com/AbCd.gif", true}, {"http://www.PicSarus.com/AbCd.png", true},
        {"https://www.deviantart.com/artist/art/Title-123", true}, {"http://DeviantArt.com/artist/art/Title-123", true},
        {"https://example.tumblr.com/post/123/title", true}, {"http://www.tumblr.com/blog/example/post/123", true},
    };

    /** Other hosts: link and the route Sync gives it, unchanged by the patch. */
    private static final String[][] KEPT = {
        {"https://i.imgur.com/AbCd.jpg", "image viewer"}, {"https://imgur.com/a/AbCd", "album viewer"},
        {"https://www.redgifs.com/watch/joyfulwhitebird", "RedGifs"}, {"https://giphy.com/gifs/abc-123", "image viewer"},
        {"https://media.giphy.com/media/abc/giphy.gif", "image viewer"}, {"https://i.redd.it/abc.jpg", "image viewer"},
        {"https://v.redd.it/abc123", "Reddit video"}, {"https://www.youtube.com/watch?v=aqz-KE-bpKQ", "YouTube"},
        {"https://youtu.be/aqz-KE-bpKQ", "YouTube"}, {"https://gfycat.com/foo", "Gfycat"},
        {"https://streamable.com/abc", "Streamable"}, {"https://64.media.tumblr.com/abc/s640x960/a.jpg", "image viewer"},
        {"https://example.com/article", "browser https://example.com/article"},
        {"https://www.deviantart.com/artist/gallery/123", "browser https://www.deviantart.com/artist/gallery/123"},
    };

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-retired-media-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/retiredmedia"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stockContext = context(source, scratch, "stock");
            BytecodePatchContext patchedContext = context(source, scratch, "patched");
            Map<String, List<String>> before = new LinkedHashMap<>();
            for (MutableMethod m : edited(patchedContext)) before.put(signature(m), keys(m));
            apply(patchedContext);
            Runner stock = new Runner(stockContext), patched = new Runner(patchedContext);

            hooks(patchedContext, before);

            for (Object[] row : DEAD) {
                String link = (String) row[0];
                String browser = "browser " + schemeAsSync(link);
                String stockRoute = route(stock, link);
                if ((Boolean) row[1]) check(!stockRoute.startsWith("browser"), "stock Sync sends " + link + " to a viewer, got " + stockRoute);
                else equal(browser, stockRoute, "stock Sync already opens " + link + " in the browser");
                equal(browser, route(patched, link), "patched Sync opens the original link " + link);
                for (String classifier : new String[] {"H", "z", "e", "C", "j"})
                    equal(0, patched.call(TYPES, classifier, link), "patched y7.c." + classifier + " " + link);
                equal(3, patched.call("Lc8/e;", "h", post(link), 3), "patched link post type " + link);
                equal(link, patched.call("Lc8/a;", "b", link, ""), "patched image URL keeps " + link);
            }
            // The defect: stock Sync makes image or GIF posts from retired links.
            for (String link : new String[] {"https://i.sli.mg/AbCd.gif", "https://m.picsarus.com/AbCd.gif", "https://v.EroShare.com/abcd.gif"})
                equal(4, stock.call("Lc8/e;", "h", post(link), 3), "stock GIF post " + link);
            for (String link : new String[] {"https://picsarus.com/AbCd.jpg", "https://vid.me/abc12", "https://i.eroshare.com/abcd.jpg"})
                equal(1, stock.call("Lc8/e;", "h", post(link), 3), "stock image post " + link);
            // ... and rewrites a picsarus link to a plain-HTTP image URL.
            equal("http://picsarus.com/AbCd.jpg", stock.call("Lc8/a;", "b", "https://picsarus.com/AbCd", ""), "stock picsarus rewrite");

            for (String[] row : KEPT) {
                String link = row[0];
                String expected = row[1];
                equal(expected, route(stock, link), "stock route " + link);
                equal(expected, route(patched, link), "patched route " + link);
                for (String classifier : new String[] {"H", "z", "e", "C", "j"})
                    equal(stock.call(TYPES, classifier, link), patched.call(TYPES, classifier, link), "y7.c." + classifier + " unchanged " + link);
                equal(stock.call("Lc8/e;", "h", post(link), 3), patched.call("Lc8/e;", "h", post(link), 3), "post type unchanged " + link);
                equal(stock.call("Lc8/a;", "b", link, ""), patched.call("Lc8/a;", "b", link, ""), "image URL unchanged " + link);
            }
            for (int kind : new int[] {1, 9})
                equal(stock.call("Lc8/e;", "h", post("https://vid.me/abc12"), kind),
                        patched.call("Lc8/e;", "h", post("https://vid.me/abc12"), kind), "post kind " + kind + " unchanged");

            // The resolver requests no longer carry a network URL.
            String[][] requests = {
                {"Lr8/a;", "http://backend.deviantart.com/oembed?url=", "sync-compat-retired:deviantart"},
                {"Lr8/c;", "https://api.eroshare.com/api/v1/albums/", "sync-compat-retired:eroshare"},
                {"Lr8/j;", "https://api.tumblr.com/v2/blog/", "sync-compat-retired:tumblr"},
            };
            String[] inputs = {"https://www.deviantart.com/artist/art/Title-123", "https://www.eroshare.com/abcd1234",
                    "https://example.tumblr.com/post/123/title"};
            for (int n = 0; n < requests.length; n++) {
                String stockUrl = stock.requestUrl(requests[n][0], inputs[n]);
                check(stockUrl.startsWith(requests[n][1]), "stock request " + requests[n][0] + " uses " + requests[n][1] + ", got " + stockUrl);
                String url = patched.requestUrl(requests[n][0], inputs[n]);
                equal(requests[n][2], url, "patched request " + requests[n][0]);
                try { new URL(url); throw new AssertionError("network URL " + url); }
                catch (MalformedURLException expected) { check(true, "no network scheme in " + url); }
            }

            rejections(source, scratch);

            System.out.println("PASS: " + checks + " retired media host patch checks (" + DEAD.length + " retired links, "
                    + KEPT.length + " other links, executed "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Hook structure ----

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        List<MutableMethod> methods = edited(context);
        for (MutableMethod m : methods) {
            String name = signature(m);
            List<String> stock = before.get(name), now = keys(m);
            List<Instruction> code = code(m);
            int p0 = m.getImplementation().getRegisterCount() - m.getParameterTypes().size() - (isStatic(m) ? 0 : 1);
            if (name.startsWith(TYPES) && "HzeC".contains(m.getName())) {
                equal(List.of("registers=" + m.getImplementation().getRegisterCount(), "CONST_4:", "RETURN:"), now, name + " returns false");
                equal(0, ((NarrowLiteralInstruction) code.get(0)).getNarrowLiteral(), name + " false");
            } else if (name.contains("-><init>(")) {
                int at = -1;
                for (int i = 0; i < code.size(); i++) if (REQUEST_INIT.equals(reference(code.get(i)))) at = i;
                check(at > 0 && code.get(at - 1).getOpcode() == Opcode.CONST_STRING, name + " sets the URL before Request");
                equal(((FiveRegisterInstruction) code.get(at)).getRegisterE(), ((OneRegisterInstruction) code.get(at - 1)).getRegisterA(),
                        name + " replaces the Request URL register");
                List<String> expected = new ArrayList<>(stock);
                expected.add(at, "CONST_STRING:" + string(code.get(at - 1)));
                equal(expected, now, name + " only adds the URL");
            } else {
                // Prefix hook, then Sync's own body unchanged.
                String hook = reference(code.get(0));
                check(hook.startsWith(RETIRED + "->"), name + " asks RetiredMediaHosts first");
                equal(p0, ((FiveRegisterInstruction) code.get(0)).getRegisterC(), name + " passes the link (p0)");
                int prefix = name.startsWith("Lc8/e;") || name.startsWith("Lc8/a;") ? 4 : 5;
                equal(stock.subList(1, stock.size()), now.subList(prefix + 1, now.size()), name + " keeps Sync's body");
                Instruction branch = code.get(2);
                check(((BuilderOffsetInstruction) branch).getTarget().getLocation().getInstruction() == code.get(prefix),
                        name + " falls through to Sync's first instruction");
            }
        }
        equal(10, methods.size(), "edited methods");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown request layout (no Eroshare endpoint) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod builder = method(other, "Lr8/c;", "a", List.of(S));
        List<Instruction> body = code(builder);
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if ("https://api.eroshare.com/api/v1/albums/".equals(string(body.get(i)))) marker = i;
        check(marker >= 0, "Eroshare marker found");
        builder.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("https://example.invalid/")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("EroshareGalleryRequest"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");
    }

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MutableMethod m : edited(context)) result.put(signature(m), keys(m));
        return result;
    }

    /** The ten methods the patch edits. */
    private static List<MutableMethod> edited(BytecodePatchContext context) {
        List<MutableMethod> result = new ArrayList<>();
        for (String name : new String[] {"H", "z", "e", "C", "j"}) result.add(method(context, TYPES, name, List.of(S)));
        result.add(method(context, "Lc8/e;", "h", List.of("Lorg/json/JSONObject;", "I")));
        result.add(method(context, "Lc8/a;", "b", List.of(S, S)));
        for (String type : new String[] {"Lr8/a;", "Lr8/c;", "Lr8/j;"})
            result.add(method(context, type, "<init>", List.of(INIT_PARAMS.split("(?<=;)"))));
        return result;
    }

    // ---- LinkHelper media routing model (y7.b.c) ----

    /** Sync lower-cases only the scheme ("HTTPS://" -> "https://") before routing. */
    private static String schemeAsSync(String link) {
        String lower = link.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("https")) return "https" + link.substring(5);
        if (lower.startsWith("http")) return "http" + link.substring(4);
        return link;
    }

    private static String route(Runner run, String link) {
        String v4 = schemeAsSync(link);
        if (yes(run.call(TYPES, "K", v4))) return "YouTube";
        if (yes(run.call(TYPES, "C", v4))) return "Tumblr viewer";
        if (yes(run.call("Lf8/a;", "f", v4))) return "album viewer";
        if (yes(run.call(TYPES, "A", v4))) return "Streamable";
        if (yes(run.call(TYPES, "m", v4))) return "mixtape";
        if (yes(run.call(TYPES, "H", v4))) return "vid.me viewer";
        if (yes(run.call(TYPES, "h", v4))) return "Gfycat";
        if (yes(run.call(TYPES, "o", v4))) return "RedGifs";
        if (yes(run.call(TYPES, "u", v4))) return "Reddit video";
        if (yes(run.call(TYPES, "j", v4))) return "image viewer";
        return "browser " + v4;
    }
    private static boolean yes(Object value) { return !Integer.valueOf(0).equals(value); }
    private static JSONObject post(String link) { return new JSONObject().put("url", link); }

    // ---- Helpers ----

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getSkipSyncRetiredMediaHosts().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name, List<String> parameters) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing " + type);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().equals(parameters)) return method;
        throw new AssertionError("Missing " + type + "->" + name + parameters);
    }
    private static String signature(MutableMethod m) {
        return m.getDefiningClass() + "->" + m.getName() + "(" + String.join("", m.getParameterTypes()) + ")" + m.getReturnType();
    }
    private static boolean isStatic(MutableMethod m) { return (m.getAccessFlags() & 0x8) != 0; }
    private static List<Instruction> code(MutableMethod method) {
        return new ArrayList<>(method.getImplementation().getInstructions());
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
        for (Instruction i : code(method)) result.add(i.getOpcode() + ":" + reference(i));
        return result;
    }

    // ---- Strict instruction runner ----

    /**
     * Executes Sync methods from a patch context. Sync's own static methods run instruction by
     * instruction; String, URI, Locale, regex, org.json, the Android test adapters and the
     * production RetiredMediaHosts run on the JVM; Sync's log calls do nothing. Any other call,
     * field or opcode fails the suite.
     */
    private static final class Runner {
        private static final Set<String> LOGS = Set.of("Lwc/i;", "Lt7/k;");
        private static final List<String> JVM = List.of("Ljava/lang/", "Ljava/net/URI;", "Ljava/util/Locale;",
                "Ljava/util/regex/", "Lorg/json/JSONObject;", "Landroid/webkit/", "Landroid/text/TextUtils;", "Landroid/net/Uri;", "Ljava/util/List;", RETIRED);
        private final BytecodePatchContext context;
        private final Map<String, Object> statics = new HashMap<>();
        private final Set<String> initialized = new HashSet<>();

        Runner(BytecodePatchContext context) { this.context = context; }

        Object call(String owner, String name, Object... args) {
            for (MutableMethod m : context.proxy(context.getClassDefs().get(owner)).getMutableClass().getMethods())
                if (m.getName().equals(name) && m.getParameterTypes().size() == args.length && isStatic(m)) return run(m, args);
            throw new AssertionError("Missing " + owner + "->" + name);
        }

        /** The URL a request constructor hands to Volley's Request constructor. */
        String requestUrl(String type, String link) {
            MutableMethod init = method(context, type, "<init>", List.of(INIT_PARAMS.split("(?<=;)")));
            try { run(init, new Object[] {new Uninitialized(type), link, null, null}); }
            catch (RequestUrl url) { return url.url; }
            throw new AssertionError(type + " never called Request");
        }

        private Object run(MutableMethod method, Object[] args) {
            List<Instruction> code = code(method);
            Map<Instruction, Integer> index = new IdentityHashMap<>();
            for (int n = 0; n < code.size(); n++) index.put(code.get(n), n);
            Object[] r = new Object[method.getImplementation().getRegisterCount()];
            System.arraycopy(args, 0, r, r.length - args.length, args.length);
            Object result = null;
            Throwable caught = null;
            int pc = 0, steps = 0;
            while (true) {
                if (++steps > 20000) throw new AssertionError("Unbounded execution in " + signature(method));
                Instruction i = code.get(pc);
                int next = pc + 1;
                try {
                    switch (i.getOpcode()) {
                        case NOP: case CHECK_CAST: break;
                        case CONST_STRING: case CONST_STRING_JUMBO: r[a(i)] = string(i); break;
                        case CONST_4: case CONST_16: case CONST: r[a(i)] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                        case MOVE: case MOVE_FROM16: case MOVE_OBJECT: case MOVE_OBJECT_FROM16:
                            r[a(i)] = r[((TwoRegisterInstruction) i).getRegisterB()]; break;
                        case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a(i)] = result; break;
                        case MOVE_EXCEPTION: r[a(i)] = caught; break;
                        case ADD_INT_LIT8: case ADD_INT_LIT16: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            r[t.getRegisterA()] = (Integer) r[t.getRegisterB()] + ((NarrowLiteralInstruction) i).getNarrowLiteral();
                            break;
                        }
                        case ADD_INT_2ADDR: case SUB_INT_2ADDR: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            int x = (Integer) r[t.getRegisterA()], y = (Integer) r[t.getRegisterB()];
                            r[t.getRegisterA()] = i.getOpcode() == Opcode.ADD_INT_2ADDR ? x + y : x - y;
                            break;
                        }
                        case ARRAY_LENGTH: r[a(i)] = Array.getLength(r[((TwoRegisterInstruction) i).getRegisterB()]); break;
                        case AGET_OBJECT: {
                            ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                            r[t.getRegisterA()] = Array.get(r[t.getRegisterB()], (Integer) r[t.getRegisterC()]);
                            break;
                        }
                        case APUT_OBJECT: {
                            ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                            Array.set(r[t.getRegisterB()], (Integer) r[t.getRegisterC()], r[t.getRegisterA()]);
                            break;
                        }
                        case NEW_ARRAY: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            String type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                            try { r[t.getRegisterA()] = Array.newInstance(type(type.substring(1)), (Integer) r[t.getRegisterB()]); }
                            catch (ClassNotFoundException missing) { throw new AssertionError("Unknown array type " + type); }
                            break;
                        }
                        case INSTANCE_OF: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            String type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                            Object value = r[t.getRegisterB()];
                            boolean is;
                            try { is = value != null && type(type).isInstance(value); }
                            catch (ClassNotFoundException notJvm) { is = false; } // Sync's own types: values here are JVM objects
                            r[t.getRegisterA()] = is ? 1 : 0;
                            break;
                        }
                        case IGET: case IGET_OBJECT: case IGET_BOOLEAN: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                            r[t.getRegisterA()] = ((SyncObject) r[t.getRegisterB()]).fields.getOrDefault(f.getName(),
                                    f.getType().startsWith("L") || f.getType().startsWith("[") ? null : 0);
                            break;
                        }
                        case IPUT: case IPUT_OBJECT: case IPUT_BOOLEAN: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                            ((SyncObject) r[t.getRegisterB()]).fields.put(f.getName(), r[t.getRegisterA()]);
                            break;
                        }
                        case NEW_INSTANCE: r[a(i)] = new Uninitialized(((TypeReference) ((ReferenceInstruction) i).getReference()).getType()); break;
                        case SGET_OBJECT: r[a(i)] = staticField((FieldReference) ((ReferenceInstruction) i).getReference()); break;
                        case SPUT_OBJECT: {
                            FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                            check(context.getClassDefs().get(f.getDefiningClass()) != null, "only Sync statics are written");
                            statics.put(f.toString(), r[a(i)]);
                            break;
                        }
                        case IF_EQZ: case IF_NEZ: case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ: {
                            Object v = r[a(i)];
                            int x = v instanceof Integer ? (Integer) v : v == null ? 0 : 1;
                            boolean jump;
                            switch (i.getOpcode()) {
                                case IF_EQZ: jump = x == 0; break;
                                case IF_NEZ: jump = x != 0; break;
                                case IF_LTZ: jump = x < 0; break;
                                case IF_GEZ: jump = x >= 0; break;
                                case IF_GTZ: jump = x > 0; break;
                                default: jump = x <= 0;
                            }
                            if (jump) next = target(index, i);
                            break;
                        }
                        case IF_EQ: case IF_NE: case IF_LT: case IF_GE: case IF_GT: case IF_LE: {
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            Object x = r[t.getRegisterA()], y = r[t.getRegisterB()];
                            int cmp = x instanceof Integer && y instanceof Integer ? Integer.compare((Integer) x, (Integer) y) : (x == y ? 0 : 1);
                            boolean jump;
                            switch (i.getOpcode()) {
                                case IF_EQ: jump = cmp == 0; break;
                                case IF_NE: jump = cmp != 0; break;
                                case IF_LT: jump = cmp < 0; break;
                                case IF_GE: jump = cmp >= 0; break;
                                case IF_GT: jump = cmp > 0; break;
                                default: jump = cmp <= 0;
                            }
                            if (jump) next = target(index, i);
                            break;
                        }
                        case GOTO: case GOTO_16: case GOTO_32: next = target(index, i); break;
                        case INVOKE_STATIC: case INVOKE_VIRTUAL: case INVOKE_DIRECT: case INVOKE_INTERFACE:
                        case INVOKE_STATIC_RANGE: case INVOKE_VIRTUAL_RANGE: case INVOKE_DIRECT_RANGE: case INVOKE_INTERFACE_RANGE:
                            result = invoke(i, r);
                            break;
                        case THROW: throw new Thrown((Throwable) r[a(i)]);
                        case RETURN: case RETURN_OBJECT: return r[a(i)];
                        case RETURN_VOID: return null;
                        default: throw new AssertionError("Unsupported opcode " + i.getOpcode() + " in " + signature(method));
                    }
                } catch (Thrown thrown) {
                    Integer handler = handler(method, pc, thrown.value);
                    if (handler == null) throw thrown;
                    caught = thrown.value;
                    next = handler;
                }
                pc = next;
            }
        }

        private Integer handler(MutableMethod method, int pc, Throwable value) {
            for (BuilderTryBlock block : method.getImplementation().getTryBlocks()) {
                if (pc < block.start.getLocation().getIndex() || pc >= block.end.getLocation().getIndex()) continue;
                for (BuilderExceptionHandler h : block.getExceptionHandlers()) {
                    String type = h.getExceptionType();
                    try {
                        if (type == null || type(type).isInstance(value)) return h.getHandler().getLocation().getIndex();
                    } catch (ClassNotFoundException missing) {
                        throw new AssertionError("Unknown exception type " + type);
                    }
                }
            }
            return null;
        }

        private Object invoke(Instruction i, Object[] r) {
            MethodReference call = (MethodReference) ((ReferenceInstruction) i).getReference();
            int[] regs;
            if (i instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction range = (RegisterRangeInstruction) i;
                regs = new int[range.getRegisterCount()];
                for (int n = 0; n < regs.length; n++) regs[n] = range.getStartRegister() + n;
            } else {
                FiveRegisterInstruction f = (FiveRegisterInstruction) i;
                regs = Arrays.copyOf(new int[] {f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG()},
                        f.getRegisterCount());
            }
            Object[] values = new Object[regs.length];
            for (int n = 0; n < regs.length; n++) values[n] = r[regs[n]];
            String owner = call.getDefiningClass();
            String ref = call.toString();
            if (ref.equals(REQUEST_INIT)) throw new RequestUrl((String) values[2]);
            if (LOGS.contains(owner)) {
                equal("V", call.getReturnType(), "Sync log call " + ref);
                return null;
            }
            boolean isStatic = i.getOpcode() == Opcode.INVOKE_STATIC || i.getOpcode() == Opcode.INVOKE_STATIC_RANGE;
            Object receiver = isStatic || values.length == 0 ? null : values[0];
            if (receiver instanceof Uninitialized && call.getName().equals("<init>")
                    && context.getClassDefs().get(((Uninitialized) receiver).type) != null) {
                SyncObject made = new SyncObject(((Uninitialized) receiver).type);
                for (int n = 0; n < r.length; n++) if (r[n] == receiver) r[n] = made;
                values[0] = receiver = made;
            }
            if (receiver instanceof SyncObject) {
                if (ref.equals("Ljava/lang/Object;-><init>()V")) return null;
                boolean direct = i.getOpcode() == Opcode.INVOKE_DIRECT || i.getOpcode() == Opcode.INVOKE_DIRECT_RANGE;
                for (String type = direct ? owner : ((SyncObject) receiver).type; type != null; ) {
                    ClassDef definition = context.getClassDefs().get(type);
                    if (definition == null) break;
                    for (MutableMethod m : context.proxy(definition).getMutableClass().getMethods())
                        if (m.getName().equals(call.getName()) && m.getReturnType().equals(call.getReturnType())
                                && m.getParameterTypes().equals(call.getParameterTypes().stream().map(Object::toString).toList()))
                            return run(m, values);
                    type = definition.getSuperclass();
                }
                throw new AssertionError("Missing Sync method " + ref);
            }
            ClassDef dexClass = context.getClassDefs().get(owner);
            if (dexClass != null && isStatic && !owner.equals(RETIRED)) {
                for (MutableMethod m : context.proxy(dexClass).getMutableClass().getMethods())
                    if (m.getName().equals(call.getName()) && m.getReturnType().equals(call.getReturnType())
                            && m.getParameterTypes().equals(call.getParameterTypes().stream().map(Object::toString).toList()))
                        return run(m, values);
                throw new AssertionError("Missing Sync method " + ref);
            }
            if (JVM.stream().noneMatch(owner::startsWith)) throw new AssertionError("Unexpected call " + ref);
            try {
                Class<?> type = type(owner);
                List<? extends CharSequence> params = call.getParameterTypes();
                Class<?>[] types = new Class<?>[params.size()];
                for (int n = 0; n < types.length; n++) types[n] = type(params.get(n).toString());
                int offset = isStatic ? 0 : 1;
                Object[] arguments = new Object[types.length];
                for (int n = 0; n < types.length; n++) arguments[n] = toJvm(values[n + offset], types[n]);
                if (call.getName().equals("<init>")) {
                    Object made = type.getConstructor(types).newInstance(arguments);
                    Object placeholder = values[0];
                    for (int n = 0; n < r.length; n++) if (r[n] == placeholder) r[n] = made;
                    return null;
                }
                Object value = type.getMethod(call.getName(), types).invoke(isStatic ? null : values[0], arguments);
                return fromJvm(value);
            } catch (InvocationTargetException thrown) {
                throw new Thrown(thrown.getCause());
            } catch (ReflectiveOperationException | IllegalArgumentException missing) {
                throw new AssertionError("Cannot call " + ref + " with " + Arrays.toString(values), missing);
            }
        }

        private Object staticField(FieldReference field) {
            String owner = field.getDefiningClass();
            ClassDef dexClass = context.getClassDefs().get(owner);
            if (dexClass != null) {
                if (initialized.add(owner)) {
                    for (MutableMethod m : context.proxy(dexClass).getMutableClass().getMethods())
                        if (m.getName().equals("<clinit>")) run(m, new Object[0]);
                }
                if (!statics.containsKey(field.toString())) throw new AssertionError("Unset Sync static " + field);
                return statics.get(field.toString());
            }
            if (JVM.stream().noneMatch(owner::startsWith)) throw new AssertionError("Unexpected field " + field);
            try { return type(owner).getField(field.getName()).get(null); }
            catch (ReflectiveOperationException missing) { throw new AssertionError("Cannot read " + field, missing); }
        }

        private static Object toJvm(Object value, Class<?> type) {
            if (type == boolean.class) return !Integer.valueOf(0).equals(value);
            if (type == char.class) return (char) (int) (Integer) value;
            // Dex writes null as the constant 0.
            if (!type.isPrimitive() && Integer.valueOf(0).equals(value) && type != Object.class) return null;
            return value;
        }
        private static Object fromJvm(Object value) {
            if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
            if (value instanceof Character) return (int) (Character) value;
            return value;
        }
        private static int a(Instruction i) { return ((OneRegisterInstruction) i).getRegisterA(); }
        private static int target(Map<Instruction, Integer> index, Instruction branch) {
            Integer at = index.get(((BuilderOffsetInstruction) branch).getTarget().getLocation().getInstruction());
            if (at == null) throw new AssertionError("Branch target not found");
            return at;
        }
    }

    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "I": return int.class;
            case "Z": return boolean.class;
            case "C": return char.class;
            case "J": return long.class;
            default:
                if (descriptor.startsWith("[")) return Class.forName(descriptor.replace('/', '.'));
                return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
    }

    private static final class Uninitialized {
        final String type;
        Uninitialized(String type) { this.type = type; }
    }
    /** An instance of one of Sync's own classes, with its fields. */
    private static final class SyncObject {
        final String type;
        final Map<String, Object> fields = new HashMap<>();
        SyncObject(String type) { this.type = type; }
    }
    private static final class Thrown extends RuntimeException {
        final Throwable value;
        Thrown(Throwable value) { super(null, null, false, false); this.value = value; }
    }
    private static final class RequestUrl extends RuntimeException {
        final String url;
        RequestUrl(String url) { super(null, null, false, false); this.url = url; }
    }
}
