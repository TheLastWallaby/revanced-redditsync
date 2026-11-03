import android.content.ContentValues;
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
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Applies "Sync: show poll options" and runs Sync's listing item parser (PostParserHelper.a,
 * c9.c.a) before and after it: on a hand-written model, or, given a local stock Sync 23033 APK, on
 * the real parser. Markdown rendering (RedditHelper.r, which calls native AndDown) is answered by
 * the suite with a stand-in that HTML-escapes the text into one paragraph.
 *
 * A poll post's values gain the PollCompat block at the end of selftext_processed and nothing
 * else changes; posts without a poll, with malformed or oversized poll_data, and comments are
 * parsed exactly as before. The hook is one call right before the parser adds the values to its
 * result list.
 */
public final class PollPatchTest {
    private static final String PARSER = "Lc9/c;";
    private static final String POLL = "Lapp/revanced/extension/synccompat/PollCompat;";
    private static final List<String> PARSE = List.of("Landroid/content/Context;", "I", "Lorg/json/JSONObject;",
            "Ljava/util/ArrayList;", "I", "Z", "Z", "Ljava/lang/String;");
    private static final String HOOK = POLL + "->apply(Landroid/content/ContentValues;Lorg/json/JSONObject;)V";
    private static final String SETTINGS = "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;";
    private static final String STATE = "Lcom/laurencedawson/reddit_sync/singleton/c;";
    private static boolean real;
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-poll-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/poll"), "assemble Sync model");
            real = args.length != 0;
            File source = real ? Path.of(args[0]).toFile() : dex.toFile();

            BytecodePatchContext stock = context(source, scratch, "stock");
            BytecodePatchContext patched = context(source, scratch, "patched");
            List<String> before = keys(parser(patched));
            apply(patched);
            hook(patched, before);

            long now = System.currentTimeMillis();
            long future = now + 3L * 24 * 3600 * 1000;
            JSONObject open = poll(future, 1234, JSONObject.NULL,
                    option("576784", "Yes", null), option("576785", "No", null), option("576786", "&lt;b&gt;Maybe&lt;/b&gt;", null));
            JSONObject closed = poll(1585325428313L, 11249, JSONObject.NULL,
                    option("1883", "Hopeful", 1675), option("1884", "Anxious", 3059));
            JSONObject voted = poll(1588379995927L, 54, "573294",
                    option("573294", "100% and nothing", 40), option("573295", "&lt;50% and chocolate cake", 14));

            int polls = 0;
            for (JSONObject poll : List.of(open, closed, voted)) {
                for (String body : List.of("Which one?", "")) {
                    JSONObject item = post("fo7p5b", body, poll);
                    ContentValues stockValues = single(parse(stock, 3, item), "stock poll post");
                    ContentValues patchedValues = single(parse(patched, 3, item), "patched poll post");
                    String block = block(item, now);
                    check(block != null, "poll block made");
                    equal(body, stockValues.get("selftext_raw"), "Sync parsed it as a text post");
                    equal(body, patchedValues.get("selftext_raw"), "Markdown for editing unchanged");
                    Map<String, Object> expected = stockValues.asMap();
                    String processed = (String) expected.get("selftext_processed");
                    expected.put("selftext_processed", processed == null || processed.isEmpty() ? block : processed + block);
                    equal(expected, patchedValues.asMap(), "patched poll post values");
                    check(!Objects.equals(stockValues.get("selftext_processed"), patchedValues.get("selftext_processed")),
                            "stock Sync shows no poll");
                    polls++;
                }
            }
            String shown = (String) single(parse(patched, 3, post("fo7p5b", "Which one?", voted)), "voted").get("selftext_processed");
            check(shown.contains("<li>100% and nothing · 40 votes · <strong>your vote</strong></li>")
                    && shown.contains("<li>&lt;50% and chocolate cake · 14 votes</li>")
                    && shown.endsWith("<p><a href=\"https://www.reddit.com/poll/fo7p5b\">Open poll on Reddit</a></p>"),
                    "voted poll shown: " + shown);

            // Unchanged: no poll, malformed or oversized poll_data, comments with poll_data, filtered posts.
            List<Object[]> unchanged = new ArrayList<>();
            unchanged.add(new Object[] {3, post("fo7p5b", "Plain **text** post", null)});
            unchanged.add(new Object[] {3, post("fo7p5b", "", null)});
            unchanged.add(new Object[] {3, post("fo7p5b", "Null poll", JSONObject.NULL)});
            unchanged.add(new Object[] {3, post("fo7p5b", "Boolean poll", true)});
            unchanged.add(new Object[] {3, post("fo7p5b", "No options", poll(future, 1, null))});
            unchanged.add(new Object[] {3, post("fo7p5b", "Bad count", poll(future, 1, null, option("1", "A", -3)))});
            unchanged.add(new Object[] {3, post("fo7p5b", "Huge option", poll(future, 1, null, option("1", "x".repeat(10_000), null)))});
            unchanged.add(new Object[] {1, comment("c1", "A reply", open)});
            for (Object[] row : unchanged) {
                int kind = (Integer) row[0];
                JSONObject item = (JSONObject) row[1];
                equal(asMaps(parse(stock, kind, item)), asMaps(parse(patched, kind, item)), "unchanged item " + item.optString("selftext", item.optString("body")));
            }
            JSONObject filtered = post("fo7p5b", "NSFW poll", open).put("over_18", true);
            List<ContentValues> filteredStock = parse(stock, 3, filtered, true);
            equal(asMaps(filteredStock), asMaps(parse(patched, 3, filtered, true)), "filtered post unchanged");
            equal(0, filteredStock.size(), "Sync's NSFW filter drops the post before the add");

            rejections(source, scratch);
            System.out.println("PASS: " + checks + " poll patch checks (" + polls + " poll posts, " + (unchanged.size() + 1)
                    + " unchanged items, " + (real ? "real Sync parser from the supplied stock APK)" : "hand-written Sync model)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Items ----

    private static JSONObject option(String id, String text, Object votes) {
        JSONObject option = new JSONObject().put("text", text).put("id", id);
        if (votes != null) option.put("vote_count", votes);
        return option;
    }
    private static JSONObject poll(Object end, Object total, Object selection, JSONObject... options) {
        JSONObject poll = new JSONObject().put("options", new JSONArray(options)).put("is_prediction", false)
                .put("voting_end_timestamp", end).put("total_vote_count", total)
                .put("prediction_status", JSONObject.NULL).put("tournament_id", JSONObject.NULL);
        if (selection != null) poll.put("user_selection", selection);
        return poll;
    }

    /** A self post with the fields Sync's parser reads, as Reddit sends it without raw_json. */
    static JSONObject post(String id, String selftext, Object poll) {
        JSONObject data = new JSONObject()
                .put("id", id).put("name", "t3_" + id).put("title", "Poll &amp; test").put("selftext", selftext)
                .put("selftext_html", JSONObject.NULL).put("subreddit", "polls").put("subreddit_id", "t5_2qh1i")
                .put("subreddit_name_prefixed", "r/polls").put("author", "tester").put("author_fullname", "t2_abc")
                .put("permalink", "/r/polls/comments/" + id + "/poll_test/")
                .put("url", "https://www.reddit.com/r/polls/comments/" + id + "/poll_test/")
                .put("domain", "self.polls").put("is_self", true).put("is_video", false).put("is_gallery", false)
                .put("created_utc", 1585239028.0).put("created", 1585239028.0).put("edited", false)
                .put("score", 42).put("ups", 42).put("upvote_ratio", 0.9).put("num_comments", 7).put("likes", JSONObject.NULL)
                .put("over_18", false).put("spoiler", false).put("stickied", false).put("locked", false).put("archived", false)
                .put("saved", false).put("hidden", false).put("visited", false).put("contest_mode", false)
                .put("send_replies", true).put("is_original_content", false).put("thumbnail", "self")
                .put("total_awards_received", 0).put("gilded", 0).put("num_reports", JSONObject.NULL)
                .put("distinguished", JSONObject.NULL).put("approved_by", JSONObject.NULL).put("banned_by", JSONObject.NULL)
                .put("link_flair_text", JSONObject.NULL).put("link_flair_css_class", JSONObject.NULL)
                .put("author_flair_text", JSONObject.NULL).put("author_flair_css_class", JSONObject.NULL)
                .put("suggested_sort", JSONObject.NULL).put("view_count", JSONObject.NULL)
                .put("media", JSONObject.NULL).put("secure_media", JSONObject.NULL)
                .put("user_reports", new JSONArray()).put("mod_reports", new JSONArray())
                .put("all_awardings", new JSONArray()).put("author_flair_richtext", new JSONArray())
                .put("link_flair_richtext", new JSONArray()).put("author_cakeday", false);
        if (poll != null) data.put("poll_data", poll);
        return data;
    }
    private static JSONObject comment(String id, String body, Object poll) {
        JSONObject data = post(id, "", null).put("body", body).put("link_id", "t3_fo7p5b").put("parent_id", "t3_fo7p5b")
                .put("name", "t1_" + id).put("depth", 0).put("controversiality", 0).put("score_hidden", false)
                .put("replies", "").put("is_submitter", false);
        if (poll != null) data.put("poll_data", poll);
        return data;
    }

    // ---- Parsing ----

    private static List<ContentValues> parse(BytecodePatchContext context, int kind, JSONObject item) {
        return parse(context, kind, item, false);
    }
    @SuppressWarnings("unchecked")
    private static List<ContentValues> parse(BytecodePatchContext context, int kind, JSONObject item, boolean filterNsfw) {
        ArrayList<Object> list = new ArrayList<>();
        DexRunner runner = new DexRunner(context, new Stand(filterNsfw), POLL);
        // Android's link pattern, which Sync uses to find links in a text post (a simpler stand-in).
        runner.preset("Landroid/util/Patterns;->WEB_URL:Ljava/util/regex/Pattern;",
                java.util.regex.Pattern.compile("https?://[^\\s)\\]]+"));
        runner.run(parser(context), new Object[] {"context", kind, new JSONObject(item.toString()), list, 0, 0, 0, "Title"});
        List<ContentValues> values = new ArrayList<>();
        for (Object o : list) values.add((ContentValues) o);
        return values;
    }
    private static ContentValues single(List<ContentValues> values, String what) {
        equal(1, values.size(), what + ": one item parsed");
        return values.get(0);
    }
    private static List<Map<String, Object>> asMaps(List<ContentValues> values) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ContentValues v : values) maps.add(v.asMap());
        return maps;
    }
    private static String block(JSONObject item, long now) throws ReflectiveOperationException {
        Method block = Class.forName("app.revanced.extension.synccompat.PollCompat")
                .getDeclaredMethod("block", JSONObject.class, long.class, Locale.class, TimeZone.class);
        block.setAccessible(true);
        return (String) block.invoke(null, item, now, Locale.getDefault(), TimeZone.getDefault());
    }

    /** Stands in for Markdown rendering, and, on the model, for Sync's entity decoding. */
    private static final class Stand implements DexRunner.Interceptor {
        final boolean filterNsfw;
        Stand(boolean filterNsfw) { this.filterNsfw = filterNsfw; }

        @Override public Object call(String ref, Object[] a) {
            if (ref.equals("Ld7/f;->r(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
                String text = (String) a[1];
                if (text == null || text.isEmpty()) return "";
                return "<p>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>";
            }
            if (!real && ref.equals("Lwc/p;->a(Ljava/lang/String;)Ljava/lang/String;")) {
                String text = (String) a[0];
                return text == null || text.isEmpty() ? text
                        : text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("‮", "").replace("‏", "");
            }
            if (!real && ref.equals("Lorg/json/JSONObject;->optBoolean(Ljava/lang/String;)Z") && "over_18".equals(a[1])) {
                return filterNsfw && ((JSONObject) a[0]).optBoolean("over_18") ? 1 : 0;
            }
            return RealParser.call(ref, a, filterNsfw);
        }
    }

    /** Sync's app services the real parser calls, answered as on a fresh install with default settings. */
    private static final class RealParser {
        static Object call(String ref, Object[] a, boolean filterNsfw) {
            // RedditDb.a: the row's age stamp (a formatted current time).
            if (ref.equals("Ll8/a;->a()Ljava/lang/String;")) return "2026-11-03 12:00:00";
            // TimestampHelper (t7.n0): the post's age text.
            if (ref.startsWith("Lt7/n0;->")) return "7y";
            // Settings: every post type shown (Sync's default), NSFW shown unless the case filters it;
            // every other switch off.
            if (ref.startsWith(SETTINGS + "->x()")) {
                DexRunner.SyncObject settings = new DexRunner.SyncObject(SETTINGS + "$Settings");
                for (String type : List.of("Link", "Image", "Gif", "Album", "Video", "Text"))
                    settings.fields.put("filtersContent" + type, 1);
                settings.fields.put("filtersContentNsfw", filterNsfw ? 0 : 1);
                return settings;
            }
            if (ref.startsWith(SETTINGS + "->m(") || ref.startsWith(SETTINGS + "->n(")) return 0;
            // App state singletons: the layout mode, moderated subreddits, subreddit colours and icons,
            // filters (none) and the window size.
            if (ref.equals(STATE + "->a()" + STATE)) return new DexRunner.SyncObject(STATE);
            if (ref.equals(STATE + "->e()I")) return 0;
            for (String manager : List.of("Ld8/c;", "Ld8/n;", "Ld8/o;", "Lt7/m;")) {
                if (!ref.startsWith(manager + "->")) continue;
                if (ref.endsWith(")" + manager)) return new DexRunner.SyncObject(manager);
                if (ref.endsWith(")V")) return null;
                if (ref.endsWith(")Z")) return 0;
                if (ref.endsWith(")Ljava/util/ArrayList;")) return new ArrayList<>();
                // gb.q is Sync's list of moderated subreddits (an ArrayList): none.
                if (ref.endsWith(")Lgb/q;")) return new ArrayList<>();
            }
            if (ref.equals("Lt7/y0;->a()I")) return 1080;
            if (ref.equals("Lt7/y0;->b()I")) return 2400;
            // GlideHelper.c: preloads a preview image.
            if (ref.equals("Lx7/a;->c(Ljava/lang/String;)V")) return null;
            if (ref.equals("Landroid/text/Html;->fromHtml(Ljava/lang/String;)Landroid/text/Spanned;"))
                return ((String) a[0]).replaceAll("<[^>]*>", "");
            // The feed preview: Html.fromHtml's text with its spans cleared.
            if (ref.equals("Landroid/text/SpannableStringBuilder;->clearSpans()V")) return null;
            if (ref.equals("Landroid/text/SpannableStringBuilder;->toString()Ljava/lang/String;")) return a[0].toString();
            return DexRunner.PASS;
        }
    }

    // ---- Hook ----

    private static void hook(BytecodePatchContext context, List<String> before) {
        MutableMethod parse = parser(context);
        List<Instruction> code = new ArrayList<>(parse.getImplementation().getInstructions());
        int hook = -1, count = 0;
        for (int i = 0; i < code.size(); i++) if (HOOK.equals(reference(code.get(i)))) { hook = i; count++; }
        equal(1, count, "one PollCompat call");
        Instruction add = code.get(hook + 1);
        equal("Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z", reference(add), "hook right before the result add");
        FiveRegisterInstruction call = (FiveRegisterInstruction) code.get(hook);
        equal(2, call.getRegisterCount(), "hook takes two registers");
        equal(((FiveRegisterInstruction) add).getRegisterD(), call.getRegisterC(), "hook gets the added ContentValues");
        equal(2, call.getRegisterD(), "hook gets the item JSON (v2, copied from p2)");
        equal(5, call.getRegisterC(), "the item's ContentValues is v5");
        List<String> expected = new ArrayList<>(before);
        expected.add(hook + 1, "INVOKE_STATIC:" + HOOK);
        equal(expected, keys(parse), "parser only gains the call");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        List<String> applied = keys(parser(twice));
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already installed"), "duplicate rejected"); }
        equal(applied, keys(parser(twice)), "duplicate makes no edits");

        // A parser without Sync's selftext_raw column fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        MutableMethod parse = parser(other);
        List<Instruction> body = new ArrayList<>(parse.getImplementation().getInstructions());
        for (int i = 0; i < body.size(); i++) {
            if (!"selftext_raw".equals(DexRunner.string(body.get(i)))) continue;
            parse.getImplementation().replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                    ((OneRegisterInstruction) body.get(i)).getRegisterA(), new ImmutableStringReference("other_column")));
        }
        List<String> unchanged = keys(parse);
        try { apply(other); throw new AssertionError("Unknown parser accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("post parser"), "unknown parser rejected: " + expected.getMessage()); }
        equal(unchanged, keys(parse), "rejection makes no edits");

        // A parser that reassigns the item JSON register before the add fails before any edit.
        BytecodePatchContext reused = context(source, scratch, "reused");
        MutableMethod reusedParse = parser(reused);
        List<Instruction> reusedBody = new ArrayList<>(reusedParse.getImplementation().getInstructions());
        int at = -1;
        for (int i = 0; i < reusedBody.size(); i++) if ("selftext_processed".equals(DexRunner.string(reusedBody.get(i)))) at = i;
        check(at > 0, "selftext_processed column found");
        reusedParse.getImplementation().addInstruction(at, new BuilderInstruction21c(Opcode.CONST_STRING, 2,
                new ImmutableStringReference("reused")));
        unchanged = keys(reusedParse);
        try { apply(reused); throw new AssertionError("Reused JSON register accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("post parser"), "reused register rejected: " + expected.getMessage()); }
        equal(unchanged, keys(reusedParse), "register rejection makes no edits");
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
        SyncCompatPatchesKt.getShowSyncPollOptions().getApply$patcher().invoke(context, resources);
    }
    private static MutableMethod parser(BytecodePatchContext context) {
        ClassDef clazz = context.getClassDefs().get(PARSER);
        if (clazz == null) throw new AssertionError("Missing " + PARSER);
        for (MutableMethod method : context.proxy(clazz).getMutableClass().getMethods())
            if (method.getName().equals("a") && method.getParameterTypes().equals(PARSE)) return method;
        throw new AssertionError("Missing " + PARSER + "->a" + PARSE);
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
