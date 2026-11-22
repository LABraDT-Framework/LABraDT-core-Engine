package de.labradt.dt_engine.engine.model;

import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import lombok.Data;

/**
 * One state declared in the AASX's StateChart_SM submodel, one of the
 * `states` list held by {@link StateChartDefinition}. Parsed by
 * {@link StateChartLoader}.
 *
 * entryAction fires when the engine arrives in this state, exitAction
 * fires when it leaves, both dispatched by
 * {@link StateChartExecutor#fireEvent}
 * independent of whichever transition triggered the move. Either can be
 * null if the AASX declares no action for that state. Exactly one state
 * across the whole chart should have isInitial set, since
 * StateChartLoader picks that state as the engine's starting state.
 */
@Data
public class StateDefinition {
    private String name;
    private boolean isInitial;
    private StateAction entryAction;
    private StateAction exitAction;
}
