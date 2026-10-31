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
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/** Applies the production patch, then executes the resulting method instructions offline. */
public final class MarkdownPatchTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static Method method(BytecodePatchContext context, String type, String name) {
        ClassDef clazz = context.getClassDefs().get(type);
        for (Method method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name)) return method;
        throw new AssertionError("Missing fixture method " + type + "->" + name);
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getPreserveSyncMarkdownLiterals().getApply$patcher().invoke(context, resources);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-markdown-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/markdown"), "assemble original fixtures");
            BytecodePatchContext context = new BytecodePatchContext(dex.toFile(), scratch.resolve("patched").toFile());
            // Optional APK argument uses the real base's complete e/g/a methods instead of fixtures.
            if (args.length != 0)
                context = new BytecodePatchContext(Path.of(args[0]).toFile(), scratch.resolve("apk").toFile());
            context.getClassDefs().initializeCache$patcher();
            Method code = method(context, "Ld7/f;", "e");
            Method spoilers = method(context, "Ld7/f;", "g");
            Method posts = method(context, "Lc9/c;", "a");
            List<Method> targets = List.of(code, spoilers, posts);
            List<List<String>> before = new ArrayList<>();
            for (Method target : targets) before.add(keys(target));
            try {
                execute(code, "```echo $HOME```");
                throw new AssertionError("Original fenced-code defect was not reproduced");
            } catch (IllegalArgumentException expected) { checks++; }
            equal("    C:tempfile    \n-", execute(code, "```C:\\temp\\file```"), "original slash corruption reproduced");
            apply(context);
            int edits = 0;
            for (int t = 0; t < targets.size(); t++) {
                List<String> after = keys(targets.get(t));
                equal(before.get(t).size(), after.size(), "no instruction insertion/removal");
                for (int i = 0; i < after.size(); i++) {
                    if (before.get(t).get(i).equals(after.get(i))) continue;
                    edits++;
                    Instruction old = instructions(targets.get(t)).get(i - 1);
                    check(old.getOpcode() == Opcode.INVOKE_STATIC, "replacement is static");
                    check(after.get(i).contains("MarkdownCompat;->"), "only literal calls replaced");
                    // Everything except opcode/reference (argument registers, instruction width) stays the same.
                    equal(before.get(t).get(i).substring(before.get(t).get(i).indexOf('|')),
                          after.get(i).substring(after.get(i).indexOf('|')), "argument registers preserved");
                }
            }
            equal(6, edits, "all six intended sites patched");
            for (String value : new String[] {"echo $HOME", "price $12", "$", "\\", "C:\\temp\\file",
                    "$1 ${name} \\$12", "café 漢字 😀 $HOME \\path", "line1\nline2 $12\\"}) {
                equal("before     " + value.replace("\n", "\n    ") + "    \n- after",
                        execute(code, "before ```" + value + "``` after"), "fenced content survives");
            }
            equal("    one    \n- and     $12\\two    \n- tail",
                    execute(code, "```one``` and ```$12\\two``` tail"), "multiple blocks and appendTail");
            for (String ordinary : new String[] {"plain **bold** [link](https://example.invalid)", "`inline $HOME \\x`", "```unfinished $HOME"})
                equal(ordinary, execute(code, ordinary), "unmatched formatting unchanged");
            equal("[██████](secret)", execute(spoilers, "[](/s \"secret\")"), "title-only spoiler branch");
            equal("[█████](label)", execute(spoilers, "[label](/s)"), "label-only spoiler branch");
            equal("[label: ██████](secret)", execute(spoilers, "[label](/s \"secret\")"), "label-and-title branch");
            equal("[price $12 \\path: ██████](secret)", execute(spoilers, "[price $12 \\path](/s \"secret\")"), "literal spoiler label");
            equal("[echo $HOME: ██████](secret)", execute(spoilers, "[echo $HOME](/s \"secret\")"), "spoiler dollar name");
            equal("[façade: ██████](secret)", execute(spoilers, "[façade](/s \"secret\")"), "existing Unicode match");
            String ordinaryLink = "[price $12 \\path](https://example.invalid/a$12)";
            equal(ordinaryLink, execute(spoilers, ordinaryLink), "ordinary link unchanged");
            if (args.length == 0) {
                for (boolean image : new boolean[] {true, false}) {
                    ArrayList<String> output = new ArrayList<>();
                    String marker = "emote|fixture.*";
                    String url = "https://example.invalid/" + (image ? "image" : "clip") + "$12\\still." +
                            (image ? "png" : "gif") + "?sig=$HOME\\end";
                    execute(posts, null, 1, null, output, 0, image, false,
                            "caption " + marker + " " + marker + " emote|fixtureXX");
                    equal(List.of("caption " + url + " " + url + " emote|fixtureXX"), output,
                            "image/gif URL preserved, literal search, all occurrences and group substitution");
                }
            }
            List<List<String>> patched = new ArrayList<>();
            for (Method target : targets) patched.add(keys(target));
            try { apply(context); throw new AssertionError("Duplicate application accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            for (int i = 0; i < targets.size(); i++) equal(patched.get(i), keys(targets.get(i)), "duplicate makes no edits");
            // A mismatched emote register must fail before either earlier method is edited.
            BytecodePatchContext malformed = new BytecodePatchContext(dex.toFile(), scratch.resolve("malformed").toFile());
            malformed.getClassDefs().initializeCache$patcher();
            MutableMethod badPosts = (MutableMethod)method(malformed, "Lc9/c;", "a");
            List<Instruction> badCode = instructions(badPosts);
            for (int i = 0; i < badCode.size(); i++) {
                Instruction instruction = badCode.get(i);
                if (!(instruction instanceof ReferenceInstruction) || !((ReferenceInstruction)instruction).getReference().toString()
                        .equals("Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) continue;
                FiveRegisterInstruction invoke = (FiveRegisterInstruction)instruction;
                badPosts.getImplementation().replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL,
                        3, invoke.getRegisterC(), 2, invoke.getRegisterE(), 0, 0, ((ReferenceInstruction)instruction).getReference()));
                break;
            }
            List<Method> malformedTargets = List.of(method(malformed, "Ld7/f;", "e"), method(malformed, "Ld7/f;", "g"), badPosts);
            List<List<String>> unmodified = new ArrayList<>();
            for (Method target : malformedTargets) unmodified.add(keys(target));
            try { apply(malformed); throw new AssertionError("Unsupported emote register accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("emote literal search"), "unsupported layout rejected"); }
            for (int i = 0; i < malformedTargets.size(); i++) equal(unmodified.get(i), keys(malformedTargets.get(i)), "validation failure makes no edits");
            System.out.println("PASS: " + checks + " Markdown patch checks (production hooks + executed DEX methods)");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static List<Instruction> instructions(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static List<String> keys(Method method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        for (Instruction i : instructions(method)) {
            String key = i.getOpcode() + ":" + (i instanceof ReferenceInstruction ? ((ReferenceInstruction)i).getReference() : "");
            key += "|" + i.getCodeUnits();
            if (i instanceof OneRegisterInstruction) key += ":" + ((OneRegisterInstruction)i).getRegisterA();
            if (i instanceof TwoRegisterInstruction) key += ":" + ((TwoRegisterInstruction)i).getRegisterB();
            if (i instanceof FiveRegisterInstruction) key += ":" + Arrays.toString(registers(i));
            if (i instanceof RegisterRangeInstruction) key += ":" + Arrays.toString(registers(i));
            if (i instanceof OffsetInstruction) key += ":offset=" + ((OffsetInstruction)i).getCodeOffset();
            if (i instanceof NarrowLiteralInstruction) key += ":literal=" + ((NarrowLiteralInstruction)i).getNarrowLiteral();
            result.add(key);
        }
        for (var block : method.getImplementation().getTryBlocks())
            result.add("try=" + block.getStartCodeAddress() + ":" + block.getCodeUnitCount() + ":" +
                    block.getExceptionHandlers().stream().map(h -> h.getExceptionType() + ":" + h.getHandlerCodeAddress()).toList());
        return result;
    }
    private static int[] registers(Instruction instruction) {
        if (instruction instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction i = (FiveRegisterInstruction)instruction;
            return Arrays.copyOf(new int[] {i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG()}, i.getRegisterCount());
        }
        RegisterRangeInstruction i = (RegisterRangeInstruction)instruction;
        return java.util.stream.IntStream.range(i.getStartRegister(), i.getStartRegister() + i.getRegisterCount()).toArray();
    }

    // Small strict runner for these straight Java/string DEX methods. All calls use
    // the real JVM APIs and production helpers; unknown opcodes fail the suite.
    // It deliberately propagates exceptions so a swallowed replacement error cannot pass.
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
            if (++steps > 10000) throw new AssertionError("Unbounded fixture execution");
            Instruction i = code.get(pc);
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction)i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case CONST_STRING: case CONST_STRING_JUMBO:
                    r[a] = ((StringReference)((ReferenceInstruction)i).getReference()).getString(); break;
                case CONST_4: case CONST_16: case CONST:
                    r[a] = ((NarrowLiteralInstruction)i).getNarrowLiteral(); break;
                case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                    r[a] = r[((TwoRegisterInstruction)i).getRegisterB()]; break;
                case NEW_INSTANCE:
                    r[a] = type(((TypeReference)((ReferenceInstruction)i).getReference()).getType()).getConstructor().newInstance(); break;
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a] = result; break;
                case INVOKE_DIRECT: break; // NEW_INSTANCE constructs the fixture's no-argument builders.
                case INVOKE_STATIC: case INVOKE_VIRTUAL: case INVOKE_STATIC_RANGE: case INVOKE_VIRTUAL_RANGE: {
                    MethodReference call = (MethodReference)((ReferenceInstruction)i).getReference();
                    int[] regs = registers(i);
                    boolean isStatic = i.getOpcode() == Opcode.INVOKE_STATIC || i.getOpcode() == Opcode.INVOKE_STATIC_RANGE;
                    Object receiver = isStatic ? null : r[regs[0]];
                    Object[] values = new Object[regs.length - (isStatic ? 0 : 1)];
                    for (int n = 0; n < values.length; n++) values[n] = r[regs[n + (isStatic ? 0 : 1)]];
                    Class<?>[] parameters = new Class<?>[call.getParameterTypes().size()];
                    for (int n = 0; n < parameters.length; n++) parameters[n] = type(call.getParameterTypes().get(n).toString());
                    try { result = type(call.getDefiningClass()).getMethod(call.getName(), parameters).invoke(receiver, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    break;
                }
                case IF_EQZ: case IF_NEZ: {
                    boolean zero = r[a] == null || Boolean.FALSE.equals(r[a]) || Integer.valueOf(0).equals(r[a]);
                    if (zero == (i.getOpcode() == Opcode.IF_EQZ)) { pc = branch(code, addresses, pc, i); continue; }
                    break;
                }
                case GOTO: case GOTO_16: case GOTO_32: pc = branch(code, addresses, pc, i); continue;
                case RETURN_OBJECT: return r[a];
                case RETURN_VOID: return null;
                default: throw new AssertionError("Unsupported fixture opcode " + i.getOpcode());
            }
            pc++;
        }
        throw new AssertionError("Missing return");
    }
    private static int branch(List<Instruction> code, Map<Integer, Integer> addresses, int pc, Instruction i) {
        int address = 0;
        for (int n = 0; n < pc; n++) address += code.get(n).getCodeUnits();
        return addresses.get(address + ((OffsetInstruction)i).getCodeOffset());
    }
    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        if (descriptor.equals("I")) return int.class;
        if (descriptor.equals("Z")) return boolean.class;
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
    }
}
