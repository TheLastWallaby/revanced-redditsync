import android.content.Context;
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
import com.android.volley.AuthFailureError;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.ServerError;
import com.android.volley.VolleyError;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: hand off retired messaging to Reddit Chat" and runs Sync's messaging code before
 * and after it: the new-message parser (OAuthMessageRequest, y8.z) and the editor's error listener
 * (EditFragment$d), and the reply request's parser, network error handler and failure notification
 * (OAuthCommentRequest, y8.e), on hand-written models or, given a local stock Sync 23033 APK, on the
 * real methods. Android and Sync's app services are answered by the suite, which records toasts,
 * dialogs, drafts and the notification Sync builds.
 *
 * Reddit's RESTRICTED_TO_PM answer: a new message shows the Chat dialog instead of Sync's toast
 * (the editor stays open); a message reply is saved as a draft and its notification says Reddit now
 * uses Chat, with Chat and archive actions instead of Retry. Everything else is exactly as before:
 * sent messages, other Reddit errors, comment replies, successful replies, network errors, 401/5xx.
 */
public final class MessagingHandoffPatchTest {
    private static final String HANDOFF = "Lapp/revanced/extension/synccompat/MessagingHandoff;";
    private static final String COMPOSE = "Ly8/z;";
    private static final String COMMENT = "Ly8/e;";
    private static final String LISTENER = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;";
    private static final String NETWORK = "Lcom/android/volley/NetworkResponse;";
    private static final String VOLLEY_ERROR = "Lcom/android/volley/VolleyError;";
    private static final String BUILDER = "Landroidx/core/app/g$e;";
    private static final String RETIRED = "{\"json\": {\"errors\": [[\"RESTRICTED_TO_PM\", "
            + "\"User doesn't accept direct messages. Try sending a chat request instead.\", \"to\"]]}}";
    private static final String SENT = "{\"json\": {\"errors\": []}}";
    private static final String NO_USER = "{\"json\": {\"errors\": [[\"USER_DOESNT_EXIST\", \"that user doesn't exist\", \"to\"]]}}";
    private static final String REPLIED = "{\"json\": {\"errors\": [], \"data\": {\"things\": [{\"kind\": \"t4\", \"data\": {\"id\": \"2abc\"}}]}}}";
    private static final String HANDOFF_TEXT = "Reddit now sends direct messages through Reddit Chat. Old private messages are "
            + "read-only. Your reply was saved as a draft.";
    private static boolean real;
    private static int checks;
    private static Context appContext;
    /** What the phone was asked to do, in order. */
    private static final List<String> events = new ArrayList<>();

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-messaging-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/messaging"), "assemble Sync models");
            real = args.length != 0;
            File source = real ? Path.of(args[0]).toFile() : dex.toFile();
            appContext = allocate(android.content.ContextWrapper.class);
            installPlatform();

            BytecodePatchContext stock = context(source, scratch, "stock");
            BytecodePatchContext patched = context(source, scratch, "patched");
            Map<String, List<String>> before = snapshot(patched);
            apply(patched);
            hooks(patched, before);

            compose(stock, patched);
            replies(stock, patched);
            rejections(source, scratch);
            System.out.println("PASS: " + checks + " messaging handoff patch checks (new message, reply, network error and "
                    + "notification; " + (real ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- New message ----

    private static void compose(BytecodePatchContext stock, BytecodePatchContext patched) throws Throwable {
        // Parser: Reddit's refusal becomes the handoff error; other answers are parsed by Sync.
        Object stockRetired = composeParse(stock, RETIRED), patchedRetired = composeParse(patched, RETIRED);
        equal("error User doesn't accept direct messages. Try sending a chat request instead.", stockRetired, "stock: Reddit's text");
        check(patchedRetired instanceof com.android.volley.Response
                && ((com.android.volley.Response<?>) patchedRetired).error.getClass().getSimpleName().equals("RetiredRouteError")
                && "User doesn't accept direct messages. Try sending a chat request instead.".equals(
                        ((com.android.volley.Response<?>) patchedRetired).error.getMessage()), "patched: handoff error");
        for (String body : List.of(SENT, NO_USER, "{\"json\": {\"errors\": [[\"RATELIMIT\", \"Take a break.\", \"ratelimit\"]]}}", "not json"))
            equal(composeParse(stock, body), composeParse(patched, body), "same parse for " + body);
        // Sync's Boolean.TRUE, as the runner holds booleans.
        equal("success 1", composeParse(patched, SENT), "sent message");

        // Listener: the handoff error shows the dialog instead of the toast; the editor is not closed.
        VolleyError handoff = ((com.android.volley.Response<?>) patchedRetired).error;
        equal(List.of("dismiss", "toast User doesn't accept direct messages. Try sending a chat request instead."),
                listen(stock, new VolleyError(stockRetired.toString().substring(6))), "stock toasts Reddit's text");
        equal(List.of("dismiss", "dialog Message not sent | Reddit now sends direct messages through Reddit Chat. "
                + "Your message is still here, so you can copy it into a chat."), listen(patched, handoff), "patched shows the handoff");
        NetworkResponse retiredStatus = response(403, RETIRED);
        check(listen(patched, new AuthFailureError(retiredStatus)).get(1).startsWith("dialog Message not sent"),
                "the refusal with an error status also hands off");
        for (VolleyError error : List.of(new VolleyError("that user doesn't exist"), new VolleyError("care to try these again?"),
                new NoConnectionError(), new AuthFailureError(response(401, "{\"error\": 401}")),
                new ServerError(response(503, "")), new VolleyError(new com.android.volley.ClientError(response(429, "{}")))))
            equal(listen(stock, error), listen(patched, error), "Sync's own handling for " + error);
    }

    private static Object composeParse(BytecodePatchContext context, String body) {
        Object result = runner(context).run(method(context, COMPOSE, "parseNetworkResponse", List.of(NETWORK)),
                new Object[] {new DexRunner.SyncObject(COMPOSE), response(200, body)});
        return result;
    }

    private static List<String> listen(BytecodePatchContext context, VolleyError error) {
        events.clear();
        DexRunner.SyncObject listener = new DexRunner.SyncObject(LISTENER);
        listener.fields.put("a", new DexRunner.SyncObject("Landroid/app/ProgressDialog;"));
        listener.fields.put("b", new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;"));
        runner(context).run(method(context, LISTENER, "onErrorResponse", List.of(VOLLEY_ERROR)), new Object[] {listener, error});
        return new ArrayList<>(events);
    }

    // ---- Replies ----

    private static void replies(BytecodePatchContext stock, BytecodePatchContext patched) {
        // A message reply (type 2) refused as a retired route.
        equal(List.of("notify Error submitting message | User doesn't accept direct messages. Try sending a chat request instead. "
                + "| Retry"), reply(stock, 2, RETIRED), "stock: Sync's notification with Reddit's text and Retry");
        equal(List.of("draft My reply", "toast " + HANDOFF_TEXT, "actions", "notify Error submitting message | " + HANDOFF_TEXT),
                reply(patched, 2, RETIRED), "patched: draft, toast, Chat actions, no Retry");
        // Comment replies (0, 1), edits (3), successful replies and other Reddit errors: unchanged.
        for (int type : new int[] {0, 1, 3})
            equal(reply(stock, type, RETIRED), reply(patched, type, RETIRED), "type " + type + " unchanged");
        for (String body : List.of(REPLIED, SENT, NO_USER, "{\"json\": {\"errors\": [[\"DELETED_COMMENT\", \"that comment has been deleted\", \"parent\"]]}}"))
            equal(reply(stock, 2, body), reply(patched, 2, body), "reply answer unchanged: " + body);
        check(reply(patched, 2, REPLIED).isEmpty(), "a sent reply posts no notification");

        // Error statuses: the refusal hands off; network failures, 401 (token refresh), 429 and 5xx don't.
        equal(List.of("notify Error submitting message | Could not connect to Reddit | Retry"),
                replyError(stock, 2, new AuthFailureError(response(403, RETIRED))), "stock network error report");
        equal(List.of("draft My reply", "toast " + HANDOFF_TEXT, "actions", "notify Error submitting message | " + HANDOFF_TEXT),
                replyError(patched, 2, new AuthFailureError(response(403, RETIRED))), "patched: refusal with an error status");
        for (VolleyError error : List.of(new NoConnectionError(), new AuthFailureError(response(401, "{\"error\": 401}")),
                new com.android.volley.ClientError(response(429, "{}")), new ServerError(response(500, "<html></html>"))))
            equal(replyError(stock, 2, error), replyError(patched, 2, error), "Sync's report for " + error);
        equal(replyError(stock, 1, new AuthFailureError(response(403, RETIRED))),
                replyError(patched, 1, new AuthFailureError(response(403, RETIRED))), "comment reply error unchanged");
    }

    private static DexRunner.SyncObject request(int type) {
        DexRunner.SyncObject request = new DexRunner.SyncObject(COMMENT);
        request.fields.put("u", type);
        request.fields.put("x", "My reply");
        request.fields.put("c", appContext);
        request.fields.put("t", "cursor");
        return request;
    }

    private static List<String> reply(BytecodePatchContext context, int type, String body) {
        events.clear();
        runner(context).run(method(context, COMMENT, "parseNetworkResponse", List.of(NETWORK)),
                new Object[] {request(type), response(200, body)});
        return new ArrayList<>(events);
    }

    private static List<String> replyError(BytecodePatchContext context, int type, VolleyError error) {
        events.clear();
        runner(context).run(method(context, COMMENT, "parseNetworkError", List.of(VOLLEY_ERROR)), new Object[] {request(type), error});
        return new ArrayList<>(events);
    }

    // ---- Stand-ins ----

    private static DexRunner runner(BytecodePatchContext context) {
        Map<Object, String> errors = new IdentityHashMap<>();
        Map<Object, List<String>> builders = new IdentityHashMap<>();
        DexRunner run = new DexRunner(context, (ref, a) -> {
            switch (ref) {
                case "Lcom/android/volley/VolleyError;-><init>(Ljava/lang/String;)V": errors.put(a[0], (String) a[1]); return null;
                case "Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V": errors.put(a[0], "parse"); return null;
                case "Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;":
                    return "error " + errors.get(a[0]);
                case "Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;":
                    return "cache";
                case "Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;":
                    return "success " + (a[0] == null || Integer.valueOf(0).equals(a[0]) ? "void" : a[0]);
                case "Lx8/a;->parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;": return a[1];
                case "Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;": return appContext;
                case "Landroid/app/ProgressDialog;->dismiss()V": events.add("dismiss"); return null;
                case "Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;":
                    events.add("toast " + a[1]); return new DexRunner.SyncObject("Landroid/widget/Toast;");
                case "Landroid/widget/Toast;->show()V": return null;
                case "Lgb/p;->d(Ljava/lang/String;)V": events.add("log " + a[0]); return null;
                case "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z":
                    return a[0] == null || a[0].toString().isEmpty() ? 1 : 0;
                case "Lf9/a;->d(Ljava/lang/String;)V": return null;
                case "Lt7/t;->a()Ljava/lang/String;": return "channel";
                case "Lt7/q;->b()I": return 0;
                case "Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;":
                    return new DexRunner.SyncObject("Landroid/app/NotificationManager;");
                case "Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V": {
                    List<String> parts = builders.get(((DexRunner.SyncObject) a[2]).fields.get("test.builder"));
                    events.add("notify " + String.join(" | ", parts));
                    return null;
                }
                case "Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V": return null;
                case "Landroid/app/PendingIntent;->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;":
                    return new DexRunner.SyncObject("Landroid/app/PendingIntent;");
                default:
            }
            if (ref.startsWith("Landroid/content/Intent;->putExtra(")) return a[0];
            if (ref.startsWith(BUILDER)) {
                Object builder = a[0];
                List<String> parts = builders.computeIfAbsent(builder, b -> new ArrayList<>());
                if (ref.contains("-><init>")) return null;
                if (ref.contains("->m(Ljava/lang/CharSequence;)")) parts.add(0, a[1].toString());
                else if (ref.contains("->l(Ljava/lang/CharSequence;)")) parts.add(a[1].toString());
                else if (ref.contains("->a(ILjava/lang/CharSequence;Landroid/app/PendingIntent;)")) parts.add(a[2].toString());
                else if (ref.contains("->c()Landroid/app/Notification;")) {
                    DexRunner.SyncObject notification = new DexRunner.SyncObject("Landroid/app/Notification;");
                    notification.fields.put("test.builder", builder);
                    return notification;
                }
                return builder;
            }
            return DexRunner.PASS;
        }, HANDOFF, HANDOFF.replace(";", "$RetiredRouteError;"));
        return run;
    }

    /** The handoff's view of the phone, recording into events. */
    private static void installPlatform() throws ReflectiveOperationException {
        Class<?> handoff = Class.forName("app.revanced.extension.synccompat.MessagingHandoff");
        Class<?> platform = Class.forName("app.revanced.extension.synccompat.MessagingHandoff$Platform");
        Object recorder = Proxy.newProxyInstance(platform.getClassLoader(), new Class<?>[] {platform}, (proxy, call, a) -> {
            switch (call.getName()) {
                case "main": ((Runnable) a[0]).run(); return null;
                case "toast": events.add("toast " + a[1]); return null;
                case "saveDraft": events.add("draft " + a[1]); return null;
                case "composeDialog": events.add("dialog " + a[1] + " | " + a[2]); return null;
                case "addActions": events.add("actions"); return null;
                default: throw new AssertionError(call);
            }
        });
        Field field = handoff.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, recorder);
    }

    private static NetworkResponse response(int status, String body) {
        return new NetworkResponse(status, body.getBytes(StandardCharsets.UTF_8), false, 0,
                List.of(new Header("Content-Type", "application/json")));
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    // ---- Hooks ----

    private static final List<String[]> METHODS = List.of(
            new String[] {COMPOSE, "parseNetworkResponse", NETWORK},
            new String[] {LISTENER, "onErrorResponse", VOLLEY_ERROR},
            new String[] {COMMENT, "parseNetworkResponse", NETWORK},
            new String[] {COMMENT, "parseNetworkError", VOLLEY_ERROR},
            new String[] {COMMENT, "d", "Ljava/lang/String;", "Z"});

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String[] m : METHODS) result.put(m[0] + m[1], keys(method(context, m[0], m[1], List.of(m).subList(2, m.length))));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        Map<String, List<String>> added = Map.of(
                COMPOSE + "parseNetworkResponse", List.of("INVOKE_STATIC:" + HANDOFF + "->composeResponse(" + NETWORK + ")Lcom/android/volley/Response;",
                        "MOVE_RESULT_OBJECT:", "IF_EQZ:", "RETURN_OBJECT:"),
                LISTENER + "onErrorResponse", List.of("IGET_OBJECT:" + LISTENER + "->b:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;",
                        "INVOKE_VIRTUAL:Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;", "MOVE_RESULT_OBJECT:",
                        "INVOKE_STATIC:" + HANDOFF + "->composeFailed(Landroid/content/Context;" + VOLLEY_ERROR + ")Z", "MOVE_RESULT:",
                        "IF_EQZ:", "RETURN_VOID:"),
                COMMENT + "parseNetworkResponse", List.of("IGET:" + COMMENT + "->u:I",
                        "INVOKE_STATIC:" + HANDOFF + "->replyResponse(Ljava/lang/Object;ILjava/lang/String;)V"),
                COMMENT + "parseNetworkError", List.of("IGET:" + COMMENT + "->u:I",
                        "INVOKE_STATIC:" + HANDOFF + "->replyError(Ljava/lang/Object;I" + VOLLEY_ERROR + ")V"),
                COMMENT + "d", List.of("IGET_OBJECT:Lx8/a;->c:Landroid/content/Context;",
                        "INVOKE_STATIC:" + HANDOFF + "->replyActions(Ljava/lang/Object;Ljava/lang/Object;Landroid/content/Context;)V",
                        "IGET_OBJECT:" + COMMENT + "->x:Ljava/lang/String;", "IGET_OBJECT:Lx8/a;->c:Landroid/content/Context;",
                        "INVOKE_STATIC:" + HANDOFF + "->replyNotice(Ljava/lang/Object;Ljava/lang/String;Landroid/content/Context;)Ljava/lang/String;",
                        "MOVE_RESULT_OBJECT:", "IF_EQZ:", "MOVE_OBJECT:", "CONST_4:"));
        for (Map.Entry<String, List<String>> entry : before.entrySet()) {
            List<String> was = new ArrayList<>(entry.getValue()), now = new ArrayList<>(after.get(entry.getKey()));
            List<String> gained = new ArrayList<>(now);
            for (String line : was) gained.remove(line);
            List<String> expected = new ArrayList<>(added.get(entry.getKey()));
            Collections.sort(gained);
            Collections.sort(expected);
            equal(expected, gained, entry.getKey() + " gains only the hook");
            equal(was.size() + added.get(entry.getKey()).size(), now.size(), entry.getKey() + " keeps Sync's instructions");
        }
        // Placement: the reply hook sits right before Sync's report of Reddit's errors; the network error
        // hook right after Sync's base handler; the notification actions before the Retry decision.
        List<Instruction> parse = code(method(context, COMMENT, "parseNetworkResponse", List.of(NETWORK)));
        int hook = index(parse, HANDOFF + "->replyResponse(Ljava/lang/Object;ILjava/lang/String;)V");
        equal(COMMENT + "->d(Ljava/lang/String;Z)V", reference(parse.get(hook + 1)), "reply hook before Sync's report");
        List<Instruction> network = code(method(context, COMMENT, "parseNetworkError", List.of(VOLLEY_ERROR)));
        int after2 = index(network, HANDOFF + "->replyError(Ljava/lang/Object;I" + VOLLEY_ERROR + ")V");
        equal("Lx8/a;->parseNetworkError(" + VOLLEY_ERROR + ")" + VOLLEY_ERROR, reference(network.get(after2 - 2)), "after Sync's base handler");
        List<Instruction> notice = code(method(context, COMMENT, "d", List.of("Ljava/lang/String;", "Z")));
        int actions = index(notice, HANDOFF + "->replyActions(Ljava/lang/Object;Ljava/lang/Object;Landroid/content/Context;)V");
        equal(BUILDER + "->i(Ljava/lang/String;)" + BUILDER, reference(notice.get(actions - 2)), "actions after the channel");
        equal(Opcode.IF_EQZ, notice.get(actions + 1).getOpcode(), "before the Retry decision");
        equal(HANDOFF + "->replyNotice(Ljava/lang/Object;Ljava/lang/String;Landroid/content/Context;)Ljava/lang/String;",
                reference(notice.get(2)), "notice decided first");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // A failure notification without Sync's Retry text fails before any edit (all five methods unchanged).
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod d = method(other, COMMENT, "d", List.of("Ljava/lang/String;", "Z"));
        List<Instruction> body = code(d);
        for (int i = 0; i < body.size(); i++) {
            if (!"Retry".equals(DexRunner.string(body.get(i)))) continue;
            d.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(i)).getRegisterA(), new ImmutableStringReference("Again")));
        }
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown notification accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("failure notification"), "unknown layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(other), "rejection makes no edits");

        // A compose parser that no longer starts by writing v0 fails before any edit.
        BytecodePatchContext shifted = context(source, scratch, "shifted");
        MutableMethod parse = method(shifted, COMPOSE, "parseNetworkResponse", List.of(NETWORK));
        parse.getImplementation().addInstruction(0, new BuilderInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference("x")));
        unchanged = snapshot(shifted);
        try { apply(shifted); throw new AssertionError("Shifted compose parser accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("message send request"), "compose layout rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(shifted), "compose rejection makes no edits");
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
        SyncCompatPatchesKt.getHandOffSyncRetiredMessaging().getApply$patcher().invoke(context, resources);
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
