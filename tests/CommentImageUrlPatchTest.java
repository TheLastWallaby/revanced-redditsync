import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22x;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.nio.file.*;
import java.util.*;

/** Executes the production edit to Sync's comment image URL preparation, offline. */
public final class CommentImageUrlPatchTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static MutableClassDef parser(BytecodePatchContext context) {
        return context.proxy(context.getClassDefs().get("Lc9/c;")).getMutableClass();
    }
    private static MutableMethod method(MutableClassDef parser) {
        return parser.getMethods().stream().filter(m -> m.getName().equals("a")).findFirst().orElseThrow();
    }
    private static List<? extends Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static int height(Method method) {
        var code = code(method);
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof ReferenceInstruction reference && reference.getReference() instanceof StringReference string
                    && string.getString().equals("&height=")) return i;
        }
        throw new AssertionError("Missing height suffix");
    }
    private static BytecodePatchContext context(Path source, Path scratch) {
        var context = new BytecodePatchContext(source.toFile(), scratch.toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("sync-comment-image-url");
        try {
            // Authored miniature model of the URL rewrite, not a copy of the base disassembly.
            Path fixture = scratch.resolve("Parser.smali");
            Files.writeString(fixture, """
                .class public Lc9/c;
                .super Ljava/lang/Object;
                .source "PostParserHelper.java"
                .method public static a(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;IZZLjava/lang/String;)V
                    .registers 37
                    move-object/from16 v2, p2
                    new-instance v4, Ljava/lang/StringBuilder;
                    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V
                    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    const-string v9, "&height="
                    invoke-virtual {v4, v9}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
                    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object v1
                    invoke-virtual {v14, v0, v1}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
                    move-result-object v14
                    return-void
                .end method
                """);
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, fixture.toString()), "assemble authored parser model");
            Path source = args.length == 0 ? dex : Path.of(args[0]);
            var context = context(source, scratch.resolve("patched"));
            var parser = parser(context);
            Method method = method(parser);
            String signed = "https://preview.redd.it/abc123.png?width=1874&format=png&auto=webp&s=0123456789abcdef";
            String body = "Before [image](" + signed + ") and " + signed + " after";
            equal(body.replace(signed, signed + "&height=917"), execute(method, body, signed, 917),
                    "original parser reproduces the reported signature corruption");

            var before = code(method);
            var tries = List.copyOf(method.getImplementation().getTryBlocks());
            int result = height(method) + 4;
            SyncCompatPatchesKt.preserveSyncCommentImageUrls(parser);
            var after = code(method);
            equal(before.size(), after.size(), "instruction count retained");
            for (int i = 0; i < before.size(); i++) {
                equal(before.get(i).getCodeUnits(), after.get(i).getCodeUnits(), "instruction width retained");
                if (i != result) check(before.get(i) == after.get(i), "all other instructions retained");
            }
            equal(tries, List.copyOf(method.getImplementation().getTryBlocks()), "try blocks retained");
            equal(Opcode.MOVE_OBJECT, after.get(result).getOpcode(), "original URL used as replacement");
            for (String url : new String[] {signed, signed + "#fragment", signed + "&height=917",
                    "https://preview.redd.it/a.png?height=917&width=1874&s=a%2Fb+%2B",
                    "https://preview.redd.it/a.png?s=A%26height%3D917&width=1874",
                    "https://external-preview.redd.it/a.webp?width=1874&s=CaseSensitive",
                    "https://i.redd.it/a.png", "https://example.invalid/a.png?height=917"}) {
                String text = "[image](" + url + ") " + url + " &lt;3 \\ $1";
                equal(text, execute(method, text, url, 917), "source URL and surrounding text preserved");
            }
            // rc.1 manually seeded this cache in its test and missed the selftext parser path.
            // Feed raw item JSON through the actual installed DEX entry call instead.
            SyncCompatPatchesKt.captureSyncInlineImageMetadata(parser);
            metadataFlow(method, "selftext", "postonly01");
            metadataFlow(method, "body", "replyonly02");
            executeMetadataHook(method, null);
            executeMetadataHook(method, new org.json.JSONObject().put("selftext", "plain text"));
            executeMetadataHook(method, new org.json.JSONObject().put("selftext", "plain text").put("media_metadata", "bad"));
            check(app.revanced.extension.synccompat.CommentMedia.inlineImageSize(signed, 1000, true) == null,
                    "missing or malformed metadata keeps the link fallback");
            DexPool.writeTo(scratch.resolve("roundtrip.dex").toString(), new ImmutableDexFile(Opcodes.getDefault(), List.of(parser)));
            check(DexFileFactory.loadDexFile(scratch.resolve("roundtrip.dex").toFile(), Opcodes.getDefault()).getClasses().size() == 1,
                    "patched DEX serializes and reloads");
            try { SyncCompatPatchesKt.preserveSyncCommentImageUrls(parser); throw new AssertionError("duplicate accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
            try { SyncCompatPatchesKt.captureSyncInlineImageMetadata(parser); throw new AssertionError("duplicate capture accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate capture rejected"); }

            var bad = parser(context(dex, scratch.resolve("malformed")));
            var badMethod = method(bad);
            badMethod.getImplementation().replaceInstruction(height(badMethod) + 4,
                    new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
            var unchanged = code(badMethod);
            try { SyncCompatPatchesKt.preserveSyncCommentImageUrls(bad); throw new AssertionError("bad result register accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("height result"), "unsupported layout rejected"); }
            equal(unchanged, code(badMethod), "layout rejection makes no edits");

            var badEntry = parser(context(dex, scratch.resolve("malformed-entry")));
            method(badEntry).getImplementation().replaceInstruction(0,
                    new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, 2, 30));
            var unchangedEntry = code(method(badEntry));
            try { SyncCompatPatchesKt.captureSyncInlineImageMetadata(badEntry); throw new AssertionError("wrong item parameter accepted"); }
            catch (PatchException expected) { check(expected.getMessage().contains("item parameter"), "unsupported item register rejected"); }
            equal(unchangedEntry, code(method(badEntry)), "entry rejection makes no edits");

            if (args.length != 0) {
                // Also prove the complete named patch installs this edit and the metadata hook.
                var full = context(source, scratch.resolve("full-patch"));
                var resources = new ResourcePatchContext(full.getApkFile$patcher(), full.getPatchedFilesPath$patcher(),
                        full.getPatchedFilesPath$patcher(), null, null);
                SyncCompatPatchesKt.getImproveSyncCommentMedia().getApply$patcher().invoke(full, resources);
                var fullMethod = method(parser(full));
                equal(body, execute(fullMethod, body, signed, 917), "complete named patch preserves URLs on the base");
                check(code(fullMethod).stream().anyMatch(i -> i instanceof ReferenceInstruction reference
                        && reference.getReference().toString().contains("CommentMedia;->rememberItemMetadata(")), "metadata hook installed");
                metadataFlow(fullMethod, "selftext", "fullpost03");
                metadataFlow(fullMethod, "body", "fullreply04");
            }
            System.out.println("PASS: " + checks + " comment image URL patch checks (executed DEX rewrite, "
                    + (args.length == 0 ? "authored model" : "verified base and complete named patch") + ")");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void metadataFlow(Method method, String field, String id) throws Exception {
        String url = "https://preview.redd.it/" + id + ".png?width=1874&format=png&auto=webp&s=Case%2BSensitive";
        String text = "Before [image](" + url + ") after";
        check(app.revanced.extension.synccompat.CommentMedia.inlineImageSize(url, 1000, true) == null,
                field + " without captured metadata reproduces rc.1's link-only fallback");
        org.json.JSONObject item = new org.json.JSONObject().put(field, text).put("media_metadata",
                new org.json.JSONObject().put(id, new org.json.JSONObject().put("id", id).put("status", "valid")
                        .put("e", "Image").put("m", "image/png")
                        .put("s", new org.json.JSONObject().put("x", 1874).put("y", 917).put("u", url))));
        executeMetadataHook(method, item);
        int[] box = app.revanced.extension.synccompat.CommentMedia.inlineImageSize(url, 1000, true);
        check(box != null && box[0] == 1000 && box[1] == 489, field + " sizes the picture from raw item metadata");
        equal(url, app.revanced.extension.synccompat.CommentMedia.displayUrl(url), field + " signed display URL retained");
        equal(text, execute(method, text, url, 917), field + " text and tap URL retained");
        equal(text, item.getString(field), field + " source JSON not mutated");
    }

    /** Execute the emitted call with the JSON bound to the real p2 parameter register. */
    private static void executeMetadataHook(Method method, org.json.JSONObject item) throws Exception {
        Instruction first = code(method).get(0);
        equal(Opcode.INVOKE_STATIC_RANGE, first.getOpcode(), "metadata capture runs at parser entry");
        var call = (RegisterRangeInstruction)first;
        equal(1, call.getRegisterCount(), "one item argument");
        int p2 = method.getImplementation().getRegisterCount() - method.getParameterTypes().size() + 2;
        equal(p2, call.getStartRegister(), "capture receives p2, including high register numbers");
        var reference = (MethodReference)((ReferenceInstruction)first).getReference();
        equal("Lapp/revanced/extension/synccompat/CommentMedia;->rememberItemMetadata(Lorg/json/JSONObject;)V",
                reference.toString(), "production metadata capture call");
        Object[] registers = new Object[method.getImplementation().getRegisterCount()];
        registers[p2] = item;
        String type = reference.getDefiningClass();
        Class.forName(type.substring(1, type.length() - 1).replace('/', '.'))
                .getMethod(reference.getName(), org.json.JSONObject.class).invoke(null, registers[call.getStartRegister()]);
    }

    /** Execute only the straight string rewrite, using JVM String/StringBuilder implementations. */
    private static String execute(Method method, String body, String url, int height) throws Exception {
        Object[] registers = new Object[method.getImplementation().getRegisterCount()];
        registers[0] = url;
        registers[1] = height;
        registers[14] = body;
        var code = code(method);
        int suffix = height(method);
        Object result = null;
        for (int pc = suffix - 3; pc <= suffix + 6; pc++) {
            Instruction instruction = code.get(pc);
            switch (instruction.getOpcode()) {
                case NEW_INSTANCE -> {
                    equal("Ljava/lang/StringBuilder;", ((TypeReference)((ReferenceInstruction)instruction).getReference()).getType(), "builder type");
                    registers[((OneRegisterInstruction)instruction).getRegisterA()] = new StringBuilder();
                }
                case INVOKE_DIRECT -> { /* NEW_INSTANCE above constructed the no-argument builder. */ }
                case CONST_STRING, CONST_STRING_JUMBO -> registers[((OneRegisterInstruction)instruction).getRegisterA()] =
                        ((StringReference)((ReferenceInstruction)instruction).getReference()).getString();
                case MOVE_OBJECT -> registers[((TwoRegisterInstruction)instruction).getRegisterA()] =
                        registers[((TwoRegisterInstruction)instruction).getRegisterB()];
                case MOVE_RESULT_OBJECT -> registers[((OneRegisterInstruction)instruction).getRegisterA()] = result;
                case INVOKE_VIRTUAL -> {
                    var call = (FiveRegisterInstruction)instruction;
                    var ref = (MethodReference)((ReferenceInstruction)instruction).getReference();
                    Object receiver = registers[call.getRegisterC()];
                    switch (ref.getName()) {
                        case "append" -> {
                            if (ref.getParameterTypes().get(0).equals("I"))
                                result = ((StringBuilder)receiver).append((int)registers[call.getRegisterD()]);
                            else result = ((StringBuilder)receiver).append((String)registers[call.getRegisterD()]);
                        }
                        case "toString" -> result = receiver.toString();
                        case "replace" -> result = ((String)receiver).replace((CharSequence)registers[call.getRegisterD()],
                                (CharSequence)registers[call.getRegisterE()]);
                        default -> throw new AssertionError("Unexpected call " + ref);
                    }
                }
                default -> throw new AssertionError("Unexpected opcode " + instruction.getOpcode());
            }
        }
        return (String)registers[14];
    }
}
