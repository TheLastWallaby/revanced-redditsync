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
import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: start chats without a subject" and runs Sync's message editor before and after it:
 * the new-message send (EditFragment.f4) and the new-message fields (EditFragment.b4), on a
 * hand-written model or, given a local stock Sync 23033 APK, on the real methods. Android and Sync's
 * app services are answered by the suite, which records what the editor does.
 *
 * A message to a user without a subject is sent with a subject made from its first line (Sync asked
 * for a subject); the Subject field is handed to ChatCompose.bind, which hides it on the phone.
 * Everything else is exactly as before: entered subjects, subreddit recipients, a missing recipient,
 * an invalid message and replies.
 */
public final class ChatComposePatchTest {
    private static final String CHAT = "Lapp/revanced/extension/synccompat/ChatCompose;";
    private static final String EDITOR = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;";
    private static final String FIELD = "Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;";
    private static final String EDITOR_VIEW = "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;";
    private static final String SUBJECT_CALL = CHAT + "->subject(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String BIND_CALL = CHAT + "->bind(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String TEXT = "Hi there, are you selling the bike?\n\nIt's the blue one.";
    private static boolean real;
    private static int checks;
    /** What the editor did, in order. */
    private static final List<String> events = new ArrayList<>();

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-chat-compose-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/chatcompose"), "assemble Sync model");
            real = args.length != 0;
            File source = real ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stock = context(source, scratch, "stock");
            BytecodePatchContext patched = context(source, scratch, "patched");
            Map<String, List<String>> before = snapshot(patched);
            apply(patched);
            hooks(patched, before);

            send(stock, patched);
            fields(stock, patched);
            rejections(source, scratch);
            System.out.println("PASS: " + checks + " chat compose patch checks (send and new-message fields; "
                    + (real ? "real Sync methods from the supplied stock APK)" : "hand-written Sync model)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Send ----

    private static void send(BytecodePatchContext stock, BytecodePatchContext patched) {
        // To a user without a subject: Sync refuses; the patch sends with the first line as subject.
        equal(List.of("error subject Enter a subject", "focus subject"), send(stock, "alice", "", TEXT, true),
                "stock asks for a subject");
        equal(List.of("progress Sending message", "request alice | Hi there, are you selling the bike? | " + TEXT, "send editor"),
                send(patched, "alice", "", TEXT, true), "patched sends with the first line as subject");
        equal(List.of("progress Sending message", "request u/alice | A heading | # A heading\nbody", "send editor"),
                send(patched, "u/alice", "", "# A heading\nbody", true), "Markdown heading marker left out");
        String longLine = "word ".repeat(40).trim();
        List<String> sent = send(patched, "alice", "", longLine, true);
        String subject = sent.get(1).split(" \\| ")[1];
        check(subject.length() <= 100 && subject.endsWith("…") && longLine.startsWith(subject.substring(0, subject.length() - 1)),
                "long first line shortened to Reddit's limit: " + subject);
        // A blank subject (Sync would send the spaces) is replaced too.
        equal(List.of("progress Sending message", "request alice | Hi there, are you selling the bike? | " + TEXT, "send editor"),
                send(patched, "alice", "   ", TEXT, true), "blank subject replaced");

        // Unchanged: an entered subject, subreddit recipients, no recipient, an invalid message.
        for (String[] c : new String[][] {{"alice", "Bike"}, {"r/bikes", ""}, {"/r/Bikes", ""}, {"R/bikes", "Question"},
                {"/r/bikes", "Question"}, {"", ""}, {"", "Bike"}}) {
            equal(send(stock, c[0], c[1], TEXT, true), send(patched, c[0], c[1], TEXT, true), "unchanged for to=" + c[0] + " subject=" + c[1]);
        }
        equal(List.of("progress Sending message", "request alice | Bike | " + TEXT, "send editor"),
                send(patched, "alice", "Bike", TEXT, true), "entered subject sent as typed");
        equal(List.of("error subject Enter a subject", "focus subject"), send(patched, "r/bikes", "", TEXT, true),
                "subreddit recipients still need a subject");
        equal(List.of("error recipient Enter a username", "focus recipient"), send(patched, "", "", TEXT, true),
                "a recipient is still required");
        equal(List.of("editor invalid"), send(patched, "alice", "", "", false), "an invalid message is still refused by Sync");
        equal(send(stock, "alice", "", "", false), send(patched, "alice", "", "", false), "invalid message unchanged");
    }

    private static List<String> send(BytecodePatchContext context, String to, String subject, String text, boolean valid) {
        events.clear();
        DexRunner.SyncObject editor = editor(text, valid);
        ((DexRunner.SyncObject) editor.fields.get("mUsernameInput")).fields.put("text", to);
        ((DexRunner.SyncObject) editor.fields.get("mSubjectInput")).fields.put("text", subject);
        runner(context).run(method(context, EDITOR, "f4", List.of("Ljava/lang/String;", "Ljava/lang/String;")),
                new Object[] {editor, "iden", "captcha"});
        return new ArrayList<>(events);
    }

    // ---- New-message fields ----

    private static void fields(BytecodePatchContext stock, BytecodePatchContext patched) {
        equal(List.of("visible recipient 0", "visible subject 0", "text recipient alice", "focus subject", "editor focus"),
                fields(stock, "alice", false), "stock shows both fields");
        equal(List.of("visible recipient 0", "visible subject 0", "bind recipient subject", "text recipient alice", "focus subject",
                "editor focus"), fields(patched, "alice", false), "patched hands both fields to ChatCompose before the recipient is filled");
        equal(List.of("visible recipient 0", "visible subject 0", "bind recipient subject", "focus recipient", "editor focus"),
                fields(patched, null, false), "no known recipient");
        equal(fields(stock, null, true), fields(patched, null, true), "reply preview unchanged");
        equal(List.of("preview parent text"), fields(patched, null, true), "reply preview");
    }

    private static List<String> fields(BytecodePatchContext context, String recipient, boolean reply) {
        events.clear();
        DexRunner.SyncObject editor = editor(TEXT, true);
        editor.fields.put("L0", recipient);
        if (reply) editor.fields.put("K0", new DexRunner.SyncObject("Lxa/d;"));
        runner(context).run(method(context, EDITOR, "b4", List.of()), new Object[] {editor});
        return new ArrayList<>(events);
    }

    // ---- Stand-ins ----

    private static DexRunner.SyncObject editor(String text, boolean valid) {
        DexRunner.SyncObject editor = new DexRunner.SyncObject(EDITOR);
        DexRunner.SyncObject view = new DexRunner.SyncObject(EDITOR_VIEW);
        view.fields.put("text", text);
        view.fields.put("valid", valid);
        editor.fields.put("mEditorView", view);
        for (String[] f : new String[][] {{"mUsernameInput", "recipient"}, {"mSubjectInput", "subject"}}) {
            DexRunner.SyncObject field = new DexRunner.SyncObject(FIELD);
            field.fields.put("name", f[1]);
            field.fields.put("text", "");
            editor.fields.put(f[0], field);
        }
        return editor;
    }

    private static String name(Object field) {
        return (String) ((DexRunner.SyncObject) field).fields.get("name");
    }

    private static DexRunner runner(BytecodePatchContext context) {
        return new DexRunner(context, (ref, a) -> {
            switch (ref) {
                case "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->e()Ljava/lang/String;":
                    return ((DexRunner.SyncObject) a[0]).fields.get("text");
                case "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->f()Z":
                    return (Boolean) ((DexRunner.SyncObject) a[0]).fields.get("valid") ? 1 : 0;
                case "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->j()V": events.add("editor invalid"); return null;
                case "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->s()V": events.add("editor focus"); return null;
                case "Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->t(Ljava/lang/String;)V": events.add("preview " + a[1]); return null;
                case "Lxa/d;->n()Ljava/lang/String;": return "parent text";
                case "Landroid/widget/EditText;->getText()Landroid/text/Editable;": return ((DexRunner.SyncObject) a[0]).fields.get("text");
                case "Landroid/widget/EditText;->setVisibility(I)V": events.add("visible " + name(a[0]) + " " + a[1]); return null;
                case "Landroid/widget/EditText;->setText(Ljava/lang/CharSequence;)V":
                    ((DexRunner.SyncObject) a[0]).fields.put("text", a[1]);
                    events.add("text " + name(a[0]) + " " + a[1]);
                    return null;
                case "Landroid/widget/EditText;->setError(Ljava/lang/CharSequence;)V": events.add("error " + name(a[0]) + " " + a[1]); return null;
                case "Landroid/widget/EditText;->requestFocus()Z": events.add("focus " + name(a[0])); return 1;
                case BIND_CALL: events.add("bind " + name(a[0]) + " " + name(a[1])); return null;
                case "Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;":
                    return new DexRunner.SyncObject("Landroidx/fragment/app/FragmentActivity;");
                case "Lgb/l;->a(Landroid/content/Context;)Landroid/app/ProgressDialog;": return new DexRunner.SyncObject("Landroid/app/ProgressDialog;");
                case "Landroid/app/ProgressDialog;->setMessage(Ljava/lang/CharSequence;)V": events.add("progress " + a[1]); return null;
                case "Landroid/app/ProgressDialog;->show()V": return null;
                case "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$c;-><init>(" + EDITOR + "Landroid/app/ProgressDialog;)V":
                case "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;-><init>(" + EDITOR + "Landroid/app/ProgressDialog;)V":
                    return null;
                case "Ly8/z;-><init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                        + "Ljava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V":
                    events.add("request " + a[2] + " | " + a[3] + " | " + a[4]);
                    return null;
                case EDITOR + "->S3()Ljava/lang/String;": return "editor";
                case "Lm8/a;->g(Landroid/content/Context;Lx8/a;Ljava/lang/String;)V": events.add("send " + a[2]); return null;
                default: return DexRunner.PASS;
            }
        }, CHAT);
    }

    // ---- Hooks ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("f4", keys(method(context, EDITOR, "f4", List.of("Ljava/lang/String;", "Ljava/lang/String;"))));
        result.put("b4", keys(method(context, EDITOR, "b4", List.of())));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        Map<String, List<String>> added = Map.of(
                "f4", List.of("INVOKE_STATIC:" + SUBJECT_CALL, "MOVE_RESULT_OBJECT:"),
                "b4", List.of("IGET_OBJECT:" + EDITOR + "->mUsernameInput:" + FIELD, "INVOKE_STATIC:" + BIND_CALL, "CONST_4:"));
        for (Map.Entry<String, List<String>> entry : before.entrySet()) {
            List<String> was = entry.getValue(), now = after.get(entry.getKey());
            List<String> gained = new ArrayList<>(now);
            for (String line : was) gained.remove(line);
            List<String> expected = new ArrayList<>(added.get(entry.getKey()));
            Collections.sort(gained);
            Collections.sort(expected);
            equal(expected, gained, entry.getKey() + " gains only the hook");
            equal(was.size() + added.get(entry.getKey()).size(), now.size(), entry.getKey() + " keeps Sync's instructions");
        }
        // Placement: the subject is replaced right before Sync's empty-subject check, in the register it
        // checks; the fields are bound right after the Subject field is shown, and v1 is zero again.
        List<Instruction> send = code(method(context, EDITOR, "f4", List.of("Ljava/lang/String;", "Ljava/lang/String;")));
        int hook = index(send, SUBJECT_CALL);
        FiveRegisterInstruction call = (FiveRegisterInstruction) send.get(hook);
        int subject = ((OneRegisterInstruction) send.get(hook + 1)).getRegisterA();
        equal(subject, call.getRegisterD(), "subject register passed and replaced");
        equal("Ljava/lang/Object;->toString()Ljava/lang/String;", reference(send.get(hook - 2)), "after the subject is read");
        equal(subject, ((OneRegisterInstruction) send.get(hook - 1)).getRegisterA(), "the subject Sync read");
        equal("Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z", reference(send.get(hook + 2)), "before Sync's check");
        equal(subject, ((FiveRegisterInstruction) send.get(hook + 2)).getRegisterC(), "Sync checks the replaced subject");

        List<Instruction> fields = code(method(context, EDITOR, "b4", List.of()));
        int bind = index(fields, BIND_CALL);
        equal("Landroid/widget/EditText;->setVisibility(I)V", reference(fields.get(bind - 2)), "after the field is shown");
        equal(EDITOR + "->mSubjectInput:" + FIELD, reference(fields.get(bind - 3)), "the Subject field");
        Instruction zero = fields.get(bind + 1);
        equal(Opcode.CONST_4, zero.getOpcode(), "visibility register restored");
        equal(0, ((NarrowLiteralInstruction) zero).getNarrowLiteral(), "to zero");
        equal(((FiveRegisterInstruction) fields.get(bind - 2)).getRegisterD(), ((OneRegisterInstruction) zero).getRegisterA(),
                "the register Sync's setVisibility used");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A send method without Sync's subject prompt fails before any edit (both methods unchanged).
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod f4 = method(other, EDITOR, "f4", List.of("Ljava/lang/String;", "Ljava/lang/String;"));
        List<Instruction> body = code(f4);
        for (int i = 0; i < body.size(); i++) {
            if (!"Enter a subject".equals(DexRunner.string(body.get(i)))) continue;
            f4.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(i)).getRegisterA(), new ImmutableStringReference("Subject?")));
        }
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown send layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("message editor send"), "send layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "send rejection makes no edits");

        // Fields that no longer zero the visibility register first fail before any edit.
        BytecodePatchContext shifted = context(source, scratch, "shifted");
        MutableMethod b4 = method(shifted, EDITOR, "b4", List.of());
        List<Instruction> fields = code(b4);
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getOpcode() != Opcode.CONST_4) continue;
            b4.getImplementation().replaceInstruction(i, new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n(
                    Opcode.CONST_4, ((OneRegisterInstruction) fields.get(i)).getRegisterA(), 4));
            break;
        }
        unchanged = snapshot(shifted);
        try { apply(shifted); throw new AssertionError("Changed field layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("new-message fields"), "fields layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(shifted), "fields rejection makes no edits");
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
        SyncCompatPatchesKt.getStartSyncChatsWithoutSubject().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod method(BytecodePatchContext context, String type, String name, List<String> parameters) {
        ClassDef clazz = context.getClassDefs().get(type);
        if (clazz == null) throw new AssertionError("Missing " + type);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().equals(parameters)) return method;
        throw new AssertionError("Missing " + type + "->" + name + parameters);
    }
    private static List<Instruction> code(MutableMethod method) {
        return new ArrayList<>(method.getImplementation().getInstructions());
    }
    private static int index(List<Instruction> code, String ref) {
        for (int i = 0; i < code.size(); i++) if (ref.equals(reference(code.get(i)))) return i;
        throw new AssertionError("Missing call " + ref);
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
