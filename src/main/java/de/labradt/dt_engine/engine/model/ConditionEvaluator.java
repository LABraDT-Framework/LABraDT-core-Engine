package de.labradt.dt_engine.engine.model;

import de.labradt.dt_engine.engine.StateChartExecutor;

/**
 * Behavioral extension point for rule conditions.
 *
 * <p>A ConditionEvaluator interprets one condition type (for example
 * "greater_than", "out_of_bounds", "wear_average") and decides whether
 * the condition is met. The engine holds a registry of these keyed by
 * condition name. Adding a new operator means registering a new
 * evaluator, not editing a switch statement.
 *
 * <p>An evaluator receives an {@link EvaluationContext} rather than
 * bare strings. The context exposes the rule's single value, resolved
 * in advance, and its threshold for simple comparisons, and crucially
 * also the rule itself plus a value resolver, so an operator can read
 * several declared inputs and compute over them. This is what lets
 * conditions be calculated to suit a particular machine: the rule names
 * whatever inputs that machine exposes, and the evaluator combines them
 * however the author specifies.
 *
 * <p>This is the counterpart to the Rule parser, which is not tied to
 * specific fields. The parser removes the limit on which property
 * NAMES a rule may carry. This interface removes the limit on which
 * condition BEHAVIORS the engine can apply, by making each behavior an
 * independent, separately registrable unit. A condition name appearing
 * in the AASX still needs a matching evaluator here, since the engine
 * cannot infer the meaning of an operator a user invents, but the set
 * of operators is open for extension and closed for modification.
 *
 * <p>Implementations must tolerate null values and must not throw on
 * malformed input. They should return false and let the caller log, so
 * one bad rule never aborts evaluation of the others.
 *
 * <p>The five built in implementations (greater_than, less_than, equals,
 * out_of_bounds, wear_average) live as lambdas inside
 * {@link StateChartExecutor#registerConditionEvaluators()},
 * which runs once at startup. Nothing here is static: each registered
 * evaluator is a live instance held in a
 * {@code Map<String, ConditionEvaluator>}, and a new one can be added at
 * any time, including after startup, through the public
 * {@link StateChartExecutor#registerCondition(String, ConditionEvaluator)}
 * method.
 */
@FunctionalInterface
public interface ConditionEvaluator {

    /**
     * @param ctx the evaluation context: the rule, its single value
     *            resolved in advance, its threshold, and a resolver for
     *            additional inputs
     * @return true if the condition is met and the rule's action should fire
     */
    boolean evaluate(EvaluationContext ctx);
}