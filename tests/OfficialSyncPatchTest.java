import app.revanced.local.synccompat.OfficialSyncPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Applies the bundled ports of the official ReVanced Sync patches with their dependencies, as the
 * CLI does, and checks each edit on stock-layout fixtures (or, given a local stock Sync 23033 APK,
 * on the real classes). Simple patched methods are executed; nothing is saved or signed.
 */
public final class OfficialSyncPatchTest {
    private static final String UTILS = "Lapp/revanced/extension/shared/Utils;";
    private static final String SLINKS = "Lapp/revanced/extension/syncforreddit/FixSLinksPatch;";
    private static final String REDGIFS = "Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;";
    private static final String VIDEO = "Lapp/revanced/extension/syncforreddit/FixRedditVideoDownloadPatch;";
    private static final String ACCOUNT = "Lcom/laurencedawson/reddit_sync/singleton/a;";
    private static final String LOGIN = "Lw8/b;";
    private static final String CLIENT_ID = "Test_Client-1";
    private static final String REDIRECT = "redreader://rr_oauth_redir";
    private static final String USER_AGENT = "TestAgent/1.0 (local test)";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-official-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/official"), "assemble stock-layout fixtures");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext context = context(source, scratch, "all");
            MutableMethod router = method(context, "Ly7/b;", "c", 10);
            Instruction routerFirst = code(router).get(0);
            MutableMethod start = method(context, "Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;", "onCreate", 1);
            int startSize = code(start).size();
            int wwwBefore = count(context, s -> s.startsWith("https://www.reddit.com/"));
            int userBefore = count(context, s -> s.endsWith("user/"));

            List<Patch> official = officialPatches();
            equal(8, official.size(), "eight official Sync patches are bundled");
            Patch spoof = named(official, "Spoof client");
            spoof.getOptions().set("OAuth client ID", CLIENT_ID);
            spoof.getOptions().set("Redirect URI", REDIRECT);
            spoof.getOptions().set("User-Agent", USER_AGENT);
            Set<Patch> applied = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Patch patch : official) applyWithDependencies(context, patch, applied);

            // Extension: merged, and given Sync's application context first.
            check(context.getClassDefs().get(REDGIFS) != null, "bundle extension merged");
            List<Instruction> app = code(method(context, "Lcom/laurencedawson/reddit_sync/RedditApplication;", "onCreate", 0));
            equal(Opcode.INVOKE_STATIC_RANGE, app.get(0).getOpcode(), "context hook first");
            equal(UTILS + "->setContext(Landroid/content/Context;)V", reference(app.get(0)), "context hook target");
            check(reference(app.get(1)).startsWith("Landroid/app/Application;->onCreate"), "Sync's own start follows");

            // Fix /s/ links: resolve before LinkHelper routes the link (p3), and capture the access token.
            List<Instruction> route = code(router);
            int link = router.getImplementation().getRegisterCount() - 10 + 3;
            equal(SLINKS + "->patchResolveSLink(Ljava/lang/String;)Z", reference(route.get(0)), "share-link hook");
            equal(link, ((FiveRegisterInstruction) route.get(0)).getRegisterC(), "hook reads the link");
            equal(Opcode.MOVE_RESULT, route.get(1).getOpcode(), "hook result kept");
            equal(Opcode.IF_EQZ, route.get(2).getOpcode(), "unresolved links continue");
            check(((BuilderOffsetInstruction) route.get(2)).getTarget().getLocation().getInstruction() == routerFirst,
                    "unresolved links continue at Sync's original first instruction");
            equal(Opcode.RETURN, route.get(3).getOpcode(), "resolved links return");
            equal(SLINKS + "->patchSetAccessToken(Ljava/lang/String;)V",
                    reference(code(method(context, ACCOUNT, "e", 1)).get(0)), "access token captured first");

            // Fix Redgifs API: the RedGifs interceptor installs where OkHttpHelper.k built its client.
            List<Instruction> client = code(method(context, "La8/a;", "k", 0));
            check(client.stream().noneMatch(i -> reference(i).contains("OkHttpClient$Builder;->build")), "client no longer built directly");
            check(client.stream().anyMatch(i -> reference(i).equals(REDGIFS + "->install(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;")),
                    "RedGifs installer builds the client");
            equal(ACCOUNT + "->g()Ljava/lang/String;", reference(code(method(context, REDGIFS, "getDefaultUserAgent", 0)).get(0)),
                    "RedGifs requests use Sync's User-Agent");

            // Spoof client: credentials, authorization URL, redirect URI, User-Agent and Imgur URL.
            String basic = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":").getBytes(StandardCharsets.UTF_8));
            equal(basic, execute(method(context, LOGIN, "c", 0)), "token request uses the client ID");
            String authorize = strings(method(context, LOGIN, "d", 1)).stream().filter(s -> s.contains("authorize.compact")).findFirst().orElse("");
            equal("https://www.reddit.com/api/v1/authorize.compact?client_id=" + CLIENT_ID + "&response_type=code&state=", authorize,
                    "authorization URL uses the client ID on www.reddit.com");
            equal(REDIRECT, execute(method(context, LOGIN, "f", 0)), "redirect URI option");
            equal(USER_AGENT, execute(method(context, ACCOUNT, "g", 0)), "User-Agent option");
            equal(0, count(context, s -> s.contains("ssl.reddit.com")), "no ssl.reddit.com URLs remain");
            equal(wwwBefore + 8, count(context, s -> s.startsWith("https://www.reddit.com/")), "eight URLs moved to www.reddit.com");
            equal(1, count(context, s -> s.equals("https://api.imgur.com/3/image")), "Imgur upload uses Imgur's API");
            equal(0, count(context, s -> s.equals("https://imgur-apiv3.p.rapidapi.com/3/image")), "RapidAPI upload URL gone");

            // Disable ads, the Lemmy sheet, thumbnails, video downloads.
            equal(0, execute(method(context, "Ll9/a;", "b", 1)), "ads disabled");
            List<Instruction> started = code(start);
            equal(startSize - 1, started.size(), "one MainActivity instruction removed");
            check(started.stream().noneMatch(i -> reference(i).startsWith("Ls9/g;->f(")), "Sync for Lemmy sheet not shown");
            equal(Opcode.RETURN_VOID, started.get(started.size() - 1).getOpcode(), "MainActivity still returns");
            List<Instruction> load = code(method(context, "Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;", "q", 5));
            equal(List.of("&amp;", "&"), List.of(string(load.get(0)), string(load.get(1))), "thumbnail URLs unescaped");
            equal("Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", reference(load.get(2)), "replace call");
            equal(Opcode.MOVE_RESULT_OBJECT, load.get(3).getOpcode(), "URL replaced in place");
            List<Instruction> parse = code(method(context, "Lr8/g;", "parseNetworkResponse", 1));
            int result = -1;
            for (int i = 0; i < parse.size(); i++) if (reference(parse.get(i)).equals("Lo8/h;")) result = i;
            check(result >= 0, "video result found");
            equal(VIDEO + "->getLinks([B)[Ljava/lang/String;", reference(parse.get(result + 2)), "video links parsed from the response");
            FiveRegisterInstruction create = (FiveRegisterInstruction) parse.get(result + 9);
            check(reference(create).startsWith("Lo8/h;-><init>"), "result still built after the parsed links");
            equal(List.of(create.getRegisterE(), create.getRegisterF()),
                    List.of(((OneRegisterInstruction) parse.get(result + 5)).getRegisterA(), ((OneRegisterInstruction) parse.get(result + 7)).getRegisterA()),
                    "parsed video and audio URLs passed to the result");

            // Use /user/ endpoint (off by default; enabled here as the private build does).
            check(!named(official, "Use /user/ endpoint").getUse(), "Use /user/ endpoint is off by default");
            equal(userBefore + 6, count(context, s -> s.endsWith("user/")), "six profile paths moved to /user/");

            // A second application of any official patch is rejected without edits.
            Map<String, List<String>> patched = snapshot(context);
            for (Patch patch : official) {
                try { apply(context, patch); throw new AssertionError(patch.getName() + ": duplicate accepted"); }
                catch (PatchException expected) { check(expected.getMessage().contains("already installed"), patch.getName() + ": duplicate rejected"); }
            }
            equal(patched, snapshot(context), "duplicates leave the APK unchanged");

            // Defaults: Sync's own redirect URI and a random ReVanced-style User-Agent.
            BytecodePatchContext defaults = context(source, scratch, "defaults");
            Patch fresh = named(officialPatches(), "Spoof client");
            fresh.getOptions().set("OAuth client ID", CLIENT_ID);
            fresh.getOptions().set("Redirect URI", "http://redditsync/auth");
            fresh.getOptions().set("User-Agent", null);
            apply(defaults, fresh);
            equal("http://redditsync/auth", execute(method(defaults, LOGIN, "f", 0)), "default redirect URI kept");
            check(String.valueOf(execute(method(defaults, ACCOUNT, "g", 0))).matches("(\\d+):app\\.revanced\\.\\1:v1\\.0\\.0 \\(by /u/revanced\\)"),
                    "default User-Agent is the official random form");

            // Option values that smali strings or OAuth cannot carry are refused.
            for (String[] bad : new String[][] {{"OAuth client ID", "bad\"id"}, {"OAuth client ID", "has space"},
                    {"Redirect URI", "no-scheme"}, {"User-Agent", "back\\slash"}}) {
                try { fresh.getOptions().set(bad[0], bad[1]); throw new AssertionError(bad[0] + " accepted " + bad[1]); }
                catch (Exception expected) { check(expected.getClass().getName().contains("OptionException"), bad[0] + " rejects " + bad[1] + ": " + expected); }
            }

            // Unsupported layouts fail before any edit.
            BytecodePatchContext sslMoved = context(source, scratch, "ssl");
            MutableMethod token = methodWith(sslMoved, "Lb9/d;", s -> s.contains("ssl.reddit.com"));
            int site = indexOf(token, s -> s.contains("ssl.reddit.com"));
            int register = ((OneRegisterInstruction) code(token).get(site)).getRegisterA();
            token.getImplementation().replaceInstruction(site, new BuilderInstruction21c(Opcode.CONST_STRING, register,
                    new ImmutableStringReference("https://example.invalid/")));
            Map<String, List<String>> before = snapshot(sslMoved);
            Patch strict = named(officialPatches(), "Spoof client");
            strict.getOptions().set("OAuth client ID", CLIENT_ID);
            try { apply(sslMoved, strict); throw new AssertionError("seven ssl.reddit.com URLs accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("ssl.reddit.com"), "unexpected URL set rejected"); }
            equal(before, snapshot(sslMoved), "Spoof client rejection makes no edits");

            BytecodePatchContext noTitle = context(source, scratch, "title");
            MutableMethod bare = method(noTitle, "Ly7/b;", "c", 10);
            int title = indexOf(bare, s -> s.equals("Link title: "));
            bare.getImplementation().replaceInstruction(title, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) code(bare).get(title)).getRegisterA(), new ImmutableStringReference("Other")));
            Map<String, List<String>> untouched = snapshot(noTitle);
            try { apply(noTitle, named(officialPatches(), "Fix /s/ links")); throw new AssertionError("unknown LinkHelper accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("LinkHelper"), "unknown LinkHelper rejected"); }
            equal(untouched, snapshot(noTitle), "Fix /s/ links rejection makes no edits");

            System.out.println("PASS: " + checks + " official Sync patch checks (8 ports with dependencies, executed methods, "
                    + (args.length != 0 ? "supplied stock APK)" : "stock-layout fixtures)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** The official patches. They are singletons, so option values carry over; each use sets what it needs. */
    private static List<Patch> officialPatches() throws ReflectiveOperationException {
        List<Patch> result = new ArrayList<>();
        for (java.lang.reflect.Method getter : OfficialSyncPatchesKt.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(getter.getModifiers()) && getter.getParameterCount() == 0
                    && Patch.class.isAssignableFrom(getter.getReturnType())) {
                Patch patch = (Patch) getter.invoke(null);
                if (patch.getName() != null) result.add(patch);
            }
        }
        result.sort(Comparator.comparing(Patch::getName));
        return result;
    }
    private static Patch named(List<Patch> patches, String name) {
        return patches.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow(() -> new AssertionError("Missing " + name));
    }

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    /** Dependencies first and once, as ReVanced Patcher does. */
    private static void applyWithDependencies(BytecodePatchContext context, Patch patch, Set<Patch> applied) throws PatchException {
        if (!applied.add(patch)) return;
        for (Patch dependency : patch.getDependencies()) applyWithDependencies(context, dependency, applied);
        apply(context, patch);
    }
    private static void apply(BytecodePatchContext context, Patch patch) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        patch.getApply$patcher().invoke(context, resources);
    }

    private static MutableMethod method(BytecodePatchContext context, String type, String name, int parameters) {
        return single(context, type, name, m -> m.getName().equals(name) && m.getParameterTypes().size() == parameters);
    }
    private static MutableMethod methodWith(BytecodePatchContext context, String type, java.util.function.Predicate<String> text) {
        return single(context, type, "with string", m -> strings(m).stream().anyMatch(text));
    }
    private static MutableMethod single(BytecodePatchContext context, String type, String name, java.util.function.Predicate<Method> match) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing class " + type);
        List<MutableMethod> found = new ArrayList<>();
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getImplementation() != null && match.test(method)) found.add(method);
        if (found.size() != 1) throw new AssertionError("Expected one " + type + "->" + name + ", found " + found.size());
        return found.get(0);
    }
    private static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static String reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference().toString() : "";
    }
    private static String string(Instruction instruction) {
        return instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof StringReference
                ? ((StringReference) ((ReferenceInstruction) instruction).getReference()).getString() : null;
    }
    private static List<String> strings(Method method) {
        List<String> result = new ArrayList<>();
        for (Instruction i : code(method)) if (string(i) != null) result.add(string(i));
        return result;
    }
    private static int indexOf(Method method, java.util.function.Predicate<String> predicate) {
        List<Instruction> code = code(method);
        for (int i = 0; i < code.size(); i++) if (string(code.get(i)) != null && predicate.test(string(code.get(i)))) return i;
        throw new AssertionError("String not found in " + method.getName());
    }
    /** String constants matching [predicate] across the classes the patches can touch (Sync's code). */
    private static int count(BytecodePatchContext context, java.util.function.Predicate<String> predicate) {
        int total = 0;
        for (ClassDef clazz : context.getClassDefs()) {
            if (clazz.getType().startsWith("Lapp/revanced/")) continue;
            for (Method method : clazz.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction i : method.getImplementation().getInstructions())
                    if (string(i) != null && predicate.test(string(i))) total++;
            }
        }
        return total;
    }
    /** Every class the official patches edit (stock Sync 23033), plus the RedGifs extension class. */
    private static final List<String> TARGETS = List.of("Lcom/laurencedawson/reddit_sync/RedditApplication;", "Ly7/b;", ACCOUNT,
            "La8/a;", LOGIN, "Lb9/a;", "Lb9/b;", "Lb9/c;", "Lb9/d;", "Lw8/a$a;", "Ly8/k0;", "Lu8/c;", "Ll9/a;",
            "Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;", "Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;",
            "Lr8/g;", "Ly8/o;", "Ly8/a1;", "Ly8/b1;", "Ly8/c1;", "Ly8/y0;", REDGIFS);

    /** The target classes' methods as instruction keys. */
    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String type : TARGETS) {
            ClassDef clazz = context.getClassDefs().get(type);
            if (clazz == null) continue;
            List<String> keys = new ArrayList<>();
            for (Method method : clazz.getMethods()) {
                keys.add(method.getName() + method.getParameterTypes() + method.getReturnType());
                if (method.getImplementation() != null)
                    for (Instruction i : method.getImplementation().getInstructions()) keys.add(i.getOpcode() + ":" + reference(i));
            }
            result.put(clazz.getType(), keys);
        }
        return result;
    }
    /** Runs a method's leading constants up to its first return: enough for the early returns these patches add. */
    private static Object execute(Method method) {
        Map<Integer, Object> registers = new HashMap<>();
        for (Instruction i : code(method)) {
            switch (i.getOpcode()) {
                case CONST_STRING: case CONST_STRING_JUMBO: registers.put(((OneRegisterInstruction) i).getRegisterA(), string(i)); break;
                case CONST_4: case CONST_16: registers.put(((OneRegisterInstruction) i).getRegisterA(), ((NarrowLiteralInstruction) i).getNarrowLiteral()); break;
                case RETURN: case RETURN_OBJECT: return registers.get(((OneRegisterInstruction) i).getRegisterA());
                default: throw new AssertionError("Not a constant early return in " + method.getName() + ": " + i.getOpcode());
            }
        }
        throw new AssertionError("No return in " + method.getName());
    }
}
