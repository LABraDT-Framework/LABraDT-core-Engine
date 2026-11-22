package de.labradt.dt_engine.engine.model;

/**
 * Looks up the current value of a submodel element by id.
 *
 * <p>Passed to condition evaluators through {@link EvaluationContext},
 * so that an evaluator can read as many inputs as it needs, not just
 * the single target element the evaluation loop fetches in advance.
 * This is what enables rules with several inputs, computed from them
 * (for example a wear estimate over several axes): the rule names its
 * inputs in the AASX, and the evaluator resolves each one through this
 * interface at evaluation time.
 *
 * <p>The engine supplies an implementation that reads the latest value
 * from the database first and falls back to the AAS model kept in
 * memory, mirroring the lookup the evaluation loop performs for rules
 * with a single target. Returns null when no value is available for
 * the given element.
 */
@FunctionalInterface
public interface ValueResolver {

    /**
     * @param submodelId the submodel idShort (e.g. "CPS_SM")
     * @param elementId  the element idShort (e.g. "XAxisLoad")
     * @return the latest value as a string, or null if none is available
     */
    String resolve(String submodelId, String elementId);
}
