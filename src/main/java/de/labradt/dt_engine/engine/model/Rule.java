package de.labradt.dt_engine.engine.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents one rule loaded from the Rules_SM submodel in the AASX file.
 * Each rule maps to one submodelElementCollection in the XML.
 *
 * <p><b>Not tied to specific fields.</b> Rather than declaring a fixed
 * set of typed fields, a Rule stores every property it was parsed with
 * in an attribute map keyed by the property's idShort. Any idShort
 * present in the AASX is captured automatically. Adding, removing or
 * renaming a rule property is done purely in the AASX, with no change
 * to this class or to the parser.
 *
 * <p>The engine's core fields are exposed as convenience accessors that
 * simply read from the map. These keep the executor code readable and
 * safe against nulls. A field that is absent from the AASX returns null
 * (or 0 for cooldownMs) instead of failing. Properties the engine does
 * not interpret (for example severity, unit, owner) are still captured
 * and reachable through {@link #getAttribute(String)}, so they can be
 * persisted, logged, forwarded to a service, or shown on the dashboard
 * without any engine logic.
 *
 * <p>Note the distinction this draws: capturing an arbitrary property
 * is driven entirely by the data here, but acting on a new
 * <i>condition</i> or <i>action</i> still requires the corresponding
 * behavioral extension point (see the condition evaluator registry in
 * StateChartExecutor). This class removes the limitation in the parser.
 * It does not, and cannot, invent behavior for fields a user defines at
 * runtime.
 */
public class Rule {

    /**
     * All properties of the rule, keyed by idShort, in document order.
     * This is the single source of truth; the typed accessors below are views
     * over this map.
     */
    private final Map<String, String> attributes = new LinkedHashMap<>();

    /**
     * Stores a property value under its idShort. Called by the parser for every
     * property found in the rule's submodelElementCollection, regardless of name.
     */
    public void setAttribute(String idShort, String value) {
        attributes.put(idShort, value);
    }

    /**
     * Returns the raw value of any property by its idShort, or null if absent.
     * This is the generic accessor for properties the engine does not model
     * with a dedicated getter.
     */
    public String getAttribute(String idShort) {
        return attributes.get(idShort);
    }

    /**
     * Returns the full attribute map. It is not technically
     * unmodifiable, but callers are expected to treat it as read only.
     * Used by persistence to serialize every property without
     * hardcoding field names.
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    // ─────────────────────────────────────────
    // Convenience accessors for engine core fields.
    // Each one reads safely from the attribute map and returns null
    // instead of throwing when the property is absent.
    // ─────────────────────────────────────────

    public String getIdShort()        { return attributes.get("idShort"); }
    public String getTargetSubmodel() { return attributes.get("targetSubmodel"); }
    public String getTargetElement()  { return attributes.get("targetElement"); }
    public String getCondition()      { return attributes.get("condition"); }
    public String getThreshold()      { return attributes.get("threshold"); }
    public String getAction()         { return attributes.get("action"); }

    /**
     * Returns the action field split by comma, allowing a single rule to
     * trigger multiple services at the same time. A rule with a single
     * service (no comma) returns a list with one element. An absent or
     * blank action field returns an empty list.
     */
    public List<String> getActions() {
        String action = attributes.get("action");
        if (action == null || action.isBlank()) return List.of();
        return Arrays.stream(action.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getDescription()    { return attributes.get("description"); }

    /**
     * Returns cooldownMs as a long, defaulting to 0 when the property is
     * absent or not numeric, so a missing cooldown never breaks
     * evaluation.
     */
    public long getCooldownMs() {
        String raw = attributes.get("cooldownMs");
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "Rule" + attributes;
    }
}
