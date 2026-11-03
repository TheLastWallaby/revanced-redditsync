package app.revanced.extension.synccompat;

import android.content.ContentValues;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shows the poll that Reddit sends with a poll post, below the post's text.
 *
 * <p>Sync 23033 never reads {@code poll_data}. Its post parser ({@code PostParserHelper.a},
 * {@code c9.c.a}) turns the post's {@code selftext} into the HTML it draws
 * ({@code selftext_processed}) and keeps the Markdown ({@code selftext_raw}) for editing. The
 * patch "Sync: show poll options" calls {@link #apply} just before the parser adds the post's
 * values to its result, and only {@code selftext_processed} gains a block: the options, the
 * closing time or "Poll closed", the vote total and per-option counts only where Reddit sent
 * them, the signed-in user's choice if Reddit sent one, and an "Open poll on Reddit" link.
 *
 * <p>The link is Reddit's own poll address, {@code https://www.reddit.com/poll/<post id>}, which
 * Reddit redirects to the post. Sync's link router opens {@code reddit.com/poll/} links in a
 * browser tab ({@code LinkHandler.W}), where voting works; the post's permalink would reopen the
 * same post in Sync. It is built only from a validated post ID.
 *
 * <p>Option text is Reddit's text (HTML-escaped by Reddit, decoded as Sync decodes its own text)
 * escaped again for HTML, so it can't add tags, Markdown or links. Without {@code poll_data}, or
 * with {@code poll_data} that is malformed or larger than these limits, nothing changes. Never
 * throws; nothing is logged.
 */
public final class PollCompat {
    /** Reddit allows 2 to 6 options of at most 120 characters; anything far beyond is refused. */
    static int maxOptions = 12;
    static int maxOptionText = 300;
    static int maxOptionId = 32;
    static long maxCount = 1_000_000_000L;
    /** 3000-01-01 in milliseconds; Reddit's voting_end_timestamp is in milliseconds. */
    static long maxTimestamp = 32_503_680_000_000L;

    private static final Pattern POST_ID = Pattern.compile("[0-9a-z]{1,13}");
    private static final Pattern OPTION_ID = Pattern.compile("[0-9A-Za-z_]+");
    private static final String POST_KIND = "kind";
    private static final int POST = 3;
    private static final String PROCESSED = "selftext_processed";

    private PollCompat() {
    }

    /**
     * Adds the poll block to a post's {@code selftext_processed}. Values other than a post's, and
     * posts without usable {@code poll_data}, are left exactly as they were.
     */
    public static void apply(ContentValues values, JSONObject data) {
        try {
            if (values == null || data == null) return;
            Object kind = values.get(POST_KIND);
            if (!(kind instanceof Integer) || (Integer) kind != POST) return;
            String block = block(data, System.currentTimeMillis(), Locale.getDefault(), TimeZone.getDefault());
            if (block == null) return;
            Object processed = values.get(PROCESSED);
            if (processed != null && !(processed instanceof String)) return;
            String text = (String) processed;
            values.put(PROCESSED, text == null || text.isEmpty() ? block : text + block);
        } catch (RuntimeException unexpected) {
            // The post keeps Sync's own values.
        }
    }

    /** The poll's address on Reddit, or null for an invalid post ID. */
    static String pollUrl(String postId) {
        if (postId == null || !POST_ID.matcher(postId).matches()) return null;
        return "https://www.reddit.com/poll/" + postId;
    }

    /** The HTML block for a post's poll_data, or null when there is none or it can't be used. */
    static String block(JSONObject data, long now, Locale locale, TimeZone zone) {
        Object raw = data.opt("poll_data");
        if (!(raw instanceof JSONObject)) return null;
        JSONObject poll = (JSONObject) raw;
        String url = pollUrl(data.opt("id") instanceof String ? (String) data.opt("id") : null);
        if (url == null) return null;

        Object rawOptions = poll.opt("options");
        if (!(rawOptions instanceof JSONArray)) return null;
        JSONArray array = (JSONArray) rawOptions;
        if (array.length() == 0 || array.length() > maxOptions) return null;
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Option option = option(array.opt(i));
            if (option == null) return null;
            options.add(option);
        }

        Object selection = nullable(poll.opt("user_selection"));
        if (selection != null && !(selection instanceof String)) return null;
        Long end = number(poll.opt("voting_end_timestamp"), maxTimestamp);
        if (end != null && end <= 0) return null;
        Long total = number(poll.opt("total_vote_count"), maxCount);
        if (total != null && total == INVALID) return null;

        NumberFormat numbers = NumberFormat.getIntegerInstance(locale);
        StringBuilder out = new StringBuilder(256);
        out.append("<p><strong>Poll</strong>");
        List<String> status = new ArrayList<>();
        boolean closed = end != null && now >= end;
        if (closed) {
            status.add("Poll closed");
        } else if (end != null) {
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
            format.setTimeZone(zone);
            status.add("Voting closes " + escape(format.format(new Date(end))));
        }
        if (total != null) status.add(votes(numbers, total));
        if (!status.isEmpty()) out.append("<br>").append(String.join(" · ", status));
        out.append("</p><ul>");
        boolean anyCount = false;
        for (Option option : options) {
            out.append("<li>").append(escape(option.text));
            if (option.count != null) {
                anyCount = true;
                out.append(" · ").append(votes(numbers, option.count));
            }
            if (option.id != null && option.id.equals(selection)) out.append(" · <strong>your vote</strong>");
            out.append("</li>");
        }
        out.append("</ul>");
        if (!anyCount) out.append("<p>Reddit hasn't shared the results.</p>");
        out.append("<p><a href=\"").append(url).append("\">Open poll on Reddit</a></p>");
        return out.toString();
    }

    private static final class Option {
        final String id;
        final String text;
        final Long count;

        Option(String id, String text, Long count) {
            this.id = id;
            this.text = text;
            this.count = count;
        }
    }

    private static Option option(Object raw) {
        if (!(raw instanceof JSONObject)) return null;
        JSONObject option = (JSONObject) raw;
        Object text = option.opt("text");
        if (!(text instanceof String)) return null;
        String clean = clean((String) text);
        if (clean.isEmpty() || clean.length() > maxOptionText) return null;
        Object id = nullable(option.opt("id"));
        if (id != null && (!(id instanceof String) || ((String) id).length() > maxOptionId
                || !OPTION_ID.matcher((String) id).matches())) return null;
        Long count = number(option.opt("vote_count"), maxCount);
        if (count != null && count == INVALID) return null;
        return new Option((String) id, clean, count);
    }

    /** Marks a number that is present but unusable. */
    private static final long INVALID = -1;

    /** A whole number from 0 to limit; null when absent or JSON null; INVALID otherwise. */
    private static Long number(Object value, long limit) {
        value = nullable(value);
        if (value == null) return null;
        if (!(value instanceof Number)) return INVALID;
        long whole;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            whole = ((Number) value).longValue();
        } else {
            // Android's org.json gives Double for 1.5e12-style numbers; JSON-java may give BigDecimal.
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 9.0e15) return INVALID;
            whole = (long) d;
        }
        return whole < 0 || whole > limit ? INVALID : whole;
    }

    private static Object nullable(Object value) {
        return value == null || value == JSONObject.NULL ? null : value;
    }

    private static String votes(NumberFormat numbers, long count) {
        return numbers.format(count) + (count == 1 ? " vote" : " votes");
    }

    /**
     * Reddit's text as Sync shows its own (WebsafeUtils.a decodes &amp;amp;, &amp;lt;, &amp;gt;
     * in that order), with control and direction-override characters removed and whitespace
     * collapsed.
     */
    static String clean(String text) {
        String decoded = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        StringBuilder out = new StringBuilder(decoded.length());
        boolean space = false;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
                continue;
            }
            if (Character.isISOControl(c) || hidden(c)) continue;
            if (space) out.append(' ');
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean hidden(char c) {
        return (c >= '​' && c <= '‏') || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩') || c == '﻿';
    }

    /** HTML text: no character of the result can open a tag, entity or attribute. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
