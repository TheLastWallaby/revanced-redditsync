import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
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
            DexRunner stock = runner(stockContext), patched = runner(patchedContext);

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
                String stockUrl = requestUrl(stock, stockContext, requests[n][0], inputs[n]);
                check(stockUrl.startsWith(requests[n][1]), "stock request " + requests[n][0] + " uses " + requests[n][1] + ", got " + stockUrl);
                String url = requestUrl(patched, patchedContext, requests[n][0], inputs[n]);
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

    private static String route(DexRunner run, String link) {
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

    // ---- Runner ----

    private static DexRunner runner(BytecodePatchContext context) {
        return new DexRunner(context, (ref, arguments) -> {
            if (ref.equals(REQUEST_INIT)) throw new RequestUrl((String) arguments[2]);
            return DexRunner.PASS;
        }, RETIRED);
    }

    /** The URL a request constructor hands to Volley's Request constructor. */
    private static String requestUrl(DexRunner run, BytecodePatchContext context, String type, String link) {
        MutableMethod init = method(context, type, "<init>", List.of(INIT_PARAMS.split("(?<=;)")));
        try { run.run(init, new Object[] {new DexRunner.Uninitialized(type), link, null, null}); }
        catch (RequestUrl url) { return url.url; }
        throw new AssertionError(type + " never called Request");
    }

    private static final class RequestUrl extends RuntimeException {
        final String url;
        RequestUrl(String url) { super(null, null, false, false); this.url = url; }
    }
}
