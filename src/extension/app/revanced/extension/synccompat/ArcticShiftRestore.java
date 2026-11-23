package app.revanced.extension.synccompat;

import app.revanced.extension.shared.Utils;
import com.android.volley.NetworkResponse;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.laurencedawson.reddit_sync.provider.RedditProvider;
import com.laurencedawson.reddit_sync.ui.views.core.MaterialRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sync's "Restore comment" action, answered by the Arctic Shift archive instead of Pushshift, and a
 * matching "Restore post" action for posts, which Sync never had.
 *
 * <p>Sync's RestoreCommentRequest ({@code r8.h}) asked Pushshift, which now serves moderators only.
 * Arctic Shift's {@code /api/comments/ids} answers in the shape Sync's parser already reads,
 * {@code {"data":[{"id","author","body"}]}}, so the request is sent here and the checked answer is
 * handed to Sync's own {@code parseNetworkResponse}, which stores the author and text.
 *
 * <p>Sync offers the action when the author is {@code [deleted]} or {@code [removed]}; the patch also
 * offers it when the text is Reddit's own removal notice ({@link #removedByReddit}), where Reddit
 * keeps the author. Arctic Shift saves comments seconds after they are posted, so a comment Reddit
 * removed later usually still has its original text there; one removed at once is archived as the
 * notice and counts as not archived.
 *
 * <p>Posts: Sync's post options sheet ({@code PostMoreBottomSheetFragment}) gets a "Restore post" row
 * for a post whose author or text is {@code [deleted]} or {@code [removed]}, or whose text is Reddit's
 * removal notice ({@link #offerPost}). Arctic Shift's {@code /api/posts/ids} supplies the author and
 * selftext, stored in the post's own row the way Sync's "Translate text" stores a translated post:
 * {@code selftext_raw} and {@code selftext_processed} through Sync's own text processing, plus the
 * list preview Sync's post parser derives from them ({@link #preview}) and the author.
 *
 * <p>Only the validated base-36 comment or post ID is sent, over HTTPS to one fixed host, by a client
 * of its own: no Sync interceptors, cookies, Reddit authorization or redirects. Time and size are
 * bounded. When the archive has no usable copy the user sees {@link #NOT_ARCHIVED} or
 * {@link #POST_NOT_ARCHIVED}. Nothing is logged.
 */
public final class ArcticShiftRestore {
    public static final String NOT_ARCHIVED = "This comment isn't in the Arctic Shift archive.";
    public static final String UNAVAILABLE = "Couldn't reach the Arctic Shift archive. Try again later.";
    public static final String FAILED = "Couldn't restore this comment.";
    public static final String POST_NOT_ARCHIVED = "This post isn't in the Arctic Shift archive.";
    public static final String POST_FAILED = "Couldn't restore this post.";
    public static final String RESTORE_POST = "Restore post";
    public static final String RESTORING_POST = "Restoring post";

    static final String HOST = "arctic-shift.photon-reddit.com";
    static final String PATH = "/api/comments/ids";
    static final String POST_PATH = "/api/posts/ids";
    /** Post.Y0() of a comment; the post sheet's restore is for posts. */
    static final int COMMENT_TYPE = 11;
    static final int MAX_ID_LENGTH = 13;

    // Limits are non-final so tests can shorten them.
    static int MAX_RESPONSE_BYTES = 256 * 1024;
    static long CONNECT_TIMEOUT_MS = 10_000;
    static long READ_TIMEOUT_MS = 10_000;
    static long CALL_TIMEOUT_MS = 20_000;

    private static final Pattern BASE36 = Pattern.compile("[0-9a-z]{1," + MAX_ID_LENGTH + "}");
    /** "[ Removed by Reddit ]", and longer forms such as "[ Removed by Reddit in response to a copyright notice. ]". */
    private static final Pattern REMOVED_BY_REDDIT = Pattern.compile("\\[\\s*removed by reddit(?:\\s[^\\]]*)?\\]",
            Pattern.CASE_INSENSITIVE);

    /** What happened to one restore; the message, if any, is what the user sees for a comment or a post. */
    enum Outcome {
        RESTORED(null, null), NOT_ARCHIVED(ArcticShiftRestore.NOT_ARCHIVED, ArcticShiftRestore.POST_NOT_ARCHIVED),
        UNAVAILABLE(ArcticShiftRestore.UNAVAILABLE, ArcticShiftRestore.UNAVAILABLE),
        FAILED(ArcticShiftRestore.FAILED, ArcticShiftRestore.POST_FAILED);

        final String message, postMessage;
        Outcome(String message, String postMessage) { this.message = message; this.postMessage = postMessage; }
    }

    /** A post's archived copy: the author and selftext worth storing (null when not), and Sync's spoiler test. */
    static final class ArchivedPost {
        final String author, selftext;
        final boolean spoiler;
        ArchivedPost(String author, String selftext, boolean spoiler) {
            this.author = author;
            this.selftext = selftext;
            this.spoiler = spoiler;
        }
    }

    interface Platform {
        /** Runs a bounded background job; may throw RejectedExecutionException when busy. */
        void execute(Runnable task);
        /** Shows a short message on Sync's main thread. */
        void toast(String message);
        /** Hands the adapted JSON to Sync's own parser; true when Sync reports success. */
        boolean deliver(Object request, byte[] json) throws Exception;
        /** Adds a row titled {@code title} to Sync's options sheet just above {@code anchor}; a tap runs {@code tap}. */
        void addRow(Object anchor, String title, Runnable tap);
        /** Closes Sync's options sheet. */
        void dismiss(Object sheet);
        /** Sync's unescaping of Reddit's JSON text (WebsafeUtils, wc.p.a). */
        String unescape(String text);
        /** Sync's processing of raw text for display (d7.f.r). */
        String process(String raw);
        /** The plain text of HTML, as Sync's post parser reads it (Html.fromHtml without spans). */
        String htmlText(String html);
        /** Stores these columns in the row with Sync's ID and refreshes Sync's lists. */
        void store(String syncId, Map<String, String> values) throws Exception;
    }

    static Platform platform = new AndroidPlatform();
    private static volatile OkHttpClient client;

    private ArcticShiftRestore() {}

    /**
     * Replaces Sync's queueing of RestoreCommentRequest in the comment options sheet. Called on the
     * main thread with Sync's request and the comment's ID ({@code Post.U()}).
     */
    public static void restore(Object request, String syncId) {
        String id = commentId(syncId);
        if (request == null || id == null) {
            platform.toast(FAILED);
            return;
        }
        try {
            platform.execute(() -> {
                Outcome outcome = run(request, id, syncId);
                if (outcome.message != null) platform.toast(outcome.message);
            });
        } catch (RejectedExecutionException busy) {
            platform.toast(FAILED);
        }
    }

    /**
     * Whether a comment's text is Reddit's own removal notice. Called while Sync builds the comment
     * options sheet, with the comment's raw text ({@code Post.o()}), when its author is not
     * {@code [deleted]} or {@code [removed]}.
     */
    public static boolean removedByReddit(String body) {
        return body != null && REMOVED_BY_REDDIT.matcher(body.trim()).matches();
    }

    /**
     * Adds "Restore post" to Sync's post options sheet. Called once Sync has built the sheet
     * ({@code PostMoreBottomSheetFragment.o2}), with the sheet, its Share row, and the post's ID
     * ({@code Post.U()}), author ({@code Post.e()}), selftext ({@code Post.N0()}) and type
     * ({@code Post.Y0()}). Any failure leaves the sheet as Sync built it.
     */
    public static void offerPost(Object sheet, Object anchor, String syncId, String author, String selftext, int type) {
        try {
            if (sheet == null || anchor == null || type == COMMENT_TYPE || !postOffered(author, selftext)) return;
            platform.addRow(anchor, RESTORE_POST, () -> {
                try {
                    platform.dismiss(sheet);
                } catch (RuntimeException | LinkageError ignored) {
                    // The restore still runs; only the sheet stays open.
                }
                restorePost(syncId);
            });
        } catch (RuntimeException | LinkageError ignored) {
            // Sync's own rows are already in place.
        }
    }

    /** Whether the post sheet offers "Restore post": a deleted or removed author or text, or Reddit's notice. */
    static boolean postOffered(String author, String selftext) {
        return marker(author) || marker(selftext) || removedByReddit(selftext);
    }

    /** The tapped "Restore post": asks the archive in the background and stores what it has. */
    static void restorePost(String syncId) {
        String id = postId(syncId);
        if (id == null) {
            platform.toast(POST_FAILED);
            return;
        }
        platform.toast(RESTORING_POST);
        try {
            platform.execute(() -> {
                Outcome outcome = runPost(id, syncId);
                if (outcome.postMessage != null) platform.toast(outcome.postMessage);
            });
        } catch (RejectedExecutionException busy) {
            platform.toast(POST_FAILED);
        }
    }

    /** The bare base-36 ID Arctic Shift expects, or null. A {@code t1_} prefix is removed. */
    static String commentId(String value) {
        return bareId(value, "t1_");
    }

    /** The bare base-36 post ID Arctic Shift expects, or null. A {@code t3_} prefix is removed. */
    static String postId(String value) {
        return bareId(value, "t3_");
    }

    private static String bareId(String value, String prefix) {
        if (value == null) return null;
        String id = value.startsWith(prefix) ? value.substring(prefix.length()) : value;
        return BASE36.matcher(id).matches() ? id : null;
    }

    /** The only URL this helper requests for a comment. */
    static String url(String id) {
        return "https://" + HOST + PATH + "?ids=" + id + "&fields=id,author,body";
    }

    /** The only URL this helper requests for a post. */
    static String postUrl(String id) {
        return "https://" + HOST + POST_PATH + "?ids=" + id + "&fields=id,author,selftext,spoiler,link_flair_text";
    }

    static Outcome run(Object request, String id, String syncId) {
        byte[] json;
        try {
            String text = fetch(id);
            json = text == null ? null : adapt(id, syncId, text);
        } catch (IOException | RuntimeException unreachable) {
            return Outcome.UNAVAILABLE;
        }
        if (json == null) return Outcome.NOT_ARCHIVED;
        try {
            return platform.deliver(request, json) ? Outcome.RESTORED : Outcome.FAILED;
        } catch (Exception | LinkageError failed) {
            return Outcome.FAILED;
        }
    }

    static Outcome runPost(String id, String syncId) {
        ArchivedPost post;
        try {
            String text = fetch(postUrl(id), POST_PATH);
            post = text == null ? null : adaptPost(id, text);
        } catch (IOException | RuntimeException unreachable) {
            return Outcome.UNAVAILABLE;
        }
        if (post == null) return Outcome.NOT_ARCHIVED;
        try {
            platform.store(syncId, postValues(post));
            return Outcome.RESTORED;
        } catch (Exception | LinkageError failed) {
            return Outcome.FAILED;
        }
    }

    /**
     * The archive's answer for one comment ID, or null when it has none (HTTP 404) or the answer is
     * larger than {@link #MAX_RESPONSE_BYTES}. Other statuses, timeouts and connection errors throw.
     */
    static String fetch(String id) throws IOException {
        return fetch(url(id), PATH);
    }

    /** As {@link #fetch(String)}, for this helper's URL {@code address} whose path is {@code path}. */
    static String fetch(String address, String path) throws IOException {
        Request request = new Request.Builder().url(address).get()
                .header("Accept", "application/json").header("User-Agent", "sync_for_reddit").build();
        Response response = client().newCall(request).execute();
        try {
            HttpUrl url = response.request().url();
            if (!url.isHttps() || !HOST.equals(url.host()) || !path.equals(url.encodedPath()))
                throw new IOException("Unexpected archive URL");
            int code = response.code();
            if (code == 404) return null;
            if (code != 200) throw new IOException("Archive answered HTTP " + code);
            ResponseBody body = response.body();
            return body == null ? null : read(body);
        } finally {
            response.close();
        }
    }

    private static String read(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = body.byteStream()) {
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > MAX_RESPONSE_BYTES) return null;
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Sync's parser input for the archived comment: exactly one entry with Sync's own ID and the
     * archive's author and body. Null when the answer is malformed or has no usable copy of the
     * comment (absent, or itself only "[deleted]", "[removed]" or Reddit's removal notice).
     */
    static byte[] adapt(String id, String syncId, String text) {
        try {
            JSONArray data = new JSONObject(text).optJSONArray("data");
            if (data == null) return null;
            for (int n = 0; n < data.length(); n++) {
                JSONObject item = data.optJSONObject(n);
                if (item == null || !id.equals(item.opt("id"))) continue;
                Object author = item.opt("author"), body = item.opt("body");
                if (!(author instanceof String) || !(body instanceof String) || !restorable((String) body)) continue;
                JSONObject comment = new JSONObject().put("id", syncId).put("author", author).put("body", body);
                return new JSONObject().put("data", new JSONArray().put(comment)).toString().getBytes(StandardCharsets.UTF_8);
            }
            return null;
        } catch (JSONException malformed) {
            return null;
        }
    }

    private static boolean restorable(String body) {
        String text = body.trim();
        return !text.isEmpty() && !marker(text) && !removedByReddit(text);
    }

    /** {@code [deleted]} or {@code [removed]}, which Reddit writes over a deleted or removed author or text. */
    private static boolean marker(String value) {
        if (value == null) return false;
        String text = value.trim();
        return text.equalsIgnoreCase("[deleted]") || text.equalsIgnoreCase("[removed]");
    }

    /**
     * The archived post's author and selftext worth storing, or null when the answer is malformed or
     * has neither. An author that is itself {@code [deleted]} or {@code [removed]} is left out, as is
     * selftext that is one of those or Reddit's notice. Empty selftext is kept only beside an archived
     * author: the post had no text, which replaces Reddit's {@code [deleted]}.
     */
    static ArchivedPost adaptPost(String id, String text) {
        try {
            JSONArray data = new JSONObject(text).optJSONArray("data");
            if (data == null) return null;
            for (int n = 0; n < data.length(); n++) {
                JSONObject item = data.optJSONObject(n);
                if (item == null || !id.equals(item.opt("id"))) continue;
                Object author = item.opt("author"), selftext = item.opt("selftext");
                String name = author instanceof String && !((String) author).trim().isEmpty() && !marker((String) author)
                        ? (String) author : null;
                String body = selftext instanceof String && !marker((String) selftext) && !removedByReddit((String) selftext)
                        ? (String) selftext : null;
                if (body != null && body.trim().isEmpty()) body = name == null ? null : "";
                if (name == null && body == null) continue;
                // As Sync's post parser decides: the spoiler flag, or a flair that mentions spoilers.
                Object flair = item.opt("link_flair_text");
                boolean spoiler = Boolean.TRUE.equals(item.opt("spoiler"))
                        || flair instanceof String && ((String) flair).toLowerCase(Locale.ROOT).contains("spoiler");
                return new ArchivedPost(name, body, spoiler);
            }
            return null;
        } catch (JSONException malformed) {
            return null;
        }
    }

    /**
     * The columns stored for an archived post, filled as Sync's post parser fills them: the author,
     * {@code selftext_raw} unescaped, {@code selftext_processed} from Sync's processing and
     * {@code selftext_processed_preview} for the post list.
     */
    static Map<String, String> postValues(ArchivedPost post) {
        Map<String, String> values = new LinkedHashMap<>();
        if (post.author != null) values.put("author", post.author);
        if (post.selftext != null) {
            String raw = platform.unescape(post.selftext);
            String processed = platform.process(raw);
            values.put("selftext_raw", raw);
            values.put("selftext_processed", processed);
            values.put("selftext_processed_preview", preview(processed, post.spoiler));
        }
        return values;
    }

    /** The post list's preview of processed selftext, step by step as Sync's PostParserHelper derives it. */
    static String preview(String processed, boolean spoiler) {
        String text = processed == null ? null : trimToNull(processed.replaceAll("\\&#x200B\\;", ""));
        if (text != null && text.contains("<table>")) text = null;
        if (spoiler) text = "&lt;Spoiler hidden&gt;";
        if (text == null) return null;
        String[] parts = text.split("\n.");
        boolean more = parts.length > 1;
        if (more) text = parts[0].trim();
        text = platform.htmlText(text).replace("￼", "").trim();
        if (more) text = text + "\n…";
        if (parts[0].trim().contains("<abbr>")) text = "<Spoiler hidden>";
        return trimToNull(text.replace("​", ""));
    }

    private static String trimToNull(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A client of its own: none of Sync's interceptors, no cookie jar, no redirects. */
    private static OkHttpClient client() {
        OkHttpClient current = client;
        if (current == null) {
            synchronized (ArcticShiftRestore.class) {
                if (client == null) client = newClient();
                current = client;
            }
        }
        return current;
    }

    static OkHttpClient newClient() {
        return new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /** Test hook: build the client again with the current limits. */
    static void resetClient() {
        client = null;
    }

    static final class AndroidPlatform implements Platform {
        private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(4), runnable -> {
                    Thread thread = new Thread(runnable, "sync-compat-restore");
                    thread.setDaemon(true);
                    return thread;
                });
        private android.os.Handler main;

        AndroidPlatform() {
            worker.allowCoreThreadTimeOut(true);
        }

        @Override public void execute(Runnable task) {
            worker.execute(task);
        }

        @Override public synchronized void toast(String message) {
            android.content.Context context = Utils.getContext();
            if (context == null) return;
            if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
        }

        /** A row like the sheet's own: Sync's MaterialRow with a title and Sync's restore icon. */
        @Override public void addRow(Object anchor, String title, Runnable tap) {
            android.view.View share = (android.view.View) anchor;
            android.view.ViewGroup parent = (android.view.ViewGroup) share.getParent();
            android.content.Context context = share.getContext();
            MaterialRow row = new MaterialRow(context);
            row.k(title);
            int icon = context.getResources().getIdentifier("outline_restore_24", "drawable", context.getPackageName());
            if (icon != 0) row.d(icon);
            row.setOnClickListener(view -> tap.run());
            parent.addView(row, parent.indexOfChild(share), new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override public void dismiss(Object sheet) {
            ((BottomSheetDialogFragment) sheet).x3();
        }

        @Override public String unescape(String text) {
            return wc.p.a(text);
        }

        @Override public String process(String raw) {
            return d7.f.r(null, raw);
        }

        @Override public String htmlText(String html) {
            CharSequence spanned = android.text.Html.fromHtml(html);
            if (spanned instanceof android.text.SpannableStringBuilder) ((android.text.SpannableStringBuilder) spanned).clearSpans();
            return spanned.toString();
        }

        /** As Sync's "Translate text" stores a post: update the row by ID, then notify Sync's post lists. */
        @Override public void store(String syncId, Map<String, String> values) {
            android.content.ContentValues columns = new android.content.ContentValues();
            for (Map.Entry<String, String> value : values.entrySet()) columns.put(value.getKey(), value.getValue());
            android.content.ContentResolver resolver = Utils.getContext().getContentResolver();
            resolver.update(RedditProvider.q, columns, syncId, null);
            resolver.notifyChange(RedditProvider.B, null);
        }

        /** RestoreCommentRequest overrides Volley's protected parseNetworkResponse as public. */
        @Override public boolean deliver(Object request, byte[] json) throws Exception {
            Method parse = request.getClass().getMethod("parseNetworkResponse", NetworkResponse.class);
            Object result;
            try {
                result = parse.invoke(request, new NetworkResponse(json));
            } catch (InvocationTargetException failed) {
                return false;
            }
            return result instanceof com.android.volley.Response && ((com.android.volley.Response<?>) result).isSuccess();
        }
    }
}
