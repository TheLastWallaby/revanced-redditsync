package app.revanced.extension.synccompat;

import android.content.Context;
import com.android.volley.NetworkResponse;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hands retired private-message actions over to Reddit Chat.
 *
 * <p>Reddit made Chat its direct-messaging service; old private messages are a read-only archive
 * that "cannot be replied to" (Reddit Help, "Accessing existing private messages", 2026). Sync 23033
 * still sends a new message with {@code api/compose} (OAuthMessageRequest, {@code y8.z}) and a reply
 * to a message with {@code api/comment} on a {@code t4_} item (OAuthCommentRequest, {@code y8.e},
 * type 2). Reddit refuses a new message with
 * {@code {"json": {"errors": [["RESTRICTED_TO_PM", "User doesn't accept direct messages. Try sending
 * a chat request instead.", "to"]]}}}. Only that error starts the handoff; every other answer, error,
 * status and request keeps Sync's handling.
 *
 * <ul>
 *   <li>New message: the editor stays open with the text, and a dialog says Reddit now uses Chat,
 *       with "Open Reddit Chat".</li>
 *   <li>Reply to a message: Sync has already closed the editor and saves no draft for message
 *       replies, so the reply is saved as one of Sync's editor drafts; Sync's failure notification
 *       says Reddit now uses Chat and offers "Open Reddit Chat" and "Open message archive" instead of
 *       a Retry that would be refused again. The text is also shown as a toast, because Android may
 *       not show Sync's notifications.</li>
 * </ul>
 *
 * <p>Both addresses are fixed HTTPS {@code www.reddit.com} pages opened with Android's ACTION_VIEW
 * (the Reddit app or the browser); if Sync itself would receive the link (Sync claims every
 * {@code www.reddit.com} path when its links are verified), a browser is asked instead. Nothing is
 * logged; no message text leaves Sync except as Sync already sent it.
 */
public final class MessagingHandoff {
    public static final String CHAT_URL = "https://www.reddit.com/chat";
    /** Reddit Help: the archive is "accessible in the notifications inbox" (bell, then archive icon). */
    public static final String ARCHIVE_URL = "https://www.reddit.com/notifications";
    static final String RETIRED_CODE = "RESTRICTED_TO_PM";
    static final String MESSAGE = "Reddit now sends direct messages through Reddit Chat.";
    static final String COMPOSE_TITLE = "Message not sent";
    static final String COMPOSE_DETAIL = "Your message is still here, so you can copy it into a chat.";
    static final String REPLY_DETAIL = "Old private messages are read-only. Your reply was saved as a draft.";
    static final String OPEN_CHAT = "Open Reddit Chat";
    static final String OPEN_ARCHIVE = "Open message archive";
    static final String CLOSE = "Close";
    static final String OPEN_FAILED = "Couldn't open Reddit. Visit reddit.com/chat in your browser.";
    /** OAuthCommentRequest's type for a reply to a private message (thing_id t4_). */
    static final int MESSAGE_REPLY = 2;
    /** Reddit's error answers are small; a larger body is not inspected. */
    static int maxBody = 64 * 1024;

    /** What the handoff does on the phone; the suites replace it. */
    interface Platform {
        void main(Runnable task);
        void toast(Context context, String message);
        void saveDraft(Context context, String text);
        void composeDialog(Context context, String title, String message);
        void addActions(Object notificationBuilder, Context context);
    }

    static Platform platform = new AndroidPlatform();

    /** Replies whose failure is the retired route, until their notification is built. */
    private static final Map<Object, Boolean> RETIRED_REPLIES = Collections.synchronizedMap(new WeakHashMap<>());

    private MessagingHandoff() {
    }

    /** Reddit refused the request because it was a private message; not raised for other errors. */
    public static final class RetiredRouteError extends VolleyError {
        RetiredRouteError(String reason) {
            super(reason);
        }
    }

    // ---- Detection ----

    /** True for Reddit's {"json": {"errors": [...]}} answer containing RESTRICTED_TO_PM. */
    static boolean isRetired(byte[] body) {
        return retiredReason(body) != null;
    }

    /** Reddit's own text for the RESTRICTED_TO_PM error (may be empty), or null without it. */
    static String retiredReason(byte[] body) {
        if (body == null || body.length == 0 || body.length > maxBody) return null;
        try {
            Object root = new org.json.JSONTokener(new String(body, StandardCharsets.UTF_8)).nextValue();
            if (!(root instanceof JSONObject)) return null;
            JSONObject json = ((JSONObject) root).optJSONObject("json");
            JSONArray errors = json == null ? null : json.optJSONArray("errors");
            if (errors == null) return null;
            for (int i = 0; i < errors.length(); i++) {
                JSONArray error = errors.optJSONArray(i);
                if (error == null || !RETIRED_CODE.equals(error.opt(0))) continue;
                Object reason = error.opt(1);
                return reason instanceof String ? (String) reason : "";
            }
            return null;
        } catch (Exception malformed) {
            // Android's org.json throws a checked JSONException; JSON-java's is unchecked.
            return null;
        }
    }

    // ---- New message: OAuthMessageRequest (y8.z) and the editor's error listener ----

    /**
     * Start of OAuthMessageRequest.parseNetworkResponse: the retired-route answer becomes a
     * {@link RetiredRouteError} (with Reddit's text, which Sync would have shown); null otherwise,
     * and Sync parses the answer itself.
     */
    public static Response<Boolean> composeResponse(NetworkResponse response) {
        if (response == null) return null;
        String reason = retiredReason(response.data);
        if (reason == null) return null;
        return Response.error(new RetiredRouteError(reason.isEmpty() ? MESSAGE : reason));
    }

    /**
     * EditFragment's send-message error listener, after Sync dismisses its progress dialog: true
     * when the handoff dialog was shown (Sync's toast is then skipped). The editor and its text stay.
     */
    public static boolean composeFailed(Context context, VolleyError error) {
        if (context == null || !retired(error)) return false;
        try {
            platform.composeDialog(context, COMPOSE_TITLE, MESSAGE + " " + COMPOSE_DETAIL);
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean retired(VolleyError error) {
        if (error instanceof RetiredRouteError) return true;
        return error != null && error.networkResponse != null && isRetired(error.networkResponse.data);
    }

    // ---- Reply to a message: OAuthCommentRequest (y8.e) ----

    /** OAuthCommentRequest.parseNetworkResponse, where Sync reports Reddit's json.errors. */
    public static void replyResponse(Object request, int type, String body) {
        if (request == null || type != MESSAGE_REPLY || body == null) return;
        if (isRetired(body.getBytes(StandardCharsets.UTF_8))) RETIRED_REPLIES.put(request, Boolean.TRUE);
    }

    /** OAuthCommentRequest.parseNetworkError: the same answer with an error status. */
    public static void replyError(Object request, int type, VolleyError error) {
        if (request == null || type != MESSAGE_REPLY || error == null || error.networkResponse == null) return;
        if (isRetired(error.networkResponse.data)) RETIRED_REPLIES.put(request, Boolean.TRUE);
    }

    /**
     * Start of OAuthCommentRequest.d, Sync's failure notification: for a retired reply, saves the
     * reply as a Sync draft, shows the handoff as a toast, and returns the notification's text (Sync
     * then adds no Retry). Null otherwise: Sync's message and Retry stay.
     */
    public static String replyNotice(Object request, String text, Context context) {
        if (request == null || !RETIRED_REPLIES.containsKey(request)) return null;
        String notice = MESSAGE + " " + REPLY_DETAIL;
        try {
            platform.main(() -> {
                if (context != null && text != null && !text.trim().isEmpty()) platform.saveDraft(context, text);
                if (context != null) platform.toast(context, notice);
            });
        } catch (RuntimeException | LinkageError unavailable) {
            // The notification still says what happened.
        }
        return notice;
    }

    /** In OAuthCommentRequest.d, before Sync builds the notification: the two handoff actions. */
    public static void replyActions(Object request, Object builder, Context context) {
        if (request == null || builder == null || RETIRED_REPLIES.remove(request) == null) return;
        try {
            platform.addActions(builder, context);
        } catch (RuntimeException | LinkageError unavailable) {
            // The notification keeps its text.
        }
    }

    // ---- Opening Reddit ----

    /** True when ACTION_VIEW would come back to Sync itself, so a browser should be asked instead. */
    static boolean needsBrowser(String resolvedPackage, String ownPackage) {
        return resolvedPackage != null && resolvedPackage.equals(ownPackage);
    }

    static final class AndroidPlatform implements Platform {
        private android.os.Handler main;

        private synchronized android.os.Handler handler() {
            if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
            return main;
        }

        @Override public void main(Runnable task) {
            handler().post(task);
        }

        @Override public void toast(Context context, String message) {
            android.widget.Toast.makeText(context.getApplicationContext(), message, android.widget.Toast.LENGTH_LONG).show();
        }

        @Override public void saveDraft(Context context, String text) {
            f9.a.e(context, text);
        }

        @Override public void composeDialog(Context context, String title, String message) {
            new android.app.AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(OPEN_CHAT, (dialog, which) -> open(context, CHAT_URL))
                    .setNegativeButton(CLOSE, null)
                    .show();
        }

        @Override public void addActions(Object notificationBuilder, Context context) {
            if (context == null || !(notificationBuilder instanceof androidx.core.app.g.e)) return;
            androidx.core.app.g.e builder = (androidx.core.app.g.e) notificationBuilder;
            builder.a(0, OPEN_CHAT, pending(context, CHAT_URL));
            builder.a(0, OPEN_ARCHIVE, pending(context, ARCHIVE_URL));
        }

        private static android.content.Intent view(Context context, String url) {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            try {
                android.content.pm.ResolveInfo match = context.getPackageManager().resolveActivity(intent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                String resolved = match == null || match.activityInfo == null ? null : match.activityInfo.packageName;
                if (needsBrowser(resolved, context.getPackageName())) {
                    intent.setSelector(android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN,
                            android.content.Intent.CATEGORY_APP_BROWSER));
                }
            } catch (RuntimeException unavailable) {
                // Plain ACTION_VIEW.
            }
            return intent;
        }

        private static void open(Context context, String url) {
            android.content.Intent intent = view(context, url);
            if (!(context instanceof android.app.Activity)) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (RuntimeException noApp) {
                android.widget.Toast.makeText(context, OPEN_FAILED, android.widget.Toast.LENGTH_LONG).show();
            }
        }

        private static android.app.PendingIntent pending(Context context, String url) {
            android.content.Intent intent = view(context, url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            // FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE, as Sync's own Retry action.
            return android.app.PendingIntent.getActivity(context, url.hashCode(), intent, 0x14000000);
        }
    }
}
