import app.revanced.extension.synccompat.RedgifsLinkCompat;
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
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: fix RedGifs link IDs" to Sync's RedGifs ID reader (LinkHandler.d, y7.a.d) and
 * executes that method before and after the patch: on a hand-written model of it, or,
 * given a local stock Sync 23033 APK, on the real method. Also checks duplicate and
 * unsupported-layout rejection.
 */
public final class RedgifsLinkPatchTest {
    private static final String HANDLER = "Ly7/a;";
    private static final String HOOK = "Lapp/revanced/extension/synccompat/RedgifsLinkCompat;->normalize(Ljava/lang/String;)Ljava/lang/String;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-redgifs-link-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/redgifslinks"), "assemble LinkHandler model");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            // Link -> the ID Sync should read. Links Sync already read correctly must not change.
            String[][] fixed = {
                {"https://www.redgifs.com/watch/joyfulwhitebird/?92", "joyfulwhitebird"},
                {"https://www.redgifs.com/watch/joyfulwhitebird/#comments", "joyfulwhitebird"},
                {"https://redgifs.com/watch/joyfulwhitebird//", "joyfulwhitebird"},
                {"https://www.redgifs.com/watch/joyfulwhitebird/?utm_source=x#y", "joyfulwhitebird"},
                {"https://www.RedGifs.com/watch/JoyfulWhiteBird/?92", "JoyfulWhiteBird"},
            };
            String[][] unchanged = {
                {"https://www.redgifs.com/watch/joyfulwhitebird", "joyfulwhitebird"},
                {"https://www.redgifs.com/watch/joyfulwhitebird/", "joyfulwhitebird"},
                {"https://www.redgifs.com/watch/joyfulwhitebird?92", "joyfulwhitebird"},
                {"https://www.redgifs.com/watch/joyfulwhitebird#x", "joyfulwhitebird"},
                {"https://www.redgifs.com/ifr/joyfulwhitebird", "joyfulwhitebird"},
                {"https://thumbs2.redgifs.com/JoyfulWhiteBird-mobile.mp4", "JoyfulWhiteBird"},
                {"https://thumbs2.redgifs.com/JoyfulWhiteBird-mobile.mp4?expires=1", "JoyfulWhiteBird"},
            };

            BytecodePatchContext context = context(source, scratch, "patched");
            MutableMethod reader = reader(context);
            Instruction first = code(reader).get(0);
            // The defect: Sync reads an empty ID from a slash before the query or fragment.
            for (String[] row : fixed) equal("", execute(reader, row[0]), "stock Sync reads no ID from " + row[0]);
            for (String[] row : unchanged) equal(row[1], execute(reader, row[0]), "stock Sync reads " + row[0]);

            apply(context);
            List<Instruction> patched = code(reader);
            equal(HOOK, reference(patched.get(0)), "link normalized first");
            int link = reader.getImplementation().getRegisterCount() - 1;
            equal(link, ((FiveRegisterInstruction) patched.get(0)).getRegisterC(), "hook reads the link (p0)");
            equal(Opcode.MOVE_RESULT_OBJECT, patched.get(1).getOpcode(), "normalized link kept");
            equal(link, ((OneRegisterInstruction) patched.get(1)).getRegisterA(), "normalized link replaces p0");
            check(patched.get(2) == first, "Sync's own reader follows unchanged");
            for (String[] row : fixed) equal(row[1], execute(reader, row[0]), "patched Sync reads the ID from " + row[0]);
            for (String[] row : unchanged) equal(row[1], execute(reader, row[0]), "patched Sync still reads " + row[0]);

            // Only RedGifs links change; empty or scheme-only remains are left to Sync.
            for (String other : new String[] {null, "", "https://gfycat.com/foo/?x", "https://example.com/a/?b",
                    "?92", "https://redgifs.com", "https://redgifs.com/"}) {
                String expected = "https://redgifs.com/".equals(other) ? "https://redgifs.com" : other;
                equal(expected, RedgifsLinkCompat.normalize(other), "normalize " + other);
            }
            equal("https://redgifs.com/watch/a", RedgifsLinkCompat.normalize("https://redgifs.com/watch/a/?x/y#z"), "query ends the path");

            // A second application is rejected without edits.
            List<String> applied = keys(reader);
            try { apply(context); throw new AssertionError("Duplicate application accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            equal(applied, keys(reader), "duplicate leaves the reader unchanged");

            // An unknown reader layout fails before any edit.
            BytecodePatchContext other = context(source, scratch, "unsupported");
            MutableMethod unknown = reader(other);
            int marker = -1;
            List<Instruction> body = code(unknown);
            for (int i = 0; i < body.size(); i++) if ("-max-1mb-poster".equals(string(body.get(i)))) marker = i;
            check(marker >= 0, "layout marker found");
            unknown.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("-other")));
            List<String> before = keys(unknown);
            try { apply(other); throw new AssertionError("Unknown reader accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("RedGifs ID reader"), "unknown layout rejected"); }
            equal(before, keys(unknown), "rejection makes no edits");

            System.out.println("PASS: " + checks + " RedGifs link ID checks (executed "
                    + (args.length != 0 ? "real LinkHandler.d from the supplied stock APK)" : "hand-written LinkHandler.d model)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getFixSyncRedgifsLinkIds().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod reader(BytecodePatchContext context) {
        ClassDef clazz = context.getClassDefs().get(HANDLER);
        if (clazz == null) throw new AssertionError("Missing " + HANDLER);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals("d") && method.getParameterTypes().equals(List.of("Ljava/lang/String;"))
                    && method.getReturnType().equals("Ljava/lang/String;")) return method;
        throw new AssertionError("Missing LinkHandler.d");
    }
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

    /**
     * Strict runner for LinkHandler.d: String calls run on the JVM, the RedGifs hook runs the
     * production extension, and an exception in the method's try block returns null as Sync's
     * handler does. Any other opcode or call fails the suite.
     */
    private static Object execute(MutableMethod method, String link) throws Throwable {
        List<Instruction> code = code(method);
        Object[] r = new Object[method.getImplementation().getRegisterCount()];
        r[r.length - 1] = link;
        Object result = null;
        int pc = 0, steps = 0;
        while (true) {
            if (++steps > 2000) throw new AssertionError("Unbounded execution");
            Instruction i = code.get(pc);
            int next = pc + 1;
            switch (i.getOpcode()) {
                case CONST_STRING: case CONST_STRING_JUMBO: r[((OneRegisterInstruction) i).getRegisterA()] = string(i); break;
                case CONST_4: case CONST_16: r[((OneRegisterInstruction) i).getRegisterA()] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[((OneRegisterInstruction) i).getRegisterA()] = result; break;
                case ADD_INT_LIT8: {
                    TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                    r[t.getRegisterA()] = (Integer) r[t.getRegisterB()] + ((NarrowLiteralInstruction) i).getNarrowLiteral();
                    break;
                }
                case AGET_OBJECT: {
                    ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                    r[t.getRegisterA()] = ((Object[]) r[t.getRegisterB()])[(Integer) r[t.getRegisterC()]];
                    break;
                }
                case IF_EQZ: case IF_NEZ: {
                    Object value = r[((OneRegisterInstruction) i).getRegisterA()];
                    boolean zero = value == null || Boolean.FALSE.equals(value) || Integer.valueOf(0).equals(value);
                    if (zero == (i.getOpcode() == Opcode.IF_EQZ)) next = target(code, i);
                    break;
                }
                case INVOKE_STATIC: case INVOKE_VIRTUAL: {
                    MethodReference call = (MethodReference) ((ReferenceInstruction) i).getReference();
                    FiveRegisterInstruction f = (FiveRegisterInstruction) i;
                    int[] regs = Arrays.copyOf(new int[] {f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG()}, f.getRegisterCount());
                    boolean isStatic = i.getOpcode() == Opcode.INVOKE_STATIC;
                    String owner = call.getDefiningClass();
                    if (!(owner.equals("Ljava/lang/String;") || reference(i).equals(HOOK)))
                        throw new AssertionError("Unexpected call in the normal path: " + reference(i));
                    Class<?>[] types = new Class<?>[call.getParameterTypes().size()];
                    for (int n = 0; n < types.length; n++) types[n] = type(call.getParameterTypes().get(n).toString());
                    Object[] values = new Object[types.length];
                    for (int n = 0; n < types.length; n++) values[n] = r[regs[n + (isStatic ? 0 : 1)]];
                    try {
                        result = type(owner).getMethod(call.getName(), types).invoke(isStatic ? null : r[regs[0]], values);
                    } catch (InvocationTargetException thrown) {
                        // Sync's handler logs the URL and returns null.
                        check(method.getImplementation().getTryBlocks().size() == 1, "reader has its one try block");
                        return null;
                    }
                    break;
                }
                case RETURN_OBJECT: return r[((OneRegisterInstruction) i).getRegisterA()];
                default: throw new AssertionError("Unsupported opcode " + i.getOpcode());
            }
            pc = next;
        }
    }
    private static int target(List<Instruction> code, Instruction branch) {
        Instruction target = ((BuilderOffsetInstruction) branch).getTarget().getLocation().getInstruction();
        for (int n = 0; n < code.size(); n++) if (code.get(n) == target) return n;
        throw new AssertionError("Branch target not found");
    }
    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "I": return int.class;
            case "Z": return boolean.class;
            default: return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
    }
}
