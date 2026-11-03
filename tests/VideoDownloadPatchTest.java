import app.revanced.local.synccompat.OfficialSyncPatchesKt;
import app.revanced.local.synccompat.SyncCompatPatchesKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchException;
import app.revanced.patcher.patch.ResourcePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Applies the bundled "Fix video downloads" port and then "Sync: fix video download manifests", and
 * runs Sync's video playlist parser (RedditVideoRequest.parseNetworkResponse, r8.g) and the download
 * job's playlist listener (DownloadRedditVideoJob$b.a) on manifests before and after the patch: on
 * hand-written models, or, given a local stock Sync 23033 APK, on the real methods. The listener's
 * outcome is what the user gets: a download of the chosen video and audio files, or Sync's message.
 * No network; Android and app services are stand-ins that record what Sync asked for.
 */
public final class VideoDownloadPatchTest {
    private static final String HELPER = "Lapp/revanced/extension/synccompat/VideoDownloadManifest;";
    private static final String PORT = "Lapp/revanced/extension/syncforreddit/FixRedditVideoDownloadPatch;";
    private static final String REQUEST = "Lr8/g;";
    private static final String NETWORK = "Lcom/android/volley/NetworkResponse;";
    private static final String LISTENER = "Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;";
    private static final String ID = "abc123";
    private static final String V = "https://v.redd.it/" + ID + "/";
    private static final String PLAYLIST_ERROR = "message Error grabbing the video playlist";
    private static final String NO_VIDEO = "message No valid video files found";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static String mpd(String sets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period>" + sets + "</Period></MPD>";
    }
    private static String rep(String attributes, String base) {
        return "<Representation " + attributes + "><BaseURL>" + base + "</BaseURL></Representation>";
    }

    public static void main(String[] args) throws Throwable {
        Path scratch = Files.createTempDirectory("sync-video-download-test");
        try {
            Path dex = scratch.resolve("classes.dex");
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 23;
            options.outputDexFile = dex.toString();
            check(Smali.assemble(options, "tests/fixtures/videodownload"), "assemble Sync models");
            File source = args.length != 0 ? Path.of(args[0]).toFile() : dex.toFile();

            Sync port = new Sync(context(source, scratch, "port"));
            port(port.context);
            Sync patched = new Sync(context(source, scratch, "patched"));
            port(patched.context);
            Map<String, List<String>> before = snapshot(patched.context);
            patch(patched.context);
            hooks(patched.context, before);

            downloads(port, patched);
            rejections(source, scratch);

            System.out.println("PASS: " + checks + " video download patch checks (port, then patch; parser and listener on "
                    + "manifests; executed " + (args.length != 0 ? "real Sync methods from the supplied stock APK)" : "hand-written Sync models)"));
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // ---- Downloads ----

    private static void downloads(Sync port, Sync patched) throws ReflectiveOperationException {
        Field field = Class.forName("app.revanced.extension.synccompat.CommentVideoTest").getDeclaredField("REDDIT_MPD");
        field.setAccessible(true);
        String reddit = ((String) field.get(null)).replace("CMAF_", "DASH_");
        String withSound = reddit.replace("    </AdaptationSet>\n", "    </AdaptationSet>\n"
                + "    <AdaptationSet contentType=\"audio\" id=\"1\"><Representation bandwidth=\"69120\" id=\"4\" mimeType=\"audio/mp4\">"
                + "<BaseURL>DASH_AUDIO_64.mp4</BaseURL></Representation><Representation bandwidth=\"132203\" id=\"5\" mimeType=\"audio/mp4\">"
                + "<BaseURL>DASH_AUDIO_128.mp4</BaseURL></Representation></AdaptationSet>\n");
        String video = rep("bandwidth='2400000' width='1280' height='720'", "DASH_720.mp4");
        String audio = rep("bandwidth='128000'", "DASH_AUDIO_128.mp4");
        String mime = mpd("<AdaptationSet mimeType='video/mp4'>" + video + "</AdaptationSet><AdaptationSet mimeType='audio/mp4'>" + audio + "</AdaptationSet>");
        String repMime = mpd("<AdaptationSet>" + rep("mimeType='video/mp4' bandwidth='2400000'", "DASH_720.mp4")
                + rep("mimeType='audio/mp4' bandwidth='128000'", "DASH_AUDIO_128.mp4") + "</AdaptationSet>");
        String prefixed = "<?xml version='1.0'?><mpd:MPD xmlns:mpd='urn:mpeg:dash:schema:mpd:2011'><mpd:Period>"
                + "<mpd:AdaptationSet contentType='video'><mpd:Representation width='1280' height='720' bandwidth='2400000'>"
                + "<mpd:BaseURL>DASH_720.mp4</mpd:BaseURL></mpd:Representation></mpd:AdaptationSet>"
                + "<mpd:AdaptationSet contentType='audio'><mpd:Representation bandwidth='128000'><mpd:BaseURL>DASH_AUDIO_128.mp4</mpd:BaseURL>"
                + "</mpd:Representation></mpd:AdaptationSet></mpd:Period></mpd:MPD>";
        String invalidBandwidth = mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='800000' width='640' height='360'", "DASH_360.mp4")
                + rep("width='1280' height='720'", "DASH_720.mp4") + rep("bandwidth='n/a' width='1920' height='1080'", "DASH_1080.mp4")
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + audio + "</AdaptationSet>");
        String signed = mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "https://v.redd.it/" + ID + "/DASH_720.mp4?a=1%2Csig&amp;b=2")
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + rep("bandwidth='1'", "audio/DASH_AUDIO.mp4?a=2%2Csig") + "</AdaptationSet>");
        String audioOnly = mpd("<AdaptationSet contentType='audio'>" + audio + "</AdaptationSet>");
        String segments = mpd("<AdaptationSet contentType='video'><SegmentTemplate media='chunk_$Number$.m4s' initialization='init.mp4'/>"
                + "<Representation bandwidth='2400000' width='1280' height='720'/></AdaptationSet><AdaptationSet contentType='audio'>"
                + "<SegmentTemplate media='a_$Number$.m4s'/><Representation bandwidth='128000'/></AdaptationSet>");
        String malformed = "<MPD><Period><AdaptationSet contentType='video'>" + video;

        String sound = "download video=" + V + "DASH_480.mp4 audio=" + V + "DASH_AUDIO_128.mp4";
        String both = "download video=" + V + "DASH_720.mp4 audio=" + V + "DASH_AUDIO_128.mp4";
        Object[][] cases = {
            // name, manifest, with the port only, with the patch
            {"real Reddit layout with sound", withSound, sound, sound},
            {"real Reddit clip without sound", reddit, "download video=" + V + "DASH_480.mp4 audio=null", "download video=" + V + "DASH_480.mp4 audio=null"},
            {"mimeType on the sets", mime, "download video=" + V + "null audio=null", both},
            {"mimeType on the representations", repMime, null, both},
            {"namespace prefixes", prefixed, "download video=" + V + "null audio=null", both},
            {"missing and invalid bandwidths", invalidBandwidth, PLAYLIST_ERROR, "download video=" + V + "DASH_360.mp4 audio=" + V + "DASH_AUDIO_128.mp4"},
            {"absolute and relative BaseURLs with signed queries", signed, null,
                "download video=" + V + "DASH_720.mp4?a=1%2Csig&b=2 audio=" + V + "audio/DASH_AUDIO.mp4?a=2%2Csig"},
            {"no video track", audioOnly, "download video=" + V + "null audio=" + V + "DASH_AUDIO_128.mp4", NO_VIDEO},
            {"SegmentTemplate only", segments, PLAYLIST_ERROR, NO_VIDEO},
            {"malformed XML", malformed, PLAYLIST_ERROR, PLAYLIST_ERROR},
        };
        for (Object[] row : cases) {
            String name = (String) row[0], manifest = (String) row[1];
            if (row[2] != null) equal(row[2], port.download(manifest), "port only, " + name);
            equal(row[3], patched.download(manifest), "patched, " + name);
        }
        // The port alone saves the audio track as the video, or a ".../null" file, where Sync's
        // own loop does not fail first; the patch never produces either.
        String audioOnlyBefore = port.download(audioOnly);
        check(audioOnlyBefore.startsWith("download video=") || audioOnlyBefore.equals(PLAYLIST_ERROR), "port only, no video: " + audioOnlyBefore);
        check(!audioOnlyBefore.equals(NO_VIDEO), "port only gives no clear no-video message");
        for (Object[] row : cases) {
            String outcome = (String) row[3];
            check(!outcome.contains("/null"), "no .../null download: " + row[0]);
            check(!outcome.startsWith("download video=" + V + "DASH_AUDIO"), "audio never saved as the video: " + row[0]);
        }
    }

    // ---- Sync with recording stand-ins ----

    private static final class Sync {
        final BytecodePatchContext context;
        final DexRunner run;
        final List<String> events = new ArrayList<>();

        Sync(BytecodePatchContext context) {
            this.context = context;
            run = new DexRunner(context, this::intercept, HELPER, PORT);
        }

        private Object intercept(String ref, Object[] a) {
            switch (ref) {
                case "Lcom/android/volley/Request;->getUrl()Ljava/lang/String;": return ((DexRunner.SyncObject) a[0]).fields.get("test.url");
                case "Lorg/apache/commons/lang3/StringUtils;->startsWith(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[0].toString().startsWith(a[1].toString()) ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->isNotEmpty(Ljava/lang/CharSequence;)Z":
                    return a[0] != null && !a[0].toString().isEmpty() ? 1 : 0;
                case "Lorg/apache/commons/lang3/StringUtils;->containsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z":
                    return a[0] != null && a[1] != null && a[0].toString().toLowerCase(Locale.ROOT).contains(a[1].toString().toLowerCase(Locale.ROOT)) ? 1 : 0;
                case "Ld9/a;->a(Lcom/android/volley/NetworkResponse;J)Lcom/android/volley/Cache$Entry;": {
                    DexRunner.SyncObject entry = new DexRunner.SyncObject("Lcom/android/volley/Cache$Entry;");
                    entry.fields.put("ttl", a[1]);
                    return entry;
                }
                case "Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;": {
                    DexRunner.SyncObject response = new DexRunner.SyncObject("Lcom/android/volley/Response;");
                    response.fields.put("result", a[0]);
                    response.fields.put("cache", a[1]);
                    return response;
                }
                case "Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;":
                    return new DexRunner.SyncObject("Lcom/android/volley/Response;");
                case "Lcom/android/volley/ParseError;-><init>()V": case "Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V":
                    return null;
                case "Ljava/util/Collections;->sort(Ljava/util/List;Ljava/util/Comparator;)V": {
                    // Sync's own comparator (real APK): run its compare method.
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) a[0];
                    DexRunner.SyncObject comparator = (DexRunner.SyncObject) a[1];
                    MutableMethod compare = method(context, comparator.type, "compare", List.of("Ljava/lang/Object;", "Ljava/lang/Object;"));
                    list.sort((x, y) -> (Integer) run.run(compare, new Object[] {comparator, x, y}));
                    return null;
                }
                case "Lcom/laurencedawson/reddit_sync/jobs/media/AbstractDownloadMediaJob;->A(Lj8/a;Ljava/lang/String;Z)V":
                    events.add("message " + a[2]); return null;
                case "Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;->I(Lj8/a;Lo8/h;)V": {
                    DexRunner.SyncObject description = (DexRunner.SyncObject) a[2];
                    events.add("download video=" + description.fields.get("a") + " audio=" + description.fields.get("b"));
                    return null;
                }
            }
            return DexRunner.PASS;
        }

        /** Sync parses the playlist; on success its listener starts the download or shows a message. */
        String download(String manifest) {
            events.clear();
            DexRunner.SyncObject request = new DexRunner.SyncObject(REQUEST);
            request.fields.put("c", ID);
            request.fields.put("test.url", V + "DASHPlaylist.mpd");
            DexRunner.SyncObject response = new DexRunner.SyncObject(NETWORK);
            response.fields.put("data", manifest.getBytes(StandardCharsets.UTF_8));
            response.fields.put("headers", new HashMap<String, String>());
            DexRunner.SyncObject result = (DexRunner.SyncObject) run.run(
                    method(context, REQUEST, "parseNetworkResponse", List.of(NETWORK)), new Object[] {request, response});
            Object description = result.fields.get("result");
            // Volley sends a failed parse to the job's error listener, which shows this message.
            if (description == null) return PLAYLIST_ERROR;
            equal(3_600_000L, ((DexRunner.SyncObject) result.fields.get("cache")).fields.get("ttl"), "cached for an hour as before");
            DexRunner.SyncObject listener = new DexRunner.SyncObject(LISTENER);
            listener.fields.put("a", new DexRunner.SyncObject("Lj8/a;"));
            listener.fields.put("b", new DexRunner.SyncObject("Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;"));
            run.run(method(context, LISTENER, "a", List.of("Lo8/h;")), new Object[] {listener, description});
            equal(1, events.size(), "one outcome: " + events);
            return events.get(0);
        }
    }

    // ---- Hook structure and rejection ----

    private static Map<String, List<String>> snapshot(BytecodePatchContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("parse", keys(method(context, REQUEST, "parseNetworkResponse", List.of(NETWORK))));
        result.put("listener", keys(method(context, LISTENER, "a", List.of("Lo8/h;"))));
        result.put("description", keys(method(context, "Lo8/h;", "<init>", List.of("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"))));
        return result;
    }

    private static void hooks(BytecodePatchContext context, Map<String, List<String>> before) {
        Map<String, List<String>> after = snapshot(context);
        equal(before.get("listener"), after.get("listener"), "listener unchanged");
        equal(before.get("description"), after.get("description"), "description unchanged");
        List<String> stock = before.get("parse"), now = after.get("parse");
        check(stock.contains("INVOKE_STATIC:" + PORT + "->getLinks([B)[Ljava/lang/String;"), "port installed first");
        int added = now.size() - stock.size();
        equal(stock.subList(1, stock.size()), now.subList(1 + added, now.size()), "Sync's loop and the port's hook kept after the new code");
        List<String> prologue = now.subList(1, 1 + added);
        equal(1, Collections.frequency(prologue, "INVOKE_STATIC:" + HELPER + "->links([BLjava/lang/String;)[Ljava/lang/String;"), "helper asked first");
        equal(2, Collections.frequency(prologue, "RETURN_OBJECT:"), "both outcomes return before Sync's loop");
        check(prologue.contains("IPUT_OBJECT:Lo8/h;->a:Ljava/lang/String;"), "no-video result clears the video");
        equal(stock.get(0), now.get(0), "registers unchanged");
    }

    private static void rejections(File source, Path scratch) throws PatchException {
        BytecodePatchContext twice = context(source, scratch, "twice");
        port(twice);
        patch(twice);
        Map<String, List<String>> applied = snapshot(twice);
        try { patch(twice); throw new AssertionError("Duplicate application accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("already fixed"), "duplicate rejected: " + expected.getMessage()); }
        equal(applied, snapshot(twice), "duplicate makes no edits");

        // Without the bundled port's hook the layout is not the one this patch builds on.
        BytecodePatchContext alone = context(source, scratch, "alone");
        Map<String, List<String>> unchanged = snapshot(alone);
        try { patch(alone); throw new AssertionError("Missing port accepted"); }
        catch (PatchException expected) { check(expected.getMessage().contains("Fix video downloads hook"), "missing port rejected: " + expected.getMessage()); }
        equal(unchanged, snapshot(alone), "rejection makes no edits");
    }

    // ---- Helpers ----

    private static BytecodePatchContext context(File source, Path scratch, String name) {
        BytecodePatchContext context = new BytecodePatchContext(source, scratch.resolve(name).toFile());
        context.getClassDefs().initializeCache$patcher();
        return context;
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void run(Patch patch, BytecodePatchContext context) throws PatchException {
        var resources = new ResourcePatchContext(context.getApkFile$patcher(),
                context.getPatchedFilesPath$patcher(), context.getPatchedFilesPath$patcher(), null, null);
        patch.getApply$patcher().invoke(context, resources);
    }
    private static void port(BytecodePatchContext context) throws PatchException { run(OfficialSyncPatchesKt.getFixVideoDownloadsPatch(), context); }
    private static void patch(BytecodePatchContext context) throws PatchException { run(SyncCompatPatchesKt.getFixSyncVideoDownloadManifests(), context); }
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
