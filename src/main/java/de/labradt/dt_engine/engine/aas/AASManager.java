package de.labradt.dt_engine.engine.aas;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.basyx.submodel.metamodel.map.Submodel;
import org.eclipse.basyx.submodel.metamodel.map.submodelelement.dataelement.property.Property;
import de.labradt.dt_engine.engine.model.Rule;
import de.labradt.dt_engine.shared.FileStorageService;
import de.labradt.dt_engine.shared.db.DatabaseStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;

/**
 * Manages AAS submodels in memory using BaSyx data model classes.
 *
 * Parses the AAS XML (V3 format) using Java's DOM parser, then stores
 * data in BaSyx ISubmodel and Property objects for a representation
 * kept in memory that aligns with the standard.
 *
 * Called during INITIALIZING to load rules from Rules_SM, and during
 * SYNCHRONIZING to read and write submodel data.
 */
@Slf4j
@Component
public class AASManager {

    private static final String AAS_NS = "https://admin-shell.io/aas/3/0";

    // BaSyx Submodel objects held in memory during runtime
    private final Map<String, Submodel> submodelRegistry = new HashMap<>();

    // BaSyx Property objects per submodel for fast read/write
    private final Map<String, Map<String, Property>> propertyRegistry = new HashMap<>();

    // Parsed rules from Rules_SM
    private List<Rule> rules = new ArrayList<>();

    @Autowired(required = false)
    private FileStorageService storageService;

    @Autowired(required = false)
    private DatabaseStorageService dbStorageService;

    /**
     * idShort of the submodel containing rule definitions. Defaults to
     * "Rules_SM" (the engine's original name, still the most common one)
     * but is configurable through engine.rules.submodel.idShort so an
     * AASX can rename this container without a code change. Note that
     * this only renames which single submodel plays the role. The
     * engine still looks for exactly one rules container per AASX, not
     * several.
     *
     * The field initializer below matches the @Value default so a plain
     * "new AASManager()" (as the test suite does, without a Spring context)
     * still gets "Rules_SM" instead of null.
     */
    @Value("${engine.rules.submodel.idShort:Rules_SM}")
    private String rulesSubmodelIdShort = "Rules_SM";

    /**
     * Parses the AAS XML using Java's DOM parser, builds BaSyx Submodel
     * and Property objects in memory, then extracts rules from
     * Rules_SM.
     *
     * Called during INITIALIZING.
     *
     * @param xmlStream the extracted behavior model XML to parse rules from
     * @param aasxBytes the raw bytes of the source AASX, forwarded to
     *                  persistRulesToStorage so the initialization
     *                  marker can record a content hash. May be null
     *                  (the marker will then carry no hash and the
     *                  engine will import the rules again next
     *                  startup).
     */
    public void loadRules(InputStream xmlStream, byte[] aasxBytes) throws Exception {
        log.info("[AASManager] Parsing AAS XML into BaSyx data model objects...");

        // Parse XML
        byte[] bytes = xmlStream.readAllBytes();
        String xmlContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (xmlContent.startsWith("\uFEFF")) {
            xmlContent = xmlContent.substring(1);
        }
        xmlContent = xmlContent.trim();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(
                xmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Find all submodels and build BaSyx objects
        NodeList submodelNodes = doc.getElementsByTagNameNS(AAS_NS, "submodel");
        for (int i = 0; i < submodelNodes.getLength(); i++) {
            Element smEl = (Element) submodelNodes.item(i);
            String idShort = getDirectChildText(smEl, "idShort");

            // Build BaSyx Submodel object
            Submodel submodel = new Submodel();
            submodel.setIdShort(idShort);

            // Register every property under its idShort PATH, so nested
            // (IDTA-style) submodels are addressable without collisions.
            Map<String, Property> properties = new LinkedHashMap<>();
            Element elementsEl = getDirectChildElement(smEl, "submodelElements");
            if (elementsEl != null) {
                registerPropertiesByPath(elementsEl, "", properties);
            }

            submodelRegistry.put(idShort, submodel);
            propertyRegistry.put(idShort, properties);
            log.info("[AASManager] Registered submodel: {} with {} properties",
                idShort, properties.size());
        }

        // Extract rules from the configured rules submodel using DOM, scoped to that one container only.
        rules = new ArrayList<>();
        Element rulesSMEl = findSubmodelElement(doc, rulesSubmodelIdShort);
        if (rulesSMEl == null) {
            log.warn("[AASManager] {} not found", rulesSubmodelIdShort);
            return;
        }

        log.info("[AASManager] Found {} — parsing rules...", rulesSubmodelIdShort);
        Element submodelElementsEl = getDirectChildElement(rulesSMEl, "submodelElements");
        if (submodelElementsEl == null) return;

        NodeList collections = submodelElementsEl.getChildNodes();
        for (int i = 0; i < collections.getLength(); i++) {
            if (!(collections.item(i) instanceof Element)) continue;
            Element col = (Element) collections.item(i);
            if (!"submodelElementCollection".equals(col.getLocalName())) continue;

            Rule rule = parseRule(col);
            if (rule == null) continue;

            // A collection is a rule if and only if it declares a condition.
            // Identifying rules by shape rather than by container lets the
            // rules submodel hold other definitions too (for example a merged
            // layout where States and Transitions live in the same submodel),
            // so splitting or merging the behavior definition across
            // submodels is the Integration Engineer's choice.
            if (rule.getCondition() == null) {
                log.info("[AASManager] Collection {} in {} declares no condition — not a rule, ignored",
                    rule.getIdShort(), rulesSubmodelIdShort);
                continue;
            }
            rules.add(rule);
            log.info("[AASManager] Loaded rule: {}", rule.getDescription());
        }

        log.info("[AASManager] Total rules loaded: {}", rules.size());
        persistRulesToStorage(aasxBytes);
    }

    /**
     * Transforms one rule's {@code submodelElementCollection} XML
     * element into a {@link Rule} object by iterating over every
     * {@code <property>} child and storing each idShort and value pair
     * through {@link Rule#setAttribute}.
     *
     * <p><b>This method is intentionally not tied to specific
     * fields.</b> It does not contain a switch or an if chain that maps
     * specific idShorts to specific Java fields. Any property present
     * in the AASX is captured automatically, regardless of name, so
     * adding a new rule property (for example {@code severity},
     * {@code unit}, {@code correctedMax}) is done entirely in the AASX,
     * with no change needed here or in {@link Rule}. The typed
     * convenience accessors on Rule ({@code getCondition()},
     * {@code getThreshold()}, and so on) are views over the same map
     * and exist only for readability in the engine code.
     *
     * <p><b>Contrast with the original design.</b> The initial
     * implementation used a switch on each property's idShort to
     * assign typed fields, which made the parser the bottleneck for
     * any change to a rule's schema. The current design removes that
     * cost entirely: the parser captures everything, and only the
     * engine code that <i>acts on</i> a new property needs to be aware
     * of it.
     *
     * @param collection the {@code <submodelElementCollection>} XML
     *                   element representing one rule (for example
     *                   Rule_1)
     * @return the populated Rule, or null if the collection cannot be
     *         parsed
     */
    private Rule parseRule(Element collection) {
        try {
            String idShort = getDirectChildText(collection, "idShort");
            Element valueEl = getDirectChildElement(collection, "value");
            if (valueEl == null) return null;

            Rule rule = new Rule();
            // The collection's own idShort is stored as a property too, so it
            // is available uniformly via getIdShort()/getAttribute("idShort").
            rule.setAttribute("idShort", idShort);

            NodeList properties = valueEl.getElementsByTagNameNS(AAS_NS, "property");
            for (int i = 0; i < properties.getLength(); i++) {
                Element prop = (Element) properties.item(i);
                String propIdShort = getDirectChildText(prop, "idShort");
                String propValue   = getDirectChildText(prop, "value");
                if (propIdShort != null && !propIdShort.isEmpty()) {
                    rule.setAttribute(propIdShort, propValue);
                }
            }
            return rule;
        } catch (Exception e) {
            log.warn("[AASManager] Failed to parse rule: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Writes a value to a BaSyx Property in a submodel.
     * Called during SYNCHRONIZING and TRIGGERING.
     * Note: database persistence is handled by
     * StateChartExecutor.onDataPush() to avoid duplicates. Do not write
     * to the database here.
     */
    public void writeProperty(String submodelIdShort,
                              String elementIdShort,
                              String value) {
        Map<String, Property> properties = propertyRegistry.get(submodelIdShort);
        if (properties == null) {
            log.warn("[AASManager] Cannot write — submodel not found: {}", submodelIdShort);
            return;
        }

        Property prop = properties.get(elementIdShort);

        if (prop != null) {
            prop.setValue(value);
            log.info("[AASManager] Written: {}.{} = {}",
                submodelIdShort, elementIdShort, value);
        } else {
            // Property doesn't exist yet — create it as a new BaSyx Property
            Property newProp = new Property();
            newProp.setIdShort(elementIdShort.substring(elementIdShort.lastIndexOf('.') + 1));
            newProp.setValue(value);
            properties.put(elementIdShort, newProp);
            log.info("[AASManager] Created new property: {}.{} = {}",
                submodelIdShort, elementIdShort, value);
        }
        // DB write intentionally removed — handled in StateChartExecutor.onDataPush()
    }

    /**
     * Reads a BaSyx Property value from a submodel.
     * Called during DECIDING to evaluate rules against current data.
     */
    public String readProperty(String submodelIdShort, String elementIdShort) {
        Map<String, Property> properties = propertyRegistry.get(submodelIdShort);
        if (properties == null) return null;

        Property prop = properties.get(elementIdShort);
        if (prop == null) return null;

        Object val = prop.getValue();
        return val != null ? val.toString() : null;
    }

    /**
     * Returns all loaded rules.
     * Called during DECIDING.
     */
    public List<Rule> getRules() {
        return rules;
    }

    public void replaceRules(List<Rule> replacement) {
        rules = List.copyOf(replacement);
    }

    /**
     * Recursively registers every property of a submodel under its idShort
     * path, for example "OperationalData.Spindle.Temperature". Properties at
     * the top level keep their bare idShort as their path, so flat submodels such as CPS_SM
     * are addressed exactly as before and nothing changes for the Gateway
     * stream, which pushes bare element names. Collections contribute their
     * idShort as a path segment and are descended into through their value
     * container. The path is the stable key because the AAS metamodel only
     * guarantees idShort uniqueness within the parent collection: two
     * elements named "Value" in different collections of one nested
     * submodel would collide if keyed by their bare name but remain
     * distinct under paths.
     */
    private void registerPropertiesByPath(Element container, String prefix,
                                          Map<String, Property> out) {
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            String local = child.getLocalName();
            if ("property".equals(local)) {
                String id = directChildText(child, "idShort");
                if (id == null || id.isEmpty()) continue;
                Property property = new Property();
                property.setIdShort(id);
                property.setValue(directChildText(child, "value"));
                out.put(prefix + id, property);
            } else if ("submodelElementCollection".equals(local)) {
                String id = directChildText(child, "idShort");
                Element valueEl = getDirectChildElement(child, "value");
                if (id != null && !id.isEmpty() && valueEl != null) {
                    registerPropertiesByPath(valueEl, prefix + id + ".", out);
                }
            }
        }
    }

    /**
     * Text content of a DIRECT child element, or null when absent. Unlike
     * getDirectChildText, this never matches nested descendants, which
     * matters when walking collections whose children carry idShorts of
     * their own.
     */
    private String directChildText(Element parent, String localName) {
        Element el = getDirectChildElement(parent, localName);
        return el != null ? el.getTextContent().trim() : null;
    }

    // ─────────────────────────────────────────
    // DOM HELPERS
    // ─────────────────────────────────────────

    private Element findSubmodelElement(Document doc, String idShort) {
        NodeList submodels = doc.getElementsByTagNameNS(AAS_NS, "submodel");
        for (int i = 0; i < submodels.getLength(); i++) {
            Element sm = (Element) submodels.item(i);
            NodeList ids = sm.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals(idShort)) {
                return sm;
            }
        }
        return null;
    }

    private Element getDirectChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if (localName.equals(child.getLocalName())) return child;
        }
        return null;
    }

    private String getDirectChildText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(AAS_NS, localName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }

    /**
     * Persists all loaded rules to storage, then writes the
     * initialization marker (engine/initialized).
     *
     * The marker records a SHA-256 hash of the AASX bytes the rules were
     * loaded from, alongside a load timestamp and rule count. On the
     * next startup, isStorageValidFor(...) compares the current AASX
     * hash against this stored hash to decide whether the cached rules
     * are still valid, or whether the AASX has changed and the rules
     * must be imported again. See computeHash(...) and
     * isStorageValidFor(...).
     *
     * @param aasxBytes the raw bytes of the AASX the rules were parsed
     *                  from, used to compute the marker hash. May be
     *                  null, in which case the marker falls back to a
     *                  flag with no hash, and the engine will always
     *                  import the rules again on the next startup.
     */
    public void persistRulesToStorage(byte[] aasxBytes) {
        if (storageService == null) {
            log.warn("[AASManager] No storage service available — skipping persistence");
            return;
        }

        for (Rule rule : rules) {
            String key = rulesSubmodelIdShort + "/" + rule.getIdShort();
            // Serialize every attribute the rule carries, not a fixed field
            // list, so properties added in the AASX are persisted too. Values
            // are escaped for JSON quotes and backslashes. The flat map with
            // only one level matches the engine's lightweight JSON style.
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : rule.getAttributes().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(jsonEscape(e.getKey())).append("\":")
                  .append("\"").append(jsonEscape(e.getValue())).append("\"");
            }
            sb.append("}");
            storageService.store(key, sb.toString());
            log.info("[AASManager] Persisted rule to storage: {}", key);
        }

        String aasxHash = (aasxBytes != null) ? computeHash(aasxBytes) : "";
        String marker = String.format(
            "{\"initialized\":true,\"aasxHash\":\"%s\",\"loadedAt\":\"%s\",\"ruleCount\":%d}",
            aasxHash,
            java.time.Instant.now().toString(),
            rules.size()
        );
        storageService.store("engine/initialized", marker);
        log.info("[AASManager] Storage initialized marker written — aasxHash={} ruleCount={}",
            aasxHash.isEmpty() ? "(none)" : aasxHash.substring(0, Math.min(12, aasxHash.length())),
            rules.size());
    }

    /**
     * Checks whether the cached rules in storage are still valid for
     * the given AASX, by comparing a SHA-256 hash of the supplied AASX
     * bytes against the hash recorded in the engine/initialized marker.
     *
     * Returns true when storage is present and the AASX is unchanged
     * (the engine may reuse the cached rules and skip importing them
     * again). Returns false when no marker exists, the marker has no
     * hash, or the AASX content differs (the engine should import the
     * rules again).
     *
     * This turns the initialization marker from a simple boolean flag
     * into a check that is aware of the file's content, so editing the
     * AASX no longer requires manually deleting the storage folder. The
     * engine detects the change itself.
     *
     * @param aasxBytes the raw bytes of the AASX currently being loaded
     */
    public boolean isStorageValidFor(byte[] aasxBytes) {
        if (storageService == null) return false;
        if (!storageService.exists("engine/initialized")) {
            log.info("[AASManager] No initialization marker — storage not valid (first run)");
            return false;
        }

        String marker = storageService.retrieve("engine/initialized");
        String storedHash = readJsonField(marker, "aasxHash");
        if (storedHash == null || storedHash.isEmpty()) {
            log.info("[AASManager] Marker has no AASX hash — treating storage as stale");
            return false;
        }

        String currentHash = computeHash(aasxBytes);
        boolean match = storedHash.equals(currentHash);
        if (match) {
            log.info("[AASManager] AASX unchanged (hash {}…) — cached rules are valid",
                currentHash.substring(0, Math.min(12, currentHash.length())));
        } else {
            log.info("[AASManager] AASX changed — stored hash {}… vs current {}… — re-import needed",
                storedHash.substring(0, Math.min(12, storedHash.length())),
                currentHash.substring(0, Math.min(12, currentHash.length())));
        }
        return match;
    }

    /**
     * Computes a SHA-256 digest of the given bytes, encoded as hex.
     * Used to fingerprint the AASX file so changes can be detected
     * across restarts. Mirrors the checksum approach used for Docker
     * contexts in the wider system.
     */
    private String computeHash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[AASManager] Could not compute AASX hash: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Escapes a string for safe embedding inside a JSON string literal.
     * Handles the two characters that would otherwise break the flat JSON the
     * engine writes: the double quote and the backslash. Null becomes empty.
     */
    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Minimal extractor for a string field from the flat JSON marker.
     * Avoids adding a JSON dependency for a single field read, matching the
     * lightweight parsing style used elsewhere in the engine.
     */
    private String readJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon + 1);
        if (start == -1) return null;
        int end = json.indexOf("\"", start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    public void registerEmptySubmodel(String idShort) {
        // Always clear and recreate so that stale sensor values from a
        // previous startup cycle do not persist into the new session.
        // Without this reset, a second call to /engine/startup (for
        // example after ERROR recovery or manually initializing again)
        // would leave previous CPS_SM values in memory, causing rules to
        // evaluate against data that was never pushed in the current
        // session.
        propertyRegistry.put(idShort, new java.util.HashMap<>());
        log.info("[AASManager] Pre-registered empty submodel: {}", idShort);
    }
}
