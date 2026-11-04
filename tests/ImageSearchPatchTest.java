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
import java.net.URLDecoder;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: fix image search" and taps the full-screen image viewer's search button
 * (ImageViewerFragment.onSearchCliced) before and after it, with a recording LinkHandler.X: on a
 * hand-written model, or, given a local stock Sync 23033 APK, on the real method.
 *
 * Before: Google's retired images.google.com/searchbyimage address with the image URL appended
 * unencoded. After: Google Lens' uploadbyurl address with the URL encoded as one value, through
 * the same LinkHandler.X; a non-web URL keeps Sync's address. Every other method of the viewer is
 * unchanged. Also checks the exact hook, duplicate and unsupported-layout rejection.
 */
public final class ImageSearchPatchTest {
    private static final String VIEWER = "Lcom/laurencedawson/reddit_sync/ui/fragments/ImageViewerFragment;";
    private static final String HELPER = "Lapp/revanced/extension/synccompat/ImageSearch;";
    private static final String OLD = "https://images.google.com/searchbyimage?image_url=";
    private static final String LENS = "https://lens.google.com/uploadbyurl?url=";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-image-search-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/imagesearch"), "assemble Sync model");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            hook(before, snapshot(patched.context));
            taps(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " image search patch checks (search button opens Google Lens; executed "
                    + (args.length != 0 ? "real Sync method from the supplied stock APK)" : "hand-written Sync model)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Taps ----

    private static void taps(Sync stock, Sync patched) throws Exception {
        String[] images = {
            "https://i.redd.it/abc123def456.jpg",
            "https://preview.redd.it/a-title-v0-abc123.jpg?width=1080&format=pjpg&auto=webp&s=0123456789abcdef0123456789abcdef01234567",
            "https://i.imgur.com/AbCdE12.jpg",
            "http://i.imgur.com/AbCdE12.gif",
            "https://external-preview.redd.it/Ab_C-d.png?auto=webp&s=aa#frag",
        };
        for (String image : images) {
            equal(List.of("X activity " + OLD + image), stock.search(image), "stock Sync for " + image);
            List<String> events = patched.search(image);
            check(events.size() == 1 && events.get(0).startsWith("X activity " + LENS), "patched Sync opens Lens for " + image + ": " + events);
            String value = events.get(0).substring(("X activity " + LENS).length());
            check(value.indexOf('&') < 0 && value.indexOf('?') < 0 && value.indexOf('#') < 0, "one query value for " + image);
            equal(image, URLDecoder.decode(value, "UTF-8"), "Lens receives the exact image URL");
        }
        // Not a web URL (or no URL yet): Sync's own address, exactly as before.
        for (String image : new String[] {"file:///storage/emulated/0/Pictures/x.jpg", "content://media/external/images/1", "", null}) {
            List<String> expected = List.of("X activity " + OLD + image);
            equal(expected, stock.search(image), "stock Sync for " + image);
            equal(expected, patched.search(image), "patched Sync keeps its address for " + image);
        }
    }

    /** Stand-ins for the fragment's activity and Sync's external link opener. */
    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();

        Sync(BytecodePatchContext context) {
            this.context = context;
            this.run = new DexRunner(context, this::intercept, HELPER);
        }

        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;":
                    check(a[0] instanceof DexRunner.SyncObject && ((DexRunner.SyncObject) a[0]).type.equals(VIEWER), "activity of the viewer");
                    return "activity";
                case "Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V":
                    events.add("X " + a[0] + " " + a[1]);
                    return null;
                default: return DexRunner.PASS;
            }
        }

        List<String> search(String image) {
            events.clear();
            DexRunner.SyncObject viewer = new DexRunner.SyncObject(VIEWER);
            viewer.fields.put("u0", image);
            run.run(method(context, VIEWER, "onSearchCliced", List.of()), new Object[] {viewer});
            return new ArrayList<>(events);
        }
    }

    // ---- The hook and rejection ----

    /** Every method of the viewer, so the check also shows the other buttons are unchanged. */
    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new TreeMap<>();
        ClassDef clazz = context.getClassDefs().get(VIEWER);
        if (clazz == null) throw new AssertionError("Missing " + VIEWER);
        for (MutableMethod m : context.proxy(clazz).getMutableClass().getMethods())
            result.put(m.getName() + m.getParameterTypes() + m.getReturnType(), m.getImplementation() == null ? List.of() : keys(m));
        return result;
    }

    private static void hook(Map<String, List<String>> before, Map<String, List<String>> after) {
        String search = "onSearchCliced[]V";
        equal(before.keySet(), after.keySet(), "no method added or removed");
        int others = 0;
        for (String name : before.keySet())
            if (!name.equals(search)) { equal(before.get(name), after.get(name), name + " unchanged"); others++; }
        check(others > 0, "other viewer buttons compared");
        List<String> stock = before.get(search), now = after.get(search);
        equal(List.of("IGET_OBJECT:" + VIEWER + "->u0:Ljava/lang/String;",
                "INVOKE_STATIC:" + HELPER + "->lensUrl(Ljava/lang/String;)Ljava/lang/String;", "MOVE_RESULT_OBJECT:", "IF_EQZ:",
                "INVOKE_VIRTUAL:Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;", "MOVE_RESULT_OBJECT:",
                "INVOKE_STATIC:Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V", "RETURN_VOID:"),
                now.subList(1, 9), "Lens hook first");
        equal(stock.subList(1, stock.size()), now.subList(9, now.size()), "Sync's own body follows unchanged");
        equal(stock.get(0), now.get(0), "register count unchanged");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown layout (another search address) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod method = method(other, VIEWER, "onSearchCliced", List.of());
        List<Instruction> body = new ArrayList<>(method.getImplementation().getInstructions());
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if (OLD.equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker >= 0, "search address found");
        method.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("https://example.invalid/?u=")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("image search button"), "unknown layout rejected: " + expected.getMessage()); }
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
        SyncCompatPatchesKt.getFixSyncImageSearch().getApply$patcher().invoke(context, resources);
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
