import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.analysis.*;
import com.android.tools.smali.dexlib2.analysis.reflection.ReflectionClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.nio.file.*;
import java.util.*;

/** Applies the production gallery patch to original Sync 23033 DEX methods (optional APK input). */
public final class GalleryPatchTest {
    static int checks;
    static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    static MutableClassDef clazz(BytecodePatchContext context, String type) {
        return context.proxy(context.getClassDefs().get(type)).getMutableClass();
    }
    static Method method(MutableClassDef clazz, String name) {
        return clazz.getMethods().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
    static List<String> code(Method method) {
        List<String> result = new ArrayList<>();
        for (Instruction i : method.getImplementation().getInstructions()) {
            result.add(i.getOpcode() + ":" + (i instanceof ReferenceInstruction
                    ? ((ReferenceInstruction)i).getReference().toString() : ""));
        }
        return result;
    }
    static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getFixSyncGalleries().getApply$patcher().invoke(context, resources);
    }
    static BytecodePatchContext context(Path source, Path output) {
        var context = new BytecodePatchContext(source.toFile(), output.toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("sync-gallery-patch-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/gallery"), "assemble original parser and model");
            BytecodePatchContext context = context(args.length == 0 ? dex : Path.of(args[0]), scratch.resolve("patched"));
            MutableClassDef request = clazz(context, "Ly8/q;");
            Method parse = method(request, "parseNetworkResponse");
            List<String> constructor = code(method(request, "<init>"));
            List<String> before = code(parse);
            check(before.stream().anyMatch(s -> s.contains("Lwc/i;->e(")), "fixture contains original response-body logger");
            apply(context);
            List<String> patched = code(parse);
            check(patched.stream().anyMatch(s -> s.contains("GalleryCompat;->parseGallery(")), "production parser hook installed");
            check(patched.stream().anyMatch(s -> s.contains("GalleryCompat$Result;->response:")), "canonical cache response used");
            check(patched.stream().anyMatch(s -> s.contains("Ld9/a;->a(")), "original cache helper retained");
            check(patched.stream().noneMatch(s -> s.contains("Lwc/i;")), "raw body logger removed");
            check(code(method(request, "b")).stream().anyMatch(s -> s.contains("RequestCompat;->galleryUrl(")), "comment endpoint route installed");
            check(constructor.equals(code(method(request, "<init>"))), "constructor and cache setting preserved");
            check(parse.getImplementation().getTryBlocks().isEmpty(), "old strict parser catch blocks removed");
            // Verify register types at the cache/no-cache merge, not just numeric register bounds.
            ClassProvider reflection = type -> {
                try { return new ReflectionClassDef(Class.forName(type.substring(1, type.length() - 1).replace('/', '.'),
                        false, GalleryPatchTest.class.getClassLoader())); }
                catch (ClassNotFoundException missing) { return null; }
            };
            var superclass = new ImmutableClassDef("Lx8/a;", 1, "Lcom/android/volley/Request;", List.of(), null,
                    Set.of(), List.of(), List.of());
            var definitions = new ImmutableDexFile(Opcodes.getDefault(),
                    List.of(request, clazz(context, "Lo8/b;"), superclass));
            var analyzer = new MethodAnalyzer(new ClassPath(List.of(new DexClassProvider(definitions), reflection),
                    false, ClassPath.NOT_ART), parse, null, false);
            check(analyzer.getAnalysisException() == null, "parser register-type analysis: " + analyzer.getAnalysisException());
            for (AnalyzedInstruction instruction : analyzer.getAnalyzedInstructions()) {
                Instruction code = instruction.getInstruction();
                if (code instanceof ReferenceInstruction && ((ReferenceInstruction)code).getReference().toString()
                        .contains("Response;->success(")) {
                    String cacheType = instruction.getPreInstructionRegisterType(0).toString();
                    check(cacheType.contains("Cache$Entry"), "success cache argument retains its type: " + cacheType);
                }
            }
            Set<Integer> boundaries = new HashSet<>();
            int pc = 0;
            for (Instruction instruction : parse.getImplementation().getInstructions()) {
                boundaries.add(pc); pc += instruction.getCodeUnits();
            }
            pc = 0;
            for (Instruction instruction : parse.getImplementation().getInstructions()) {
                if (instruction instanceof OffsetInstruction)
                    check(boundaries.contains(pc + ((OffsetInstruction)instruction).getCodeOffset()), "branch lands on instruction");
                if (instruction instanceof FiveRegisterInstruction) {
                    FiveRegisterInstruction call = (FiveRegisterInstruction)instruction;
                    int[] registers = {call.getRegisterC(), call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG()};
                    for (int i = 0; i < call.getRegisterCount(); i++)
                        check(registers[i] < parse.getImplementation().getRegisterCount(), "invoke register in range");
                }
                pc += instruction.getCodeUnits();
            }
            DexPool.writeTo(scratch.resolve("roundtrip.dex").toString(), new ImmutableDexFile(Opcodes.getDefault(),
                    List.of(request, clazz(context, "Lo8/b;"))));
            check(DexFileFactory.loadDexFile(scratch.resolve("roundtrip.dex").toFile(), Opcodes.getDefault()).getClasses().size() == 2,
                    "patched DEX serializes and reloads");
            try { apply(context); throw new AssertionError("Duplicate patch accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            check(patched.equals(code(parse)), "duplicate leaves parser unchanged");
            // Validate the model before making either request edit.
            BytecodePatchContext malformed = context(dex, scratch.resolve("malformed"));
            MutableClassDef model = clazz(malformed, "Lo8/b;");
            model.getFields().removeIf(f -> f.getName().equals("b"));
            MutableClassDef bad = clazz(malformed, "Ly8/q;");
            List<String> oldUrl = code(method(bad, "b"));
            try { apply(malformed); throw new AssertionError("Unsupported model accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("Gallery model"), "unsupported model rejected"); }
            check(before.equals(code(method(bad, "parseNetworkResponse"))) && oldUrl.equals(code(method(bad, "b"))),
                    "layout rejection makes no partial edits");
            System.out.println("GalleryPatchTest: " + checks + " checks passed (production hooks, original DEX, serialization)");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
