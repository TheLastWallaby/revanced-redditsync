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
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: open YouTube in the YouTube app" and taps links through Sync's link router
 * (LinkHelper.c, y7.b.c) and its YouTube handler (LinkHandler.a0, y7.a.a0) before and after it,
 * with Sync's "YouTube videos" link setting on (the default) and off: on hand-written models, or,
 * given a local stock Sync 23033 APK, on the real methods, including Sync's real YouTube
 * classifier (LinkTypeHelper.K, y7.c.K).
 *
 * Stock Sync starts the embedded player (CustomYouTubeVideoActivity) for links K accepts, and
 * sends Shorts, youtube-nocookie.com and links containing "playlist" to its browser. Patched, every
 * YouTube video link goes to Sync's external opener LinkHandler.X (a plain ACTION_VIEW: the YouTube
 * app, else the browser) with the link YouTubeLinks.appUrl gives; look-alike and other hosts keep
 * their stock route. Sync's other classifiers are answered "no" by the suite, so links that are not
 * YouTube end at Sync's default-link opener (LinkHandler.V).
 */
public final class YouTubeAppPatchTest {
    private static final String YT = "Lapp/revanced/extension/synccompat/YouTubeLinks;";
    private static final String HELPER = "Ly7/b;";
    private static final String HANDLER = "Ly7/a;";
    private static final String TYPES = "Ly7/c;";
    private static final String SETTINGS = "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;";
    private static final String PLAYER = "Lcom/laurencedawson/reddit_sync/ui/activities/CustomYouTubeVideoActivity;";
    private static final String S = "Ljava/lang/String;";
    private static final List<String> ROUTER = List.of("Landroid/content/Context;", "[Lr0/d;", S, S, S, S, S, "Z", "Z", "Z");
    private static final List<String> A0 = List.of("Landroid/content/Context;", "[Lr0/d;", S, S);
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static final String ID = "aqz-KE-bpKQ";
    private static final String WATCH = "https://www.youtube.com/watch?v=" + ID;

    /**
     * Link tapped; stock route with the setting on; patched route with the setting on. Routes:
     * "player <url>" (embedded player, its url extra), "external <url>" (LinkHandler.X),
     * "browser <url>" (LinkHandler.V, Sync's browser choice).
     */
    private static final String[][] ROUTES = {
        // Videos Sync sends to the embedded player.
        {WATCH, "player " + WATCH, "external " + WATCH},
        {"http://youtube.com/watch?v=" + ID + "&t=90", "player http://youtube.com/watch?v=" + ID + "&t=90",
            "external http://youtube.com/watch?v=" + ID + "&t=90"},
        {WATCH + "&t=1h2m3s", "player " + WATCH + "&t=1h2m3s", "external " + WATCH + "&t=1h2m3s"},
        {"https://youtu.be/" + ID, "player https://youtu.be/" + ID, "external https://youtu.be/" + ID},
        {"https://youtu.be/" + ID + "?t=90", "player https://youtu.be/" + ID + "?t=90", "external https://youtu.be/" + ID + "?t=90"},
        {"https://m.youtube.com/watch?v=" + ID, "player https://m.youtube.com/watch?v=" + ID, "external " + WATCH},
        {"https://music.youtube.com/watch?v=" + ID, "player https://music.youtube.com/watch?v=" + ID,
            "external https://music.youtube.com/watch?v=" + ID},
        {"https://www.youtube.com/live/" + ID, "player https://www.youtube.com/live/" + ID, "external https://www.youtube.com/live/" + ID},
        {"https://www.youtube.com/embed/" + ID + "?start=90", "player https://www.youtube.com/embed/" + ID + "?start=90",
            "external " + WATCH + "&t=90"},
        {WATCH + "&list=PLx1&index=2", "player " + WATCH + "&list=PLx1&index=2", "external " + WATCH + "&list=PLx1&index=2"},
        {WATCH + "&amp;t=90", "player " + WATCH + "&amp;t=90", "external " + WATCH + "&t=90"},
        // Sync lower-cases only the scheme before routing; the host is lower-cased for Android.
        {"HTTPS://WWW.YouTube.com/watch?v=" + ID, "player https://WWW.YouTube.com/watch?v=" + ID, "external " + WATCH},
        {"youtube.com/watch?v=" + ID, "player youtube.com/watch?v=" + ID, "external https://youtube.com/watch?v=" + ID},
        // Sync already opens "shared" links externally.
        {WATCH + "&feature=shared", "external " + WATCH + "&feature=shared", "external " + WATCH + "&feature=shared"},
        // Other YouTube pages K accepts: no video to embed; now Android's handling too.
        {"https://www.youtube.com/@someone", "player https://www.youtube.com/@someone", "external https://www.youtube.com/@someone"},
        // Video links Sync sends to its browser: Shorts, nocookie embeds, "playlist" in the link.
        {"https://www.youtube.com/shorts/" + ID, "browser https://www.youtube.com/shorts/" + ID,
            "external https://www.youtube.com/shorts/" + ID},
        {"https://youtube.com/shorts/" + ID + "?feature=share", "browser https://youtube.com/shorts/" + ID + "?feature=share",
            "external https://youtube.com/shorts/" + ID + "?feature=share"},
        {"https://m.youtube.com/shorts/" + ID, "browser https://m.youtube.com/shorts/" + ID, "external https://www.youtube.com/shorts/" + ID},
        {"https://www.youtube-nocookie.com/embed/" + ID + "?start=30", "browser https://www.youtube-nocookie.com/embed/" + ID + "?start=30",
            "external " + WATCH + "&t=30"},
        {WATCH + "&list=PLx1&feature=playlist", "browser " + WATCH + "&list=PLx1&feature=playlist",
            "external " + WATCH + "&list=PLx1&feature=playlist"},
        // YouTube pages that are not videos keep Sync's browser route.
        {"https://www.youtube.com/playlist?list=PLx1", "browser https://www.youtube.com/playlist?list=PLx1",
            "browser https://www.youtube.com/playlist?list=PLx1"},
        {"https://www.youtube.com/channel/UCabc", "browser https://www.youtube.com/channel/UCabc", "browser https://www.youtube.com/channel/UCabc"},
        {"https://www.youtube.com/clip/UgkxAbc", "browser https://www.youtube.com/clip/UgkxAbc", "browser https://www.youtube.com/clip/UgkxAbc"},
        {"https://www.youtube.com/user/someone", "browser https://www.youtube.com/user/someone", "browser https://www.youtube.com/user/someone"},
        {"https://img.youtube.com/vi/" + ID + "/0.jpg", "browser https://img.youtube.com/vi/" + ID + "/0.jpg",
            "browser https://img.youtube.com/vi/" + ID + "/0.jpg"},
        // Look-alike hosts keep their stock route, even where Sync's K accepts them.
        {"https://notyoutube.com/watch?v=" + ID, "player https://notyoutube.com/watch?v=" + ID, "player https://notyoutube.com/watch?v=" + ID},
        {"https://user@www.youtube.com/watch?v=" + ID, "player https://user@www.youtube.com/watch?v=" + ID,
            "player https://user@www.youtube.com/watch?v=" + ID},
        {"https://youtube.com.evil.example/shorts/" + ID, "browser https://youtube.com.evil.example/shorts/" + ID,
            "browser https://youtube.com.evil.example/shorts/" + ID},
        {"https://gifyoutube.com/" + ID, "browser https://gifyoutube.com/" + ID, "browser https://gifyoutube.com/" + ID},
        {"https://youtu.be.example.com/" + ID, "browser https://youtu.be.example.com/" + ID, "browser https://youtu.be.example.com/" + ID},
        {"https://evil.example/youtu.be/" + ID, "browser https://evil.example/youtu.be/" + ID, "browser https://evil.example/youtu.be/" + ID},
        {"https://www.youtube-nocookie.com.example/embed/" + ID, "browser https://www.youtube-nocookie.com.example/embed/" + ID,
            "browser https://www.youtube-nocookie.com.example/embed/" + ID},
        // Other hosts.
        {"https://v.redd.it/abc123", "browser https://v.redd.it/abc123", "browser https://v.redd.it/abc123"},
        {"https://www.redgifs.com/watch/joyfulwhitebird", "browser https://www.redgifs.com/watch/joyfulwhitebird",
            "browser https://www.redgifs.com/watch/joyfulwhitebird"},
        {"https://imgur.com/a/AbCd", "browser https://imgur.com/a/AbCd", "browser https://imgur.com/a/AbCd"},
        {"https://streamable.com/abc", "browser https://streamable.com/abc", "browser https://streamable.com/abc"},
        {"https://example.com/article?v=" + ID, "browser https://example.com/article?v=" + ID, "browser https://example.com/article?v=" + ID},
    };

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-youtube-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/youtube"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stockContext = context(source, scratch, "stock");
            BytecodePatchContext patchedContext = context(source, scratch, "patched");
            Map<String, List<String>> before = snapshot(patchedContext);
            apply(patchedContext);
            hooks(patchedContext, before);

            int handedOver = 0;
            for (String[] row : ROUTES) {
                String link = row[0];
                equal(row[1], tap(stockContext, link, true), "stock route " + link);
                String patched = tap(patchedContext, link, true);
                equal(row[2], patched, "patched route " + link);
                if (patched.startsWith("external ")) {
                    handedOver++;
                    equal("external " + app.revanced.extension.synccompat.YouTubeLinks.appUrl(schemeAsSync(link)), patched,
                            "handed over as YouTubeLinks.appUrl " + link);
                }
                // With the setting off Sync already opens K's links externally; the patch only adds its fixes.
                String stockOff = tap(stockContext, link, false), patchedOff = tap(patchedContext, link, false);
                check(!stockOff.startsWith("player") && !patchedOff.startsWith("player") || link.contains("notyoutube") || link.contains("user@"),
                        "setting off: no embedded player for " + link + " (" + stockOff + ", " + patchedOff + ")");
                if (row[1].startsWith("player") && !row[2].startsWith("player")) {
                    equal("external " + schemeAsSync(link), stockOff, "stock with the setting off " + link);
                    equal(row[2], patchedOff, "patched with the setting off " + link);
                } else if (row[2].startsWith("player")) {
                    equal("external " + schemeAsSync(link), patchedOff, "look-alike keeps Sync's setting-off route " + link);
                } else {
                    equal(row[1].startsWith("browser") ? row[1] : "external " + schemeAsSync(link), stockOff, "stock with the setting off " + link);
                    equal(row[2], patchedOff, "patched with the setting off " + link);
                }
            }
            check(handedOver >= 20, "most YouTube rows handed over");

            // The handler alone (as the long-press sheet and other callers reach it).
            equal("external " + WATCH, handler(patchedContext, "https://m.youtube.com/watch?v=" + ID, true), "a0 hands over");
            equal("player https://notyoutube.com/x", handler(patchedContext, "https://notyoutube.com/x", true), "a0 keeps a look-alike");
            equal("external " + WATCH, handler(stockContext, WATCH, false), "stock a0 with the setting off");

            rejections(source, scratch);

            System.out.println("PASS: " + checks + " YouTube app handoff patch checks (" + ROUTES.length + " links, setting on and off, "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Hook structure ----

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        // a0: appUrl into p1 (dead on entry), external opener and return, else Sync's body unchanged.
        MutableMethod a0 = method(context, HANDLER, "a0", A0);
        List<Instruction> code = code(a0);
        List<String> stock = before.get(signature(a0)), now = keys(a0);
        equal(YT + "->appUrl(Ljava/lang/String;)Ljava/lang/String;", reference(code.get(0)), "a0 asks YouTubeLinks first");
        equal(2, ((FiveRegisterInstruction) code.get(0)).getRegisterC(), "a0 passes the link (p2)");
        equal(Opcode.MOVE_RESULT_OBJECT, code.get(1).getOpcode(), "a0 keeps the app URL");
        equal(1, ((OneRegisterInstruction) code.get(1)).getRegisterA(), "a0 keeps it in p1");
        equal(Opcode.IF_EQZ, code.get(2).getOpcode(), "a0 checks for a YouTube host");
        check(((BuilderOffsetInstruction) code.get(2)).getTarget().getLocation().getInstruction() == code.get(5),
                "a0 falls through to Sync's first instruction");
        equal(HANDLER + "->X(Landroid/content/Context;Ljava/lang/String;)V", reference(code.get(3)), "a0 opens externally");
        FiveRegisterInstruction open = (FiveRegisterInstruction) code.get(3);
        check(open.getRegisterC() == 0 && open.getRegisterD() == 1, "a0 opens the app URL with Sync's context");
        equal(Opcode.RETURN_VOID, code.get(4).getOpcode(), "a0 returns after opening");
        equal(stock.subList(1, stock.size()), now.subList(6, now.size()), "a0 keeps Sync's body");
        equal(Opcode.NEW_INSTANCE, code.get(5).getOpcode(), "Sync's first instruction overwrites p1");
        equal(1, ((OneRegisterInstruction) code.get(5)).getRegisterA(), "Sync's first instruction writes p1");

        // Router: after K's result, a false answer asks isVideoLink; the original branch follows.
        MutableMethod router = method(context, HELPER, "c", ROUTER);
        code = code(router);
        stock = before.get(signature(router));
        now = keys(router);
        int k = -1;
        for (int i = 0; i < code.size(); i++) if ((TYPES + "->K(Ljava/lang/String;)Z").equals(reference(code.get(i)))) k = i;
        check(k > 0, "router still calls K");
        int link = ((FiveRegisterInstruction) code.get(k)).getRegisterC();
        int result = ((OneRegisterInstruction) code.get(k + 1)).getRegisterA();
        equal(Opcode.IF_NEZ, code.get(k + 2).getOpcode(), "K's yes skips the new check");
        equal(result, ((OneRegisterInstruction) code.get(k + 2)).getRegisterA(), "new check reads K's answer");
        check(((BuilderOffsetInstruction) code.get(k + 2)).getTarget().getLocation().getInstruction() == code.get(k + 5),
                "K's yes reaches Sync's YouTube branch");
        equal(YT + "->isVideoLink(Ljava/lang/String;)Z", reference(code.get(k + 3)), "K's no asks isVideoLink");
        equal(link, ((FiveRegisterInstruction) code.get(k + 3)).getRegisterC(), "isVideoLink reads the routed link");
        equal(Opcode.MOVE_RESULT, code.get(k + 4).getOpcode(), "isVideoLink answer kept");
        equal(result, ((OneRegisterInstruction) code.get(k + 4)).getRegisterA(), "isVideoLink answers in K's register");
        equal(Opcode.IF_EQZ, code.get(k + 5).getOpcode(), "Sync's YouTube branch check follows");
        equal("Was YouTube", DexRunner.string(code.get(k + 6)), "Sync's YouTube branch");
        List<String> expected = new ArrayList<>(stock);
        expected.addAll(k + 3, List.of("IF_NEZ:", "INVOKE_STATIC:" + YT + "->isVideoLink(Ljava/lang/String;)Z", "MOVE_RESULT:"));
        equal(expected, now, "router only gains the check");
        equal(2, snapshotTypes(context).size(), "two methods edited");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A router without Sync's YouTube branch log fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod router = method(other, HELPER, "c", ROUTER);
        List<Instruction> body = code(router);
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if ("Was YouTube".equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker > 0, "YouTube branch log found");
        router.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("Was something else")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("YouTube branch"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");

        // A handler that no longer starts the embedded player fails before any edit.
        BytecodePatchContext noPlayer = context(source, scratch, "no-player");
        MutableMethod a0 = method(noPlayer, HANDLER, "a0", A0);
        List<Instruction> handler = code(a0);
        for (int i = 0; i < handler.size(); i++) {
            if (!"shared".equals(DexRunner.string(handler.get(i)))) continue;
            a0.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) handler.get(i)).getRegisterA(), new ImmutableStringReference("other")));
        }
        unchanged = snapshot(noPlayer);
        try { apply(noPlayer); throw new AssertionError("Unknown handler accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("YouTube link handler"), "unknown handler rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(noPlayer), "handler rejection makes no edits");
    }

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MutableMethod m : List.of(method(context, HANDLER, "a0", A0), method(context, HELPER, "c", ROUTER)))
            result.put(signature(m), keys(m));
        return result;
    }
    private static Set<String> snapshotTypes(BytecodePatchContext context) {
        Set<String> edited = new TreeSet<>();
        for (MutableMethod m : List.of(method(context, HANDLER, "a0", A0), method(context, HELPER, "c", ROUTER)))
            if (code(m).stream().anyMatch(i -> reference(i).startsWith(YT))) edited.add(signature(m));
        return edited;
    }

    // ---- Taps ----

    /** Sync lower-cases only an http(s) scheme ("HTTPS://" -> "https://") before routing. */
    private static String schemeAsSync(String link) {
        String lower = link.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("https")) return "https" + link.substring(5);
        if (lower.startsWith("http")) return "http" + link.substring(4);
        return link;
    }

    /** Taps the link through Sync's router and returns where it went. */
    private static String tap(BytecodePatchContext context, String link, boolean embed) {
        Taps taps = new Taps(embed);
        Object handled = runner(context, taps).run(method(context, HELPER, "c", ROUTER),
                new Object[] {"context", null, null, link, null, null, null, 0, 0, 0});
        check(!Integer.valueOf(0).equals(handled), "router handled " + link);
        equal(1, taps.routes.size(), "one destination for " + link + " " + taps.routes);
        return taps.routes.get(0);
    }

    /** Calls LinkHandler.a0 directly. */
    private static String handler(BytecodePatchContext context, String link, boolean embed) {
        Taps taps = new Taps(embed);
        runner(context, taps).run(method(context, HANDLER, "a0", A0), new Object[] {"context", null, link, null});
        equal(1, taps.routes.size(), "one destination for " + link + " " + taps.routes);
        return taps.routes.get(0);
    }

    /** Stands in for Android and Sync's app services, and records where a link was sent. */
    private static final class Taps implements DexRunner.Interceptor {
        final boolean embed;
        final List<String> routes = new ArrayList<>();
        final Map<Object, String> intents = new IdentityHashMap<>();
        final Map<Object, String> urls = new IdentityHashMap<>();
        Taps(boolean embed) { this.embed = embed; }

        @Override public Object call(String ref, Object[] a) {
            if (ref.startsWith(SETTINGS + "->d()") || ref.startsWith(SETTINGS + "->x()") || ref.startsWith(SETTINGS + "->j()")) {
                if (ref.startsWith(SETTINGS + "->d()")) return new DexRunner.SyncObject(SETTINGS);
                DexRunner.SyncObject settings = new DexRunner.SyncObject(SETTINGS + "$Settings");
                settings.fields.put("embedYouTube", embed ? 1 : 0);
                return settings;
            }
            if (ref.equals("Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V")) {
                intents.put(a[0], ((DexRunner.ClassRef) a[2]).type);
                return null;
            }
            if (ref.equals("Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;")) {
                if ("url".equals(a[1])) urls.put(a[0], (String) a[2]);
                return a[0];
            }
            if (ref.startsWith(HANDLER + "->c0(")) {
                equal(PLAYER, intents.get(a[1]), "started activity");
                routes.add("player " + urls.get(a[1]));
                return null;
            }
            if (ref.startsWith(HANDLER + "->X(")) { routes.add("external " + a[1]); return null; }
            if (ref.startsWith(HANDLER + "->V(")) { routes.add("browser " + a[1]); return null; }
            if (ref.startsWith(HANDLER + "->a0(")) return DexRunner.PASS;
            if (ref.startsWith(HANDLER + "->")) { routes.add(ref.substring(HANDLER.length() + 2, ref.indexOf('(')) + " " + a[1]); return null; }
            if (ref.startsWith(HELPER + "->b(")) { routes.add("reroute " + a[1]); return 1; }
            // Sync's YouTube classifier and its host helper run; every other classifier answers no.
            if (ref.equals(TYPES + "->K(Ljava/lang/String;)Z") || ref.equals(TYPES + "->a(Ljava/lang/String;)Ljava/lang/String;"))
                return DexRunner.PASS;
            if (ref.startsWith(TYPES) && ref.endsWith(")Z")) return 0;
            if (ref.startsWith("Lf8/a;->") && ref.endsWith(")Z")) return 0;
            if (ref.startsWith("Lgb/p;->")) return null;
            return DexRunner.PASS;
        }
    }

    // ---- Helpers ----

    private static DexRunner runner(BytecodePatchContext context, Taps taps) {
        return new DexRunner(context, taps, YT);
    }
    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    private static void apply(BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        SyncCompatPatchesKt.getOpenSyncYouTubeInApp().getApply$patcher().invoke(context, resources);
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
