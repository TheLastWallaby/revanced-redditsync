package app.revanced.extension.synccompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a DASH manifest this bundle reads, shared by comment video posters
 * ({@link CommentVideo}) and video downloads ({@link VideoDownloadManifest}).
 *
 * <p>A tag scanner, not a full XML parser: it finds AdaptationSet, Representation, BaseURL,
 * SegmentTemplate and SegmentList elements under any namespace prefix, reads their attributes
 * (also without prefix), and skips XML comments. Attribute values and BaseURL text have XML's
 * character references decoded. Callers that need a well-formed document check that separately.
 */
final class DashManifest {
    /** One Representation with its AdaptationSet's attributes and its own BaseURL text. */
    static final class Representation {
        /** The enclosing AdaptationSet's attributes, or null outside one. */
        final Map<String, String> set;
        final Map<String, String> attributes;
        /** The Representation's BaseURL text, trimmed, or null. The last one wins. */
        final String base;
        /** A SegmentTemplate or SegmentList applies: the media is split into segments. */
        final boolean segmented;

        Representation(Map<String, String> set, Map<String, String> attributes, String base, boolean segmented) {
            this.set = set;
            this.attributes = attributes;
            this.base = base;
            this.segmented = segmented;
        }

        /** The Representation's own value, else its AdaptationSet's. */
        String attribute(String name) {
            String value = attributes.get(name);
            if (value == null && set != null) value = set.get(name);
            return value;
        }
    }

    private static final Pattern TAG = Pattern.compile(
            "<(/?)(?:[A-Za-z_][\\w.-]*:)?(AdaptationSet|Representation|BaseURL|SegmentTemplate|SegmentList)\\b([^>]*?)(/?)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][\\w.:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern REFERENCE = Pattern.compile("&(?:#([0-9]{1,7})|#x([0-9A-Fa-f]{1,6})|(amp|lt|gt|quot|apos));");

    private DashManifest() {}

    /** Representations in document order. A self-closing Representation has no BaseURL and is skipped. */
    static List<Representation> representations(String manifest) {
        if (manifest == null) return Collections.emptyList();
        String text = COMMENT.matcher(manifest).replaceAll("");
        List<Representation> result = new ArrayList<>();
        Map<String, String> set = null;
        Map<String, String> representation = null;
        String base = null;
        boolean outerSegments = false, setSegments = false, ownSegments = false;
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            boolean empty = !matcher.group(4).isEmpty();
            String name = matcher.group(2);
            if (name.equals("AdaptationSet")) {
                set = closing || empty ? null : attributes(matcher.group(3));
                setSegments = false;
            } else if (name.equals("Representation")) {
                if (closing) {
                    if (representation != null)
                        result.add(new Representation(set, representation, base, outerSegments || setSegments || ownSegments));
                    representation = null;
                } else {
                    representation = empty ? null : attributes(matcher.group(3));
                }
                base = null;
                ownSegments = false;
            } else if (name.equals("BaseURL")) {
                if (!closing && !empty && representation != null) {
                    int end = text.indexOf('<', matcher.end());
                    if (end > matcher.end()) base = unescape(text.substring(matcher.end(), end).trim());
                }
            } else if (!closing) {
                // SegmentTemplate or SegmentList, at Period, AdaptationSet or Representation level.
                if (representation != null) ownSegments = true;
                else if (set != null) setSegments = true;
                else outerSegments = true;
            }
        }
        return result;
    }

    /** Attributes by local name (any namespace prefix removed), values decoded. */
    static Map<String, String> attributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            values.put(name, unescape(matcher.group(3) != null ? matcher.group(3) : matcher.group(4)));
        }
        return values;
    }

    /** Decodes XML's predefined entities and numeric character references; others stay as written. */
    static String unescape(String text) {
        if (text.indexOf('&') < 0) return text;
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String replacement = matcher.group();
            if (matcher.group(3) != null) {
                switch (matcher.group(3)) {
                    case "amp": replacement = "&"; break;
                    case "lt": replacement = "<"; break;
                    case "gt": replacement = ">"; break;
                    case "quot": replacement = "\""; break;
                    default: replacement = "'";
                }
            } else {
                int code = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : Integer.parseInt(matcher.group(2), 16);
                if (code > 0 && code <= Character.MAX_CODE_POINT) replacement = new String(Character.toChars(code));
            }
            out.append(replacement);
            last = matcher.end();
        }
        return out.append(text, last, text.length()).toString();
    }
}
