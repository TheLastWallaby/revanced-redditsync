import app.revanced.extension.synccompat.NotificationPermissionDevice;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: ask for notification permission" and resumes Sync's main screen (MainActivity)
 * before and after it: on hand-written models, or, given a local stock Sync 23033 APK, on the real
 * classes. Sync's inherited onResume is a stand-in that records it ran; the production helper runs
 * against a stand-in phone (API level, permission state, Sync's inbox check setting, preference
 * files).
 *
 * Before: MainActivity has no onResume, nothing on resume asks for POST_NOTIFICATIONS, and only
 * the onboarding requests it. After: MainActivity.onResume runs Sync's onResume first, then asks
 * once on Android 13+ with inbox checks on and the permission not granted; never with checks Off,
 * on Android 12 or earlier, when granted, or the second time. Nothing else changes.
 */
public final class NotificationPermissionPatchTest {
    private static final String ACTIVITIES = "Lcom/laurencedawson/reddit_sync/ui/activities/";
    private static final String MAIN = ACTIVITIES + "MainActivity;";
    private static final String HELPER = "Lapp/revanced/extension/synccompat/NotificationPermission;";
    private static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final List<String> CLASSES = List.of(MAIN, ACTIVITIES + "BaseMaterialActivity;", ACTIVITIES + "BaseActivity;",
            ACTIVITIES + "HomeActivity;", ACTIVITIES + "OnboardingActivity;", "Lz7/b;", "Lt7/z;");
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-notification-permission-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/notificationpermission"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            Set<String> requesters = requesters(patched.context);
            stock(stock, before, requesters);
            apply(patched.context);
            hook(before, snapshot(patched.context));
            equal(requesters, requesters(patched.context), "no Sync method gains a request");
            resumes(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " notification permission patch checks (main screen asks once on Android 13+; executed "
                    + (args.length != 0 ? "real Sync classes from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Stock Sync ----

    private static void stock(Sync stock, Map<String, List<String>> before, Set<String> requesters) {
        check(before.keySet().stream().noneMatch(k -> k.startsWith(MAIN + "->onResume")), "stock MainActivity has no onResume");
        for (String inherited : List.of(ACTIVITIES + "BaseMaterialActivity;->onResume[]V", ACTIVITIES + "BaseActivity;->onResume[]V")) {
            List<String> code = before.get(inherited);
            check(code != null && !code.isEmpty(), "stock " + inherited + " exists");
            check(code.stream().noneMatch(k -> k.contains("requestPermissions") || k.contains("checkSelfPermission")),
                    "stock " + inherited + " neither checks nor asks for a permission");
        }
        check(!requesters.isEmpty() && requesters.stream().allMatch(r -> r.startsWith(ACTIVITIES + "OnboardingActivity;")
                || r.startsWith("Lcom/laurencedawson/reddit_sync/ui/views/onboarding/")), "stock asks only in onboarding: " + requesters);
    }

    /** Sync methods that load the POST_NOTIFICATIONS name (where Sync builds its request). */
    private static Set<String> requesters(BytecodePatchContext context) {
        Set<String> result = new TreeSet<>();
        for (ClassDef clazz : context.getClassDefs()) {
            if (!clazz.getType().startsWith("Lcom/laurencedawson/")) continue;
            for (var m : clazz.getMethods()) {
                if (m.getImplementation() == null) continue;
                for (Instruction i : m.getImplementation().getInstructions())
                    if (PERMISSION.equals(DexRunner.string(i))) result.add(clazz.getType() + "->" + m.getName());
            }
        }
        return result;
    }

    // ---- Resumes ----

    private static void resumes(Sync stock, Sync patched) {
        // Stock: whatever the phone's state, resuming asks nothing.
        NotificationPermissionDevice phone = new NotificationPermissionDevice();
        equal(List.of("sync onResume"), stock.resume(phone), "stock resume");
        equal(List.of(), phone.events, "stock never checks or asks");

        // Patched: Sync's onResume first, then one request on Android 13 with checks on and not granted.
        String request = "request [" + PERMISSION + "] code=";
        phone = new NotificationPermissionDevice();
        List<String> first = patched.resume(phone);
        equal(2, first.size(), "Sync's resume, then the helper: " + first);
        equal("sync onResume", first.get(0), "Sync's onResume runs first");
        equal("helper", first.get(1), "then the helper, with the activity");
        equal(1, phone.requests().size(), "first resume asks");
        check(phone.requests().get(0).startsWith(request), "asks for POST_NOTIFICATIONS: " + phone.requests());
        for (int n = 0; n < 3; n++) equal(List.of("sync onResume", "helper"), patched.resume(phone), "later resume " + n);
        equal(1, phone.requests().size(), "asked once only");

        Object[][] none = {{"inbox checks Off", "0", 33, false}, {"Android 12L", "3", 32, false}, {"Android 12", "3", 31, false},
            {"Android 6", "3", 23, false}, {"already granted", "3", 33, true}};
        for (Object[] row : none) {
            NotificationPermissionDevice other = new NotificationPermissionDevice().inboxCheck((String) row[1]);
            other.sdk = (Integer) row[2];
            other.granted = (Boolean) row[3];
            equal(List.of("sync onResume", "helper"), patched.resume(other), row[0] + ": Sync's resume unchanged");
            equal(List.of(), other.requests(), row[0] + ": nothing asked");
        }
        NotificationPermissionDevice later = new NotificationPermissionDevice();
        later.sdk = 34;
        later.inboxCheck("2");
        patched.resume(later);
        equal(1, later.requests().size(), "Android 14, every 30 minutes: asks");
    }

    /** Stand-ins for Android's call into the activity and Sync's inherited onResume. */
    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();
        NotificationPermissionDevice phone;

        Sync(BytecodePatchContext context) {
            this.context = context;
            this.run = new DexRunner(context, this::intercept);
        }

        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;->onResume()V":
                    check(a[0] instanceof DexRunner.SyncObject && ((DexRunner.SyncObject) a[0]).type.equals(MAIN), "super call on the main screen");
                    events.add("sync onResume");
                    return null;
                case "Lapp/revanced/extension/synccompat/NotificationPermission;->onResume(Landroid/app/Activity;)V":
                    check(a[0] instanceof DexRunner.SyncObject && ((DexRunner.SyncObject) a[0]).type.equals(MAIN), "helper gets the main screen");
                    events.add("helper");
                    phone.resume();
                    return null;
                default: return DexRunner.PASS;
            }
        }

        /** Android's onResume call: MainActivity's own method if it has one, else the inherited one (a stand-in). */
        List<String> resume(NotificationPermissionDevice phone) {
            this.phone = phone;
            events.clear();
            MutableMethod own = null;
            for (MutableMethod m : context.proxy(context.getClassDefs().get(MAIN)).getMutableClass().getMethods())
                if (m.getName().equals("onResume") && m.getParameterTypes().isEmpty()) own = m;
            if (own == null) events.add("sync onResume");
            else run.run(own, new Object[] {new DexRunner.SyncObject(MAIN)});
            return new ArrayList<>(events);
        }
    }

    // ---- The hook and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String type : CLASSES) {
            ClassDef clazz = context.getClassDefs().get(type);
            if (clazz == null) throw new AssertionError("Missing " + type);
            result.put(type + " super", List.of(String.valueOf(clazz.getSuperclass()), "flags=" + clazz.getAccessFlags()));
            for (MutableMethod m : context.proxy(clazz).getMutableClass().getMethods())
                result.put(type + "->" + m.getName() + m.getParameterTypes() + m.getReturnType(),
                        m.getImplementation() == null ? List.of() : keys(m));
        }
        return result;
    }

    private static void hook(Map<String, List<String>> before, Map<String, List<String>> after) {
        String added = MAIN + "->onResume[]V";
        Map<String, List<String>> expected = new TreeMap<>(before);
        expected.put(added, List.of("registers=1",
                "INVOKE_SUPER:" + ACTIVITIES + "BaseMaterialActivity;->onResume()V",
                "INVOKE_STATIC:" + HELPER + "->onResume(Landroid/app/Activity;)V",
                "RETURN_VOID:"));
        equal(expected, after, "only MainActivity.onResume is added");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        MutableMethod resume = method(twice, MAIN, "onResume", List.of());
        check(AccessFlags.PUBLIC.isSet(resume.getAccessFlags()) && !AccessFlags.STATIC.isSet(resume.getAccessFlags())
                && !AccessFlags.FINAL.isSet(resume.getAccessFlags()), "added onResume is public, virtual and overridable");
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown layout (Sync's inbox check reads another setting) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod rule = method(other, "Lz7/b;", "g", List.of("Landroid/content/Context;"));
        List<Instruction> body = new ArrayList<>(rule.getImplementation().getInstructions());
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if ("message_preference_new".equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker >= 0, "inbox setting found");
        rule.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("message_preference_other")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("inbox check setting"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");
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
        SyncCompatPatchesKt.getAskSyncNotificationPermission().getApply$patcher().invoke(context, resources);
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
