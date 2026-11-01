import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;

/**
 * Applies the production "Sync: refresh RedGifs tokens" patch to layout fixtures (or, with an APK
 * argument, to a local prepared base) and checks the single replaced call, the inherited-layout
 * checks, duplicate rejection and that a rejected layout makes no edits.
 */
public final class RedgifsTokenPatchTest {
    private static final String HELPER = "La8/a;";
    private static final String INHERITED = "Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;";
    private static final String REFRESH = "Lapp/revanced/extension/synccompat/RedgifsTokenRefresh;";
    private static final String TOKENS = "Lapp/revanced/extension/shared/fixes/redgifs/RedgifsTokenManager;";
    private static final String INTERCEPTOR = "Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;";
    private static final String BUILDER = "Lokhttp3/OkHttpClient$Builder;";
    private static final String SIGNATURE = "install(" + BUILDER + ")Lokhttp3/OkHttpClient;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-redgifs-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/redgifs"), "assemble layout fixtures");

            // The replacement must call the extension's real entry point.
            java.lang.reflect.Method entry = Class.forName("app.revanced.extension.synccompat.RedgifsTokenRefresh")
                    .getMethod("install", okhttp3.OkHttpClient.Builder.class);
            check(Modifier.isStatic(entry.getModifiers()) && Modifier.isPublic(entry.getModifiers()), "extension install is public static");
            equal(okhttp3.OkHttpClient.class, entry.getReturnType(), "extension install returns the client");

            BytecodePatchContext context = context(dex, scratch, "patched", args);
            MutableMethod helper = installer(context);
            int site = indexOf(helper, INHERITED, "install");
            int builder = ((FiveRegisterInstruction) instructions(helper).get(site)).getRegisterC();
            List<String> before = keys(helper);
            List<String> branchesBefore = branchTargets(helper);

            apply(context);

            List<Instruction> code = instructions(helper);
            Instruction replaced = code.get(site);
            equal(Opcode.INVOKE_STATIC, replaced.getOpcode(), "replacement is a static call");
            equal(REFRESH + "->" + SIGNATURE, reference(replaced), "replacement calls the refresh installer");
            equal(1, ((FiveRegisterInstruction) replaced).getRegisterCount(), "one argument");
            equal(builder, ((FiveRegisterInstruction) replaced).getRegisterC(), "passes Sync's builder register");
            equal(Opcode.MOVE_RESULT_OBJECT, code.get(site + 1).getOpcode(), "client result kept");
            equal(Opcode.SPUT_OBJECT, code.get(site + 2).getOpcode(), "client still stored for Volley");
            List<String> after = keys(helper);
            equal(before.size(), after.size(), "no instructions added or removed");
            for (int i = 0; i < before.size(); i++) {
                if (i == site + 1) continue; // keys() index 0 is the register count
                equal(before.get(i), after.get(i), "instruction " + (i - 1) + " unchanged");
            }
            check(before.get(site + 1).replace(INHERITED, REFRESH).equals(after.get(site + 1)),
                    "only the call's owner changed: " + after.get(site + 1));
            equal(branchesBefore, branchTargets(helper), "branches still reach the same instructions");
            if (args.length == 0) {
                check(branchesBefore.contains(Integer.toString(site)), "fixture's DNS skip branch targets the install call");
            }
            check(count(context, REFRESH) == 1, "exactly one refresh call site");
            equal(0, count(context, INHERITED + "->install"), "inherited install now reached only through the refresh");

            // A second application is rejected without edits.
            List<String> patched = keys(helper);
            try { apply(context); throw new AssertionError("Duplicate application accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            equal(patched, keys(helper), "duplicate leaves OkHttpHelper");

            // Each unsupported inherited layout fails before OkHttpHelper is edited.
            rejects(dex, scratch, args, "no-token-map", c -> mutable(c, TOKENS).getFields().removeIf(f -> f.getName().equals("tokenMap")),
                    "token cache");
            rejects(dex, scratch, args, "no-cached-lookup", c -> {
                MutableMethod intercept = method(c, INTERCEPTOR, "intercept", 1);
                int lookup = indexOf(intercept, TOKENS, "refreshToken");
                intercept.getImplementation().replaceInstruction(lookup, new BuilderInstruction10x(Opcode.NOP));
            }, "RedGifs interceptor");
            rejects(dex, scratch, args, "no-call-timeout", c -> mutable(c, BUILDER).getMethods()
                    .removeIf(m -> m.getName().equals("callTimeout")), "callTimeout");
            rejects(dex, scratch, args, "no-info-lookup", c -> {
                for (MutableMethod m : mutable(c, "Lt8/a;").getMethods()) {
                    if (m.getImplementation() == null) continue;
                    List<Instruction> body = instructions(m);
                    for (int i = 0; i < body.size(); i++) {
                        Instruction insn = body.get(i);
                        if (insn instanceof ReferenceInstruction && ((ReferenceInstruction) insn).getReference().toString()
                                .equals("https://api.redgifs.com/info")) {
                            m.getImplementation().replaceInstruction(i, new BuilderInstruction10x(Opcode.NOP));
                        }
                    }
                }
            }, "GrabIpRequest");
            rejects(dex, scratch, args, "result-not-stored", c -> {
                MutableMethod k = installer(c);
                k.getImplementation().replaceInstruction(indexOf(k, INHERITED, "install") + 1, new BuilderInstruction10x(Opcode.NOP));
            }, "OkHttpHelper RedGifs install");

            // Stock Sync without the official Fix Redgifs API patch names that prerequisite (issue #70).
            rejects(dex, scratch, args, "no-official-redgifs", c -> c.getClassDefs().remove(c.getClassDefs().get(TOKENS)),
                    "missing the official ReVanced \"Fix Redgifs API\" patch");

            System.out.println("PASS: " + checks + " RedGifs token patch checks (production hook"
                    + (args.length != 0 ? ", supplied base APK" : ", layout fixtures") + ")");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private interface Mutation { void apply(BytecodePatchContext context) throws Exception; }

    private static void rejects(Path dex, Path scratch, String[] args, String name, Mutation mutation, String message) throws Exception {
        BytecodePatchContext context = context(dex, scratch, name, args);
        mutation.apply(context);
        MutableMethod helper = installer(context);
        List<String> before = keys(helper);
        try { apply(context); throw new AssertionError(name + ": unsupported layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains(message), name + ": rejected (" + expected.getMessage() + ")"); }
        equal(before, keys(helper), name + ": rejection makes no edits");
    }

    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getRefreshSyncRedgifsTokens().getApply$patcher().invoke(context, resources);
    }
    private static BytecodePatchContext context(Path dex, Path scratch, String name, String[] args) {
        // Optional APK argument checks the real base's classes and methods instead of fixtures.
        File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static MutableClassDef mutable(BytecodePatchContext context, String type) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing class " + type);
        return context.proxy(clazz).getMutableClass();
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name, int parameters) {
        for (MutableMethod method : mutable(context, type).getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().size() == parameters) return method;
        throw new AssertionError("Missing method " + type + "->" + name);
    }
    /** The OkHttpHelper method holding the inherited (or replaced) install call. */
    private static MutableMethod installer(BytecodePatchContext context) {
        MutableMethod found = null;
        for (MutableMethod method : mutable(context, HELPER).getMethods()) {
            if (method.getImplementation() == null) continue;
            for (Instruction i : method.getImplementation().getInstructions()) {
                String ref = reference(i);
                if (ref.equals(INHERITED + "->" + SIGNATURE) || ref.equals(REFRESH + "->" + SIGNATURE)) {
                    if (found != null && found != method) throw new AssertionError("install in several OkHttpHelper methods");
                    found = method;
                }
            }
        }
        if (found == null) throw new AssertionError("OkHttpHelper install call not found");
        return found;
    }
    private static int count(BytecodePatchContext context, String prefix) {
        int n = 0;
        for (Method method : mutable(context, HELPER).getMethods()) {
            if (method.getImplementation() == null) continue;
            for (Instruction i : method.getImplementation().getInstructions()) if (reference(i).startsWith(prefix)) n++;
        }
        return n;
    }
    private static int indexOf(Method method, String owner, String name) {
        List<Instruction> code = instructions(method);
        int found = -1;
        for (int i = 0; i < code.size(); i++) {
            if (reference(code.get(i)).startsWith(owner + "->" + name + "(")) {
                if (found >= 0) throw new AssertionError("Several " + owner + "->" + name + " calls");
                found = i;
            }
        }
        if (found < 0) throw new AssertionError("Missing " + owner + "->" + name + " call");
        return found;
    }
    private static String reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference().toString() : "";
    }
    private static List<Instruction> instructions(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    /** Instruction index each branch reaches, in branch order. */
    private static List<String> branchTargets(Method method) {
        List<Instruction> code = instructions(method);
        Map<Integer, Integer> addresses = new HashMap<>();
        int address = 0;
        for (int n = 0; n < code.size(); n++) { addresses.put(address, n); address += code.get(n).getCodeUnits(); }
        List<String> result = new ArrayList<>();
        address = 0;
        for (Instruction i : code) {
            if (i instanceof OffsetInstruction) result.add(String.valueOf(addresses.get(address + ((OffsetInstruction) i).getCodeOffset())));
            address += i.getCodeUnits();
        }
        return result;
    }
    /** Register count, then one key per instruction: opcode, reference, size and registers. */
    private static List<String> keys(Method method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        for (Instruction i : instructions(method)) {
            String key = i.getOpcode() + ":" + reference(i) + "|" + i.getCodeUnits();
            if (i instanceof OneRegisterInstruction) key += ":" + ((OneRegisterInstruction) i).getRegisterA();
            if (i instanceof TwoRegisterInstruction) key += ":" + ((TwoRegisterInstruction) i).getRegisterB();
            if (i instanceof FiveRegisterInstruction) {
                FiveRegisterInstruction f = (FiveRegisterInstruction) i;
                key += ":" + Arrays.toString(Arrays.copyOf(new int[]{f.getRegisterC(), f.getRegisterD(), f.getRegisterE(),
                        f.getRegisterF(), f.getRegisterG()}, f.getRegisterCount()));
            }
            if (i instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction r = (RegisterRangeInstruction) i;
                key += ":range=" + r.getStartRegister() + "+" + r.getRegisterCount();
            }
            if (i instanceof NarrowLiteralInstruction) key += ":literal=" + ((NarrowLiteralInstruction) i).getNarrowLiteral();
            if (i instanceof WideLiteralInstruction) key += ":wide=" + ((WideLiteralInstruction) i).getWideLiteral();
            result.add(key);
        }
        for (var block : method.getImplementation().getTryBlocks())
            result.add("try=" + block.getStartCodeAddress() + ":" + block.getCodeUnitCount());
        return result;
    }
}
