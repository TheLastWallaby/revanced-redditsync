package app.revanced.extension.syncforreddit;

import android.util.Pair;
import androidx.annotation.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

// From ReVanced Patches v6.1.0. Local change: explicit types instead of `var`, because this
// bundle's extension is compiled for Java 8.

/**
 * @noinspection unused
 */
public class FixRedditVideoDownloadPatch {
    private static @Nullable Pair<Integer, String> getBestMpEntry(Element element) {
        NodeList representations = element.getElementsByTagName("Representation");
        ArrayList<Pair<Integer, String>> entries = new ArrayList<>();

        for (int i = 0; i < representations.getLength(); i++) {
            Element representation = (Element) representations.item(i);
            String bandwidthStr = representation.getAttribute("bandwidth");
            try {
                int bandwidth = Integer.parseInt(bandwidthStr);
                Node baseUrl = representation.getElementsByTagName("BaseURL").item(0);
                if (baseUrl != null) {
                    entries.add(new Pair<>(bandwidth, baseUrl.getTextContent()));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (entries.isEmpty()) {
            return null;
        }

        Collections.sort(entries, (e1, e2) -> e2.first - e1.first);
        return entries.get(0);
    }

    private static String[] parse(byte[] data) throws ParserConfigurationException, IOException, SAXException {
        NodeList adaptionSets = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(data))
                .getElementsByTagName("AdaptationSet");

        String videoUrl = null;
        String audioUrl = null;

        for (int i = 0; i < adaptionSets.getLength(); i++) {
            Element element = (Element) adaptionSets.item(i);
            String contentType = element.getAttribute("contentType");
            Pair<Integer, String> bestEntry = getBestMpEntry(element);
            if (bestEntry == null) continue;

            if (contentType.equalsIgnoreCase("video")) {
                videoUrl = bestEntry.second;
            } else if (contentType.equalsIgnoreCase("audio")) {
                audioUrl = bestEntry.second;
            }
        }

        return new String[]{videoUrl, audioUrl};
    }

    public static String[] getLinks(byte[] data) {
        try {
            return parse(data);
        } catch (ParserConfigurationException | IOException | SAXException e) {
            return new String[]{null, null};
        }
    }
}
