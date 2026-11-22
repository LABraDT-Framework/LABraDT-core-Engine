package de.labradt.dt_engine.engine.aas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.NodeList;
import org.w3c.dom.*;

import java.io.*;
import java.util.zip.*;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Handles extraction of entries (rules, state chart XML) from an AASX zip
 * archive already loaded into memory. The AASX file itself is read from disk
 * by StateChartExecutor (engine.aasx.path); this class only operates on the
 * resulting bytes.
 */
@Slf4j
@Component
public class AASXImporter {

    /**
     * Extracts a specific entry from a zip InputStream.
     * Used for testing without modifying the actual file.
     *
     * @param zipStream  InputStream of the zip/aasx file
     * @param entryName  path of the entry inside the zip
     * @return InputStream of the entry content
     */
    public InputStream extractFromStream(InputStream zipStream, String entryName) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipStream);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            // Normalize separators. Windows zip tools use a backslash, this code uses a forward slash.
            String normalizedEntry = entry.getName().replace("\\", "/");
            String normalizedTarget = entryName.replace("\\", "/");
            if (normalizedEntry.equals(normalizedTarget)) {
                byte[] content = zis.readAllBytes();
                if (normalizedEntry.endsWith(".xml")) {
                    String xml = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    for (String old : new String[]{"Rules-SM", "StateChart-SM", "CPS-SM"}) {
                        xml = xml.replaceAll("(<(?:\\w+:)?(?:idShort|value)>\\s*)" + old + "(\\s*</(?:\\w+:)?(?:idShort|value)>)", "$1" + old.replace('-', '_') + "$2");
                    }
                    content = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                log.info("[AASXImporter] Extracted entry from stream: {}", entryName);
                return new ByteArrayInputStream(content);
            }
            zis.closeEntry();
        }
        throw new IOException("[AASXImporter] Entry not found in stream: " + entryName);
    }

    /**
     * Reads the behavior model location from the Behavior submodel
     * inside the AASX. Returns the path and submodelIdShort.
     * This replaces the hardcoded engine.rules.submodel.entry property.
     */
    public BehaviorModelLocation loadBehaviorModelLocation(InputStream xmlStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(xmlStream);

        String AAS_NS = "https://admin-shell.io/aas/3/0";
        NodeList submodels = doc.getElementsByTagNameNS(AAS_NS, "submodel");

        for (int i = 0; i < submodels.getLength(); i++) {
            Element sm = (Element) submodels.item(i);
            NodeList ids = sm.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals("Behavior")) {

                NodeList properties = sm.getElementsByTagNameNS(AAS_NS, "property");
                String path = null;
                String submodelIdShort = null;

                for (int j = 0; j < properties.getLength(); j++) {
                    Element prop = (Element) properties.item(j);
                    NodeList propIds = prop.getElementsByTagNameNS(AAS_NS, "idShort");
                    NodeList propVals = prop.getElementsByTagNameNS(AAS_NS, "value");
                    if (propIds.getLength() > 0 && propVals.getLength() > 0) {
                        String idShort = propIds.item(0).getTextContent().trim();
                        String value   = propVals.item(0).getTextContent().trim();
                        if ("path".equals(idShort))             path = value;
                        if ("submodelIdShort".equals(idShort))  submodelIdShort = value;
                    }
                }

                if (path != null) {
                    log.info("[AASXImporter] Behavior model location: path={} submodel={}",
                        path, submodelIdShort);
                    return new BehaviorModelLocation(path, submodelIdShort);
                }
            }
        }

        log.warn("[AASXImporter] Behavior submodel not found — using default path");
        return null;
    }

    /**
     * Simple record holding the behavior model location read from the AASX.
     */
    public record BehaviorModelLocation(String path, String submodelIdShort) {}
}
