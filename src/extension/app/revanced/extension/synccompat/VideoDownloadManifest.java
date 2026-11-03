package app.revanced.extension.synccompat;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Chooses the files Sync's Reddit video download saves from the video's DASH manifest.
 *
 * <p>Sync 23033's RedditVideoRequest ({@code r8.g}) parsed the manifest itself, and the bundled
 * official "Fix video downloads" port only re-reads unprefixed AdaptationSets by contentType. Sync's
 * own loop runs first and throws on a missing or invalid bandwidth or a missing BaseURL, so the
 * download failed before the port's hook. The patch "Sync: fix video download manifests" calls
 * {@link #links} before any of that.
 *
 * <p>The manifest must be well-formed XML with an MPD root (no DOCTYPE). Representations are read
 * with {@link DashManifest}, shared with comment video posters: any namespace prefix, video or audio
 * from mimeType or contentType on the Representation or its AdaptationSet. Each track takes the
 * representation with the highest valid bandwidth (for video without one, the largest picture).
 * Only single-file representations count: a SegmentTemplate or SegmentList cannot be saved as one
 * file. BaseURLs are resolved against the manifest URL; their own query (a signature) is kept and
 * only HTTPS results are used.
 */
public final class VideoDownloadManifest {
    static int MAX_MANIFEST_BYTES = 1024 * 1024;

    private static final Pattern DOCTYPE = Pattern.compile("<!(?:DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);

    private VideoDownloadManifest() {}

    /**
     * {video, audio} as absolute HTTPS URLs. Video is null when the manifest has no single-file
     * video track (audio is then ignored by the patch); audio is null for silent videos. Null when
     * the data is not a usable DASH manifest (malformed, oversized, DOCTYPE, not an MPD).
     */
    public static String[] links(byte[] data, String manifestUrl) {
        try {
            if (data == null || data.length > MAX_MANIFEST_BYTES) return null;
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            if (!isManifest(text)) return null;
            return select(text, manifestUrl);
        } catch (RuntimeException | LinkageError | StackOverflowError unusable) {
            return null;
        }
    }

    static String[] select(String manifest, String manifestUrl) {
        String video = null, audio = null;
        long videoRate = -1, videoArea = -1, audioRate = -1;
        for (DashManifest.Representation representation : DashManifest.representations(manifest)) {
            if (representation.segmented || representation.base == null) continue;
            String kind = kind(representation);
            if (kind == null) continue;
            String url = resolve(manifestUrl, representation.base);
            if (url == null) continue;
            long rate = bandwidth(representation);
            if (kind.equals("video")) {
                long area = (long) dimension(representation.attribute("width")) * dimension(representation.attribute("height"));
                if (video == null || rate > videoRate || (rate == videoRate && area > videoArea)) {
                    video = url;
                    videoRate = rate;
                    videoArea = area;
                }
            } else if (audio == null || rate > audioRate) {
                audio = url;
                audioRate = rate;
            }
        }
        return new String[] {video, audio};
    }

    /** "video", "audio" or null; the Representation's own type wins over its AdaptationSet's. */
    static String kind(DashManifest.Representation representation) {
        String kind = kind(representation.attributes.get("mimeType"), representation.attributes.get("contentType"));
        if (kind == null && representation.set != null)
            kind = kind(representation.set.get("mimeType"), representation.set.get("contentType"));
        if (kind != null) return kind.isEmpty() ? null : kind;
        // No type at all: Sync 23033's own rule, a file name with "audio" is the audio track.
        if (representation.base.toLowerCase(Locale.ROOT).contains("audio")) return "audio";
        return dimension(representation.attribute("width")) > 0 && dimension(representation.attribute("height")) > 0 ? "video" : null;
    }

    /** "video", "audio", "" for another declared type (subtitles), or null when undeclared. */
    private static String kind(String mime, String content) {
        if (mime != null) {
            String value = mime.trim().toLowerCase(Locale.ROOT);
            return value.startsWith("video/") ? "video" : value.startsWith("audio/") ? "audio" : "";
        }
        if (content != null) {
            String value = content.trim().toLowerCase(Locale.ROOT);
            return value.equals("video") || value.equals("audio") ? value : "";
        }
        return null;
    }

    /** A positive bandwidth in bits per second, or -1 when missing or invalid. */
    static long bandwidth(DashManifest.Representation representation) {
        String value = representation.attributes.get("bandwidth");
        if (value == null || !value.trim().matches("[0-9]{1,18}")) return -1;
        long rate = Long.parseLong(value.trim());
        return rate > 0 ? rate : -1;
    }

    private static int dimension(String value) {
        if (value == null || !value.trim().matches("[0-9]{1,5}")) return 0;
        return Integer.parseInt(value.trim());
    }

    /** The BaseURL against the manifest URL: an absolute HTTPS URL without fragment, or null. */
    static String resolve(String manifestUrl, String base) {
        if (base == null || base.isEmpty()) return null;
        for (int n = 0; n < base.length(); n++) if (base.charAt(n) <= ' ') return null;
        try {
            URI reference = new URI(base);
            URI resolved;
            if (reference.isAbsolute()) {
                resolved = reference;
            } else {
                if (manifestUrl == null) return null;
                URI manifest = new URI(manifestUrl);
                if (!"https".equalsIgnoreCase(manifest.getScheme()) || manifest.getHost() == null) return null;
                resolved = manifest.resolve(reference);
            }
            if (!"https".equalsIgnoreCase(resolved.getScheme()) || resolved.getHost() == null || resolved.getHost().isEmpty()
                    || resolved.getRawUserInfo() != null) return null;
            String url = resolved.toString();
            int fragment = url.indexOf('#');
            return fragment >= 0 ? url.substring(0, fragment) : url;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Well-formed XML whose root element is MPD, under any namespace; no DOCTYPE or entities. */
    static boolean isManifest(String text) {
        if (DOCTYPE.matcher(text).find()) return false;
        final String[] root = {null};
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newSAXParser().parse(new InputSource(new StringReader(text)), new DefaultHandler() {
                @Override public void startElement(String uri, String local, String qualified, Attributes attributes) {
                    if (root[0] != null) return;
                    String name = local != null && !local.isEmpty() ? local : qualified;
                    int colon = name == null ? -1 : name.indexOf(':');
                    root[0] = colon >= 0 ? name.substring(colon + 1) : name;
                }
            });
        } catch (SAXException | IOException | ParserConfigurationException malformed) {
            return false;
        }
        return "MPD".equals(root[0]);
    }
}
