package app.revanced.extension.synccompat;

import app.revanced.extension.shared.Utils;
import com.android.volley.NetworkResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
 * Sync's "Restore comment" action, answered by the Arctic Shift archive instead of Pushshift.
 *
 * <p>Sync's RestoreCommentRequest ({@code r8.h}) asked Pushshift, which now serves moderators only.
 * Arctic Shift's {@code /api/comments/ids} answers in the shape Sync's parser already reads,
 * {@code {"data":[{"id","author","body"}]}}, so the request is sent here and the checked answer is
 * handed to Sync's own {@code parseNetworkResponse}, which stores the author and text.
 *
 * <p>Only the validated base-36 comment ID is sent, over HTTPS to one fixed host, by a client of its
 * own: no Sync interceptors, cookies, Reddit authorization or redirects. Time and size are bounded.
 * When the archive has no usable copy the user sees {@link #NOT_ARCHIVED}. Nothing is logged.
 */
public final class ArcticShiftRestore {
    public static final String NOT_ARCHIVED = "This comment isn't in the Arctic Shift archive.";
    public static final String UNAVAILABLE = "Couldn't reach the Arctic Shift archive. Try again later.";
    public static final String FAILED = "Couldn't restore this comment.";

    static final String HOST = "arctic-shift.photon-reddit.com";
    static final String PATH = "/api/comments/ids";
    static final int MAX_ID_LENGTH = 13;

    // Limits are non-final so tests can shorten them.
    static int MAX_RESPONSE_BYTES = 256 * 1024;
    static long CONNECT_TIMEOUT_MS = 10_000;
    static long READ_TIMEOUT_MS = 10_000;
    static long CALL_TIMEOUT_MS = 20_000;

    private static final Pattern BASE36 = Pattern.compile("[0-9a-z]{1," + MAX_ID_LENGTH + "}");

    /** What happened to one restore; the message, if any, is what the user sees. */
    enum Outcome {
        RESTORED(null), NOT_ARCHIVED(ArcticShiftRestore.NOT_ARCHIVED),
        UNAVAILABLE(ArcticShiftRestore.UNAVAILABLE), FAILED(ArcticShiftRestore.FAILED);

        final String message;
        Outcome(String message) { this.message = message; }
    }

    interface Platform {
        /** Runs a bounded background job; may throw RejectedExecutionException when busy. */
        void execute(Runnable task);
        /** Shows a short message on Sync's main thread. */
        void toast(String message);
        /** Hands the adapted JSON to Sync's own parser; true when Sync reports success. */
        boolean deliver(Object request, byte[] json) throws Exception;
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

    /** The bare base-36 ID Arctic Shift expects, or null. A {@code t1_} prefix is removed. */
    static String commentId(String value) {
        if (value == null) return null;
        String id = value.startsWith("t1_") ? value.substring(3) : value;
        return BASE36.matcher(id).matches() ? id : null;
    }

    /** The only URL this helper requests. */
    static String url(String id) {
        return "https://" + HOST + PATH + "?ids=" + id + "&fields=id,author,body";
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

    /**
     * The archive's answer for one ID, or null when it has none (HTTP 404) or the answer is larger
     * than {@link #MAX_RESPONSE_BYTES}. Other statuses, timeouts and connection errors throw.
     */
    static String fetch(String id) throws IOException {
        Request request = new Request.Builder().url(url(id)).get()
                .header("Accept", "application/json").header("User-Agent", "sync_for_reddit").build();
        Response response = client().newCall(request).execute();
        try {
            HttpUrl url = response.request().url();
            if (!url.isHttps() || !HOST.equals(url.host()) || !PATH.equals(url.encodedPath()))
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
     * comment (absent, or itself only "[deleted]"/"[removed]").
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
        return !text.isEmpty() && !text.equalsIgnoreCase("[deleted]") && !text.equalsIgnoreCase("[removed]");
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
