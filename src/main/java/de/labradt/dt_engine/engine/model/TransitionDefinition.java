package de.labradt.dt_engine.engine.model;

import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import lombok.Data;

/**
 * One transition declared in the AASX's StateChart_SM submodel, one of
 * the `transitions` list held by {@link StateChartDefinition}. Parsed by
 * {@link StateChartLoader}.
 *
 * A transition fires when {@link StateChartExecutor#fireEvent}
 * is called with an event name matching trigger while the engine is in
 * state from, and guard (matched by exact string in
 * StateChartExecutor.evaluateGuard()) either is null or evaluates to
 * true. action is optional and runs after the departing state's exit
 * action and before the arriving state's entry action.
 */
@Data
public class TransitionDefinition {
    private String from;
    private String to;
    private String trigger;
    private String guard;
    private StateAction action;
}
