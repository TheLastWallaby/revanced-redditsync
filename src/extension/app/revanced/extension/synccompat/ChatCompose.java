package app.revanced.extension.synccompat;

/**
 * Makes Sync's new-message editor work like starting a chat.
 *
 * <p>Reddit now delivers a new message as a Reddit Chat message (Reddit Help changelog, 7 August
 * 2025: messages sent from the compose page "will be delivered as chats"). Sync 23033 still sends
 * it with {@code api/compose} (OAuthMessageRequest, {@code y8.z}), which requires a subject, and its
 * editor ({@code EditFragment}, mode 2) shows a Subject field and refuses to send without one
 * ("Enter a subject").
 *
 * <p>Reddit's chat shows the subject as its own line above "user: message" (seen on a phone,
 * 2026-11-22), so any visible subject is noise in the chat.
 *
 * <ul>
 *   <li>To a user: the Subject field is hidden and the subject Reddit's API requires is a single
 *       zero-width space (U+200B). Reddit's blank-subject check rejects only whitespace, which
 *       U+200B is not, so the chat shows just the message.</li>
 *   <li>To a subreddit's moderators ({@code r/name} or {@code /r/name}, which Reddit delivers to
 *       modmail, where subjects are kept): the field stays and is still required, as in Sync.</li>
 *   <li>A subject already filled in (for example from a {@code message/compose?subject=} link) is
 *       shown and sent unchanged.</li>
 * </ul>
 *
 * <p>Nothing is logged and no text goes anywhere but the request Sync already sends. Any failure
 * leaves Sync's own editor behaviour.
 */
public final class ChatCompose {
    /** The subject sent for a chat: a zero-width space, not blank to Reddit but invisible in the chat. */
    static final String CHAT_SUBJECT = "​";

    private ChatCompose() {
    }

    /**
     * Sync's editor, just before it checks the subject: the subject to send. Unchanged for a
     * subreddit recipient, a subject the user entered or an empty message; otherwise the invisible
     * chat subject.
     */
    public static String subject(String to, String subject, String text) {
        try {
            if (subject != null && !subject.trim().isEmpty()) return subject;
            if (isSubreddit(to) || text == null || text.trim().isEmpty()) return subject;
            return CHAT_SUBJECT;
        } catch (RuntimeException unexpected) {
            return subject;
        }
    }

    /** Whether the Subject field is shown: for subreddit recipients, or when it already has text. */
    static boolean showSubject(String to, String subject) {
        return isSubreddit(to) || (subject != null && !subject.trim().isEmpty());
    }

    /** {@code r/name} or {@code /r/name}, any case: a message to the subreddit's moderators. */
    static boolean isSubreddit(String to) {
        if (to == null) return false;
        String name = to.trim().toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("/")) name = name.substring(1);
        return name.startsWith("r/");
    }

    /**
     * Sync's editor, when it shows the recipient and Subject fields for a new message: hides the
     * Subject field unless {@link #showSubject} says otherwise, and keeps that up to date as the
     * recipient changes. Sync then puts the focus on the message.
     */
    public static void bind(Object recipientField, Object subjectField) {
        try {
            if (!(recipientField instanceof android.widget.TextView) || !(subjectField instanceof android.widget.TextView)) return;
            android.widget.TextView recipient = (android.widget.TextView) recipientField;
            android.widget.TextView subject = (android.widget.TextView) subjectField;
            Runnable update = () -> {
                boolean show = showSubject(text(recipient), text(subject));
                int visibility = show ? android.view.View.VISIBLE : android.view.View.GONE;
                if (subject.getVisibility() != visibility) subject.setVisibility(visibility);
            };
            update.run();
            recipient.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override public void afterTextChanged(android.text.Editable s) {
                    update.run();
                }
            });
            // Android restores a saved subject after this point (for example after rotation).
            subject.post(update);
        } catch (RuntimeException unexpected) {
            // Sync's field stays visible; an empty subject is still filled in when sending.
        }
    }

    private static String text(android.widget.TextView field) {
        CharSequence value = field.getText();
        return value == null ? "" : value.toString();
    }
}
