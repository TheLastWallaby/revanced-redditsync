package app.revanced.extension.synccompat;

import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.VolleyError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline checks for award metadata handling (issue #11). The listing loop, sheet parser and
 * detail sheet below are ports of Sync 23033's own code (c9.c.a, y8.c, t9.g$a/t9.i$a, s9.c),
 * so the tests show both the original failure and that the patched input no longer fails.
 */
public final class AwardCompatTest {
    private static int checks;
    private static final String POST_URL = "https://oauth.reddit.com/api/info/?id=t3_abc123";
    private static final String COMMENT_URL = "https://oauth.reddit.com/api/info/?id=t1_def456";
    private static final String POST_LINK = "/r/pics/comments/abc123/a_title/";
    private static final String COMMENT_LINK = "/r/pics/comments/abc123/a_title/def456/";

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private static NetworkResponse response(String body) {
        return new NetworkResponse(body.getBytes(StandardCharsets.UTF_8));
    }

    /** /api/info response with one item; awardings is raw JSON or null to omit the field. */
    private static String info(String name, String permalink, String awardings) {
        JSONObject data = new JSONObject().put("name", name);
        if (permalink != null) data.put("permalink", permalink);
        String item = data.toString();
        if (awardings != null) item = item.substring(0, item.length() - 1) + ",\"all_awardings\":" + awardings + "}";
        return "{\"kind\":\"Listing\",\"data\":{\"children\":[{\"kind\":\"" + name.substring(0, 2)
                + "\",\"data\":" + item + "}]}}";
    }

    private static String award(String name, int count, String extra) {
        return "{\"name\":" + JSONObject.quote(name) + ",\"count\":" + count + extra + "}";
    }

    private static final String ICONS = ",\"description\":\"Thanks %{coin_symbol}100\""
            + ",\"icon_url\":\"https://i.redd.it/award_images/t5_22cerq/abc.png\""
            + ",\"resized_icons\":[{\"url\":\"https://i.redd.it/award_images/t5_22cerq/abc_16.png\",\"width\":16},"
            + "{\"url\":\"https://preview.redd.it/award_images/t5_22cerq/abc.png?width=128&amp;height=128&amp;auto=webp&amp;s=f00\",\"width\":128}]";

    private static AwardCompat.Result parse(String body, String url) {
        return AwardCompat.parseAwards(response(body), url);
    }

    private static void error(AwardCompat.Result result, String message, String what) {
        check(result != null && result.awards == null && result.error instanceof AwardCompat.AwardsError,
                what + ": expected an error result");
        check(message.equals(result.error.getMessage()), what + ": message " + result.error.getMessage());
        check(message.equals(AwardCompat.errorMessage(result.error)), what + ": toast text");
    }

    private static void handoff(AwardCompat.Result result, String label, String url, String what) {
        check(result != null && result.error == null && result.awards != null && result.awards.length == 1, what + ": one row");
        o8.a row = result.awards[0];
        check(label.equals(row.b) && row.a == 0 && "".equals(row.c) && row.r == null, what + ": row fields");
        check(url.equals(AwardCompat.handoffUrl(row)), what + ": opens " + AwardCompat.handoffUrl(row));
        check(label.equals(sheetLabel(row)), what + ": no count suffix on the row");
    }

    public static void main(String[] args) throws Exception {
        sheetLookup();
        failures();
        fields();
        permalinks();
        listing();
        System.out.println("AwardCompatTest: " + checks + " checks passed");
    }

    private static void sheetLookup() {
        // Current supported metadata: entries with name, count, description and icons.
        String body = info("t3_abc123", POST_LINK, "[" + award("Helpful", 3, ICONS) + "," + award("Wholesome", 1, ICONS) + "]");
        AwardCompat.Result result = parse(body, POST_URL);
        check(result.error == null && result.awards.length == 2, "two supported awards");
        o8.a first = result.awards[0];
        check(first.a == 3 && "Helpful".equals(first.b) && "Thanks %{coin_symbol}100".equals(first.c), "award text fields");
        check("https://i.redd.it/award_images/t5_22cerq/abc.png".equals(first.q), "detail icon is icon_url");
        check("https://preview.redd.it/award_images/t5_22cerq/abc.png?width=128&height=128&auto=webp&s=f00".equals(first.r),
                "row icon is the last resized icon, unescaped like Sync's WebsafeUtils");
        check("Helpful \u00d7 3".equals(sheetLabel(first)) && "Wholesome".equals(sheetLabel(result.awards[1])), "sheet labels");
        check("Thanks 100".equals(detailDescription(first)), "detail sheet text");
        check(AwardCompat.handoffUrl(first) == null, "real awards keep the detail sheet");
        // The original parser handles this response too.
        check(originalSheetParse(body).length == 2, "baseline parser accepts complete metadata");

        // Optional icon fields: missing icons, icon arrays and descriptions no longer fail.
        body = info("t3_abc123", POST_LINK, "[" + award("Plain", 1, "") + "]");
        result = parse(body, POST_URL);
        o8.a plain = result.awards[0];
        check(result.error == null && plain.q == null && plain.r == null && "".equals(plain.c), "award without icons or text");
        check("".equals(detailDescription(plain)), "detail sheet no longer dereferences a null description");
        check(throwsOriginal(body), "baseline parser rejected an award without icon fields");
        body = info("t3_abc123", POST_LINK, "[" + award("Static", 2,
                ",\"resized_static_icons\":[{\"url\":\"https://www.redditstatic.com/gold/awards/icon/a_16.png\"}]") + "]");
        o8.a stat = parse(body, POST_URL).awards[0];
        check("https://www.redditstatic.com/gold/awards/icon/a_16.png".equals(stat.r) && stat.r.equals(stat.q),
                "static icons are used when animated ones are absent");
        body = info("t3_abc123", POST_LINK, "[" + award("Icon only", 1, ",\"icon_url\":\"https://i.redd.it/x.png\"") + "]");
        o8.a iconOnly = parse(body, POST_URL).awards[0];
        check("https://i.redd.it/x.png".equals(iconOnly.q) && "https://i.redd.it/x.png".equals(iconOnly.r), "icon_url fills the row icon");

        // Entries Sync can't show are skipped, not fatal.
        body = info("t3_abc123", POST_LINK, "[1,null,\"x\",{\"count\":4},{\"name\":\"\"},{\"name\":5}," + award("Kept", 1, "") + "]");
        result = parse(body, POST_URL);
        check(result.error == null && result.awards.length == 1 && "Kept".equals(result.awards[0].b), "unusable entries skipped");

        // Empty list: distinct from unavailable data, and offers the item on reddit.com.
        body = info("t3_abc123", POST_LINK, "[]");
        handoff(parse(body, POST_URL), AwardCompat.OPEN_NONE, "https://www.reddit.com" + POST_LINK, "empty list");
        check(originalSheetParse(body).length == 0, "baseline returned an empty sheet");
        error(parse(info("t3_abc123", null, "[]"), POST_URL), AwardCompat.NONE, "empty list without a permalink");

        // Missing all_awardings: unavailable, not empty, never "Could not load gallery".
        body = info("t3_abc123", POST_LINK, null);
        handoff(parse(body, POST_URL), AwardCompat.OPEN_UNAVAILABLE, "https://www.reddit.com" + POST_LINK, "missing all_awardings");
        check(throwsOriginal(body), "baseline failed on missing all_awardings");
        for (String shape : new String[] {"null", "{}", "\"none\"", "7", "[1,2]", "[{\"count\":1}]"}) {
            handoff(parse(info("t3_abc123", POST_LINK, shape), POST_URL), AwardCompat.OPEN_UNAVAILABLE,
                    "https://www.reddit.com" + POST_LINK, "unsupported all_awardings " + shape);
        }
        error(parse(info("t3_abc123", null, null), POST_URL), AwardCompat.UNAVAILABLE, "unavailable without a permalink");

        // Comments resolve to their own comment page.
        handoff(parse(info("t1_def456", COMMENT_LINK, null), COMMENT_URL), AwardCompat.OPEN_UNAVAILABLE,
                "https://www.reddit.com" + COMMENT_LINK, "comment handoff");
        check(parse(info("t1_def456", COMMENT_LINK, "[" + award("Gold", 1, ICONS) + "]"), COMMENT_URL).awards[0].a == 1,
                "comment awards");

        // Bounds: at most MAX_AWARDS rows and clamped counts.
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 250; i++) many.append(i == 0 ? "" : ",").append(award("A" + i, i, ""));
        result = parse(info("t3_abc123", POST_LINK, many + "]"), POST_URL);
        check(result.awards.length == AwardCompat.MAX_AWARDS && result.awards[0].a == 1, "award rows bounded, counts at least 1");
        check(AwardCompat.count(Long.MAX_VALUE) == AwardCompat.MAX_COUNT && AwardCompat.count(-5) == 1
                && AwardCompat.count("12") == 12 && AwardCompat.count("1e9") == 1 && AwardCompat.count(2.5) == 1
                && AwardCompat.count(null) == 1, "count parsing");
        for (o8.a row : result.awards) check(AwardCompat.handoffUrl(row) == null, "real award never mistaken for a handoff row");
    }

    private static void failures() {
        // Lookup failures report accurate errors instead of the gallery message.
        error(AwardCompat.parseAwards(null, POST_URL), AwardCompat.LOAD_FAILED, "no response");
        error(AwardCompat.parseAwards(new NetworkResponse((byte[]) null), POST_URL), AwardCompat.LOAD_FAILED, "no body");
        for (String bad : new String[] {"", "<html>blocked</html>", "[]", "{", "{\"data\":5}", "{\"data\":{\"children\":{}}}",
                "{\"error\":403,\"message\":\"Forbidden\"}"}) {
            error(parse(bad, POST_URL), AwardCompat.UNREADABLE, "unreadable " + bad);
        }
        error(parse("{\"data\":{\"children\":[]}}", POST_URL), AwardCompat.NOT_FOUND, "deleted or missing item");
        error(parse("{\"data\":{\"children\":[5,{\"data\":7}]}}", POST_URL), AwardCompat.NOT_FOUND, "non-object children");
        // Another item's awards are never shown, even if Reddit returns one.
        error(parse(info("t3_zzz999", "/r/pics/comments/zzz999/x/", "[" + award("Other", 1, "") + "]"), POST_URL),
                AwardCompat.NOT_FOUND, "mismatched item");
        check(parse(info("T3_ABC123", POST_LINK, "[" + award("Case", 1, "") + "]"), POST_URL).awards.length == 1,
                "fullname match ignores case");
        // Without a parseable requested ID the first item is used, but no handoff is offered.
        check(parse(info("t3_abc123", POST_LINK, "[" + award("First", 1, "") + "]"), "https://oauth.reddit.com/api/info/").awards.length == 1,
                "unknown request ID keeps Sync's first-item behavior");
        error(parse(info("t3_abc123", POST_LINK, null), null), AwardCompat.UNAVAILABLE, "no handoff without a verified ID");
        // Deeply nested input fails safely.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20000; i++) deep.append('[');
        AwardCompat.Result nested = parse("{\"data\":{\"children\":[{\"data\":{\"name\":\"t3_abc123\",\"all_awardings\":" + deep + "}}]}}", POST_URL);
        check(nested.awards == null || nested.awards.length <= 1, "deeply nested input handled");

        // Network/HTTP failures from Volley.
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new NoConnectionError())), "no connection");
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(null)), "null error");
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new VolleyError("Could not load gallery"))),
                "foreign error messages are not shown");
        NetworkResponse forbidden = new NetworkResponse(403, new byte[0], false, 0, Collections.emptyList());
        check("Couldn't load awards from Reddit (HTTP 403).".equals(AwardCompat.errorMessage(new VolleyError(forbidden))), "HTTP status");
        NetworkResponse odd = new NetworkResponse(200, new byte[0], false, 0, Collections.emptyList());
        check(AwardCompat.LOAD_FAILED.equals(AwardCompat.errorMessage(new VolleyError(odd))), "non-error status");

        // Request ID parsing.
        check("t3_abc123".equals(AwardCompat.requestedFullname(POST_URL)), "post ID");
        check("t1_def456".equals(AwardCompat.requestedFullname("https://oauth.reddit.com/api/info/?x=1&id=T1_DEF456#f")), "comment ID");
        for (String url : new String[] {null, "https://oauth.reddit.com/api/info/", "https://x/?id=t5_abc", "https://x/?id=t3_",
                "https://x/?id=t3_abc,t3_def", "https://x/?id=t3_" + "a".repeat(17), "https://x/?ids=t3_abc"}) {
            check(AwardCompat.requestedFullname(url) == null, "rejected request ID " + url);
        }
    }

    private static void fields() {
        check(AwardCompat.iconUrl("https://i.redd.it/a.png") != null, "i.redd.it icon");
        check(AwardCompat.iconUrl("https://www.redditstatic.com/a.png") != null, "redditstatic icon");
        check("https://preview.redd.it/a.png?width=1&s=2".equals(AwardCompat.iconUrl(" https://preview.redd.it/a.png?width=1&amp;s=2 ")), "unescaped");
        for (String bad : new String[] {null, "", "http://i.redd.it/a.png", "javascript:alert(1)", "https://example.com/a.png",
                "https://i.redd.it.example.com/a.png", "https://evilredd.it/a.png", "https://user@i.redd.it/a.png",
                "https://i.redd.it:8443/a.png", "https://i.redd.it/a b.png", "//i.redd.it/a.png", "https:///a.png"}) {
            check(AwardCompat.iconUrl(bad) == null, "rejected icon " + bad);
        }
        check(AwardCompat.iconUrl(7) == null && AwardCompat.iconUrl("https://i.redd.it/" + "a".repeat(2100)) == null, "non-string/oversized icon");
        JSONObject text = new JSONObject().put("a", "  x  ").put("b", "").put("c", 5).put("d", JSONObject.NULL).put("e", "y".repeat(501));
        check("x".equals(AwardCompat.text(text, "a")), "trimmed text");
        for (String key : new String[] {"b", "c", "d", "e", "missing"}) check(AwardCompat.text(text, key) == null, "rejected text " + key);
        check(AwardCompat.text(null, "a") == null, "null object");
    }

    private static void permalinks() {
        JSONObject item = new JSONObject();
        String[][] good = {
                {"t3_abc123", "/r/pics/comments/abc123/a_title/"},
                {"t3_abc123", "/r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/abc123"},
                {"t3_abc123", "/user/someone/comments/abc123/my_post/"},
                {"t3_abc123", "/r/u_someone/comments/abc123/x/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/def456/"},
                {"t1_def456", "/r/pics/comments/abc123/_/def456"},
        };
        for (String[] pair : good) {
            item.put("permalink", pair[1]);
            check(("https://www.reddit.com" + pair[1]).equals(AwardCompat.openUrl(item, pair[0])), "permalink " + pair[1]);
        }
        String[][] bad = {
                {"t3_abc123", "/r/pics/comments/zzz999/a_title/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/"},
                {"t1_def456", "/r/pics/comments/abc123/a_title/zzz999/"},
                {"t3_abc123", "https://evil.example/r/pics/comments/abc123/"},
                {"t3_abc123", "//evil.example/r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics//comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/abc123/../../../../logout"},
                {"t3_abc123", "/r/pics/comments/abc123/../x"},
                {"t1_def456", "/r/pics/comments/abc123/./def456/"},
                {"t3_abc123", "/r/pics/comments/abc123/a?b=1"},
                {"t3_abc123", "/r/pics/comments/abc123/a#b"},
                {"t3_abc123", "/r/pics/comments/abc123/a b/"},
                {"t3_abc123", "/r/pics/comments/abc123/a/b/c/d/"},
                {"t3_abc123", "/s/pics/comments/abc123/"},
                {"t3_abc123", "/r/pi.cs/comments/abc123/"},
                {"t3_abc123", "/r/pics/comment/abc123/"},
                {"t3_abc123", "r/pics/comments/abc123/"},
                {"t3_abc123", "/r/pics/comments/ABC123/"},
                {"t3_abc123", "/r/pics/comments/abc123/\u00e9/"},
        };
        for (String[] pair : bad) {
            item.put("permalink", pair[1]);
            check(AwardCompat.openUrl(item, pair[0]) == null, "rejected permalink " + pair[1]);
        }
        item.put("permalink", POST_LINK);
        check(AwardCompat.openUrl(item, null) == null && AwardCompat.openUrl(item, "t5_abc") == null, "requires a verified ID");
        check(AwardCompat.openUrl(new JSONObject(), "t3_abc123") == null, "requires a permalink");

        // Handoff detection uses only rows this patch built.
        o8.a forged = new o8.a();
        forged.q = "https://www.reddit.com/r/pics/";
        check("https://www.reddit.com/r/pics/".equals(AwardCompat.handoffUrl(forged)), "count 0 + reddit URL is a handoff row");
        forged.q = "https://evil.example/";
        check(AwardCompat.handoffUrl(forged) == null, "other hosts never open");
        forged.q = null;
        check(AwardCompat.handoffUrl(forged) == null && AwardCompat.handoffUrl("x") == null && AwardCompat.handoffUrl(null) == null,
                "non-rows never open");
    }

    private static void listing() {
        String full = "[{\"resized_static_icons\":[{\"url\":\"https://i.redd.it/s1.png\"},{\"url\":\"https://i.redd.it/s2.png\"},"
                + "{\"url\":\"https://i.redd.it/s3.png\"},{\"url\":\"https://i.redd.it/s4.png?a=1&amp;b=2\"}],"
                + "\"resized_icons\":[{\"url\":\"https://i.redd.it/r1.png\"}]}]";
        // Current complete metadata: identical icon choice to Sync's own loop.
        check(originalListing(new JSONArray(full)).equals(originalListing(AwardCompat.listingAwardings(new JSONArray(full)))),
                "complete metadata gives the same icons");
        check(Collections.singletonList("https://i.redd.it/s4.png?a=1&b=2").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(full)))),
                "fourth static icon chosen and unescaped by Sync");
        String animated = "[{\"resized_static_icons\":[{\"url\":\"https://external-preview.redd.it/a.png\"},{\"url\":\"https://i.redd.it/2\"},"
                + "{\"url\":\"https://i.redd.it/3\"},{\"url\":\"https://i.redd.it/4\"}],\"resized_icons\":[{\"url\":\"https://i.redd.it/a1\"},"
                + "{\"url\":\"https://i.redd.it/a2\"},{\"url\":\"https://i.redd.it/a3\"},{\"url\":\"https://i.redd.it/a4\"}]}]";
        check(Collections.singletonList("https://i.redd.it/a4").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(animated)))),
                "external-preview static icons still fall back to resized_icons");

        // Missing or changed fields: the original throws and fails the whole post/comment parse.
        String[] broken = {
                "[{\"name\":\"New award\"}]",
                "[{\"resized_static_icons\":null}]",
                "[{\"resized_static_icons\":[]}]",
                "[{\"resized_static_icons\":\"x\",\"resized_icons\":{}}]",
                "[5,\"x\",null]",
                "[{\"resized_static_icons\":[1,2,3,4,5]}]",
                "[{\"resized_static_icons\":[{},{},{},{\"width\":5}]}]",
        };
        for (String awardings : broken) {
            JSONArray original = new JSONArray(awardings);
            check(throwsListing(original), "baseline listing parse failed on " + awardings);
            List<String> icons = originalListing(AwardCompat.listingAwardings(new JSONArray(awardings)));
            check(icons.isEmpty(), "patched listing parse succeeds without icons for " + awardings);
        }
        // Untrusted icon URLs are dropped; valid ones keep Sync's index-3 choice among the rest.
        String mixed = "[{\"resized_static_icons\":[{\"url\":\"http://i.redd.it/1\"},{\"url\":\"https://i.redd.it/2\"},{\"url\":\"https://example.com/3\"},"
                + "{\"url\":\"https://i.redd.it/4\"},{\"url\":\"https://i.redd.it/5\"},{\"url\":\"https://i.redd.it/6\"}]}]";
        check(Collections.singletonList("https://i.redd.it/6").equals(originalListing(AwardCompat.listingAwardings(new JSONArray(mixed)))),
                "non-HTTPS and third-party icons dropped");
        check(AwardCompat.listingAwardings(null) == null, "absent all_awardings stays absent");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 500; i++) many.append(i == 0 ? "" : ",").append("{}");
        check(AwardCompat.listingAwardings(new JSONArray(many + "]")).length() == AwardCompat.MAX_AWARDS, "listing entries bounded");

        // gildings: Sync calls getJSONObject whenever has() is true.
        JSONObject post = new JSONObject("{\"a\":{\"gid_1\":1},\"b\":null,\"c\":\"x\",\"d\":[]}");
        check(AwardCompat.hasObject(post, "a"), "gildings object read");
        for (String key : new String[] {"b", "c", "d", "missing"}) {
            check(!AwardCompat.hasObject(post, key), "gildings " + key + " skipped");
            check(!post.has(key) || throwsGetObject(post, key), "baseline gildings " + key + " would throw");
        }
        check(!AwardCompat.hasObject(null, "a"), "null post");
    }

    // ---- Ports of Sync 23033 code (behavior only) ----

    /** c9.c.a: the all_awardings loop, run when total_awards_received > 0. */
    private static List<String> originalListing(JSONArray awardings) {
        List<String> out = new ArrayList<>();
        if (awardings == null) return out;
        for (int i = 0; i < awardings.length(); i++) {
            JSONObject entry = awardings.getJSONObject(i);
            if (entry == null) continue;
            JSONArray icons = entry.getJSONArray("resized_static_icons");
            String url = null;
            if (icons != null && !icons.toString().contains("external-preview") && icons.length() > 3) {
                url = icons.getJSONObject(3).getString("url");
            } else {
                icons = entry.getJSONArray("resized_icons");
                if (icons != null && icons.length() > 3) url = icons.getJSONObject(3).getString("url");
            }
            if (url != null && !url.isEmpty()) out.add(websafe(url));
        }
        return out;
    }

    private static boolean throwsListing(JSONArray awardings) {
        try {
            originalListing(awardings);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static boolean throwsGetObject(JSONObject object, String key) {
        try {
            object.getJSONObject(key);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    /** y8.c.parseNetworkResponse before the patch. */
    private static o8.a[] originalSheetParse(String body) {
        JSONArray list = new JSONObject(body).getJSONObject("data").getJSONArray("children").getJSONObject(0)
                .getJSONObject("data").getJSONArray("all_awardings");
        o8.a[] out = new o8.a[list.length()];
        for (int i = 0; i < out.length; i++) {
            JSONObject entry = list.getJSONObject(i);
            out[i] = new o8.a();
            out[i].a = entry.getInt("count");
            out[i].b = entry.getString("name");
            out[i].c = entry.getString("description");
            out[i].q = entry.getString("icon_url");
            JSONArray resized = entry.getJSONArray("resized_icons");
            out[i].r = websafe(resized.getJSONObject(resized.length() - 1).getString("url"));
        }
        return out;
    }

    private static boolean throwsOriginal(String body) {
        try {
            originalSheetParse(body);
            return false;
        } catch (RuntimeException expected) {
            // Sync then reported "Could not load gallery".
            return true;
        }
    }

    /** t9.g$a / t9.i$a row label. */
    private static String sheetLabel(o8.a award) {
        return award.b + (award.a > 1 ? " \u00d7 " + award.a : "");
    }

    /** s9.c.o2: the detail sheet's description line. */
    private static String detailDescription(o8.a award) {
        return award.c.replaceAll("\\%\\{coin_symbol\\}", "");
    }

    /** wc.p.a (WebsafeUtils). */
    private static String websafe(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("\u202e", "").replaceAll("\u200f", "");
    }
}
