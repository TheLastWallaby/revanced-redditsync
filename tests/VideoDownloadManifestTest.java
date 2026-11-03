package app.revanced.extension.synccompat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * VideoDownloadManifest and the shared DashManifest reader on hand-written manifests and the real
 * Reddit manifest from CommentVideoTest: contentType and mimeType on the AdaptationSet and on the
 * Representation, namespace prefixes, missing and invalid bandwidths, relative and absolute BaseURLs
 * with signed queries, silent videos, missing video, SegmentTemplate/SegmentList-only manifests,
 * malformed XML, DOCTYPEs and the size limit. No network.
 */
public final class VideoDownloadManifestTest {
    private static final String URL = "https://v.redd.it/abc123/DASHPlaylist.mpd";
    private static final String BASE = "https://v.redd.it/abc123/";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static void links(String video, String audio, String manifest, String message) {
        String[] links = VideoDownloadManifest.links(manifest.getBytes(StandardCharsets.UTF_8), URL);
        check(links != null, message + ": manifest accepted");
        equal(Arrays.asList(video, audio), Arrays.asList(links), message);
    }
    private static void rejected(String manifest, String message) {
        equal(null, VideoDownloadManifest.links(manifest == null ? null : manifest.getBytes(StandardCharsets.UTF_8), URL), message);
    }

    private static String mpd(String sets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period>" + sets + "</Period></MPD>";
    }
    private static String rep(String attributes, String base) {
        return "<Representation " + attributes + "><BaseURL>" + base + "</BaseURL></Representation>";
    }

    public static void main(String[] args) {
        reader();
        types();
        bandwidths();
        urls();
        tracks();
        rejections();
        real();
        System.out.println("PASS: " + checks + " video download manifest checks (contentType/mimeType on set and representation, "
                + "namespace prefixes, bandwidths, BaseURLs, silent, missing video, segments, malformed; real Reddit manifest)");
    }

    // ---- The shared reader ----

    private static void reader() {
        List<DashManifest.Representation> list = DashManifest.representations(
                "<mpd:MPD xmlns:mpd='urn:mpeg:dash:schema:mpd:2011'><mpd:Period><mpd:AdaptationSet mpd:mimeType='video/mp4'>"
                + "<!-- <mpd:Representation bandwidth='9'><mpd:BaseURL>commented.mp4</mpd:BaseURL></mpd:Representation> -->"
                + "<mpd:Representation width='1' height='1' bandwidth='5'><mpd:BaseURL> a.mp4?x=1&amp;y=&#50;&#x33; </mpd:BaseURL></mpd:Representation>"
                + "<mpd:Representation bandwidth='6'/>"
                + "<mpd:Representation bandwidth='7'><mpd:SegmentTemplate media='$Number$.m4s'/></mpd:Representation>"
                + "</mpd:AdaptationSet></mpd:Period></mpd:MPD>");
        equal(2, list.size(), "commented and self-closing representations skipped");
        DashManifest.Representation first = list.get(0);
        equal("a.mp4?x=1&y=23", first.base, "BaseURL trimmed and unescaped");
        equal("video/mp4", first.attribute("mimeType"), "prefixed attribute read from the set");
        equal("5", first.attribute("bandwidth"), "own attribute");
        check(!first.segmented, "single file");
        check(list.get(1).segmented && list.get(1).base == null, "SegmentTemplate noted");
        equal("<&>\"'&unknown;&#0;", DashManifest.unescape("&lt;&amp;&gt;&quot;&apos;&unknown;&#0;"), "unescape");
        check(DashManifest.representations(null).isEmpty(), "null manifest");
    }

    // ---- Track types ----

    private static void types() {
        String video = rep("bandwidth='2000' width='1280' height='720'", "DASH_720.mp4");
        String audio = rep("bandwidth='128000'", "DASH_AUDIO_128.mp4");
        links(BASE + "DASH_720.mp4", BASE + "DASH_AUDIO_128.mp4", mpd("<AdaptationSet contentType='video'>" + video
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + audio + "</AdaptationSet>"), "contentType on the set");
        links(BASE + "DASH_720.mp4", BASE + "DASH_AUDIO_128.mp4", mpd("<AdaptationSet mimeType='video/mp4'>" + video
                + "</AdaptationSet><AdaptationSet mimeType='audio/mp4'>" + audio + "</AdaptationSet>"), "mimeType on the set");
        links(BASE + "v.mp4", BASE + "a.mp4", mpd("<AdaptationSet>" + rep("mimeType='video/mp4' bandwidth='1'", "v.mp4")
                + rep("mimeType='audio/mp4; codecs=\"mp4a.40.2\"' bandwidth='1'", "a.mp4") + "</AdaptationSet>"), "mimeType on the representation");
        links(BASE + "v.mp4", BASE + "a.mp4", mpd("<AdaptationSet>" + rep("contentType='VIDEO' bandwidth='1'", "v.mp4")
                + rep("contentType='Audio' bandwidth='1'", "a.mp4") + "</AdaptationSet>"), "contentType on the representation, any case");
        links(BASE + "v.mp4", BASE + "a.mp4", mpd("<AdaptationSet contentType='video'>" + rep("mimeType='video/mp4' bandwidth='1'", "v.mp4")
                + rep("mimeType='audio/mp4' bandwidth='1'", "a.mp4") + "</AdaptationSet>"), "the representation's type wins over the set's");
        links(BASE + "v.mp4", null, mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "v.mp4")
                + "</AdaptationSet><AdaptationSet mimeType='text/vtt'>" + rep("bandwidth='9'", "subs.mp4") + "</AdaptationSet>"
                + "<AdaptationSet contentType='text'>" + rep("bandwidth='9'", "more-subs.mp4") + "</AdaptationSet>"), "subtitles ignored");
        // No declared type: Sync 23033's own rule (a name with "audio"), and a picture size for video.
        links(BASE + "DASH_480.mp4", BASE + "DASH_audio.mp4", mpd("<AdaptationSet>" + rep("width='854' height='480' bandwidth='9'", "DASH_480.mp4")
                + "</AdaptationSet><AdaptationSet>" + rep("bandwidth='1'", "DASH_audio.mp4") + "</AdaptationSet>"), "untyped legacy manifest");
        // Namespace prefixes, on elements and attributes.
        links(BASE + "DASH_1080.mp4", BASE + "DASH_audio.mp4", "<?xml version='1.0'?><mpd:MPD xmlns:mpd='urn:mpeg:dash:schema:mpd:2011'><mpd:Period>"
                + "<mpd:AdaptationSet mimeType='video/mp4'>"
                + "<mpd:Representation width='1920' height='1080' bandwidth='4800000'><mpd:BaseURL>DASH_1080.mp4</mpd:BaseURL></mpd:Representation>"
                + "<mpd:Representation width='1280' height='720' bandwidth='2400000'><mpd:BaseURL>DASH_720.mp4</mpd:BaseURL></mpd:Representation>"
                + "</mpd:AdaptationSet><mpd:AdaptationSet mpd:contentType='audio'>"
                + "<mpd:Representation bandwidth='128000'><mpd:BaseURL>DASH_audio.mp4</mpd:BaseURL></mpd:Representation>"
                + "</mpd:AdaptationSet></mpd:Period></mpd:MPD>", "namespace prefixes");
    }

    // ---- Choosing among representations ----

    private static void bandwidths() {
        links(BASE + "high.mp4", BASE + "a2.mp4", mpd("<AdaptationSet contentType='video'>"
                + rep("bandwidth='800000' width='640' height='360'", "low.mp4")
                + rep("bandwidth='abc' width='3840' height='2160'", "invalid.mp4")
                + rep("width='2560' height='1440'", "missing.mp4")
                + rep("bandwidth='-5' width='1920' height='1080'", "negative.mp4")
                + rep("bandwidth='0' width='1920' height='1080'", "zero.mp4")
                + rep("bandwidth='99999999999999999999' width='1920' height='1080'", "huge.mp4")
                + rep("bandwidth=' 2400000 ' width='1280' height='720'", "high.mp4")
                + "</AdaptationSet><AdaptationSet contentType='audio'>"
                + rep("bandwidth='64000'", "a1.mp4") + rep("bandwidth='x'", "a-invalid.mp4") + rep("bandwidth='128000'", "a2.mp4")
                + "</AdaptationSet>"), "highest valid bandwidth; missing and invalid ones rank below");
        links(BASE + "big.mp4", BASE + "first.mp4", mpd("<AdaptationSet contentType='video'>"
                + rep("width='640' height='360'", "small.mp4") + rep("bandwidth='nope' width='1920' height='1080'", "big.mp4")
                + rep("width='1280' height='720'", "medium.mp4") + "</AdaptationSet><AdaptationSet contentType='audio'>"
                + rep("", "first.mp4") + rep("bandwidth='?'", "second.mp4") + "</AdaptationSet>"), "no valid bandwidth: largest picture, first audio");
        links(BASE + "one.mp4", null, mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='5'", "one.mp4")
                + rep("bandwidth='5'", "two.mp4") + "</AdaptationSet>"), "ties keep the first");
    }

    // ---- BaseURLs ----

    private static void urls() {
        String signed = "https://v.redd.it/abc123/DASH_720.mp4?source=fallback&a=1700000000%2CSIGNATURE%3D";
        links(signed, "https://cdn.example.test/x/audio.mp4?sig=abc",
                mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", signed.replace("&", "&amp;"))
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + rep("bandwidth='1'", "https://cdn.example.test/x/audio.mp4?sig=abc#frag")
                + "</AdaptationSet>"), "absolute BaseURLs with signed queries kept, fragment removed");
        links(BASE + "DASH_720.mp4?a=sig%3D&b=2", BASE + "audio/DASH_AUDIO.mp4",
                mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "DASH_720.mp4?a=sig%3D&amp;b=2")
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + rep("bandwidth='1'", "./audio/DASH_AUDIO.mp4") + "</AdaptationSet>"),
                "relative BaseURLs resolved against the manifest, their query kept");
        equal("https://v.redd.it/other/v.mp4", VideoDownloadManifest.resolve(URL, "../other/v.mp4"), "parent path");
        equal("https://v.redd.it/v.mp4", VideoDownloadManifest.resolve(URL, "/v.mp4"), "absolute path");
        equal("https://v.redd.it/abc123/v.mp4", VideoDownloadManifest.resolve("https://v.redd.it/abc123/DASHPlaylist.mpd?a=manifest-sig", "v.mp4"),
                "the manifest's own query is not copied to its files");
        for (String bad : new String[] {"http://v.redd.it/v.mp4", "javascript:alert(1)", "file:///sdcard/x.mp4", "content://x/y",
                "data:video/mp4;base64,AAAA", "https://user:pw@v.redd.it/v.mp4", "https:///v.mp4", "v 1.mp4", "v\t.mp4", "", "%zz", "https://[bad"})
            equal(null, VideoDownloadManifest.resolve(URL, bad), "rejected BaseURL " + bad);
        equal(null, VideoDownloadManifest.resolve("http://v.redd.it/abc123/DASHPlaylist.mpd", "v.mp4"), "relative to a plain-HTTP manifest");
        equal(null, VideoDownloadManifest.resolve(null, "v.mp4"), "relative without a manifest URL");
        equal("https://v.redd.it/x.mp4", VideoDownloadManifest.resolve(null, "https://v.redd.it/x.mp4"), "absolute without a manifest URL");
        links(BASE + "good.mp4", null, mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='9'", "http://v.redd.it/better.mp4")
                + rep("bandwidth='8'", "javascript:x") + rep("bandwidth='1'", "good.mp4") + "</AdaptationSet>"), "unusable URLs skipped");
    }

    // ---- Which tracks there are ----

    private static void tracks() {
        links(BASE + "DASH_480.mp4", null, mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1' width='480' height='480'", "DASH_480.mp4")
                + "</AdaptationSet>"), "silent video: video alone");
        links(null, BASE + "DASH_audio.mp4", mpd("<AdaptationSet contentType='audio'>" + rep("bandwidth='1'", "DASH_audio.mp4") + "</AdaptationSet>"),
                "no video track: no video (the patch then ignores audio)");
        links(null, null, mpd(""), "empty period");
        String template = "<SegmentTemplate media='chunk_$Number$.m4s' initialization='init.mp4' startNumber='1'/>";
        links(null, null, mpd("<AdaptationSet contentType='video'>" + template
                + "<Representation bandwidth='1' width='1' height='1'/></AdaptationSet>"), "SegmentTemplate only");
        links(null, BASE + "a.mp4", mpd("<AdaptationSet contentType='video'>" + template + rep("bandwidth='5' width='1' height='1'", "prefix/")
                + "</AdaptationSet><AdaptationSet contentType='audio'>" + rep("bandwidth='1'", "a.mp4") + "</AdaptationSet>"),
                "BaseURL with an AdaptationSet SegmentTemplate is a prefix, not a file");
        links(null, null, "<MPD><Period>" + template + "<AdaptationSet contentType='video'>" + rep("bandwidth='5'", "v.mp4")
                + "</AdaptationSet></Period></MPD>", "Period-level SegmentTemplate");
        links(null, null, mpd("<AdaptationSet contentType='video'><Representation bandwidth='5'><BaseURL>v/</BaseURL>"
                + "<SegmentList><SegmentURL media='1.m4s'/></SegmentList></Representation></AdaptationSet>"), "SegmentList");
        links(BASE + "file.mp4", null, mpd("<AdaptationSet contentType='video'>" + template + rep("bandwidth='9'", "x/")
                + "</AdaptationSet><AdaptationSet contentType='video'>" + rep("bandwidth='1'", "file.mp4") + "</AdaptationSet>"),
                "a single-file set is used when another set is segmented");
        links(BASE + "v.mp4", null, mpd("<AdaptationSet contentType='video'><Representation bandwidth='1'><BaseURL>v.mp4</BaseURL>"
                + "<SegmentBase indexRange='1-2'/></Representation></AdaptationSet>"), "SegmentBase is a single file");
        links(null, null, mpd("<AdaptationSet contentType='video'><Representation bandwidth='1' width='9' height='9'></Representation></AdaptationSet>"),
                "representation without BaseURL");
    }

    // ---- Not a usable manifest ----

    private static void rejections() {
        rejected(null, "no data");
        rejected("", "empty");
        rejected("not xml at all", "text");
        rejected("<MPD><Period><AdaptationSet contentType='video'>" + rep("bandwidth='1'", "v.mp4"), "truncated");
        rejected("<MPD><Period></AdaptationSet></Period></MPD>", "mismatched tags");
        rejected("<MPD bandwidth=1></MPD>", "unquoted attribute");
        rejected("<MPD>&bogus;</MPD>", "undefined entity");
        rejected("<MPD></MPD><MPD></MPD>", "two roots");
        rejected("<html><body>Not found</body></html>", "HTML error page");
        rejected("<Manifest><Period/></Manifest>", "other root element");
        rejected("<?xml version='1.0'?><!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><MPD>&x;</MPD>", "DOCTYPE with an external entity");
        rejected("<!doctype MPD><MPD/>", "DOCTYPE in any case");
        int limit = VideoDownloadManifest.MAX_MANIFEST_BYTES;
        try {
            VideoDownloadManifest.MAX_MANIFEST_BYTES = 400;
            String small = mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "v.mp4") + "</AdaptationSet>");
            check(small.length() <= 400, "small fixture fits: " + small.length());
            links(BASE + "v.mp4", null, small, "under the size limit");
            rejected(mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "v.mp4") + "</AdaptationSet>" + " ".repeat(400)), "oversized");
        } finally {
            VideoDownloadManifest.MAX_MANIFEST_BYTES = limit;
        }
        links(BASE + "v.mp4", null, "﻿" + mpd("<AdaptationSet contentType='video'>" + rep("bandwidth='1'", "v.mp4") + "</AdaptationSet>"),
                "byte order mark");
    }

    // ---- Reddit's own manifest ----

    private static void real() {
        String id = "https://v.redd.it/zj9xk97frsyh1/";
        String[] links = VideoDownloadManifest.links(CommentVideoTest.REDDIT_MPD.getBytes(StandardCharsets.UTF_8), id + "DASHPlaylist.mpd");
        equal(Arrays.asList(id + "CMAF_480.mp4", null), Arrays.asList(links), "real Reddit comment clip: best rendition, silent");
        // The same clip with an audio set as Reddit lists one for videos with sound.
        String withAudio = CommentVideoTest.REDDIT_MPD.replace("    </AdaptationSet>\n",
                "    </AdaptationSet>\n    <AdaptationSet contentType=\"audio\" id=\"1\" segmentAlignment=\"true\" subsegmentAlignment=\"true\">\n"
                + "      <Representation audioSamplingRate=\"48000\" bandwidth=\"69120\" codecs=\"mp4a.40.2\" id=\"4\" mimeType=\"audio/mp4\">\n"
                + "        <BaseURL>CMAF_AUDIO_64.mp4</BaseURL>\n      </Representation>\n"
                + "      <Representation audioSamplingRate=\"48000\" bandwidth=\"132203\" codecs=\"mp4a.40.2\" id=\"5\" mimeType=\"audio/mp4\">\n"
                + "        <BaseURL>CMAF_AUDIO_128.mp4</BaseURL>\n      </Representation>\n    </AdaptationSet>\n");
        check(!withAudio.equals(CommentVideoTest.REDDIT_MPD), "audio set added");
        links = VideoDownloadManifest.links(withAudio.getBytes(StandardCharsets.UTF_8), id + "DASHPlaylist.mpd");
        equal(Arrays.asList(id + "CMAF_480.mp4", id + "CMAF_AUDIO_128.mp4"), Arrays.asList(links), "real layout with sound");
        // CommentVideo still reads the same renditions through the shared reader.
        equal(4, CommentVideo.renditions(CommentVideoTest.REDDIT_MPD).size(), "comment poster renditions unchanged");
    }
}
