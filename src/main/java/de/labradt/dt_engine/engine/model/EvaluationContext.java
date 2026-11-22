package de.labradt.dt_engine.engine.model;

/**
 * Everything a {@link ConditionEvaluator} needs to decide whether a
 * rule's condition is met.
 *
 * <p>This replaces the earlier evaluator signature that took two plain
 * strings (currentValue, threshold), which could only express
 * comparisons with a single input. A context carries:
 * <ul>
 *   <li>the {@link Rule} itself, so an evaluator can read its
 *       threshold, its declared inputs, and any custom property the
 *       rule author added (since Rule is not tied to specific fields,
 *       arbitrary properties are available here);</li>
 *   <li>a {@code currentValue}, resolved in advance, for the rule's
 *       single target element, as a convenience for the simple
 *       operators (this may be null for a rule that declares several
 *       inputs instead of one target element);</li>
 *   <li>a {@link ValueResolver}, so an evaluator can look up any number
 *       of additional element values by name, which is what makes
 *       conditions with several inputs, computed from them,
 *       possible.</li>
 * </ul>
 *
 * <p>The simple operators use {@link #getCurrentValue()} and
 * {@link #getThreshold()} and ignore the rest. An operator with several
 * inputs reads its input list from the rule (for example an "inputs"
 * property) and resolves each one through {@link #resolve(String)}.
 * The convenience getters are safe against nulls: an absent property
 * yields null rather than an error.
 */
public class EvaluationContext {

    private final Rule rule;
    private final String currentValue;
    private final ValueResolver resolver;

    public EvaluationContext(Rule rule, String currentValue, ValueResolver resolver) {
        this.rule = rule;
        this.currentValue = currentValue;
        this.resolver = resolver;
    }

    /** The rule under evaluation. Use it to read inputs, threshold, or any custom property. */
    public Rule getRule() {
        return rule;
    }

    /**
     * The value of the rule's single target element, resolved in
     * advance, or null when the rule declares several inputs instead of
     * one target element.
     */
    public String getCurrentValue() {
        return currentValue;
    }

    /** Convenience: the rule's threshold property (null if absent). */
    public String getThreshold() {
        return rule != null ? rule.getThreshold() : null;
    }

    /**
     * Resolves another element value within the rule's target submodel.
     * Used by operators with several inputs to pull each declared input.
     */
    public String resolve(String elementId) {
        if (resolver == null || rule == null) return null;
        return resolver.resolve(rule.getTargetSubmodel(), elementId);
    }

    /**
     * Resolves an element value in an explicit submodel, for rules whose inputs
     * span more than one submodel.
     */
    public String resolve(String submodelId, String elementId) {
        if (resolver == null) return null;
        return resolver.resolve(submodelId, elementId);
    }
}