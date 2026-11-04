import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: fix image peek" and long-presses posts through Sync's peek before and after it:
 * the layout listener (ImagePeekDialogFragment$f.onGlobalLayout) that picks a text post's URL,
 * and the picture and GIF loaders (V3, U3) that hand a URL to Glide. Runs on hand-written models,
 * or, given a local stock Sync 23033 APK, on the real methods. Glide, Android views and the post's
 * stored fields are stood in for; the suite records the URL Glide is asked to load.
 *
 * Stock Sync loads Reddit's escaped "&amp;" URLs, which Reddit refuses, and a text post's 140px
 * thumbnail. Patched, Glide gets the URL with its escaping undone, and a text post shows the
 * body's link to the same picture (ImagePeekCompat.textPostImage), which the peek also keeps in F0.
 */
public final class ImagePeekPatchTest {
    private static final String PEEK = "Lapp/revanced/extension/synccompat/ImagePeekCompat;";
    private static final String DIALOG = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;";
    private static final String LISTENER = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;";
    private static final String POST = "Lxa/d;";
    private static final List<String> URL = List.of("Ljava/lang/String;");
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    // Shapes from Reddit listings (made-up IDs and signatures), as Sync stores them: escaped.
    private static final String ID = "7idyhcufrhzh1";
    private static final String SIG = "a896079fd1a37caf6052b9d4954a28f529f270bc";
    private static final String THUMB = "https://preview.redd.it/" + ID + ".png?width=140&height=140&crop=1:1,smart&auto=webp&s="
            + "0123456789abcdef0123456789abcdef01234567";
    private static final String FULL = "https://preview.redd.it/" + ID + ".png?width=714&format=png&auto=webp&s=" + SIG;
    private static final String PREVIEW = "https://preview.redd.it/" + ID + ".png?width=640&crop=smart&auto=webp&s=" + SIG;
    private static final String LEGACY = "https://b.thumbs.redditmedia.com/f3llsNZB1PdhZ3lsyb9ok_z1qiC5l5_WnEghRF0A4qI.jpg";

    private static String escaped(String url) {
        return url.replace("&", "&amp;");
    }

    /** Text posts: preview_url, thumbnail, body; stock load; patched load. */
    private static final String[][] TEXT_POSTS = {
        // A picture in a text post: no preview block, a preview.redd.it thumbnail.
        {"", escaped(THUMB), FULL + "\n\n", "picture " + escaped(THUMB), "picture " + FULL},
        {null, escaped(THUMB), "Look at this:\n\n" + FULL + "\n\nWhy?", "picture " + escaped(THUMB), "picture " + FULL},
        // Sync's own body parse can append &height=<y>; Reddit refuses that form too.
        {"", escaped(THUMB), FULL + "&height=542\n\n", "picture " + escaped(THUMB), "picture " + FULL},
        // The original, as a just-made post's body names it.
        {"", escaped(THUMB), "https://i.redd.it/" + ID + ".png\n\n", "picture " + escaped(THUMB), "picture https://i.redd.it/" + ID + ".png"},
        // No usable body link: the thumbnail, loadable now.
        {"", escaped(THUMB), "just words", "picture " + escaped(THUMB), "picture " + THUMB},
        {"", escaped(THUMB), "https://preview.redd.it/aaaaaaaaaaaa1.jpg?width=99&s=" + SIG, "picture " + escaped(THUMB), "picture " + THUMB},
        {"", escaped(THUMB), "https://preview.redd.it/" + ID + ".gif?format=mp4&s=" + SIG, "picture " + escaped(THUMB), "picture " + THUMB},
        // A stored preview comes first in Sync's choice; the body's link to it is larger.
        {escaped(PREVIEW), escaped(THUMB), FULL, "picture " + escaped(PREVIEW), "picture " + FULL},
        {escaped(PREVIEW), escaped(THUMB), "", "picture " + escaped(PREVIEW), "picture " + PREVIEW},
        // Legacy thumbnails and website previews are left as they are.
        {"", LEGACY, FULL, "picture " + LEGACY, "picture " + LEGACY},
        {"https://external-preview.redd.it/abc.jpg?width=640&amp;s=" + SIG, LEGACY, "https://example.com",
            "picture https://external-preview.redd.it/abc.jpg?width=640&amp;s=" + SIG,
            "picture https://external-preview.redd.it/abc.jpg?width=640&s=" + SIG},
    };

    /** URLs handed straight to the loaders: loader, URL, URL Glide gets once patched. */
    private static final String[][] LOADS = {
        {"V3", escaped(PREVIEW), PREVIEW},
        {"V3", PREVIEW, PREVIEW},
        {"V3", "https://i.imgur.com/abcdefg.jpg", "https://i.imgur.com/abcdefg.jpg"},
        {"U3", "https://preview.redd.it/" + ID + ".gif?width=320&amp;format=png8&amp;s=" + SIG,
            "https://preview.redd.it/" + ID + ".gif?width=320&format=png8&s=" + SIG},
        {"U3", "https://i.redd.it/" + ID + ".gif", "https://i.redd.it/" + ID + ".gif"},
    };

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-image-peek-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/imagepeek"), "assemble Sync models");
            boolean real = args.length != 0;
            File source = real ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stock = context(source, scratch, "stock");
            BytecodePatchContext patched = context(source, scratch, "patched");
            Map<String, List<String>> before = snapshot(patched);
            apply(patched);
            hooks(patched, before);

            for (String[] row : TEXT_POSTS) {
                String label = "text post " + Arrays.toString(Arrays.copyOf(row, 3));
                Peek stockPeek = peek(stock, 2, row[0], row[1], row[2], null);
                equal(List.of(row[3]), stockPeek.loads, "stock " + label);
                Peek patchedPeek = peek(patched, 2, row[0], row[1], row[2], null);
                equal(List.of(row[4]), patchedPeek.loads, "patched " + label);
                equal(row[4].substring("picture ".length()), patchedPeek.fragment.fields.get("F0"), "F0 keeps the shown URL " + label);
            }
            for (String[] row : LOADS) {
                String kind = row[0].equals("V3") ? "picture " : "gif ";
                equal(List.of(kind + row[1]), load(stock, row[0], row[1]), "stock " + row[0] + " " + row[1]);
                equal(List.of(kind + row[2]), load(patched, row[0], row[1]), "patched " + row[0] + " " + row[1]);
            }
            if (!real) {
                // Other posts take the model's link branch: only the loaders' fix applies.
                equal(List.of("picture " + PREVIEW), peek(patched, 1, "", "", FULL, escaped(PREVIEW)).loads, "link post picture");
                equal(List.of("gif https://i.redd.it/" + ID + ".gif"),
                        peek(patched, 1, "", escaped(THUMB), FULL, "https://i.redd.it/" + ID + ".gif").loads, "link post GIF");
                equal(List.of("picture " + escaped(PREVIEW)), peek(stock, 1, "", "", FULL, escaped(PREVIEW)).loads, "stock link post");
            }

            rejections(source, scratch);

            System.out.println("PASS: " + checks + " image peek patch checks (" + TEXT_POSTS.length + " text posts, " + LOADS.length
                    + " loader URLs, " + (real ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Hook structure ----

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        for (String name : List.of("V3", "U3")) {
            MutableMethod loader = method(context, DIALOG, name, URL);
            List<Instruction> code = code(loader);
            int p1 = loader.getImplementation().getRegisterCount() - 1;
            equal(PEEK + "->imageUrl(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(0)), name + " undoes escaping first");
            equal(p1, ((FiveRegisterInstruction) code.get(0)).getRegisterC(), name + " passes its URL (p1)");
            equal(Opcode.MOVE_RESULT_OBJECT, code.get(1).getOpcode(), name + " keeps the result");
            equal(p1, ((OneRegisterInstruction) code.get(1)).getRegisterA(), name + " replaces p1");
            List<String> stock = before.get(signature(loader)), now = keys(loader);
            equal(stock.get(0), now.get(0), name + " keeps its registers");
            equal(stock.subList(1, stock.size()), now.subList(3, now.size()), name + " keeps Sync's body");
        }

        MutableMethod layout = method(context, LISTENER, "onGlobalLayout", List.of());
        List<Instruction> code = code(layout);
        int show = -1;
        for (int i = 0; i < code.size(); i++)
            if ((DIALOG + "->V3(Ljava/lang/String;)V").equals(reference(code.get(i)))) { show = i; break; }
        check(show > 6, "text branch still shows through V3");
        FiveRegisterInstruction call = (FiveRegisterInstruction) code.get(show);
        int fragment = call.getRegisterC(), url = call.getRegisterD();
        List<String> added = List.of("IGET_OBJECT:" + DIALOG + "->E0:" + POST, "INVOKE_VIRTUAL:" + POST + "->P0()Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:", "INVOKE_STATIC:" + PEEK + "->textPostImage(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:", "IPUT_OBJECT:" + DIALOG + "->F0:Ljava/lang/String;");
        List<String> now = keys(layout), expected = new ArrayList<>(before.get(signature(layout)));
        expected.addAll(show + 1 - 6, added);
        equal(expected, now, "layout listener only gains the text-post lookup");
        int scratchRegister = ((TwoRegisterInstruction) code.get(show - 6)).getRegisterA();
        check(scratchRegister != fragment && scratchRegister != url && scratchRegister < layout.getImplementation().getRegisterCount() - 1,
                "scratch register is not the fragment, URL or this");
        equal(fragment, ((TwoRegisterInstruction) code.get(show - 6)).getRegisterB(), "post read from the fragment");
        equal(scratchRegister, ((FiveRegisterInstruction) code.get(show - 3)).getRegisterC(), "body passed first");
        equal(url, ((FiveRegisterInstruction) code.get(show - 3)).getRegisterD(), "peek URL passed second");
        equal(url, ((OneRegisterInstruction) code.get(show - 2)).getRegisterA(), "result is the URL V3 shows");
        equal(url, ((TwoRegisterInstruction) code.get(show - 1)).getRegisterA(), "F0 keeps the result");
        equal(fragment, ((TwoRegisterInstruction) code.get(show - 1)).getRegisterB(), "F0 of the fragment");
        equal(Opcode.RETURN_VOID, code.get(show + 1).getOpcode(), "text branch still returns after V3");
        equal(3, edited(context).size(), "three methods edited");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A listener whose text-post check is not sync_type 2 fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod layout = method(other, LISTENER, "onGlobalLayout", List.of());
        List<Instruction> body = code(layout);
        int type = -1;
        for (int i = 0; i < body.size(); i++) if ((POST + "->Y0()I").equals(reference(body.get(i)))) { type = i; break; }
        check(type > 0 && body.get(type + 2).getOpcode() == Opcode.CONST_4, "sync_type check found");
        layout.getImplementation().replaceInstruction(type + 2,
                new BuilderInstruction11n(Opcode.CONST_4, ((OneRegisterInstruction) body.get(type + 2)).getRegisterA(), 3));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("text post image peek"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");
    }

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MutableMethod m : methods(context)) result.put(signature(m), keys(m));
        return result;
    }
    private static Set<String> edited(BytecodePatchContext context) {
        Set<String> result = new TreeSet<>();
        for (MutableMethod m : methods(context))
            if (code(m).stream().anyMatch(i -> reference(i).startsWith(PEEK))) result.add(signature(m));
        return result;
    }
    private static List<MutableMethod> methods(BytecodePatchContext context) {
        return List.of(method(context, DIALOG, "V3", URL), method(context, DIALOG, "U3", URL),
                method(context, LISTENER, "onGlobalLayout", List.of()));
    }

    // ---- Long-presses ----

    private static final class Peek {
        final List<String> loads = new ArrayList<>();
        final DexRunner.SyncObject fragment = new DexRunner.SyncObject(DIALOG);
    }

    /** Opens the peek for a post: runs the layout listener and returns what Glide was asked to load. */
    private static Peek peek(BytecodePatchContext context, int type, String preview, String thumbnail, String body, String link) {
        Peek peek = new Peek();
        Map<String, Object> post = new HashMap<>();
        post.put("Y0", type);
        post.put("H0", preview);
        post.put("Z0", thumbnail);
        post.put("P0", body);
        post.put("e1", link);
        DexRunner.SyncObject postObject = new DexRunner.SyncObject(POST);
        peek.fragment.fields.put("E0", postObject);
        peek.fragment.fields.put("mRootView", "root view");
        peek.fragment.fields.put("mImageView", "image view");
        DexRunner.SyncObject listener = new DexRunner.SyncObject(LISTENER);
        listener.fields.put("a", peek.fragment);
        runner(context, new Services(peek.loads, postObject, post)).run(method(context, LISTENER, "onGlobalLayout", List.of()),
                new Object[] {listener});
        return peek;
    }

    /** Calls a loader directly with a URL. */
    private static List<String> load(BytecodePatchContext context, String loader, String url) {
        Peek peek = new Peek();
        peek.fragment.fields.put("mImageView", "image view");
        runner(context, new Services(peek.loads, null, Map.of())).run(method(context, DIALOG, loader, URL),
                new Object[] {peek.fragment, url});
        return peek.loads;
    }

    /** Stands in for Android views, Sync's app context, Glide and the post's stored fields. */
    private static final class Services implements DexRunner.Interceptor {
        final List<String> loads;
        final Object post;
        final Map<String, Object> fields;
        Services(List<String> loads, Object post, Map<String, Object> fields) {
            this.loads = loads;
            this.post = post;
            this.fields = fields;
        }

        @Override public Object call(String ref, Object[] a) {
            if (ref.startsWith(POST + "->")) {
                check(a[0] == post, "post getter on the peek's post: " + ref);
                String name = ref.substring(POST.length() + 2, ref.indexOf('('));
                check(fields.containsKey(name), "stood-in post field " + name);
                return fields.get(name);
            }
            if (ref.equals(DIALOG + "->T3()Z")) return 1;
            if (ref.equals("Landroid/widget/RelativeLayout;->getViewTreeObserver()Landroid/view/ViewTreeObserver;")) return "observer";
            if (ref.startsWith("Landroid/view/ViewTreeObserver;->removeGlobalOnLayoutListener(")) return null;
            if (ref.startsWith("Landroid/widget/ImageView;->setVisibility(") || ref.startsWith("Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;->setVisibility(")) return null;
            if (ref.equals("Lorg/apache/commons/lang3/StringUtils;->isEmpty(Ljava/lang/CharSequence;)Z"))
                return a[0] == null || ((CharSequence) a[0]).length() == 0 ? 1 : 0;
            if (ref.equals("Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;")) return "app context";
            if (ref.equals("Lcom/bumptech/glide/b;->u(Landroid/content/Context;)Lcom/bumptech/glide/g;")) return "glide";
            if (ref.equals("Lcom/bumptech/glide/g;->x(Ljava/lang/String;)Lcom/bumptech/glide/f;")) { loads.add("picture " + a[1]); return "request"; }
            if (ref.equals("Lcom/bumptech/glide/g;->n()Lcom/bumptech/glide/f;")) return "gif request";
            if (ref.equals("Lcom/bumptech/glide/f;->G0(Ljava/lang/String;)Lcom/bumptech/glide/f;")) { loads.add("gif " + a[1]); return "request"; }
            if (ref.startsWith("Lt3/a;->") || ref.startsWith("Lcom/bumptech/glide/f;->p0(")) return a[0];
            if (ref.startsWith("Lcom/bumptech/glide/f;->B0(")) return null;
            if (ref.startsWith(DIALOG.substring(0, DIALOG.length() - 1) + "$") && ref.contains("-><init>(")) return null;
            return DexRunner.PASS;
        }
    }

    // ---- Helpers ----

    private static DexRunner runner(BytecodePatchContext context, Services services) {
        DexRunner runner = new DexRunner(context, services, PEEK);
        runner.preset("Lw2/c;->a:Lw2/c;", "transition");
        return runner;
    }
    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getFixSyncImagePeek().getApply$patcher().invoke(context, resources);
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
    private static List<Instruction> code(MutableMethod method) {
        return new ArrayList<>(method.getImplementation().getInstructions());
    }
    private static String reference(Instruction i) {
        return i instanceof ReferenceInstruction ? ((ReferenceInstruction) i).getReference().toString() : "";
    }
    private static List<String> keys(MutableMethod method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        for (Instruction i : code(method)) result.add(i.getOpcode() + ":" + reference(i));
        return result;
    }
}
