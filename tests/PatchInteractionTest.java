import app.revanced.local.synccompat.OfficialSyncPatchesKt;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;

/**
 * Patches that edit the same Sync method must not depend on the order they run in. ReVanced CLI
 * applies the bundle's patches sorted by name, so a rename alone could reorder them.
 *
 * Without arguments: applies "normalize Reddit links" and the comment-video link hook, the two
 * patches that both rewrite the link at the start of LinkHelper (y7.b.c), to the link fixtures in
 * both orders and checks each succeeds with the same result.
 *
 * With a local stock Sync 23033 APK argument: applies every named patch in this bundle, including
 * the bundled official ones, to the real APK in name order (the CLI's order) and in reverse, each
 * after its dependencies as ReVanced Patcher does, so every pair of patches runs both ways round.
 * Each patch must succeed in both, and every class they change must come out the same. Nothing is
 * saved or signed.
 */
public final class PatchInteractionTest {
    private static final String HELPER = "Ly7/b;";
    private static final String VIDEO = "Lapp/revanced/extension/synccompat/CommentVideo;";
    private static final String LINKS = "Lapp/revanced/extension/synccompat/LinkNormalizer;";
    private static final String SLINK = "Lapp/revanced/extension/syncforreddit/FixSLinksPatch;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-interaction-test");
        try {
            if (args.length == 0) linkHooks(scratch);
            else wholeBundle(Path.of(args[0]).toFile(), scratch);
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Fixture mode: the two LinkHelper entry hooks ----

    private static void linkHooks(Path scratch) throws Throwable {
        Path dex = scratch.resolve("classes.dex");
        SmaliOptions options = new SmaliOptions();
        options.apiLevel = 23;
        options.outputDexFile = dex.toString();
        check(Smali.assemble(options, "tests/fixtures/links"), "assemble link fixtures");

        Map<String, List<String>> results = new LinkedHashMap<>();
        for (boolean normalizeFirst : new boolean[] {true, false}) {
            String order = normalizeFirst ? "normalize then video" : "video then normalize";
            BytecodePatchContext context = new BytecodePatchContext(dex.toFile(), scratch.resolve(order.replace(' ', '-')).toFile());
            context.getClassDefs().initializeCache$patcher();
            MutableClassDef helper = context.proxy(context.getClassDefs().get(HELPER)).getMutableClass();
            Patch normalize = SyncCompatPatchesKt.getNormalizeSyncRedditLinks();
            if (normalizeFirst) {
                apply(context, normalize);
                SyncCompatPatchesKt.installSyncCommentVideoLinkHook(helper);
            } else {
                SyncCompatPatchesKt.installSyncCommentVideoLinkHook(helper);
                apply(context, normalize);
            }
            Method router = router(helper);
            List<Instruction> code = new ArrayList<>();
            router.getImplementation().getInstructions().forEach(code::add);
            int link = router.getImplementation().getRegisterCount() - 10 + 3;
            // Comment-video mapping first, then normalization, then the inherited share-link hook.
            equal(VIDEO + "->tapUrl(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(0)), order + ": video link mapping first");
            equal(LINKS + "->normalize(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(2)), order + ": normalization second");
            check(reference(code.get(4)).startsWith(SLINK + "->patchResolveSLink("), order + ": share-link hook follows both");
            for (int i : new int[] {0, 2, 4})
                equal(link, ((FiveRegisterInstruction) code.get(i)).getRegisterC(), order + ": hook " + i + " reads the link");
            for (int i : new int[] {1, 3})
                equal(link, ((OneRegisterInstruction) code.get(i)).getRegisterA(), order + ": rewrite " + i + " replaces the link");
            results.put(order, keys(router));

            // Each hook still refuses a second installation in this combined state.
            try { SyncCompatPatchesKt.installSyncCommentVideoLinkHook(helper); throw new AssertionError("duplicate video hook accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), order + ": duplicate video hook rejected"); }
            try { apply(context, normalize); throw new AssertionError("duplicate normalizer accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), order + ": duplicate normalizer rejected"); }
        }
        Iterator<List<String>> both = results.values().iterator();
        equal(both.next(), both.next(), "LinkHelper is identical in either order");

        // Normalization skips only this bundle's own link rewrites, not an unknown call.
        BytecodePatchContext foreign = new BytecodePatchContext(dex.toFile(), scratch.resolve("foreign").toFile());
        foreign.getClassDefs().initializeCache$patcher();
        MutableClassDef helper = foreign.proxy(foreign.getClassDefs().get(HELPER)).getMutableClass();
        app.revanced.patcher.extensions.InstructionExtensions.INSTANCE.addInstructions(
                (app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod) router(helper), 0,
                "invoke-static {p3}, Lcom/example/Other;->rewrite(Ljava/lang/String;)Ljava/lang/String;\nmove-result-object p3");
        List<String> before = keys(router(helper));
        try { apply(foreign, SyncCompatPatchesKt.getNormalizeSyncRedditLinks()); throw new AssertionError("unknown entry rewrite accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("LinkHelper"), "unknown entry rewrite rejected"); }
        equal(before, keys(router(helper)), "rejection makes no LinkHelper edit");

        System.out.println("PASS: " + checks + " patch interaction checks (LinkHelper hooks in both orders, fixtures)");
    }

    private static Method router(ClassDef helper) {
        for (Method method : helper.getMethods())
            if (method.getName().equals("c") && method.getParameterTypes().size() == 10) return method;
        throw new AssertionError("Missing LinkHelper router");
    }

    // ---- Base APK mode: every named patch, both orders ----

    private static void wholeBundle(File apk, Path scratch) throws Throwable {
        List<Patch> patches = bundlePatches();
        check(patches.size() >= 2, "bundle patches found");
        List<Patch> byName = new ArrayList<>(patches);
        byName.sort(Comparator.comparing(Patch::getName));
        List<Patch> reversed = new ArrayList<>(byName);
        Collections.reverse(reversed);

        Map<String, Map<String, List<String>>> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, List<Patch>> run : Map.of("name order", byName, "reverse name order", reversed)
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            BytecodePatchContext context = new BytecodePatchContext(apk, scratch.resolve(run.getKey().replace(' ', '-')).toFile());
            context.getClassDefs().initializeCache$patcher();
            List<String> failures = new ArrayList<>();
            Set<Patch> applied = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Patch patch : run.getValue()) {
                try { applyWithDependencies(context, patch, applied); }
                catch (Throwable failure) { failures.add(patch.getName() + ": " + failure.getMessage()); }
            }
            check(failures.isEmpty(), run.getKey() + ": every patch succeeds, failed " + failures);
            Map<String, List<String>> changed = new TreeMap<>();
            for (ClassDef clazz : context.getClassDefs())
                if (clazz instanceof MutableClassDef) changed.put(clazz.getType(), keys(clazz));
            check(!changed.isEmpty(), run.getKey() + ": patches changed classes");
            outcomes.put(run.getKey(), changed);
            System.out.println(run.getKey() + ": " + run.getValue().size() + " patches succeeded, "
                    + changed.size() + " classes changed");
        }
        Iterator<Map<String, List<String>>> both = outcomes.values().iterator();
        Map<String, List<String>> first = both.next(), second = both.next();
        equal(first.keySet(), second.keySet(), "same classes changed in either order");
        List<String> different = new ArrayList<>();
        for (String type : first.keySet()) {
            checks++;
            if (!first.get(type).equals(second.get(type))) different.add(type + firstDifference(first.get(type), second.get(type)));
        }
        check(different.isEmpty(), "classes differ by patch order: " + different);
        System.out.println("PASS: " + checks + " patch interaction checks (" + patches.size()
                + " patches in both name orders, supplied stock APK)");
    }

    /** Every named patch the Kotlin files publish, as the RVP does; Spoof client gets a test client ID. */
    private static List<Patch> bundlePatches() throws ReflectiveOperationException {
        List<Patch> result = new ArrayList<>();
        for (Class<?> file : List.of(SyncCompatPatchesKt.class, OfficialSyncPatchesKt.class)) {
            for (java.lang.reflect.Method getter : file.getMethods()) {
                if (Modifier.isStatic(getter.getModifiers()) && getter.getParameterCount() == 0
                        && Patch.class.isAssignableFrom(getter.getReturnType())) {
                    Patch patch = (Patch) getter.invoke(null);
                    if (patch.getName() != null) result.add(patch);
                }
            }
        }
        for (Patch patch : result)
            if ("Spoof client".equals(patch.getName())) {
                patch.getOptions().set("OAuth client ID", "InteractionTestClient");
                patch.getOptions().set("User-Agent", "InteractionTest/1.0"); // the default is random per run
            }
        return result;
    }

    /** Dependencies first and once, as ReVanced Patcher does. */
    private static void applyWithDependencies(BytecodePatchContext context, Patch patch, Set<Patch> applied) throws PatchException {
        if (!applied.add(patch)) return;
        for (Patch dependency : patch.getDependencies()) applyWithDependencies(context, dependency, applied);
        apply(context, patch);
    }

    private static String firstDifference(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++)
            if (!a.get(i).equals(b.get(i))) return " at " + a.get(i).split("\\|", 2)[0];
        return " (lengths " + a.size() + " and " + b.size() + ")";
    }

    // ---- Shared helpers ----

    private static void apply(BytecodePatchContext context, Patch patch) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        patch.getApply$patcher().invoke(context, resources);
    }

    private static String reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference().toString() : "";
    }

    /** A class's fields and methods with instruction keys; branch targets are instruction indices. */
    private static List<String> keys(ClassDef clazz) {
        List<String> result = new ArrayList<>();
        for (Field field : clazz.getFields()) result.add("field " + field.getName() + ":" + field.getType());
        List<Method> methods = new ArrayList<>();
        clazz.getMethods().forEach(methods::add);
        methods.sort(Comparator.comparing(m -> m.getName() + m.getParameterTypes() + m.getReturnType()));
        for (Method method : methods) {
            String name = method.getName() + method.getParameterTypes() + method.getReturnType();
            if (method.getImplementation() == null) { result.add(name + "|abstract"); continue; }
            for (String key : keys(method)) result.add(name + "|" + key);
        }
        return result;
    }

    private static List<String> keys(Method method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        List<Instruction> code = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(code::add);
        Map<Integer, Integer> addresses = new HashMap<>();
        int address = 0;
        for (int n = 0; n < code.size(); n++) { addresses.put(address, n); address += code.get(n).getCodeUnits(); }
        address = 0;
        for (Instruction i : code) {
            int at = address;
            address += i.getCodeUnits();
            String key = i.getOpcode() + ":" + reference(i);
            if (i instanceof OneRegisterInstruction) key += ":" + ((OneRegisterInstruction) i).getRegisterA();
            if (i instanceof TwoRegisterInstruction) key += ":" + ((TwoRegisterInstruction) i).getRegisterB();
            if (i instanceof ThreeRegisterInstruction) key += ":" + ((ThreeRegisterInstruction) i).getRegisterC();
            if (i instanceof FiveRegisterInstruction) {
                FiveRegisterInstruction f = (FiveRegisterInstruction) i;
                key += ":" + Arrays.toString(Arrays.copyOf(new int[] {f.getRegisterC(), f.getRegisterD(), f.getRegisterE(),
                        f.getRegisterF(), f.getRegisterG()}, f.getRegisterCount()));
            }
            if (i instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction r = (RegisterRangeInstruction) i;
                key += ":range=" + r.getStartRegister() + "+" + r.getRegisterCount();
            }
            if (i instanceof OffsetInstruction) key += ":target=" + addresses.get(at + ((OffsetInstruction) i).getCodeOffset());
            if (i instanceof WideLiteralInstruction) key += ":literal=" + ((WideLiteralInstruction) i).getWideLiteral();
            result.add(key);
        }
        for (var block : method.getImplementation().getTryBlocks())
            result.add("try=" + addresses.get(block.getStartCodeAddress()) + "+" + block.getCodeUnitCount() + ":" +
                    block.getExceptionHandlers().stream().map(h -> h.getExceptionType() + ":" + addresses.get(h.getHandlerCodeAddress())).toList());
        return result;
    }
}
