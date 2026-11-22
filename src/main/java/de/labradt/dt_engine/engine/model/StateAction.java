package de.labradt.dt_engine.engine.model;

import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import lombok.Data;
import java.util.Map;

/**
 * One action declared in the AASX, parsed by {@link StateChartLoader}
 * and dispatched by {@link StateChartExecutor#executeAction}.
 * A StateAction can be a transition's own action, or a state's entry or
 * exit action (see {@link StateDefinition}).
 *
 * actionType names one of the fixed set of actions StateChartExecutor's
 * dispatch switch understands (SERVICE_CALL, RULE_EVALUATE, AAS_WRITE and
 * so on). component is a label from the AASX, logged but otherwise not
 * interpreted. params holds whatever key and value pairs the action
 * declared in its own AASX value block, read by the specific handler
 * for that actionType.
 */
@Data
public class StateAction {
    private String actionType;
    private String component;
    private Map<String, String> params;
}