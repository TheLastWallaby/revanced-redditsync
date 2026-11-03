package app.revanced.extension.synccompat;

import android.content.Context;
import android.net.Uri;
import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sync's image uploads, hosted by Reddit instead of Imgur.
 *
 * <p>Sync 23033 sends every picked image to Imgur ({@code UploadManager.a}, {@code u8.d.a}) and
 * hands the resulting link to its caller: the submit screen puts it in the URL field of an image
 * post ({@code SubmitFragment}, {@code ma.n}), and the text editor offers it in its "Insert image"
 * dialog ({@code EditorView.h}). This helper replaces the upload with Reddit's media-asset flow:
 *
 * <ol>
 *   <li>{@code POST https://oauth.reddit.com/api/media/asset.json} with the image's MIME type, sent
 *       as one of Sync's own OAuth requests ({@code x8.a} through {@code m8.a.c}), so Sync fetches,
 *       refreshes and attaches the bearer token as it does for every Reddit call;</li>
 *   <li>a multipart {@code POST} of Reddit's upload fields and the image to the returned action,
 *       accepted only over HTTPS on Reddit's upload host, by a client of its own: no Reddit
 *       authorization, no cookies, no redirects.</li>
 * </ol>
 *
 * The uploaded asset is used the way Reddit expects. An image post is submitted as
 * {@code kind=image} and identified through the websocket Reddit names in its reply. In a comment
 * or text post the editor inserts {@code ![img](<asset id>)}; when that text is sent, Reddit's own
 * converter ({@code /api/convert_rte_body_format}) turns it into {@code richtext_json}, and the
 * comment reply is adapted back to the shape Sync's parser reads.
 *
 * <p>Every failure keeps the draft and shows a specific message. Size and time are bounded. Nothing
 * is logged: no token, upload field, signature, websocket address or image data.
 */
public final class RedditImageUpload {
    // What the user sees. Upload errors reach Sync's upload callers, which show them as they are.
    public static final String NOT_SIGNED_IN = "Sign in to a Reddit account to upload images.";
    public static final String NO_IMAGE = "No image selected";
    public static final String UNREADABLE = "Couldn't read this image.";
    public static final String UNSUPPORTED = "Reddit accepts JPEG, PNG, GIF and WebP images only.";
    public static final String TOO_LARGE = "This image is larger than the 20 MB upload limit.";
    public static final String SIGN_IN_AGAIN = "Reddit didn't accept your sign-in. Sign in again, then retry.";
    public static final String NEEDS_PERMISSION = "Reddit needs a new permission for image uploads. Sign in to your account again, then retry.";
    public static final String REFUSED = "Reddit refused the image upload (HTTP %d).";
    public static final String RATE_LIMITED = "Reddit is limiting requests right now. Wait a minute and try again.";
    public static final String REDDIT_UNAVAILABLE = "Reddit isn't available right now (HTTP %d). Try again later.";
    public static final String OFFLINE = "Couldn't reach Reddit. Check your connection and try again.";
    public static final String TIMED_OUT = "The image upload timed out. Try again.";
    public static final String MALFORMED = "Reddit sent an unexpected reply. Try again later.";
    public static final String BAD_HOST = "Reddit named an unexpected upload address, so the image wasn't sent.";
    public static final String HOST_REFUSED = "Reddit's image host refused the upload (HTTP %d).";
    public static final String BUSY = "Another image is still uploading. Try again in a moment.";
    public static final String MEDIA_NOT_ALLOWED = "This subreddit doesn't allow images in comments. Remove the image and try again.";
    public static final String IMAGE_NOT_RECOGNIZED = "Reddit didn't recognize the image in this text. Remove it, add it again and retry.";
    public static final String UNSUPPORTED_PLACE = "Reddit images can only be added to new comments, replies and text posts. Remove the image to send this.";
    public static final String POST_FAILED = "Reddit couldn't process this image, so the post wasn't created.";
    public static final String POST_UNCONFIRMED = "Reddit accepted the image post but didn't confirm it. Check your profile before posting again.";

    static final String LEASE_URL = "https://oauth.reddit.com/api/media/asset.json?raw_json=1";
    static final String CONVERT_URL = "https://oauth.reddit.com/api/convert_rte_body_format?raw_json=1";
    static final String OAUTH_HOST = "oauth.reddit.com";
    /** Reddit's image upload host (PRAW's recorded leases; Infinity's API_UPLOAD_MEDIA_URI). */
    static final Set<String> UPLOAD_HOSTS = Collections.singleton("reddit-uploaded-media.s3-accelerate.amazonaws.com");
    static final String DEFAULT_UPLOAD_HOST = "reddit-uploaded-media.s3-accelerate.amazonaws.com";
    /** Reddit's media websockets: k8s-lb.wss.redditmedia.com and ws-<id>.wss.redditmedia.com. */
    static final String SOCKET_HOST_SUFFIX = ".wss.redditmedia.com";
    static final String COMMENT_PATH = "/api/comment";
    static final String SUBMIT_PATH = "/api/submit";

    // Limits are non-final so tests can shorten them.
    static int MAX_IMAGE_BYTES = 20_000_000;
    static int MAX_REPLY_BYTES = 1024 * 1024;
    static int LEASE_TIMEOUT_MS = 20_000;
    static int CONVERT_TIMEOUT_MS = 20_000;
    static long UPLOAD_CONNECT_MS = 15_000;
    static long UPLOAD_READ_MS = 30_000;
    static long UPLOAD_CALL_MS = 120_000;
    static long TOTAL_TIMEOUT_MS = 180_000;
    static long SOCKET_WAIT_MS = 30_000;
    static int REGISTRY_SIZE = 32;

    private static final Pattern MEDIA_ID = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    /** An image the editor inserted: ![img](<id>) or ![gif](<id>), optionally with a "caption". */
    static final Pattern MARKER = Pattern.compile("!\\[(img|gif)\\]\\(([A-Za-z0-9]{1,40})(?:[ \\t]+\"[^\"\\n]*\")?\\)");
    private static final Pattern POST_PATH = Pattern.compile("/(?:r/[A-Za-z0-9_]{1,30}/|u(?:ser)?/[A-Za-z0-9_-]{1,30}/)?comments/([a-z0-9]{1,13})(?:/.*)?");

    /** Android and Sync services; replaced by tests. */
    interface Platform {
        /** Runs on a bounded background worker; may throw RejectedExecutionException. */
        void background(Runnable task);
        /** Runs on Sync's main thread. */
        void main(Runnable task);
        /** Runs on Sync's main thread after a delay. */
        void later(long delayMs, Runnable task);
        boolean signedIn();
        InputStream open(Context context, Uri uri) throws IOException;
        /** Queues one of Sync's OAuth requests: m8.a.c, which fetches or refreshes the token. */
        void queue(Context context, Request<?> request);
        /** Sync's request parameters (x8.a.s). */
        Map<String, String> params(Object request);
        /** Sends Sync's request on as it was going: m8.a.b, or m8.a.g for another account. */
        void resend(Context context, Object request, String token, boolean exchange);
        /** Reports a send failure the way Sync does for that request, keeping its text. */
        void fail(Object request, String message);
    }

    static Platform platform = new AndroidPlatform();

    private static final Map<String, Asset> UPLOADS = new LinkedHashMap<String, Asset>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Asset> eldest) {
            return size() > REGISTRY_SIZE;
        }
    };
    private static final Map<Object, Boolean> IMAGE_POSTS = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Boolean> CONVERTED = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile OkHttpClient uploadClient;
    private static volatile OkHttpClient socketClient;

    private RedditImageUpload() {}

    // ---- Upload: replaces UploadManager.a (u8.d.a) ----

    /**
     * Uploads {@code uri} to Reddit and hands the asset's URL to Sync's listener, or a
     * {@link UploadError} (Sync shows its message) to the error listener. Called on the main thread.
     */
    public static void upload(Context context, Uri uri, Response.Listener<String> listener, Response.ErrorListener errorListener) {
        Job job = new Job(context, uri, listener, errorListener);
        if (uri == null) { job.fail(NO_IMAGE); return; }
        boolean signedIn;
        try {
            signedIn = platform.signedIn();
        } catch (RuntimeException | LinkageError unknown) {
            signedIn = false;
        }
        if (!signedIn) { job.fail(NOT_SIGNED_IN); return; }
        job.start();
    }

    /** One upload: read, lease, post to the host; exactly one result reaches Sync. */
    static final class Job {
        final Context context;
        final Uri uri;
        final Response.Listener<String> listener;
        final Response.ErrorListener errorListener;
        final AtomicBoolean done = new AtomicBoolean();
        volatile Request<?> lease;
        volatile Call call;

        Job(Context context, Uri uri, Response.Listener<String> listener, Response.ErrorListener errorListener) {
            this.context = context;
            this.uri = uri;
            this.listener = listener;
            this.errorListener = errorListener;
        }

        void start() {
            platform.later(TOTAL_TIMEOUT_MS, () -> fail(TIMED_OUT));
            try {
                platform.background(this::read);
            } catch (RejectedExecutionException busy) {
                fail(BUSY);
            }
        }

        private void read() {
            Image image;
            try (InputStream in = platform.open(context, uri)) {
                if (in == null) { fail(UNREADABLE); return; }
                image = Image.read(in);
            } catch (UploadFailure failure) {
                fail(failure.getMessage());
                return;
            } catch (IOException | RuntimeException unreadable) {
                fail(UNREADABLE);
                return;
            }
            platform.main(() -> lease(image));
        }

        private void lease(Image image) {
            if (done.get()) return;
            LeaseRequest request = new LeaseRequest(context, image.mime,
                    lease -> leased(image, lease), error -> fail(message(error)));
            lease = request;
            try {
                platform.queue(context, request);
            } catch (RuntimeException | LinkageError failed) {
                fail(OFFLINE);
            }
        }

        private void leased(Image image, Lease lease) {
            if (done.get()) return;
            try {
                platform.background(() -> post(image, lease));
            } catch (RejectedExecutionException busy) {
                fail(BUSY);
            }
        }

        private void post(Image image, Lease lease) {
            String url;
            try {
                url = postToHost(lease, image, this);
            } catch (UploadFailure failure) {
                fail(failure.getMessage());
                return;
            } catch (InterruptedIOException timeout) {
                fail(TIMED_OUT);
                return;
            } catch (IOException | RuntimeException unreachable) {
                fail(done.get() ? TIMED_OUT : OFFLINE);
                return;
            }
            if (done.get()) return;
            synchronized (UPLOADS) {
                UPLOADS.put(url, new Asset(lease.mediaId, "image/gif".equals(image.mime)));
            }
            platform.main(() -> succeed(url));
        }

        void succeed(String url) {
            if (!done.compareAndSet(false, true)) return;
            if (listener != null) listener.onResponse(url);
        }

        void fail(String message) {
            if (!done.compareAndSet(false, true)) return;
            Request<?> pending = lease;
            if (pending != null) pending.cancel();
            Call running = call;
            if (running != null) running.cancel();
            platform.main(() -> {
                if (errorListener != null) errorListener.onErrorResponse(new UploadError(message));
            });
        }
    }

    /**
     * Sync's upload callers ({@code UploadImageDialogFragment$c}, {@code SubmitFragment$i}) show
     * the message of their Imgur error type ({@code u8.b}) and a generic "Failed to upload image"
     * for anything else, so errors are delivered as that type with a specific message.
     */
    public static final class UploadError extends u8.b {
        private final String text;

        UploadError(String text) {
            this.text = text;
        }

        @Override public String getMessage() {
            return text;
        }
    }

    // ---- The image ----

    static final class Image {
        final byte[] bytes;
        final String mime;
        final String extension;

        private Image(byte[] bytes, String mime, String extension) {
            this.bytes = bytes;
            this.mime = mime;
            this.extension = extension;
        }

        /** Reads the image once, at most {@link #MAX_IMAGE_BYTES}, and identifies it by its header. */
        static Image read(InputStream in) throws IOException, UploadFailure {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if ((long) out.size() + n > MAX_IMAGE_BYTES) throw new UploadFailure(TOO_LARGE);
                out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) throw new UploadFailure(UNREADABLE);
            String[] type = sniff(bytes);
            if (type == null) throw new UploadFailure(UNSUPPORTED);
            return new Image(bytes, type[0], type[1]);
        }
    }

    /** MIME type and file extension from the file's own header, or null for anything else. */
    static String[] sniff(byte[] b) {
        if (starts(b, 0xFF, 0xD8, 0xFF)) return new String[] {"image/jpeg", "jpg"};
        if (starts(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return new String[] {"image/png", "png"};
        if (starts(b, 'G', 'I', 'F', '8', '7', 'a') || starts(b, 'G', 'I', 'F', '8', '9', 'a')) return new String[] {"image/gif", "gif"};
        if (b.length >= 12 && starts(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return new String[] {"image/webp", "webp"};
        return null;
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xFF) != prefix[i]) return false;
        return true;
    }

    static final class UploadFailure extends Exception {
        UploadFailure(String message) {
            super(message, null, false, false);
        }
    }

    // ---- The lease: one of Sync's OAuth requests ----

    /** Reddit's upload form for one image. */
    static final class Lease {
        final String host;
        final List<String[]> fields;
        final String key;
        final String mediaId;

        Lease(String host, List<String[]> fields, String key, String mediaId) {
            this.host = host;
            this.fields = fields;
            this.key = key;
            this.mediaId = mediaId;
        }

        String action() {
            return "https://" + host + "/";
        }

        /** The asset's URL, as Reddit's upload host reports it (Location) and Reddit expects it in a post. */
        String url() {
            return "https://" + host + "/" + key;
        }
    }

    /**
     * Reads Reddit's lease: {@code {"args": {"action", "fields": [{"name", "value"}]}, "asset":
     * {"asset_id"}}} (or the same form without the {@code args} wrapper). Throws
     * {@link UploadFailure} with {@link #BAD_HOST} for an action anywhere but Reddit's upload host,
     * and {@link #MALFORMED} for anything else unusable.
     */
    static Lease parseLease(byte[] data) throws UploadFailure {
        if (data == null || data.length > MAX_REPLY_BYTES) throw new UploadFailure(MALFORMED);
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONObject form = root.optJSONObject("args") != null ? root.getJSONObject("args") : root;
            Object action = form.opt("action");
            String host = action == null ? DEFAULT_UPLOAD_HOST : uploadHost(action);
            if (host == null) throw new UploadFailure(BAD_HOST);
            JSONArray list = form.getJSONArray("fields");
            if (list.length() == 0 || list.length() > 64) throw new UploadFailure(MALFORMED);
            List<String[]> fields = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            String key = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject field = list.getJSONObject(i);
                Object name = field.opt("name"), value = field.opt("value");
                if (!(name instanceof String) || !(value instanceof String) || ((String) name).isEmpty()
                        || ((String) value).length() > 16 * 1024 || "file".equals(name) || !names.add((String) name)
                        || ((String) name).indexOf('"') >= 0 || ((String) name).indexOf('\r') >= 0 || ((String) name).indexOf('\n') >= 0)
                    throw new UploadFailure(MALFORMED);
                fields.add(new String[] {(String) name, (String) value});
                if ("key".equals(name)) key = (String) value;
            }
            if (key == null || !KEY.matcher(key).matches() || key.contains("..") || key.contains("//"))
                throw new UploadFailure(MALFORMED);
            JSONObject asset = root.optJSONObject("asset");
            Object assetId = asset == null ? null : asset.opt("asset_id");
            String mediaId = assetId instanceof String ? (String) assetId : key;
            if (!MEDIA_ID.matcher(mediaId).matches()) throw new UploadFailure(MALFORMED);
            return new Lease(host, Collections.unmodifiableList(fields), key, mediaId);
        } catch (JSONException malformed) {
            throw new UploadFailure(MALFORMED);
        }
    }

    /** The host of an HTTPS action on Reddit's upload host, or null. Reddit sends it scheme-relative. */
    static String uploadHost(Object action) {
        if (!(action instanceof String)) return null;
        String text = (String) action;
        if (text.startsWith("//")) text = "https:" + text;
        try {
            URI uri = new URI(text);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !path.equals("/")))
                return null;
            host = host.toLowerCase(Locale.ROOT);
            return UPLOAD_HOSTS.contains(host) ? host : null;
        } catch (URISyntaxException bad) {
            return null;
        }
    }

    /** {@code POST /api/media/asset.json}, sent and authorized by Sync. */
    static final class LeaseRequest extends x8.a<Lease> {
        private final String mime;

        LeaseRequest(Context context, String mime, Response.Listener<Lease> listener, Response.ErrorListener errorListener) {
            super(context, Request.Method.POST, LEASE_URL, listener, errorListener);
            this.mime = mime;
            setShouldCache(false);
            setRetryPolicy(new DefaultRetryPolicy(LEASE_TIMEOUT_MS, 0, 1f));
        }

        @Override protected Map<String, String> getParams() {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("api_type", "json");
            params.put("mimetype", mime);
            return params;
        }

        @Override public Map<String, String> getHeaders() throws AuthFailureError {
            return onlyForReddit(getUrl(), super.getHeaders());
        }

        @Override protected Response<Lease> parseNetworkResponse(NetworkResponse response) {
            try {
                return Response.success(parseLease(response.data), null);
            } catch (UploadFailure failure) {
                return Response.error(new ReplyError(failure.getMessage()));
            }
        }
    }

    /** Sync's headers carry its bearer token; they go to oauth.reddit.com only. */
    static Map<String, String> onlyForReddit(String url, Map<String, String> headers) {
        Map<String, String> result = headers == null ? new HashMap<>() : new HashMap<>(headers);
        boolean reddit;
        try {
            URI uri = new URI(url);
            reddit = "https".equalsIgnoreCase(uri.getScheme()) && OAUTH_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (URISyntaxException | RuntimeException bad) {
            reddit = false;
        }
        if (!reddit) {
            result.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie"));
        }
        return result;
    }

    /** A reply Sync's request could not use; the message is what the user sees. */
    static final class ReplyError extends ParseError {
        private final String text;

        ReplyError(String text) {
            this.text = text;
        }

        @Override public String getMessage() {
            return text;
        }
    }

    /** What the user sees for a failed Reddit request. */
    static String message(VolleyError error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReplyError) return cause.getMessage();
            if (cause instanceof VolleyError && ((VolleyError) cause).networkResponse != null) {
                NetworkResponse response = ((VolleyError) cause).networkResponse;
                int code = response.statusCode;
                if (code == 401) return SIGN_IN_AGAIN;
                if (code == 403) return insufficientScope(response) ? NEEDS_PERMISSION : String.format(Locale.ROOT, REFUSED, code);
                if (code == 429) return RATE_LIMITED;
                if (code >= 500) return String.format(Locale.ROOT, REDDIT_UNAVAILABLE, code);
                if (code >= 400) return String.format(Locale.ROOT, REFUSED, code);
            }
            if (cause instanceof TimeoutError || cause instanceof InterruptedIOException) return TIMED_OUT;
            if (cause.getClass().getName().equals("com.android.volley.errors.OAuthError")) return SIGN_IN_AGAIN;
            if (cause instanceof ParseError) return MALFORMED;
        }
        return OFFLINE;
    }

    private static boolean insufficientScope(NetworkResponse response) {
        if (response.headers != null) {
            for (Map.Entry<String, String> header : response.headers.entrySet()) {
                if ("www-authenticate".equalsIgnoreCase(header.getKey()) && header.getValue() != null
                        && header.getValue().contains("insufficient_scope")) return true;
            }
        }
        return false;
    }

    // ---- Posting the image to Reddit's upload host ----

    /** Posts Reddit's fields, in order, and then the image; returns the asset's URL. */
    static String postToHost(Lease lease, Image image, Job job) throws IOException, UploadFailure {
        MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (String[] field : lease.fields) form.addFormDataPart(field[0], field[1]);
        form.addFormDataPart("file", "image." + image.extension, RequestBody.create(image.bytes, MediaType.get(image.mime)));
        okhttp3.Request request = new okhttp3.Request.Builder().url(lease.action()).post(form.build()).build();
        Call call = uploadClient().newCall(request);
        if (job != null) {
            job.call = call;
            if (job.done.get()) call.cancel();
        }
        okhttp3.Response response = call.execute();
        try {
            HttpUrl url = response.request().url();
            if (!url.isHttps() || !lease.host.equals(url.host())) throw new UploadFailure(BAD_HOST);
            int code = response.code();
            String body = read(response.body(), 64 * 1024);
            if (code == 200 || code == 201 || code == 204) {
                String key = xmlValue(body, "Key");
                if (key != null && !key.equals(lease.key)) throw new UploadFailure(MALFORMED);
                return lease.url();
            }
            if (body != null && "EntityTooLarge".equals(xmlValue(body, "Code"))) throw new UploadFailure(TOO_LARGE);
            throw new UploadFailure(String.format(Locale.ROOT, HOST_REFUSED, code));
        } finally {
            response.close();
        }
    }

    private static String read(ResponseBody body, int limit) throws IOException {
        if (body == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = body.byteStream()) {
            if (in == null) return null;
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > limit) return null;
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The text of the first {@code <tag>} in S3's small XML reply, or null. */
    static String xmlValue(String xml, String tag) {
        if (xml == null) return null;
        int start = xml.indexOf("<" + tag + ">");
        if (start < 0) return null;
        start += tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">", start);
        return end < 0 ? null : xml.substring(start, end).trim();
    }

    /** A client of its own: none of Sync's interceptors, OkHttp's no-cookie jar, no redirects. */
    private static OkHttpClient uploadClient() {
        OkHttpClient current = uploadClient;
        if (current == null) {
            synchronized (RedditImageUpload.class) {
                if (uploadClient == null) {
                    uploadClient = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                            .connectTimeout(UPLOAD_CONNECT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(UPLOAD_READ_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(UPLOAD_READ_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(UPLOAD_CALL_MS, TimeUnit.MILLISECONDS)
                            .build();
                }
                current = uploadClient;
            }
        }
        return current;
    }

    private static OkHttpClient socketClient() {
        OkHttpClient current = socketClient;
        if (current == null) {
            synchronized (RedditImageUpload.class) {
                if (socketClient == null) {
                    socketClient = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                            .connectTimeout(UPLOAD_CONNECT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(SOCKET_WAIT_MS, TimeUnit.MILLISECONDS)
                            .build();
                }
                current = socketClient;
            }
        }
        return current;
    }

    /** Test hook: build the clients again with the current limits. */
    static void resetClients() {
        uploadClient = null;
        socketClient = null;
    }

    /** Test hook: forget earlier uploads and tagged requests. */
    static void resetState() {
        synchronized (UPLOADS) {
            UPLOADS.clear();
        }
        IMAGE_POSTS.clear();
        CONVERTED.clear();
    }

    static final class Asset {
        final String mediaId;
        final boolean gif;

        Asset(String mediaId, boolean gif) {
            this.mediaId = mediaId;
            this.gif = gif;
        }
    }

    private static Asset asset(String url) {
        if (url == null) return null;
        synchronized (UPLOADS) {
            return UPLOADS.get(url);
        }
    }

    // ---- The editor: EditorView.h ----

    /**
     * The text EditorView inserts for an image this helper uploaded, instead of Sync's "Insert
     * image" link dialog; null for any other URL, which keeps Sync's dialog.
     */
    public static String editorMarkdown(String url) {
        Asset asset = asset(url);
        if (asset == null) return null;
        return "\n\n![" + (asset.gif ? "gif" : "img") + "](" + asset.mediaId + ")\n\n";
    }

    // ---- Sending text: m8.a.b and m8.a.g ----

    enum Target { NONE, CONVERT, UNSUPPORTED }

    /** m8.a.b(context, token, request): true when this helper takes the request over. */
    public static boolean sendDirect(Context context, String token, Object request, String url) {
        return send(context, request, url, token, false);
    }

    /** m8.a.g(context, request, refreshToken): true when this helper takes the request over. */
    public static boolean sendExchange(Context context, Object request, String refreshToken, String url) {
        return send(context, request, url, refreshToken, true);
    }

    static boolean send(Context context, Object request, String url, String token, boolean exchange) {
        if (request == null || url == null) return false;
        Map<String, String> params;
        try {
            params = platform.params(request);
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
        Target target = target(url, params);
        if (target == Target.NONE) return false;
        String text = params.get("text");
        if (text == null) return false;
        Set<String> ids = markers(text);
        if (ids.isEmpty()) return false;
        if (target == Target.UNSUPPORTED) {
            // Only images uploaded in this session; existing text in edits and messages is never touched.
            for (String id : ids) {
                if (uploadedId(id)) {
                    platform.fail(request, UNSUPPORTED_PLACE);
                    return true;
                }
            }
            return false;
        }
        ConvertRequest convert = new ConvertRequest(context, text, ids, document -> {
            params.remove("text");
            params.put("richtext_json", document);
            CONVERTED.put(request, Boolean.TRUE);
            platform.resend(context, request, token, exchange);
        }, error -> platform.fail(request, message(error)));
        try {
            platform.queue(context, convert);
        } catch (RuntimeException | LinkageError failed) {
            platform.fail(request, OFFLINE);
        }
        return true;
    }

    /**
     * New comments and replies ({@code api/comment} on a post or comment) and new text posts
     * ({@code api/submit} kind self) carry images; message replies, edits and messages do not.
     */
    static Target target(String url, Map<String, String> params) {
        if (params == null) return Target.NONE;
        String path;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !OAUTH_HOST.equalsIgnoreCase(uri.getHost())) return Target.NONE;
            path = uri.getRawPath();
        } catch (URISyntaxException bad) {
            return Target.NONE;
        }
        if (path == null) return Target.NONE;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String thing = params.get("thing_id");
        if (path.equals(COMMENT_PATH)) {
            return thing != null && (thing.startsWith("t1_") || thing.startsWith("t3_")) ? Target.CONVERT : Target.UNSUPPORTED;
        }
        if (path.equals(SUBMIT_PATH)) return "self".equals(params.get("kind")) ? Target.CONVERT : Target.NONE;
        if (path.equals("/api/editusertext") || path.equals("/api/compose")) return Target.UNSUPPORTED;
        return Target.NONE;
    }

    /** The asset IDs of the images inserted in {@code text}. */
    static Set<String> markers(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) ids.add(matcher.group(2));
        return ids;
    }

    private static boolean uploadedId(String id) {
        synchronized (UPLOADS) {
            for (Asset asset : UPLOADS.values()) if (asset.mediaId.equals(id)) return true;
        }
        return false;
    }

    /** {@code POST /api/convert_rte_body_format}: Reddit's own Markdown to richtext_json converter. */
    static final class ConvertRequest extends x8.a<String> {
        private final String markdown;
        private final Set<String> ids;

        ConvertRequest(Context context, String markdown, Set<String> ids, Response.Listener<String> listener,
                       Response.ErrorListener errorListener) {
            super(context, Request.Method.POST, CONVERT_URL, listener, errorListener);
            this.markdown = markdown;
            this.ids = ids;
            setShouldCache(false);
            setRetryPolicy(new DefaultRetryPolicy(CONVERT_TIMEOUT_MS, 0, 1f));
        }

        @Override protected Map<String, String> getParams() {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("api_type", "json");
            params.put("markdown_text", markdown);
            params.put("output_mode", "rtjson");
            return params;
        }

        @Override public Map<String, String> getHeaders() throws AuthFailureError {
            return onlyForReddit(getUrl(), super.getHeaders());
        }

        @Override protected Response<String> parseNetworkResponse(NetworkResponse response) {
            try {
                return Response.success(document(response.data, ids), null);
            } catch (UploadFailure failure) {
                return Response.error(new ReplyError(failure.getMessage()));
            }
        }
    }

    /** The converter's {@code output} document, when it holds every inserted image as media. */
    static String document(byte[] data, Set<String> ids) throws UploadFailure {
        if (data == null || data.length > MAX_REPLY_BYTES) throw new UploadFailure(MALFORMED);
        try {
            JSONObject output = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONObject("output");
            JSONArray document = output.getJSONArray("document");
            Set<String> media = new LinkedHashSet<>();
            collectMedia(document, media);
            if (!media.containsAll(ids)) throw new UploadFailure(IMAGE_NOT_RECOGNIZED);
            return output.toString();
        } catch (JSONException malformed) {
            throw new UploadFailure(MALFORMED);
        }
    }

    private static void collectMedia(Object node, Set<String> media) {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectMedia(array.opt(i), media);
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String type = object.optString("e");
            if (("img".equals(type) || "gif".equals(type)) && object.opt("id") instanceof String) media.add(object.optString("id"));
            collectMedia(object.opt("c"), media);
        }
    }

    // ---- Comment replies: OAuthCommentRequest.parseNetworkResponse (y8.e) ----

    /**
     * With {@code richtext_json} Reddit answers a new comment with the bare comment instead of
     * {@code {"json": {"errors": [], "data": {"things": [...]}}}}; that shape is restored for Sync's
     * parser, which looks for the literal text {@code "errors": []}. Reddit's error for a subreddit
     * that doesn't allow images in comments gets a clear message. Other requests pass unchanged.
     */
    public static NetworkResponse commentResponse(Object request, NetworkResponse response) {
        if (request == null || response == null || response.data == null || !CONVERTED.containsKey(request)) return response;
        try {
            JSONObject root = new JSONObject(new String(response.data, StandardCharsets.UTF_8));
            JSONObject json = root.optJSONObject("json");
            if (json != null) {
                JSONArray errors = json.optJSONArray("errors");
                if (errors == null || errors.length() == 0) return response;
                boolean changed = false;
                for (int i = 0; i < errors.length(); i++) {
                    JSONArray error = errors.optJSONArray(i);
                    if (error != null && "META_MEDIA_TYPE_NOT_ALLOWED".equals(error.optString(0)) && error.length() >= 2) {
                        error.put(1, MEDIA_NOT_ALLOWED);
                        changed = true;
                    }
                }
                return changed ? copy(response, root.toString()) : response;
            }
            JSONObject thing;
            if (root.optJSONObject("data") != null && root.opt("kind") instanceof String) thing = root;
            else if (root.optString("name").startsWith("t1_") || (root.has("id") && root.has("body")))
                thing = new JSONObject().put("kind", "t1").put("data", root);
            else return response;
            return copy(response, "{\"json\": {\"errors\": [], \"data\": {\"things\": [" + thing + "]}}}");
        } catch (JSONException malformed) {
            return response;
        }
    }

    // ---- Image posts: OAuthSubmitRequest (y8.x0) ----

    /**
     * End of OAuthSubmitRequest's constructor: a link post whose URL is an image this helper just
     * uploaded becomes Reddit's image post ({@code kind=image}, the same URL). Everything else,
     * including a link a user typed, is left as Sync built it.
     */
    public static void submitParams(Object request, Map<String, String> params) {
        if (request == null || params == null) return;
        if ("link".equals(params.get("kind")) && asset(params.get("url")) != null) {
            params.put("kind", "image");
            IMAGE_POSTS.put(request, Boolean.TRUE);
        }
    }

    /**
     * Start of OAuthSubmitRequest.parseNetworkResponse. Reddit answers an image post with a
     * websocket instead of the post's ID; the post is identified there, and Sync's parser receives
     * {@code {"json": {"errors": [], "data": {"id": "<id>"}}}}, or an error it shows. Called on
     * Volley's network thread; the wait is bounded by {@link #SOCKET_WAIT_MS}.
     */
    public static NetworkResponse submitResponse(Object request, NetworkResponse response) {
        if (request == null || response == null || response.data == null || !IMAGE_POSTS.containsKey(request)) return response;
        String socket;
        try {
            JSONObject json = new JSONObject(new String(response.data, StandardCharsets.UTF_8)).getJSONObject("json");
            JSONArray errors = json.optJSONArray("errors");
            if (errors != null && errors.length() > 0) return response;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return error(response, MALFORMED);
            if (data.opt("id") instanceof String) return response;
            socket = data.optString("websocket_url", null);
        } catch (JSONException malformed) {
            return response;
        }
        if (!socketUrl(socket)) return error(response, POST_UNCONFIRMED);
        String[] outcome = awaitPost(socket);
        if (outcome[0] != null) return copy(response, "{\"json\": {\"errors\": [], \"data\": {\"id\": \"" + outcome[0] + "\"}}}");
        return error(response, outcome[1]);
    }

    private static NetworkResponse error(NetworkResponse response, String message) {
        try {
            JSONArray error = new JSONArray().put("SYNC_COMPAT_IMAGE").put(message).put("");
            return copy(response, new JSONObject().put("json", new JSONObject().put("errors", new JSONArray().put(error))).toString());
        } catch (JSONException impossible) {
            return response;
        }
    }

    /** Only wss:// on Reddit's media websocket hosts. */
    static boolean socketUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return "wss".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && host.toLowerCase(Locale.ROOT).endsWith(SOCKET_HOST_SUFFIX)
                    && host.length() > SOCKET_HOST_SUFFIX.length();
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /**
     * Waits for Reddit's processing result: {@code {"type": "success", "payload": {"redirect":
     * "<post URL>"}}} or {@code {"type": "failed"}}. Returns {id, null} or {null, message}.
     */
    static String[] awaitPost(String socket) {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String[]> outcome = new AtomicReference<>();
        WebSocket webSocket;
        try {
            okhttp3.Request request = new okhttp3.Request.Builder().url(socket).build();
            webSocket = socketClient().newWebSocket(request, new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    String[] result = socketMessage(text);
                    if (result != null && outcome.compareAndSet(null, result)) finished.countDown();
                }

                @Override public void onFailure(WebSocket ws, Throwable failure, okhttp3.Response response) {
                    if (outcome.compareAndSet(null, new String[] {null, POST_UNCONFIRMED})) finished.countDown();
                }

                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    if (outcome.compareAndSet(null, new String[] {null, POST_UNCONFIRMED})) finished.countDown();
                }
            });
        } catch (RuntimeException | LinkageError failed) {
            return new String[] {null, POST_UNCONFIRMED};
        }
        try {
            if (!finished.await(SOCKET_WAIT_MS, TimeUnit.MILLISECONDS)) outcome.compareAndSet(null, new String[] {null, POST_UNCONFIRMED});
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome.compareAndSet(null, new String[] {null, POST_UNCONFIRMED});
        } finally {
            try {
                webSocket.cancel();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
        }
        return outcome.get();
    }

    /** One websocket message: {id, null}, {null, message}, or null to keep waiting. */
    static String[] socketMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            if ("failed".equals(message.optString("type"))) return new String[] {null, POST_FAILED};
            JSONObject payload = message.optJSONObject("payload");
            Object redirect = payload == null ? null : payload.opt("redirect");
            if (!(redirect instanceof String)) return null;
            String id = postId((String) redirect);
            return id == null ? new String[] {null, POST_UNCONFIRMED} : new String[] {id, null};
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** The base-36 post ID of an HTTPS post URL on Reddit, or null. */
    static String postId(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("reddit.com") && !host.endsWith(".reddit.com")) return null;
            Matcher matcher = POST_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            return matcher.matches() ? matcher.group(1) : null;
        } catch (URISyntaxException bad) {
            return null;
        }
    }

    private static NetworkResponse copy(NetworkResponse response, String body) {
        return new NetworkResponse(response.statusCode, body.getBytes(StandardCharsets.UTF_8), response.notModified,
                response.networkTimeMs, response.allHeaders);
    }

    // ---- Android and Sync ----

    static final class AndroidPlatform implements Platform {
        private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(4), runnable -> {
                    Thread thread = new Thread(runnable, "sync-compat-image-upload");
                    thread.setDaemon(true);
                    return thread;
                });
        private android.os.Handler main;

        AndroidPlatform() {
            worker.allowCoreThreadTimeOut(true);
        }

        private synchronized android.os.Handler handler() {
            if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
            return main;
        }

        @Override public void background(Runnable task) {
            worker.execute(task);
        }

        @Override public void main(Runnable task) {
            handler().post(task);
        }

        @Override public void later(long delayMs, Runnable task) {
            handler().postDelayed(task, delayMs);
        }

        @Override public boolean signedIn() {
            com.laurencedawson.reddit_sync.singleton.a account = com.laurencedawson.reddit_sync.singleton.a.d();
            return account != null && account.i();
        }

        /** As Sync's UploadImgurRequest: a content:// URI through the resolver, otherwise a file path. */
        @Override public InputStream open(Context context, Uri uri) throws IOException {
            if (uri.toString().startsWith("content://")) return context.getContentResolver().openInputStream(uri);
            String path = uri.getPath();
            if (path == null) throw new IOException("No path");
            return new java.io.FileInputStream(path);
        }

        @Override public void queue(Context context, Request<?> request) {
            m8.a.c(context, (x8.a<?>) request);
        }

        @SuppressWarnings("unchecked")
        @Override public Map<String, String> params(Object request) {
            if (!(request instanceof x8.a)) return null;
            try {
                Field field = x8.a.class.getDeclaredField("s");
                field.setAccessible(true);
                return (Map<String, String>) field.get(request);
            } catch (ReflectiveOperationException missing) {
                return null;
            }
        }

        @Override public void resend(Context context, Object request, String token, boolean exchange) {
            if (exchange) m8.a.g(context, (x8.a<?>) request, token);
            else m8.a.b(context, token, (x8.a<?>) request);
        }

        /**
         * OAuthCommentRequest (y8.e) has no error listener; its {@code d(message, true)} posts Sync's
         * "Error submitting comment" notification with a Retry action that keeps the text. Other
         * requests get the message through their error listener.
         */
        @Override public void fail(Object request, String message) {
            main(() -> {
                if (request.getClass().getName().equals("y8.e")) {
                    try {
                        Method report = request.getClass().getMethod("d", String.class, boolean.class);
                        report.invoke(request, message, true);
                        return;
                    } catch (ReflectiveOperationException | RuntimeException unexpected) {
                        // Fall back to the error listener.
                    }
                }
                ((Request<?>) request).deliverError(new VolleyError(message));
            });
        }
    }
}
