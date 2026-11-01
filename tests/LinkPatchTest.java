import app.revanced.extension.syncforreddit.FixSLinksPatch;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/** Applies the production link patch, then executes the patched entry points offline. */
public final class LinkPatchTest {
    private static final String LINKS = "Lapp/revanced/extension/synccompat/LinkNormalizer;";
    private static final String HELPER = "Ly7/b;";
    private static final String TYPES = "Ly7/c;";
    private static final String MAIN = "Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static Method method(BytecodePatchContext context, String type, String name, int parameters) {
        ClassDef clazz = context.getClassDefs().get(type);
        for (Method method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().size() == parameters) return method;
        throw new AssertionError("Missing method " + type + "->" + name);
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getNormalizeSyncRedditLinks().getApply$patcher().invoke(context, resources);
    }
    private static BytecodePatchContext context(Path dex, Path scratch, String name, String[] args) {
        // Optional APK argument checks the real base's complete methods instead of fixtures.
        File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static boolean truthy(Object value) {
        return value != null && !Boolean.FALSE.equals(value) && !Integer.valueOf(0).equals(value);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-link-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/links"), "assemble original fixtures");
            BytecodePatchContext context = context(dex, scratch, "patched", args);
            Method helper = method(context, HELPER, "c", 10);
            Method gallery = method(context, TYPES, "q", 1);
            Method start = method(context, MAIN, "onCreate", 1);
            List<String> helperBefore = keys(helper), galleryBefore = keys(gallery), startBefore = keys(start);
            int startCall = indexOf(start, TYPES, "B");
            int coldLink = ((FiveRegisterInstruction) instructions(start).get(startCall)).getRegisterC();

            // The original classifier only knows Sync's synthetic gallery form.
            String synthetic = "https://www.reddit.com/r/test/comments/abc123?is_gallery=true";
            String canonical = "https://www.reddit.com/gallery/abc123";
            check(truthy(execute(gallery, synthetic)), "original accepts synthetic gallery link");
            check(!truthy(execute(gallery, canonical)), "original gallery defect reproduced");

            apply(context);

            // LinkHelper: normalize p3 before the inherited share-link hook, change nothing else.
            List<Instruction> code = instructions(helper);
            int link = helper.getImplementation().getRegisterCount() - 10 + 3;
            equal(Opcode.INVOKE_STATIC, code.get(0).getOpcode(), "normalizer call first");
            equal(LINKS + "->normalize(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(0)), "normalizer reference");
            equal(link, ((FiveRegisterInstruction) code.get(0)).getRegisterC(), "normalizer reads the link parameter");
            equal(Opcode.MOVE_RESULT_OBJECT, code.get(1).getOpcode(), "normalized result kept");
            equal(link, ((OneRegisterInstruction) code.get(1)).getRegisterA(), "normalized link replaces the parameter");
            check(reference(code.get(2)).startsWith("Lapp/revanced/extension/syncforreddit/FixSLinksPatch;->patchResolveSLink"),
                    "inherited share-link hook still runs, now on the normalized link");
            equal(withoutTries(helperBefore), withoutTries(keys(helper, 0, 2)), "LinkHelper otherwise unchanged");
            equal(shiftedTries(helperBefore, 4), tries(keys(helper)), "LinkHelper try blocks follow their code");
            for (String[] row : new String[][] {
                    {"https://old.reddit.com/r/test/comments/abc123/a_title/?utm_source=share", "https://www.reddit.com/r/test/comments/abc123/"},
                    {"http://reddit.com/r/test/s/AbCd12/", "https://www.reddit.com/r/test/s/AbCd12"},
                    {"Shared text https://redd.it/abc123", "https://www.reddit.com/comments/abc123/"},
                    {"https://example.com/page", "https://example.com/page"}}) {
                FixSLinksPatch.received = null;
                Object result = execute(helper, null, null, null, row[0], null, null, null, false, false, false);
                check(truthy(result), "hook result returned");
                equal(row[1], FixSLinksPatch.received, "share-link hook receives normalized link for " + row[0]);
            }

            // Gallery classifier: canonical gallery accepted, original behavior otherwise.
            check(truthy(execute(gallery, canonical)), "canonical gallery link accepted");
            check(truthy(execute(gallery, synthetic)), "synthetic gallery link still accepted");
            for (String other : new String[] {null, "", "https://www.reddit.com/gallery/abc123/", "https://www.reddit.com/r/test/comments/abc123/",
                    "https://evil.invalid/gallery/abc123"}) {
                check(!truthy(execute(gallery, other)), "not a gallery: " + other);
            }
            equal(galleryBefore, keys(gallery, 0, 5), "original gallery classifier kept after the new check");
            equal("IF_EQZ:|2:0:target=5", keys(gallery).get(3), "new check falls through to the original first instruction");

            // Cold start: the link is normalized before Sync's subreddit check and LinkHelper handoff.
            List<Instruction> startCode = instructions(start);
            equal(LINKS + "->normalize(Ljava/lang/String;)Ljava/lang/String;", reference(startCode.get(startCall)), "cold-start normalizer");
            equal(coldLink, ((FiveRegisterInstruction) startCode.get(startCall)).getRegisterC(), "cold-start link register read");
            equal(coldLink, ((OneRegisterInstruction) startCode.get(startCall + 1)).getRegisterA(), "cold-start link register replaced");
            equal(TYPES + "->B(Ljava/lang/String;)Z", reference(startCode.get(startCall + 2)), "subreddit check follows");
            equal(withoutTries(startBefore), withoutTries(keys(start, startCall, 2)), "MainActivity otherwise unchanged");

            // A second application is rejected without edits.
            List<String> helperPatched = keys(helper), galleryPatched = keys(gallery), startPatched = keys(start);
            try { apply(context); throw new AssertionError("Duplicate application accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            equal(helperPatched, keys(helper), "duplicate leaves LinkHelper");
            equal(galleryPatched, keys(gallery), "duplicate leaves gallery classifier");
            equal(startPatched, keys(start), "duplicate leaves MainActivity");

            // An unsupported cold-start layout fails before any method is edited.
            BytecodePatchContext malformed = context(dex, scratch, "malformed", args);
            MutableMethod badStart = (MutableMethod) method(malformed, MAIN, "onCreate", 1);
            int badCall = indexOf(badStart, TYPES, "B");
            FiveRegisterInstruction call = (FiveRegisterInstruction) instructions(badStart).get(badCall);
            badStart.getImplementation().replaceInstruction(badCall, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1,
                    call.getRegisterC(), 0, 0, 0, 0, new ImmutableMethodReference(TYPES, "C", List.of("Ljava/lang/String;"), "Z")));
            List<Method> untouched = List.of(method(malformed, HELPER, "c", 10), method(malformed, TYPES, "q", 1), badStart);
            List<List<String>> before = new ArrayList<>();
            for (Method target : untouched) before.add(keys(target));
            try { apply(malformed); throw new AssertionError("Unsupported cold-start layout accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("cold-start"), "unsupported layout rejected"); }
            for (int i = 0; i < untouched.size(); i++) equal(before.get(i), keys(untouched.get(i)), "validation failure makes no edits");
            System.out.println("PASS: " + checks + " link patch checks (production hooks + executed DEX methods"
                    + (args.length != 0 ? ", supplied base APK" : ", fixtures") + ")");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static int indexOf(Method method, String owner, String name) {
        List<Instruction> code = instructions(method);
        int found = -1;
        for (int i = 0; i < code.size(); i++) {
            String ref = reference(code.get(i));
            if (ref.startsWith(owner + "->" + name + "(")) {
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
    private static List<String> withoutTries(List<String> keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) if (!key.startsWith("try=")) result.add(key);
        return result;
    }
    private static List<String> tries(List<String> keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) if (key.startsWith("try=")) result.add(key);
        return result;
    }
    /** Try blocks and handlers move by the code units inserted at the start. */
    private static List<String> shiftedTries(List<String> keys, int units) {
        List<String> result = new ArrayList<>();
        for (String key : tries(keys)) {
            String[] parts = key.substring(4).split(":", 3);
            String handlers = parts[2];
            StringBuilder shifted = new StringBuilder();
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(":(\\d+)(?=[,\\]])").matcher(handlers);
            while (match.find()) match.appendReplacement(shifted, ":" + (Integer.parseInt(match.group(1)) + units));
            match.appendTail(shifted);
            result.add("try=" + (Integer.parseInt(parts[0]) + units) + ":" + parts[1] + ":" + shifted);
        }
        return result;
    }
    private static List<String> keys(Method method) {
        return keys(method, 0, 0);
    }
    /** Instruction keys without the {@code count} instructions inserted at {@code from}; branch targets are instructions. */
    private static List<String> keys(Method method, int from, int count) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        List<Instruction> code = instructions(method);
        Map<Integer, Integer> addresses = new HashMap<>();
        int address = 0;
        for (int n = 0; n < code.size(); n++) { addresses.put(address, n); address += code.get(n).getCodeUnits(); }
        address = 0;
        for (int n = 0; n < code.size(); n++) {
            Instruction i = code.get(n);
            int at = address;
            address += i.getCodeUnits();
            if (n >= from && n < from + count) continue;
            String key = i.getOpcode() + ":" + reference(i) + "|" + i.getCodeUnits();
            if (i instanceof OneRegisterInstruction) key += ":" + ((OneRegisterInstruction) i).getRegisterA();
            if (i instanceof TwoRegisterInstruction) key += ":" + ((TwoRegisterInstruction) i).getRegisterB();
            if (i instanceof FiveRegisterInstruction || i instanceof RegisterRangeInstruction) key += ":" + Arrays.toString(registers(i));
            if (i instanceof OffsetInstruction) {
                int target = addresses.get(at + ((OffsetInstruction) i).getCodeOffset());
                key += ":target=" + (target >= from && target < from + count ? "inserted"
                        : target >= from + count ? target - count : target);
            }
            if (i instanceof NarrowLiteralInstruction) key += ":literal=" + ((NarrowLiteralInstruction) i).getNarrowLiteral();
            result.add(key);
        }
        for (var block : method.getImplementation().getTryBlocks())
            result.add("try=" + block.getStartCodeAddress() + ":" + block.getCodeUnitCount() + ":" +
                    block.getExceptionHandlers().stream().map(h -> h.getExceptionType() + ":" + h.getHandlerCodeAddress()).toList());
        return result;
    }
    private static int[] registers(Instruction instruction) {
        if (instruction instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction i = (FiveRegisterInstruction) instruction;
            return Arrays.copyOf(new int[] {i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG()}, i.getRegisterCount());
        }
        RegisterRangeInstruction i = (RegisterRangeInstruction) instruction;
        return java.util.stream.IntStream.range(i.getStartRegister(), i.getStartRegister() + i.getRegisterCount()).toArray();
    }

    // Small strict runner for the straight-line static methods under test. Calls use the
    // production normalizer and small test stand-ins; unknown opcodes fail the suite.
    private static Object execute(Method method, Object... arguments) throws Throwable {
        Object[] r = new Object[method.getImplementation().getRegisterCount()];
        System.arraycopy(arguments, 0, r, r.length - arguments.length, arguments.length);
        List<Instruction> code = instructions(method);
        Map<Integer, Integer> addresses = new HashMap<>();
        int address = 0;
        for (int i = 0; i < code.size(); i++) { addresses.put(address, i); address += code.get(i).getCodeUnits(); }
        Object result = null;
        int pc = 0, steps = 0;
        while (pc < code.size()) {
            if (++steps > 1000) throw new AssertionError("Unbounded fixture execution");
            Instruction i = code.get(pc);
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case CONST_STRING: case CONST_STRING_JUMBO:
                    r[a] = ((StringReference) ((ReferenceInstruction) i).getReference()).getString(); break;
                case CONST_4: case CONST_16: case CONST:
                    r[a] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a] = result; break;
                case INVOKE_STATIC: case INVOKE_STATIC_RANGE: {
                    MethodReference call = (MethodReference) ((ReferenceInstruction) i).getReference();
                    int[] regs = registers(i);
                    Object[] values = new Object[regs.length];
                    for (int n = 0; n < values.length; n++) values[n] = r[regs[n]];
                    Class<?>[] parameters = new Class<?>[call.getParameterTypes().size()];
                    for (int n = 0; n < parameters.length; n++) parameters[n] = type(call.getParameterTypes().get(n).toString());
                    try { result = type(call.getDefiningClass()).getMethod(call.getName(), parameters).invoke(null, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    break;
                }
                case IF_EQZ: case IF_NEZ: {
                    boolean zero = !truthy(r[a]);
                    if (zero == (i.getOpcode() == Opcode.IF_EQZ)) { pc = branch(code, addresses, pc, i); continue; }
                    break;
                }
                case RETURN: case RETURN_OBJECT: return r[a];
                default: throw new AssertionError("Unsupported fixture opcode " + i.getOpcode());
            }
            pc++;
        }
        throw new AssertionError("Missing return");
    }
    private static int branch(List<Instruction> code, Map<Integer, Integer> addresses, int pc, Instruction i) {
        int address = 0;
        for (int n = 0; n < pc; n++) address += code.get(n).getCodeUnits();
        return addresses.get(address + ((OffsetInstruction) i).getCodeOffset());
    }
    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        if (descriptor.equals("Z")) return boolean.class;
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
    }
}
