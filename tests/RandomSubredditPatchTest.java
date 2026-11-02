import app.revanced.extension.synccompat.RandomSubreddit;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/**
 * Applies "Sync: hide random subreddit" and runs every entry point to Reddit's retired random
 * subreddit before and after it: on hand-written models, or, given a local stock Sync 23033 APK,
 * on the real methods. Android and app services (toasts, fragments, menus, the event bus, the
 * request queue) are stand-ins that record what Sync asked for.
 *
 * Before: the chip and actions select "random", links and selections open the random sheet, and
 * the sheet queues RandomRequest (r/random.json, r/randnsfw.json). After: the chip, the actions and
 * their visibility toggles are hidden, links and selections show the message, the sheet queues
 * nothing, and other subreddits, links and actions are unchanged.
 */
public final class RandomSubredditPatchTest {
    private static final String RANDOM = "Lapp/revanced/extension/synccompat/RandomSubreddit;";
    private static final String MESSAGE = "Random subreddit is no longer available from Reddit.";
    private static final String CHIPS = "Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/";
    private static final String POSTS = "Lcom/laurencedawson/reddit_sync/ui/fragments/posts/VerticalPostsFragment;";
    private static final String ACTION_SHEET = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/ActionsBottomSheetFragment;";
    private static final String BASE_ACTIVITY = "Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Throwable {
        helper();
        Path scratch = Files.createTempDirectory("sync-random-subreddit-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/randomsubreddit"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync stock = new Sync(context(source, scratch, "stock"));
            Sync patched = new Sync(context(source, scratch, "patched"));
            Map<String, List<String>> before = snapshot(patched.context);
            apply(patched.context);
            hooks(patched.context, before);

            links(stock, patched);
            selections(stock, patched);
            sheet(stock, patched);
            actions(stock, patched);
            menu(stock, patched);
            chip(stock, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " random subreddit checks (no r/random or r/randnsfw request from any entry point; executed "
                    + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- The production helper ----

    private static void helper() {
        for (String name : new String[] {"random", "RANDOM", " Random ", "random_nsfw", "Random_NSFW", "randnsfw", "RandNSFW"})
            check(RandomSubreddit.isRandom(name), "random name " + name);
        for (String name : new String[] {null, "", "pics", "randomstuff", "random_", "r/random", "random nsfw", "all", "popular", "frontpage"})
            check(!RandomSubreddit.isRandom(name), "other name " + name);
        for (int action = -1; action < 30; action++)
            equal(action == 5 || action == 6, RandomSubreddit.isRandomAction(action), "action " + action);
        List<Integer> list = new ArrayList<>(List.of(18, 2, 5, 0, 6, 16, 5, 3));
        RandomSubreddit.removeRandomActions(list);
        equal(List.of(18, 2, 0, 16, 3), list, "random actions removed in place, order kept");
        RandomSubreddit.removeRandomActions(null);
        RandomSubreddit.hideRandomItems(null);
        FakeMenu menu = new FakeMenu();
        Menu root = menu.proxy(Menu.class);
        root.add("Random");
        SubMenu visibility = root.addSubMenu("Action visibility");
        visibility.add("About");
        visibility.add("Random");
        visibility.add("Random nsfw");
        visibility.add("Randomize");
        RandomSubreddit.hideRandomItems(root);
        equal("[Random=hidden, Action visibility=shown[About=shown, Random=hidden, Random nsfw=hidden, Randomize=shown]]",
                menu.toString(), "menu items hidden by title, nested");
        equal(MESSAGE, RandomSubreddit.MESSAGE, "message");
    }

    // ---- Entry points ----

    private static void links(Sync stock, Sync patched) {
        String[][] random = {
            {"https://www.reddit.com/r/random", "sheet nsfw=false"}, {"https://reddit.com/r/Random/", "sheet nsfw=false"},
            {"/r/random", "sheet nsfw=false"}, {"https://old.reddit.com/r/random_nsfw", "sheet nsfw=true"},
            {"https://www.reddit.com/r/randnsfw", "open https://www.reddit.com/r/randnsfw"},
        };
        for (String[] row : random) {
            equal(List.of(row[1]), stock.link(row[0]), "stock Sync for " + row[0]);
            equal(List.of("toast " + MESSAGE), patched.link(row[0]), "patched Sync for " + row[0]);
        }
        for (String link : new String[] {"https://www.reddit.com/r/pics", "/r/AskReddit/", "https://www.reddit.com/r/randomthoughts",
                "https://www.reddit.com/user/someone/m/random"}) {
            equal(List.of("open " + link), stock.link(link), "stock opens " + link);
            equal(stock.link(link), patched.link(link), "patched opens " + link);
        }
    }

    private static void selections(Sync stock, Sync patched) {
        equal(List.of("sheet nsfw=false"), stock.select("random"), "stock selection random");
        equal(List.of("sheet nsfw=true"), stock.select("random_nsfw"), "stock selection random_nsfw");
        for (String name : new String[] {"random", "Random", "random_nsfw", "RANDOM_NSFW", "randnsfw"})
            equal(List.of("toast " + MESSAGE), patched.select(name), "patched selection " + name);
        for (String name : new String[] {"pics", "all", "popular", "randomthoughts"}) {
            List<String> events = stock.select(name);
            check(!events.isEmpty() && events.stream().noneMatch(e -> e.startsWith("sheet") || e.startsWith("toast")), "stock navigates to " + name + ": " + events);
            equal(events, patched.select(name), "patched navigates to " + name);
        }
    }

    private static void sheet(Sync stock, Sync patched) {
        for (boolean nsfw : new boolean[] {false, true}) {
            equal(List.of("request nsfw=" + nsfw, "queued"), stock.sheet(nsfw, true, true), "stock sheet requests, nsfw=" + nsfw);
            equal(List.of("toast " + MESSAGE, "dismissed"), patched.sheet(nsfw, true, true), "patched sheet, nsfw=" + nsfw);
        }
        equal(List.of("toast " + MESSAGE), patched.sheet(false, true, false), "patched sheet without a dialog");
        equal(List.of("toast " + MESSAGE), patched.sheet(false, false, true), "patched sheet without an activity");
        String sfw = (String) stock.run.call("Lr8/d;", "b", (Object) null, 0);
        String nsfw = (String) stock.run.call("Lr8/d;", "b", (Object) null, 1);
        check(sfw.endsWith("r/random.json"), "RandomRequest SFW endpoint " + sfw);
        check(nsfw.endsWith("r/randnsfw.json"), "RandomRequest NSFW endpoint " + nsfw);
    }

    private static void actions(Sync stock, Sync patched) {
        equal(1, stock.enabled(5), "stock Random action on");
        equal(1, stock.enabled(6), "stock Random NSFW action on");
        equal(0, patched.enabled(5), "patched Random action off");
        equal(0, patched.enabled(6), "patched Random NSFW action off");
        for (int action = 0; action < 8; action++) {
            if (action == 5 || action == 6) continue;
            equal(stock.enabled(action), patched.enabled(action), "action " + action + " unchanged");
        }
        List<Integer> saved = new ArrayList<>(List.of(18, 2, 0, 5, 6, 7, 1));
        DexRunner.SyncObject manager = new DexRunner.SyncObject("Lt7/b;");
        manager.fields.put("a", saved);
        Object stockList = stock.instance("Lt7/b;", "c", manager);
        equal(List.of(18, 2, 0, 5, 6, 7, 1), stockList, "stock action list");
        Object patchedList = patched.instance("Lt7/b;", "c", manager);
        check(patchedList == saved, "patched list is Sync's own list");
        equal(List.of(18, 2, 0, 7, 1), patchedList, "patched list without Random actions");

        List<Integer> stockReset = stock.reset(), patchedReset = patched.reset();
        check(stockReset.contains(5) && stockReset.contains(6), "stock reset adds Random actions: " + stockReset);
        List<Integer> expected = new ArrayList<>(stockReset);
        expected.removeAll(List.of(5, 6));
        equal(expected, patchedReset, "patched reset adds the other actions only");
    }

    private static void menu(Sync stock, Sync patched) {
        Map<String, Boolean> before = stock.menu(), after = patched.menu();
        equal(Boolean.TRUE, before.get("Action visibility/Random"), "stock Random toggle shown");
        equal(Boolean.TRUE, before.get("Action visibility/Random nsfw"), "stock Random nsfw toggle shown");
        equal(Boolean.FALSE, after.get("Action visibility/Random"), "patched Random toggle hidden");
        equal(Boolean.FALSE, after.get("Action visibility/Random nsfw"), "patched Random nsfw toggle hidden");
        check(before.size() >= 4, "menu built: " + before.keySet());
        for (String title : before.keySet())
            if (!title.endsWith("/Random") && !title.endsWith("/Random nsfw"))
                equal(before.get(title), after.get(title), "menu item " + title + " unchanged");
    }

    private static void chip(Sync stock, Sync patched) {
        equal(List.of("select random"), stock.chipClick(), "the chip selects random");
        List<String> stockBinding = stock.bind(), patchedBinding = patched.bind();
        check(stockBinding.stream().noneMatch(e -> e.startsWith("visibility")), "stock chip shown: " + stockBinding);
        check(patchedBinding.contains("visibility 0x7f0900da=8"), "patched chip_random hidden: " + patchedBinding);
        List<String> others = new ArrayList<>(patchedBinding);
        others.remove("visibility 0x7f0900da=8");
        equal(stockBinding, others, "other chips bound as before");
    }

    // ---- Sync with recording stand-ins ----

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();
        final DexRunner.SyncObject settings = new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;");
        Object activity, dialog;
        FakeMenu popup;

        Sync(BytecodePatchContext context) {
            this.context = context;
            for (String on : new String[] {"actionsRandom", "actionsRandomNsfw", "actionsSaved", "actionsProfile", "actionsHideRead", "toolbarExpanded"})
                settings.fields.put(on, 1);
            run = new DexRunner(context, this::intercept, RANDOM);
        }

        private Object intercept(String ref, Object[] a) {
            String owner = ref.substring(0, ref.indexOf("->"));
            String name = ref.substring(ref.indexOf("->") + 2, ref.indexOf('('));
            switch (ref) {
                case "Lgb/p;->e(Ljava/lang/String;Landroid/content/Context;)V": events.add("toast " + a[0]); return null;
                case "Ls9/g;->e(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Landroid/os/Bundle;)V":
                    equal(new DexRunner.ClassRef("Lt9/m1;"), a[0], "sheet class");
                    events.add("sheet " + a[2]); return null;
                case "Lt9/m1;->u4(Z)Landroid/os/Bundle;": return "nsfw=" + !Integer.valueOf(0).equals(a[0]);
                case "Lcom/laurencedawson/reddit_sync/ui/activities/CasualActivity;->c1(Landroid/content/Context;Ljava/lang/String;)V":
                    events.add("open " + a[1]); return null;
                case "Lr8/d;-><init>(Landroid/content/Context;ZLcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V":
                    events.add("request nsfw=" + !Integer.valueOf(0).equals(a[2])); return null;
                case "Lm8/a;->c(Landroid/content/Context;Lx8/a;)V": events.add("queued"); return null;
                case "Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;": return activity;
                case "Landroidx/fragment/app/FragmentActivity;->G()Landroidx/fragment/app/FragmentManager;":
                case "Landroidx/fragment/app/Fragment;->Q0()Landroidx/fragment/app/FragmentManager;": return "fragments";
                case "Landroidx/fragment/app/Fragment;->q1()Landroid/view/View;": return new DexRunner.SyncObject("Ltest/View;");
                case "Landroidx/fragment/app/c;->z3()Landroid/app/Dialog;": return dialog;
                case "Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V": events.add("dismissed"); return null;
                case "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;":
                    return settings;
                case "Lgb/j;->c(Landroid/view/View;I)Lm/e;": popup = new FakeMenu(); return new DexRunner.SyncObject("Lm/e;");
                case "Lm/e;->a()Landroid/view/Menu;": return popup.proxy(Menu.class);
                case "Lm/e;->e()V": events.add("shown"); return null;
                case "Lq2/c;->c(Landroid/view/View;ILjava/lang/String;)Landroid/view/View;": {
                    DexRunner.SyncObject view = new DexRunner.SyncObject("Ltest/Chip;");
                    view.fields.put("id", String.format("0x%08x", (Integer) a[1]));
                    events.add("bind " + view.fields.get("id"));
                    return view;
                }
                case "Lj9/a;->a()Lbd/b;": return new DexRunner.SyncObject("Ltest/Bus;");
                case "Lbd/b;->i(Ljava/lang/Object;)V": events.add("select " + ((DexRunner.SyncObject) a[1]).fields.get("a")); return null;
            }
            if (owner.equals("Lm/e;") && name.equals("d")) return null;
            if (owner.equals(ACTION_SHEET.replace(";", "$c;")) && name.equals("<init>")) return null;
            if (owner.equals(CHIPS + "AbstractChipGroup_ViewBinding;") && name.equals("<init>")) return null;
            if (owner.equals("Lt7/b;") && (name.equals("k") || name.equals("j"))) return null;
            if (owner.equals("Landroid/view/View;") || owner.equals("Landroid/view/ViewGroup;")) {
                if (name.equals("setVisibility")) events.add("visibility " + ((DexRunner.SyncObject) a[0]).fields.get("id") + "=" + a[1]);
                else if (name.startsWith("post")) events.add("navigate");
                return name.startsWith("post") ? 1 : null;
            }
            return DexRunner.PASS;
        }

        List<String> link(String link) {
            events.clear();
            DexRunner.SyncObject context = new DexRunner.SyncObject(BASE_ACTIVITY);
            run.call("Ly7/a;", "T", context, link);
            return new ArrayList<>(events);
        }

        List<String> select(String name) {
            events.clear();
            activity = new DexRunner.SyncObject("Ltest/Activity;");
            DexRunner.SyncObject event = new DexRunner.SyncObject("Lh7/d0;");
            event.fields.put("a", name);
            event.fields.put("b", 0);
            run.run(method(context, POSTS, "onSubredditSelected", List.of("Lh7/d0;")),
                    new Object[] {new DexRunner.SyncObject(POSTS), event});
            return new ArrayList<>(events);
        }

        List<String> sheet(boolean nsfw, boolean hasActivity, boolean hasDialog) {
            events.clear();
            activity = hasActivity ? new DexRunner.SyncObject("Ltest/Activity;") : null;
            dialog = hasDialog ? "dialog" : null;
            DexRunner.SyncObject sheet = new DexRunner.SyncObject("Lt9/m1;");
            sheet.fields.put("P0", nsfw ? 1 : 0);
            run.run(method(context, "Lt9/m1;", "v4", List.of()), new Object[] {sheet});
            return new ArrayList<>(events);
        }

        Object enabled(int action) { return run.call("Lt7/b;", "f", action); }

        Object instance(String type, String name, Object receiver) {
            return run.run(method(context, type, name, List.of()), new Object[] {receiver});
        }

        @SuppressWarnings("unchecked")
        List<Integer> reset() {
            DexRunner.SyncObject manager = new DexRunner.SyncObject("Lt7/b;");
            manager.fields.put("a", new ArrayList<Integer>());
            instance("Lt7/b;", "b", manager);
            return (List<Integer>) manager.fields.get("a");
        }

        Map<String, Boolean> menu() {
            events.clear();
            run.run(method(context, ACTION_SHEET, "showMore", List.of("Landroid/view/View;")),
                    new Object[] {new DexRunner.SyncObject(ACTION_SHEET), null});
            equal(List.of("shown"), events, "menu shown once");
            return popup.visibility();
        }

        List<String> chipClick() {
            events.clear();
            run.run(method(context, CHIPS + "DefaultSubredditChipGroup;", "onPreviewsClicked", List.of()),
                    new Object[] {new DexRunner.SyncObject(CHIPS + "DefaultSubredditChipGroup;")});
            return new ArrayList<>(events);
        }

        List<String> bind() {
            events.clear();
            ClassDef binding = context.getClassDefs().get(CHIPS + "DefaultSubredditChipGroup_ViewBinding;");
            MutableMethod init = null;
            for (MutableMethod m : context.proxy(binding).getMutableClass().getMethods()) if (m.getName().equals("<init>")) init = m;
            run.run(init, new Object[] {new DexRunner.SyncObject(CHIPS + "DefaultSubredditChipGroup_ViewBinding;"),
                    new DexRunner.SyncObject(CHIPS + "DefaultSubredditChipGroup;"), new DexRunner.SyncObject("Ltest/View;")});
            List<String> result = new ArrayList<>();
            for (String event : events) if (event.startsWith("bind") || event.startsWith("visibility")) result.add(event);
            return result;
        }
    }

    /** A Menu/SubMenu/MenuItem stand-in that records titles and visibility. */
    private static final class FakeMenu {
        final List<Object[]> items = new ArrayList<>(); // {title, visible, FakeMenu sub}

        @SuppressWarnings("unchecked")
        <T> T proxy(Class<T> type) {
            return (T) Proxy.newProxyInstance(FakeMenu.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
                switch (method.getName()) {
                    case "add": return item(add(title(args), null));
                    case "addSubMenu": { FakeMenu sub = new FakeMenu(); add(title(args), sub); return sub.proxy(SubMenu.class); }
                    case "size": return items.size();
                    case "getItem": return item(items.get((Integer) args[0]));
                    case "hashCode": return System.identityHashCode(self);
                    case "equals": return self == args[0];
                    case "toString": return FakeMenu.this.toString();
                    default: return method.getReturnType().isInstance(self) ? self : zero(method.getReturnType());
                }
            });
        }
        private Object[] add(String title, FakeMenu sub) { Object[] item = {title, true, sub}; items.add(item); return item; }
        private static String title(Object[] args) {
            for (int n = args.length - 1; n >= 0; n--) if (args[n] instanceof CharSequence) return args[n].toString();
            return "?";
        }
        private static MenuItem item(Object[] item) {
            return (MenuItem) Proxy.newProxyInstance(FakeMenu.class.getClassLoader(), new Class<?>[] {MenuItem.class}, (self, method, args) -> {
                switch (method.getName()) {
                    case "setVisible": item[1] = args[0]; return self;
                    case "isVisible": return item[1];
                    case "getTitle": return item[0];
                    case "hasSubMenu": return item[2] != null;
                    case "getSubMenu": return item[2] == null ? null : ((FakeMenu) item[2]).proxy(SubMenu.class);
                    case "hashCode": return System.identityHashCode(self);
                    case "equals": return self == args[0];
                    case "toString": return String.valueOf(item[0]);
                    default: return method.getReturnType().isInstance(self) ? self : zero(method.getReturnType());
                }
            });
        }
        private static Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == char.class) return (char) 0;
            return null;
        }
        Map<String, Boolean> visibility() {
            Map<String, Boolean> result = new LinkedHashMap<>();
            collect("", result);
            return result;
        }
        private void collect(String prefix, Map<String, Boolean> result) {
            for (Object[] item : items) {
                result.put(prefix + item[0], (Boolean) item[1]);
                if (item[2] != null) ((FakeMenu) item[2]).collect(prefix + item[0] + "/", result);
            }
        }
        @Override public String toString() {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Object[] item : items)
                joiner.add(item[0] + "=" + ((Boolean) item[1] ? "shown" : "hidden") + (item[2] == null ? "" : item[2].toString()));
            return joiner.toString();
        }
    }

    // ---- Hook structure and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("T", keys(method(context, "Ly7/a;", "T", List.of("Landroid/content/Context;", "Ljava/lang/String;"))));
        result.put("selected", keys(method(context, POSTS, "onSubredditSelected", List.of("Lh7/d0;"))));
        result.put("f", keys(method(context, "Lt7/b;", "f", List.of("I"))));
        result.put("c", keys(method(context, "Lt7/b;", "c", List.of())));
        result.put("showMore", keys(method(context, ACTION_SHEET, "showMore", List.of("Landroid/view/View;"))));
        result.put("v4", keys(method(context, "Lt9/m1;", "v4", List.of())));
        ClassDef binding = context.getClassDefs().get(CHIPS + "DefaultSubredditChipGroup_ViewBinding;");
        for (MutableMethod m : context.proxy(binding).getMutableClass().getMethods()) if (m.getName().equals("<init>")) result.put("bind", keys(m));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        // Prefix hooks: Sync's own body follows unchanged.
        Map<String, Integer> prefixes = Map.of("T", 11, "selected", 9, "f", 5);
        for (Map.Entry<String, Integer> hook : prefixes.entrySet()) {
            List<String> stock = before.get(hook.getKey()), now = after.get(hook.getKey());
            equal(stock.subList(1, stock.size()), now.subList(hook.getValue() + 1, now.size()), hook.getKey() + " keeps Sync's body");
            check(now.subList(1, hook.getValue() + 1).stream().anyMatch(k -> k.contains(RANDOM + "->")), hook.getKey() + " asks RandomSubreddit");
        }
        check(after.get("T").subList(1, 12).stream().anyMatch(k -> k.contains("Lgb/p;->e(")), "link shows the message");
        check(after.get("selected").subList(1, 10).stream().anyMatch(k -> k.contains("Lgb/p;->e(")), "selection shows the message");
        // Inserted lines only.
        List<String> c = new ArrayList<>(before.get("c"));
        c.add(2, "INVOKE_STATIC:" + RANDOM + "->removeRandomActions(Ljava/util/List;)V");
        equal(c, after.get("c"), "action list filtered in place");
        List<String> more = new ArrayList<>(before.get("showMore")), moreNow = after.get("showMore");
        more.addAll(more.size() - 2, List.of("INVOKE_VIRTUAL:Lm/e;->a()Landroid/view/Menu;", "MOVE_RESULT_OBJECT:",
                "INVOKE_STATIC:" + RANDOM + "->hideRandomItems(Landroid/view/Menu;)V"));
        equal(more, moreNow, "menu toggles hidden just before the menu shows");
        List<String> bindNow = after.get("bind");
        check(bindNow.size() == before.get("bind").size() + 2 && bindNow.contains("INVOKE_VIRTUAL:Landroid/view/View;->setVisibility(I)V"),
                "chip binding hides the chip");
        // The sheet no longer builds or queues the request.
        check(after.get("v4").stream().noneMatch(k -> k.contains("Lr8/d;") || k.contains("Lm8/a;")), "sheet sends nothing");
        check(before.get("v4").stream().anyMatch(k -> k.contains("Lr8/d;")), "stock sheet builds RandomRequest");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        apply(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { apply(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already hidden"), "duplicate rejected"); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // An unknown layout (the binding no longer names onPreviewsClicked) fails before any edit.
        BytecodePatchContext other = context(source, scratch, "unsupported");
        ClassDef binding = other.getClassDefs().get(CHIPS + "DefaultSubredditChipGroup_ViewBinding;");
        MutableMethod init = null;
        for (MutableMethod m : other.proxy(binding).getMutableClass().getMethods()) if (m.getName().equals("<init>")) init = m;
        List<Instruction> body = new ArrayList<>(init.getImplementation().getInstructions());
        int marker = -1;
        for (int i = 0; i < body.size(); i++) if ("method 'onPreviewsClicked'".equals(DexRunner.string(body.get(i)))) marker = i;
        check(marker >= 0, "binding marker found");
        init.getImplementation().replaceInstruction(marker, new BuilderInstruction21c(Opcode.CONST_STRING,
                ((OneRegisterInstruction) body.get(marker)).getRegisterA(), new ImmutableStringReference("method 'other'")));
        Map<String, List<String>> unchanged = snapshot(other);
        try { apply(other); throw new AssertionError("Unknown layout accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("random chip binding"), "unknown layout rejected: " + expected.getMessage()); }
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
        SyncCompatPatchesKt.getHideSyncRandomSubreddit().getApply$patcher().invoke(context, resources);
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
