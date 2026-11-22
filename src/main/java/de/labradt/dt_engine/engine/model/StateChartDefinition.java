package de.labradt.dt_engine.engine.model;

import de.labradt.dt_engine.engine.aas.StateChartLoader;
import lombok.Data;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The whole state chart parsed out of one AASX, built once by
 * {@link StateChartLoader#loadStateChart}
 * at startup and held for the life of the process by StateChartExecutor.
 * This is a plain snapshot that is never mutated in practice: nothing in
 * the engine changes its lists after loading, a changed AASX is picked
 * up by building a new one on the next startup, not by editing this
 * object.
 *
 * The two lookup methods below are the only behavior here, and both are
 * instance methods, not static, since they read this particular chart's
 * own lists.
 */
@Data
public class StateChartDefinition {
    private List<StateDefinition> states;
    private List<TransitionDefinition> transitions;
    private String initialState;

    /** The state with this name, or null if the chart declares no such state. */
    public StateDefinition getState(String name) {
        return states.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst().orElse(null);
    }

    /**
     * Every transition whose from matches stateName, in declaration
     * order. Called by StateChartExecutor.fireEvent() to find the
     * candidate transitions for the current state before matching one
     * by trigger and guard.
     */
    public List<TransitionDefinition> getTransitionsFrom(String stateName) {
        return transitions.stream()
            .filter(t -> t.getFrom().equals(stateName))
            .collect(Collectors.toList());
    }
}