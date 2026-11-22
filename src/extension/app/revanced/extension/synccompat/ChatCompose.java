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
 * <ul>
 *   <li>To a user: the Subject field is hidden and the subject Reddit's API requires is made from
 *       the first line of the message, at most 100 characters (Reddit's limit).</li>
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
    /** Reddit's maximum subject length, in characters. */
    static final int SUBJECT_LIMIT = 100;
    /** Used if the message has no line with words in it. */
    static final String FALLBACK_SUBJECT = "Message";
    private static final String ELLIPSIS = "…";

    private ChatCompose() {
    }

    /**
     * Sync's editor, just before it checks the subject: the subject to send. Unchanged for a
     * subreddit recipient or a subject the user entered; otherwise made from the message.
     */
    public static String subject(String to, String subject, String text) {
        try {
            if (subject != null && !subject.trim().isEmpty()) return subject;
            if (isSubreddit(to) || text == null || text.trim().isEmpty()) return subject;
            return fromMessage(text);
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

    /** The first line with words in it, without Markdown heading/quote markers, at most 100 characters. */
    static String fromMessage(String text) {
        for (String line : text.split("\r\n|\r|\n")) {
            String words = line.replaceFirst("^\\s*(?:(?:#{1,6}(?=\\s|$)|>)\\s*)*", "").replaceAll("\\s+", " ").trim();
            if (!words.isEmpty()) return limit(words);
        }
        return FALLBACK_SUBJECT;
    }

    private static String limit(String words) {
        if (words.codePointCount(0, words.length()) <= SUBJECT_LIMIT) return words;
        String cut = words.substring(0, words.offsetByCodePoints(0, SUBJECT_LIMIT - 1));
        int space = cut.lastIndexOf(' ');
        if (space >= SUBJECT_LIMIT / 2) cut = cut.substring(0, space);
        return cut.trim() + ELLIPSIS;
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
