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
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: show profile history from Arctic Shift on demand" and runs Sync's profile section
 * sheet (ProfileSectionSelectionDialogBottomSheet, t9.l1) before and after it: on hand-written models,
 * or, given a local stock Sync 23033 APK, on the real methods.
 *
 * <p>A4 (the sheet's items): stock lists Sync's sections; patched lists exactly the same and then one
 * "Show Arctic Shift archive" row, with Sync's restore icon, on someone else's profile and on your own
 * (whose extra sections end at the same return), titled "Show Reddit's profile" and highlighted while
 * the archive is on. r0 (an item chosen): a section posts exactly the same event as stock; the archive
 * row turns the archive on or off for that user and posts the section Sync last loaded for them, so
 * Sync reloads it, then closes the sheet as for a section. Sync's event bus, account and sheet list are
 * stand-ins that record what Sync did.
 */
public final class ArchivedProfileOnDemandPatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/ArchivedProfile;";
    private static final String SHEET = "Lt9/l1;";
    private static final String ITEM = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;";
    private static final String SELECTION = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;";
    private static final String USER = "Partly_Hidden";
    private static final String ME = "me_myself";
    private static final int ICON = 0x7f0801ab;
    private static final String SHOW = "Show Arctic Shift archive", HIDE = "Show Reddit's profile";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-archived-profile-ondemand-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, new String[] {"tests/fixtures/archivedprofileondemand"}), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            hooks(patched.context, before);

            setPlatform();
            rows(stock, patched);
            choices(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " archived profile on-demand patch checks (profile sheet lists Sync's sections "
                    + "then the archive row; sections post as before; the row toggles the archive and reloads the section; executed "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Sync, run instruction by instruction ----

    private static final List<String> TOASTS = new ArrayList<>();

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        String user = USER;
        /** Items the sheet listed: title, icon, highlight. */
        final List<String> items = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        Sync(BytecodePatchContext context) {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER);
        }

        private boolean model(String type) { return context.getClassDefs().get(type) == null; }

        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Ls9/f;->Y3()Ljava/lang/String;": return user;
                case "Lcom/laurencedawson/reddit_sync/singleton/a;->d()Lcom/laurencedawson/reddit_sync/singleton/a;":
                    return new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/singleton/a;");
                case "Lcom/laurencedawson/reddit_sync/singleton/a;->h()Ljava/lang/String;": return ME;
                case SELECTION + "->t4(" + ITEM + ")" + ITEM: {
                    DexRunner.SyncObject item = (DexRunner.SyncObject) a[1];
                    Object highlight = item.fields.get("q");
                    items.add(item.fields.get("b") + "|" + item.fields.get("a") + "|" + (highlight != null && !Integer.valueOf(0).equals(highlight)));
                    return item;
                }
                case "Lj9/a;->a()Lbd/b;": return new DexRunner.SyncObject("Lbd/b;");
                case "Lh7/d0;-><init>(Ljava/lang/String;Z)V": events.add("event " + a[1] + " " + a[2]); return null;
                case "Lbd/b;->i(Ljava/lang/Object;)V": events.add("post"); return null;
                case "Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V": events.add("dismiss"); return null;
                case "Lorg/apache/commons/lang3/StringUtils;->equalsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] == null ? (a[1] == null ? 1 : 0) : a[1] != null && a[0].toString().equalsIgnoreCase(a[1].toString()) ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->containsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().toLowerCase(Locale.ROOT).contains(a[1].toString().toLowerCase(Locale.ROOT)) ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->trim(Ljava/lang/String;)Ljava/lang/String;":
                    return a[0] == null ? null : ((String) a[0]).trim();
            }
            // Sync helpers the model only names; the real APK runs Sync's own.
            if (ref.equals("Ld7/d;->t(Ljava/lang/String;)I") && model("Ld7/d;")) return ((String) a[0]).length();
            if (ref.equals("Ld7/d;->g(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") && model("Ld7/d;"))
                return "user###" + a[1] + "###" + ((String) a[0]).trim() + "###null";
            return DexRunner.PASS;
        }

        /** Runs A4 for a profile sheet; the items it listed. */
        List<String> list(String profile) {
            user = profile;
            items.clear();
            run.run(method(context, SHEET, "A4", List.of()), new Object[] {new DexRunner.SyncObject(SHEET)});
            return new ArrayList<>(items);
        }

        /** Runs r0 for an item with this title; what Sync did. */
        List<String> choose(String profile, String title) {
            user = profile;
            events.clear();
            DexRunner.SyncObject item = new DexRunner.SyncObject(ITEM);
            item.fields.put("b", title);
            run.run(method(context, SHEET, "r0", List.of(ITEM)), new Object[] {new DexRunner.SyncObject(SHEET), item});
            return new ArrayList<>(events);
        }
    }

    // ---- The sheet's items ----

    private static void rows(Sync stock, Sync patched) throws ReflectiveOperationException {
        List<String> theirs = stock.list(USER), mine = stock.list(ME);
        equal(3, theirs.size(), "stock: Overview, Comments, Submitted on someone else's profile: " + theirs);
        check(mine.size() > theirs.size() && mine.subList(0, 3).equals(theirs), "stock: more sections on your own profile: " + mine);
        check(theirs.stream().noneMatch(i -> i.contains("Arctic Shift")), "stock has no archive row");

        List<String> expected = new ArrayList<>(theirs);
        expected.add(SHOW + "|" + ICON + "|false");
        equal(expected, patched.list(USER), "patched: Sync's sections, then the archive row");
        expected = new ArrayList<>(mine);
        expected.add(SHOW + "|" + ICON + "|false");
        equal(expected, patched.list(ME), "patched own profile: Sync's sections, then the archive row");
        equal(theirs, patched.list("not a user"), "no row for a name the archive cannot be asked for");
        check(TOASTS.isEmpty(), "listing shows no message: " + TOASTS);

        // While the archive is on for this user: the row turns it off and is highlighted, like a chosen item.
        helper("section", USER, SHOW);
        List<String> on = new ArrayList<>(theirs);
        on.add(HIDE + "|" + ICON + "|true");
        equal(on, patched.list(USER.toLowerCase(Locale.ROOT)), "on: the row offers Reddit's profile, highlighted");
        expected = new ArrayList<>(mine);
        expected.add(SHOW + "|" + ICON + "|false");
        equal(expected, patched.list(ME), "on for one user only");
        helper("reset");
        TOASTS.clear();
    }

    // ---- Choosing an item ----

    private static void choices(Sync stock, Sync patched) throws ReflectiveOperationException {
        // Sync's own sections: the same event, posted and the sheet closed, archive untouched.
        for (String section : List.of("Overview", "Comments", "Submitted", "Upvoted")) {
            List<String> stockEvents = stock.choose(USER, section);
            equal(List.of("event user###" + section + "###" + USER + "###null 0", "post", "dismiss"), stockEvents,
                    "stock opens " + section);
            equal(stockEvents, patched.choose(USER, section), "patched opens " + section + " the same");
        }
        equal(SHOW, helper("archiveRow", USER), "sections leave the archive off");

        // The archive row: turned on, the section Sync last loaded is loaded again.
        response("https://oauth.reddit.com/user/" + USER.toLowerCase(Locale.ROOT) + "/comments.json?sort=new&limit=25");
        equal(List.of("event user###Comments###" + USER + "###null 0", "post", "dismiss"), patched.choose(USER, SHOW),
                "archive row on: Comments reloaded, sheet closed");
        equal(HIDE, helper("archiveRow", USER), "the archive is on for the user");
        check(TOASTS.isEmpty(), "the archived page says where it comes from, not the tap: " + TOASTS);
        equal(HIDE + "|" + ICON + "|true", patched.list(USER).get(3), "reopened sheet offers Reddit's profile");

        // Its listings now come from the archive even with items from Reddit; that is ArchivedProfileTest's
        // and ArchivedProfilePatchTest's to check against a loopback archive.

        // Turned off from Upvoted: Overview (the archive has no Upvoted) and Reddit's profile is back.
        response("https://oauth.reddit.com/user/" + USER + "/upvoted.json?sort=new");
        equal(List.of("event user###Overview###" + USER + "###null 0", "post", "dismiss"), patched.choose(USER, HIDE),
                "archive row off: Overview reloaded");
        equal(List.of("Showing this profile from Reddit."), TOASTS, "says Reddit's profile is back");
        equal(SHOW, helper("archiveRow", USER), "the archive is off again");

        // A sheet with no usable username: Sync's Overview, nothing turned on.
        TOASTS.clear();
        equal(List.of("event user###Overview###bad name###null 0", "post", "dismiss"), patched.choose("bad name", SHOW),
                "invalid username: Overview, nothing turned on");
        equal(null, helper("archiveRow", "bad name"), "still no row for it");
        check(TOASTS.isEmpty(), "no message: " + TOASTS);
        helper("reset");
    }

    // ---- The helper, as the patched code calls it ----

    private static Object helper(String name, Object... args) throws ReflectiveOperationException {
        Class<?> helper = Class.forName("app.revanced.extension.synccompat.ArchivedProfile");
        for (Method method : helper.getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(null, args);
            }
        throw new AssertionError("Missing helper " + name);
    }

    /** Sync loaded this profile listing; Reddit answered with items, the archive is off. */
    private static void response(String url) throws ReflectiveOperationException {
        NetworkResponse reddit = new NetworkResponse(200, "{\"data\":{\"children\":[{\"kind\":\"t3\",\"data\":{}}]}}"
                .getBytes(StandardCharsets.UTF_8), false, 1, List.of(new Header("Content-Type", "application/json")));
        Class<?> helper = Class.forName("app.revanced.extension.synccompat.ArchivedProfile");
        Object result = helper.getMethod("response", String.class, NetworkResponse.class).invoke(null, url, reddit);
        check(result == reddit, "Reddit's answer kept while the archive is off: " + url);
    }

    private static void setPlatform() throws ReflectiveOperationException {
        Class<?> helper = Class.forName("app.revanced.extension.synccompat.ArchivedProfile");
        Class<?> type = Class.forName("app.revanced.extension.synccompat.ArchivedProfile$Platform");
        Object platform = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "toast": TOASTS.add((String) args[0]); return null;
                case "icon": return ICON;
                case "hashCode": return System.identityHashCode(self);
                case "equals": return self == args[0];
                default: return "platform";
            }
        });
        Field field = helper.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
        helper("reset");
        TOASTS.clear();
    }

    // ---- Hook structure and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MutableMethod method : context.proxy(context.getClassDefs().get(SHEET)).getMutableClass().getMethods())
            result.put(DexRunner.signature(method), method.getImplementation() == null ? List.of() : keys(method));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        String list = SHEET + "->A4()V", choose = SHEET + "->r0(" + ITEM + ")V";
        equal(before.keySet(), after.keySet(), "no method added or removed");
        for (String method : before.keySet())
            if (!method.equals(list) && !method.equals(choose)) equal(before.get(method), after.get(method), "unchanged: " + method);

        List<String> expected = new ArrayList<>(before.get(list));
        expected.remove(expected.size() - 1);
        expected.addAll(List.of("INVOKE_VIRTUAL:Ls9/f;->Y3()Ljava/lang/String;", "MOVE_RESULT_OBJECT:",
                "INVOKE_STATIC:" + HELPER + "->archiveRow(Ljava/lang/String;)Ljava/lang/String;", "MOVE_RESULT_OBJECT:", "IF_EQZ:",
                "NEW_INSTANCE:" + ITEM, "INVOKE_DIRECT:" + ITEM + "-><init>(Ljava/lang/String;)V",
                "INVOKE_STATIC:" + HELPER + "->archiveShown(Ljava/lang/String;)Z", "MOVE_RESULT:", "IPUT_BOOLEAN:" + ITEM + "->q:Z",
                "INVOKE_STATIC:" + HELPER + "->archiveIcon()I", "MOVE_RESULT:", "IPUT:" + ITEM + "->a:I",
                "INVOKE_VIRTUAL:" + SELECTION + "->t4(" + ITEM + ")" + ITEM, "RETURN_VOID:"));
        equal(expected, after.get(list), "items: Sync's own, then the archive row before the only return");

        expected = new ArrayList<>(before.get(choose));
        int read = expected.indexOf("IGET_OBJECT:" + ITEM + "->b:Ljava/lang/String;");
        check(read > 0, "r0 reads the item's title");
        expected.addAll(read + 1, List.of("INVOKE_STATIC:" + HELPER + "->section(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "MOVE_RESULT_OBJECT:"));
        equal(expected, after.get(choose), "choice: the title passes through the helper, the rest unchanged");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already offer"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A sheet that no longer lists Submitted is not the layout the patch knows: no edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod items = method(other, SHEET, "A4", List.of());
        List<Instruction> body = new ArrayList<>(items.getImplementation().getInstructions());
        int found = 0;
        for (int i = 0; i < body.size(); i++) if ("Submitted".equals(DexRunner.string(body.get(i)))) {
            items.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(i)).getRegisterA(), new ImmutableStringReference("Posts")));
            found++;
        }
        check(found > 0, "Submitted found");
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("profile section list"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");

        // A choice that no longer builds the listing from the title right away: no edit.
        BytecodePatchContext moved = context(source, scratch, "moved");
        MutableMethod chosen = method(moved, SHEET, "r0", List.of(ITEM));
        List<Instruction> code = new ArrayList<>(chosen.getImplementation().getInstructions());
        int read = -1;
        for (int i = 0; i < code.size(); i++)
            if (code.get(i).getOpcode() == Opcode.IGET_OBJECT && reference(code.get(i)).endsWith("->b:Ljava/lang/String;")) read = i;
        check(read > 0, "title read found");
        chosen.getImplementation().addInstruction(read + 1, new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x(Opcode.NOP));
        unchanged = snapshot(moved);
        try { apply(moved); throw new AssertionError("Unknown choice layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("profile section choice"), "unknown choice rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(moved), "rejection makes no edits");
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
        SyncCompatPatchesKt.getShowSyncProfilesFromArcticShiftOnDemand().getApply$patcher().invoke(context, resources);
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
