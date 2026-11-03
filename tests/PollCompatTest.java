package app.revanced.extension.synccompat;

import android.content.ContentValues;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks the poll block PollCompat adds to a post's selftext_processed: open polls with hidden
 * counts, closed polls with counts, voted polls, missing optional fields, zero/one/many options,
 * odd and hostile option text, malformed and oversized poll_data, posts without a poll (values
 * unchanged), and the poll link. The poll_data shapes follow PRAW's recorded OAuth responses
 * (oauth.reddit.com/comments/fo7p5b, g9zfex, ga3hiq) and Arctic Shift's 2026 schema, where
 * vote_count is optional and poll_data can also be a boolean or null.
 */
public final class PollCompatTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 2026-11-05 14:00 UTC. */
    private static final long END = 1_793_887_200_000L;
    private static final String LINK = "<p><a href=\"https://www.reddit.com/poll/1abcde\">Open poll on Reddit</a></p>";
    private static final String HIDDEN = "<p>Reddit hasn't shared the results.</p>";

    private static JSONObject post(String id, Object poll) {
        JSONObject data = new JSONObject().put("id", id).put("selftext", "Body");
        if (poll != null) data.put("poll_data", poll);
        return data;
    }
    private static JSONObject option(String id, String text, Object votes) {
        JSONObject option = new JSONObject().put("text", text);
        if (id != null) option.put("id", id);
        if (votes != null) option.put("vote_count", votes);
        return option;
    }
    private static JSONObject poll(Object end, Object total, Object selection, JSONObject... options) {
        JSONObject poll = new JSONObject().put("options", new JSONArray(options)).put("is_prediction", false)
                .put("prediction_status", JSONObject.NULL).put("resolved_option_id", JSONObject.NULL);
        if (end != null) poll.put("voting_end_timestamp", end);
        if (total != null) poll.put("total_vote_count", total);
        if (selection != null) poll.put("user_selection", selection);
        return poll;
    }
    private static String block(JSONObject data, long now) {
        return PollCompat.block(data, now, Locale.US, UTC);
    }
    private static String block(JSONObject poll) {
        return block(post("1abcde", poll), END - 1);
    }

    public static void main(String[] args) {
        openPollWithHiddenCounts();
        closedPollWithCounts();
        votedPoll();
        missingOptionalFields();
        optionCounts();
        hostileText();
        malformed();
        oversized();
        unchangedWithoutPoll();
        apply();
        pollUrl();
        System.out.println("PASS: " + checks + " poll block checks");
    }

    private static void openPollWithHiddenCounts() {
        JSONObject poll = poll(END, 1234, JSONObject.NULL,
                option("576784", "Yes", null), option("576785", "No", null), option("576786", "Maybe", null));
        equal("<p><strong>Poll</strong><br>Voting closes Nov 5, 2026, 2:00 PM · 1,234 votes</p>"
                + "<ul><li>Yes</li><li>No</li><li>Maybe</li></ul>" + HIDDEN + LINK, block(poll), "open poll, counts hidden");
        // Closing time in another zone and locale comes from the device.
        String tokyo = PollCompat.block(post("1abcde", poll), END - 1, Locale.UK, TimeZone.getTimeZone("Asia/Tokyo"));
        check(tokyo.contains("Voting closes 5 Nov 2026, 23:00"), "closing time in device zone and locale: " + tokyo);
        // The exact closing moment counts as closed.
        check(block(post("1abcde", poll), END).contains("Poll closed"), "closed at voting_end_timestamp");
        check(!block(post("1abcde", poll), END - 1).contains("Poll closed"), "open one millisecond before");
    }

    private static void closedPollWithCounts() {
        // PRAW's recorded fo7p5b poll (closed, user not voted).
        JSONObject poll = poll(1585325428313L, 11249, JSONObject.NULL,
                option("1883", "Hopeful", 1675), option("1884", "Inspired by others", 343), option("1885", "Anxious", 3059),
                option("1886", "Angry", 652), option("1887", "Meh...mostly bored", 2839), option("1888", "All of the above", 2681));
        equal("<p><strong>Poll</strong><br>Poll closed · 11,249 votes</p><ul>"
                + "<li>Hopeful · 1,675 votes</li><li>Inspired by others · 343 votes</li><li>Anxious · 3,059 votes</li>"
                + "<li>Angry · 652 votes</li><li>Meh...mostly bored · 2,839 votes</li><li>All of the above · 2,681 votes</li>"
                + "</ul>" + LINK, block(poll), "closed poll with counts");
        // Closed but Reddit sent no counts: nothing is estimated.
        String noCounts = block(poll(1585325428313L, 11249, null, option("1", "A", null), option("2", "B", null)));
        check(noCounts.contains("Poll closed · 11,249 votes") && noCounts.contains("<li>A</li><li>B</li>")
                && noCounts.contains(HIDDEN), "closed poll without counts: " + noCounts);
    }

    private static void votedPoll() {
        // PRAW's recorded g9zfex poll, as Sync receives it without raw_json (Reddit escapes "<").
        JSONObject poll = poll(1588379995927L, 54, "573294",
                option("573294", "100% and nothing", 40), option("573295", "&lt;50% and chocolate cake", 14));
        equal("<p><strong>Poll</strong><br>Poll closed · 54 votes</p><ul>"
                + "<li>100% and nothing · 40 votes · <strong>your vote</strong></li>"
                + "<li>&lt;50% and chocolate cake · 14 votes</li></ul>" + LINK, block(poll), "voted poll");
        // An open poll the user voted in, with counts.
        String open = block(poll(END, 2, "b", option("a", "One", 1), option("b", "Two", 1)));
        check(open.contains("Voting closes") && open.contains("<li>One · 1 vote</li>")
                && open.contains("<li>Two · 1 vote · <strong>your vote</strong></li>") && !open.contains(HIDDEN), "open voted poll: " + open);
        // A selection that matches no option marks nothing.
        check(!block(poll(END, 2, "zzz", option("a", "One", 1))).contains("your vote"), "unknown selection ignored");
        // Options without IDs can't be matched.
        check(!block(poll(END, 2, "a", option(null, "One", 1))).contains("your vote"), "option without id not marked");
    }

    private static void missingOptionalFields() {
        equal("<p><strong>Poll</strong></p><ul><li>Only text</li></ul>" + HIDDEN + LINK,
                block(new JSONObject().put("options", new JSONArray().put(new JSONObject().put("text", "Only text")))),
                "only options");
        JSONObject nulls = new JSONObject().put("options", new JSONArray().put(option("1", "A", JSONObject.NULL)))
                .put("voting_end_timestamp", JSONObject.NULL).put("total_vote_count", JSONObject.NULL)
                .put("user_selection", JSONObject.NULL);
        equal("<p><strong>Poll</strong></p><ul><li>A</li></ul>" + HIDDEN + LINK, block(nulls), "JSON nulls");
        String noTotal = block(poll(END, null, null, option("1", "A", 3)));
        check(noTotal.contains("Voting closes Nov 5, 2026, 2:00 PM</p>") && noTotal.contains("<li>A · 3 votes</li>"), "no total: " + noTotal);
        check(block(poll(null, 7, null, option("1", "A", null))).startsWith("<p><strong>Poll</strong><br>7 votes</p>"), "no end time");
        // A post that also has a "View Poll" link in its text (Reddit adds one to API-made polls) still gets the block.
        JSONObject data = post("1abcde", poll(END, 0, null, option("1", "A", 0), option("2", "B", 0)));
        data.put("selftext", "Test poll text.\n\n[View Poll](https://www.reddit.com/poll/1abcde)");
        check(block(data, END - 1).contains("<li>A · 0 votes</li>"), "zero counts shown as Reddit sent them");
    }

    private static void optionCounts() {
        check(block(poll(END, 0, null)) == null, "zero options: no block");
        check(block(poll(END, 1, null, option("1", "Single", null))).contains("<ul><li>Single</li></ul>"), "one option");
        JSONObject[] many = new JSONObject[12];
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < many.length; i++) {
            many[i] = option(String.valueOf(i), "Option " + (i + 1), i);
            items.append("<li>Option ").append(i + 1).append(" · ").append(i).append(i == 1 ? " vote" : " votes").append("</li>");
        }
        check(block(poll(END, 66, null, many)).contains("<ul>" + items + "</ul>"), "twelve options in order");
        // Counts only for the options Reddit gave them for.
        String mixed = block(poll(END, 5, null, option("1", "A", 5), option("2", "B", null)));
        check(mixed.contains("<li>A · 5 votes</li><li>B</li>") && !mixed.contains(HIDDEN), "mixed counts: " + mixed);
        check(block(poll(END, 1_234_567, null, option("1", "A", 1_234_567))).contains("A · 1,234,567 votes"), "large count");
        check(block(poll(END, 3.0, null, option("1", "A", 3.0))).contains("A · 3 votes"), "integral doubles accepted");
    }

    private static void hostileText() {
        String[][] cases = {
            {"<script>alert(1)</script>", "&lt;script&gt;alert(1)&lt;/script&gt;"},
            {"&lt;b&gt;bold&lt;/b&gt;", "&lt;b&gt;bold&lt;/b&gt;"},
            {"&amp;lt;b&amp;gt;", "&lt;b&gt;"},
            {"<a href=\"https://evil.example\">x</a>", "&lt;a href=&quot;https://evil.example&quot;&gt;x&lt;/a&gt;"},
            {"[click](https://evil.example)", "[click](https://evil.example)"},
            {"https://evil.example/path", "https://evil.example/path"},
            {"**bold** _it_ `code` >!spoiler!< ~~s~~ ^sup #h", "**bold** _it_ `code` &gt;!spoiler!&lt; ~~s~~ ^sup #h"},
            {"' onmouseover='x", "&#39; onmouseover=&#39;x"},
            {"Tom & Jerry", "Tom &amp; Jerry"},
            {"Line one\nLine two\r\n\tthree", "Line one Line two three"},
            {"  padded  ", "padded"},
            {"‮evil‬ ​zero⁦width⁩﻿", "evil zerowidth"},
            {"nul\u0000bell\u0007", "nulbell"},
            {"Emoji 🗳️ and ünïcödé", "Emoji 🗳️ and ünïcödé"},
            {"ESCAPED_OPEN SPOILER_OPEN QUOTE_ESCAPE", "ESCAPED_OPEN SPOILER_OPEN QUOTE_ESCAPE"},
            {"<spoiler>x</spoiler>", "&lt;spoiler&gt;x&lt;/spoiler&gt;"},
        };
        for (String[] c : cases) {
            String html = block(poll(END, 1, null, option("1", c[0], null)));
            check(html != null && html.contains("<li>" + c[1] + "</li>"), "option text " + c[0] + " -> " + html);
            check(html.split("<a ", -1).length == 2 && html.split("href=\"", -1).length == 2 && html.contains(LINK),
                    "only the poll link for " + c[0]);
            check(!html.contains("<script") && !html.contains("<a href=\"https://evil") && !html.contains("<b>")
                    && !html.contains("<spoiler>"), "no tags injected by " + c[0]);
        }
        check(block(poll(END, 1, null, option("1", "​ ‮", null))) == null, "option with no visible text: no block");
    }

    private static void malformed() {
        Object[] polls = {
            "a poll", true, false, 1, new JSONArray(), JSONObject.NULL,
            new JSONObject(),
            new JSONObject().put("options", "Yes,No"),
            new JSONObject().put("options", new JSONObject()),
            new JSONObject().put("options", new JSONArray().put("Yes")),
            new JSONObject().put("options", new JSONArray().put(JSONObject.NULL)),
            new JSONObject().put("options", new JSONArray().put(new JSONObject().put("id", "1"))),
            new JSONObject().put("options", new JSONArray().put(new JSONObject().put("text", 5))),
            new JSONObject().put("options", new JSONArray().put(new JSONObject().put("text", ""))),
            poll(END, 1, null, option("1", "A", -1)),
            poll(END, 1, null, option("1", "A", "12")),
            poll(END, 1, null, option("1", "A", 1.5)),
            poll(END, 1, null, option("1", "A", true)),
            poll(END, 1, null, new JSONObject().put("text", "A").put("id", 1883)),
            poll(END, 1, null, option("1 2", "A", null)),
            poll(END, 1, null, option("\"><x", "A", null)),
            poll(END, -5, null, option("1", "A", null)),
            poll(END, "54", null, option("1", "A", null)),
            poll(-1L, 1, null, option("1", "A", null)),
            poll(0, 1, null, option("1", "A", null)),
            poll("tomorrow", 1, null, option("1", "A", null)),
            poll(1.5e12 + 0.5, 1, null, option("1", "A", null)),
            poll(END, 1, 573294, option("573294", "A", null)),
            poll(END, 1, new JSONObject(), option("1", "A", null)),
        };
        for (Object p : polls) check(block(post("1abcde", p), END - 1) == null, "malformed poll_data " + p);
        // The post ID must be valid for the poll link.
        JSONObject good = poll(END, 1, null, option("1", "A", null));
        for (Object id : new Object[] {null, "", "ABCDEF", "1abc/../x", "1abcdefghijklm", "t3_1abcde", 12345, "1abc de"})
            check(block(new JSONObject().put("poll_data", good).putOpt("id", id), END - 1) == null, "invalid post id " + id);
        check(block(post("1abcdefghijkl", good), END - 1) != null, "13-character post id accepted");
    }

    private static void oversized() {
        String limit = "x".repeat(300);
        check(block(poll(END, 1, null, option("1", limit, null))).contains("<li>" + limit + "</li>"), "300-character option");
        check(block(poll(END, 1, null, option("1", limit + "x", null))) == null, "301-character option: no block");
        check(block(poll(END, 1, null, option("1", "y".repeat(100_000), null))) == null, "100,000-character option: no block");
        JSONObject[] thirteen = new JSONObject[13];
        for (int i = 0; i < thirteen.length; i++) thirteen[i] = option(String.valueOf(i), "O" + i, null);
        check(block(poll(END, 1, null, thirteen)) == null, "13 options: no block");
        check(block(poll(END, 1, null, option("1", "A", 1_000_000_001L))) == null, "count over limit: no block");
        check(block(poll(END, 1_000_000_001L, null, option("1", "A", null))) == null, "total over limit: no block");
        check(block(poll(32_503_680_000_001L, 1, null, option("1", "A", null))) == null, "end beyond year 3000: no block");
        check(block(poll(END, 1, null, option("x".repeat(33), "A", null))) == null, "long option id: no block");
        // Limits are fields the suite can shorten.
        int saved = PollCompat.maxOptions;
        PollCompat.maxOptions = 2;
        try {
            check(block(poll(END, 1, null, option("1", "A", null), option("2", "B", null), option("3", "C", null))) == null,
                    "shortened option limit applies");
        } finally {
            PollCompat.maxOptions = saved;
        }
    }

    private static ContentValues values(int kind, String processed) {
        ContentValues values = new ContentValues();
        values.put("_id", "1abcde");
        values.put("kind", kind);
        values.put("selftext_raw", "Body");
        if (processed != null) values.put("selftext_processed", processed);
        values.put("selftext_processed_preview", "Body");
        return values;
    }

    private static void unchangedWithoutPoll() {
        JSONObject[] posts = {
            post("1abcde", null),
            new JSONObject().put("id", "1abcde").put("poll_data", JSONObject.NULL),
            post("1abcde", false),
            post("1abcde", poll(END, 1, null)),
            post("1abcde", poll(END, 1, null, option("1", "y".repeat(5000), null))),
            new JSONObject(),
        };
        for (JSONObject data : posts) {
            ContentValues values = values(3, "<p>Body</p>");
            ContentValues before = values(3, "<p>Body</p>");
            PollCompat.apply(values, data);
            equal(before, values, "values unchanged for " + data);
        }
        // Comments and other kinds keep their values even with poll_data.
        JSONObject withPoll = post("1abcde", poll(END, 1, null, option("1", "A", null)));
        for (int kind : new int[] {1, 9}) {
            ContentValues values = values(kind, "<p>Body</p>");
            PollCompat.apply(values, withPoll);
            equal(values(kind, "<p>Body</p>"), values, "kind " + kind + " unchanged");
        }
        ContentValues noKind = values(3, "<p>Body</p>");
        noKind.putNull("kind");
        PollCompat.apply(noKind, withPoll);
        equal("<p>Body</p>", noKind.get("selftext_processed"), "missing kind unchanged");
        PollCompat.apply(null, withPoll);
        PollCompat.apply(values(3, "x"), null);
        check(true, "null arguments ignored");
    }

    private static void apply() {
        JSONObject withPoll = post("1abcde", poll(END + 365L * 24 * 3600 * 1000 * 50, 1, null, option("1", "A", null)));
        ContentValues values = values(3, "<p>Body</p>");
        PollCompat.apply(values, withPoll);
        String processed = (String) values.get("selftext_processed");
        check(processed.startsWith("<p>Body</p><p><strong>Poll</strong><br>Voting closes ") && processed.endsWith(LINK),
                "block appended after Sync's HTML: " + processed);
        equal("Body", values.get("selftext_raw"), "Markdown for editing unchanged");
        equal("Body", values.get("selftext_processed_preview"), "feed preview unchanged");
        ContentValues empty = values(3, "");
        PollCompat.apply(empty, withPoll);
        check(((String) empty.get("selftext_processed")).startsWith("<p><strong>Poll</strong>"), "empty body gets the block");
        ContentValues missing = values(3, null);
        PollCompat.apply(missing, withPoll);
        check(((String) missing.get("selftext_processed")).startsWith("<p><strong>Poll</strong>"), "missing body gets the block");
        ContentValues odd = values(3, null);
        odd.put("selftext_processed", 5);
        PollCompat.apply(odd, withPoll);
        equal(5, odd.get("selftext_processed"), "non-text body unchanged");
    }

    private static void pollUrl() {
        equal("https://www.reddit.com/poll/fo7p5b", PollCompat.pollUrl("fo7p5b"), "poll url");
        equal("https://www.reddit.com/poll/1abcdefghijkl", PollCompat.pollUrl("1abcdefghijkl"), "13-character id");
        for (String id : new String[] {null, "", "FO7P5B", "t3_fo7p5b", "fo7p5b/", "fo7p5b?x", "fo7p5b#x", "../fo7p5b",
                "fo7p 5b", "1abcdefghijklm", "föo"})
            check(PollCompat.pollUrl(id) == null, "invalid id " + id);
        // Sync routes reddit.com/poll/ links to its browser tab (LinkHandler.W) before its post classifier.
        check(PollCompat.pollUrl("fo7p5b").contains("reddit.com/poll/"), "link matches Sync's poll route");
    }
}
