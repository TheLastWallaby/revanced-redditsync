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
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/**
 * Applies the production "Sync: play Reddit videos via DASH" patch to layout fixtures and executes
 * Sync's player before and after it, or, with an APK argument, checks the hooks on a local prepared
 * base's real methods. Also checks duplicate and unsupported-layout rejection.
 */
public final class RedditVideoPatchTest {
    private static final String COMPAT = "Lapp/revanced/extension/synccompat/RedditVideoCompat;";
    private static final String PLAYER = "Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;";
    private static final String UTILS = "Lxc/a;";
    private static final String TYPES = "Ly7/c;";
    private static final String HOOK = COMPAT + "->playbackSource(Ljava/lang/String;)Ljava/lang/String;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        boolean apk = args.length != 0;
        Path scratch = Files.createTempDirectory("sync-reddit-video-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/redditvideo"), "assemble layout fixtures");

            BytecodePatchContext context = context(dex, scratch, "patched", args);
            MutableMethod setSource = method(context, PLAYER, "setSource", 3);
            MutableMethod autoplay = method(context, UTILS, "a", 1);
            int source = setSource.getImplementation().getRegisterCount() - 4 + 1;
            int url = autoplay.getImplementation().getRegisterCount() - 1;
            List<String> setBefore = keys(setSource), autoBefore = keys(autoplay);

            String hls = "https://v.redd.it/6oemnktwd1zh1/HLSPlaylist.m3u8";
            String dashUrl = "https://v.redd.it/6oemnktwd1zh1/DASHPlaylist.mpd";
            if (!apk) {
                equal("HLS " + hls, execute(context, autoplay, null, hls), "original autoplay plays the unsigned HLS playlist");
                Map<String, Object> view = new HashMap<>();
                execute(context, setSource, view, view, hls, true, false);
                equal("HLS " + hls, view.get("mediaSource"), "original viewer plays the unsigned HLS playlist");
            }

            apply(context);

            for (MutableMethod method : List.of(setSource, autoplay)) {
                List<Instruction> code = instructions(method);
                int register = method == setSource ? source : url;
                equal(Opcode.INVOKE_STATIC, code.get(0).getOpcode(), "hook call first");
                equal(HOOK, reference(code.get(0)), "hook reference");
                equal(register, ((FiveRegisterInstruction) code.get(0)).getRegisterC(), "hook reads the source parameter");
                equal(Opcode.MOVE_RESULT_OBJECT, code.get(1).getOpcode(), "rewritten source kept");
                equal(register, ((OneRegisterInstruction) code.get(1)).getRegisterA(), "rewritten source replaces the parameter");
            }
            equal(shifted(setBefore, 2), keys(setSource).subList(3, keys(setSource).size()), "setSource otherwise unchanged");
            equal(shifted(autoBefore, 2), keys(autoplay).subList(3, keys(autoplay).size()), "ExoplayerUtils otherwise unchanged");
            java.lang.reflect.Method hook = Class.forName("app.revanced.extension.synccompat.RedditVideoCompat")
                    .getMethod("playbackSource", String.class);
            check(java.lang.reflect.Modifier.isStatic(hook.getModifiers()) && hook.getReturnType() == String.class,
                    "hook resolves to the extension's public static method");

            if (!apk) {
                String signed = hls + "?a=1796157371%2CZDIw%3D%3D&amp;v=1&amp;f=sd";
                String[][] rows = {
                        {hls, "DASH " + dashUrl},
                        {signed, "DASH " + dashUrl},
                        {dashUrl, "DASH " + dashUrl},
                        {"https://v.redd.it/6oemnktwd1zh1", "PROGRESSIVE https://v.redd.it/6oemnktwd1zh1"},
                        {"/storage/emulated/0/Sync/clip.mp4", "PROGRESSIVE /storage/emulated/0/Sync/clip.mp4"},
                        {"https://i.imgur.com/abc.mp4", "PROGRESSIVE https://i.imgur.com/abc.mp4"},
                        // Sync matches v.redd.it anywhere; another host keeps Sync's own (HLS) handling.
                        {"https://example.org/v.redd.it/x/HLSPlaylist.m3u8", "HLS https://example.org/v.redd.it/x/HLSPlaylist.m3u8"},
                };
                for (String[] row : rows) {
                    equal(row[1], execute(context, autoplay, null, row[0]), "autoplay source for " + row[0]);
                    Map<String, Object> view = new HashMap<>();
                    execute(context, setSource, view, view, row[0], true, false);
                    equal(row[1], view.get("mediaSource"), "viewer source for " + row[0]);
                    equal(row[1].substring(row[1].indexOf(' ') + 1), view.get("mSource"), "viewer stores the source it plays");
                    equal(Boolean.TRUE, view.get("mHd"), "HD flag still stored");
                }
            }

            // A second application is rejected without edits.
            List<String> setPatched = keys(setSource), autoPatched = keys(autoplay);
            try { apply(context); throw new AssertionError("Duplicate application accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            equal(setPatched, keys(setSource), "duplicate leaves setSource");
            equal(autoPatched, keys(autoplay), "duplicate leaves ExoplayerUtils");

            // Each unsupported layout fails before either method is edited.
            rejects(dex, scratch, args, "source-not-stored", c -> nop(method(c, PLAYER, "setSource", 3), 0), "setSource");
            rejects(dex, scratch, args, "no-dash-branch", c -> {
                MutableMethod m = method(c, UTILS, "a", 1);
                nop(m, indexOf(m, "Lcom/google/android/exoplayer2/source/dash/DashMediaSource$Factory;->createMediaSource("));
            }, "ExoplayerUtils");
            rejects(dex, scratch, args, "no-mpd-classifier", c -> {
                MutableMethod m = method(c, TYPES, "s", 1);
                List<Instruction> code = instructions(m);
                for (int i = 0; i < code.size(); i++) {
                    if (code.get(i) instanceof ReferenceInstruction && ((ReferenceInstruction) code.get(i)).getReference()
                            instanceof StringReference && ".mpd".equals(reference(code.get(i)))) nop(m, i);
                }
            }, "Reddit video classifier");

            System.out.println("PASS: " + checks + " Reddit video patch checks (production hooks"
                    + (apk ? ", supplied base APK" : ", executed layout fixtures") + ")");
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
        List<Method> targets = List.of(method(context, PLAYER, "setSource", 3), method(context, UTILS, "a", 1));
        List<List<String>> before = new ArrayList<>();
        for (Method target : targets) before.add(keys(target));
        try { apply(context); throw new AssertionError(name + ": unsupported layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains(message), name + ": rejected (" + expected.getMessage() + ")"); }
        for (int i = 0; i < targets.size(); i++) equal(before.get(i), keys(targets.get(i)), name + ": rejection makes no edits");
    }

    private static void nop(MutableMethod method, int index) {
        method.getImplementation().replaceInstruction(index, new BuilderInstruction10x(Opcode.NOP));
    }

    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getPlaySyncRedditVideosViaDash().getApply$patcher().invoke(context, resources);
    }
    private static BytecodePatchContext context(Path dex, Path scratch, String name, String[] args) {
        File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name, int parameters) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing class " + type);
        MutableClassDef mutable = context.proxy(clazz).getMutableClass();
        for (MutableMethod method : mutable.getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().size() == parameters) return method;
        throw new AssertionError("Missing method " + type + "->" + name);
    }
    private static int indexOf(Method method, String prefix) {
        List<Instruction> code = instructions(method);
        for (int i = 0; i < code.size(); i++) if (reference(code.get(i)).startsWith(prefix)) return i;
        throw new AssertionError("Missing " + prefix);
    }
    private static String reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference().toString() : "";
    }
    private static List<Instruction> instructions(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static Map<Integer, Integer> addresses(List<Instruction> code) {
        Map<Integer, Integer> result = new HashMap<>();
        int address = 0;
        for (int n = 0; n < code.size(); n++) { result.put(address, n); address += code.get(n).getCodeUnits(); }
        return result;
    }
    /** Register count, then one key per instruction; branch targets are instruction indexes. */
    private static List<String> keys(Method method) {
        List<String> result = new ArrayList<>();
        result.add("registers=" + method.getImplementation().getRegisterCount());
        List<Instruction> code = instructions(method);
        Map<Integer, Integer> addresses = addresses(code);
        int address = 0;
        for (Instruction i : code) {
            String key = i.getOpcode() + ":" + reference(i);
            if (i instanceof OneRegisterInstruction) key += ":a" + ((OneRegisterInstruction) i).getRegisterA();
            if (i instanceof TwoRegisterInstruction) key += ":b" + ((TwoRegisterInstruction) i).getRegisterB();
            if (i instanceof FiveRegisterInstruction) {
                FiveRegisterInstruction f = (FiveRegisterInstruction) i;
                key += ":" + Arrays.toString(Arrays.copyOf(new int[]{f.getRegisterC(), f.getRegisterD(), f.getRegisterE(),
                        f.getRegisterF(), f.getRegisterG()}, f.getRegisterCount()));
            }
            if (i instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction r = (RegisterRangeInstruction) i;
                key += ":range" + r.getStartRegister() + "+" + r.getRegisterCount();
            }
            if (i instanceof NarrowLiteralInstruction) key += ":lit" + ((NarrowLiteralInstruction) i).getNarrowLiteral();
            if (i instanceof OffsetInstruction) key += ":target=" + addresses.get(address + ((OffsetInstruction) i).getCodeOffset());
            result.add(key);
            address += i.getCodeUnits();
        }
        for (var block : method.getImplementation().getTryBlocks())
            result.add("try=" + block.getCodeUnitCount() + ":" + block.getExceptionHandlers().size());
        return result;
    }
    /** Original instruction keys with branch targets moved by the inserted instructions. */
    private static List<String> shifted(List<String> keys, int inserted) {
        List<String> result = new ArrayList<>();
        for (String key : keys.subList(1, keys.size())) {
            int at = key.indexOf(":target=");
            result.add(at < 0 ? key : key.substring(0, at) + ":target=" + (Integer.parseInt(key.substring(at + 8)) + inserted));
        }
        return result;
    }

    // ---- small strict runner for the fixture methods; unknown opcodes fail the suite ----

    private static Object execute(BytecodePatchContext context, Method method, Map<String, Object> self, Object... arguments)
            throws Throwable {
        Object[] r = new Object[method.getImplementation().getRegisterCount()];
        System.arraycopy(arguments, 0, r, r.length - arguments.length, arguments.length);
        List<Instruction> code = instructions(method);
        Map<Integer, Integer> addresses = addresses(code);
        Object result = null;
        int pc = 0, steps = 0;
        while (pc < code.size()) {
            if (++steps > 1000) throw new AssertionError("Unbounded fixture execution");
            Instruction i = code.get(pc);
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case CONST_STRING: case CONST_STRING_JUMBO: r[a] = reference(i); break;
                case CONST_4: case CONST_16: case CONST: r[a] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a] = result; break;
                case IPUT_OBJECT: case IPUT_BOOLEAN:
                    self.put(((FieldReference) ((ReferenceInstruction) i).getReference()).getName(), r[a]); break;
                case IGET_OBJECT: r[a] = self.get(((FieldReference) ((ReferenceInstruction) i).getReference()).getName()); break;
                case IF_EQZ: case IF_NEZ: {
                    boolean zero = r[a] == null || Boolean.FALSE.equals(r[a]) || Integer.valueOf(0).equals(r[a]);
                    if (zero == (i.getOpcode() == Opcode.IF_EQZ)) { pc = target(code, addresses, pc, i); continue; }
                    break;
                }
                case GOTO: case GOTO_16: pc = target(code, addresses, pc, i); continue;
                case INVOKE_STATIC: case INVOKE_VIRTUAL: result = invoke(context, self, i, r); break;
                case NOP: break;
                case RETURN: case RETURN_OBJECT: return r[a];
                case RETURN_VOID: return null;
                default: throw new AssertionError("Unsupported fixture opcode " + i.getOpcode());
            }
            pc++;
        }
        throw new AssertionError("Missing return");
    }

    private static Object invoke(BytecodePatchContext context, Map<String, Object> self, Instruction i, Object[] r) throws Throwable {
        MethodReference call = (MethodReference) ((ReferenceInstruction) i).getReference();
        FiveRegisterInstruction f = (FiveRegisterInstruction) i;
        int[] regs = Arrays.copyOf(new int[]{f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG()},
                f.getRegisterCount());
        Object[] values = new Object[regs.length];
        for (int n = 0; n < values.length; n++) values[n] = r[regs[n]];
        String owner = call.getDefiningClass(), name = call.getName();
        if (owner.equals(COMPAT)) {
            try {
                return Class.forName("app.revanced.extension.synccompat.RedditVideoCompat")
                        .getMethod(name, String.class).invoke(null, values[0]);
            } catch (InvocationTargetException e) { throw e.getCause(); }
        }
        if (owner.equals("Ljava/lang/String;") && name.equals("contains")) return ((String) values[0]).contains((CharSequence) values[1]);
        if (owner.equals("Lcom/google/android/exoplayer2/MediaItem;") && name.equals("fromUri")) return values[0];
        if (name.equals("createMediaSource")) {
            String kind = owner.contains("/dash/") ? "DASH" : owner.contains("/hls/") ? "HLS" : "PROGRESSIVE";
            return kind + " " + values[1];
        }
        if (owner.equals(TYPES) || owner.equals(UTILS) || owner.equals(PLAYER)) {
            Method target = method(context, owner, name, call.getParameterTypes().size());
            if (owner.equals(PLAYER)) return execute(context, target, self, values);
            return execute(context, target, null, values);
        }
        throw new AssertionError("Unsupported fixture call " + owner + "->" + name);
    }

    private static int target(List<Instruction> code, Map<Integer, Integer> addresses, int pc, Instruction i) {
        int address = 0;
        for (int n = 0; n < pc; n++) address += code.get(n).getCodeUnits();
        return addresses.get(address + ((OffsetInstruction) i).getCodeOffset());
    }
}
